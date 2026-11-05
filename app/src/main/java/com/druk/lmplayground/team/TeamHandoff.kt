package com.druk.lmplayground.team

import org.json.JSONObject

/**
 * Structured in-room handoff (feature E seed). Priority is higher-first (1 = urgent).
 */
data class TeamHandoff(
    val fromRole: TeamRole,
    val toRole: TeamRole,
    val priority: Int,
    val body: String,
) {
    fun toJson(): String = JSONObject()
        .put("from", fromRole.name)
        .put("to", toRole.name)
        .put("priority", priority)
        .put("body", body)
        .toString()

    companion object {
        fun fromJson(json: String): TeamHandoff? {
            val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val from = runCatching { TeamRole.valueOf(obj.getString("from")) }.getOrNull() ?: return null
            val to = runCatching { TeamRole.valueOf(obj.getString("to")) }.getOrNull() ?: return null
            return TeamHandoff(
                fromRole = from,
                toRole = to,
                priority = obj.optInt("priority", 0),
                body = obj.optString("body"),
            )
        }
    }
}
