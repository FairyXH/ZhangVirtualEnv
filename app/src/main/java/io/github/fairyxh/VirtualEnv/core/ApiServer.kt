package io.github.fairyxh.VirtualEnv.core

import io.github.fairyxh.VirtualEnv.core.model.ApiResult
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地 HTTP API 服务（Phase 1）。
 *
 * 监听 127.0.0.1 端口，提供 App 控制端与 Backend 之间的通信。
 * 路由均为 `/api/...`，请求/响应均为 JSON。
 *
 * 访问控制：所有请求必须携带 `X-ZVE-Token` 头，值须与 [token] 完全一致。
 * 未授权请求返回裸 404（无 JSON 特征、不写日志），避免其他应用识别出
 * 本机存在该模块接口（不暴露检测点）。
 */
class ApiServer(
    private val port: Int,
    private val backend: Backend,
    private val token: String,
) {
    companion object {
        private const val TAG_SCOPE = "Api"
        const val DEFAULT_PORT = 18790
        const val TOKEN_HEADER = "X-ZVE-Token"

        private const val BIND_ADDRESS = "127.0.0.1"
        // 配置导入/导出请求体可能包含大量环境快照数据，放宽到 16MB
        private const val MAX_BODY = 1 shl 24 // 16MB
        private const val MAX_HANDLER_THREADS = 16
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        return try {
            serverSocket = ServerSocket(port, 50, InetAddress.getByName(BIND_ADDRESS))
            // 固定线程池 + 有界队列：防止大量慢速/半开连接把进程拖死
            executor = Executors.newFixedThreadPool(
                MAX_HANDLER_THREADS,
                java.util.concurrent.ThreadFactory { r ->
                    Thread(r, "ZVE-ApiHandler").apply { isDaemon = true }
                }
            ).also { pool ->
                // CallerRunsPolicy：队列满时由 accept 线程直接处理，避免拒绝连接
                (pool as java.util.concurrent.ThreadPoolExecutor).rejectedExecutionHandler =
                    java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
            }
            Thread { acceptLoop() }
                .apply {
                    name = "ZVE-ApiServer"
                    isDaemon = true
                    start()
                }
            ZLog.i(TAG_SCOPE, "ApiServer listening on $BIND_ADDRESS:$port")
            true
        } catch (t: Throwable) {
            ZLog.e(TAG_SCOPE, "ApiServer start failed", t)
            running.set(false)
            false
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            serverSocket?.close()
            executor?.shutdownNow()
        } catch (_: Throwable) {
        }
        ZLog.i(TAG_SCOPE, "ApiServer stopped")
    }

    private fun acceptLoop() {
        var consecutiveErrors = 0
        while (running.get()) {
            try {
                val socket = serverSocket?.accept() ?: break
                consecutiveErrors = 0
                executor?.execute { handle(socket) }
            } catch (t: Throwable) {
                if (!running.get()) break
                // accept 单次异常（如 fd 瞬时耗尽）不得退出循环，否则监听 socket 假死：
                // 所有新连接进入 backlog 排队（SYN-SENT），App 端 ApiServer 看似存在实则不可用。
                consecutiveErrors++
                ZLog.w(TAG_SCOPE, "accept failed (#$consecutiveErrors), retrying", t)
                try {
                    if (consecutiveErrors >= 3) {
                        recreateServerSocket()
                    }
                    Thread.sleep((consecutiveErrors * 100L).coerceAtMost(1000L))
                } catch (ie: InterruptedException) {
                    break
                }
            }
        }
    }

    /** 重建监听 socket（accept 连续失败时的兜底，避免长期假死）。 */
    private fun recreateServerSocket() {
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        try {
            serverSocket = ServerSocket(port, 50, InetAddress.getByName(BIND_ADDRESS))
            ZLog.i(TAG_SCOPE, "ApiServer socket recreated on $BIND_ADDRESS:$port")
        } catch (t: Throwable) {
            ZLog.e(TAG_SCOPE, "recreate server socket failed", t)
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 5000
                val input = java.io.BufferedInputStream(s.getInputStream())
                val requestLine = readLineBytes(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')

                // 字节级读 header（避免 Reader 预缓冲 body 导致 UTF-8 中文长度错位）
                var contentLength = 0
                var authToken: String? = null
                while (true) {
                    val line = readLineBytes(input) ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    } else if (line.startsWith(TOKEN_HEADER, ignoreCase = true)) {
                        authToken = line.substringAfter(':').trim()
                    }
                }
                // 访问控制：token 不匹配时直接断开连接，不返回任何字节。
                // 客户端表现为连接被重置/EOF（像访问不存在的主机），
                // 不产生 HTTP 响应特征，避免暴露模块 API 存在。
                if (authToken != token || authToken.isNullOrEmpty()) {
                    return
                }
                val body = if (contentLength > 0 && contentLength <= MAX_BODY) {
                    val bytes = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(bytes, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(bytes, 0, read, StandardCharsets.UTF_8)
                } else {
                    ""
                }

                val result = route(method, path, body)
                writeResponse(s.getOutputStream(), result)
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "handle request failed", t)
        }
    }

    /** 按字节读一行（\n 结尾，剔除 \r），返回 null 表示流结束。 */
    private fun readLineBytes(input: java.io.BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun route(method: String, path: String, body: String): ApiResult {
        return try {
            when {
                path == "/api/status" && method == "GET" -> status()
                path == "/api/location/status" && method == "GET" -> locationStatus()
                path == "/api/location/set" && method == "POST" -> locationSet(body)
                path == "/api/location/enable" && method == "POST" -> locationEnable(body)
                path == "/api/system/info" && method == "GET" -> systemInfo()
                path == "/api/route/create" && method == "POST" -> routeCreate(body)
                path == "/api/route/list" && method == "GET" -> routeList()
                path == "/api/route/get" && method == "POST" -> routeGet(body)
                path == "/api/route/delete" && method == "POST" -> routeDelete(body)
                path == "/api/route/start" && method == "POST" -> routeStart(body)
                path == "/api/route/pause" && method == "POST" -> routePause()
                path == "/api/route/resume" && method == "POST" -> routeResume()
                path == "/api/route/reset" && method == "POST" -> routeReset()
                path == "/api/route/config" && method == "POST" -> routeConfig(body)
                path == "/api/route/stop" && method == "POST" -> routeStop()
                path == "/api/route/status" && method == "GET" -> routeStatus()
                path == "/api/joystick/set" && method == "POST" -> joystickSet(body)
                path == "/api/joystick/status" && method == "GET" -> joystickStatus()
                path == "/api/joystick/reset" && method == "POST" -> joystickReset()
                path == "/api/settings/jitter" && method == "GET" -> settingsJitter()
                path == "/api/settings/jitter" && method == "POST" -> settingsJitter(body)
                path == "/api/location-point/create" && method == "POST" -> locationPointCreate(body)
                path == "/api/location-point/list" && method == "GET" -> locationPointList()
                path == "/api/location-point/use" && method == "POST" -> locationPointUse(body)
                path == "/api/location-point/delete" && method == "POST" -> locationPointDelete(body)
                path == "/api/env-snapshot/create" && method == "POST" -> envSnapshotCreate(body)
                path == "/api/env-snapshot/list" && method == "GET" -> envSnapshotList()
                path == "/api/env-snapshot/delete" && method == "POST" -> envSnapshotDelete(body)
                path == "/api/env/use" && method == "POST" -> envUse(body)
                path == "/api/env/clear" && method == "POST" -> envClear(body)
                path == "/api/env/enable" && method == "POST" -> envEnable(body)
                path == "/api/env/auto-managed" && method == "POST" -> envAutoManaged(body)
                path == "/api/env/suspend" && method == "POST" -> envSuspend()
                path == "/api/env/resume" && method == "POST" -> envResume()
                path == "/api/env/status" && method == "GET" -> envStatus()
                path == "/api/cell/status" && method == "GET" -> envStatus("cell")
                path == "/api/cell/set" && method == "POST" -> envSet("cell", body)
                path == "/api/cell/auto" && method == "POST" -> cellAuto(body)
                path == "/api/wifi/status" && method == "GET" -> envStatus("wifi")
                path == "/api/wifi/set" && method == "POST" -> envSet("wifi", body)
                path == "/api/bluetooth/status" && method == "GET" -> envStatus("ble")
                path == "/api/bluetooth/set" && method == "POST" -> envSet("ble", body)
                path == "/api/sensor/status" && method == "GET" -> envStatus("sensor")
                path == "/api/sensor/set" && method == "POST" -> envSet("sensor", body)
                path == "/api/gnss/status" && method == "GET" -> envStatus("gnss")
                path == "/api/gnss/set" && method == "POST" -> envSet("gnss", body)
                path == "/api/sim/status" && method == "GET" -> envStatus("sim")
                path == "/api/sim/set" && method == "POST" -> envSet("sim", body)
                path == "/api/profile/status" && method == "GET" -> profileStatus()
                path == "/api/module/status" && method == "GET" -> moduleStatus()
                path == "/api/module/enable" && method == "POST" -> moduleEnable(body)
                path == "/api/hook/status" && method == "POST" -> hookStatusReport(body)
                path == "/api/hook/status" && method == "GET" -> hookStatusGet()
                path == "/api/report/export" && method == "GET" -> reportExport()
                path == "/api/debug/random-env" && method == "POST" -> randomEnv()
                path == "/api/debug/load-sample-profile" && method == "POST" -> randomEnv()
                path == "/api/debug/observe/start" && method == "POST" -> observeStart()
                path == "/api/debug/observe/end" && method == "POST" -> observeEnd()
                path == "/api/debug/observe/snapshot" && method == "GET" -> observeSnapshot()
                path == "/api/test/report" && method == "POST" -> testReportSet(body)
                path == "/api/test/report" && method == "GET" -> testReportGet()
                path == "/api/recording/start" && method == "POST" -> recordingStart(body)
                path == "/api/recording/append" && method == "POST" -> recordingAppend(body)
                path == "/api/recording/stop" && method == "POST" -> recordingStop(body)
                path == "/api/recording/list" && method == "GET" -> recordingList()
                path == "/api/recording/get" && method == "POST" -> recordingGet(body)
                path == "/api/recording/delete" && method == "POST" -> recordingDelete(body)
                path == "/api/recording/play" && method == "POST" -> recordingPlay(body)
                path == "/api/recording/pause" && method == "POST" -> recordingPause()
                path == "/api/recording/resume" && method == "POST" -> recordingResume()
                path == "/api/recording/stop-play" && method == "POST" -> recordingStopPlay()
                path == "/api/recording/status" && method == "GET" -> recordingStatus()
                path == "/api/recording/speed" && method == "POST" -> recordingSpeed(body)
                path == "/api/recording/smooth" && method == "POST" -> recordingSmooth(body)
                path == "/api/preset/create" && method == "POST" -> presetCreate(body)
                path == "/api/preset/list" && method == "GET" -> presetList()
                path == "/api/preset/load" && method == "POST" -> presetLoad(body)
                path == "/api/preset/rename" && method == "POST" -> presetRename(body)
                path == "/api/preset/delete" && method == "POST" -> presetDelete(body)
                path == "/api/config/export" && method == "GET" -> configExport()
                path == "/api/config/import" && method == "POST" -> configImport(body)
                else -> ApiResult.error("not found: $method $path", 404)
            }
        } catch (t: Throwable) {
            ZLog.e(TAG_SCOPE, "route $method $path failed", t)
            ApiResult.error("internal error: ${t.message}")
        }
    }

    private fun status(): ApiResult {
        val data = JSONObject().apply {
            put("running", true)
            put("location", backend.locationState().toJson())
        }
        return ApiResult.ok("ok", data)
    }

    private fun routeCreate(body: String): ApiResult {
        val json = JSONObject(body)
        val name = json.optString("name", "")
        val pointsArr = json.optJSONArray("points") ?: JSONArray()
        if (name.isBlank() || pointsArr.length() < 2) {
            return ApiResult.error("name and at least 2 points required")
        }
        val pointsJson = pointsArr.toString()
        val remark = json.optString("remark", "")
        val speed = json.optDouble("speed", 3.5)
        val stepFrequency = json.optInt("stepFrequency", 120)
        val id = backend.createRoute(name, remark, pointsJson, speed, stepFrequency)
        ZLog.i(TAG_SCOPE, "route created id=$id name=$name points=${pointsArr.length()}")
        val data = JSONObject().apply { put("id", id) }
        return ApiResult.ok("ok", data)
    }

    private fun routeList(): ApiResult {
        val data = JSONObject()
        // JSONArray(Collection) 直接放入 JSONObject 元素，避免 toString 变成字符串数组
        data.put("routes", org.json.JSONArray(backend.listRoutes()))
        return ApiResult.ok("ok", data)
    }

    private fun routeGet(body: String): ApiResult {
        val json = JSONObject(body)
        val id = json.optLong("id", -1)
        val route = backend.getRoute(id)
            ?: return ApiResult.error("route not found: $id")
        return ApiResult.ok("ok", route)
    }

    private fun routeDelete(body: String): ApiResult {
        val json = JSONObject(body)
        val id = json.optLong("id", -1)
        return if (backend.deleteRoute(id)) {
            ApiResult.ok("deleted")
        } else {
            ApiResult.error("route not found: $id")
        }
    }

    private fun routeStart(body: String): ApiResult {
        val json = JSONObject(body)
        val id = json.optLong("id", -1)
        val speed = json.optDouble("speed", 0.0)
        val stepFrequency = json.optInt("stepFrequency", 0)
        // 循环选项缺省时走 Backend 上次配置（悬浮窗启动不丢设置）
        val loop = if (json.has("loop")) json.optBoolean("loop", false) else null
        val smoothReturn = if (json.has("smoothReturn")) json.optBoolean("smoothReturn", false) else null
        val route = backend.startRoute(id, speed, stepFrequency, loop, smoothReturn)
            ?: return ApiResult.error("route not found: $id")
        ZLog.i(TAG_SCOPE, "route start id=$id name=${route.optString("name")} stepFrequency=$stepFrequency loop=$loop smoothReturn=$smoothReturn")
        return ApiResult.ok("started", route)
    }

    private fun routePause(): ApiResult {
        backend.pauseRoute()
        return ApiResult.ok("paused")
    }

    private fun routeResume(): ApiResult {
        backend.resumeRoute()
        return ApiResult.ok("resumed")
    }

    private fun routeReset(): ApiResult {
        backend.resetRoute()
        return ApiResult.ok("reset")
    }

    private fun routeConfig(body: String): ApiResult {
        val json = JSONObject(body)
        val speed = json.optDouble("speed", 0.0)
        val stepFrequency = json.optInt("stepFrequency", 0)
        val loop = if (json.has("loop")) json.optBoolean("loop", false) else null
        val smoothReturn = if (json.has("smoothReturn")) json.optBoolean("smoothReturn", false) else null
        backend.configRoute(speed, stepFrequency, loop, smoothReturn)
        ZLog.i(TAG_SCOPE, "route config speed=$speed stepFrequency=$stepFrequency loop=$loop smoothReturn=$smoothReturn")
        return ApiResult.ok("ok", backend.routeStatusJson())
    }

    private fun routeStop(): ApiResult {
        backend.stopRoute()
        return ApiResult.ok("stopped")
    }

    private fun routeStatus(): ApiResult {
        return ApiResult.ok("ok", backend.routeStatusJson())
    }

    // ---------- Joystick ----------

    private fun joystickSet(body: String): ApiResult {
        val json = JSONObject(body)
        val enabled = json.optBoolean("enabled", false)
        val dx = json.optDouble("dx", 0.0)
        val dy = json.optDouble("dy", 0.0)
        val speedKmh = json.optDouble("speedKmh", 5.0)
        backend.setJoystickVector(enabled, dx, dy, speedKmh)
        ZLog.i(TAG_SCOPE, "joystick set enabled=$enabled dx=$dx dy=$dy speedKmh=$speedKmh")
        return ApiResult.ok("ok", backend.joystickStatusJson())
    }

    private fun joystickStatus(): ApiResult {
        return ApiResult.ok("ok", backend.joystickStatusJson())
    }

    /** 显式复位摇杆位移（回基准位置；悬浮窗“复位”按钮使用）。 */
    private fun joystickReset(): ApiResult {
        backend.resetJoystickOffset()
        return ApiResult.ok("ok", backend.joystickStatusJson())
    }

    // ---------- Settings ----------

    private fun settingsJitter(body: String? = null): ApiResult {
        if (body != null) {
            val json = JSONObject(body)
            if (json.has("enabled")) {
                backend.setJitterEnabled(json.optBoolean("enabled", true))
            }
        }
        return ApiResult.ok("ok", backend.settingsStatusJson())
    }

    // ---------- LocationPoint ----------

    private fun locationPointCreate(body: String): ApiResult {
        val json = JSONObject(body)
        val name = json.optString("name", "")
        val latitude = json.optDouble("latitude", Double.NaN)
        val longitude = json.optDouble("longitude", Double.NaN)
        if (name.isBlank() || latitude.isNaN() || longitude.isNaN()) {
            return ApiResult.error("name/latitude/longitude required")
        }
        val remark = json.optString("remark", "")
        val id = backend.createLocationPoint(name, remark, latitude, longitude)
        ZLog.i(TAG_SCOPE, "location point created id=$id name=$name")
        val data = JSONObject().apply { put("id", id) }
        return ApiResult.ok("ok", data)
    }

    private fun locationPointList(): ApiResult {
        val data = JSONObject()
        data.put("points", org.json.JSONArray(backend.listLocationPoints()))
        return ApiResult.ok("ok", data)
    }

    private fun locationPointUse(body: String): ApiResult {
        val json = JSONObject(body)
        val id = json.optLong("id", -1)
        val point = backend.getLocationPoint(id)
            ?: return ApiResult.error("location point not found: $id", 404)
        if (!backend.isModuleEnabled()) {
            return ApiResult.error("module disabled: enable module master switch first", 409)
        }
        backend.useLocationPoint(id)
            ?: return ApiResult.error("location point invalid: $id", 422)
        ZLog.i(TAG_SCOPE, "location point used id=$id name=${point.optString("name")}")
        return ApiResult.ok("applied", point)
    }

    private fun locationPointDelete(body: String): ApiResult {
        val json = JSONObject(body)
        val id = json.optLong("id", -1)
        return if (backend.deleteLocationPoint(id)) {
            ApiResult.ok("deleted")
        } else {
            ApiResult.error("location point not found: $id")
        }
    }

    // ---------- EnvSnapshot ----------

    private fun envSnapshotCreate(body: String): ApiResult {
        val json = JSONObject(body)
        val name = json.optString("name", "")
        val type = json.optString("type", "")
        if (name.isBlank() || type.isBlank()) {
            return ApiResult.error("name/type required")
        }
        val remark = json.optString("remark", "")
        val data = json.optJSONObject("data") ?: JSONObject()
        val id = backend.createEnvSnapshot(name, remark, type, data.toString())
        ZLog.i(TAG_SCOPE, "env snapshot created id=$id type=$type name=$name")
        val result = JSONObject().apply { put("id", id) }
        return ApiResult.ok("ok", result)
    }

    private fun envSnapshotList(): ApiResult {
        val data = JSONObject()
        data.put("snapshots", org.json.JSONArray(backend.listEnvSnapshots()))
        return ApiResult.ok("ok", data)
    }

    private fun envSnapshotDelete(body: String): ApiResult {
        val json = JSONObject(body)
        val id = json.optLong("id", -1)
        return if (backend.deleteEnvSnapshot(id)) {
            ApiResult.ok("deleted")
        } else {
            ApiResult.error("env snapshot not found: $id")
        }
    }

    private fun envUse(body: String): ApiResult {
        val json = JSONObject(body)
        val id = json.optLong("id", -1)
        if (!backend.isModuleEnabled()) {
            return ApiResult.error("module disabled: enable module master switch first")
        }
        val snapshot = backend.useEnvSnapshot(id)
            ?: return ApiResult.error("env snapshot not found or unsupported: $id")
        ZLog.i(TAG_SCOPE, "env use id=$id type=${snapshot.optString("type")}")
        return ApiResult.ok("applied", snapshot)
    }

    private fun envClear(body: String): ApiResult {
        val json = JSONObject(body)
        val type = json.optString("type", "")
        if (type.isBlank()) return ApiResult.error("type required")
        backend.clearEnv(type)
        return ApiResult.ok("cleared")
    }

    private fun envEnable(body: String): ApiResult {
        val json = JSONObject(body)
        val type = json.optString("type", "")
        val enabled = json.optBoolean("enabled", false)
        if (type.isBlank()) return ApiResult.error("type required")
        if (!backend.setEnvEnabled(type, enabled)) return ApiResult.error("unsupported env type: $type", 404)
        return ApiResult.ok("ok", backend.envStatus(type))
    }

    private fun envAutoManaged(body: String): ApiResult {
        val json = JSONObject(body)
        val type = json.optString("type", "")
        val auto = json.optBoolean("autoManaged", false)
        if (type.isBlank()) return ApiResult.error("type required")
        if (!backend.setEnvAutoManaged(type, auto)) return ApiResult.error("unsupported env type: $type", 404)
        return ApiResult.ok("ok", backend.envStatus(type))
    }

    private fun envStatus(): ApiResult {
        return ApiResult.ok("ok", backend.envStatusJson())
    }

    /** 临时停用全部虚拟环境（采集真实环境前调用）。 */
    private fun envSuspend(): ApiResult {
        return if (backend.suspendAll()) {
            ApiResult.ok("suspended")
        } else {
            ApiResult.ok("already suspended")
        }
    }

    /** 恢复被停用的虚拟环境。 */
    private fun envResume(): ApiResult {
        return if (backend.resumeAll()) {
            ApiResult.ok("resumed")
        } else {
            ApiResult.ok("not suspended")
        }
    }

    /** 指定环境类型状态（cell/wifi/bluetooth/sensor/gnss）。 */
    private fun envStatus(type: String): ApiResult {
        val status = backend.envStatus(type)
            ?: return ApiResult.error("unsupported env type: $type", 404)
        return ApiResult.ok("ok", status)
    }

    /** 指定环境类型直接设置虚拟数据（body 内 data 字段，缺省时整体作为数据）。 */
    private fun envSet(type: String, body: String): ApiResult {
        val json = JSONObject(body)
        val data = json.optJSONObject("data") ?: json
        if (!backend.setEnvData(type, data)) {
            return ApiResult.error("unsupported env type: $type", 404)
        }
        ZLog.i(TAG_SCOPE, "env set type=$type keys=${data.length()}")
        return ApiResult.ok("ok", backend.envStatus(type))
    }

    /** 设置基站自动托管缓存（OpenCellID 查询结果；body 无 data → null → 空基站）。 */
    private fun cellAuto(body: String): ApiResult {
        val json = JSONObject(body)
        val data = json.optJSONObject("data")
        backend.setAutoCellCache(data)
        return ApiResult.ok("ok", backend.envStatus("cell"))
    }

    private fun profileStatus(): ApiResult {
        return ApiResult.ok("ok", backend.profileInfoJson())
    }

    /** 模块总开关状态。 */
    private fun moduleStatus(): ApiResult {
        return ApiResult.ok("ok", backend.moduleStatusJson())
    }

    /** 模块总开关切换（关闭 = 一键停用模块所有功能）。 */
    private fun moduleEnable(body: String): ApiResult {
        val enabled = JSONObject(body).optBoolean("enabled", true)
        backend.setModuleEnabled(enabled)
        return ApiResult.ok("ok", backend.moduleStatusJson())
    }

    /** 各作用域进程上报 Hook 状态快照。 */
    private fun hookStatusReport(body: String): ApiResult {
        val json = try {
            JSONObject(body)
        } catch (t: Throwable) {
            return ApiResult.error("bad hook status json: ${t.message}")
        }
        val process = json.optString("process", "")
        val status = json.optJSONObject("status") ?: return ApiResult.error("status required")
        backend.reportHookStatus(process, status)
        return ApiResult.ok("ok")
    }

    /** 汇总各作用域 Hook 状态（报告/设置页展示）。 */
    private fun hookStatusGet(): ApiResult {
        return ApiResult.ok("ok", backend.hookStatusJson())
    }

    /** 完整调试报告导出。 */
    private fun reportExport(): ApiResult {
        return ApiResult.ok("ok", backend.fullDebugReportJson())
    }

    /** 调试辅助：生成全套随机虚拟环境并启用，返回生成的配置。 */
    private fun randomEnv(): ApiResult {
        val data = backend.generateRandomEnv()
        return ApiResult.ok("ok", data)
    }

    /** 开启 Hook 层真实数据观测（采集检验）。 */
    private fun observeStart(): ApiResult {
        backend.beginHookObserve()
        return ApiResult.ok("ok", backend.hookObserveSnapshotJson())
    }

    /** 结束 Hook 层观测（保留最后一次快照）。 */
    private fun observeEnd(): ApiResult {
        backend.endHookObserve()
        return ApiResult.ok("ok", backend.hookObserveSnapshotJson())
    }

    /** 读取 Hook 层观测快照（挂起期间自动补拉真实基站）。 */
    private fun observeSnapshot(): ApiResult {
        return ApiResult.ok("ok", backend.hookObserveSnapshotJson())
    }

    /** App 环境实时测试上报报告。 */
    private fun testReportSet(body: String): ApiResult {
        val json = try {
            JSONObject(body)
        } catch (t: Throwable) {
            return ApiResult.error("bad report json: ${t.message}")
        }
        backend.setTestReport(json)
        return ApiResult.ok("ok")
    }

    /** 读取最近一次环境实时测试报告。 */
    private fun testReportGet(): ApiResult {
        val report = backend.getTestReport() ?: return ApiResult.error("no test report", 404)
        return ApiResult.ok("ok", report)
    }

    // ---------- Recording ----------

    private fun recordingStart(body: String): ApiResult {
        val json = JSONObject(body)
        val name = json.optString("name", "")
        if (name.isBlank()) return ApiResult.error("name required")
        val remark = json.optString("remark", "")
        val intervalMs = (json.optDouble("intervalSec", 1.0) * 1000.0).toLong()
            .coerceIn(100L, 300_000L)
        val id = backend.startRecording(name, remark, intervalMs)
        ZLog.i(TAG_SCOPE, "recording start id=$id name=$name")
        val data = JSONObject().apply { put("id", id) }
        return ApiResult.ok("ok", data)
    }

    private fun recordingAppend(body: String): ApiResult {
        val json = JSONObject(body)
        val id = json.optLong("id", -1)
        val frame = json.optJSONObject("frame") ?: return ApiResult.error("frame required")
        val ok = backend.appendRecordingFrame(id, frame)
        return if (ok) ApiResult.ok("ok") else ApiResult.error("no active recording")
    }

    private fun recordingStop(body: String): ApiResult {
        val json = JSONObject(body)
        val id = json.optLong("id", -1)
        return if (backend.stopRecording(id)) {
            ApiResult.ok("stopped")
        } else {
            ApiResult.error("no active recording")
        }
    }

    private fun recordingList(): ApiResult {
        val data = JSONObject()
        data.put("recordings", org.json.JSONArray(backend.listRecordings()))
        return ApiResult.ok("ok", data)
    }

    private fun recordingGet(body: String): ApiResult {
        val json = JSONObject(body)
        val id = json.optLong("id", -1)
        val offset = json.optInt("offset", -1)
        val limit = json.optInt("limit", -1)
        return if (offset >= 0 && limit > 0) {
            ApiResult.ok("ok", backend.getRecordingFramesPaged(id, offset, limit))
        } else {
            val data = JSONObject()
            data.put("frames", org.json.JSONArray(backend.getRecordingFrames(id)))
            ApiResult.ok("ok", data)
        }
    }

    private fun recordingDelete(body: String): ApiResult {
        val json = JSONObject(body)
        val id = json.optLong("id", -1)
        return if (backend.deleteRecording(id)) {
            ApiResult.ok("deleted")
        } else {
            ApiResult.error("recording not found: $id")
        }
    }

    private fun recordingPlay(body: String): ApiResult {
        val json = JSONObject(body)
        val idsArr = json.optJSONArray("ids") ?: JSONArray()
        val ids = mutableListOf<Long>()
        for (i in 0 until idsArr.length()) ids.add(idsArr.optLong(i, -1L))
        if (ids.isEmpty()) return ApiResult.error("ids required")
        val loop = json.optBoolean("loop", false)
        return if (backend.playRecordings(ids, loop)) {
            ApiResult.ok("playing")
        } else {
            ApiResult.error("no playable recordings")
        }
    }

    private fun recordingPause(): ApiResult {
        backend.pauseRecordingPlayback()
        return ApiResult.ok("paused")
    }

    private fun recordingResume(): ApiResult {
        backend.resumeRecordingPlayback()
        return ApiResult.ok("resumed")
    }

    private fun recordingStopPlay(): ApiResult {
        backend.stopRecordingPlayback()
        return ApiResult.ok("stopped")
    }

    private fun recordingSpeed(body: String): ApiResult {
        val speed = JSONObject(body).optDouble("speed", 1.0).toFloat()
        backend.setRecordingPlaybackSpeed(speed)
        return ApiResult.ok("ok")
    }

    private fun recordingSmooth(body: String): ApiResult {
        val enabled = JSONObject(body).optBoolean("enabled", true)
        backend.setRecordingPlaybackSmooth(enabled)
        return ApiResult.ok("ok", backend.recordingStatusJson())
    }

    private fun recordingStatus(): ApiResult {
        return ApiResult.ok("ok", backend.recordingStatusJson())
    }

    // ---------- 配置状态预设 ----------

    private fun presetCreate(body: String): ApiResult {
        val json = JSONObject(body)
        val name = json.optString("name", "")
        if (name.isBlank()) return ApiResult.error("name required")
        val remark = json.optString("remark", "")
        val id = backend.saveConfigPreset(name, remark)
        ZLog.i(TAG_SCOPE, "config preset created id=$id name=$name")
        return ApiResult.ok("saved", JSONObject().apply { put("id", id) })
    }

    private fun presetList(): ApiResult {
        val data = JSONObject()
        data.put("presets", org.json.JSONArray(backend.listConfigPresets()))
        return ApiResult.ok("ok", data)
    }

    private fun presetLoad(body: String): ApiResult {
        val id = JSONObject(body).optLong("id", -1)
        val preset = backend.loadConfigPreset(id)
            ?: return ApiResult.error("config preset not found: $id")
        ZLog.i(TAG_SCOPE, "config preset loaded id=$id name=${preset.optString("name")}")
        return ApiResult.ok("applied", preset)
    }

    private fun presetRename(body: String): ApiResult {
        val json = JSONObject(body)
        val id = json.optLong("id", -1)
        val name = json.optString("name", "")
        if (name.isBlank()) return ApiResult.error("name required")
        val remark = json.optString("remark", "")
        return if (backend.renameConfigPreset(id, name, remark)) {
            ApiResult.ok("updated")
        } else {
            ApiResult.error("config preset not found: $id")
        }
    }

    private fun presetDelete(body: String): ApiResult {
        val id = JSONObject(body).optLong("id", -1)
        return if (backend.deleteConfigPreset(id)) {
            ApiResult.ok("deleted")
        } else {
            ApiResult.error("config preset not found: $id")
        }
    }

    // ---------- 配置整体导入导出 ----------

    private fun configExport(): ApiResult {
        return ApiResult.ok("ok", backend.exportConfigJson())
    }

    private fun configImport(body: String): ApiResult {
        val json = try {
            JSONObject(body)
        } catch (t: Throwable) {
            return ApiResult.error("bad config json: ${t.message}")
        }
        return if (backend.importConfigJson(json)) {
            ZLog.i(TAG_SCOPE, "config imported via api")
            ApiResult.ok("imported")
        } else {
            ApiResult.error("import failed")
        }
    }

    private fun locationStatus(): ApiResult {
        return ApiResult.ok("ok", backend.locationStatusJson())
    }

    private fun locationSet(body: String): ApiResult {
        val json = JSONObject(body)
        val latitude = json.optDouble("latitude", Double.NaN)
        val longitude = json.optDouble("longitude", Double.NaN)
        if (latitude.isNaN() || longitude.isNaN()) {
            return ApiResult.error("latitude/longitude required")
        }
        val speed = json.optDouble("speed", 0.0).toFloat()
        val bearing = json.optDouble("bearing", 0.0).toFloat()
        backend.setLocationPoint(latitude, longitude, speed, bearing)
        ZLog.i(TAG_SCOPE, "location set lat=$latitude lon=$longitude")
        return ApiResult.ok("ok", backend.locationState().toJson())
    }

    private fun locationEnable(body: String): ApiResult {
        val json = JSONObject(body)
        val enabled = json.optBoolean("enabled", false)
        backend.setLocationEnabled(enabled)
        ZLog.i(TAG_SCOPE, "location enable=$enabled")
        return ApiResult.ok("ok", backend.locationState().toJson())
    }

    private fun systemInfo(): ApiResult {
        val data = JSONObject().apply {
            put("apiVersion", 1)
            put("phase", "1")
            put("package", "io.github.fairyxh.VirtualEnv")
            put("moduleEnabled", backend.isModuleEnabled())
            put("moduleVersion", backend.moduleVersion())
            put("device", backend.deviceInfoJson())
        }
        return ApiResult.ok("ok", data)
    }

    private fun writeResponse(output: OutputStream, result: ApiResult) {
        val json = result.toJson().toString()
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val header =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }
}
