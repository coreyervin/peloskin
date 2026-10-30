package app.peloskin.data.store

import app.peloskin.domain.Lifetime
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
        // A rider that's never been used (the default one on a fresh install) has nothing of
        // its own to keep, so the backed-up rider comes back as it was, name and all.
        val untouched = local.rides.isEmpty() && local.ftp == null && local.records == Records() &&
            local.name == Profile.first().name
        if (untouched) return backup

        val rides = (local.rides + backup.rides)
            .distinctBy { it.startedAt }
            .sortedByDescending { it.startedAt }
            .take(Settings.MaxRides)
        val records = Records(
            efforts = best(local.records.efforts, backup.records.efforts),
            byLength = best(local.records.byLength, backup.records.byLength),
        )
        // Neither copy's totals can simply be added: they share rides. Each total takes the larger
        // copy's, and never less than the combined history adds up to.
        val lifetime = local.lifetime.max(backup.lifetime).max(Lifetime.of(rides))
        val merged = local.copy(rides = rides, records = records, lifetime = lifetime)
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
