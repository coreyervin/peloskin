package app.peloskin.feed

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

enum class FeedStatus { Connecting, Connected, NotFound }

/**
 * Connects to Grupetto's DirCon server on this tablet and turns its notifications
 * into [BikeSample]s. Reconnects on its own if Grupetto restarts or updates.
 */
class GrupettoFeed(
    private val host: String = "127.0.0.1",
    private val port: Int = DirCon.Port,
) {
    private val _status = MutableStateFlow(FeedStatus.Connecting)
    val status: StateFlow<FeedStatus> = _status

    private val _samples = MutableSharedFlow<BikeSample>(extraBufferCapacity = 16)
    val samples: SharedFlow<BikeSample> = _samples

    private val _heartRate = MutableStateFlow<Int?>(null)
    val heartRate: StateFlow<Int?> = _heartRate

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            var failures = 0
            while (isActive) {
                // Grupetto restarts its DirCon server now and then (for example when a heart
                // rate strap connects), so only count failures since the last good session.
                val gotData = session()
                failures = if (gotData) 1 else failures + 1
                _heartRate.value = null
                _status.value = if (failures >= 3) FeedStatus.NotFound else FeedStatus.Connecting
                delay(3_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** @return true if the session received bike data before it ended. */
    private fun session(): Boolean {
        var gotData = false
        try { Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 2_000)
            // Grupetto sends about once a second; silence for 10 s means the link is dead.
            socket.soTimeout = 10_000
            val out = socket.getOutputStream()
            out.write(DirCon.enableNotifications(DirCon.IndoorBikeData, 1))
            out.write(DirCon.enableNotifications(DirCon.HeartRateMeasurement, 2))
            out.flush()

            val input = socket.getInputStream()
            val reader = DirCon.Reader()
            val buffer = ByteArray(1024)
            var lastHeartRateAt = 0L
            while (true) {
                val count = try {
                    input.read(buffer)
                } catch (_: SocketTimeoutException) {
                    return gotData
                }
                if (count < 0) return gotData
                for (frame in reader.feed(buffer, count)) {
                    if (frame.message != DirCon.MsgNotification) continue
                    when (frame.uuid) {
                        DirCon.IndoorBikeData -> IndoorBikeDataParser.parse(frame.data)?.let {
                            gotData = true
                            _status.value = FeedStatus.Connected
                            _samples.tryEmit(it)
                        }
                        DirCon.HeartRateMeasurement -> {
                            _heartRate.value = HeartRateParser.parse(frame.data)
                            lastHeartRateAt = SystemClock.elapsedRealtime()
                        }
                    }
                }
                if (lastHeartRateAt != 0L && SystemClock.elapsedRealtime() - lastHeartRateAt > 10_000) {
                    _heartRate.value = null
                }
            }
        } } catch (_: IOException) {
            // Connection refused or dropped; the caller retries.
        }
        return gotData
    }
}
