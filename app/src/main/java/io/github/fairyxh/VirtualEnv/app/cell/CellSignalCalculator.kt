package io.github.fairyxh.VirtualEnv.app.cell

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * 从 OpenCellID 查询结果生成虚拟基站配置条目的信号强度算法。
 *
 * 输入：OpenCellID Cell（含坐标 / range / averageSignalStrength / samples）
 * 输出：VirtualCellFactory 兼容的 cell entry（type/mcc/mnc/tac|ci/nci/pci/rsrp/rssi...）。
 *
 * 算法设计（确定性为主、轻度随机）：
 * 1. 计算查询点到小区的大圆距离 [dist]；
 * 2. 覆盖半径 [range]（缺失时按制式取典型值）；
 * 3. 距离衰减：penalty = 25 * distRatio^1.2 dB（distRatio = clamp(dist / range)），
 *    基站正下方几乎无衰减、覆盖边缘衰减约 25dB（城市环境典型路径损耗），
 *    同制式下远近基站信号拉开明显差距；
 * 4. 基准信号：优先用 OpenCellID averageSignalStrength，否则按制式默认值；
 * 5. 样本置信度抖动：samples 越少抖动越大（样本 <10 时 ±2.5dB，<50 时 ±1.5dB，否则 ±0.8dB），
 *    模拟真实测量的波动；
 * 6. 按制式映射到 Android 合法字段范围并消毒（rsrp -156..-31、rssi -113..-51、rscp -120..-25、ecno -24..0）。
 */
object CellSignalCalculator {

    private const val MAX_DIST_RATIO = 1.0

    private val defaultRangeMeters = mapOf(
        "LTE" to 1500,
        "NR" to 800,
        "GSM" to 500,
        "UMTS" to 1000,
        "NBIOT" to 3000,
        "CDMA" to 3000
    )

    private val defaultSignalDbm = mapOf(
        "LTE" to -95,
        "NR" to -100,
        "GSM" to -85,
        "UMTS" to -92,
        "NBIOT" to -100,
        "CDMA" to -90
    )

    /**
     * 生成单个 cell entry。
     *
     * @param cell OpenCellID 查询结果
     * @param queryLat / queryLon 查询点（当前选点，WGS-84）
     * @param random 抖动随机源；传固定种子可复现
     */
    fun computeEntry(cell: CellInfo, queryLat: Double, queryLon: Double, random: Random = Random.Default): JSONObject {
        val radio = (cell.radio ?: "LTE").uppercase()
        val type = RadioType.fromString(radio)?.entryType() ?: "LTE"
        if (type == "CDMA") {
            // 模块 CDMA 走 Hook 层自动 fallback（带虚拟坐标），无需显式条目
            return JSONObject()
        }

        val dist = cell.distanceMeters(queryLat, queryLon)
        val range = max(cell.rangeMeters ?: (defaultRangeMeters[radio] ?: 1000), 50)
        val distRatio = min(dist / range, MAX_DIST_RATIO)
        val penalty = 25.0 * distRatio.pow(1.2)

        val base = cell.averageSignalStrength
            ?: (defaultSignalDbm[radio] ?: -95)
        val samples = cell.samples ?: 0
        val jitter = when {
            samples < 10 -> 2.5
            samples < 50 -> 1.5
            else -> 0.8
        } * (random.nextDouble() * 2.0 - 1.0)

        val rawSignal = base - penalty + jitter

        val mcc = (cell.mcc ?: 460).coerceIn(0, 999)
        val mnc = (cell.mnc ?: 0).coerceIn(0, 999)
        val tac = (cell.tac ?: cell.lac ?: 0).coerceIn(0, 16_777_215)
        val lac = (cell.lac ?: cell.tac ?: 0).coerceIn(0, 65_535)
        val ci = (cell.cellId ?: cell.cid ?: 0L).coerceIn(0L, 268_435_455L)
        val pci = (cell.pci ?: 0).coerceIn(0, 1007)

        return JSONObject().apply {
            put("type", type)
            put("mcc", mcc)
            put("mnc", mnc)
            when (type) {
                "LTE" -> {
                    put("tac", tac)
                    put("ci", ci)
                    put("pci", pci.coerceAtMost(503))
                    put("rsrp", clampSignal(rawSignal, -140, -80).toInt())
                }
                "NR" -> {
                    put("tac", tac)
                    put("nci", ci.coerceIn(1L, 68_719_476_735L))
                    put("pci", pci)
                    put("rsrp", clampSignal(rawSignal, -140, -80).toInt())
                }
                "GSM" -> {
                    put("lac", lac)
                    put("cid", ci.coerceAtMost(65_535))
                    put("rssi", clampSignal(rawSignal, -110, -60).toInt())
                }
                "WCDMA" -> {
                    put("lac", lac)
                    put("cid", ci)
                    put("rssi", clampSignal(rawSignal, -110, -60).toInt())
                    // RSCP 通常比 RSSI 低 2~4dB；ECNO 取 -24..0
                    put("rscp", clampSignal(rawSignal - 3, -115, -30).toInt())
                    put("ecno", clampSignal(rawSignal / 8.0, -24, 0).toInt())
                }
            }
            // 供 UI 回溯：来源与小区坐标（Hook 层不消费）
            put("_opencellid", true)
            put("_cellLat", cell.latitude)
            put("_cellLon", cell.longitude)
            put("_distanceMeters", dist.toInt())
            put("_rangeMeters", range)
            put("_samples", samples)
        }
    }

