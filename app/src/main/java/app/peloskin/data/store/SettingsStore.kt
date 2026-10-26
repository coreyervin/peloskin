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
import app.peloskin.domain.RideCheckpoint
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

    /** Set when a ride raises the FTP, so the skin can show a short banner. */
    private val _ftpBanner = MutableStateFlow<Int?>(null)
    val ftpBanner: StateFlow<Int?> = _ftpBanner

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
    }

    fun update(change: (Settings) -> Settings) {
        val next = synchronized(this) {
            change(_settings.value).also {
                _settings.value = it
                haveLocal = true
            }
        }
        io.execute {
            write(appFile, next)
            if (backupChecked) {
                if (canUseBackup()) write(backupFile, next)
            } else {
                checkBackupNow()
            }
        }
    }

    fun setFtp(ftp: Int?, source: FtpSource = FtpSource.Manual, at: Long = System.currentTimeMillis()) = update {
        it.copy(ftp = ftp?.takeIf { f -> f > 0 }, ftpSource = source, ftpSetAt = at)
    }

    /** Use a ride's best 20 minutes as FTP, even if it's lower (a retest). */
    fun useRideForFtp(ride: RideSummary) {
        val best20 = ride.best20Watts ?: return
        setFtp(Ftp.fromBest20(best20), FtpSource.Ride, ride.startedAt)
    }

    fun recordRide(ride: RideSummary) {
        var raisedTo: Int? = null
        update { s ->
            val rides = (listOf(ride) + s.rides).take(Settings.MaxRides)
            val newFtp = Ftp.afterRide(s.ftp, s.autoFtp, ride)
            if (newFtp != null) {
                raisedTo = newFtp
                s.copy(rides = rides, ftp = newFtp, ftpSource = FtpSource.Ride, ftpSetAt = ride.startedAt)
            } else {
                s.copy(rides = rides)
            }
        }
        raisedTo?.let { _ftpBanner.value = it }
    }

    fun clearBanner() { _ftpBanner.value = null }

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

    private fun write(file: File, s: Settings): Boolean = try {
        writeText(file, SettingsJson.encode(s))
        true
    } catch (e: Exception) {
        Log.w(Tag, "Could not write ${file.path}", e)
        false
    }

    /** Writes to a temp file, syncs it, then swaps it in, so a power cut can't leave half a file. */
    private fun writeText(file: File, text: String) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(text.toByteArray())
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }
}
