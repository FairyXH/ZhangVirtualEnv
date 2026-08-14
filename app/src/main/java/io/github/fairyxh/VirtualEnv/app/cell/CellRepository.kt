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

        /** 单次查询返回给 UI 的最大条数（性能保护，超出截断并提示翻页/缩小半径）。 */
        private const val MAX_RESULTS = 300
    }

    private val cache = OpenCellIdCache(context)
    private val uploader = OpenCellIdUploader(context)

    /** CSV 离线数据库（支持多文件导入）。 */
    val csvDb = CsvCellDatabase(context)

    /** 一次基站查询的完整结果（含来源与在线请求状态，供 UI 明确展示）。 */
    data class NearbyQuery(
        /** 当前查询模式。 */
        val mode: OpenCellIdSettings.QueryMode,
        /** 数据来源：offline / hybrid-offline / online-cache / online / hybrid-online。 */
        val source: String,
        /** 命中小区（已按距离排序，最多 [MAX_RESULTS] 条）。 */
        val cells: List<CellInfo>,
        /** 是否尝试过在线请求。 */
        val onlineAttempted: Boolean,
        /** 在线请求失败原因（null = 未失败；与 [onlineEmpty] 互斥）。 */
        val onlineError: String?,
        /** 在线请求成功但该区域无结果。 */
        val onlineEmpty: Boolean,
        /** 结果因超过 [MAX_RESULTS] 被截断。 */
        val truncated: Boolean
    ) {
        val total: Int get() = cells.size
    }

    /**
     * 查询附近基站（按设置页查询模式路由）。
     *
     * - OFFLINE：仅查询本地 CSV 数据库（无需 API Key）；
     * - ONLINE：仅查询 OpenCellID API；
     * - HYBRID：先查离线，命中返回；无结果再转在线。
     *
     * 在线请求失败与"真的无结果"通过 [NearbyQuery.onlineError] / [NearbyQuery.onlineEmpty] 区分。
     */
    fun queryNearbyCells(
        latitude: Double,
        longitude: Double,
        radiusMeters: Int = DEFAULT_RADIUS_M,
        radio: String? = null,
        mcc: Int? = null,
        mnc: Int? = null,
        lac: Int? = null,
        useCache: Boolean = true
    ): NearbyQuery {
        val mode = OpenCellIdSettings.getQueryMode(context)
        val offlineCells = if (mode != OpenCellIdSettings.QueryMode.ONLINE) {
            val cells = csvDb.queryNearby(latitude, longitude, radiusMeters)
            filterCells(cells, radio, mcc, mnc, lac)
        } else {
            emptyList()
        }
        val dedupedOffline = CellSignalCalculator.dedupe(offlineCells, latitude, longitude)
            .sortedBy { it.distanceMeters(latitude, longitude) }

        if (mode == OpenCellIdSettings.QueryMode.OFFLINE) {
            return NearbyQuery(
                mode = mode,
                source = "offline",
                cells = dedupedOffline.take(MAX_RESULTS),
                onlineAttempted = false,
                onlineError = null,
                onlineEmpty = false,
                truncated = dedupedOffline.size > MAX_RESULTS
            )
        }
        if (mode == OpenCellIdSettings.QueryMode.HYBRID && dedupedOffline.isNotEmpty()) {
            ZLog.i(TAG_SCOPE, "hybrid offline hit ${dedupedOffline.size} cells")
            return NearbyQuery(
                mode = mode,
                source = "hybrid-offline",
                cells = dedupedOffline.take(MAX_RESULTS),
                onlineAttempted = false,
                onlineError = null,
                onlineEmpty = false,
                truncated = dedupedOffline.size > MAX_RESULTS
            )
        }
        // ONLINE 或 HYBRID 离线未命中 → API
        val apiKey = OpenCellIdSettings.getApiKey(context)
        if (apiKey.isNullOrBlank()) {
            return NearbyQuery(
                mode = mode,
                source = "online",
                cells = emptyList(),
                onlineAttempted = true,
                onlineError = if (dedupedOffline.isEmpty()) {
                    "请先在设置中填写 OpenCellID API Key 或导入 CSV 数据库"
                } else {
                    "离线数据库无结果，且未配置 OpenCellID API Key"
                },
                onlineEmpty = false,
                truncated = false
            )
        }
        if (useCache) {
            cache.get(apiKey, latitude, longitude, radio, mcc, mnc, lac)?.let { cached ->
                val deduped = CellSignalCalculator.dedupe(cached, latitude, longitude)
                    .sortedBy { it.distanceMeters(latitude, longitude) }
                ZLog.i(TAG_SCOPE, "cache hit ${deduped.size} cells")
                return NearbyQuery(
                    mode = mode,
                    source = "online-cache",
                    cells = deduped.take(MAX_RESULTS),
                    onlineAttempted = true,
                    onlineError = null,
                    onlineEmpty = deduped.isEmpty(),
                    truncated = deduped.size > MAX_RESULTS
                )
            }
        }
        val result = OpenCellIdApi.getNearbyCells(
            apiKey, latitude, longitude, radiusMeters, radio, mcc, mnc, lac
        )
        if (result.isSuccess) {
            val cells = CellSignalCalculator.dedupe(result.getOrThrow(), latitude, longitude)
                .sortedBy { it.distanceMeters(latitude, longitude) }
            cache.put(apiKey, latitude, longitude, radio, mcc, mnc, lac, cells)
            return NearbyQuery(
                mode = mode,
                source = if (mode == OpenCellIdSettings.QueryMode.HYBRID) "hybrid-online" else "online",
                cells = cells.take(MAX_RESULTS),
                onlineAttempted = true,
                onlineError = null,
                onlineEmpty = cells.isEmpty(),
                truncated = cells.size > MAX_RESULTS
            )
        }
        return NearbyQuery(
            mode = mode,
            source = if (mode == OpenCellIdSettings.QueryMode.HYBRID) "hybrid-online" else "online",
            cells = emptyList(),
            onlineAttempted = true,
            onlineError = result.exceptionOrNull()?.message ?: "在线查询失败",
            onlineEmpty = false,
            truncated = false
        )
    }

    private fun filterCells(
        cells: List<CellInfo>,
        radio: String?,
        mcc: Int?,
        mnc: Int?,
        lac: Int?
    ): List<CellInfo> {
        return cells.filter { cell ->
            (radio == null || radio.isBlank() || cell.radio.equals(radio, ignoreCase = true)) &&
                (mcc == null || cell.mcc == mcc) &&
                (mnc == null || cell.mnc == mnc) &&
                (lac == null || cell.lac == lac || cell.tac == lac)
        }
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