    private fun clampSignal(value: Double, minVal: Int, maxVal: Int): Double {
        return min(max(value, minVal.toDouble()), maxVal.toDouble())
    }

    /**
     * 摘要：距离 / 覆盖 / 样本 + **算法估算信号**（与保存到基站模拟的数值一致）。
     * 使用稳定随机源，UI 重组时数值不闪烁。
     */
    fun describe(cell: CellInfo, queryLat: Double, queryLon: Double): String {
        val dist = cell.distanceMeters(queryLat, queryLon).toInt()
        val range = cell.rangeMeters ?: (defaultRangeMeters[(cell.radio ?: "LTE").uppercase()] ?: 1000)
        val samples = cell.samples ?: 0
        val entry = computeEntry(cell, queryLat, queryLon, stableRandom(queryLat, queryLon))
        val signal = when (entry.optString("type", "")) {
            "LTE", "NR" -> entry.optInt("rsrp", Int.MIN_VALUE)
            "GSM", "WCDMA" -> entry.optInt("rssi", Int.MIN_VALUE)
            else -> Int.MIN_VALUE
        }
        val signalText = if (signal == Int.MIN_VALUE) "—" else "$signal"
        return "距离 ${dist}m · 覆盖 ${range}m · 样本 $samples · 估算信号 ${signalText}dBm"
    }

    /**
     * 由 OpenCellID 结果构造完整 cell 配置数据（`/api/cell/set` 的 data）。
     *
     * 默认使用稳定随机源：同一查询点每次保存信号一致，可复现。
     * @return 空 JSONObject（无有效条目）时表示没有可保存的基站。
     */
    fun buildCellConfig(cells: List<CellInfo>, queryLat: Double, queryLon: Double, random: Random = stableRandom(queryLat, queryLon)): JSONObject {
        val entries = org.json.JSONArray()
        cells.forEach { cell ->
            val entry = computeEntry(cell, queryLat, queryLon, random)
            if (entry.length() > 0) {
                entries.put(entry)
            }
        }
        return JSONObject().apply { put("entries", entries) }
    }

    /** 去重：同 radio+mcc+mnc+id 只保留距离最近的。 */
    fun dedupe(cells: List<CellInfo>, queryLat: Double, queryLon: Double): List<CellInfo> {
        val byKey = LinkedHashMap<String, CellInfo>()
        cells.forEach { cell ->
            val key = buildString {
                append(cell.radio ?: "")
                append('|').append(cell.mcc ?: -1)
                append('|').append(cell.mnc ?: -1)
                append('|').append(cell.cellId ?: cell.cid ?: -1L)
            }
            val prev = byKey[key]
            if (prev == null || cell.distanceMeters(queryLat, queryLon) < prev.distanceMeters(queryLat, queryLon)) {
                byKey[key] = cell
            }
        }
        return byKey.values.toList()
    }

    /** 稳定随机源：同一查询点每次结果尽量一致（仅 samples 差异引起轻微抖动）。 */
    fun stableRandom(queryLat: Double, queryLon: Double): Random {
        val seed = abs((queryLat * 1_000_000).toLong() * 31 + (queryLon * 1_000_000).toLong())
        return Random(seed)
    }
}
