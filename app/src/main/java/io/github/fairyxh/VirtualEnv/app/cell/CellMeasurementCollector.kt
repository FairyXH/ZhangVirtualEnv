package io.github.fairyxh.VirtualEnv.app.cell

import android.content.Context
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 真实基站测量采集 → OpenCellID measurement 转换。
 *
 * 输入为环境采集包（EnvironmentCollector 输出，采集期间虚拟环境已挂起），
 * 其中 location / cell 均为**真实设备观测**；虚拟基站/虚拟坐标严禁进入此转换。
 */
object CellMeasurementCollector {

    private const val TAG_SCOPE = "OpenCellId"
    private const val DEDUPE_WINDOW_MS = 60_000L
    private const val DEDUPE_DISTANCE_M = 50.0

    // 最近上传的测量指纹（mcc|mnc|cellid），用于短时间去重
    private val recentFingerprints = ConcurrentHashMap<String, Long>()

    /**
     * 从采集包提取真实测量。
     *
     * @param collect 采集包 JSON（含 location 扁平字段与 cell.cells[]）
     * @return OpenCellID measurement 列表（measure/uploadJson 数组元素）
     */
    fun extractMeasurements(collect: JSONObject): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val location = collect.optJSONObject("location") ?: return out
        val lat = location.optDouble("latitude", Double.NaN)
        val lon = location.optDouble("longitude", Double.NaN)
        if (!lat.isFinite() || !lon.isFinite() || lat == 0.0 || lon == 0.0) {
            ZLog.w(TAG_SCOPE, "skip contribute: no real location")
            return out
        }

        val cell = collect.optJSONObject("cell") ?: return out
        val cells = cell.optJSONArray("cells") ?: JSONArray()
        val now = System.currentTimeMillis()
        for (i in 0 until cells.length()) {
            val c = cells.optJSONObject(i) ?: continue
            val mcc = c.optInt("mcc", -1)
            val mnc = c.optInt("mnc", -1)
            val lac = c.optInt("lac", c.optInt("tac", -1))
            val cellId = if (c.has("ci")) c.optLong("ci", -1L) else c.optLong("cid", -1L)
            if (mcc < 0 || mnc < 0 || cellId < 0) {
                // 无合法 ID 的小区无法被 OpenCellID 使用
                continue
            }
            val fingerprint = "$mcc|$mnc|$cellId"
            val last = recentFingerprints[fingerprint]
            if (last != null && now - last < DEDUPE_WINDOW_MS) {
                continue
            }
            recentFingerprints[fingerprint] = now
            out.add(
                JSONObject().apply {
                    put("lon", lon)
                    put("lat", lat)
                    put("mcc", mcc)
                    put("mnc", mnc)
                    put("lac", lac)
                    put("cellid", cellId)
                    put("measured_at", now)
                    put("act", c.optString("type", "LTE"))
                }
            )
        }
        ZLog.i(TAG_SCOPE, "contribute extracted ${out.size} measurements from collect")
        return out
    }

    /** 清空短时间去重表（测试用）。 */
    fun resetDedupe() {
        recentFingerprints.clear()
    }
}
