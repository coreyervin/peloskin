package app.peloskin.domain

import kotlin.math.ceil
import kotlin.math.floor

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
    /** Best average watts this ride for each of [PersonalRecords.EffortWindows] reached so far. */
    val bestEfforts: Map<Int, Int> = emptyMap(),
)

/** A finished ride, as kept in history. */
data class RideSummary(
    val startedAt: Long,
    val durationSec: Int,
    val avgPower: Int,
    val totalKj: Int,
    val best20Watts: Int?,
    val avgCadence: Int = 0,
    val avgResistance: Int = 0,
    val distanceMi: Float = 0f,
    val calories: Int = 0,
    /** Best average watts by effort length in seconds. */
    val bestEfforts: Map<Int, Int> = emptyMap(),
    /** Seconds in each of the 7 power zones, if the rider had an FTP. */
    val zoneSeconds: List<Int>? = null,
    /** The FTP the zones were worked out with. */
    val ftp: Int? = null,
)

/** The bulky part of a ride, kept in its own file: average output for each 5 seconds. */
data class RideDetail(val startedAt: Long, val powerEvery5s: List<Int>) {
    companion object {
        const val StepSec = 5
    }
}

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
    val efforts: DoubleArray = DoubleArray(PersonalRecords.EffortWindows.size) { -1.0 },
    /** Average watts for each whole second of the ride so far. */
    val watts: IntArray = IntArray(0),
    /** Whether you pedaled during each of those seconds. */
    val pedaled: BooleanArray = BooleanArray(0),
    /** Whether you'd pedaled in the second still in progress. */
    val partialPedaled: Boolean = false,
)

/**
 * Turns the bike's readings into ride totals. A ride starts when you pedal and ends after
 * [idleEndMs] without pedaling, or when [finish] is called; the numbers stay on screen until
 * the next ride starts.
 *
 * `now` values passed in must come from a monotonic clock (SystemClock.elapsedRealtime);
 * [wallClock] is only used to timestamp the ride for history. [ftp] is the FTP of the rider
 * the ride will be credited to, read when the ride ends, for time in zones.
 */
