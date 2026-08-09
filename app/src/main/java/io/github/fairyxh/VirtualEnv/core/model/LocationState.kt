package io.github.fairyxh.VirtualEnv.core.model

import org.json.JSONObject

/**
 * 位置快照（Backend 与 Hook 之间传递的统一数据模型）。
 */
data class LocationState(
    val enabled: Boolean = false,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val speed: Float = 0f,
    val bearing: Float = 0f,
    val accuracy: Float = 5f,
    val altitude: Double = 0.0,
    val provider: String = "gps",
    val updateTime: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("latitude", latitude)
        put("longitude", longitude)
        put("speed", speed)
        put("bearing", bearing)
        put("accuracy", accuracy)
        put("altitude", altitude)
        put("provider", provider)
        put("updateTime", updateTime)
    }

    companion object {
        fun fromJson(obj: JSONObject): LocationState = LocationState(
            enabled = obj.optBoolean("enabled", false),
            latitude = obj.optDouble("latitude", 0.0),
            longitude = obj.optDouble("longitude", 0.0),
            speed = obj.optDouble("speed", 0.0).toFloat(),
            bearing = obj.optDouble("bearing", 0.0).toFloat(),
            accuracy = obj.optDouble("accuracy", 5.0).toFloat(),
            altitude = obj.optDouble("altitude", 0.0),
            provider = obj.optString("provider", "gps"),
            updateTime = obj.optLong("updateTime", 0L),
        )
    }
}
