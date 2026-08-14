package io.github.fairyxh.VirtualEnv.app.cell

import android.content.Context
import android.net.Uri
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor

/**
 * OpenCellID CSV 离线数据库（用户自行导入官方下载的 CSV）。
 *
 * 数据格式（OpenCellID 官方 download 无表头）：
 *   radio,mcc,net(area→mnc),area(lac/tac),cell(cellid),unit(psc/rnc),lon,lat,range,samples,changeable,created,updated,averageSignal
 *
 * 存储设计（避免把 19 万行数据全部加载进内存）：
 * - `<id>/data.csv`：原始 CSV 副本；
 * - `<id>/offsets.bin`：每行在文件中的字节偏移（Long 数组）；
 * - `<id>/index.json`：网格（0.01°）→ 行号数组；
 * - 查询时按覆盖网格取行号 → RandomAccessFile 随机读行 → 解析 CellInfo → Haversine 过滤。
 *
 * 支持导入多个 CSV 文件；跨库合并去重。
 */
class CsvCellDatabase(private val context: Context) {

    companion object {
        private const val TAG_SCOPE = "OpenCellId"
        private const val DIR_NAME = "opencellid_csv"
        private const val GRID_DEG = 0.01
        private const val META_PREFS = "opencellid_csv_meta"
        private const val MAX_CSV_BYTES = 200L * 1024 * 1024 // 200MB 安全上限
        private const val MAX_ROWS = 2_000_000

        /** 单次离线查询返回上限（与 CellRepository.MAX_RESULTS 一致）。 */
        private const val MAX_QUERY_RESULTS = 300
    }

    data class DbMeta(
        val id: String,
        val displayName: String,
        val rowCount: Int,
        val importedAt: Long
    )

    private class DbIndex(
        val meta: DbMeta,
        val offsets: LongArray,
        val grids: Map<String, IntArray>
    )

    private val loaded = ConcurrentHashMap<String, DbIndex>()

