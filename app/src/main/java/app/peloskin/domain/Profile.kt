package app.peloskin.domain

/** One rider on the bike, with their own FTP, history and records. */
data class Profile(
    val id: String,
    val name: String,
    val ftp: Int? = null,
    val ftpSource: FtpSource = FtpSource.Manual,
    val ftpSetAt: Long = 0L,
    val autoFtp: Boolean = true,
    /** Newest first, at most [Settings.MaxRides]. */
    val rides: List<RideSummary> = emptyList(),
    val records: Records = Records(),
) {
    companion object {
        const val FirstId = "rider-1"
        fun first() = Profile(FirstId, "Rider 1")
    }
}

/** A personal best and the ride it came from. */
data class Record(val value: Int, val startedAt: Long)

data class Records(
    /** Best average watts, keyed by effort length in seconds (see [PersonalRecords.EffortWindows]). */
    val efforts: Map<Int, Record> = emptyMap(),
    /** Best total output in kJ, keyed by class length in minutes. */
    val byLength: Map<Int, Record> = emptyMap(),
)

/** A record broken by a ride. [previous] is null for the first ride of that kind. */
sealed interface NewRecord {
    val value: Int
    val previous: Int?

    data class Effort(val seconds: Int, override val value: Int, override val previous: Int?) : NewRecord
    data class ClassLength(val minutes: Int, override val value: Int, override val previous: Int?) : NewRecord
}

object PersonalRecords {
    /** Effort lengths tracked for best average output: 5 s, 1 min, 5 min, 20 min. */
    val EffortWindows = listOf(5, 60, 300, 1200)

    /** Class lengths rides are grouped into for total-output records, in minutes. */
    private val ClassLengths = listOf(5, 10, 15, 20, 30, 45, 60, 75, 90, 120)

    /**
     * The class length a ride most likely was, if it's within 15% of one. A ride is a few
     * seconds off its class length because PeloSkin counts from the first to the last pedal stroke.
     */
    fun classLength(durationSec: Int): Int? {
        val minutes = durationSec / 60.0
        val nearest = ClassLengths.minBy { kotlin.math.abs(it - minutes) }
        return nearest.takeIf { kotlin.math.abs(nearest - minutes) <= nearest * 0.15 }
    }

    fun effortLabel(seconds: Int) = if (seconds < 60) "$seconds sec" else "${seconds / 60} min"

    /** Folds a finished ride into the records. @return the updated records and what it broke. */
    fun apply(records: Records, ride: RideSummary): Pair<Records, List<NewRecord>> {
        val broken = mutableListOf<NewRecord>()
        val efforts = records.efforts.toMutableMap()
        for ((seconds, watts) in ride.bestEfforts) {
            val old = efforts[seconds]
            if (watts > 0 && (old == null || watts > old.value)) {
                efforts[seconds] = Record(watts, ride.startedAt)
                broken += NewRecord.Effort(seconds, watts, old?.value)
            }
        }
        val byLength = records.byLength.toMutableMap()
        classLength(ride.durationSec)?.let { minutes ->
            val old = byLength[minutes]
            if (ride.totalKj > 0 && (old == null || ride.totalKj > old.value)) {
                byLength[minutes] = Record(ride.totalKj, ride.startedAt)
                broken += NewRecord.ClassLength(minutes, ride.totalKj, old?.value)
            }
        }
        return Records(efforts, byLength) to broken
    }

    /** The most recent earlier ride of the same class length, for comparing. */
    fun previousSimilar(rides: List<RideSummary>, ride: RideSummary): RideSummary? {
        val length = classLength(ride.durationSec)
        return rides.firstOrNull { other ->
            other.startedAt < ride.startedAt && if (length != null) {
                classLength(other.durationSec) == length
            } else {
                kotlin.math.abs(other.durationSec - ride.durationSec) <= ride.durationSec * 0.15
            }
        }
    }
}
