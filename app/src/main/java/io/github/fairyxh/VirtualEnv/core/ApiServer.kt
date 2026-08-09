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
 */
class ApiServer(
    private val port: Int,
    private val backend: Backend,
) {
    companion object {
        private const val TAG_SCOPE = "Api"
        const val DEFAULT_PORT = 18790

        private const val BIND_ADDRESS = "127.0.0.1"
        private const val MAX_BODY = 1 shl 20 // 1MB
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        return try {
            serverSocket = ServerSocket(port, 50, InetAddress.getByName(BIND_ADDRESS))
            executor = Executors.newCachedThreadPool()
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
        while (running.get()) {
            try {
                val socket = serverSocket?.accept() ?: break
                executor?.execute { handle(socket) }
            } catch (t: Throwable) {
                if (running.get()) ZLog.w(TAG_SCOPE, "accept failed", t)
                break
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')

                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                val body = if (contentLength > 0 && contentLength <= MAX_BODY) {
                    val chars = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(chars, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(chars, 0, read)
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
                path == "/api/location-point/create" && method == "POST" -> locationPointCreate(body)
                path == "/api/location-point/list" && method == "GET" -> locationPointList()
                path == "/api/location-point/use" && method == "POST" -> locationPointUse(body)
                path == "/api/location-point/delete" && method == "POST" -> locationPointDelete(body)
                path == "/api/env-snapshot/create" && method == "POST" -> envSnapshotCreate(body)
                path == "/api/env-snapshot/list" && method == "GET" -> envSnapshotList()
                path == "/api/env-snapshot/delete" && method == "POST" -> envSnapshotDelete(body)
                path == "/api/env/use" && method == "POST" -> envUse(body)
                path == "/api/env/clear" && method == "POST" -> envClear(body)
                path == "/api/env/status" && method == "GET" -> envStatus()
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
        val route = backend.startRoute(id, speed)
            ?: return ApiResult.error("route not found: $id")
        ZLog.i(TAG_SCOPE, "route start id=$id name=${route.optString("name")}")
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
        backend.configRoute(speed, stepFrequency)
        ZLog.i(TAG_SCOPE, "route config speed=$speed stepFrequency=$stepFrequency")
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
        val point = backend.useLocationPoint(id)
            ?: return ApiResult.error("location point not found: $id")
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

    private fun envStatus(): ApiResult {
        return ApiResult.ok("ok", backend.envStatusJson())
    }

    // ---------- Recording ----------

    private fun recordingStart(body: String): ApiResult {
        val json = JSONObject(body)
        val name = json.optString("name", "")
        if (name.isBlank()) return ApiResult.error("name required")
        val remark = json.optString("remark", "")
        val id = backend.startRecording(name, remark)
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
        val data = JSONObject()
        data.put("frames", org.json.JSONArray(backend.getRecordingFrames(id)))
        return ApiResult.ok("ok", data)
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

    private fun recordingStatus(): ApiResult {
        return ApiResult.ok("ok", backend.recordingStatusJson())
    }

    private fun locationStatus(): ApiResult {
        return ApiResult.ok("ok", backend.locationState().toJson())
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