    private fun dbDir(): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    private fun metaPrefs() = context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)

    // ---------- 管理 ----------

    fun listDatabases(): List<DbMeta> {
        val prefs = metaPrefs()
        val out = mutableListOf<DbMeta>()
        dbDir().listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val id = dir.name
            val name = prefs.getString("name_$id", id) ?: id
            val count = prefs.getInt("rows_$id", 0)
            val importedAt = prefs.getLong("time_$id", 0L)
            if (count > 0 || File(dir, "data.csv").exists()) {
                out.add(DbMeta(id, name, count, importedAt))
            }
        }
        return out.sortedByDescending { it.importedAt }
    }

    fun totalRows(): Int = listDatabases().sumOf { it.rowCount }

    /**
     * 导入 CSV（SAF Uri）。流式解析，构建行偏移 + 网格索引。
     * 回调 onProgress(parsedRows, totalBytes) 用于 UI 进度。
     */
    fun importDatabase(
        uri: Uri,
        displayName: String,
        onProgress: (parsed: Int, bytes: Long) -> Unit = { _, _ -> }
    ): Result<DbMeta> {
        return try {
            val id = UUID.randomUUID().toString().replace("-", "").take(12)
            val dir = File(dbDir(), id).apply { mkdirs() }
            val csvFile = File(dir, "data.csv")
            val input = context.contentResolver.openInputStream(uri)
                ?: return Result.failure(IllegalStateException("无法读取所选文件"))
            var parsed = 0
            val offsets = ArrayList<Long>(1 shl 16)
            val grids = HashMap<String, ArrayList<Int>>()
            input.use { ins ->
                val reader = BufferedReader(ins.reader(Charsets.UTF_8), 1 shl 16)
                var offset = 0L
                var line: String?
                while (true) {
                    line = reader.readLine() ?: break
                    if (parsed >= MAX_ROWS) break
                    val cell = parseCsvLine(line)
                    if (cell != null) {
                        offsets.add(offset)
                        val key = gridKey(cell.latitude, cell.longitude)
                        grids.getOrPut(key) { ArrayList() }.add(offsets.size - 1)
                        parsed++
                        if (parsed % 5000 == 0) onProgress(parsed, offset)
                    }
                    offset += line.length + 1L // \n
                    if (offset > MAX_CSV_BYTES) {
                        // 超限：保留已解析部分
                        break
                    }
                }
            }
            if (parsed == 0) {
                dir.deleteRecursively()
                return Result.failure(IllegalStateException("CSV 中没有有效基站数据（请确认是 OpenCellID 官方下载格式）"))
            }
            // 写 offsets.bin
            DataOutputStream(File(dir, "offsets.bin").outputStream().buffered()).use { out ->
                offsets.forEach { out.writeLong(it) }
            }
            // 写 index.json
            val idxObj = JSONObject()
            val gridObj = JSONObject()
            grids.forEach { (key, rows) ->
                gridObj.put(key, JSONArray(rows))
            }
            idxObj.put("grids", gridObj)
            File(dir, "index.json").writeText(idxObj.toString())
            // 元数据
            metaPrefs().edit()
                .putString("name_$id", displayName.ifBlank { "OpenCellID CSV" })
                .putInt("rows_$id", parsed)
                .putLong("time_$id", System.currentTimeMillis())
                .apply()
            // 复制 CSV 到私有目录（保存原始数据，供随机读取）
            // 说明：importDatabase 前半段已流式读取一遍用于建索引；
            // 为节省 IO，这里直接用 input 流再复制一遍不可行，改为从临时读取。
            // 上面的流已经消费完，这里通过 Uri 二次打开复制。
            try {
                context.contentResolver.openInputStream(uri)?.use { src ->
                    csvFile.outputStream().buffered().use { dst -> src.copyTo(dst) }
                }
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "csv copy failed: ${t.message}")
            }
            val meta = DbMeta(id, displayName.ifBlank { "OpenCellID CSV" }, parsed, System.currentTimeMillis())
            ZLog.i(TAG_SCOPE, "csv import done id=$id rows=$parsed grids=${grids.size}")
            Result.success(meta)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "csv import failed", t)
            Result.failure(t)
        }
    }

    fun deleteDatabase(id: String) {
        loaded.remove(id)
        File(dbDir(), id).deleteRecursively()
        metaPrefs().edit()
            .remove("name_$id")
            .remove("rows_$id")
            .remove("time_$id")
            .apply()
    }

    fun clearAll() {
        loaded.clear()
        dbDir().deleteRecursively()
        metaPrefs().edit().clear().apply()
    }

    // ---------- 查询 ----------

    /** 查询附近基站（合并所有已导入数据库，Haversine 过滤，跨库去重，按距离排序取前 [MAX_QUERY_RESULTS]）。 */
    fun queryNearby(latitude: Double, longitude: Double, radiusMeters: Int): List<CellInfo> {
        val radius = radiusMeters.coerceAtLeast(100)
        val latDelta = radius / 111_320.0
        val lonDelta = radius / (111_320.0 * cos(Math.toRadians(latitude)).coerceAtLeast(0.01))
        val gxMin = floor((latitude - latDelta) / GRID_DEG).toInt()
        val gxMax = floor((latitude + latDelta) / GRID_DEG).toInt()
        val gyMin = floor((longitude - lonDelta) / GRID_DEG).toInt()
        val gyMax = floor((longitude + lonDelta) / GRID_DEG).toInt()

        val keys = HashSet<String>()
        for (gx in gxMin..gxMax) {
            for (gy in gyMin..gyMax) {
                keys.add("${gx}_$gy")
            }
        }

        val out = ArrayList<CellInfo>()
        val seen = HashSet<String>()
        listDatabases().forEach { meta ->
            val index = loadIndex(meta) ?: return@forEach
            val csvFile = File(File(dbDir(), meta.id), "data.csv")
            if (!csvFile.exists()) return@forEach
            try {
                RandomAccessFile(csvFile, "r").use { raf ->
                    keys.forEach { key ->
                        val rows = index.grids[key] ?: return@forEach
                        rows.forEach { row ->
                            if (row >= 0 && row < index.offsets.size) {
                                raf.seek(index.offsets[row])
                                val line = raf.readLine() ?: return@forEach
                                val cell = parseCsvLine(line) ?: return@forEach
                                if (cell.distanceMeters(latitude, longitude) <= radius) {
                                    val fp = cellFingerprint(cell)
                                    if (seen.add(fp)) {
                                        out.add(cell)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "csv query db=${meta.id} failed: ${t.message}")
            }
        }
        // 按距离排序，截断到上限，避免大城区网格返回过多导致 UI 卡顿
        return out.sortedBy { it.distanceMeters(latitude, longitude) }
            .take(MAX_QUERY_RESULTS)
    }

    // ---------- 内部 ----------

    private fun loadIndex(meta: DbMeta): DbIndex? {
        loaded[meta.id]?.let { return it }
        synchronized(this) {
            loaded[meta.id]?.let { return it }
            return try {
                val dir = File(dbDir(), meta.id)
                val offsetsFile = File(dir, "offsets.bin")
                if (!offsetsFile.exists()) return null
                val offsets = ArrayList<Long>(meta.rowCount)
                DataInputStream(offsetsFile.inputStream().buffered()).use { ins ->
                    while (ins.available() >= 8) {
                        offsets.add(ins.readLong())
                    }
                }
                val idxObj = JSONObject(File(dir, "index.json").readText())
                val gridObj = idxObj.optJSONObject("grids") ?: JSONObject()
                val grids = HashMap<String, IntArray>()
                gridObj.keys().forEach { key ->
                    val arr = gridObj.optJSONArray(key) ?: return@forEach
                    val rows = IntArray(arr.length())
                    for (i in 0 until arr.length()) rows[i] = arr.optInt(i, -1)
                    grids[key] = rows
                }
                val index = DbIndex(meta, offsets.toLongArray(), grids)
                loaded[meta.id] = index
                index
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "csv index load db=${meta.id} failed: ${t.message}")
                null
            }
        }
    }

    private fun gridKey(lat: Double, lon: Double): String {
        val gx = floor(lat / GRID_DEG).toInt()
        val gy = floor(lon / GRID_DEG).toInt()
        return "${gx}_$gy"
    }

    private fun cellFingerprint(cell: CellInfo): String {
        return buildString {
            append(cell.radio ?: "")
            append('|').append(cell.mcc ?: -1)
            append('|').append(cell.mnc ?: -1)
            append('|').append(cell.cellId ?: cell.cid ?: -1L)
        }
    }

    /** 解析 OpenCellID 官方 CSV 行（14 字段，无表头）。 */
    fun parseCsvLine(line: String): CellInfo? {
        if (line.isBlank()) return null
        val parts = line.split(',')
        if (parts.size < 11) return null
        val radio = parts[0].trim().uppercase()
        val mcc = parts[1].trim().toIntOrNull() ?: return null
        val mnc = parts[2].trim().toIntOrNull() ?: return null
        val area = parts[3].trim().toIntOrNull() ?: -1
        val cell = parts[4].trim().toLongOrNull() ?: return null
        val unit = parts[5].trim().toIntOrNull() ?: -1
        val lon = parts[6].trim().toDoubleOrNull() ?: return null
        val lat = parts[7].trim().toDoubleOrNull() ?: return null
        if (abs(lat) > 90.0 || abs(lon) > 180.0) return null
        // MCC/MNC 限 0..999，防 CSV 脏数据（哨兵/溢出值）进入缓存与模拟
        if (mcc !in 0..999 || mnc !in 0..999 || cell < 0) return null
        val range = parts[8].trim().toIntOrNull()?.takeIf { it > 0 }
        val samples = parts[9].trim().toIntOrNull()?.takeIf { it >= 0 }
        val changeable = parts.getOrNull(10)?.trim() == "1"
        val averageSignal = parts.getOrNull(13)?.trim()?.toIntOrNull()?.takeIf { it != 0 }
        val isLteOrNr = radio == "LTE" || radio == "NR"
        return CellInfo(
            radio = radio,
            mcc = mcc,
            mnc = mnc,
            lac = if (isLteOrNr) null else area.takeIf { it >= 0 },
            tac = if (isLteOrNr) area.takeIf { it >= 0 } else null,
            cellId = cell,
            latitude = lat,
            longitude = lon,
            averageSignalStrength = averageSignal,
            rangeMeters = range,
            samples = samples,
            changeable = changeable,
            rnc = if (radio == "UMTS") unit.takeIf { it >= 0 } else null,
            cid = null,
            pci = if (isLteOrNr || radio == "UMTS") unit.takeIf { it >= 0 } else null
        )
    }
}
