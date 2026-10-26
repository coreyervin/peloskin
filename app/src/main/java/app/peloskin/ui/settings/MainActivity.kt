package app.peloskin.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.OutlinedButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.peloskin.data.bike.BikeModel
import app.peloskin.data.bike.FeedStatus
import app.peloskin.ui.overlay.Figtree
import app.peloskin.ui.overlay.SkinService
import app.peloskin.domain.Ftp
import app.peloskin.domain.FtpSource
import app.peloskin.domain.CompactEdge
import app.peloskin.domain.CompactMetric
import app.peloskin.data.store.SettingsStore
import app.peloskin.domain.SkinMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF111317)
private val Card = Color(0xFF1A1D23)
private val Ink = Color(0xFFE9EBEF)
private val Muted = Color(0xFF9AA1AE)
private val Accent = Color(0xFF41C7C7)
private val Warn = Color(0xFFE6B422)

class MainActivity : ComponentActivity() {
    /** Permissions are granted in other screens, so re-check whenever we come back. */
    private val permissions = MutableStateFlow(Perms(false, false))

    data class Perms(val overlay: Boolean, val files: Boolean)

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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { refreshPermissions() }
        }
        setContent { Screen() }
    }

    @Composable
    private fun Screen() {
        val settings by SettingsStore.settings.collectAsState()
        val running by SkinService.running.collectAsState()
        val feed by SkinService.feedStatus.collectAsState()
        val source by SkinService.feedSource.collectAsState()
        val demo by SettingsStore.demoRide.collectAsState()
        val model = remember { BikeModel.detect(this) }
        val perms by permissions.collectAsState()
        var message by remember { mutableStateOf<String?>(null) }

        Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 1100.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text("PeloSkin", color = Ink, style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 34.sp))
                Text("Peloton-style ride metrics, read straight from your bike.", color = Muted, style = bodyStyle)

                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Section("Skin") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(if (running) "Showing" else "Off", color = Ink, style = titleStyle)
                                    if (running) Text(
                                        when (feed) {
                                            FeedStatus.Connected -> listOfNotNull("Reading the bike", source).joinToString(" · ")
                                            FeedStatus.Connecting -> "Connecting to the bike…"
                                            FeedStatus.NotFound -> "Can't read the bike. Check that the sensor cable is plugged into the tablet."
                                            FeedStatus.Unsupported ->
                                                if (model == BikeModel.NotPeloton) "No Peloton bike on this device. Turn on Demo ride to try PeloSkin."
                                                else "${model.displayName} isn't supported yet."
                                        },
                                        color = if (feed == FeedStatus.Connected || feed == FeedStatus.Connecting) Muted else Warn,
                                        style = bodyStyle,
                                    )
                                }
                                if (running) GhostButton(onClick = { SkinService.stop(this@MainActivity) }) { Text("Stop") }
                                else Button(
                                    onClick = { SkinService.start(this@MainActivity) },
                                    enabled = perms.overlay,
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Accent),
                                ) { Text("Start") }
                            }
                            if (!perms.overlay) PermissionRow(
                                "PeloSkin needs permission to draw over other apps.",
                                "Allow",
                            ) {
                                startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                            }
                            Toggle("Start when the bike turns on", settings.startOnBoot) { v ->
                                SettingsStore.update { it.copy(startOnBoot = v) }
                            }
                            // Lets you try PeloSkin, or work on it, away from a Peloton.
                            if (model == BikeModel.NotPeloton) {
                                Toggle("Demo ride (no Peloton found on this device)", demo) { SettingsStore.setDemoRide(it) }
                            }
                        }

                        Section("FTP") { FtpSection(settings) }
                    }

                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Section("Layout") {
                            Text("Start in", color = Muted, style = bodyStyle)
                            Choice(listOf(SkinMode.Full to "Full", SkinMode.Compact to "Single line"), settings.startMode) { v ->
                                SettingsStore.update { it.copy(startMode = v) }
                            }
                            Text("Single-line bar sits at the", color = Muted, style = bodyStyle)
                            Choice(listOf(CompactEdge.Top to "Top", CompactEdge.Bottom to "Bottom"), settings.compactEdge) { v ->
                                SettingsStore.update { it.copy(compactEdge = v) }
                            }
                            Text("Single-line bar shows", color = Muted, style = bodyStyle)
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

                        Section("Backup") {
                            Text(SettingsStore.backupFile.path, color = Muted, style = smallStyle)
                            if (!perms.files) PermissionRow(
                                "Turn on file access so your FTP and rides are saved outside the app.",
                                "Allow",
                            ) {
                                requestFileAccess()
                            } else {
                                val file = SettingsStore.backupFile
                                Text(
                                    if (file.exists()) "Saved automatically. Last backup ${dateTime(file.lastModified())}."
                                    else "No backup yet. One is written whenever something changes.",
                                    color = Ink, style = bodyStyle,
                                )
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
                    }
                }

                Section("Recent rides") {
                    if (settings.rides.isEmpty()) {
                        Text("Rides with at least 5 minutes of pedaling show up here.", color = Muted, style = bodyStyle)
                    }
                    settings.rides.take(10).forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(dateTime(r.startedAt), color = Ink, style = bodyStyle, modifier = Modifier.weight(1.4f))
                            Text("${r.durationSec / 60} min", color = Ink, style = bodyStyle, modifier = Modifier.weight(1f))
                            Text("${r.avgPower} W avg", color = Ink, style = bodyStyle, modifier = Modifier.weight(1f))
                            Text("${r.totalKj} kJ", color = Ink, style = bodyStyle, modifier = Modifier.weight(1f))
                            Text(
                                r.best20Watts?.let { "Best 20 min ${it} W → FTP ${Ftp.fromBest20(it)}" } ?: "Under 20 min",
                                color = Muted, style = bodyStyle, modifier = Modifier.weight(2f),
                            )
                            // Rides only raise FTP on their own; this applies a lower retest result too.
                            val rideFtp = r.best20Watts?.let(Ftp::fromBest20)
                            Box(Modifier.width(96.dp), contentAlignment = Alignment.CenterEnd) {
                                if (rideFtp != null && rideFtp != settings.ftp) {
                                    GhostButton(onClick = { SettingsStore.useRideForFtp(r) }) { Text("Use") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun FtpSection(settings: app.peloskin.domain.Settings) {
        var text by remember(settings.ftp) { mutableStateOf(settings.ftp?.toString() ?: "") }
        Text(
            when {
                settings.ftp == null -> "Not set yet. The zone bar appears once you have an FTP."
                settings.ftpSource == FtpSource.Ride -> "${settings.ftp} W, from your best 20 minutes on ${date(settings.ftpSetAt)}"
                else -> "${settings.ftp} W, entered ${date(settings.ftpSetAt)}"
            },
            color = Ink, style = bodyStyle,
        )
        val weeks = (System.currentTimeMillis() - settings.ftpSetAt) / (7L * 24 * 3600 * 1000)
        if (settings.ftp != null && weeks >= 8) {
            Text("It's been $weeks weeks. Time for another FTP Test Ride.", color = Warn, style = bodyStyle)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter(Char::isDigit).take(4) },
                label = { Text("FTP (watts)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = TextFieldDefaults.outlinedTextFieldColors(textColor = Ink, focusedBorderColor = Accent,
                    unfocusedBorderColor = Muted, focusedLabelColor = Accent, unfocusedLabelColor = Muted, cursorColor = Accent),
                modifier = Modifier.width(180.dp),
            )
            GhostButton(onClick = { SettingsStore.setFtp(text.toIntOrNull()?.takeIf { it > 0 }) }) { Text("Save") }
        }
        Toggle("Raise FTP automatically when a ride beats it", settings.autoFtp) { v ->
            SettingsStore.update { it.copy(autoFtp = v) }
        }
        Text("FTP is 95% of your best 20-minute average output. For a real number, take the 20 min FTP Test Ride and go all out.",
            color = Muted, style = smallStyle)
    }

    private fun toggleMetric(m: CompactMetric) = SettingsStore.update { s ->
        val list = if (m in s.compactMetrics) s.compactMetrics - m else s.compactMetrics + m
        // Keep the user's chosen set in the fixed display order.
        s.copy(compactMetrics = CompactMetric.values().filter { it in list })
    }

    private fun date(t: Long) = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(t))
    private fun dateTime(t: Long) = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(t))
}

private val bodyStyle = TextStyle(fontFamily = Figtree, fontSize = 16.sp)
private val smallStyle = TextStyle(fontFamily = Figtree, fontSize = 13.sp)
private val titleStyle = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 20.sp)

@Composable
private fun Section(name: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Card, RoundedCornerShape(10.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(name.uppercase(), color = Accent, style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium,
            fontSize = 13.sp, letterSpacing = 1.5.sp))
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
            if (value == selected) Button(onClick = {}, colors = ButtonDefaults.buttonColors(backgroundColor = Accent)) { Text(label) }
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
private fun GhostButton(onClick: () -> Unit, content: @Composable () -> Unit) = OutlinedButton(
    onClick = onClick,
    border = BorderStroke(1.dp, Muted),
    colors = ButtonDefaults.outlinedButtonColors(backgroundColor = Color.Transparent, contentColor = Ink),
) { content() }
