package app.peloskin.domain

import kotlin.math.roundToInt

/** A rider's Bluetooth heart rate monitor, remembered so PeloSkin can find it again. */
data class HeartRateMonitor(val address: String, val name: String)

object HeartRateZones {
    /** Upper bound of zones 1–4 as a fraction of max heart rate (Peloton's heart rate zones). */
    private val upperBounds = floatArrayOf(0.65f, 0.75f, 0.85f, 0.95f)

    const val Count = 5

    /** Zone 1–5 for [bpm], or 0 without a max heart rate. */
    fun zone(bpm: Int, maxHr: Int): Int {
        if (maxHr <= 0 || bpm <= 0) return 0
        val pct = bpm.toFloat() / maxHr
        val i = upperBounds.indexOfFirst { pct < it }
        return if (i < 0) Count else i + 1
    }

    fun percentOfMax(bpm: Int, maxHr: Int): Int = if (maxHr <= 0) 0 else (bpm * 100f / maxHr).roundToInt()

    /** The usual estimate when a rider doesn't know theirs. */
    fun estimateMax(age: Int): Int = 220 - age
}

object HeartRateMeasurement {
    /**
     * Beats per minute from a Bluetooth Heart Rate Measurement (characteristic 0x2A37).
     * Bit 0 of the flags byte says whether the value is 8 or 16 bits. @return null for a
     * malformed packet or a 0 reading, which straps send when they lose skin contact.
     */
    fun bpm(value: ByteArray): Int? {
        if (value.size < 2) return null
        val flags = value[0].toInt()
        val bpm = if (flags and 0x01 != 0) {
            if (value.size < 3) return null
            (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
        } else {
            value[1].toInt() and 0xFF
        }
        return bpm.takeIf { it in 1..250 }
    }
}
