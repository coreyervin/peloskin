package app.peloskin.ride

import app.peloskin.feed.BikeSample
import org.json.JSONArray
import org.json.JSONObject

/** What the skin draws, recomputed on every sample. */
data class RideSnapshot(
    val riding: Boolean = false,
    val power: Float = 0f,
    val cadence: Float = 0f,
    val resistance: Float = 0f,
    val speedMph: Float = 0f,
    val avgPower: Float = 0f,
    val avgCadence: Float = 0f,
    val avgResistance: Float = 0f,
    val bestPower: Float = 0f,
    val bestCadence: Float = 0f,
    val bestResistance: Float = 0f,
    val totalKj: Float = 0f,
    val distanceMi: Float = 0f,
    val calories: Float = 0f,
    /** Ride length up to the last pedal stroke. */
    val elapsedSec: Int = 0,
    /** Best 20-minute average power so far, once the ride is 20 minutes long. */
    val best20Watts: Float? = null,
)

data class RideSummary(
    val startedAt: Long,
    val durationSec: Int,
    val avgPower: Int,
    val totalKj: Int,
    val best20Watts: Int?,
)

/**
 * Turns the 1 Hz feed into ride totals. A ride starts when you pedal and ends after
 * [idleEndMs] without pedaling; the numbers stay on screen until the next ride starts.
 *
 * `now` values passed in must come from a monotonic clock (SystemClock.elapsedRealtime);
 * [wallClock] is only used to timestamp the ride for history.
 */
