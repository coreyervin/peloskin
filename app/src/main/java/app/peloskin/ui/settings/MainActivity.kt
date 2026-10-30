package app.peloskin.ui.settings

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextFieldColors
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.peloskin.R
import app.peloskin.data.bike.BikeModel
import app.peloskin.data.bike.FeedStatus
import app.peloskin.data.hr.HeartRateLink
import app.peloskin.data.store.FinishedRide
import app.peloskin.data.store.RiderPhotos
import app.peloskin.data.store.SettingsStore
import app.peloskin.domain.CompactEdge
import app.peloskin.domain.CompactMetric
import app.peloskin.domain.Ftp
import app.peloskin.domain.FtpSource
import app.peloskin.domain.HeartRateMonitor
import app.peloskin.domain.NewRecord
import app.peloskin.domain.PersonalRecords
import app.peloskin.domain.Profile
import app.peloskin.domain.RideSummary
import app.peloskin.domain.Settings
import app.peloskin.domain.SkinMode
import app.peloskin.domain.Units
import app.peloskin.ui.overlay.Figtree
import app.peloskin.ui.overlay.HeartIcon
import app.peloskin.ui.overlay.RiderAvatar
import app.peloskin.ui.overlay.RiderBadge
import app.peloskin.ui.overlay.SkinService
import app.peloskin.ui.overlay.badgeFor
import app.peloskin.ui.overlay.rememberRiderPhotos
import app.peloskin.ui.overlay.riderColor
import app.peloskin.ui.summary.RideSummaryCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF111317)
private val Card = Color(0xFF1A1D23)
private val Raised = Color(0xFF22262D)
private val Ink = Color(0xFFE9EBEF)
private val Muted = Color(0xFF9AA1AE)
private val Line = Color(0xFF2C3038)
private val Accent = Color(0xFF41C7C7)
private val Gold = Color(0xFFE6B422)
private val Warn = Color(0xFFE6B422)
private val Danger = Color(0xFFFF6B6B)

class MainActivity : ComponentActivity() {
    /** Permissions are granted in other screens, so re-check whenever we come back. */
    private val permissions = MutableStateFlow(Perms(false, false))

    data class Perms(val overlay: Boolean, val files: Boolean)

    /** A picture change in the rider editor, kept until it's saved. */
    sealed interface PhotoEdit {
        object Unchanged : PhotoEdit
        object Removed : PhotoEdit
        data class Picked(val photo: Bitmap) : PhotoEdit
    }

    /** The rider whose heart rate monitor is being paired, or null. */
    private val pairingFor = MutableStateFlow<String?>(null)
    /** Bumped after a permission answer, so the pairing dialog re-checks and starts scanning. */
    private val permissionAnswers = MutableStateFlow(0)

