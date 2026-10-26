package app.peloskin.domain

import kotlin.math.sqrt

/** One reading from the bike. */
data class BikeSample(
    val power: Float,
    val cadence: Float,
    val resistance: Float,
    val speedMph: Float,
)

object BikeMath {
    /** Readings above this are sensor glitches, not riders. */
    private const val MaxWatts = 2500f

    /**
     * Builds a sample from raw sensor values. The flywheel sensor sometimes reports
     * big power spikes while you aren't pedaling, so power only counts with cadence.
     */
    fun sample(power: Float, cadence: Float, resistance: Float): BikeSample {
        val rpm = cadence.coerceIn(0f, 250f)
        val watts = if (rpm <= 0f || !power.isFinite()) 0f else power.coerceIn(0f, MaxWatts)
        return BikeSample(
            power = watts,
            cadence = rpm,
            resistance = resistance.coerceIn(0f, 100f),
            speedMph = speedFromPower(watts),
        )
    }

    /**
     * Peloton's power-to-speed curve for the bike, as measured by the PeloMon project
     * (ihaque.org/posts/2020/12/25/pelomon-part-ib-computing-speed).
     */
    fun speedFromPower(watts: Float): Float {
        if (watts < 0.1f) return 0f
        val r = sqrt(watts)
        val mph = if (watts < 26f) {
            0.057f - 0.172f * r + 0.759f * r * r - 0.079f * r * r * r
        } else {
            -1.635f + 2.325f * r - 0.064f * r * r + 0.001f * r * r * r
        }
        return mph.coerceAtLeast(0f)
    }
}
