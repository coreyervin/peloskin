package app.peloskin.store

import app.peloskin.ride.FtpSource
import app.peloskin.ride.RideSummary
import org.json.JSONArray
import org.json.JSONObject

enum class SkinMode { Full, Compact }
enum class CompactEdge { Top, Bottom }

enum class CompactMetric(val label: String) {
    Cadence("Cadence"),
    Output("Output"),
    Resistance("Resistance"),
    Speed("Speed"),
    Distance("Distance"),
    TotalOutput("Total"),
    Calories("Calories"),
    HeartRate("Heart rate"),
}

data class Settings(
    val ftp: Int? = null,
    val ftpSource: FtpSource = FtpSource.Manual,
    val ftpSetAt: Long = 0L,
    val autoFtp: Boolean = true,
    val startMode: SkinMode = SkinMode.Full,
    val compactEdge: CompactEdge = CompactEdge.Bottom,
    val compactMetrics: List<CompactMetric> = DefaultCompactMetrics,
    val startOnBoot: Boolean = true,
    val rides: List<RideSummary> = emptyList(),
) {
    companion object {
        val DefaultCompactMetrics = listOf(
            CompactMetric.Cadence, CompactMetric.Output, CompactMetric.Resistance,
            CompactMetric.Speed, CompactMetric.TotalOutput,
        )
        const val MaxRides = 100
    }
}

/** JSON form, used for both the app's own storage and the backup file. */
object SettingsJson {
    private const val Version = 1

    fun encode(s: Settings): String = JSONObject().apply {
        put("version", Version)
        s.ftp?.let { put("ftp", it) }
        put("ftpSource", s.ftpSource.name)
        put("ftpSetAt", s.ftpSetAt)
        put("autoFtp", s.autoFtp)
        put("startMode", s.startMode.name)
        put("compactEdge", s.compactEdge.name)
        put("compactMetrics", JSONArray(s.compactMetrics.map { it.name }))
        put("startOnBoot", s.startOnBoot)
        put("rides", JSONArray(s.rides.map { r ->
            JSONObject().apply {
                put("startedAt", r.startedAt)
                put("durationSec", r.durationSec)
                put("avgPower", r.avgPower)
                put("totalKj", r.totalKj)
                r.best20Watts?.let { put("best20", it) }
            }
        }))
    }.toString(2)

    fun decode(text: String): Settings {
        val o = JSONObject(text)
        val d = Settings()
        val rides = o.optJSONArray("rides")?.let { a ->
            (0 until a.length()).map { i ->
                val r = a.getJSONObject(i)
                RideSummary(
                    startedAt = r.getLong("startedAt"),
                    durationSec = r.getInt("durationSec"),
                    avgPower = r.getInt("avgPower"),
                    totalKj = r.getInt("totalKj"),
                    best20Watts = if (r.has("best20")) r.getInt("best20") else null,
                )
            }
        } ?: emptyList()
        val metrics = o.optJSONArray("compactMetrics")?.let { a ->
            (0 until a.length()).mapNotNull { enumOrNull<CompactMetric>(a.getString(it)) }
        } ?: d.compactMetrics
        return Settings(
            ftp = if (o.has("ftp")) o.getInt("ftp").takeIf { it > 0 } else null,
            ftpSource = enumOrNull<FtpSource>(o.optString("ftpSource")) ?: d.ftpSource,
            ftpSetAt = o.optLong("ftpSetAt", 0L),
            autoFtp = o.optBoolean("autoFtp", d.autoFtp),
            startMode = enumOrNull<SkinMode>(o.optString("startMode")) ?: d.startMode,
            compactEdge = enumOrNull<CompactEdge>(o.optString("compactEdge")) ?: d.compactEdge,
            compactMetrics = metrics,
            startOnBoot = o.optBoolean("startOnBoot", d.startOnBoot),
            rides = rides,
        )
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }
}
