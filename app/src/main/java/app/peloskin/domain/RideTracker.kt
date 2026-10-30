package app.peloskin.domain

import kotlin.math.ceil

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
    val graph: IntArray = IntArray(0),
    val zones: IntArray = IntArray(PowerZones.Count),
    val pendingIdle: Int = 0,
    val ftp: Int? = null,
)

/**
 * Turns the bike's readings into ride totals. A ride starts when you pedal and ends after
 * [idleEndMs] without pedaling, or when [finish] is called; the numbers stay on screen until
 * the next ride starts.
 *
 * `now` values passed in must come from a monotonic clock (SystemClock.elapsedRealtime);
 * [wallClock] is only used to timestamp the ride for history. [ftp] gives the current
 * rider's FTP, for time in zones.
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
    // window, so a long ride doesn't keep growing memory.
    private val ring = DoubleArray(RingSize)
    private var seconds = 0
    private var best20: Double? = null
    private val efforts = DoubleArray(Windows.size) { -1.0 }

    private val graph = ArrayList<Int>()
    private val zones = IntArray(PowerZones.Count)
    // Seconds spent not pedaling. They count toward zone 1 once you pedal again; if the
    // ride ends first, they were the idle tail and are dropped.
    private var pendingIdle = 0
    private var pedaledThisSecond = false
    private var zonesFtp: Int? = null

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
            pedaledThisSecond = true
        }
        bestPower = maxOf(bestPower, power)
        bestCadence = maxOf(bestCadence, cadence)
        bestResistance = maxOf(bestResistance, resistance)

        while (seconds <= elapsed.toInt()) {
            ring[seconds % ring.size] = energyJ
            if (seconds > 0) onSecond(seconds)
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
            bestEfforts = currentEfforts(),
        )
    }

    /** Called once for each whole second of the ride, with the ring filled up to [s]. */
    private fun onSecond(s: Int) {
        fun energyAt(sec: Int) = ring[sec % ring.size]
        Windows.forEachIndexed { i, w ->
            if (s >= w) {
                val avg = (energyAt(s) - energyAt(s - w)) / w
                if (avg > efforts[i]) efforts[i] = avg
            }
        }
        if (s >= Window20) best20 = efforts[Windows.indexOf(Window20)]
        if (s % RideDetail.StepSec == 0) {
            graph += ((energyAt(s) - energyAt(s - RideDetail.StepSec)) / RideDetail.StepSec).toInt()
        }
        val rider = ftp()?.takeIf { it > 0 }
        if (rider != null) {
            zonesFtp = rider
            if (pedaledThisSecond) {
                zones[0] += pendingIdle
                pendingIdle = 0
                val watts = (energyAt(s) - energyAt(s - 1)).toFloat()
                zones[PowerZones.zone(watts, rider) - 1]++
            } else {
                pendingIdle++
            }
        }
        pedaledThisSecond = false
    }

    private fun currentEfforts(): Map<Int, Int> =
        Windows.indices.filter { efforts[it] >= 0 }.associate { Windows[it] to efforts[it].toInt() }

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
        if (moving >= minRideSec) onRideFinished(summary(), detail())
    }

    private fun summary() = buildSummary(
        startedAt, elapsedAtLastPedal, moving, powerSum, cadenceSum, resistanceSum, energyJ, distanceMi,
        efforts, zones, zonesFtp,
    )

    private fun detail() = RideDetail(startedAt, trimGraph(graph, elapsedAtLastPedal))

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
        graph.clear()
        zones.fill(0)
        pendingIdle = 0
        pedaledThisSecond = false
        zonesFtp = null
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
            efforts = efforts.copyOf(), graph = graph.toIntArray(), zones = zones.copyOf(),
            pendingIdle = pendingIdle, ftp = zonesFtp,
        )
    }

    /**
     * @return true if the saved ride was still in progress and has been resumed. If not,
     * record [summaryOf] the checkpoint instead, so the ride isn't lost.
     */
    fun resume(c: RideCheckpoint, now: Long, bootId: Int): Boolean {
        val sameBoot = c.bootId == bootId && c.savedAt <= now
        val shapeOk = c.ring.size == ring.size && c.efforts.size == efforts.size && c.zones.size == zones.size
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
        graph.clear(); graph.addAll(c.graph.toList())
        c.zones.copyInto(zones)
        pendingIdle = c.pendingIdle
        pedaledThisSecond = false
        zonesFtp = c.ftp
        snapshot = RideSnapshot(
            riding = true,
            avgPower = avg(powerSum), avgCadence = avg(cadenceSum), avgResistance = avg(resistanceSum),
            bestPower = bestPower, bestCadence = bestCadence, bestResistance = bestResistance,
            totalKj = (energyJ / 1000).toFloat(), distanceMi = distanceMi.toFloat(),
            calories = (energyJ / JoulesPerKcal / Efficiency).toFloat(),
            elapsedSec = elapsedAtLastPedal.toInt(), best20Watts = best20?.toFloat(),
            bestEfforts = currentEfforts(),
        )
        return true
    }

    companion object {
        /** Longest effort window, plus one, in seconds. */
        const val RingSize = 20 * 60 + 1

        /** The finished ride a checkpoint describes, or null if it's too short to keep. */
        fun summaryOf(c: RideCheckpoint, minRideSec: Int = 5 * 60): Pair<RideSummary, RideDetail>? {
            if (c.moving < minRideSec) return null
            val summary = buildSummary(
                c.startedAt, c.elapsedAtLastPedal, c.moving, c.powerSum, c.cadenceSum, c.resistanceSum,
                c.energyJ, c.distanceMi, c.efforts, c.zones, c.ftp,
            )
            return summary to RideDetail(c.startedAt, trimGraph(c.graph.toList(), c.elapsedAtLastPedal))
        }

        private fun buildSummary(
            startedAt: Long, elapsedAtLastPedal: Double, moving: Double,
            powerSum: Double, cadenceSum: Double, resistanceSum: Double, energyJ: Double, distanceMi: Double,
            efforts: DoubleArray, zones: IntArray, zonesFtp: Int?,
        ): RideSummary {
            fun avg(sum: Double) = if (moving > 0) (sum / moving).toInt() else 0
            val bests = Windows.indices.filter { efforts[it] >= 0 }.associate { Windows[it] to efforts[it].toInt() }
            return RideSummary(
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
                zoneSeconds = zones.toList().takeIf { zonesFtp != null && it.sum() > 0 },
                ftp = zonesFtp,
            )
        }

        /** Drops the graph points from the idle minutes after the last pedal stroke. */
        private fun trimGraph(graph: List<Int>, elapsedAtLastPedal: Double): List<Int> =
            graph.take(ceil(elapsedAtLastPedal / RideDetail.StepSec).toInt())

        private val Windows = PersonalRecords.EffortWindows
        private const val Window20 = 20 * 60
        private const val NoSample = Long.MIN_VALUE
        // Standard estimate: mechanical work over ~22% body efficiency.
        private const val JoulesPerKcal = 4184.0
        private const val Efficiency = 0.22
    }
}
