package io.github.fairyxh.VirtualEnv.app

import io.github.fairyxh.VirtualEnv.core.model.ApiResult
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Backend API 客户端。
 *
 * 控制端所有操作必须经此访问 Backend，禁止直接操作配置/数据库/Hook 状态。
 */
object ApiClient {

    private const val TAG_SCOPE = "ApiClient"
    private const val BASE_URL = "http://127.0.0.1:18790"

    /** 后端是否可达。 */
    fun ping(): Boolean {
        return try {
            val result = get("/api/status")
            result.code == ApiResult.CODE_OK
        } catch (t: Throwable) {
            ZLog.d(TAG_SCOPE, "ping failed: ${t.message}")
            false
        }
    }

    fun getStatus(): ApiResult = get("/api/status")

    fun getLocationStatus(): ApiResult = get("/api/location/status")

    fun setLocation(latitude: Double, longitude: Double, speed: Float, bearing: Float): ApiResult {
        val body = JSONObject().apply {
            put("latitude", latitude)
            put("longitude", longitude)
            put("speed", speed)
            put("bearing", bearing)
        }
        return post("/api/location/set", body)
    }

    fun setLocationEnabled(enabled: Boolean): ApiResult {
        val body = JSONObject().apply { put("enabled", enabled) }
        return post("/api/location/enable", body)
    }

    fun getSystemInfo(): ApiResult = get("/api/system/info")

    fun createRoute(name: String, points: List<com.amap.api.maps.model.LatLng>): ApiResult {
        val arr = org.json.JSONArray()
        points.forEach { p ->
            arr.put(org.json.JSONObject().apply {
                put("lat", p.latitude)
                put("lon", p.longitude)
            })
        }
        val body = JSONObject().apply {
            put("name", name)
            put("points", arr)
        }
        return post("/api/route/create", body)
    }

    fun listRoutes(): ApiResult = get("/api/route/list")

    private fun get(path: String): ApiResult {
        return request("GET", path, null)
    }

    private fun post(path: String, body: JSONObject?): ApiResult {
        return request("POST", path, body?.toString())
    }

    private fun request(method: String, path: String, body: String?): ApiResult {
        val conn = URL(BASE_URL + path).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use(BufferedReader::readText) ?: ""
            val json = JSONObject(text)
            ApiResult(
                code = json.optInt("code", ApiResult.CODE_ERROR),
                message = json.optString("message", ""),
                data = json.optJSONObject("data")
            )
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "$method $path failed: ${t.message}")
            ApiResult.error("backend unreachable: ${t.message}")
        } finally {
            conn.disconnect()
        }
    }
}
