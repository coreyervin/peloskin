package app.peloskin.data.store

import app.peloskin.domain.PersonalRecords
import app.peloskin.domain.PowerZones
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
        put("graph", JSONArray(c.graph.toList()))
        put("zones", JSONArray(c.zones.toList()))
        put("pendingIdle", c.pendingIdle)
        c.ftp?.let { put("ftp", it) }
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
            // Checkpoints from before records existed have none of these; the ride carries on without them.
            efforts = o.optJSONArray("efforts")?.let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
                ?: DoubleArray(PersonalRecords.EffortWindows.size) { -1.0 },
            graph = o.optJSONArray("graph")?.let { a -> IntArray(a.length()) { a.getInt(it) } } ?: IntArray(0),
            zones = o.optJSONArray("zones")?.let { a -> IntArray(a.length()) { a.getInt(it) } } ?: IntArray(PowerZones.Count),
            pendingIdle = o.optInt("pendingIdle", 0),
            ftp = if (o.has("ftp")) o.getInt("ftp") else null,
        )
    } catch (e: Exception) {
        null
    }
}
