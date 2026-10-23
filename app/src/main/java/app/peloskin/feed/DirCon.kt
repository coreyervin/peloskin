package app.peloskin.feed

import java.nio.ByteBuffer
import java.util.UUID

/**
 * Minimal client-side codec for the Wahoo "DirCon" protocol: Bluetooth GATT
 * characteristics carried over TCP. Grupetto serves it on port 8081.
 *
 * Frame: version(1) message(1) sequence(1) response(1) length(2, big-endian) payload.
 */
object DirCon {
    const val Port = 8081
    const val HeaderLength = 6

    const val MsgEnableNotifications = 0x05
    const val MsgNotification = 0x06

    /** FTMS Indoor Bike Data. */
    val IndoorBikeData: UUID = shortUuid(0x2AD2)
    /** Heart Rate Measurement. Only present when a strap is paired to Grupetto. */
    val HeartRateMeasurement: UUID = shortUuid(0x2A37)

    fun shortUuid(short: Int): UUID =
        UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))

    fun enableNotifications(uuid: UUID, sequence: Int): ByteArray {
        val payload = ByteBuffer.allocate(17)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .put(1)
            .array()
        return frame(MsgEnableNotifications, sequence, payload)
    }

    private fun frame(message: Int, sequence: Int, payload: ByteArray): ByteArray =
        ByteArray(HeaderLength + payload.size).also {
            it[0] = 1
            it[1] = message.toByte()
            it[2] = sequence.toByte()
            it[3] = 0
            it[4] = (payload.size ushr 8).toByte()
            it[5] = payload.size.toByte()
            payload.copyInto(it, HeaderLength)
        }

    class Frame(val message: Int, val response: Int, val uuid: UUID?, val data: ByteArray)

    /**
     * Accumulates bytes from the socket and yields complete frames.
     */
    class Reader {
        private var pending = ByteArray(0)

        fun feed(bytes: ByteArray, count: Int): List<Frame> {
            pending += bytes.copyOf(count)
            val frames = mutableListOf<Frame>()
            var offset = 0
            while (pending.size - offset >= HeaderLength) {
                val length = ((pending[offset + 4].toInt() and 0xFF) shl 8) or
                    (pending[offset + 5].toInt() and 0xFF)
                val end = offset + HeaderLength + length
                if (end > pending.size) break
                val message = pending[offset + 1].toInt() and 0xFF
                val response = pending[offset + 3].toInt() and 0xFF
                val start = offset + HeaderLength
                var uuid: UUID? = null
                var data = ByteArray(0)
                if (length >= 16) {
                    val buffer = ByteBuffer.wrap(pending, start, 16)
                    uuid = UUID(buffer.long, buffer.long)
                    data = pending.copyOfRange(start + 16, end)
                }
                frames += Frame(message, response, uuid, data)
                offset = end
            }
            pending = pending.copyOfRange(offset, pending.size)
            return frames
        }
    }
}
