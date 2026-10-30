package app.peloskin.data.store

import app.peloskin.domain.RideDetail
import org.json.JSONArray
import org.json.JSONObject

/** JSON form of a ride's output graph and heart rate, one small file per ride. */
object RideDetailJson {
    fun encode(d: RideDetail): String = JSONObject().apply {
        put("startedAt", d.startedAt)
        put("stepSec", RideDetail.StepSec)
        put("power", JSONArray(d.powerEvery5s))
        if (d.heartRate.isNotEmpty()) put("heartRate", JSONArray(d.heartRate))
    }.toString()

    fun decode(text: String): RideDetail? = try {
        val o = JSONObject(text)
        val a = o.getJSONArray("power")
        val hr = o.optJSONArray("heartRate")?.let { h -> (0 until h.length()).map { h.getInt(it) } } ?: emptyList()
        RideDetail(o.getLong("startedAt"), (0 until a.length()).map { a.getInt(it) }, hr)
    } catch (e: Exception) {
        null
    }
}
