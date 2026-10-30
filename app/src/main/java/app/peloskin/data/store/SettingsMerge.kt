package app.peloskin.data.store

import app.peloskin.domain.Profile
import app.peloskin.domain.Record
import app.peloskin.domain.Records
import app.peloskin.domain.Settings

/** Combines the app's own settings with a backup found later, without losing either. */
object SettingsMerge {
    /**
     * @param local the app's current settings, or null on a fresh install with nothing changed yet.
     * Riders are matched by id. Each rider's ride history is combined, FTP comes from whichever
     * copy set it more recently, and records keep the better value. Everything else keeps the
     * local choice, since that's what the rider has been seeing.
     */
    fun merge(local: Settings?, backup: Settings): Settings {
        if (local == null) return backup
        val backupById = backup.profiles.associateBy { it.id }
        val merged = local.profiles.map { p -> backupById[p.id]?.let { mergeProfile(p, it) } ?: p }
        val onlyInBackup = backup.profiles.filter { b -> local.profiles.none { it.id == b.id } }
        return local.copy(profiles = merged + onlyInBackup)
    }

    private fun mergeProfile(local: Profile, backup: Profile): Profile {
        val rides = (local.rides + backup.rides)
            .distinctBy { it.startedAt }
            .sortedByDescending { it.startedAt }
            .take(Settings.MaxRides)
        val records = Records(
            efforts = best(local.records.efforts, backup.records.efforts),
            byLength = best(local.records.byLength, backup.records.byLength),
        )
        val merged = local.copy(rides = rides, records = records)
        val backupFtpIsNewer = backup.ftp != null && (local.ftp == null || backup.ftpSetAt > local.ftpSetAt)
        return if (backupFtpIsNewer) {
            merged.copy(ftp = backup.ftp, ftpSource = backup.ftpSource, ftpSetAt = backup.ftpSetAt)
        } else {
            merged
        }
    }

    private fun best(a: Map<Int, Record>, b: Map<Int, Record>): Map<Int, Record> =
        (a.keys + b.keys).associateWith { k ->
            listOfNotNull(a[k], b[k]).maxBy { it.value }
        }
}
