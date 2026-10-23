package app.peloskin.store

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.util.AtomicFile
import app.peloskin.ride.Ftp
import app.peloskin.ride.FtpSource
import app.peloskin.ride.RideSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

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

    /** Set when a ride raises the FTP, so the skin can show a short banner. */
    private val _ftpBanner = MutableStateFlow<Int?>(null)
    val ftpBanner: StateFlow<Int?> = _ftpBanner

    /**
     * False until the backup file has been read, or confirmed missing. Until then we never
     * write it, so a fresh install that can't see the backup yet can't overwrite it with defaults.
     */
    private var backupChecked = false

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
        val local = read(appFile)
        if (local != null) {
            // The app's own copy is the source of truth; the backup only mirrors it.
            _settings.value = local
            backupChecked = true
        } else {
            checkBackup()
        }
    }

    /**
     * Fresh install or lost app data: adopt the backup once we're allowed to read it.
     * Call again when file access might have just been granted.
     */
    @Synchronized
    fun checkBackup() {
        if (backupChecked || !canUseBackup()) return
        val file = backupFile
        when {
            !file.exists() -> Unit
            else -> {
                val restored = read(file)
                if (restored != null) {
                    Log.i(Tag, "Restored settings from backup")
                    _settings.value = restored
                    write(appFile, restored)
                } else {
                    // Keep an unreadable backup aside instead of overwriting it.
                    val aside = File(file.parentFile, "peloskin-backup.unreadable-${System.currentTimeMillis()}.json")
                    file.renameTo(aside)
                    Log.w(Tag, "Backup unreadable, kept as ${aside.name}")
                }
            }
        }
        backupChecked = true
    }

    @Synchronized
    fun update(change: (Settings) -> Settings) {
        val next = change(_settings.value)
        _settings.value = next
        write(appFile, next)
        backup()
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

    /** @return true if the backup file was written. */
    @Synchronized
    fun backup(): Boolean {
        checkBackup()
        if (!backupChecked || !canUseBackup()) return false
        return write(backupFile, _settings.value)
    }

    /** @return true if settings were loaded from the backup file. */
    @Synchronized
    fun restore(): Boolean {
        if (!canUseBackup()) return false
        val s = read(backupFile) ?: return false
        _settings.value = s
        write(appFile, s)
        backupChecked = true
        return true
    }

    // The in-progress ride, kept only in app storage (not the backup).
    fun saveCheckpoint(json: String?) {
        try {
            if (json == null) checkpointFile.delete() else writeText(checkpointFile, json)
        } catch (e: Exception) {
            Log.w(Tag, "Could not save ride checkpoint", e)
        }
    }

    fun loadCheckpoint(): String? = try {
        if (checkpointFile.exists()) AtomicFile(checkpointFile).readFully().decodeToString() else null
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
