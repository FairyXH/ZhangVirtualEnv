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
import kotlin.math.ceil
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

    /** 单片 BBOX 边长（米）：面积约 3.24 km²，低于服务端 4,000,000 m² 限制，留安全余量。 */
    private const val TILE_METERS = 1800
    /** 分片总数上限：超过说明查询范围过大，拒绝（约支持半径 4.5km 内）。 */
    private const val MAX_TILES = 25

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
        // BBOX 面积限制（服务端实测：单次 ≤ 4,000,000 m²，约 2km×2km）。
        // 目标范围超限时按网格分片逐片查询后合并。
        val tiles = bboxTiles(latitude, longitude, radiusMeters)
        if (tiles.isEmpty()) {
            val failure = ApiFailure("查询范围过大，请缩小查询半径（单次在线查询约支持半径 4.5km 内）")
            ZLog.w(TAG_SCOPE, "getInArea rejected radius=$radiusMeters -> ${failure.message}")
            return Result.failure(failure)
        }
        ZLog.i(
            TAG_SCOPE,
            "getInArea radius=$radiusMeters -> ${tiles.size} tile(s) (key=${OpenCellIdSettings.logSafe(apiKey)})"
        )
        val all = mutableListOf<CellInfo>()
        tiles.forEachIndexed { index, bbox ->
            val query = StringBuilder("key=").append(urlEncode(apiKey))
                // BBOX 逗号分隔：逗号在 query 中合法，不 URL 编码（编码 %2C 可能被部分服务端原样解析）
                .append("&BBOX=").append(bbox)
                .append("&limit=").append(limit.coerceIn(1, DEFAULT_LIMIT))
                .append("&format=json")
            radio?.takeIf { it.isNotBlank() }?.let { query.append("&radio=").append(urlEncode(it.uppercase())) }
            mcc?.takeIf { it >= 0 }?.let { query.append("&mcc=").append(it) }
            mnc?.takeIf { it >= 0 }?.let { query.append("&mnc=").append(it) }
            lac?.takeIf { it >= 0 }?.let { query.append("&lac=").append(it) }

            val resp = get("$BASE_URL/cell/getInArea?$query")
                ?: return Result.failure(ApiFailure("OpenCellID 服务暂时不可用"))
            if (resp.httpCode !in 200..299) {
                val failure = mapHttp(resp.httpCode, parseBodySafe(resp.body))
                ZLog.w(
                    TAG_SCOPE,
                    "getInArea tile[$index/$tiles.size] failed http=${resp.httpCode} bbox=$bbox " +
                        "body=${resp.body?.take(300)} key=${OpenCellIdSettings.logSafe(apiKey)} -> ${failure.message}"
                )
                return Result.failure(failure)
            }
            val body = resp.body ?: return Result.failure(ApiFailure("OpenCellID 返回格式异常"))
            val parsed = parseBody(body) ?: return Result.failure(ApiFailure("OpenCellID 返回格式异常"))
            // OpenCellID 对无效 Key / 权限不足等错误也返回 HTTP 200，错误在 body：{"error":"...","code":N}
            parseApiError(parsed, resp.httpCode)?.let { failure ->
                ZLog.w(
                    TAG_SCOPE,
                    "getInArea tile[$index/$tiles.size] business error code=${failure.code} bbox=$bbox " +
                        "body=${resp.body?.take(300)} key=${OpenCellIdSettings.logSafe(apiKey)} -> ${failure.message}"
                )
                return Result.failure(failure)
            }
            val cellsArr = parsed.optJSONArray("cells") ?: JSONArray()
            var tileCount = 0
            for (i in 0 until cellsArr.length()) {
                val obj = cellsArr.optJSONObject(i) ?: continue
                val cell = CellInfo.fromJson(obj)
                if (cell.latitude == 0.0 && cell.longitude == 0.0) continue
                // BBOX 是矩形，再按真实距离过滤，保证 radius 语义
                if (cell.distanceMeters(latitude, longitude) <= radiusMeters) {
                    all.add(cell)
                    tileCount++
                }
            }
            ZLog.d(TAG_SCOPE, "getInArea tile[$index/$tiles.size] bbox=$bbox -> $tileCount cells")
        }
        // 分片边界可能重复命中同一小区，按小区身份去重
        val seen = HashSet<String>()
        val deduped = all.filter { seen.add(it.dedupeKey()) }
        ZLog.i(TAG_SCOPE, "getInArea merged ${deduped.size} cells (raw ${all.size}) radius=$radiusMeters key=${OpenCellIdSettings.logSafe(apiKey)}")
        return Result.success(deduped)
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
        parseApiError(parsed, resp.httpCode)?.let { return Result.failure(it) }
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
            val resp = uploadJson(apiKey, payload)
            if (resp.httpCode !in 200..299) {
                return Result.failure(mapHttp(resp.httpCode, parseBodySafe(resp.body)))
            }
            parseBodySafe(resp.body)?.let { parsed ->
                parseApiError(parsed, resp.httpCode)?.let { return Result.failure(it) }
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

    private fun uploadJson(apiKey: String, payload: JSONObject): HttpResponse {
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
            HttpResponse(code, resp)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "uploadJson network error: ${t.message}")
            HttpResponse(-1, null)
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    /** 解析 OpenCellID 业务错误体（错误时 HTTP 仍可能为 200）：`{"error":"...","code":N}`。 */
    private fun parseApiError(body: JSONObject, httpCode: Int): ApiFailure? {
        val code = body.optInt("code", 0)
        val error = body.optString("error", "")
        if (error.isNotEmpty() || (body.has("code") && code != 0)) {
            return mapHttp(httpCode, body)
        }
        return null
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
        val apiError = body?.optString("error", "") ?: ""
        // 所有分支都带出服务端 error 原文，便于用户调试
        val detail = if (apiError.isNotEmpty()) "：$apiError" else ""
        return when {
            apiCode == 2 || httpCode == 401 -> ApiFailure(
                if (apiError.isNotEmpty()) "API Key 无效：$apiError" else "API Key 无效",
                httpCode, apiCode
            )
            apiCode == 3 || httpCode == 400 -> ApiFailure("请求参数错误$detail", httpCode, apiCode)
            apiCode == 4 || httpCode == 403 -> ApiFailure("API Key 未获得社区 API 权限$detail（需贡献数据或白名单）", httpCode, apiCode)
            apiCode == 5 || httpCode == 500 -> ApiFailure("OpenCellID 服务暂时不可用$detail", httpCode, apiCode)
            apiCode == 6 || httpCode == 503 -> ApiFailure("请求过于频繁，请稍后重试$detail", httpCode, apiCode)
            apiCode == 7 || httpCode == 429 -> ApiFailure("今日 API 使用量已达到限制$detail", httpCode, apiCode)
            httpCode == -1 -> ApiFailure("网络请求失败，请检查网络连接")
            else -> ApiFailure(
                if (apiError.isNotEmpty()) "OpenCellID 请求失败：$apiError" else "OpenCellID 请求失败（HTTP $httpCode）",
                httpCode, apiCode
            )
        }
    }

    /**
     * 按 OpenCellID BBOX 面积限制（单次 ≤ 4,000,000 m²，约 2km×2km）把目标范围切分为网格分片。
     *
     * 单片边长 [TILE_METERS]（留安全余量），行/列向上取整，每片都是独立合法 BBOX；
     * 半径 ≤ [TILE_METERS] 时单片即可。分片总数超 [MAX_TILES] 返回空（调用方拒绝过大范围）。
     */
    private fun bboxTiles(latitude: Double, longitude: Double, radiusMeters: Int): List<String> {
        val radius = radiusMeters.coerceAtLeast(100)
        val latSpanMeters = radius * 2.0
        val latMetersPerDeg = 111_320.0
        val lonMetersPerDeg = latMetersPerDeg * cos(Math.toRadians(latitude)).coerceAtLeast(0.01)
        val rows = ceil(latSpanMeters / TILE_METERS).toInt().coerceAtLeast(1)
        val cols = ceil(latSpanMeters / TILE_METERS).toInt().coerceAtLeast(1)
        if (rows * cols > MAX_TILES) return emptyList()
        val latMinBase = latitude - radius / latMetersPerDeg
        val lonMinBase = longitude - radius / lonMetersPerDeg
        val tiles = mutableListOf<String>()
        for (r in 0 until rows) {
            val latMin = latMinBase + r * TILE_METERS / latMetersPerDeg
            val latMax = minOf(
                latMinBase + (r + 1) * TILE_METERS / latMetersPerDeg,
                latitude + radius / latMetersPerDeg
            )
            for (c in 0 until cols) {
                val lonMin = lonMinBase + c * TILE_METERS / lonMetersPerDeg
                val lonMax = minOf(
                    lonMinBase + (c + 1) * TILE_METERS / lonMetersPerDeg,
                    longitude + radius / lonMetersPerDeg
                )
                tiles.add(
                    String.format(
                        "%.6f,%.6f,%.6f,%.6f",
                        latMin.coerceIn(-90.0, 90.0),
                        lonMin.coerceIn(-180.0, 180.0),
                        latMax.coerceIn(-90.0, 90.0),
                        lonMax.coerceIn(-180.0, 180.0)
                    )
                )
            }
        }
        return tiles
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
