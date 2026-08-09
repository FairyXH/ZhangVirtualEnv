package io.github.fairyxh.VirtualEnv.core

import io.github.fairyxh.VirtualEnv.app.ApiClient
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * App 进程侧虚拟环境状态缓存。
 *
 * 虚拟环境状态保存在 system_server 的 Backend（内存），App 进程的 framework API Hook
 * 无法直接读取。本缓存定时从 ApiServer 拉取 /api/env/status，Hook 层直接读缓存快照，
 * 避免每次 Hook 调用都发起 HTTP 请求。
 */
class EnvStateCache(private val pollIntervalMs: Long = 2000L) {

    companion object {
        private const val TAG_SCOPE = "EnvCache"
    }

    private val lock = Any()
    private var wifi: JSONObject? = null
    private var cell: JSONObject? = null
    private var ble: JSONObject? = null

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ZVE-EnvCache").apply { isDaemon = true }
    }

    init {
        executor.scheduleWithFixedDelay(
            { refresh() },
            0,
            pollIntervalMs,
            TimeUnit.MILLISECONDS
        )
    }

    /** 从 system_server 拉取环境状态并更新缓存。 */
    fun refresh() {
        try {
            val result = ApiClient.getEnvStatus()
            val data = result.data ?: return
            synchronized(lock) {
                wifi = data.optJSONObject("wifi")?.optJSONObject("data")
                cell = data.optJSONObject("cell")?.optJSONObject("data")
                ble = data.optJSONObject("ble")?.optJSONObject("data")
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "refresh env cache failed: ${t.message}")
        }
    }

    /** 当前虚拟 WiFi 数据；未启用时 null。 */
    fun currentWifi(): JSONObject? = synchronized(lock) { wifi }

    /** 当前虚拟基站数据；未启用时 null。 */
    fun currentCell(): JSONObject? = synchronized(lock) { cell }

    /** 当前虚拟 BLE 数据；未启用时 null。 */
    fun currentBle(): JSONObject? = synchronized(lock) { ble }

    fun shutdown() {
        executor.shutdownNow()
    }
}