    private val askBluetooth = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionAnswers.value++
    }

    /** The rider being edited: an existing rider's id, or [NewRider]. Null when the editor is closed. */
    private val editing = MutableStateFlow<String?>(null)
    private val photoEdit = MutableStateFlow<PhotoEdit>(PhotoEdit.Unchanged)
    private val photoError = MutableStateFlow<String?>(null)

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val photo = withContext(Dispatchers.IO) { RiderPhotos.decode(this@MainActivity, uri) }
            if (photo != null) photoEdit.value = PhotoEdit.Picked(photo) else photoError.value = "Couldn't open that picture."
        }
    }

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        bitmap ?: return@registerForActivityResult
        photoEdit.value = PhotoEdit.Picked(RiderPhotos.square(bitmap))
    }

    private fun canTakePhoto() = Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(packageManager) != null

    private fun choosePhoto() {
        photoError.value = null
        try {
            pickPhoto.launch("image/*")
        } catch (e: Exception) {
            photoError.value = "No app on this tablet can pick pictures. Install a file manager or gallery app."
        }
    }

    private fun refreshPermissions() {
        // File access may have just been granted: adopt the backup if this is a fresh install.
        SettingsStore.checkBackup()
        permissions.value = Perms(AndroidSettings.canDrawOverlays(this), SettingsStore.canUseBackup())
    }

    private fun requestFileAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    @Deprecated("Only used below Android 11")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.init(this)
        HeartRateLink.init(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { refreshPermissions() }
        }
        setContent { Screen() }
    }

    @Composable
    private fun Screen() {
        val settings by SettingsStore.settings.collectAsState()
        val running by SkinService.running.collectAsState()
        val lastFinished by SettingsStore.lastFinished.collectAsState()
        val editingId by editing.collectAsState()
        val photos = rememberRiderPhotos()
        var viewing by remember { mutableStateOf<FinishedRide?>(null) }
        val rider = settings.active

        // A ride that ended with the skin stopped has no overlay to show its summary, so show it here.
        val showing = viewing ?: lastFinished?.takeIf { !running }
        showing?.let { f ->
            SummaryDialog(f, settings.units, settings.badgeFor(settings.profiles.find { it.id == f.profileId }, photos)) {
                if (viewing != null) viewing = null else SettingsStore.dismissSummary()
            }
        }
        editingId?.let { id -> RiderEditor(settings, settings.profiles.find { it.id == id }, photos) }
        val pairingId by pairingFor.collectAsState()
        pairingId?.let { id -> settings.profiles.find { it.id == id }?.let { PairMonitorDialog(it) } }

        Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 1100.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    // The wordmark's baseline sits 12/72 of its height above the bottom; the
                    // subtitle's padding lines it up with that baseline.
                    Image(painterResource(R.drawable.wordmark), contentDescription = "PeloSkin", modifier = Modifier.height(56.dp))
                    Spacer(Modifier.width(20.dp))
                    Text("Your ride metrics over any app on the bike.", color = Muted, style = bodyStyle,
                        modifier = Modifier.padding(bottom = 6.dp))
                }

                SkinCard(settings)

                Heading("Who's riding")
                RiderPicker(settings, photos)
                RiderCard(settings, rider, photos) { viewing = it }

                Heading("Skin settings")
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Section("Layout", Modifier.weight(1f).fillMaxHeight()) { LayoutSettings(settings) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Section("On the bike") { BikeSettings(settings) }
                        Section("Backup") { BackupSettings() }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------- skin

    /** On/off, what it's reading, and ending a ride. The one thing used every ride, so it's first. */
    @Composable
    private fun SkinCard(settings: Settings) {
        val running by SkinService.running.collectAsState()
        val riding by SkinService.riding.collectAsState()
        val feed by SkinService.feedStatus.collectAsState()
        val source by SkinService.feedSource.collectAsState()
        val perms by permissions.collectAsState()
        val model = remember { BikeModel.detect(this) }
        val ok = feed == FeedStatus.Connected || feed == FeedStatus.Connecting
        Section(null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(if (running && ok) Accent else if (running) Warn else Muted, CircleShape))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            !running -> "Skin is off"
                            riding -> "Riding as ${settings.active.name}"
                            else -> "Skin is on"
                        },
                        color = Ink, style = titleStyle,
                    )
                    Text(
                        if (!running) "Start it, then open your class in any app." else when (feed) {
                            FeedStatus.Connected -> listOfNotNull("Reading the bike", source).joinToString(" · ") +
                                if (riding) " · hold the skin to end the ride" else ""
                            FeedStatus.Connecting -> "Connecting to the bike…"
                            FeedStatus.NotFound -> "Can't read the bike. Check that the sensor cable is plugged into the tablet."
                            FeedStatus.Unsupported ->
                                if (model == BikeModel.NotPeloton) "No Peloton bike on this device. Turn on Demo ride to try PeloSkin."
                                else "${model.displayName} isn't supported yet."
                        },
                        color = if (!running || ok) Muted else Warn, style = bodyStyle,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Ends the ride without turning the skin off; Stop does both.
                    if (running && riding) GhostButton(onClick = { SkinService.requestEndRide() }) { Text("End ride") }
                    if (running) GhostButton(onClick = { SkinService.stop(this@MainActivity) }) { Text("Stop") }
                    else PrimaryButton(onClick = { SkinService.start(this@MainActivity) }, enabled = perms.overlay) { Text("Start") }
                }
            }
            if (!perms.overlay) PermissionRow("PeloSkin needs permission to draw over other apps.", "Allow") {
                startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
    }

    // ------------------------------------------------------------ riders

    /** Every rider as a picture; tap one to ride as them. The last tile adds a rider. */
    @Composable
    private fun RiderPicker(settings: Settings, photos: Map<String, androidx.compose.ui.graphics.ImageBitmap>) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            settings.profiles.forEach { p ->
                val active = p.id == settings.active.id
                RiderTile(p.name, active, onClick = { SettingsStore.setActiveProfile(p.id) }) {
                    RiderAvatar(settings.badgeFor(p, photos)!!, Modifier.fillMaxSize(), fontSize = 26.sp)
                }
            }
            RiderTile("Add rider", active = false, onClick = { openEditor(NewRider) }) {
                Box(Modifier.fillMaxSize().border(1.5.dp, Muted, CircleShape), contentAlignment = Alignment.Center) {
                    Text("+", color = Muted, style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Light, fontSize = 34.sp))
                }
            }
        }
    }

    @Composable
    private fun RiderTile(name: String, active: Boolean, onClick: () -> Unit, avatar: @Composable () -> Unit) {
        Column(
            Modifier.width(96.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The rider on the bike gets a teal ring, like the selected tab in Peloton's own app.
            Box(
                Modifier.size(84.dp).border(3.dp, if (active) Accent else Color.Transparent, CircleShape).padding(6.dp),
            ) { avatar() }
            Text(name, color = if (active) Ink else Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = if (active) bodyStyle.copy(fontWeight = FontWeight.SemiBold) else bodyStyle)
        }
    }

    /** Everything that belongs to the rider on the bike: FTP, records and rides. */
    @Composable
    private fun RiderCard(settings: Settings, rider: Profile, photos: Map<String, androidx.compose.ui.graphics.ImageBitmap>, onOpenRide: (FinishedRide) -> Unit) {
        Section(null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clickable { openEditor(rider.id) }) {
                    RiderAvatar(settings.badgeFor(rider, photos)!!, Modifier.fillMaxSize(), fontSize = 24.sp)
                }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(rider.name, color = Ink, style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 26.sp))
                    Text(
                        listOfNotNull(
                            rider.ftp?.let { "FTP $it W" } ?: "No FTP yet",
                            "${rider.lifetime.rides} ${if (rider.lifetime.rides == 1) "ride" else "rides"}",
                            rider.rides.firstOrNull()?.let { "last ride ${date(it.startedAt)}" },
                        ).joinToString(" · "),
                        color = Muted, style = bodyStyle,
                    )
                }
                GhostButton(onClick = { openEditor(rider.id) }) { Text("Edit rider") }
            }
            if (rider.lifetime.rides > 0) LifetimeRow(rider, settings.units)
            Divider()
            HeartRateSection(rider, active = rider.id == settings.active.id)
            Divider()
            Row(Modifier.height(IntrinsicSize.Min)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SubHeading("FTP")
                    FtpSection(rider)
                }
                Box(Modifier.padding(horizontal = 24.dp).width(1.dp).fillMaxHeight().background(Line))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SubHeading("Personal records")
                    RecordsSection(rider)
                }
            }
            Divider()
            SubHeading("Recent rides")
            RidesSection(rider, onOpenRide)
        }
    }

    // ---------------------------------------------------------- heart rate

    /** The rider's own monitor and max heart rate. Their monitor connects whenever they ride. */
    @Composable
    private fun HeartRateSection(rider: Profile, active: Boolean) {
        val link by HeartRateLink.state.collectAsState()
        val running by SkinService.running.collectAsState()
        val monitor = rider.heartRateMonitor
        Row(Modifier.height(IntrinsicSize.Min)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SubHeading("Heart rate monitor")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val live = monitor != null && active && running && link.monitor == monitor
                    val connected = live && link.status == HeartRateLink.Status.Connected
                    HeartIcon(if (connected) Danger else Muted, Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(monitor?.name ?: "None paired", color = Ink, style = bodyStyle)
                        Text(
                            when {
                                monitor == null -> "Pair a chest strap or armband to see heart rate on the skin."
                                !active -> "Connects when ${rider.name} is riding."
                                !running -> "Connects when the skin is on."
                                link.status == HeartRateLink.Status.Connected ->
                                    link.bpm?.let { "Connected · $it bpm" } ?: "Connected · waiting for a reading"
                                link.status == HeartRateLink.Status.Unavailable -> link.problem ?: "Unavailable"
                                else -> "Looking for it… put it on so it wakes up."
                            },
                            color = Muted, style = smallStyle,
                        )
                    }
                    if (monitor != null) {
                        TextButton(onClick = { SettingsStore.setHeartRateMonitor(rider.id, null) }) { Text("Forget", color = Muted) }
                        Spacer(Modifier.width(4.dp))
                    }
                    GhostButton(onClick = { pairingFor.value = rider.id }) { Text(if (monitor == null) "Pair" else "Change") }
                }
            }
            Box(Modifier.padding(horizontal = 24.dp).width(1.dp).fillMaxHeight().background(Line))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SubHeading("Max heart rate")
                var text by remember(rider.id, rider.maxHr) { mutableStateOf(rider.maxHr?.toString() ?: "") }
                val focus = LocalFocusManager.current
                val save = {
                    SettingsStore.setMaxHr(rider.id, text.toIntOrNull())
                    focus.clearFocus()
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { v -> text = v.filter(Char::isDigit).take(3) },
                        label = { Text("Max (bpm)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                        colors = fieldColors(),
                        modifier = Modifier.width(140.dp),
                    )
                    GhostButton(onClick = save) { Text("Save") }
                }
                Text(
                    if (rider.maxHr == null) "Sets the 5 heart rate zones. Don't know it? 220 minus your age is the usual estimate."
                    else "Zones: 1 under ${pct(rider.maxHr, 65)} · 2 to ${pct(rider.maxHr, 75)} · 3 to ${pct(rider.maxHr, 85)} · " +
                        "4 to ${pct(rider.maxHr, 95)} · 5 above",
                    color = Muted, style = smallStyle,
                )
            }
        }
    }

    private fun pct(max: Int, percent: Int) = max * percent / 100

    /** Nearby heart rate monitors to pick from, like pairing one in the Peloton app. */
    @Composable
    private fun PairMonitorDialog(rider: Profile) {
        val answers by permissionAnswers.collectAsState()
        val demo = remember { BikeModel.detect(this) == BikeModel.NotPeloton }
        // Ask for Bluetooth (or, before Android 12, location) permission the first time.
        LaunchedEffect(Unit) {
            val missing = HeartRateLink.permissions().filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) askBluetooth.launch(missing.toTypedArray())
        }
        val problem = remember(answers) { HeartRateLink.scanProblem() }
        val found by remember(answers) { HeartRateLink.scan(includeDemo = demo) }.collectAsState(initial = emptyList())
        val close = { pairingFor.value = null }
        Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                Modifier.width(560.dp).background(Card, RoundedCornerShape(12.dp)).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Pair ${rider.name}'s heart rate monitor", color = Ink, style = titleStyle)
                Text("Put your strap on (most only turn on against skin), then tap it below. It connects automatically whenever ${rider.name} rides.",
                    color = Muted, style = bodyStyle)
                problem?.let { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p, color = Warn, style = bodyStyle, modifier = Modifier.weight(1f))
                        if (p.contains("permission")) GhostButton(onClick = { askBluetooth.launch(HeartRateLink.permissions()) }) { Text("Allow") }
                    }
                }
                Column(Modifier.fillMaxWidth().background(Raised, RoundedCornerShape(8.dp)).padding(vertical = 6.dp)) {
                    if (found.isEmpty()) {
                        Text(if (problem == null) "Looking for heart rate monitors…" else "Not searching.",
                            color = Muted, style = bodyStyle, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
                    }
                    found.forEach { m ->
                        val current = rider.heartRateMonitor?.address == m.address
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                SettingsStore.setHeartRateMonitor(rider.id, HeartRateMonitor(m.address, m.name))
                                close()
                            }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            HeartIcon(Danger, Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(m.name, color = Ink, style = bodyStyle, modifier = Modifier.weight(1f))
                            if (current) Text("Paired", color = Accent, style = smallStyle, modifier = Modifier.padding(end = 12.dp))
                            SignalBars(m.rssi)
                        }
                    }
                }
                Row {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = close) { Text("Cancel", color = Ink) }
                }
            }
        }
    }

    /** Signal strength as 1–4 bars, so the strap you're wearing is easy to tell apart. */
    @Composable
    private fun SignalBars(rssi: Int) {
        val bars = when {
            rssi >= -60 -> 4
            rssi >= -70 -> 3
            rssi >= -80 -> 2
            else -> 1
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (i in 1..4) {
                Box(Modifier.width(4.dp).height((4 + i * 3).dp).background(if (i <= bars) Ink else Line, RoundedCornerShape(1.dp)))
            }
        }
    }

    /** Totals over every ride, not just the ones kept in history. */
    @Composable
    private fun LifetimeRow(rider: Profile, units: Units) {
        val t = rider.lifetime
        val hours = t.seconds / 3600
        val minutes = (t.seconds % 3600) / 60
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SubHeading("Lifetime")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LifetimeStat("Rides", "%,d".format(t.rides), null)
                LifetimeStat("Time", if (hours > 0) "$hours" else "$minutes", if (hours > 0) "h ${minutes} min" else "min")
                LifetimeStat("Total output", "%,d".format(t.kj), "kJ")
                LifetimeStat("Distance", "%,.0f".format(units.distance(t.miles.toFloat())), units.distanceLabel)
                LifetimeStat("Calories", "%,d".format(t.calories), "kcal")
            }
        }
    }

    @Composable
    private fun LifetimeStat(name: String, value: String, unit: String?) {
        Column {
            Text(name.uppercase(), color = Muted, style = labelStyle)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, color = Ink, style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Light, fontSize = 32.sp))
                unit?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(it, color = Muted, style = bodyStyle, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
        }
    }

    @Composable
    private fun FtpSection(rider: Profile) {
        var text by remember(rider.id, rider.ftp) { mutableStateOf(rider.ftp?.toString() ?: "") }
        val focus = LocalFocusManager.current
        // Closes the keyboard too, so it doesn't pop back up over the screen.
        val save = {
            SettingsStore.setFtp(text.toIntOrNull()?.takeIf { it > 0 })
            focus.clearFocus()
        }
        Text(
            when {
                rider.ftp == null -> "Not set yet. The zone bar appears once there's an FTP."
                rider.ftpSource == FtpSource.Ride -> "${rider.ftp} W, from the best 20 minutes on ${date(rider.ftpSetAt)}"
                else -> "${rider.ftp} W, entered ${date(rider.ftpSetAt)}"
            },
            color = Ink, style = bodyStyle,
        )
        val weeks = (System.currentTimeMillis() - rider.ftpSetAt) / (7L * 24 * 3600 * 1000)
        if (rider.ftp != null && weeks >= 8) {
            Text("It's been $weeks weeks. Time for another FTP Test Ride.", color = Warn, style = bodyStyle)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter(Char::isDigit).take(4) },
                label = { Text("FTP (watts)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                colors = fieldColors(),
                modifier = Modifier.width(160.dp),
            )
            GhostButton(onClick = save) { Text("Save") }
        }
        Toggle("Raise FTP when a ride beats it", rider.autoFtp) { SettingsStore.setAutoFtp(it) }
        Text("FTP is 95% of your best 20-minute average output. For a real number, take a 20 min FTP test and go all out.",
            color = Muted, style = smallStyle)
    }

    @Composable
    private fun RecordsSection(rider: Profile) {
        val records = rider.records
        if (records.efforts.isEmpty() && records.byLength.isEmpty()) {
            Text("Best efforts and ride records show up here after the first ride.", color = Muted, style = bodyStyle)
            return
        }
        Text("BEST EFFORTS", color = Muted, style = labelStyle)
        PersonalRecords.EffortWindows.forEach { w ->
            records.efforts[w]?.let { RecordRow(PersonalRecords.effortLabel(w), "${it.value} W", it.startedAt) }
        }
        if (records.byLength.isNotEmpty()) {
            Text("BEST TOTAL OUTPUT", color = Muted, style = labelStyle, modifier = Modifier.padding(top = 6.dp))
            records.byLength.toSortedMap().forEach { (minutes, rec) -> RecordRow("$minutes-min ride", "${rec.value} kJ", rec.startedAt) }
        }
    }

    @Composable
    private fun RecordRow(name: String, value: String, at: Long) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = Ink, style = bodyStyle, modifier = Modifier.weight(1f))
            Text(date(at), color = Muted, style = smallStyle, modifier = Modifier.padding(end = 16.dp))
            Text(value, color = Gold, style = titleStyle)
        }
    }

    @Composable
    private fun RidesSection(rider: Profile, onOpenRide: (FinishedRide) -> Unit) {
        var allRides by remember(rider.id) { mutableStateOf(false) }
        if (rider.rides.isEmpty()) {
            Text("Rides with at least a minute of pedaling show up here.", color = Muted, style = bodyStyle)
            return
        }
        Row(Modifier.padding(horizontal = 12.dp)) {
            listOf("DATE" to 1.4f, "LENGTH" to 1f, "AVG OUTPUT" to 1f, "TOTAL" to 1f, "BEST 20 MIN" to 2f).forEach { (h, w) ->
                Text(h, color = Muted, style = labelStyle, modifier = Modifier.weight(w))
            }
            Spacer(Modifier.width(96.dp))
        }
        rider.rides.take(if (allRides) Settings.MaxRides else 10).forEachIndexed { i, r ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                    .background(if (i % 2 == 0) Raised else Color.Transparent)
                    .clickable { onOpenRide(historyView(rider, r)) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(dateTime(r.startedAt), color = Ink, style = bodyStyle, modifier = Modifier.weight(1.4f))
                Text("${r.durationSec / 60} min", color = Ink, style = bodyStyle, modifier = Modifier.weight(1f))
                Text("${r.avgPower} W", color = Ink, style = bodyStyle, modifier = Modifier.weight(1f))
                Text("${r.totalKj} kJ", color = Ink, style = bodyStyle, modifier = Modifier.weight(1f))
                Text(
                    r.best20Watts?.let { "$it W → FTP ${Ftp.fromBest20(it)}" } ?: "Under 20 min",
                    color = Muted, style = bodyStyle, modifier = Modifier.weight(2f),
                )
                // Rides only raise FTP on their own; this applies a lower retest result too.
                val rideFtp = r.best20Watts?.let(Ftp::fromBest20)
                Box(Modifier.width(96.dp).height(40.dp), contentAlignment = Alignment.CenterEnd) {
                    if (rideFtp != null && rideFtp != rider.ftp) {
                        TextButton(onClick = { SettingsStore.useRideForFtp(r) }) { Text("Use FTP", color = Accent) }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tap a ride to see its summary.", color = Muted, style = smallStyle, modifier = Modifier.weight(1f))
            if (rider.rides.size > 10) {
                TextButton(onClick = { allRides = !allRides }) {
                    Text(if (allRides) "Show fewer" else "Show all ${rider.rides.size} rides", color = Accent)
                }
            }
        }
    }

    // ------------------------------------------------------- rider editor

    private fun openEditor(id: String) {
        photoEdit.value = PhotoEdit.Unchanged
        photoError.value = null
        editing.value = id
    }

    private fun closeEditor() {
        editing.value = null
        photoEdit.value = PhotoEdit.Unchanged
    }

    /** Name and picture for a new or existing rider, and removing a rider. Nothing changes until Save. */
    @Composable
    private fun RiderEditor(settings: Settings, rider: Profile?, photos: Map<String, androidx.compose.ui.graphics.ImageBitmap>) {
        val edit by photoEdit.collectAsState()
        val error by photoError.collectAsState()
        // Starts with the whole current name selected, so typing replaces it.
        var name by remember(rider?.id) {
            val n = rider?.name ?: ""
            mutableStateOf(TextFieldValue(n, selection = TextRange(0, n.length)))
        }
        var confirmRemove by remember { mutableStateOf(false) }
        val current = rider?.let { photos[it.id] }
        val shown = when (val e = edit) {
            PhotoEdit.Unchanged -> current
            PhotoEdit.Removed -> null
            is PhotoEdit.Picked -> remember(e) { e.photo.asImageBitmap() }
        }
        val index = rider?.let { r -> settings.profiles.indexOfFirst { it.id == r.id } } ?: settings.profiles.size
        val preview = RiderBadge(Profile("", name.text.ifBlank { "?" }).initials, index, shown)
        val save = {
            val id = if (rider == null) SettingsStore.addProfile(name.text) else {
                SettingsStore.renameProfile(rider.id, name.text)
                rider.id
            }
            if (id != null) {
                when (val e = edit) {
                    PhotoEdit.Unchanged -> {}
                    PhotoEdit.Removed -> RiderPhotos.set(id, null)
                    is PhotoEdit.Picked -> RiderPhotos.set(id, e.photo)
                }
                closeEditor()
            }
        }

        Dialog(onDismissRequest = ::closeEditor, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                Modifier.width(560.dp).background(Card, RoundedCornerShape(12.dp)).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(if (rider == null) "New rider" else "Edit rider", color = Ink, style = titleStyle)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RiderAvatar(preview, Modifier.size(112.dp), fontSize = 40.sp)
                    Spacer(Modifier.width(24.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton(onClick = ::choosePhoto) { Text(if (shown == null) "Choose picture" else "Change picture") }
                        if (canTakePhoto()) GhostButton(onClick = {
                            photoError.value = null
                            runCatching { takePhoto.launch(null) }.onFailure { photoError.value = "Couldn't open the camera." }
                        }) { Text("Take picture") }
                        if (shown != null) TextButton(onClick = { photoEdit.value = PhotoEdit.Removed }) {
                            Text("Remove picture", color = Muted)
                        }
                    }
                }
                error?.let { Text(it, color = Warn, style = bodyStyle) }
                val focus = remember { FocusRequester() }
                OutlinedTextField(
                    name, { if (it.text.length <= 20) name = it }, label = { Text("Name") }, singleLine = true,
                    colors = fieldColors(), modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                )
                // A new rider needs a name first, so open the keyboard straight away.
                if (rider == null) LaunchedEffect(Unit) { focus.requestFocus() }
                Text(
                    if (rider == null) "They get their own FTP, rides and records. Switch riders here or by tapping the bubble on the skin."
                    else "Each rider has their own FTP, rides and records.",
                    color = Muted, style = smallStyle,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (rider != null && settings.profiles.size > 1) {
                        TextButton(onClick = { confirmRemove = true }) { Text("Remove rider", color = Danger) }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = ::closeEditor) { Text("Cancel", color = Ink) }
                    Spacer(Modifier.width(12.dp))
                    PrimaryButton(onClick = save, enabled = name.text.isNotBlank()) { Text(if (rider == null) "Add rider" else "Save") }
                }
            }
        }

        if (confirmRemove && rider != null) {
            AlertDialog(
                onDismissRequest = { confirmRemove = false },
                title = { Text("Remove ${rider.name}?") },
                text = { Text("Their FTP, ${rider.lifetime.rides} rides, records and picture will be deleted.") },
                confirmButton = {
                    TextButton({ SettingsStore.deleteProfile(rider.id); confirmRemove = false; closeEditor() }) {
                        Text("Remove", color = Danger)
                    }
                },
                dismissButton = { TextButton({ confirmRemove = false }) { Text("Cancel", color = Ink) } },
                backgroundColor = Card, contentColor = Ink,
            )
        }
    }

    // ------------------------------------------------------ skin settings

    @Composable
    private fun LayoutSettings(settings: Settings) {
        Field("Start in") {
            Choice(listOf(SkinMode.Full to "Full", SkinMode.Compact to "Single line"), settings.startMode) { v ->
                SettingsStore.update { it.copy(startMode = v) }
            }
        }
        Field("Speed and distance") {
            Choice(listOf(Units.Imperial to "mph · miles", Units.Metric to "km/h · km"), settings.units) { v ->
                SettingsStore.update { it.copy(units = v) }
            }
        }
        Field("Single-line bar sits at the") {
            Choice(listOf(CompactEdge.Top to "Top", CompactEdge.Bottom to "Bottom"), settings.compactEdge) { v ->
                SettingsStore.update { it.copy(compactEdge = v) }
            }
        }
        Field("Single-line bar shows") {
            CompactMetric.values().toList().chunked(2).forEach { pair ->
                Row {
                    pair.forEach { m ->
                        Row(Modifier.weight(1f).clickable { toggleMetric(m) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(m in settings.compactMetrics, { toggleMetric(m) },
                                colors = CheckboxDefaults.colors(checkedColor = Accent, uncheckedColor = Muted))
                            Text(m.label, color = Ink, style = bodyStyle)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun BikeSettings(settings: Settings) {
        val demo by SettingsStore.demoRide.collectAsState()
        val model = remember { BikeModel.detect(this) }
        Toggle("Start when the bike turns on", settings.startOnBoot) { v ->
            SettingsStore.update { it.copy(startOnBoot = v) }
        }
        // Lets you try PeloSkin, or work on it, away from a Peloton.
        if (model == BikeModel.NotPeloton) {
            Toggle("Demo ride (no Peloton found on this device)", demo) { SettingsStore.setDemoRide(it) }
        }
    }

    @Composable
    private fun BackupSettings() {
        val perms by permissions.collectAsState()
        var message by remember { mutableStateOf<String?>(null) }
        if (!perms.files) {
            PermissionRow("Turn on file access so riders, rides and pictures are saved outside the app.", "Allow") {
                requestFileAccess()
            }
        } else {
            val file = SettingsStore.backupFile
            Text(
                if (file.exists()) "Saved automatically. Last backup ${dateTime(file.lastModified())}."
                else "No backup yet. One is written whenever something changes.",
                color = Ink, style = bodyStyle,
            )
            Text(file.parentFile?.path ?: file.path, color = Muted, style = smallStyle)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton(onClick = {
                    message = if (SettingsStore.backup()) "Backed up" else "Couldn't write the backup file"
                }) { Text("Back up now") }
                GhostButton(onClick = {
                    message = if (SettingsStore.restore()) "Restored from backup" else "No backup file found"
                }) { Text("Restore") }
            }
        }
        message?.let { Text(it, color = Accent, style = bodyStyle) }
    }

    // ------------------------------------------------------------ helpers

    /** A past ride as a summary card. Records it still holds get a PR badge. */
    private fun historyView(rider: Profile, ride: RideSummary): FinishedRide {
        val held = rider.records.efforts.filterValues { it.startedAt == ride.startedAt }
            .map { (sec, rec) -> NewRecord.Effort(sec, rec.value, previous = rec.value) } +
            rider.records.byLength.filterValues { it.startedAt == ride.startedAt }
                .map { (min, rec) -> NewRecord.ClassLength(min, rec.value, previous = rec.value) }
        return FinishedRide(
            profileId = rider.id, profileName = rider.name, ride = ride, detail = null,
            previous = PersonalRecords.previousSimilar(rider.rides, ride), newRecords = held, newFtp = null,
        )
    }

    @Composable
    private fun SummaryDialog(f: FinishedRide, units: Units, rider: RiderBadge?, onClose: () -> Unit) {
        // Past rides load their graph from storage.
        var detail by remember(f) { mutableStateOf(f.detail) }
        LaunchedEffect(f) {
            if (detail == null) detail = withContext(Dispatchers.IO) { SettingsStore.loadDetail(f.profileId, f.ride.startedAt) }
        }
        Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(
                Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                RideSummaryCard(f.copy(detail = detail), units, Modifier.verticalScroll(rememberScrollState()),
                    footer = "Tap anywhere to close", rider = rider)
            }
        }
    }

    private fun toggleMetric(m: CompactMetric) = SettingsStore.update { s ->
        val list = if (m in s.compactMetrics) s.compactMetrics - m else s.compactMetrics + m
        // Keep the user's chosen set in the fixed display order.
        s.copy(compactMetrics = CompactMetric.values().filter { it in list })
    }

    private fun date(t: Long) = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(t))
    private fun dateTime(t: Long) = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(t))

    private companion object {
        /** [editing] value for a rider that doesn't exist yet. */
        const val NewRider = ""
    }
}

private val bodyStyle = TextStyle(fontFamily = Figtree, fontSize = 16.sp)
private val labelStyle = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 1.5.sp)
private val smallStyle = TextStyle(fontFamily = Figtree, fontSize = 13.sp)
private val titleStyle = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 20.sp)

/** A group heading between cards. */
@Composable
private fun Heading(text: String) = Text(
    text.uppercase(), color = Muted, modifier = Modifier.padding(top = 8.dp),
    style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 1.8.sp),
)

/** A heading inside a card. */
@Composable
private fun SubHeading(text: String) = Text(
    text.uppercase(), color = Accent,
    style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 1.5.sp),
)

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Line))

