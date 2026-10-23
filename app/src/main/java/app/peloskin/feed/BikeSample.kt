package app.peloskin.feed

/** One reading from Grupetto's feed. Null means the field wasn't in the message. */
data class BikeSample(
    val power: Float?,
    val cadence: Float?,
    val resistance: Float?,
    val speedMph: Float?,
)

/**
 * Parses the Bluetooth FTMS "Indoor Bike Data" characteristic (0x2AD2).
 * Field order and sizes follow the FTMS spec; each flag bit says whether a field is present.
 */
object IndoorBikeDataParser {
    private const val KmhToMph = 0.621371f

    fun parse(bytes: ByteArray): BikeSample? {
        if (bytes.size < 2) return null
        val r = LeReader(bytes)
        val flags = r.u16()

        // Bit 0 is "More Data": when it is CLEAR, instantaneous speed is present.
        val speedKmh = if (flags and 0x0001 == 0) r.u16() / 100f else null
        if (flags and 0x0002 != 0) r.skip(2)              // average speed
        val cadence = if (flags and 0x0004 != 0) r.u16() / 2f else null
        if (flags and 0x0008 != 0) r.skip(2)              // average cadence
        if (flags and 0x0010 != 0) r.skip(3)              // total distance
        val resistance = if (flags and 0x0020 != 0) r.s16().toFloat() else null
        val power = if (flags and 0x0040 != 0) r.s16().toFloat() else null

        if (!r.ok) return null
        return BikeSample(
            power = power,
            cadence = cadence,
            resistance = resistance,
            speedMph = speedKmh?.let { it * KmhToMph },
        )
    }
}

/** Parses Heart Rate Measurement (0x2A37). */
object HeartRateParser {
    fun parse(bytes: ByteArray): Int? {
        if (bytes.size < 2) return null
        val r = LeReader(bytes)
        val flags = r.u8()
        val bpm = if (flags and 0x01 != 0) r.u16() else r.u8()
        return if (r.ok && bpm > 0) bpm else null
    }
}

private class LeReader(private val b: ByteArray) {
    private var i = 0
    var ok = true
        private set

    private fun has(n: Int): Boolean {
        if (i + n > b.size) ok = false
        return ok
    }

    fun u8(): Int = if (has(1)) (b[i++].toInt() and 0xFF) else 0
    fun u16(): Int = if (has(2)) {
        val v = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        i += 2; v
    } else 0
    fun s16(): Int = u16().toShort().toInt()
    fun skip(n: Int) { if (has(n)) i += n }
}
