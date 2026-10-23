package app.peloskin.ride

import kotlin.math.roundToInt

object PowerZones {
    /** Upper bound of zones 1–6 as a fraction of FTP (Peloton's power zones). */
    private val upperBounds = floatArrayOf(0.55f, 0.75f, 0.90f, 1.05f, 1.20f, 1.50f)

    const val Count = 7

    fun zone(power: Float, ftp: Int): Int {
        if (ftp <= 0) return 0
        val pct = power / ftp
        val i = upperBounds.indexOfFirst { pct <= it }
        return if (i < 0) Count else i + 1
    }

    fun percentOfFtp(power: Float, ftp: Int): Int = if (ftp <= 0) 0 else (power / ftp * 100).roundToInt()
}

enum class FtpSource { Manual, Ride }

object Ftp {
    /** Peloton's definition: 95% of your best 20-minute average output. */
    fun fromBest20(best20Watts: Int): Int = (best20Watts * 0.95).roundToInt()

    /**
     * Returns the new FTP if this ride should replace the current one, else null.
     * Easy rides give low numbers, so a ride only ever raises FTP automatically (or sets
     * the first one). A lower result, such as a retest, is applied from the ride history.
     */
    fun afterRide(currentFtp: Int?, autoUpdate: Boolean, ride: RideSummary): Int? {
        val best20 = ride.best20Watts ?: return null
        val candidate = fromBest20(best20)
        if (candidate <= 0) return null
        return when {
            currentFtp == null -> candidate
            autoUpdate && candidate > currentFtp -> candidate
            else -> null
        }
    }
}
