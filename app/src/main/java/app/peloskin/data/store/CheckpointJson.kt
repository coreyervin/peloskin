package app.peloskin.data.store

import app.peloskin.domain.PersonalRecords
import app.peloskin.domain.RideCheckpoint
import org.json.JSONArray
import org.json.JSONObject

/** JSON form of an in-progress ride, kept in the app's own storage. */
object CheckpointJson {
    fun encode(c: RideCheckpoint): String = JSONObject().apply {
        put("bootId", c.bootId)
        put("savedAt", c.savedAt)
        put("startedAt", c.startedAt)
        put("lastPedalAt", c.lastPedalAt)
        put("elapsed", c.elapsed)
        put("elapsedAtLastPedal", c.elapsedAtLastPedal)
        put("moving", c.moving)
        put("powerSum", c.powerSum)
        put("cadenceSum", c.cadenceSum)
        put("resistanceSum", c.resistanceSum)
        put("energyJ", c.energyJ)
        put("distanceMi", c.distanceMi)
        put("bestPower", c.bestPower.toDouble())
        put("bestCadence", c.bestCadence.toDouble())
        put("bestResistance", c.bestResistance.toDouble())
        put("seconds", c.seconds)
        c.best20?.let { put("best20", it) }
        put("ring", JSONArray(c.ring.toList()))
        put("efforts", JSONArray(c.efforts.toList()))
        put("watts", JSONArray(c.watts.toList()))
        // One character per second keeps a long ride's checkpoint small.
        put("pedaled", c.pedaled.joinToString("") { if (it) "1" else "0" })
        put("partialPedaled", c.partialPedaled)
        put("heartRate", JSONArray(c.heartRate.toList()))
    }.toString()

    /** @return null if the text isn't a checkpoint. */
    fun decode(text: String): RideCheckpoint? = try {
        val o = JSONObject(text)
        val ring = o.getJSONArray("ring")
        RideCheckpoint(
            bootId = o.optInt("bootId", -1),
            savedAt = o.getLong("savedAt"),
            startedAt = o.getLong("startedAt"),
            lastPedalAt = o.getLong("lastPedalAt"),
            elapsed = o.getDouble("elapsed"),
            elapsedAtLastPedal = o.getDouble("elapsedAtLastPedal"),
            moving = o.getDouble("moving"),
            powerSum = o.getDouble("powerSum"),
            cadenceSum = o.getDouble("cadenceSum"),
            resistanceSum = o.getDouble("resistanceSum"),
            energyJ = o.getDouble("energyJ"),
            distanceMi = o.getDouble("distanceMi"),
            bestPower = o.getDouble("bestPower").toFloat(),
            bestCadence = o.getDouble("bestCadence").toFloat(),
            bestResistance = o.getDouble("bestResistance").toFloat(),
            seconds = o.getInt("seconds"),
            best20 = if (o.has("best20")) o.getDouble("best20") else null,
            ring = DoubleArray(ring.length()) { ring.getDouble(it) },
            // Checkpoints from before 0.4 have no efforts, only the best 20 minutes; keep that
            // so a resumed or recovered ride can still raise FTP.
            efforts = o.optJSONArray("efforts")?.let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
                ?: DoubleArray(PersonalRecords.EffortWindows.size) { i ->
                    if (PersonalRecords.EffortWindows[i] == 1200 && o.has("best20")) o.getDouble("best20") else -1.0
                },
            // Older checkpoints have no per-second record; the ride carries on, its graph and
            // zones covering only the time after the restart.
            watts = o.optJSONArray("watts")?.let { a -> IntArray(a.length()) { a.getInt(it) } } ?: IntArray(0),
            pedaled = o.optString("pedaled").let { str -> BooleanArray(str.length) { str[it] == '1' } },
            partialPedaled = o.optBoolean("partialPedaled", false),
            heartRate = o.optJSONArray("heartRate")?.let { a -> IntArray(a.length()) { a.getInt(it) } } ?: IntArray(0),
        )
    } catch (e: Exception) {
        null
    }
}