@Composable
private fun Section(name: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Card, RoundedCornerShape(10.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        name?.let { SubHeading(it) }
        content()
    }
}

/** A setting's label above its control. */
@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Muted, style = bodyStyle)
        content()
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onChange(!value) }) {
        Text(label, color = Ink, style = bodyStyle, modifier = Modifier.weight(1f))
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Accent))
    }
}

@Composable
private fun <T> Choice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { (value, label) ->
            if (value == selected) PrimaryButton(onClick = {}) { Text(label) }
            else GhostButton(onClick = { onSelect(value) }) { Text(label) }
        }
    }
}

@Composable
private fun PermissionRow(text: String, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Warn, style = bodyStyle, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        GhostButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun PrimaryButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) = Button(
    onClick = onClick, enabled = enabled,
    colors = ButtonDefaults.buttonColors(backgroundColor = Accent, contentColor = Color(0xFF0E1012)),
) { content() }

@Composable
private fun GhostButton(onClick: () -> Unit, content: @Composable () -> Unit) = OutlinedButton(
    onClick = onClick,
    border = BorderStroke(1.dp, Muted),
    colors = ButtonDefaults.outlinedButtonColors(backgroundColor = Color.Transparent, contentColor = Ink),
) { content() }

@Composable
private fun fieldColors(): TextFieldColors = TextFieldDefaults.outlinedTextFieldColors(
    textColor = Ink, focusedBorderColor = Accent, unfocusedBorderColor = Muted,
    focusedLabelColor = Accent, unfocusedLabelColor = Muted, cursorColor = Accent,
)
