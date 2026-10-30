package app.peloskin.data.store

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.util.AtomicFile
import app.peloskin.domain.Ftp
import app.peloskin.domain.FtpSource
import app.peloskin.domain.PersonalRecords
import app.peloskin.domain.Profile
import app.peloskin.domain.RideCheckpoint
import app.peloskin.domain.RideDetail
import app.peloskin.domain.RideSummary
import app.peloskin.domain.Settings
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Settings, FTP and ride history. Saved in the app's own storage, and mirrored to
 * Documents/PeloSkin/peloskin-backup.json so a reinstall can pick everything back up.
 */
object SettingsStore {
    private const val Tag = "PeloSkin"

    private lateinit var appContext: Context
    private lateinit var appFile: File
    private lateinit var checkpointFile: File

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings

    /**
     * Demo ride for trying PeloSkin on a device that isn't a Peloton. Kept out of the backup.
     */
    private val _demoRide = MutableStateFlow(false)
    val demoRide: StateFlow<Boolean> = _demoRide

    fun setDemoRide(on: Boolean) {
        _demoRide.value = on
        prefs.edit().putBoolean("demoRide", on).apply()
    }

    /**
     * A short message for the skin: a new FTP, a new PR, or who's riding now. [riderSwitch]
     * marks "Riding as …", which the full layout leaves to its rider bubble.
     */
    data class Banner(val text: String, val riderSwitch: Boolean = false)

    private val _banner = MutableStateFlow<Banner?>(null)
    val banner: StateFlow<Banner?> = _banner

    fun showBanner(text: String, riderSwitch: Boolean = false) { _banner.value = Banner(text, riderSwitch) }
    fun clearBanner() { _banner.value = null }

    /** The ride that just ended, for the summary card. Cleared once it's been seen. */
    private val _lastFinished = MutableStateFlow<FinishedRide?>(null)
    val lastFinished: StateFlow<FinishedRide?> = _lastFinished

    fun dismissSummary() { _lastFinished.value = null }

    /**
     * False until the backup file has been read, or confirmed missing. Until then we never
     * write it, so an install that can't see the backup yet can't overwrite it. Saved, so it
     * survives the app restarting before file access is granted.
     */
    @Volatile private var backupChecked = false

    /** Whether there are local settings worth keeping when a backup turns up. */
    private var haveLocal = false

    /** File writes happen here, one at a time, so the overlay never waits on storage. */
    private val io = Executors.newSingleThreadExecutor()

    private val prefs get() = appContext.getSharedPreferences("peloskin", Context.MODE_PRIVATE)

