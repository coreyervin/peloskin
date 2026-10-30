package app.peloskin.data.store

import app.peloskin.domain.CompactEdge
import app.peloskin.domain.CompactMetric
import app.peloskin.domain.FtpSource
import app.peloskin.domain.HeartRateMonitor
import app.peloskin.domain.Lifetime
import app.peloskin.domain.PersonalRecords
import app.peloskin.domain.Profile
import app.peloskin.domain.Record
import app.peloskin.domain.Records
import app.peloskin.domain.RideSummary
import app.peloskin.domain.Settings
import app.peloskin.domain.SkinMode
import app.peloskin.domain.Units
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON form, used for both the app's own storage and the backup file.
 * Version 1 had a single rider's FTP and rides at the top level; it's read as one profile.
 */
object SettingsJson {
    private const val Version = 2

    fun encode(s: Settings): String = JSONObject().apply {
        put("version", Version)
        put("activeProfileId", s.activeProfileId)
        put("profiles", JSONArray(s.profiles.map(::encodeProfile)))
        put("startMode", s.startMode.name)
        put("compactEdge", s.compactEdge.name)
        put("compactMetrics", JSONArray(s.compactMetrics.map { it.name }))
        put("startOnBoot", s.startOnBoot)
        put("units", s.units.name)
    }.toString()

    fun decode(text: String): Settings {
        val o = JSONObject(text)
        val d = Settings()
        val profiles = o.optJSONArray("profiles")?.let { a ->
            (0 until a.length()).map { decodeProfile(a.getJSONObject(it)) }
        }?.takeIf { it.isNotEmpty() } ?: listOf(decodeProfile(o, Profile.first()))
        val metrics = o.optJSONArray("compactMetrics")?.let { a ->
            (0 until a.length()).mapNotNull { enumOrNull<CompactMetric>(a.getString(it)) }
        } ?: d.compactMetrics
        val activeId = o.optString("activeProfileId").takeIf { id -> profiles.any { it.id == id } }
        return Settings(
            profiles = profiles,
            activeProfileId = activeId ?: profiles.first().id,
            startMode = enumOrNull<SkinMode>(o.optString("startMode")) ?: d.startMode,
            compactEdge = enumOrNull<CompactEdge>(o.optString("compactEdge")) ?: d.compactEdge,
            compactMetrics = metrics,
            startOnBoot = o.optBoolean("startOnBoot", d.startOnBoot),
            units = enumOrNull<Units>(o.optString("units")) ?: d.units,
        )
    }

