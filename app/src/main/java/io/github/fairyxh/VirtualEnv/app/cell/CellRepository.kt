package io.github.fairyxh.VirtualEnv.app.cell

import android.content.Context
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject

/**
 * 基站数据库统一入口（控制端）。
 *
 * 业务层只依赖本类：
 * - 查询附近基站：OpenCellID API + 空间网格缓存 + radius 二次过滤；
 * - 数据贡献：真实采集包 → 本地队列 → 批量上传。
 */
class CellRepository(private val context: Context) {

    companion object {
        private const val TAG_SCOPE = "OpenCellId"
        private const val DEFAULT_RADIUS_M = 1500
    }

    private val cache = OpenCellIdCache(context)
    private val uploader = OpenCellIdUploader(context)

    /** 查询附近基站（带缓存）。 */
    fun queryNearbyCells(
        latitude: Double,
        longitude: Double,
        radiusMeters: Int = DEFAULT_RADIUS_M,
        radio: String? = null,
        mcc: Int? = null,
        mnc: Int? = null,
        lac: Int? = null,
        useCache: Boolean = true
    ): Result<List<CellInfo>> {
        val apiKey = OpenCellIdSettings.getApiKey(context)
        if (apiKey.isNullOrBlank()) {
            return Result.failure(OpenCellIdApi.ApiFailure("请先在设置中填写 OpenCellID API Key"))
        }
        if (useCache) {
            cache.get(apiKey, latitude, longitude, radio, mcc, mnc, lac)?.let { cached ->
                ZLog.i(TAG_SCOPE, "cache hit ${cached.size} cells")
                return Result.success(cached)
            }
        }
        val result = OpenCellIdApi.getNearbyCells(
            apiKey, latitude, longitude, radiusMeters, radio, mcc, mnc, lac
        )
        if (result.isSuccess) {
            val cells = CellSignalCalculator.dedupe(result.getOrThrow(), latitude, longitude)
            cache.put(apiKey, latitude, longitude, radio, mcc, mnc, lac, cells)
            return Result.success(cells)
        }
        return result
    }

    /** 测试 API Key。 */
    fun testApiKey(): Result<String> {
        val apiKey = OpenCellIdSettings.getApiKey(context)
        if (apiKey.isNullOrBlank()) {
            return Result.failure(OpenCellIdApi.ApiFailure("请先填写 OpenCellID API Key"))
        }
        return OpenCellIdApi.testKey(apiKey)
    }

    /** 清空查询缓存。 */
    fun clearCache() = cache.clear()

    fun cacheSize(): Int = cache.size()

    /** 贡献真实采集包：提取测量 → 入队 → 尝试上传。 */
    fun contribute(collect: JSONObject): ContributeResult {
        if (!OpenCellIdSettings.isContributeEnabled(context)) {
            return ContributeResult(false, 0, "未开启数据贡献")
        }
        val apiKey = OpenCellIdSettings.getApiKey(context)
        if (apiKey.isNullOrBlank()) {
            return ContributeResult(false, 0, "未配置 OpenCellID API Key")
        }
        val measurements = CellMeasurementCollector.extractMeasurements(collect)
        if (measurements.isEmpty()) {
            return ContributeResult(false, 0, "本次采集没有可贡献的基站测量")
        }
        val total = uploader.enqueue(measurements)
        val upload = uploader.uploadPending()
        return ContributeResult(upload.success, total, upload.message)
    }

    fun pendingCount(): Int = uploader.pendingCount()

    /** 上传待发送队列（设置页/主页手动触发）。 */
    fun uploadPending(): OpenCellIdUploader.UploadResult = uploader.uploadPending()

    data class ContributeResult(val success: Boolean, val queued: Int, val message: String)
}
