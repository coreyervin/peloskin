package app.peloskin.data.store

import app.peloskin.domain.RideDetail
import org.json.JSONArray
import org.json.JSONObject

/** JSON form of a ride's output graph, one small file per ride. */
object RideDetailJson {
    fun encode(d: RideDetail): String = JSONObject().apply {
        put("startedAt", d.startedAt)
        put("stepSec", RideDetail.StepSec)
        put("power", JSONArray(d.powerEvery5s))
    }.toString()

    fun decode(text: String): RideDetail? = try {
        val o = JSONObject(text)
        val a = o.getJSONArray("power")
        RideDetail(o.getLong("startedAt"), (0 until a.length()).map { a.getInt(it) })
    } catch (e: Exception) {
        null
    }
}