class RideTracker(
    private val idleEndMs: Long = 5 * 60_000L,
    private val minRideSec: Int = 5 * 60,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val onRideFinished: (RideSummary) -> Unit = {},
) {
    var snapshot = RideSnapshot()
        private set

    private var startedAt = 0L
    private var lastSampleAt = NoSample
    private var lastPedalAt = 0L
    private var elapsed = 0.0
    private var elapsedAtLastPedal = 0.0
    private var moving = 0.0
    private var powerSum = 0.0
    private var cadenceSum = 0.0
    private var resistanceSum = 0.0
    private var energyJ = 0.0
    private var distanceMi = 0.0
    private var bestPower = 0f
    private var bestCadence = 0f
    private var bestResistance = 0f

    // Cumulative energy at each whole second, in a ring big enough for one 20-minute
    // window, so a long ride doesn't keep growing memory.
    private val ring = DoubleArray(Window + 1)
    private var seconds = 0
    private var best20: Double? = null

    fun onSample(now: Long, sample: BikeSample) {
        val power = (sample.power ?: 0f).coerceAtLeast(0f)
        val cadence = (sample.cadence ?: 0f).coerceAtLeast(0f)
        val resistance = sample.resistance ?: snapshot.resistance
        val speed = (sample.speedMph ?: 0f).coerceAtLeast(0f)
        val pedaling = cadence > 0f

        if (snapshot.riding) checkIdle(now)
        if (!snapshot.riding && pedaling) begin(now)

        if (!snapshot.riding) {
            // Between rides: show live numbers, keep the last ride's totals.
            snapshot = snapshot.copy(power = power, cadence = cadence, resistance = resistance, speedMph = speed)
            lastSampleAt = now
            return
        }

        val dt = if (lastSampleAt == NoSample) 0.0 else ((now - lastSampleAt) / 1000.0).coerceIn(0.0, 3.0)
        lastSampleAt = now

        elapsed += dt
        energyJ += power * dt
        distanceMi += speed * dt / 3600.0
        if (pedaling) {
            lastPedalAt = now
            elapsedAtLastPedal = elapsed
            moving += dt
            powerSum += power * dt
            cadenceSum += cadence * dt
            resistanceSum += resistance * dt
        }
        bestPower = maxOf(bestPower, power)
        bestCadence = maxOf(bestCadence, cadence)
        bestResistance = maxOf(bestResistance, resistance)

        while (seconds <= elapsed.toInt()) {
            ring[seconds % ring.size] = energyJ
            if (seconds >= Window) {
                val avg = (energyJ - ring[(seconds - Window) % ring.size]) / Window
                if (best20 == null || avg > best20!!) best20 = avg
            }
            seconds++
        }

        snapshot = RideSnapshot(
            riding = true,
            power = power,
            cadence = cadence,
            resistance = resistance,
            speedMph = speed,
            avgPower = avg(powerSum),
            avgCadence = avg(cadenceSum),
            avgResistance = avg(resistanceSum),
            bestPower = bestPower,
            bestCadence = bestCadence,
            bestResistance = bestResistance,
            totalKj = (energyJ / 1000).toFloat(),
            distanceMi = distanceMi.toFloat(),
            calories = (energyJ / JoulesPerKcal / Efficiency).toFloat(),
            elapsedSec = elapsedAtLastPedal.toInt(),
            best20Watts = best20?.toFloat(),
        )
    }

    /** Call periodically so a ride still ends if the feed stops entirely. */
    fun tick(now: Long) {
        if (snapshot.riding) checkIdle(now)
    }

    private fun checkIdle(now: Long) {
        if (now - lastPedalAt < idleEndMs) return
        snapshot = snapshot.copy(riding = false)
        // Only time spent pedaling counts, so a bump of the pedals is never a ride.
        if (moving >= minRideSec) {
            onRideFinished(
                RideSummary(
                    startedAt = startedAt,
                    durationSec = elapsedAtLastPedal.toInt(),
                    avgPower = snapshot.avgPower.toInt(),
                    totalKj = snapshot.totalKj.toInt(),
                    best20Watts = best20?.toInt(),
                )
            )
        }
    }

    private fun begin(now: Long) {
        startedAt = wallClock()
        lastSampleAt = NoSample
        lastPedalAt = now
        elapsed = 0.0; elapsedAtLastPedal = 0.0; moving = 0.0
        powerSum = 0.0; cadenceSum = 0.0; resistanceSum = 0.0
        energyJ = 0.0; distanceMi = 0.0
        bestPower = 0f; bestCadence = 0f; bestResistance = 0f
        seconds = 0
        best20 = null
        snapshot = RideSnapshot(riding = true)
    }

    private fun avg(sum: Double) = if (moving > 0) (sum / moving).toFloat() else 0f

    // ------------------------------------------------------------- checkpoint

    /**
     * Ride state as JSON, so a ride survives Android killing and restarting the service.
     * `now` is the monotonic clock; it is used to reject a checkpoint from before a reboot.
     */
    fun checkpoint(now: Long): String? {
        if (!snapshot.riding) return null
        return JSONObject().apply {
            put("savedAt", now)
            put("startedAt", startedAt)
            put("lastSampleAt", lastSampleAt)
            put("lastPedalAt", lastPedalAt)
            put("elapsed", elapsed); put("elapsedAtLastPedal", elapsedAtLastPedal); put("moving", moving)
            put("powerSum", powerSum); put("cadenceSum", cadenceSum); put("resistanceSum", resistanceSum)
            put("energyJ", energyJ); put("distanceMi", distanceMi)
            put("bestPower", bestPower.toDouble()); put("bestCadence", bestCadence.toDouble())
            put("bestResistance", bestResistance.toDouble())
            put("seconds", seconds)
            best20?.let { put("best20", it) }
            put("ring", JSONArray(ring.toList()))
        }.toString()
    }

    /** @return true if the saved ride was still in progress and has been resumed. */
    fun resume(json: String, now: Long): Boolean = try {
        val o = JSONObject(json)
        val savedAt = o.getLong("savedAt")
        val pedalAt = o.getLong("lastPedalAt")
        // A monotonic time in the future means the tablet rebooted since the save.
        if (savedAt > now || now - pedalAt >= idleEndMs) {
            false
        } else {
            startedAt = o.getLong("startedAt")
            lastSampleAt = NoSample
            lastPedalAt = pedalAt
            elapsed = o.getDouble("elapsed"); elapsedAtLastPedal = o.getDouble("elapsedAtLastPedal")
            moving = o.getDouble("moving")
            powerSum = o.getDouble("powerSum"); cadenceSum = o.getDouble("cadenceSum")
            resistanceSum = o.getDouble("resistanceSum")
            energyJ = o.getDouble("energyJ"); distanceMi = o.getDouble("distanceMi")
            bestPower = o.getDouble("bestPower").toFloat(); bestCadence = o.getDouble("bestCadence").toFloat()
            bestResistance = o.getDouble("bestResistance").toFloat()
            seconds = o.getInt("seconds")
            best20 = if (o.has("best20")) o.getDouble("best20") else null
            val saved = o.getJSONArray("ring")
            for (i in ring.indices) ring[i] = saved.optDouble(i, 0.0)
            snapshot = RideSnapshot(
                riding = true,
                avgPower = avg(powerSum), avgCadence = avg(cadenceSum), avgResistance = avg(resistanceSum),
                bestPower = bestPower, bestCadence = bestCadence, bestResistance = bestResistance,
                totalKj = (energyJ / 1000).toFloat(), distanceMi = distanceMi.toFloat(),
                calories = (energyJ / JoulesPerKcal / Efficiency).toFloat(),
                elapsedSec = elapsedAtLastPedal.toInt(), best20Watts = best20?.toFloat(),
            )
            true
        }
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val Window = 20 * 60
        const val NoSample = Long.MIN_VALUE
        // Same estimate Grupetto uses: mechanical work over ~22% body efficiency.
        const val JoulesPerKcal = 4184.0
        const val Efficiency = 0.22
    }
}
