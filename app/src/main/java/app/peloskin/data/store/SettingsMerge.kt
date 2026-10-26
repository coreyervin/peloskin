package app.peloskin.data.store

import app.peloskin.domain.Settings

/** Combines the app's own settings with a backup found later, without losing either. */
object SettingsMerge {
    /**
     * @param local the app's current settings, or null on a fresh install with nothing changed yet.
     * Ride history is combined. FTP comes from whichever copy set it more recently. Everything
     * else keeps the local choice, since that's what the rider has been seeing.
     */
    fun merge(local: Settings?, backup: Settings): Settings {
        if (local == null) return backup
        val rides = (local.rides + backup.rides)
            .distinctBy { it.startedAt }
            .sortedByDescending { it.startedAt }
            .take(Settings.MaxRides)
        val merged = local.copy(rides = rides)
        val backupFtpIsNewer = backup.ftp != null && (local.ftp == null || backup.ftpSetAt > local.ftpSetAt)
        return if (backupFtpIsNewer) {
            merged.copy(ftp = backup.ftp, ftpSource = backup.ftpSource, ftpSetAt = backup.ftpSetAt)
        } else {
            merged
        }
    }
}
