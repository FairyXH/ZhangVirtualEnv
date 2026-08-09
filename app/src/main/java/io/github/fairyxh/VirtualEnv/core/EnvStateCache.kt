package io.github.fairyxh.VirtualEnv.core

import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * App 进程侧虚拟环境状态缓存。
 *
 * 虚拟环境状态保存在 system_server 的 Backend（内存），App 进程的 framework API Hook
 * 无法直接读取。本缓存定时从 ApiServer 拉取 /api/env/status，Hook 层直接读缓存快照，
 * 避免每次 Hook 调用都发起网络请求。
 *
 * 注意：被 Hook 的目标 App 进程通常不允许 cleartext HTTP（usesCleartextTraffic=false），
 * 因此这里使用原始 TCP Socket 直连 127.0.0.1，绕过应用层网络安全策略。
 */
class EnvStateCache(private val pollIntervalMs: Long = 2000L) {

    companion object {
        private const val TAG_SCOPE = "EnvCache"
        private const val HOST = "127.0.0.1"
        private const val PORT = 18790
        private const val TIMEOUT_MS = 1500
    }

    private val lock = Any()
    private var wifi: JSONObject? = null
    private var cell: JSONObject? = null
    private var ble: JSONObject? = null
    private var locationEnabled: Boolean = false

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
            val data = rawGet("/api/env/status") ?: return
            synchronized(lock) {
                wifi = data.optJSONObject("wifi")?.optJSONObject("data")
                cell = data.optJSONObject("cell")?.optJSONObject("data")
                ble = data.optJSONObject("ble")?.optJSONObject("data")
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "refresh env cache failed: ${t.message}")
        }
        try {
            val loc = rawGet("/api/location/status") ?: return
            synchronized(lock) {
                locationEnabled = loc.optBoolean("enabled", false)
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "refresh location cache failed: ${t.message}")
        }
    }

    /** 当前虚拟 WiFi 数据；未启用时 null。 */
    fun currentWifi(): JSONObject? = synchronized(lock) { wifi }

    /** 当前虚拟基站数据；未启用时 null。 */
    fun currentCell(): JSONObject? = synchronized(lock) { cell }

    /** 当前虚拟 BLE 数据；未启用时 null。 */
    fun currentBle(): JSONObject? = synchronized(lock) { ble }

    /** 位置虚拟化开关（单点或路线任一启用即为 true）。 */
    fun isLocationEnabled(): Boolean = synchronized(lock) { locationEnabled }

    fun shutdown() {
        executor.shutdownNow()
    }

    /** 原始 TCP HTTP GET，绕过 cleartext 网络安全策略。 */
    private fun rawGet(path: String): JSONObject? {
        val socket = Socket()
        return try {
            socket.connect(java.net.InetSocketAddress(HOST, PORT), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            val request = "GET $path HTTP/1.1\r\n" +
                "Host: $HOST\r\n" +
                "Connection: close\r\n" +
                "\r\n"
            socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            val body = response.substringAfter("\r\n\r\n", "")
            if (body.isBlank()) return null
            JSONObject(body).optJSONObject("data")
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "rawGet $path failed: ${t.message}")
            null
        } finally {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }
}
