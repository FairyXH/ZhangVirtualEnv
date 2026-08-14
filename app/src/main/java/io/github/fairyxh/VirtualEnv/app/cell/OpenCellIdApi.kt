package io.github.fairyxh.VirtualEnv.app.cell

import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.cos

/**
 * OpenCellID API 客户端（HTTPS，控制端进程内直连，不经过模块 Backend）。
 *
 * - 查询统一走 `cell/getInArea`（BBOX）+ 客户端 Haversine 二次过滤（radius 语义）。
 * - 数据贡献走 `measure/uploadJson`（multipart POST，API Key 不暴露在 URL）。
 * - 所有请求集中在 API Key 校验 / 错误映射 / credits 限制处理。
 */
object OpenCellIdApi {

    private const val TAG_SCOPE = "OpenCellId"
    private const val BASE_URL = "https://opencellid.org"
    private const val DEFAULT_LIMIT = 50
    private const val CONNECT_TIMEOUT_MS = 10000
    private const val READ_TIMEOUT_MS = 20000
    private const val MAX_UPLOAD_BATCH = 50

    /** 用户可读错误。 */
    class ApiFailure(
        val friendly: String,
        val httpCode: Int = -1,
        val code: Int = -1
    ) : Exception(friendly) {
        override fun toString(): String = friendly
    }

    private data class HttpResponse(val httpCode: Int, val body: String?)

    // ---------- 查询 ----------

    /**
     * 查询指定位置附近的小区。
     *
     * @param radiusMeters 查询半径（米），内部转 BBOX 后再次按 Haversine 过滤。
     * @param radio 可选过滤：GSM / UMTS / LTE / NBIOT / NR / CDMA。
     */
    fun getNearbyCells(
        apiKey: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Int,
        radio: String? = null,
        mcc: Int? = null,
        mnc: Int? = null,
        lac: Int? = null,
        limit: Int = DEFAULT_LIMIT
    ): Result<List<CellInfo>> {
        if (apiKey.isBlank()) return Result.failure(ApiFailure("请先在设置中填写 OpenCellID API Key"))
        val bbox = bboxFor(latitude, longitude, radiusMeters)

        val query = StringBuilder("key=").append(urlEncode(apiKey))
            .append("&BBOX=").append(urlEncode(bbox))
            .append("&limit=").append(limit.coerceIn(1, DEFAULT_LIMIT))
            .append("&format=json")
        radio?.takeIf { it.isNotBlank() }?.let { query.append("&radio=").append(urlEncode(it.uppercase())) }
        mcc?.takeIf { it >= 0 }?.let { query.append("&mcc=").append(it) }
        mnc?.takeIf { it >= 0 }?.let { query.append("&mnc=").append(it) }
        lac?.takeIf { it >= 0 }?.let { query.append("&lac=").append(it) }

        val resp = get("$BASE_URL/cell/getInArea?$query")
            ?: return Result.failure(ApiFailure("OpenCellID 服务暂时不可用"))
        if (resp.httpCode !in 200..299) {
            return Result.failure(mapHttp(resp.httpCode, parseBodySafe(resp.body)))
        }
        val body = resp.body ?: return Result.failure(ApiFailure("OpenCellID 返回格式异常"))
        val parsed = parseBody(body) ?: return Result.failure(ApiFailure("OpenCellID 返回格式异常"))
        val cellsArr = parsed.optJSONArray("cells") ?: JSONArray()
        val cells = mutableListOf<CellInfo>()
        for (i in 0 until cellsArr.length()) {
            val obj = cellsArr.optJSONObject(i) ?: continue
            val cell = CellInfo.fromJson(obj)
            if (cell.latitude == 0.0 && cell.longitude == 0.0) continue
            // BBOX 是矩形，再按真实距离过滤，保证 radius 语义
            if (cell.distanceMeters(latitude, longitude) <= radiusMeters) {
                cells.add(cell)
            }
        }
        ZLog.i(TAG_SCOPE, "getInArea radius=$radiusMeters -> ${cells.size} cells (key=${OpenCellIdSettings.logSafe(apiKey)})")
        return Result.success(cells)
    }

    /** 测试 API Key：用小 BBOX 请求 getInArea，仅校验鉴权与配额，不返回业务数据。 */
    fun testKey(apiKey: String): Result<String> {
        if (apiKey.isBlank()) return Result.failure(ApiFailure("请输入 API Key"))
        val query = "key=${urlEncode(apiKey)}&BBOX=31.0,121.0,31.01,121.01&limit=1&format=json"
        val resp = get("$BASE_URL/cell/getInArea?$query")
            ?: return Result.failure(ApiFailure("OpenCellID 服务暂时不可用"))
        if (resp.httpCode !in 200..299) {
            return Result.failure(mapHttp(resp.httpCode, parseBodySafe(resp.body)))
        }
        val body = resp.body ?: return Result.failure(ApiFailure("OpenCellID 返回格式异常"))
        val parsed = parseBody(body) ?: return Result.failure(ApiFailure("OpenCellID 返回格式异常"))
        val count = parsed.optJSONArray("cells")?.length() ?: 0
        return Result.success("API Key 有效（返回 $count 个小区）")
    }

    // ---------- 数据贡献 ----------

