package app.peloskin.domain


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

/** Everything [RideTracker] needs to pick a ride back up. */
class RideCheckpoint(
    /** Which boot of the tablet this was saved in. */
    val bootId: Int,
    val savedAt: Long, val startedAt: Long, val lastPedalAt: Long,
    val elapsed: Double, val elapsedAtLastPedal: Double, val moving: Double,
    val powerSum: Double, val cadenceSum: Double, val resistanceSum: Double,
    val energyJ: Double, val distanceMi: Double,
    val bestPower: Float, val bestCadence: Float, val bestResistance: Float,
    val seconds: Int, val best20: Double?, val ring: DoubleArray,
)

data class RideSummary(
    val startedAt: Long,
    val durationSec: Int,
    val avgPower: Int,
    val totalKj: Int,
    val best20Watts: Int?,
)

/**
 * Turns the bike's readings into ride totals. A ride starts when you pedal and ends after
 * [idleEndMs] without pedaling, or when [finish] is called; the numbers stay on screen until
 * the next ride starts.
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
    private val ring = DoubleArray(RingSize)
    private var seconds = 0
    private var best20: Double? = null

    fun onSample(now: Long, sample: BikeSample) {
        val power = sample.power.coerceAtLeast(0f)
        val cadence = sample.cadence.coerceAtLeast(0f)
        val resistance = sample.resistance
        val speed = sample.speedMph.coerceAtLeast(0f)
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
        if (now - lastPedalAt >= idleEndMs) finish()
    }

    /** Ends the ride now, recording it if it's long enough (for example, when the skin is stopped). */
    fun finish() {
        if (!snapshot.riding) return
        snapshot = snapshot.copy(riding = false)
        // Only time spent pedaling counts, so a bump of the pedals is never a ride.
        if (moving >= minRideSec) onRideFinished(summary())
    }

    private fun summary() = RideSummary(
        startedAt = startedAt,
        durationSec = elapsedAtLastPedal.toInt(),
        avgPower = avg(powerSum).toInt(),
        totalKj = (energyJ / 1000).toInt(),
        best20Watts = best20?.toInt(),
    )

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
     * Ride state, so a ride survives Android killing and restarting the service.
     * `now` is the monotonic clock and [bootId] identifies the current boot; together they
     * let [resume] reject a checkpoint from before a reboot.
     */
    fun checkpoint(now: Long, bootId: Int): RideCheckpoint? {
        if (!snapshot.riding) return null
        return RideCheckpoint(
            bootId = bootId, savedAt = now, startedAt = startedAt, lastPedalAt = lastPedalAt,
            elapsed = elapsed, elapsedAtLastPedal = elapsedAtLastPedal, moving = moving,
            powerSum = powerSum, cadenceSum = cadenceSum, resistanceSum = resistanceSum,
            energyJ = energyJ, distanceMi = distanceMi,
            bestPower = bestPower, bestCadence = bestCadence, bestResistance = bestResistance,
            seconds = seconds, best20 = best20, ring = ring.copyOf(),
        )
    }

    /**
     * @return true if the saved ride was still in progress and has been resumed. If not,
     * record [summaryOf] the checkpoint instead, so the ride isn't lost.
     */
    fun resume(c: RideCheckpoint, now: Long, bootId: Int): Boolean {
        val sameBoot = c.bootId == bootId && c.savedAt <= now
        if (!sameBoot || now - c.lastPedalAt >= idleEndMs || c.ring.size != ring.size) return false
        startedAt = c.startedAt
        lastSampleAt = NoSample
        lastPedalAt = c.lastPedalAt
        elapsed = c.elapsed; elapsedAtLastPedal = c.elapsedAtLastPedal; moving = c.moving
        powerSum = c.powerSum; cadenceSum = c.cadenceSum; resistanceSum = c.resistanceSum
        energyJ = c.energyJ; distanceMi = c.distanceMi
        bestPower = c.bestPower; bestCadence = c.bestCadence; bestResistance = c.bestResistance
        seconds = c.seconds
        best20 = c.best20
        c.ring.copyInto(ring)
        snapshot = RideSnapshot(
            riding = true,
            avgPower = avg(powerSum), avgCadence = avg(cadenceSum), avgResistance = avg(resistanceSum),
            bestPower = bestPower, bestCadence = bestCadence, bestResistance = bestResistance,
            totalKj = (energyJ / 1000).toFloat(), distanceMi = distanceMi.toFloat(),
            calories = (energyJ / JoulesPerKcal / Efficiency).toFloat(),
            elapsedSec = elapsedAtLastPedal.toInt(), best20Watts = best20?.toFloat(),
        )
        return true
    }

    companion object {
        /** Length of the rolling window for best 20 minutes, plus one, in seconds. */
        const val RingSize = 20 * 60 + 1

        /** The finished ride a checkpoint describes, or null if it's too short to keep. */
        fun summaryOf(c: RideCheckpoint, minRideSec: Int = 5 * 60): RideSummary? {
            if (c.moving < minRideSec) return null
            return RideSummary(
                startedAt = c.startedAt,
                durationSec = c.elapsedAtLastPedal.toInt(),
                avgPower = (c.powerSum / c.moving).toInt(),
                totalKj = (c.energyJ / 1000).toInt(),
                best20Watts = c.best20?.toInt(),
            )
        }
        private const val Window = 20 * 60
        private const val NoSample = Long.MIN_VALUE
        // Standard estimate: mechanical work over ~22% body efficiency.
        private const val JoulesPerKcal = 4184.0
        private const val Efficiency = 0.22
    }
}
