package app.peloskin.data.store

import app.peloskin.domain.CompactEdge
import app.peloskin.domain.CompactMetric
import app.peloskin.domain.FtpSource
import app.peloskin.domain.RideSummary
import app.peloskin.domain.Settings
import app.peloskin.domain.SkinMode
import org.json.JSONArray
import org.json.JSONObject

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
