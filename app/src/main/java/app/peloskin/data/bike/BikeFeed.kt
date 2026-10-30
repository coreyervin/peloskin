package app.peloskin.data.bike

import android.util.Log
import app.peloskin.domain.BikeMath
import app.peloskin.domain.BikeSample
import app.peloskin.domain.ResistanceFilter
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class FeedStatus {
    Connecting,
    Connected,
    /** The Peloton sensor software didn't answer. */
    NotFound,
    /** PeloSkin doesn't know how to read this machine yet. */
    Unsupported,
}

/**
 * Reads the bike through Peloton's software on the tablet: the current sensor service first,
 * then the older sensor app. Reconnects on its own if either goes away.
 */
class BikeFeed(
    private val sources: List<SensorSource>,
    private val demo: () -> Boolean = { false },
    private val log: (String) -> Unit = { Log.i("PeloSkin", it) },
) {
    private val _status = MutableStateFlow(FeedStatus.Connecting)
    val status: StateFlow<FeedStatus> = _status

    private val _source = MutableStateFlow<String?>(null)
    /** Which source is feeding us, for the settings screen. */
    val source: StateFlow<String?> = _source

    private val _demoActive = MutableStateFlow(false)
    /** True while the readings are made up, so they stay out of ride history. */
    val demoActive: StateFlow<Boolean> = _demoActive

    private val _samples = MutableSharedFlow<BikeSample>(extraBufferCapacity = 64)
    val samples: SharedFlow<BikeSample> = _samples

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                if (demo()) {
                    runDemo()
                    continue
                }
                if (sources.isEmpty()) {
                    _status.value = FeedStatus.Unsupported
                    delay(5_000)
                    continue
                }
                var gotData = false
                for (source in sources) {
                    if (session(source)) { gotData = true; break }
                }
                _source.value = null
                // Show dashes rather than the last numbers while we reconnect.
                _status.value = if (gotData) FeedStatus.Connecting else FeedStatus.NotFound
                delay(if (gotData) 1_000 else 5_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private sealed interface Event {
        class Sample(val sample: BikeSample) : Event
        object Lost : Event
    }

    /** Runs one source until it stops. @return true if it delivered any data. */
    private suspend fun session(source: SensorSource): Boolean {
        val events = Channel<Event>(64, BufferOverflow.DROP_OLDEST)
        val started = source.start(
            onSample = { events.trySend(Event.Sample(it)) },
            onLost = { events.trySend(Event.Lost) },
        )
        if (!started) {
            source.stop()
            return false
        }
        var gotData = false
        val filter = ResistanceFilter()
        try {
            while (true) {
                // The bike reports several times a second even when you aren't pedaling,
                // so a long silence means the service has stopped talking to us.
                val event = withTimeoutOrNull(if (gotData) 30_000L else 10_000L) { events.receive() }
                when (event) {
                    is Event.Sample -> {
                        if (!gotData) {
                            gotData = true
                            log("Reading the bike from ${source.name}")
                            _source.value = source.name
                            _status.value = FeedStatus.Connected
                        }
                        _samples.tryEmit(filter.clean(event.sample))
                    }
                    Event.Lost, null -> return gotData
                }
            }
        } finally {
            source.stop()
        }
    }

    /** A made-up interval ride, for trying PeloSkin on a device that isn't a Peloton. */
    private suspend fun runDemo() {
        _source.value = "Demo ride"
        _demoActive.value = true
        _status.value = FeedStatus.Connected
        var t = 0.0
        // Starts from a standstill, like getting on the bike, so a demo can follow an ended ride.
        _samples.tryEmit(BikeMath.sample(0f, 0f, 25f))
        while (demo()) {
            val base = when {
                t < 300 -> 90 + t / 300 * 60
                t % 240 < 120 -> 230.0
                else -> 160.0
            }
            val power = max(0.0, base + 10 * sin(t * 1.7) + 6 * sin(t * 0.37)).toFloat()
            val cadence = (78 + power / 12 + 2 * sin(t * 0.9)).toFloat()
            _samples.tryEmit(BikeMath.sample(power, cadence, (25 + power / 8).coerceAtMost(100f)))
            delay(250)
            t += 0.25
        }
        _demoActive.value = false
        _source.value = null
        _status.value = FeedStatus.Connecting
    }
}