class RideTracker(
    private val idleEndMs: Long = 5 * 60_000L,
    private val minRideSec: Int = 5 * 60,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val ftp: () -> Int? = { null },
    private val onRideFinished: (RideSummary, RideDetail) -> Unit = { _, _ -> },
) {
    var snapshot = RideSnapshot()
        private set

    /** Whether the ride in progress has enough pedaling to be saved if it ended now. */
    val longEnough: Boolean
        get() = moving >= minRideSec

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

    // Cumulative energy at each whole second, in a ring big enough for the longest effort
    // window, so the live best efforts don't need the whole ride.
    private val ring = DoubleArray(RingSize)
    private var seconds = 0
    private var best20: Double? = null
    private val efforts = DoubleArray(Windows.size) { -1.0 }
    private var effortsMap: Map<Int, Int> = emptyMap()

    // One entry per whole second: its average watts and whether you pedaled in it. The
    // graph and time in zones are worked out from these when the ride ends.
    private val watts = IntList()
    private val pedaled = BoolList()
    private var partialPedaled = false
    private var waitForStop = false

    fun onSample(now: Long, sample: BikeSample) {
        val power = sample.power.coerceAtLeast(0f)
        val cadence = sample.cadence.coerceAtLeast(0f)
        val resistance = sample.resistance
        val speed = sample.speedMph.coerceAtLeast(0f)
        val pedaling = cadence > 0f

        if (snapshot.riding) checkIdle(now)
        // After you end a ride yourself, wait until you've stopped pedaling before counting a
        // new one, so a cool-down spin doesn't start a ride straight away.
        if (!pedaling) waitForStop = false
        if (!snapshot.riding && pedaling && !waitForStop) begin(now)

        if (!snapshot.riding) {
            // Between rides: show live numbers, keep the last ride's totals.
            snapshot = snapshot.copy(power = power, cadence = cadence, resistance = resistance, speedMph = speed)
            lastSampleAt = now
            return
        }

        val dt = if (lastSampleAt == NoSample) 0.0 else ((now - lastSampleAt) / 1000.0).coerceIn(0.0, 3.0)
        lastSampleAt = now

        val elapsedBefore = elapsed
        val energyBefore = energyJ
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

        // Fill in every whole second this sample covers. The sample's power was steady over
        // its span, so energy at each second boundary is interpolated rather than lumped
        // into the first second.
        while (seconds <= elapsed.toInt()) {
            val e = if (dt <= 0.0 || seconds <= elapsedBefore) energyJ
            else energyBefore + (energyJ - energyBefore) * ((seconds - elapsedBefore) / dt)
            ring[seconds % ring.size] = e
            if (seconds > 0) {
                watts += (e - ring[(seconds - 1) % ring.size]).toInt()
                pedaled += partialPedaled || pedaling
                partialPedaled = false
                onSecond(seconds)
            }
            seconds++
        }
        if (elapsed > floor(elapsed)) partialPedaled = partialPedaled || pedaling

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
            bestEfforts = effortsMap,
        )
    }

    /** Called once for each whole second of the ride, with the ring filled up to [s]. */
    private fun onSecond(s: Int) {
        var changed = false
        Windows.forEachIndexed { i, w ->
            if (s >= w) {
                val avg = (ring[s % ring.size] - ring[(s - w) % ring.size]) / w
                if (avg > efforts[i]) { efforts[i] = avg; changed = true }
            }
        }
        if (changed) {
            effortsMap = effortsToMap(efforts)
            best20 = efforts[Windows.indexOf(Window20)].takeIf { it >= 0 }
        }
    }

    /** Call periodically so a ride still ends if the feed stops entirely. */
    fun tick(now: Long) {
        if (snapshot.riding) checkIdle(now)
    }

    private fun checkIdle(now: Long) {
        if (now - lastPedalAt >= idleEndMs) finish()
    }

    /**
     * Ends the ride now, recording it if it's long enough (for example, when the skin is stopped).
     * [byRider] is true when you ended it yourself, while you might still be pedaling.
     */
    fun finish(byRider: Boolean = false) {
        if (!snapshot.riding) return
        if (byRider) waitForStop = true
        snapshot = snapshot.copy(riding = false)
        // Only time spent pedaling counts, so a bump of the pedals is never a ride.
        if (moving >= minRideSec) {
            val (summary, detail) = build(
                startedAt, elapsedAtLastPedal, moving, powerSum, cadenceSum, resistanceSum, energyJ, distanceMi,
                efforts, watts.toArray(), pedaled.toArray(), ftp(),
            )
            onRideFinished(summary, detail)
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
        efforts.fill(-1.0)
        effortsMap = emptyMap()
        watts.clear()
        pedaled.clear()
        partialPedaled = false
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
            efforts = efforts.copyOf(), watts = watts.toArray(), pedaled = pedaled.toArray(),
            partialPedaled = partialPedaled,
        )
    }

    /**
     * @return true if the saved ride was still in progress and has been resumed. If not,
     * record [summaryOf] the checkpoint instead, so the ride isn't lost.
     */
    fun resume(c: RideCheckpoint, now: Long, bootId: Int): Boolean {
        val sameBoot = c.bootId == bootId && c.savedAt <= now
        val shapeOk = c.ring.size == ring.size && c.efforts.size == efforts.size
        if (!sameBoot || now - c.lastPedalAt >= idleEndMs || !shapeOk) return false
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
        c.efforts.copyInto(efforts)
        effortsMap = effortsToMap(efforts)
        watts.clear(); watts.addAll(c.watts)
        pedaled.clear(); pedaled.addAll(c.pedaled)
        partialPedaled = c.partialPedaled
        snapshot = RideSnapshot(
            riding = true,
            avgPower = avg(powerSum), avgCadence = avg(cadenceSum), avgResistance = avg(resistanceSum),
            bestPower = bestPower, bestCadence = bestCadence, bestResistance = bestResistance,
            totalKj = (energyJ / 1000).toFloat(), distanceMi = distanceMi.toFloat(),
            calories = (energyJ / JoulesPerKcal / Efficiency).toFloat(),
            elapsedSec = elapsedAtLastPedal.toInt(), best20Watts = best20?.toFloat(),
            bestEfforts = effortsMap,
        )
        return true
    }

    companion object {
        /** Longest effort window, plus one, in seconds. */
        const val RingSize = 20 * 60 + 1

        /**
         * The finished ride a checkpoint describes, or null if it's too short to keep.
         * [ftp] is the FTP of the rider it's credited to.
         */
        fun summaryOf(c: RideCheckpoint, ftp: Int?, minRideSec: Int = 5 * 60): Pair<RideSummary, RideDetail>? {
            if (c.moving < minRideSec) return null
            return build(
                c.startedAt, c.elapsedAtLastPedal, c.moving, c.powerSum, c.cadenceSum, c.resistanceSum,
                c.energyJ, c.distanceMi, c.efforts, c.watts, c.pedaled, ftp,
            )
        }

        private fun build(
            startedAt: Long, elapsedAtLastPedal: Double, moving: Double,
            powerSum: Double, cadenceSum: Double, resistanceSum: Double, energyJ: Double, distanceMi: Double,
            efforts: DoubleArray, watts: IntArray, pedaled: BooleanArray, ftp: Int?,
        ): Pair<RideSummary, RideDetail> {
            fun avg(sum: Double) = if (moving > 0) (sum / moving).toInt() else 0
            // Everything after the last pedal stroke is the idle tail, not part of the ride.
            val n = minOf(ceil(elapsedAtLastPedal).toInt(), watts.size, pedaled.size)
            val rider = ftp?.takeIf { it > 0 }
            val zones = rider?.let {
                val z = IntArray(PowerZones.Count)
                for (i in 0 until n) {
                    // Pauses in the middle of a ride count as zone 1, as an easy spin would.
                    val zone = if (pedaled[i]) PowerZones.zone(watts[i].toFloat(), it) else 1
                    z[zone - 1]++
                }
                z.toList().takeIf { list -> list.sum() > 0 }
            }
            val graph = (0 until n / RideDetail.StepSec).map { b ->
                (b * RideDetail.StepSec until (b + 1) * RideDetail.StepSec).sumOf { watts[it] } / RideDetail.StepSec
            }
            val bests = effortsToMap(efforts)
            val summary = RideSummary(
                startedAt = startedAt,
                durationSec = elapsedAtLastPedal.toInt(),
                avgPower = avg(powerSum),
                totalKj = (energyJ / 1000).toInt(),
                best20Watts = bests[Window20],
                avgCadence = avg(cadenceSum),
                avgResistance = avg(resistanceSum),
                distanceMi = distanceMi.toFloat(),
                calories = (energyJ / JoulesPerKcal / Efficiency).toInt(),
                bestEfforts = bests,
                zoneSeconds = zones,
                ftp = zones?.let { rider },
            )
            return summary to RideDetail(startedAt, graph)
        }

        private fun effortsToMap(efforts: DoubleArray): Map<Int, Int> =
            Windows.indices.filter { efforts[it] >= 0 }.associate { Windows[it] to efforts[it].toInt() }

        private val Windows = PersonalRecords.EffortWindows
        private const val Window20 = 20 * 60
        private const val NoSample = Long.MIN_VALUE
        // Standard estimate: mechanical work over ~22% body efficiency.
        private const val JoulesPerKcal = 4184.0
        private const val Efficiency = 0.22
    }
}

/** A growable list of ints without boxing, for one entry per second of a long ride. */
private class IntList {
    private var data = IntArray(1024)
    var size = 0
        private set

    operator fun plusAssign(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    fun addAll(values: IntArray) = values.forEach { this += it }
    fun clear() { size = 0 }
    fun toArray(): IntArray = data.copyOf(size)
}

private class BoolList {
    private var data = BooleanArray(1024)
    var size = 0
        private set

    operator fun plusAssign(v: Boolean) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    fun addAll(values: BooleanArray) = values.forEach { this += it }
    fun clear() { size = 0 }
    fun toArray(): BooleanArray = data.copyOf(size)
}
