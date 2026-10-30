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
    /** Totals over every ride ever saved, including ones that have dropped out of [rides]. */
    val lifetime: Lifetime = Lifetime.of(rides),
) {
    /** One or two letters for the rider's bubble: "Corey" → "C", "Jess Smith" → "JS". */
    val initials: String
        get() = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
            .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

    companion object {
        const val FirstId = "rider-1"
        fun first() = Profile(FirstId, "Rider 1")
    }
}

/** A rider's totals over all their rides. */
data class Lifetime(
    val rides: Int = 0,
    val seconds: Long = 0,
    val kj: Long = 0,
    val miles: Double = 0.0,
    val calories: Long = 0,
) {
    operator fun plus(r: RideSummary) = Lifetime(
        rides + 1, seconds + r.durationSec, kj + r.totalKj, miles + r.distanceMi, calories + r.calories,
    )

    /** The larger of each total, for combining two copies of the same rider's history. */
    fun max(other: Lifetime) = Lifetime(
        maxOf(rides, other.rides), maxOf(seconds, other.seconds), maxOf(kj, other.kj),
        maxOf(miles, other.miles), maxOf(calories, other.calories),
    )

    companion object {
        /** Totals for a list of rides, for history saved before lifetime totals existed. */
        fun of(rides: List<RideSummary>) = rides.fold(Lifetime()) { total, r -> total + r }
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

    /** Works records out from scratch, for history saved before records existed. */
    fun rebuild(rides: List<RideSummary>): Records =
        rides.sortedBy { it.startedAt }.fold(Records()) { records, ride -> apply(records, ride).first }

    /**
     * The banner text for a finished ride, or null if there's nothing to announce. Only
     * records that beat an earlier one count, so a rider's first ride isn't all "PR"s.
     */
    fun banner(newFtp: Int?, broken: List<NewRecord>): String? {
        val prs = broken.filter { it.previous != null }
        val parts = listOfNotNull(
            newFtp?.let { "New FTP $it" },
            prs.takeIf { it.isNotEmpty() }?.let { "New PR · " + it.joinToString(" · ") { r -> r.label() } },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
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

fun NewRecord.label(): String = when (this) {
    is NewRecord.Effort -> "${PersonalRecords.effortLabel(seconds)} $value W"
    is NewRecord.ClassLength -> "$minutes-min ride $value kJ"
}
