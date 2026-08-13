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

    /** 访问令牌：由控制端 Application 从自身 APK assets/api_token.txt 初始化。 */
    @Volatile
    var token: String = ""

    /** 由控制端 Application 调用，从 assets 读取访问令牌。 */
    fun initTokenFromAssets(context: android.content.Context) {
        token = try {
            context.assets.open("api_token.txt").bufferedReader().use { it.readText().trim() }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "api token load failed: ${t.message}")
            ""
        }
    }

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
        return createRoute(name, "", points)
    }

    fun createRoute(name: String, remark: String, points: List<com.amap.api.maps.model.LatLng>): ApiResult {
        val arr = org.json.JSONArray()
        points.forEach { p ->
            arr.put(org.json.JSONObject().apply {
                put("lat", p.latitude)
                put("lon", p.longitude)
            })
        }
        val body = JSONObject().apply {
            put("name", name)
            put("remark", remark)
            put("points", arr)
        }
        return post("/api/route/create", body)
    }

    fun listRoutes(): ApiResult = get("/api/route/list")

    fun deleteRoute(id: Long): ApiResult {
        val body = JSONObject().apply { put("id", id) }
        return post("/api/route/delete", body)
    }

    fun getRoute(id: Long): ApiResult {
        val body = JSONObject().apply { put("id", id) }
        return post("/api/route/get", body)
    }

    // ---------- 路线模拟控制 ----------

    /**
     * 一键启动路线模拟。speed 为 km/h，<=0 时使用路线默认速度；stepFrequency<=0 用路线默认步频。
     * loop/smoothReturn 为 null 时后端沿用上次配置（悬浮窗启动不丢循环设置）。
     */
    fun startRoute(
        id: Long,
        speedKmh: Double = 0.0,
        stepFrequency: Int = 0,
        loop: Boolean? = null,
        smoothReturn: Boolean? = null
    ): ApiResult {
        val body = JSONObject().apply {
            put("id", id)
            put("speed", speedKmh)
            put("stepFrequency", stepFrequency)
            if (loop != null) put("loop", loop)
            if (smoothReturn != null) put("smoothReturn", smoothReturn)
        }
        return post("/api/route/start", body)
    }

    fun pauseRoute(): ApiResult = post("/api/route/pause", JSONObject())

    fun resumeRoute(): ApiResult = post("/api/route/resume", JSONObject())

    fun resetRoute(): ApiResult = post("/api/route/reset", JSONObject())

    /** 更新路线运行参数：speedKmh/stepFrequency 传 0 表示不修改；loop/smoothReturn 传 null 表示不修改。 */
    fun configRoute(
        speedKmh: Double = 0.0,
        stepFrequency: Int = 0,
        loop: Boolean? = null,
        smoothReturn: Boolean? = null
    ): ApiResult {
        val body = JSONObject().apply {
            put("speed", speedKmh)
            put("stepFrequency", stepFrequency)
            if (loop != null) put("loop", loop)
            if (smoothReturn != null) put("smoothReturn", smoothReturn)
        }
        return post("/api/route/config", body)
    }

    fun stopRoute(): ApiResult = post("/api/route/stop", JSONObject())

    fun getRouteStatus(): ApiResult = get("/api/route/status")

    // ---------- Joystick ----------

    /** 悬浮窗摇杆向量：enabled=false 时停止并回基准。 */
    fun setJoystick(enabled: Boolean, dx: Double, dy: Double, speedKmh: Double): ApiResult {
        val body = JSONObject().apply {
            put("enabled", enabled)
            put("dx", dx)
            put("dy", dy)
            put("speedKmh", speedKmh)
        }
        return post("/api/joystick/set", body)
    }

    fun getJoystickStatus(): ApiResult = get("/api/joystick/status")

    // ---------- LocationPoint ----------

    fun createLocationPoint(name: String, remark: String, latitude: Double, longitude: Double): ApiResult {
        val body = JSONObject().apply {
            put("name", name)
            put("remark", remark)
            put("latitude", latitude)
            put("longitude", longitude)
        }
        return post("/api/location-point/create", body)
    }

    fun listLocationPoints(): ApiResult = get("/api/location-point/list")

    /** 一键使用已保存地点：设置坐标并启用。 */
    fun useLocationPoint(id: Long): ApiResult {
        val body = JSONObject().apply { put("id", id) }
        return post("/api/location-point/use", body)
    }

    fun deleteLocationPoint(id: Long): ApiResult {
        val body = JSONObject().apply { put("id", id) }
        return post("/api/location-point/delete", body)
    }

    // ---------- EnvSnapshot ----------

    fun createEnvSnapshot(name: String, remark: String, type: String, data: org.json.JSONObject): ApiResult {
        val body = JSONObject().apply {
            put("name", name)
            put("remark", remark)
            put("type", type)
            put("data", data)
        }
        return post("/api/env-snapshot/create", body)
    }

    fun listEnvSnapshots(): ApiResult = get("/api/env-snapshot/list")

    fun deleteEnvSnapshot(id: Long): ApiResult {
        val body = JSONObject().apply { put("id", id) }
        return post("/api/env-snapshot/delete", body)
    }

    // ---------- 虚拟环境 ----------

    /** 一键使用环境快照：加载到对应模拟引擎。 */
    fun useEnvSnapshot(id: Long): ApiResult {
        val body = JSONObject().apply { put("id", id) }
        return post("/api/env/use", body)
    }

    fun clearEnv(type: String): ApiResult {
        val body = JSONObject().apply { put("type", type) }
        return post("/api/env/clear", body)
    }

    /** 单类型开关：关闭时 Hook 放行真实数据（数据保留），开启时恢复。 */
    fun setEnvEnabled(type: String, enabled: Boolean): ApiResult {
        val body = JSONObject().apply {
            put("type", type)
            put("enabled", enabled)
        }
        return post("/api/env/enable", body)
    }

    fun getEnvStatus(): ApiResult = get("/api/env/status")

    /** 上报环境实时测试结果报告（供自动化验证/修正 Hook）。 */
    fun postTestReport(report: org.json.JSONObject): ApiResult = post("/api/test/report", report)

    /** 临时停用全部虚拟环境（采集真实环境前调用，可嵌套）。 */
    fun suspendEnv(): ApiResult = post("/api/env/suspend", JSONObject())

    /** 恢复被停用的虚拟环境。 */
    fun resumeEnv(): ApiResult = post("/api/env/resume", JSONObject())

    /** 直接设置指定环境类型的虚拟数据（cell/wifi/bluetooth/sensor/gnss）。 */
    fun setEnvData(type: String, data: org.json.JSONObject): ApiResult {
        val body = JSONObject().apply { put("data", data) }
        return post("/api/${envPath(type)}/set", body)
    }

    /** 查询指定环境类型的虚拟数据状态。 */
    fun getEnvStatus(type: String): ApiResult = get("/api/${envPath(type)}/status")

    /** 当前生效 Profile 信息（排障用）。 */
    fun getProfileStatus(): ApiResult = get("/api/profile/status")

    private fun envPath(type: String): String {
        return when (type) {
            "ble" -> "bluetooth"
            else -> type
        }
    }

    // ---------- Recording ----------

    fun startRecording(name: String, remark: String = ""): ApiResult {
        val body = JSONObject().apply {
            put("name", name)
            put("remark", remark)
        }
        return post("/api/recording/start", body)
    }

    /** 追加一帧采集数据（collectAll 输出格式）。 */
    fun appendRecordingFrame(id: Long, frame: org.json.JSONObject): ApiResult {
        val body = JSONObject().apply {
            put("id", id)
            put("frame", frame)
        }
        return post("/api/recording/append", body)
    }

    fun stopRecording(id: Long): ApiResult {
        val body = JSONObject().apply { put("id", id) }
        return post("/api/recording/stop", body)
    }

    fun listRecordings(): ApiResult = get("/api/recording/list")

    /** 取录像帧；传 offset/limit 时分页返回（响应带 total/firstTs/lastTs）。 */
    fun getRecordingFrames(id: Long, offset: Int = -1, limit: Int = -1): ApiResult {
        val body = JSONObject().apply {
            put("id", id)
            if (offset >= 0) put("offset", offset)
            if (limit > 0) put("limit", limit)
        }
        return post("/api/recording/get", body)
    }

    fun deleteRecording(id: Long): ApiResult {
        val body = JSONObject().apply { put("id", id) }
        return post("/api/recording/delete", body)
    }

    /** 播放录像列表（顺序播放），loop 表示末尾循环。 */
    fun playRecordings(ids: List<Long>, loop: Boolean): ApiResult {
        val body = JSONObject().apply {
            put("ids", org.json.JSONArray(ids))
            put("loop", loop)
        }
        return post("/api/recording/play", body)
    }

    fun pauseRecordingPlayback(): ApiResult = post("/api/recording/pause", JSONObject())

    fun resumeRecordingPlayback(): ApiResult = post("/api/recording/resume", JSONObject())

    fun stopRecordingPlayback(): ApiResult = post("/api/recording/stop-play", JSONObject())

    fun setRecordingSpeed(speed: Float): ApiResult {
        val body = JSONObject().apply { put("speed", speed.toDouble()) }
        return post("/api/recording/speed", body)
    }

    fun getRecordingStatus(): ApiResult = get("/api/recording/status")

    // ---------- 配置状态预设 ----------

    /** 保存当前完整配置状态（位置/路线/摇杆 + 环境六大板块）为预设。 */
    fun createConfigPreset(name: String, remark: String): ApiResult {
        val body = JSONObject().apply {
            put("name", name)
            put("remark", remark)
        }
        return post("/api/preset/create", body)
    }

    fun listConfigPresets(): ApiResult = get("/api/preset/list")

    /** 一键加载配置状态预设。 */
    fun loadConfigPreset(id: Long): ApiResult {
        val body = JSONObject().apply { put("id", id) }
        return post("/api/preset/load", body)
    }

    fun renameConfigPreset(id: Long, name: String, remark: String): ApiResult {
        val body = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("remark", remark)
        }
        return post("/api/preset/rename", body)
    }

    fun deleteConfigPreset(id: Long): ApiResult {
        val body = JSONObject().apply { put("id", id) }
        return post("/api/preset/delete", body)
    }

    // ---------- 配置整体导入导出 ----------

    /** 导出模块整体配置（data 即完整配置 JSON）。 */
    fun exportConfig(): ApiResult = get("/api/config/export")

    /** 导入模块整体配置（整体覆盖并立即生效）。 */
    fun importConfig(json: JSONObject): ApiResult = post("/api/config/import", json)

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
            // 后端为单请求即关闭连接（Connection: close），禁止 keep-alive 复用，
            // 否则死连接上的后续 POST 会立即抛 EOFException（message=null）。
            conn.setRequestProperty("Connection", "close")
            // 访问令牌：未设置时后端返回 404，调用方按不可达处理
            if (token.isNotEmpty()) {
                conn.setRequestProperty("X-ZVE-Token", token)
            }
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
            ZLog.w(TAG_SCOPE, "$method $path failed: ${t.javaClass.name}: ${t.message}", t)
            ApiResult.error("backend unreachable: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            conn.disconnect()
        }
    }
}
