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
 * 每个请求必须携带 X-ZVE-Token（与 ApiServer 一致），否则被 404 拒绝。
 */
class EnvStateCache(
    private val token: String = "",
    private val pollIntervalMs: Long = 500L,
) {

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
    private var sensor: JSONObject? = null
    private var gnss: JSONObject? = null
    private var sim: JSONObject? = null
    private var locationEnabled: Boolean = false
    private var locationLat: Double = 0.0
    private var locationLon: Double = 0.0
    private var locationSpeed: Float = 0f
    private var locationBearing: Float = 0f
    private var locationAccuracy: Float = 5f
    private var locationAltitude: Double = 0.0
    private var stepEnabled: Boolean = false
    private var stepFrequency: Int = 120
    private var stepCounter: Long = 0L
    private var lastSensorTickMs: Long = 0L

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
                // 单类型开关：enabled=false 时 Hook 放行真实数据（数据保留在引擎内）
                wifi = data.optJSONObject("wifi")
                    ?.takeIf { it.optBoolean("enabled", false) }
                    ?.optJSONObject("data")
                cell = data.optJSONObject("cell")
                    ?.takeIf { it.optBoolean("enabled", false) }
                    ?.optJSONObject("data")
                ble = data.optJSONObject("ble")
                    ?.takeIf { it.optBoolean("enabled", false) }
                    ?.optJSONObject("data")
                sensor = data.optJSONObject("sensor")
                    ?.takeIf { it.optBoolean("enabled", false) }
                    ?.optJSONObject("data")
                gnss = data.optJSONObject("gnss")
                    ?.takeIf { it.optBoolean("enabled", false) }
                    ?.optJSONObject("data")
                sim = data.optJSONObject("sim")
                    ?.takeIf { it.optBoolean("enabled", false) }
                    ?.optJSONObject("data")
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "refresh env cache failed: ${t.message}")
        }
        try {
            val loc = rawGet("/api/location/status") ?: return
            synchronized(lock) {
                locationEnabled = loc.optBoolean("enabled", false)
                locationLat = loc.optDouble("latitude", 0.0)
                locationLon = loc.optDouble("longitude", 0.0)
                locationSpeed = loc.optDouble("speed", 0.0).toFloat()
                locationBearing = loc.optDouble("bearing", 0.0).toFloat()
                locationAccuracy = loc.optDouble("accuracy", 5.0).toFloat()
                locationAltitude = loc.optDouble("altitude", 0.0)
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "refresh location cache failed: ${t.message}")
        }
        try {
            val route = rawGet("/api/route/status") ?: return
            synchronized(lock) {
                val routeRunning = route.optBoolean("running", false)
                val routeEnabled = route.optBoolean("enabled", false)
                val routeStepFrequency = route.optInt("stepFrequency", 120)
                val routeStepCount = route.optLong("stepCount", 0L)
                // 传感器引擎（环境模拟页）优先：配置了步频则按它注入，并本地累计步数
                val sensorStep = sensor?.optInt("stepFrequency", 0) ?: 0
                if (sensorStep > 0) {
                    stepEnabled = true
                    stepFrequency = sensorStep
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (lastSensorTickMs > 0L) {
                        val deltaSec = (now - lastSensorTickMs) / 1000.0
                        stepCounter += (sensorStep * deltaSec / 60.0).toLong()
                    } else {
                        stepCounter = routeStepCount
                    }
                    lastSensorTickMs = now
                } else {
                    stepEnabled = routeEnabled && routeRunning && routeStepFrequency > 0
                    stepFrequency = routeStepFrequency
                    stepCounter = routeStepCount
                    lastSensorTickMs = 0L
                }
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "refresh step cache failed: ${t.message}")
        }
    }

    /** 当前虚拟 WiFi 数据；未启用时 null。 */
    fun currentWifi(): JSONObject? = synchronized(lock) { wifi }

    /** 当前虚拟基站数据；未启用时 null。 */
    fun currentCell(): JSONObject? = synchronized(lock) { cell }

    /** 当前虚拟 BLE 数据；未启用时 null。 */
    fun currentBle(): JSONObject? = synchronized(lock) { ble }

    /** 当前虚拟传感器数据（加速度/陀螺仪/计步等）；未启用时 null。 */
    fun currentSensor(): JSONObject? = synchronized(lock) { sensor }

    /** 当前虚拟 GNSS 数据；未启用时 null。 */
    fun currentGnss(): JSONObject? = synchronized(lock) { gnss }

    /** 当前虚拟 SIM 数据；未启用时 null。 */
    fun currentSim(): JSONObject? = synchronized(lock) { sim }

    /** 传感器模拟是否处于活动状态（步频模拟或传感器连续流/事件流数据）。 */
    fun isSensorStreamActive(): Boolean = synchronized(lock) {
        if (stepEnabled) return true
        val d = sensor ?: return false
        d.has("stepCounter") || d.has("accelerometer") || d.has("gyroscope") ||
            (d.optJSONArray("events")?.length() ?: 0) > 0
    }

    /** 位置虚拟化开关（单点或路线任一启用即为 true）。 */
    fun isLocationEnabled(): Boolean = synchronized(lock) { locationEnabled }

    /** 当前虚拟纬度。 */
    fun locationLat(): Double = synchronized(lock) { locationLat }

    /** 当前虚拟经度。 */
    fun locationLon(): Double = synchronized(lock) { locationLon }

    /** 当前虚拟速度（m/s）。 */
    fun locationSpeed(): Float = synchronized(lock) { locationSpeed }

    /** 当前虚拟朝向（度）。 */
    fun locationBearing(): Float = synchronized(lock) { locationBearing }

    /** 当前虚拟精度（米）。 */
    fun locationAccuracy(): Float = synchronized(lock) { locationAccuracy }

    /** 当前虚拟海拔（米）。 */
    fun locationAltitude(): Double = synchronized(lock) { locationAltitude }

    /** 步频模拟是否开启（路线运行且 stepFrequency>0）。 */
    fun isStepEnabled(): Boolean = synchronized(lock) { stepEnabled }

    /** 步频（steps/min）。 */
    fun stepFrequency(): Int = synchronized(lock) { stepFrequency }

    /** 当前累计步数。 */
    fun stepCounter(): Long = synchronized(lock) { stepCounter }

    /** 构造一个带当前时间戳的虚拟 Location（每次调用刷新 time，避免客户端判旧）。 */
    fun buildVirtualLocation(): android.location.Location? {
        val enabled = isLocationEnabled()
        if (!enabled) return null
        val location = android.location.Location("fused")
        // 随机抖动 ±0.000005°（约 ±0.5m），与 system_server 引擎一致
        val jitter = java.util.concurrent.ThreadLocalRandom.current().nextDouble(-0.000005, 0.000005)
        location.latitude = locationLat() + jitter
        location.longitude = locationLon() + jitter
        location.accuracy = locationAccuracy()
        location.speed = locationSpeed()
        location.bearing = locationBearing()
        location.altitude = locationAltitude()
        location.time = System.currentTimeMillis()
        location.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        return location
    }

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
                "X-ZVE-Token: $token\r\n" +
                "Connection: close\r\n" +
                "\r\n"
            socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            val statusLine = response.substringBefore("\r\n")
            // 未授权/不存在时后端返回 404 空 body，直接视为无虚拟状态
            if (!statusLine.contains("200")) return null
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
