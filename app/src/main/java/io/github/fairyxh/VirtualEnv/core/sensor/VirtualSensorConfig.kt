package io.github.fairyxh.VirtualEnv.core.sensor

import org.json.JSONObject

/**
 * 传感器模拟配置（统一模型，兼容现有 EnvStateEngine sensor 数据格式）。
 *
 * 现有数据格式（不破坏）：
 * ```
 * {
 *   "stepFrequency": 120,
 *   "stepCount": 10000,
 *   "backend": "auto" | "system" | "legacy",   // 新增
 *   "randomNoise": true,                        // 新增
 *   "mode": "walking",                          // 新增（运动模式，预留）
 *   "events": [...], "accelerometer": [...], "gyroscope": [...]  // 录像回放保留
 * }
 * ```
 */
data class VirtualSensorConfig(
    /** 传感器模拟总开关（对应 EnvStateEngine enabled）。 */
    val enabled: Boolean = false,
    /** 后端选择偏好：auto / system / legacy。 */
    val backend: String = "auto",
    /** 运动模式（walking / running / custom，预留）。 */
    val mode: String = "walking",
    /** 步频（steps/min）。 */
    val stepFrequency: Int = 120,
    /** 起始步数。 */
    val stepCount: Long = 0L,
    /** 是否叠加随机噪声（模拟真实传感器波动）。 */
    val randomNoise: Boolean = true,
    /** 目标传感器类型集合（默认 STEP_COUNTER / STEP_DETECTOR / ACCELEROMETER）。 */
    val sensorTypes: List<Int> = DEFAULT_SENSOR_TYPES,
) {
    companion object {
        const val TYPE_ACCELEROMETER = 1
        const val TYPE_MAGNETIC_FIELD = 2
        const val TYPE_GYROSCOPE = 4
        const val TYPE_GRAVITY = 9
        const val TYPE_STEP_DETECTOR = 18
        const val TYPE_STEP_COUNTER = 19

        const val BACKEND_AUTO = "auto"

        val DEFAULT_SENSOR_TYPES = listOf(
            TYPE_STEP_COUNTER, TYPE_STEP_DETECTOR, TYPE_ACCELEROMETER,
            TYPE_GRAVITY, TYPE_MAGNETIC_FIELD
        )

        /** 从 EnvStateEngine 状态 JSON（{enabled, data}）解析配置；数据为空时返回默认。 */
        fun fromStatus(status: JSONObject?): VirtualSensorConfig {
            if (status == null) return VirtualSensorConfig()
            val enabled = status.optBoolean("enabled", false)
            val data = status.optJSONObject("data") ?: JSONObject()
            return fromData(enabled, data)
        }

        /** 从 enabled + sensor data JSON 解析配置。 */
        fun fromData(enabled: Boolean, data: JSONObject): VirtualSensorConfig {
            val types = mutableListOf<Int>()
            data.optJSONArray("sensorTypes")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optInt(i).takeIf { it > 0 }?.let { types.add(it) }
                }
            }
            return VirtualSensorConfig(
                enabled = enabled,
                backend = data.optString("backend", BACKEND_AUTO).ifBlank { BACKEND_AUTO },
                mode = data.optString("mode", "walking").ifBlank { "walking" },
                stepFrequency = data.optInt("stepFrequency", 120).coerceAtLeast(0),
                stepCount = data.optLong("stepCount", 0L).coerceAtLeast(0L),
                randomNoise = data.optBoolean("randomNoise", true),
                sensorTypes = types.ifEmpty { DEFAULT_SENSOR_TYPES },
            )
        }

        /** 将配置序列化回 sensor data JSON（保留录像回放字段）。 */
        fun toData(config: VirtualSensorConfig, base: JSONObject = JSONObject()): JSONObject {
            val out = JSONObject(base.toString())
            out.put("backend", config.backend)
            out.put("mode", config.mode)
            out.put("stepFrequency", config.stepFrequency)
            out.put("stepCount", config.stepCount)
            out.put("randomNoise", config.randomNoise)
            out.put("sensorTypes", org.json.JSONArray(config.sensorTypes))
            return out
        }
    }
}
