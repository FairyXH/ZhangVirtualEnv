package io.github.fairyxh.VirtualEnv.core

import io.github.fairyxh.VirtualEnv.core.model.ApiResult
import io.github.fairyxh.VirtualEnv.util.ZLog
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