    private fun encodeProfile(p: Profile) = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        p.ftp?.let { put("ftp", it) }
        put("ftpSource", p.ftpSource.name)
        put("ftpSetAt", p.ftpSetAt)
        put("autoFtp", p.autoFtp)
        put("rides", JSONArray(p.rides.map(::encodeRide)))
        put("records", JSONObject().apply {
            put("efforts", encodeRecords(p.records.efforts))
            put("byLength", encodeRecords(p.records.byLength))
        })
        p.heartRateMonitor?.let { put("heartRateMonitor", JSONObject().put("address", it.address).put("name", it.name)) }
        p.maxHr?.let { put("maxHr", it) }
        put("lifetime", JSONObject().apply {
            put("rides", p.lifetime.rides)
            put("seconds", p.lifetime.seconds)
            put("kj", p.lifetime.kj)
            put("miles", p.lifetime.miles)
            put("calories", p.lifetime.calories)
        })
    }

    /** Reads a profile object, or a version 1 file's top level when [fallback] is given. */
    private fun decodeProfile(o: JSONObject, fallback: Profile? = null): Profile {
        val d = fallback ?: Profile.first()
        val rides = o.optJSONArray("rides")?.let { a -> (0 until a.length()).map { decodeRide(a.getJSONObject(it)) } }
            ?: emptyList()
        // History saved before records existed gets its records worked out from the rides.
        val records = o.optJSONObject("records")?.let {
            Records(decodeRecords(it.optJSONObject("efforts")), decodeRecords(it.optJSONObject("byLength")))
        } ?: PersonalRecords.rebuild(rides)
        // Likewise lifetime totals, which start from the rides that were kept.
        val lifetime = o.optJSONObject("lifetime")?.let {
            Lifetime(it.optInt("rides"), it.optLong("seconds"), it.optLong("kj"), it.optDouble("miles", 0.0), it.optLong("calories"))
        } ?: Lifetime.of(rides)
        return Profile(
            id = o.optString("id").ifEmpty { d.id },
            name = o.optString("name").ifEmpty { d.name },
            ftp = if (o.has("ftp")) o.getInt("ftp").takeIf { it > 0 } else null,
            ftpSource = enumOrNull<FtpSource>(o.optString("ftpSource")) ?: d.ftpSource,
            ftpSetAt = o.optLong("ftpSetAt", 0L),
            autoFtp = o.optBoolean("autoFtp", d.autoFtp),
            rides = rides,
            records = records,
            lifetime = lifetime,
            heartRateMonitor = o.optJSONObject("heartRateMonitor")?.let { m ->
                m.optString("address").takeIf { it.isNotEmpty() }?.let { HeartRateMonitor(it, m.optString("name")) }
            },
            maxHr = if (o.has("maxHr")) o.getInt("maxHr").takeIf { it in 100..240 } else null,
        )
    }

    private fun encodeRide(r: RideSummary) = JSONObject().apply {
        put("startedAt", r.startedAt)
        put("durationSec", r.durationSec)
        put("avgPower", r.avgPower)
        put("totalKj", r.totalKj)
        r.best20Watts?.let { put("best20", it) }
        put("avgCadence", r.avgCadence)
        put("avgResistance", r.avgResistance)
        put("distanceMi", r.distanceMi.toDouble())
        put("calories", r.calories)
        put("efforts", JSONObject().apply { r.bestEfforts.forEach { (sec, w) -> put(sec.toString(), w) } })
        r.zoneSeconds?.let { put("zones", JSONArray(it)) }
        r.ftp?.let { put("ftp", it) }
        r.avgHeartRate?.let { put("avgHr", it) }
        r.maxHeartRate?.let { put("maxHeartRate", it) }
        r.heartRateZoneSeconds?.let { put("hrZones", JSONArray(it)) }
        r.maxHr?.let { put("maxHr", it) }
    }

    private fun decodeRide(r: JSONObject): RideSummary {
        val efforts = r.optJSONObject("efforts")?.let { e ->
            e.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to e.getInt(k) } }.toMap()
        } ?: emptyMap()
        return RideSummary(
            startedAt = r.getLong("startedAt"),
            durationSec = r.getInt("durationSec"),
            avgPower = r.getInt("avgPower"),
            totalKj = r.getInt("totalKj"),
            best20Watts = if (r.has("best20")) r.getInt("best20") else null,
            avgCadence = r.optInt("avgCadence", 0),
            avgResistance = r.optInt("avgResistance", 0),
            distanceMi = r.optDouble("distanceMi", 0.0).toFloat(),
            calories = r.optInt("calories", 0),
            // Version 1 rides only knew their best 20 minutes.
            bestEfforts = efforts.ifEmpty { if (r.has("best20")) mapOf(1200 to r.getInt("best20")) else emptyMap() },
            zoneSeconds = r.optJSONArray("zones")?.let { z -> (0 until z.length()).map { z.getInt(it) } },
            ftp = if (r.has("ftp")) r.getInt("ftp") else null,
            avgHeartRate = if (r.has("avgHr")) r.getInt("avgHr") else null,
            maxHeartRate = if (r.has("maxHeartRate")) r.getInt("maxHeartRate") else null,
            heartRateZoneSeconds = r.optJSONArray("hrZones")?.let { z -> (0 until z.length()).map { z.getInt(it) } },
            maxHr = if (r.has("maxHr")) r.getInt("maxHr") else null,
        )
    }

    private fun encodeRecords(m: Map<Int, Record>) = JSONObject().apply {
        m.forEach { (k, rec) -> put(k.toString(), JSONObject().put("value", rec.value).put("startedAt", rec.startedAt)) }
    }

    private fun decodeRecords(o: JSONObject?): Map<Int, Record> {
        if (o == null) return emptyMap()
        return o.keys().asSequence().mapNotNull { k ->
            val key = k.toIntOrNull() ?: return@mapNotNull null
            val rec = o.optJSONObject(k) ?: return@mapNotNull null
            key to Record(rec.getInt("value"), rec.getLong("startedAt"))
        }.toMap()
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }
}