    val backupFile: File
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "PeloSkin/peloskin-backup.json",
        )

    fun canUseBackup(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            appContext.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    @Synchronized
    fun init(context: Context) {
        if (::appFile.isInitialized) return
        appContext = context.applicationContext
        appFile = File(context.filesDir, "settings.json")
        checkpointFile = File(context.filesDir, "ride-checkpoint.json")
        _demoRide.value = prefs.getBoolean("demoRide", false)
        backupChecked = prefs.getBoolean("backupChecked", false)
        val local = read(appFile)
        haveLocal = local != null
        _settings.value = local ?: Settings()
        RiderPhotos.init(appContext)
        checkBackup()
    }

    /**
     * Picks up the backup once we're allowed to read it, merging it with anything set here.
     * Safe to call often; it only does work once. Call it when file access may have been granted.
     */
    fun checkBackup() {
        if (backupChecked) return
        io.execute { checkBackupNow() }
    }

    private fun checkBackupNow() {
        if (backupChecked || !canUseBackup()) return
        val file = backupFile
        if (file.exists()) {
            val found = read(file)
            if (found != null) {
                val merged = synchronized(this) {
                    SettingsMerge.merge(if (haveLocal) _settings.value else null, found).also {
                        _settings.value = it
                        haveLocal = true
                    }
                }
                Log.i(Tag, "Merged settings from backup")
                write(appFile, merged)
                pruneDetails()
            } else {
                // Keep an unreadable backup aside instead of overwriting it.
                val aside = File(file.parentFile, "peloskin-backup.unreadable-${System.currentTimeMillis()}.json")
                file.renameTo(aside)
                Log.w(Tag, "Backup unreadable, kept as ${aside.name}")
            }
        }
        backupChecked = true
        prefs.edit().putBoolean("backupChecked", true).apply()
        write(backupFile, _settings.value)
        // Graphs and pictures saved before file access was allowed go into the backup now.
        copyDetailsToBackup()
        RiderPhotos.syncWithBackup()
    }

    /** Whether the backup folder has been read and can be written to. */
    fun backupReady(): Boolean = backupChecked && canUseBackup()

    fun update(change: (Settings) -> Settings) {
        val next = synchronized(this) {
            change(_settings.value).also {
                _settings.value = it
                haveLocal = true
            }
        }
        io.execute {
            // Save what's current when this runs, not `next`: a backup merge may have
            // happened in between, and writing `next` would undo it.
            val text = SettingsJson.encode(_settings.value)
            writeOrLog(appFile, text)
            if (backupChecked) {
                if (canUseBackup()) writeOrLog(backupFile, text)
            } else {
                checkBackupNow()
            }
        }
    }

    // ---------------------------------------------------------------- riders

    fun setActiveProfile(id: String) {
        val current = _settings.value
        if (current.activeProfileId == id || current.profiles.none { it.id == id }) return
        update { it.copy(activeProfileId = id) }
        showBanner("Riding as ${_settings.value.active.name}", riderSwitch = true)
    }

    /** Switches to the next rider, for the chip on the skin. */
    fun nextProfile() {
        val s = _settings.value
        if (s.profiles.size < 2) return
        val i = s.profiles.indexOfFirst { it.id == s.active.id }
        setActiveProfile(s.profiles[(i + 1) % s.profiles.size].id)
    }

    /**
     * Adds a rider without switching to them, so a ride in progress keeps its rider.
     * @return the new rider's id, or null if the name was blank.
     */
    fun addProfile(name: String): String? {
        val clean = name.trim().take(20).ifEmpty { return null }
        var id = ""
        update { s ->
            id = "rider-${System.currentTimeMillis()}"
            while (s.profiles.any { it.id == id }) id += "x"
            s.copy(profiles = s.profiles + Profile(id, clean))
        }
        return id
    }

    fun renameProfile(id: String, name: String) {
        val clean = name.trim().take(20).ifEmpty { return }
        update { s -> s.copy(profiles = s.profiles.map { if (it.id == id) it.copy(name = clean) else it }) }
    }

    /** Removes a rider, their history and their ride graphs. The last rider can't be removed. */
    fun deleteProfile(id: String) {
        update { s ->
            if (s.profiles.size < 2) return@update s
            val rest = s.profiles.filter { it.id != id }
            s.copy(profiles = rest, activeProfileId = if (s.activeProfileId == id) rest.first().id else s.activeProfileId)
        }
        io.execute { pruneDetails() }
        RiderPhotos.prune(_settings.value.profiles.map { it.id }.toSet())
    }

    // ------------------------------------------------------------- FTP & rides

    fun setFtp(ftp: Int?, source: FtpSource = FtpSource.Manual, at: Long = System.currentTimeMillis()) = update { s ->
        s.withActive { it.copy(ftp = ftp?.takeIf { f -> f > 0 }, ftpSource = source, ftpSetAt = at) }
    }

    fun setAutoFtp(on: Boolean) = update { s -> s.withActive { it.copy(autoFtp = on) } }

    /** Use a ride's best 20 minutes as FTP, even if it's lower (a retest). */
    fun useRideForFtp(ride: RideSummary) {
        val best20 = ride.best20Watts ?: return
        setFtp(Ftp.fromBest20(best20), FtpSource.Ride, ride.startedAt)
    }

    /**
     * Saves a finished ride for the current rider: history, records, FTP and its graph, and
     * offers it as [lastFinished] for the summary card.
     */
    fun recordRide(ride: RideSummary, detail: RideDetail) {
        var finished: FinishedRide? = null
        update { s ->
            s.withActive { p ->
                val previous = PersonalRecords.previousSimilar(p.rides, ride)
                val (records, broken) = PersonalRecords.apply(p.records, ride)
                val newFtp = Ftp.afterRide(p.ftp, p.autoFtp, ride)
                finished = FinishedRide(p.id, p.name, ride, detail, previous, broken, newFtp)
                val rides = (listOf(ride) + p.rides).take(Settings.MaxRides)
                val updated = p.copy(rides = rides, records = records, lifetime = p.lifetime + ride)
                if (newFtp != null) updated.copy(ftp = newFtp, ftpSource = FtpSource.Ride, ftpSetAt = ride.startedAt) else updated
            }
        }
        val f = finished ?: return
        io.execute { saveDetail(f.profileId, detail) }
        PersonalRecords.banner(f.newFtp, f.newRecords)?.let(::showBanner)
        _lastFinished.value = f
    }

    /** Shows a demo ride's summary without saving anything. */
    fun showDemoSummary(ride: RideSummary, detail: RideDetail) {
        _lastFinished.value = FinishedRide("demo", "Demo ride", ride, detail, previous = null, newRecords = emptyList(), newFtp = null)
    }

    /** A ride's graph, from the app's own copy or the backup. */
    fun loadDetail(profileId: String, startedAt: Long): RideDetail? {
        for (dir in listOfNotNull(appRidesDir, backupRidesDir.takeIf { canUseBackup() })) {
            val f = File(dir, "$profileId/$startedAt.json")
            if (f.exists()) {
                val d = runCatching { RideDetailJson.decode(AtomicFile(f).readFully().decodeToString()) }.getOrNull()
                if (d != null) return d
            }
        }
        return null
    }

    private val appRidesDir: File get() = File(appContext.filesDir, "rides")
    private val backupRidesDir: File get() = File(backupFile.parentFile, "rides")

    private fun saveDetail(profileId: String, detail: RideDetail) {
        val text = RideDetailJson.encode(detail)
        val name = "$profileId/${detail.startedAt}.json"
        runCatching { writeText(File(appRidesDir, name), text) }.onFailure { Log.w(Tag, "Could not save ride graph", it) }
        if (backupChecked && canUseBackup()) {
            runCatching { writeText(File(backupRidesDir, name), text) }.onFailure { Log.w(Tag, "Could not back up ride graph", it) }
        }
        pruneDetails()
    }

    /**
     * Graphs for rides that have dropped out of history, or belong to a removed rider, aren't
     * needed any more, in either folder. Runs on [io], after the settings that reference them.
     */
    private fun pruneDetails() {
        val keep = _settings.value.profiles.flatMap { p -> p.rides.map { "${p.id}/${it.startedAt}.json" } }.toSet()
        val dirs = listOfNotNull(appRidesDir, backupRidesDir.takeIf { backupChecked && canUseBackup() })
        for (dir in dirs) {
            dir.listFiles()?.forEach { profileDir ->
                profileDir.listFiles()?.forEach { f -> if ("${profileDir.name}/${f.name}" !in keep) f.delete() }
                if (profileDir.listFiles()?.isEmpty() == true) profileDir.delete()
            }
        }
    }

    private fun copyDetailsToBackup() {
        if (!canUseBackup()) return
        appRidesDir.listFiles()?.forEach { profileDir ->
            profileDir.listFiles()?.forEach { f ->
                val target = File(backupRidesDir, "${profileDir.name}/${f.name}")
                if (!target.exists()) runCatching { writeText(target, AtomicFile(f).readFully().decodeToString()) }
            }
        }
    }

    /** Writes the backup now. @return true if it was written. */
    fun backup(): Boolean {
        if (!backupChecked) checkBackupNow()
        if (!backupChecked || !canUseBackup()) return false
        return write(backupFile, _settings.value)
    }

    /** Replaces everything with the backup file. @return true if it was loaded. */
    fun restore(): Boolean {
        if (!canUseBackup()) return false
        val s = read(backupFile) ?: return false
        synchronized(this) {
            _settings.value = s
            haveLocal = true
        }
        io.execute { write(appFile, s) }
        backupChecked = true
        prefs.edit().putBoolean("backupChecked", true).apply()
        RiderPhotos.syncWithBackup(replace = true)
        return true
    }

    // The in-progress ride, kept only in app storage (not the backup).
    fun saveCheckpoint(checkpoint: RideCheckpoint?) {
        io.execute {
            try {
                if (checkpoint == null) checkpointFile.delete() else writeText(checkpointFile, CheckpointJson.encode(checkpoint))
            } catch (e: Exception) {
                Log.w(Tag, "Could not save ride checkpoint", e)
            }
        }
    }

    fun loadCheckpoint(): RideCheckpoint? = try {
        if (checkpointFile.exists()) CheckpointJson.decode(AtomicFile(checkpointFile).readFully().decodeToString()) else null
    } catch (e: Exception) {
        null
    }

    private fun read(file: File): Settings? = try {
        if (file.exists()) SettingsJson.decode(AtomicFile(file).readFully().decodeToString()) else null
    } catch (e: Exception) {
        Log.w(Tag, "Could not read ${file.path}", e)
        null
    }

    private fun write(file: File, s: Settings): Boolean = writeOrLog(file, SettingsJson.encode(s))

    private fun writeOrLog(file: File, text: String): Boolean = try {
        writeText(file, text)
        true
    } catch (e: Exception) {
        Log.w(Tag, "Could not write ${file.path}", e)
        false
    }

    private fun writeText(file: File, text: String) = writeBytes(file, text.toByteArray())

    /** Writes to a temp file, syncs it, then swaps it in, so a power cut can't leave half a file. */
    fun writeBytes(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }
}
