package app.peloskin.overlay

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import app.peloskin.MainActivity
import app.peloskin.feed.FeedStatus
import app.peloskin.feed.GrupettoFeed
import app.peloskin.ride.RideSnapshot
import app.peloskin.ride.RideTracker
import app.peloskin.store.CompactEdge
import app.peloskin.store.SettingsStore
import app.peloskin.store.SkinMode
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
        /** Grupetto connection, for the settings screen. */
        val feedStatus: StateFlow<FeedStatus> = _feedStatus

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

    private val feed = GrupettoFeed()
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
        feed.start(lifecycleScope)
        lifecycleScope.launch { feed.status.collect { _feedStatus.value = it } }
        lifecycleScope.launch {
            feed.samples.collect {
                tracker.onSample(SystemClock.elapsedRealtime(), it)
                ride.value = tracker.snapshot
            }
        }
        lifecycleScope.launch {
            var ticks = 0
            while (true) {
                delay(5_000)
                val now = SystemClock.elapsedRealtime()
                tracker.tick(now)
                ride.value = tracker.snapshot
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
        feed.stop()
        _running.value = false
        super.onDestroy()
    }

    // ---------------------------------------------------------------- windows

    private val unit: Float get() = resources.displayMetrics.widthPixels / 100f

    private fun baseParams(touchable: Boolean) = LayoutParams(
        LayoutParams.WRAP_CONTENT,
        LayoutParams.WRAP_CONTENT,
        LayoutParams.TYPE_APPLICATION_OVERLAY,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL or
            LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (touchable) 0 else LayoutParams.FLAG_NOT_TOUCHABLE),
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
        val display = baseParams(touchable = false).apply {
            width = LayoutParams.MATCH_PARENT
            gravity = Gravity.BOTTOM
        }
        add(composeView { FullSkin(skinState()) }, display)

        // The fold button gets its own small touchable window, so the rest of the
        // skin never blocks taps meant for the Peloton app underneath.
        val pad = 1f
        val button = baseParams(touchable = true).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (((StripHeight - FoldButton) / 2f - pad) * unit).roundToInt()
        }
        add(composeView {
            val s = rememberScale()
            Box(
                Modifier
                    .clickable(remember { MutableInteractionSource() }, null) { mode.value = SkinMode.Compact }
                    .padding(s.dp(pad))
            ) { FoldButtonView(expanded = true) }
        }, button)
    }

    private fun showCompact() {
        val edge = SettingsStore.settings.value.compactEdge
        val margin = (1.4f * unit).roundToInt()
        val params = baseParams(touchable = true).apply {
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
                    modifier = Modifier.onSizeChanged { size ->
                        if (params.width != size.width || params.height != size.height) {
                            params.width = size.width
                            params.height = size.height
                            runCatching { windows.updateViewLayout(frame, params) }
                        }
                    },
                ) {
                    Box(Modifier.clickable(remember { MutableInteractionSource() }, null) { mode.value = SkinMode.Full }) {
                        FoldButtonView(expanded = false)
                    }
                }
            }
        })
        add(frame, params)
    }

    @androidx.compose.runtime.Composable
    private fun skinState(): SkinState {
        val r by ride.collectAsState()
        val f by feed.status.collectAsState()
        val hr by feed.heartRate.collectAsState()
        val settings by SettingsStore.settings.collectAsState()
        val banner by SettingsStore.ftpBanner.collectAsState()
        return SkinState(r, f, settings.ftp, hr, banner)
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
