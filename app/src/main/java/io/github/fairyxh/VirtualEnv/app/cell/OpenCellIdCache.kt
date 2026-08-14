package io.github.fairyxh.VirtualEnv.app.cell

import android.content.Context
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * OpenCellID 查询结果空间网格缓存。
 *
 * - 按 0.01°（约 1.1km）网格切分，同一网格内查询直接命中缓存，避免高频 API 请求。
 * - 缓存按 API Key 隔离（不同 Key 的配额/权限不同）。
 * - 默认 24 小时过期；支持清空。
 * - 持久化到 App 私有 filesDir（opencellid_cache.json），重启不丢。
 */
class OpenCellIdCache(private val context: Context) {

    companion object {
        private const val TAG_SCOPE = "OpenCellId"
        private const val GRID_DEG = 0.01
        private const val DEFAULT_TTL_MS = 24 * 60 * 60 * 1000L
        private const val FILE_NAME = "opencellid_cache.json"
        private const val MAX_ENTRIES = 512
    }

    private data class GridKey(val apiKey: String, val gridX: Int, val gridY: Int, val radio: String, val mcc: Int, val mnc: Int, val lac: Int)

    private val memory = ConcurrentHashMap<String, CacheEntry>()

    private data class CacheEntry(val createdAt: Long, val cellsJson: String)

    init {
        loadFromDisk()
    }

    fun get(
        apiKey: String,
        latitude: Double,
        longitude: Double,
        radio: String?,
        mcc: Int?,
        mnc: Int?,
        lac: Int?
    ): List<CellInfo>? {
        val key = keyFor(apiKey, latitude, longitude, radio, mcc, mnc, lac) ?: return null
        val entry = memory[key] ?: return null
        if (System.currentTimeMillis() - entry.createdAt > DEFAULT_TTL_MS) {
            memory.remove(key)
            return null
        }
        return try {
            val arr = JSONArray(entry.cellsJson)
            val cells = mutableListOf<CellInfo>()
            for (i in 0 until arr.length()) {
                cells.add(CellInfo.fromJson(arr.optJSONObject(i) ?: continue))
            }
            cells
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "cache parse failed: ${t.message}")
            null
        }
    }

    fun put(
        apiKey: String,
        latitude: Double,
        longitude: Double,
        radio: String?,
        mcc: Int?,
        mnc: Int?,
        lac: Int?,
        cells: List<CellInfo>
    ) {
        val key = keyFor(apiKey, latitude, longitude, radio, mcc, mnc, lac) ?: return
        val json = CellInfo.toJsonArray(cells).toString()
        memory[key] = CacheEntry(System.currentTimeMillis(), json)
        trim()
        saveToDisk()
    }

    fun clear() {
        memory.clear()
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "cache file delete failed: ${t.message}")
        }
    }

    fun size(): Int = memory.size

    private fun keyFor(
        apiKey: String,
        latitude: Double,
        longitude: Double,
        radio: String?,
        mcc: Int?,
        mnc: Int?,
        lac: Int?
    ): String? {
        if (apiKey.isBlank()) return null
        val gridX = Math.floor(latitude / GRID_DEG).toInt()
        val gridY = Math.floor(longitude / GRID_DEG).toInt()
        return "$apiKey|$gridX|$gridY|${radio ?: ""}|${mcc ?: -1}|${mnc ?: -1}|${lac ?: -1}"
    }

    private fun trim() {
        while (memory.size > MAX_ENTRIES) {
            val oldest = memory.entries.minByOrNull { it.value.createdAt } ?: break
            memory.remove(oldest.key)
        }
    }

    private fun saveToDisk() {
        try {
            val root = JSONObject()
            val arr = JSONArray()
            memory.forEach { (k, v) ->
                arr.put(JSONObject().apply {
                    put("key", k)
                    put("createdAt", v.createdAt)
                    put("cells", JSONArray(v.cellsJson))
                })
            }
            root.put("entries", arr)
            File(context.filesDir, FILE_NAME).writeText(root.toString())
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "cache save failed: ${t.message}")
        }
    }

    private fun loadFromDisk() {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("entries") ?: return
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                memory[obj.optString("key", "")] = CacheEntry(
                    obj.optLong("createdAt", 0L),
                    obj.optJSONArray("cells")?.toString() ?: "[]"
                )
            }
            trim()
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "cache load failed: ${t.message}")
        }
    }
}
