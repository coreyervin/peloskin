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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import app.peloskin.data.bike.BikeFeed
import app.peloskin.data.bike.BikeModel
import app.peloskin.data.bike.FeedStatus
import app.peloskin.data.hr.HeartRateLink
import app.peloskin.data.store.FinishedRide
import app.peloskin.data.store.SettingsStore
import app.peloskin.domain.BikeSample
import app.peloskin.domain.CompactEdge
import app.peloskin.domain.PersonalRecords
import app.peloskin.domain.RideSnapshot
import app.peloskin.domain.RideTracker
import app.peloskin.domain.SkinMode
import app.peloskin.ui.settings.MainActivity
import app.peloskin.ui.summary.WorkoutCompleteBar
import app.peloskin.ui.summary.WorkoutDetailsSheet
import app.peloskin.ui.summary.WorkoutSummaryPanel
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class SkinService : LifecycleService(), SavedStateRegistryOwner {

    companion object {
        private const val Channel = "skin"
        private const val NotificationId = 1

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        private val _feedStatus = MutableStateFlow(FeedStatus.Connecting)
        /** Bike connection, for the settings screen. */
        val feedStatus: StateFlow<FeedStatus> = _feedStatus

        private val _riding = MutableStateFlow(false)
        /** Whether a ride is in progress, for the settings screen's End ride button. */
        val riding: StateFlow<Boolean> = _riding

        private val endRideRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /** Ends the ride in progress, if the skin is running. */
        fun requestEndRide() { endRideRequests.tryEmit(Unit) }

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
    private val tracker = RideTracker(
        ftp = { SettingsStore.settings.value.active.ftp },
        maxHr = { SettingsStore.settings.value.active.maxHr },
        onRideFinished = { ride, detail ->
            // Demo rides are made up: they get a summary but stay out of history, FTP and PRs.
            if (rideIsDemo) SettingsStore.showDemoSummary(ride, detail)
            else SettingsStore.recordRide(ride, detail)
        },
    )
    private val mode = MutableStateFlow(SkinMode.Full)
    private var rideIsDemo = false

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
        // If Android killed and restarted the service mid-ride, pick the ride back up. If too
        // much time has passed or the tablet rebooted, save it as a finished ride instead.
        SettingsStore.loadCheckpoint()?.let { saved ->
            if (tracker.resume(saved, SystemClock.elapsedRealtime(), bootId())) {
                ride.value = tracker.snapshot
            } else {
                val rider = SettingsStore.settings.value.active
                RideTracker.summaryOf(saved, rider.ftp, rider.maxHr)
                    ?.let { (ride, detail) -> SettingsStore.recordRide(ride, detail) }
                SettingsStore.saveCheckpoint(null)
            }
        }
        feed = BikeFeed(BikeModel.detect(this).sources(this), demo = { SettingsStore.demoRide.value })
        feed.start(lifecycleScope)
        lifecycleScope.launch { feed.status.collect { _feedStatus.value = it } }
        lifecycleScope.launch { feed.source.collect { _feedSource.value = it } }
        // The bike reports several times a second. Every reading goes into the ride totals,
        // but the screen redraws 4 times a second showing the average since the last redraw.
        // Resistance is a knob setting, so it shows the latest reading rather than an average.
        val window = DisplayWindow()
        var lastSampleAt = 0L
        lifecycleScope.launch {
            feed.samples.collect {
                lastSampleAt = SystemClock.elapsedRealtime()
                val wasRiding = tracker.snapshot.riding
                tracker.onSample(lastSampleAt, it)
                // A ride with any demo readings in it is a demo ride, even if the demo is
                // turned off before the ride ends.
                if (!wasRiding && tracker.snapshot.riding) rideIsDemo = false
                if (feed.demoActive.value) rideIsDemo = true
                window.add(it)
            }
        }
        lifecycleScope.launch {
            while (true) {
                delay(250)
                val avg = window.drain()
                if (avg != null) {
                    ride.value = tracker.snapshot.copy(power = avg.power, cadence = avg.cadence, speedMph = avg.speedMph)
                } else if (SystemClock.elapsedRealtime() - lastSampleAt > 2_500 && ride.value.power + ride.value.cadence > 0f) {
                    // No readings for a while: don't leave the last watts and rpm on screen.
                    ride.value = ride.value.copy(power = 0f, cadence = 0f, speedMph = 0f)
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
        // Whoever is riding, keep their heart rate monitor connected; switching rider drops the
        // last one and goes looking for the new rider's.
        HeartRateLink.init(this)
        lifecycleScope.launch {
            SettingsStore.settings.map { it.active.heartRateMonitor }.distinctUntilChanged()
                .collect { HeartRateLink.follow(it) }
        }
        lifecycleScope.launch {
            HeartRateLink.state.collect { tracker.onHeartRate(it.bpm.takeIf { _ -> it.status == HeartRateLink.Status.Connected }) }
        }
        lifecycleScope.launch { endRideRequests.collect { endRide() } }
        lifecycleScope.launch {
            // A newer banner restarts the 12 seconds.
            SettingsStore.banner.collectLatest {
                if (it != null) { delay(12_000); SettingsStore.clearBanner() }
            }
        }
        lifecycleScope.launch {
            // Call out a personal best the moment it's beaten, once per effort length per ride.
            val announced = mutableSetOf<Int>()
            // A ride picked back up after a restart already announced its PRs before.
            val resumed = tracker.snapshot
            if (resumed.riding) {
                val records = SettingsStore.settings.value.active.records.efforts
                resumed.bestEfforts.forEach { (sec, w) -> if (w > (records[sec]?.value ?: Int.MAX_VALUE)) announced += sec }
            }
            var wasRiding = resumed.riding
            ride.collect { r ->
                _riding.value = r.riding
                if (r.riding && !wasRiding) {
                    announced.clear()
                    // Pedaling into the next class: the last ride's summary has had its moment.
                    SettingsStore.dismissSummary()
                }
                wasRiding = r.riding
                if (!r.riding || rideIsDemo) return@collect
                val records = SettingsStore.settings.value.active.records.efforts
                for ((seconds, watts) in r.bestEfforts) {
                    val best = records[seconds] ?: continue
                    if (watts > best.value && announced.add(seconds)) {
                        val effort = PersonalRecords.effortLabel(seconds)
                        SettingsStore.showBanner("New $effort PR · $watts W", record = SettingsStore.RecordCard(effort, watts, best.value))
                    }
                }
            }
        }
        lifecycleScope.launch {
            // A summary from a while ago (say, a ride stopped before the skin last closed)
            // isn't worth putting over whatever's on screen now.
            SettingsStore.lastFinished.value?.let {
                if (System.currentTimeMillis() - it.finishedAt > 5 * 60_000L) SettingsStore.dismissSummary()
            }
            SettingsStore.lastFinished.collect { finished ->
                if (finished != null) showSummary(finished) else hideSummary()
            }
        }
        lifecycleScope.launch {
            // Redraw when the mode changes, or when the single-line bar's edge changes.
            combine(mode, SettingsStore.settings.map { it.compactEdge }) { m, edge ->
                m to if (m == SkinMode.Compact) edge else null
            }.distinctUntilChanged().collect { (m, _) -> showWindows(m) }
        }
    }

    private fun saveCheckpoint(now: Long) = SettingsStore.saveCheckpoint(tracker.checkpoint(now, bootId()))

    /** Ends the ride now: it's saved and its summary comes up, and the skin keeps running. */
    private fun endRide() {
        if (!tracker.snapshot.riding) return
        if (!tracker.longEnough) SettingsStore.showBanner("Ride ended · under a minute of pedaling, so it isn't saved")
        tracker.finish(byRider = true)
        ride.value = tracker.snapshot.copy(power = ride.value.power, cadence = ride.value.cadence, speedMph = ride.value.speedMph)
        SettingsStore.saveCheckpoint(null)
    }

    /** Changes every time the tablet boots, so a checkpoint from an earlier boot is spotted. */
    private fun bootId() = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)

    override fun onDestroy() {
        _riding.value = false
        if (_running.value) {
            // Stopping the skin ends the ride, so it's saved now rather than lost.
            tracker.finish()
            SettingsStore.saveCheckpoint(null)
        }
        removeWindows()
        hideSummary()
        if (::feed.isInitialized) feed.stop()
        HeartRateLink.follow(null)
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
        // Keep an open summary on top of the skin.
        SettingsStore.lastFinished.value?.let { if (summaryViews.isNotEmpty()) showSummary(it) }
    }

    private fun showFull() {
        // The whole skin is one tap target: tap it to switch layouts.
        val display = baseParams().apply {
            width = LayoutParams.MATCH_PARENT
            gravity = Gravity.BOTTOM
        }
        add(composeView {
            FullSkin(
                skinState(), onToggle = { mode.value = SkinMode.Compact },
                onNextRider = SettingsStore::nextProfile, onEndRide = ::endRide,
            )
        }, display)
        val margin = (1.3f * unit).roundToInt()
        // Heart rate on the left edge, a little above the middle, like Peloton's. It's only
        // there for riders with a monitor, and taps go through it to the class app.
        add(composeView {
            skinState().heartRate?.let { HeartRatePanel(it, rememberScale()) }
        }, passThroughParams().apply {
            width = (16.5f * unit).roundToInt()
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = margin
            y = -(resources.displayMetrics.heightPixels * 0.08f).roundToInt()
        })
        // A personal record beaten mid-ride, in the top-right corner while its banner lasts.
        add(composeView {
            skinState().record?.let { RecordCardView(it, rememberScale()) }
        }, passThroughParams().apply {
            width = (19f * unit).roundToInt()
            gravity = Gravity.TOP or Gravity.END
            x = margin
            y = margin
        })
    }

    /** A window that shows but never takes a touch. */
    private fun passThroughParams() = baseParams().apply { flags = flags or LayoutParams.FLAG_NOT_TOUCHABLE }

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
                    onNextRider = SettingsStore::nextProfile,
                    onEndRide = ::endRide,
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
        val banner by SettingsStore.banner.collectAsState()
        val photos = rememberRiderPhotos()
        val hr by HeartRateLink.state.collectAsState()
        val active = settings.active
        return SkinState(
            // Only riders with a monitor get a heart rate on the skin.
            heartRate = active.heartRateMonitor?.let {
                val connected = hr.status == HeartRateLink.Status.Connected && hr.monitor == it
                HeartRateReading.of(hr.bpm.takeIf { connected }, active.maxHr, !connected, r.heartRateZoneSeconds)
            },
            ride = r, feed = f, ftp = active.ftp, banner = banner?.text, units = settings.units,
            riderSwitchBanner = banner?.riderSwitch == true,
            record = banner?.record,
            // A lone rider without a picture doesn't need a bubble saying who's riding.
            rider = settings.badgeFor(active, photos).takeIf { settings.profiles.size > 1 || it?.photo != null },
        )
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

    // ------------------------------------------------------------ ride summary

    private val summaryViews = mutableListOf<View>()
    private var summaryTimeout: Job? = null
    private var detailsOpen = false
    private var panelOpen = true
    private var summaryShown: FinishedRide? = null

    /**
     * After a ride, like Peloton's: a top bar with "Workout complete", Workout details and
     * Done, and the workout summary panel in the top-right corner, with the skin still showing
     * underneath. Workout details swaps the panel for the details sheet.
     */
    private fun showSummary(finished: FinishedRide) {
        // A new ride's summary starts on the panel; redrawing the same one keeps where you were.
        val fresh = summaryShown !== finished || summaryViews.isEmpty()
        removeSummaryViews()
        if (fresh) { summaryTimeout?.cancel(); detailsOpen = false; panelOpen = true; summaryShown = finished }
        val margin = (1.3f * unit).roundToInt()
        val barHeight = (5.2f * unit).roundToInt()
        addSummary(composeView {
            WorkoutCompleteBar(
                detailsOpen,
                onDetails = { detailsOpen = !detailsOpen; panelOpen = true; showSummary(finished) },
                onDone = SettingsStore::dismissSummary,
                s = rememberScale(),
            )
        }, baseParams().apply { width = LayoutParams.MATCH_PARENT; gravity = Gravity.TOP })
        if (detailsOpen) {
            addSummary(composeView {
                val settings by SettingsStore.settings.collectAsState()
                val photos = rememberRiderPhotos()
                val s = rememberScale()
                WorkoutDetailsSheet(
                    finished, settings.units, s, Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    rider = settings.badgeFor(settings.profiles.find { it.id == finished.profileId }, photos),
                )
            }, baseParams().apply {
                width = resources.displayMetrics.widthPixels - 2 * margin
                gravity = Gravity.TOP or Gravity.START
                x = margin
                y = barHeight + margin
            })
        } else if (panelOpen) {
            addSummary(composeView {
                val settings by SettingsStore.settings.collectAsState()
                val photos = rememberRiderPhotos()
                WorkoutSummaryPanel(
                    finished, settings.units,
                    rider = settings.badgeFor(settings.profiles.find { it.id == finished.profileId }, photos),
                    onBack = { panelOpen = false; showSummary(finished) },
                    s = rememberScale(),
                )
            }, baseParams().apply {
                width = (24f * unit).roundToInt()
                gravity = Gravity.TOP or Gravity.END
                x = margin
                y = barHeight + margin
            })
        }
        if (fresh) {
            // Nobody's watching after a few minutes; don't leave it over the next class.
            summaryTimeout = lifecycleScope.launch { delay(5 * 60_000L); SettingsStore.dismissSummary() }
        }
    }

    private fun addSummary(view: View, params: LayoutParams) {
        windows.addView(view, params)
        summaryViews += view
    }

    private fun removeSummaryViews() {
        summaryViews.forEach { runCatching { windows.removeView(it) } }
        summaryViews.clear()
    }

    private fun hideSummary() {
        summaryTimeout?.cancel()
        summaryTimeout = null
        summaryShown = null
        removeSummaryViews()
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
    class Average(val power: Float, val cadence: Float, val speedMph: Float)

    private var power = 0f
    private var cadence = 0f
    private var speed = 0f
    private var count = 0

    fun add(sample: BikeSample) {
        power += sample.power; cadence += sample.cadence; speed += sample.speedMph; count++
    }

    fun drain(): Average? {
        if (count == 0) return null
        val avg = Average(power / count, cadence / count, speed / count)
        power = 0f; cadence = 0f; speed = 0f; count = 0
        return avg
    }
}