    /**
     * 批量上传测量数据（`measure/uploadJson`，multipart/form-data）。
     *
     * 只接受真实设备观测数据；虚拟基站/虚拟坐标严禁上传（调用方保证）。
     */
    fun uploadMeasurements(apiKey: String, measurements: List<JSONObject>): Result<Unit> {
        if (apiKey.isBlank()) return Result.failure(ApiFailure("未配置 OpenCellID API Key"))
        if (measurements.isEmpty()) return Result.failure(ApiFailure("没有待上传的测量数据"))
        var idx = 0
        while (idx < measurements.size) {
            val batch = measurements.subList(idx, minOf(idx + MAX_UPLOAD_BATCH, measurements.size))
            val payload = JSONObject().apply { put("measurements", JSONArray(batch)) }
            val code = uploadJson(apiKey, payload)
            if (code !in 200..299) {
                return Result.failure(mapHttp(code, null))
            }
            idx += batch.size
        }
        ZLog.i(TAG_SCOPE, "uploadJson ok count=${measurements.size} (key=${OpenCellIdSettings.logSafe(apiKey)})")
        return Result.success(Unit)
    }

    // ---------- 内部 HTTP ----------

    private fun get(urlStr: String): HttpResponse? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code in 200..299) {
                HttpResponse(code, readStream(conn.inputStream))
            } else {
                val body = readStream(conn.errorStream)
                ZLog.w(TAG_SCOPE, "GET failed http=$code body=${body?.take(200)}")
                HttpResponse(code, body)
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "GET network error: ${t.message}")
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun uploadJson(apiKey: String, payload: JSONObject): Int {
        var conn: HttpURLConnection? = null
        return try {
            val boundary = "----ZVE" + System.currentTimeMillis()
            conn = URL("$BASE_URL/measure/uploadJson").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setRequestProperty("Accept", "application/json")

            val body = StringBuilder()
            body.append("--$boundary\r\n")
            body.append("Content-Disposition: form-data; name=\"key\"\r\n\r\n")
            body.append(apiKey).append("\r\n")
            body.append("--$boundary\r\n")
            body.append("Content-Disposition: form-data; name=\"datafile\"; filename=\"measurements.json\"\r\n")
            body.append("Content-Type: application/json\r\n\r\n")
            body.append(payload.toString()).append("\r\n")
            body.append("--$boundary--\r\n")

            DataOutputStream(conn.outputStream).use { out ->
                out.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
            val code = conn.responseCode
            val resp = if (code in 200..299) readStream(conn.inputStream) else readStream(conn.errorStream)
            ZLog.i(TAG_SCOPE, "uploadJson http=$code resp=${resp?.take(160)}")
            code
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "uploadJson network error: ${t.message}")
            -1
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun parseBodySafe(body: String?): JSONObject? {
        if (body.isNullOrBlank()) return null
        return try {
            JSONObject(body)
        } catch (_: Throwable) {
            null
        }
    }

    private fun readStream(stream: java.io.InputStream?): String? {
        if (stream == null) return null
        return try {
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
        } catch (t: Throwable) {
            null
        }
    }

    private fun parseBody(body: String): JSONObject? {
        return try {
            JSONObject(body)
        } catch (t: Throwable) {
            // 部分接口可能返回数组或非 JSON，尝试包裹
            try {
                JSONObject().apply { put("cells", JSONArray(body)) }
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun mapHttp(httpCode: Int, body: JSONObject?): ApiFailure {
        val apiCode = body?.optInt("code", -1) ?: -1
        return when {
            httpCode == 401 || apiCode == 2 -> ApiFailure("API Key 无效", httpCode, apiCode)
            httpCode == 400 || apiCode == 3 -> ApiFailure("请求参数错误", httpCode, apiCode)
            httpCode == 403 || apiCode == 4 -> ApiFailure("API Key 未获得社区 API 权限（需贡献数据或白名单）", httpCode, apiCode)
            httpCode == 500 || apiCode == 5 -> ApiFailure("OpenCellID 服务暂时不可用", httpCode, apiCode)
            httpCode == 503 || apiCode == 6 -> ApiFailure("请求过于频繁，请稍后重试", httpCode, apiCode)
            httpCode == 429 || apiCode == 7 -> ApiFailure("今日 API 使用量已达到限制", httpCode, apiCode)
            httpCode == -1 -> ApiFailure("网络请求失败，请检查网络连接")
            else -> ApiFailure("OpenCellID 请求失败（HTTP $httpCode）", httpCode, apiCode)
        }
    }

    /** 经纬度 + 半径 → OpenCellID BBOX（latmin,lonmin,latmax,lonmax）。 */
    private fun bboxFor(latitude: Double, longitude: Double, radiusMeters: Int): String {
        val radius = radiusMeters.coerceAtLeast(100)
        val latDelta = radius / 111_320.0
        val lonDelta = radius / (111_320.0 * cos(Math.toRadians(latitude)).coerceAtLeast(0.01))
        val latMin = latitude - latDelta
        val latMax = latitude + latDelta
        val lonMin = longitude - lonDelta
        val lonMax = longitude + lonDelta
        return String.format("%.6f,%.6f,%.6f,%.6f", latMin, lonMin, latMax, lonMax)
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
