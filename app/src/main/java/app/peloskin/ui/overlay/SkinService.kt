package app.peloskin.ui.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.peloskin.ui.settings.MainActivity
import app.peloskin.data.bike.BikeFeed
import app.peloskin.data.bike.BikeModel
import app.peloskin.domain.BikeSample
import app.peloskin.data.bike.FeedStatus
import app.peloskin.domain.RideSnapshot
import app.peloskin.domain.RideTracker
import app.peloskin.domain.CompactEdge
import app.peloskin.data.store.SettingsStore
import app.peloskin.domain.SkinMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class SkinService : LifecycleService(), SavedStateRegistryOwner {

    companion object {
        private const val Channel = "skin"
        private const val NotificationId = 1

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        private val _feedStatus = MutableStateFlow(FeedStatus.Connecting)
        /** Bike connection, for the settings screen. */
        val feedStatus: StateFlow<FeedStatus> = _feedStatus

        private val _feedSource = MutableStateFlow<String?>(null)
        /** Which Peloton service the numbers come from, for the settings screen. */
        val feedSource: StateFlow<String?> = _feedSource

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SkinService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SkinService::class.java))
        }
    }

    private val savedState = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private lateinit var windows: WindowManager
    private val attached = mutableListOf<View>()

    private lateinit var feed: BikeFeed
    private val ride = MutableStateFlow(RideSnapshot())
    private val tracker = RideTracker(onRideFinished = { SettingsStore.recordRide(it) })
    private val mode = MutableStateFlow(SkinMode.Full)

    override fun onCreate() {
        savedState.performRestore(null)
        super.onCreate()
        SettingsStore.init(this)
        windows = getSystemService(WINDOW_SERVICE) as WindowManager

        // startForegroundService() requires startForeground() even if we stop right away.
        goForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        _running.value = true

        mode.value = SettingsStore.settings.value.startMode
        // If Android killed and restarted the service mid-ride, pick the ride back up.
        SettingsStore.loadCheckpoint()?.let { saved ->
            if (tracker.resume(saved, SystemClock.elapsedRealtime())) ride.value = tracker.snapshot
        }
        feed = BikeFeed(BikeModel.detect(this).sources(this), demo = { SettingsStore.demoRide.value })
        feed.start(lifecycleScope)
        lifecycleScope.launch { feed.status.collect { _feedStatus.value = it } }
        lifecycleScope.launch { feed.source.collect { _feedSource.value = it } }
        // The bike reports several times a second. Every reading goes into the ride totals,
        // but the screen redraws 4 times a second showing the average since the last redraw.
        val window = DisplayWindow()
        lifecycleScope.launch {
            feed.samples.collect {
                tracker.onSample(SystemClock.elapsedRealtime(), it)
                window.add(it)
            }
        }
        lifecycleScope.launch {
            while (true) {
                delay(250)
                window.drain()?.let { avg ->
                    ride.value = tracker.snapshot.copy(power = avg.power, cadence = avg.cadence, speedMph = avg.speedMph)
                }
            }
        }
        lifecycleScope.launch {
            var ticks = 0
            while (true) {
                delay(5_000)
                val now = SystemClock.elapsedRealtime()
                tracker.tick(now)
                // Keep the averaged live numbers; this only picks up a ride ending.
                ride.value = tracker.snapshot.copy(power = ride.value.power, cadence = ride.value.cadence, speedMph = ride.value.speedMph)
                if (++ticks % 6 == 0) saveCheckpoint(now)
            }
        }
        lifecycleScope.launch {
            SettingsStore.ftpBanner.collect {
                if (it != null) { delay(15_000); SettingsStore.clearBanner() }
            }
        }
        lifecycleScope.launch {
            // Redraw when the mode changes, or when the single-line bar's edge changes.
            combine(mode, SettingsStore.settings.map { it.compactEdge }) { m, edge ->
                m to if (m == SkinMode.Compact) edge else null
            }.distinctUntilChanged().collect { (m, _) -> showWindows(m) }
        }
    }

    private fun saveCheckpoint(now: Long) = SettingsStore.saveCheckpoint(tracker.checkpoint(now))

    override fun onDestroy() {
        if (_running.value) saveCheckpoint(SystemClock.elapsedRealtime())
        removeWindows()
        if (::feed.isInitialized) feed.stop()
        _running.value = false
        super.onDestroy()
    }

    // ---------------------------------------------------------------- windows

    private val unit: Float get() = resources.displayMetrics.widthPixels / 100f

    private fun baseParams() = LayoutParams(
        LayoutParams.WRAP_CONTENT,
        LayoutParams.WRAP_CONTENT,
        LayoutParams.TYPE_APPLICATION_OVERLAY,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL or
            LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )

    private fun showWindows(m: SkinMode) {
        removeWindows()
        when (m) {
            SkinMode.Full -> showFull()
            SkinMode.Compact -> showCompact()
        }
    }

    private fun showFull() {
        // The whole skin is one tap target: tap it to switch layouts.
        val display = baseParams().apply {
            width = LayoutParams.MATCH_PARENT
            gravity = Gravity.BOTTOM
        }
        add(composeView { FullSkin(skinState(), onToggle = { mode.value = SkinMode.Compact }) }, display)
    }

    private fun showCompact() {
        val edge = SettingsStore.settings.value.compactEdge
        val margin = (1.4f * unit).roundToInt()
        val params = baseParams().apply {
            gravity = Gravity.CENTER_HORIZONTAL or if (edge == CompactEdge.Top) Gravity.TOP else Gravity.BOTTOM
            y = margin
        }
        val screenHeight = resources.displayMetrics.heightPixels
        lateinit var frame: DragFrame
        frame = DragFrame(
            this,
            onDrag = { dy ->
                // y counts away from the anchored edge.
                params.y = (margin + if (edge == CompactEdge.Top) dy else -dy)
                    .roundToInt().coerceIn(0, (screenHeight - frame.height).coerceAtLeast(0))
                runCatching { windows.updateViewLayout(frame, params) }
            },
            onDragEnd = {
                val fromTop = if (edge == CompactEdge.Top) params.y else screenHeight - params.y - frame.height
                val newEdge = if (fromTop + frame.height / 2 < screenHeight / 2) CompactEdge.Top else CompactEdge.Bottom
                if (newEdge != edge) {
                    // The settings collector redraws the bar at its new edge.
                    SettingsStore.update { it.copy(compactEdge = newEdge) }
                } else {
                    params.y = margin
                    runCatching { windows.updateViewLayout(frame, params) }
                }
            },
        )
        // Compose looks up its lifecycle from the window's root view, which is this frame.
        frame.setViewTreeLifecycleOwner(this)
        frame.setViewTreeSavedStateRegistryOwner(this)
        frame.addView(composeView {
            val settings by SettingsStore.settings.collectAsState()
            // Android sizes WRAP_CONTENT overlay windows like dialogs (~580 px wide) and
            // Compose never asks for more, so measure the bar unbounded and size the window to it.
            Box(Modifier.wrapContentSize(unbounded = true)) {
                CompactSkin(
                    state = skinState(),
                    metrics = settings.compactMetrics,
                    onToggle = { mode.value = SkinMode.Full },
                    modifier = Modifier.onSizeChanged { size ->
                        if (params.width != size.width || params.height != size.height) {
                            params.width = size.width
                            params.height = size.height
                            runCatching { windows.updateViewLayout(frame, params) }
                        }
                    },
                )
            }
        })
        add(frame, params)
    }

    @androidx.compose.runtime.Composable
    private fun skinState(): SkinState {
        val r by ride.collectAsState()
        val f by feed.status.collectAsState()
        val settings by SettingsStore.settings.collectAsState()
        val banner by SettingsStore.ftpBanner.collectAsState()
        return SkinState(r, f, settings.ftp, banner)
    }

    private fun composeView(content: @androidx.compose.runtime.Composable () -> Unit) =
        ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@SkinService)
            setViewTreeSavedStateRegistryOwner(this@SkinService)
            setContent(content)
        }

    private fun add(view: View, params: LayoutParams) {
        windows.addView(view, params)
        attached += view
    }

    private fun removeWindows() {
        attached.forEach { runCatching { windows.removeView(it) } }
        attached.clear()
    }

    // ----------------------------------------------------------- notification

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(Channel, "Metrics skin", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, Channel)
            .setContentTitle("PeloSkin is showing your metrics")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NotificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NotificationId, notification)
        }
    }
}

/** Averages readings between screen redraws. Readings arrive on the main thread. */
private class DisplayWindow {
    private var power = 0f
    private var cadence = 0f
    private var speed = 0f
    private var count = 0

    fun add(sample: BikeSample) {
        power += sample.power; cadence += sample.cadence; speed += sample.speedMph; count++
    }

    fun drain(): BikeSample? {
        if (count == 0) return null
        val avg = BikeSample(power / count, cadence / count, 0f, speed / count)
        power = 0f; cadence = 0f; speed = 0f; count = 0
        return avg
    }
}
