package io.github.fairyxh.VirtualEnv.core.sensor

import org.json.JSONObject

/**
 * 传感器模拟后端类型。
 */
enum class SensorBackendType {
    /** 全局系统模式：SensorService Data Injection（system_server，所有 App 生效）。 */
    SYSTEM,

    /** 应用兼容模式：LSPosed App 进程 Hook（需要 scope，保留旧逻辑）。 */
    LEGACY,

    /** 未选择 / 探测失败且无回退。 */
    NONE
}

/**
 * 传感器后端运行状态快照（供 UI / 日志 / EnvStateCache 展示）。
 */
data class SensorBackendStatus(
    val type: SensorBackendType = SensorBackendType.NONE,
    val started: Boolean = false,
    val injectMode: Int = -1,
    val reason: String = "",
    val eventCount: Long = 0L,
    /** 系统级通道是否已实证送达（Native Hook 首次改写事件后为 true；LEGACY 抑制的依据）。 */
    val deliveryVerified: Boolean = false,
    /** 后端详情（如 Native Hook 地址/重写计数 JSON）。 */
    val detail: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.name)
        put("started", started)
        put("injectMode", injectMode)
        put("reason", reason)
        put("eventCount", eventCount)
        put("deliveryVerified", deliveryVerified)
        put("detail", detail)
    }

    companion object {
        fun fromJson(json: JSONObject?): SensorBackendStatus {
            if (json == null) return SensorBackendStatus()
            return SensorBackendStatus(
                type = runCatching { SensorBackendType.valueOf(json.optString("type", "NONE")) }
                    .getOrDefault(SensorBackendType.NONE),
                started = json.optBoolean("started", false),
                injectMode = json.optInt("injectMode", -1),
                reason = json.optString("reason", ""),
                eventCount = json.optLong("eventCount", 0L),
                deliveryVerified = json.optBoolean("deliveryVerified", false),
                detail = json.optString("detail", ""),
            )
        }
    }
}

/**
 * 统一传感器模拟后端接口。
 *
 * UI / 业务层只依赖 [SensorBackendManager]，不感知具体实现是
 * SensorService Data Injection 还是 App 进程 Hook。后续新增
 * Sensor HAL Backend 时同样实现本接口即可。
 */
interface SensorBackend {
    /** 启动后端（探测能力、启动注入调度）。 */
    fun start()

    /** 停止后端并恢复原生行为（fail-open）。 */
    fun stop()

    /** 更新模拟配置（后端自行决定是否重启注入）。 */
    fun updateConfig(config: VirtualSensorConfig?)

    /** 当前后端状态。 */
    fun getStatus(): SensorBackendStatus
}
