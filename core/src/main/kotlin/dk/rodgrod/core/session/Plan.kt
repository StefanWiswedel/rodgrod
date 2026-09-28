package dk.rodgrod.core.session

import org.json.JSONArray
import org.json.JSONObject

enum class Mode {
    /** First time: English → model → repeat. */
    NEW,
    /** Due review: English → pause to recall → model → repeat. Moves the Leitner box. */
    REVIEW,
    /** Extra retrieval practice (later in the same session, or before it is due). Recorded, but doesn't move the box. */
    EXTRA,
}

sealed class Task {
    data class Production(val itemId: String, val mode: Mode) : Task()
    /** HVPT perception block on one minimal pair. */
    data class Hvpt(val pairItemId: String, val trials: Int) : Task()

    fun toJson(): JSONObject = when (this) {
        is Production -> JSONObject().put("t", "p").put("id", itemId).put("mode", mode.name)
        is Hvpt -> JSONObject().put("t", "h").put("id", pairItemId).put("trials", trials)
    }

    companion object {
        fun fromJson(o: JSONObject): Task = when (o.getString("t")) {
            "p" -> Production(o.getString("id"), Mode.valueOf(o.getString("mode")))
            "h" -> Hvpt(o.getString("id"), o.getInt("trials"))
            else -> error("unknown task type")
        }
    }
}

data class SessionPlan(
    val tasks: List<Task>,
    val level: Int,
    val seed: Long,
    /** Rotates which Danish voice is used for which task. */
    val voiceOffset: Int = 0,
) {
    val productionCount get() = tasks.count { it is Task.Production }

    fun toJson(): JSONObject = JSONObject().put("level", level).put("seed", seed).put("voiceOffset", voiceOffset)
        .put("tasks", JSONArray(tasks.map { it.toJson() }))

    companion object {
        fun fromJson(o: JSONObject): SessionPlan {
            val arr = o.getJSONArray("tasks")
            return SessionPlan((0 until arr.length()).map { Task.fromJson(arr.getJSONObject(it)) },
                o.getInt("level"), o.getLong("seed"), o.optInt("voiceOffset", 0))
        }
    }
}
