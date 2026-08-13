package io.github.fairyxh.VirtualEnv.core

import android.database.sqlite.SQLiteDatabase
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.io.File

/**
 * 数据库管理器（Phase 1）。
 *
 * 不依赖 App Context：Backend 运行在 system_server 进程，数据库文件
 * 直接存放在 [File] 指定路径（默认 /data/system/zve/zve.db）。
 *
 * 前端禁止直接访问数据库，必须通过 Backend API。
 * 后续 Phase 扩展 environment/cell/wifi/ble/sensor/gnss 表。
 */
class DatabaseManager(private val dbFile: File) {

    companion object {
        private const val TAG_SCOPE = "Core"
        private const val DATABASE_VERSION = 5

        const val TABLE_ROUTE = "route"
        const val TABLE_LOCATION_POINT = "location_point"
        const val TABLE_ENV_SNAPSHOT = "env_snapshot"
        const val TABLE_ENV_STATE = "env_state"
        const val TABLE_RECORDING = "recording"
        const val TABLE_RECORDING_FRAME = "recording_frame"

        const val COL_ID = "id"
        const val COL_NAME = "name"
        const val COL_REMARK = "remark"
        const val COL_POINTS = "points"
        const val COL_SPEED = "speed"
        const val COL_STEP_FREQUENCY = "step_frequency"
        const val COL_CREATE_TIME = "create_time"

        const val COL_LATITUDE = "latitude"
        const val COL_LONGITUDE = "longitude"
        const val COL_TYPE = "type"
        const val COL_DATA = "data"
        const val COL_ENABLED = "enabled"
        const val COL_SNAPSHOT_ID = "snapshot_id"
        const val COL_UPDATE_TIME = "update_time"

        const val COL_DURATION_MS = "duration_ms"
        const val COL_FRAME_COUNT = "frame_count"
        const val COL_RECORDING_ID = "recording_id"
        const val COL_SEQ = "seq"
        const val COL_TIMESTAMP_MS = "timestamp_ms"
        const val COL_INTERRUPTED = "interrupted"

        private const val SQL_CREATE_ROUTE =
            "CREATE TABLE IF NOT EXISTS $TABLE_ROUTE (" +
                "$COL_ID INTEGER PRIMARY KEY AUTOINCREMENT," +
                "$COL_NAME TEXT NOT NULL," +
                "$COL_REMARK TEXT DEFAULT ''," +
                "$COL_POINTS TEXT NOT NULL," +
                "$COL_SPEED REAL NOT NULL DEFAULT 3.5," +
                "$COL_STEP_FREQUENCY INTEGER NOT NULL DEFAULT 120," +
                "$COL_CREATE_TIME INTEGER NOT NULL" +
                ")"

        private const val SQL_CREATE_LOCATION_POINT =
            "CREATE TABLE IF NOT EXISTS $TABLE_LOCATION_POINT (" +
                "$COL_ID INTEGER PRIMARY KEY AUTOINCREMENT," +
                "$COL_NAME TEXT NOT NULL," +
                "$COL_REMARK TEXT DEFAULT ''," +
                "$COL_LATITUDE REAL NOT NULL," +
                "$COL_LONGITUDE REAL NOT NULL," +
                "$COL_CREATE_TIME INTEGER NOT NULL" +
                ")"

        private const val SQL_CREATE_ENV_SNAPSHOT =
            "CREATE TABLE IF NOT EXISTS $TABLE_ENV_SNAPSHOT (" +
                "$COL_ID INTEGER PRIMARY KEY AUTOINCREMENT," +
                "$COL_NAME TEXT NOT NULL," +
                "$COL_REMARK TEXT DEFAULT ''," +
                "$COL_TYPE TEXT NOT NULL," +
                "$COL_DATA TEXT NOT NULL," +
                "$COL_CREATE_TIME INTEGER NOT NULL" +
                ")"

        /** 各环境引擎上次使用的配置（wifi/cell/ble/gnss/sensor/sim）：重启后恢复。 */
        private const val SQL_CREATE_ENV_STATE =
            "CREATE TABLE IF NOT EXISTS $TABLE_ENV_STATE (" +
                "$COL_TYPE TEXT PRIMARY KEY," +
                "$COL_ENABLED INTEGER NOT NULL DEFAULT 0," +
                "$COL_DATA TEXT NOT NULL DEFAULT ''," +
                "$COL_SNAPSHOT_ID INTEGER NOT NULL DEFAULT -1," +
                "$COL_UPDATE_TIME INTEGER NOT NULL" +
                ")"

        private const val SQL_CREATE_RECORDING =
            "CREATE TABLE IF NOT EXISTS $TABLE_RECORDING (" +
                "$COL_ID INTEGER PRIMARY KEY AUTOINCREMENT," +
                "$COL_NAME TEXT NOT NULL," +
                "$COL_REMARK TEXT DEFAULT ''," +
                "$COL_DURATION_MS INTEGER NOT NULL DEFAULT 0," +
                "$COL_FRAME_COUNT INTEGER NOT NULL DEFAULT 0," +
                "$COL_INTERRUPTED INTEGER NOT NULL DEFAULT 0," +
                "$COL_CREATE_TIME INTEGER NOT NULL" +
                ")"

        private const val SQL_CREATE_RECORDING_FRAME =
            "CREATE TABLE IF NOT EXISTS $TABLE_RECORDING_FRAME (" +
                "$COL_ID INTEGER PRIMARY KEY AUTOINCREMENT," +
                "$COL_RECORDING_ID INTEGER NOT NULL," +
                "$COL_SEQ INTEGER NOT NULL," +
                "$COL_TIMESTAMP_MS INTEGER NOT NULL," +
                "$COL_DATA TEXT NOT NULL" +
                ")"

        private const val SQL_INDEX_RECORDING_FRAME =
            "CREATE INDEX IF NOT EXISTS idx_recording_frame ON $TABLE_RECORDING_FRAME($COL_RECORDING_ID, $COL_SEQ)"
    }

    private val lock = Any()

    @Volatile
    private var db: SQLiteDatabase? = null

    init {
        dbFile.parentFile?.mkdirs()
    }

    /** 获取（或惰性打开）数据库连接。 */
    fun open(): SQLiteDatabase {
        synchronized(lock) {
            db?.let { return it }
            val opened = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
            opened.execSQL(SQL_CREATE_ROUTE)
            opened.execSQL(SQL_CREATE_LOCATION_POINT)
            opened.execSQL(SQL_CREATE_ENV_SNAPSHOT)
            opened.execSQL(SQL_CREATE_ENV_STATE)
            opened.execSQL(SQL_CREATE_RECORDING)
            opened.execSQL(SQL_CREATE_RECORDING_FRAME)
            opened.execSQL(SQL_INDEX_RECORDING_FRAME)
            migrate(opened)
            db = opened
            ZLog.i(TAG_SCOPE, "DatabaseManager opened ${dbFile.absolutePath}, version=$DATABASE_VERSION")
            return opened
        }
    }

    /** 老库升级：route 表补充 remark 列；recording 表补充 interrupted 列（录像中断兜底标记）。 */
    private fun migrate(database: SQLiteDatabase) {
        try {
            val routeColumns = mutableSetOf<String>()
            database.rawQuery("PRAGMA table_info($TABLE_ROUTE)", null).use { c ->
                val nameIdx = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) routeColumns.add(c.getString(nameIdx))
            }
            if (!routeColumns.contains(COL_REMARK)) {
                database.execSQL("ALTER TABLE $TABLE_ROUTE ADD COLUMN $COL_REMARK TEXT DEFAULT ''")
                ZLog.i(TAG_SCOPE, "migrated: route add remark column")
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "migrate route failed", t)
        }
        try {
            val recordingColumns = mutableSetOf<String>()
            database.rawQuery("PRAGMA table_info($TABLE_RECORDING)", null).use { c ->
                val nameIdx = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) recordingColumns.add(c.getString(nameIdx))
            }
            if (!recordingColumns.contains(COL_INTERRUPTED)) {
                database.execSQL("ALTER TABLE $TABLE_RECORDING ADD COLUMN $COL_INTERRUPTED INTEGER NOT NULL DEFAULT 0")
                ZLog.i(TAG_SCOPE, "migrated: recording add interrupted column")
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "migrate recording failed", t)
        }
    }

    /** 关闭数据库连接（system_server 生命周期结束前调用）。 */
    fun close() {
        synchronized(lock) {
            db?.close()
            db = null
        }
    }

    // ---------- Route CRUD ----------

    /** 插入一条路线，返回新行 id。 */
    fun insertRoute(name: String, remark: String, pointsJson: String, speed: Double, stepFrequency: Int): Long {
        val values = android.content.ContentValues().apply {
            put(COL_NAME, name)
            put(COL_REMARK, remark)
            put(COL_POINTS, pointsJson)
            put(COL_SPEED, speed)
            put(COL_STEP_FREQUENCY, stepFrequency)
            put(COL_CREATE_TIME, System.currentTimeMillis())
        }
        return open().insert(TABLE_ROUTE, null, values)
    }

    /** 查询全部路线（按创建时间倒序）。 */
    fun queryRoutes(): List<org.json.JSONObject> {
        val result = mutableListOf<org.json.JSONObject>()
        val cursor = open().query(
            TABLE_ROUTE,
            null,
            null,
            null,
            null,
            null,
            "$COL_CREATE_TIME DESC"
        )
        cursor?.use {
            val idIdx = it.getColumnIndexOrThrow(COL_ID)
            val nameIdx = it.getColumnIndexOrThrow(COL_NAME)
            val remarkIdx = it.getColumnIndexOrThrow(COL_REMARK)
            val pointsIdx = it.getColumnIndexOrThrow(COL_POINTS)
            val speedIdx = it.getColumnIndexOrThrow(COL_SPEED)
            val stepIdx = it.getColumnIndexOrThrow(COL_STEP_FREQUENCY)
            val createIdx = it.getColumnIndexOrThrow(COL_CREATE_TIME)
            while (it.moveToNext()) {
                val obj = org.json.JSONObject()
                obj.put("id", it.getLong(idIdx))
                obj.put("name", it.getString(nameIdx))
                obj.put("remark", it.getString(remarkIdx))
                obj.put("points", org.json.JSONArray(it.getString(pointsIdx)))
                obj.put("speed", it.getDouble(speedIdx))
                obj.put("stepFrequency", it.getInt(stepIdx))
                obj.put("createTime", it.getLong(createIdx))
                result.add(obj)
            }
        }
        return result
    }

    /** 查询单条路线。 */
    fun getRoute(id: Long): org.json.JSONObject? {
        val cursor = open().query(
            TABLE_ROUTE,
            null,
            "$COL_ID=?",
            arrayOf(id.toString()),
            null,
            null,
            null,
            "1"
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val obj = org.json.JSONObject()
                obj.put("id", it.getLong(it.getColumnIndexOrThrow(COL_ID)))
                obj.put("name", it.getString(it.getColumnIndexOrThrow(COL_NAME)))
                obj.put("remark", it.getString(it.getColumnIndexOrThrow(COL_REMARK)))
                obj.put("points", org.json.JSONArray(it.getString(it.getColumnIndexOrThrow(COL_POINTS))))
                obj.put("speed", it.getDouble(it.getColumnIndexOrThrow(COL_SPEED)))
                obj.put("stepFrequency", it.getInt(it.getColumnIndexOrThrow(COL_STEP_FREQUENCY)))
                obj.put("createTime", it.getLong(it.getColumnIndexOrThrow(COL_CREATE_TIME)))
                return obj
            }
        }
        return null
    }

    /** 删除一条路线。 */
    fun deleteRoute(id: Long): Boolean {
        return open().delete(TABLE_ROUTE, "$COL_ID=?", arrayOf(id.toString())) > 0
    }

    // ---------- LocationPoint CRUD ----------

    /** 插入一个已保存地点。 */
    fun insertLocationPoint(name: String, remark: String, latitude: Double, longitude: Double): Long {
        val values = android.content.ContentValues().apply {
            put(COL_NAME, name)
            put(COL_REMARK, remark)
            put(COL_LATITUDE, latitude)
            put(COL_LONGITUDE, longitude)
            put(COL_CREATE_TIME, System.currentTimeMillis())
        }
        return open().insert(TABLE_LOCATION_POINT, null, values)
    }

    /** 查询全部已保存地点（按创建时间倒序）。 */
    fun queryLocationPoints(): List<org.json.JSONObject> {
        val result = mutableListOf<org.json.JSONObject>()
        val cursor = open().query(
            TABLE_LOCATION_POINT,
            null,
            null,
            null,
            null,
            null,
            "$COL_CREATE_TIME DESC"
        )
        cursor?.use {
            val idIdx = it.getColumnIndexOrThrow(COL_ID)
            val nameIdx = it.getColumnIndexOrThrow(COL_NAME)
            val remarkIdx = it.getColumnIndexOrThrow(COL_REMARK)
            val latIdx = it.getColumnIndexOrThrow(COL_LATITUDE)
            val lonIdx = it.getColumnIndexOrThrow(COL_LONGITUDE)
            val createIdx = it.getColumnIndexOrThrow(COL_CREATE_TIME)
            while (it.moveToNext()) {
                val obj = org.json.JSONObject()
                obj.put("id", it.getLong(idIdx))
                obj.put("name", it.getString(nameIdx))
                obj.put("remark", it.getString(remarkIdx))
                obj.put("latitude", it.getDouble(latIdx))
                obj.put("longitude", it.getDouble(lonIdx))
                obj.put("createTime", it.getLong(createIdx))
                result.add(obj)
            }
        }
        return result
    }

    /** 查询单个地点。 */
    fun getLocationPoint(id: Long): org.json.JSONObject? {
        val cursor = open().query(
            TABLE_LOCATION_POINT,
            null,
            "$COL_ID=?",
            arrayOf(id.toString()),
            null,
            null,
            null,
            "1"
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val obj = org.json.JSONObject()
                obj.put("id", it.getLong(it.getColumnIndexOrThrow(COL_ID)))
                obj.put("name", it.getString(it.getColumnIndexOrThrow(COL_NAME)))
                obj.put("remark", it.getString(it.getColumnIndexOrThrow(COL_REMARK)))
                obj.put("latitude", it.getDouble(it.getColumnIndexOrThrow(COL_LATITUDE)))
                obj.put("longitude", it.getDouble(it.getColumnIndexOrThrow(COL_LONGITUDE)))
                obj.put("createTime", it.getLong(it.getColumnIndexOrThrow(COL_CREATE_TIME)))
                return obj
            }
        }
        return null
    }

    /** 删除一个地点。 */
    fun deleteLocationPoint(id: Long): Boolean {
        return open().delete(TABLE_LOCATION_POINT, "$COL_ID=?", arrayOf(id.toString())) > 0
    }

    // ---------- EnvSnapshot CRUD ----------

    /** 插入一条环境快照（信息采集 / 基站 / WiFi / GNSS 共用）。 */
    fun insertEnvSnapshot(name: String, remark: String, type: String, dataJson: String): Long {
        val values = android.content.ContentValues().apply {
            put(COL_NAME, name)
            put(COL_REMARK, remark)
            put(COL_TYPE, type)
            put(COL_DATA, dataJson)
            put(COL_CREATE_TIME, System.currentTimeMillis())
        }
        return open().insert(TABLE_ENV_SNAPSHOT, null, values)
    }

    /** 查询全部环境快照（按创建时间倒序）。 */
    fun queryEnvSnapshots(): List<org.json.JSONObject> {
        val result = mutableListOf<org.json.JSONObject>()
        val cursor = open().query(
            TABLE_ENV_SNAPSHOT,
            null,
            null,
            null,
            null,
            null,
            "$COL_CREATE_TIME DESC"
        )
        cursor?.use {
            val idIdx = it.getColumnIndexOrThrow(COL_ID)
            val nameIdx = it.getColumnIndexOrThrow(COL_NAME)
            val remarkIdx = it.getColumnIndexOrThrow(COL_REMARK)
            val typeIdx = it.getColumnIndexOrThrow(COL_TYPE)
            val dataIdx = it.getColumnIndexOrThrow(COL_DATA)
            val createIdx = it.getColumnIndexOrThrow(COL_CREATE_TIME)
            while (it.moveToNext()) {
                val obj = org.json.JSONObject()
                obj.put("id", it.getLong(idIdx))
                obj.put("name", it.getString(nameIdx))
                obj.put("remark", it.getString(remarkIdx))
                obj.put("type", it.getString(typeIdx))
                obj.put("data", org.json.JSONObject(it.getString(dataIdx)))
                obj.put("createTime", it.getLong(createIdx))
                result.add(obj)
            }
        }
        return result
    }

    /** 删除一条环境快照。 */
    fun deleteEnvSnapshot(id: Long): Boolean {
        return open().delete(TABLE_ENV_SNAPSHOT, "$COL_ID=?",
            arrayOf(id.toString())) > 0
    }

    // ---------- Recording CRUD ----------

    /** 创建一条录像记录，返回 id。 */
    fun insertRecording(name: String, remark: String): Long {
        val values = android.content.ContentValues().apply {
            put(COL_NAME, name)
            put(COL_REMARK, remark)
            put(COL_DURATION_MS, 0L)
            put(COL_FRAME_COUNT, 0)
            put(COL_CREATE_TIME, System.currentTimeMillis())
        }
        return open().insert(TABLE_RECORDING, null, values)
    }

    /** 更新录像元信息（停止录制时写入时长与帧数）。 */
    fun updateRecordingMeta(id: Long, durationMs: Long, frameCount: Int) {
        val values = android.content.ContentValues().apply {
            put(COL_DURATION_MS, durationMs)
            put(COL_FRAME_COUNT, frameCount)
        }
        open().update(TABLE_RECORDING, values, "$COL_ID=?", arrayOf(id.toString()))
    }

    /** 插入一帧录像数据。 */
    fun insertRecordingFrame(recordingId: Long, seq: Int, timestampMs: Long, dataJson: String) {
        val values = android.content.ContentValues().apply {
            put(COL_RECORDING_ID, recordingId)
            put(COL_SEQ, seq)
            put(COL_TIMESTAMP_MS, timestampMs)
            put(COL_DATA, dataJson)
        }
        open().insert(TABLE_RECORDING_FRAME, null, values)
    }

    /** 查询全部录像（按创建时间倒序）。 */
    fun queryRecordings(): List<org.json.JSONObject> {
        val result = mutableListOf<org.json.JSONObject>()
        val cursor = open().query(
            TABLE_RECORDING,
            null,
            null,
            null,
            null,
            null,
            "$COL_CREATE_TIME DESC"
        )
        cursor?.use {
            val idIdx = it.getColumnIndexOrThrow(COL_ID)
            val nameIdx = it.getColumnIndexOrThrow(COL_NAME)
            val remarkIdx = it.getColumnIndexOrThrow(COL_REMARK)
            val durationIdx = it.getColumnIndexOrThrow(COL_DURATION_MS)
            val countIdx = it.getColumnIndexOrThrow(COL_FRAME_COUNT)
            val createIdx = it.getColumnIndexOrThrow(COL_CREATE_TIME)
            val interruptedIdx = it.getColumnIndex(COL_INTERRUPTED)
            while (it.moveToNext()) {
                val obj = org.json.JSONObject()
                obj.put("id", it.getLong(idIdx))
                obj.put("name", it.getString(nameIdx))
                obj.put("remark", it.getString(remarkIdx))
                obj.put("durationMs", it.getLong(durationIdx))
                obj.put("frameCount", it.getInt(countIdx))
                obj.put("createTime", it.getLong(createIdx))
                obj.put("interrupted", interruptedIdx >= 0 && it.getInt(interruptedIdx) > 0)
                result.add(obj)
            }
        }
        return result
    }

    /** 标记录像为中断（录制未正常停止，如进程崩溃/重启）。 */
    fun markRecordingInterrupted(id: Long) {
        val values = android.content.ContentValues().apply {
            put(COL_INTERRUPTED, 1)
        }
        open().update(TABLE_RECORDING, values, "$COL_ID=?", arrayOf(id.toString()))
    }

    /** 查询所有尚未 finalize（frame_count=0）且 interrupted=0 的录像 id。 */
    fun queryUnfinalizedRecordingIds(): List<Long> {
        val result = mutableListOf<Long>()
        val cursor = open().query(
            TABLE_RECORDING,
            arrayOf(COL_ID),
            "$COL_FRAME_COUNT=? AND $COL_INTERRUPTED=?",
            arrayOf("0", "0"),
            null,
            null,
            "$COL_CREATE_TIME ASC"
        )
        cursor?.use {
            val idIdx = it.getColumnIndexOrThrow(COL_ID)
            while (it.moveToNext()) result.add(it.getLong(idIdx))
        }
        return result
    }

    /** 统计某录像的帧范围（首帧/末帧时间戳与帧数），无帧返回 null。 */
    fun recordingFrameRange(recordingId: Long): org.json.JSONObject? {
        val cursor = open().rawQuery(
            "SELECT MIN($COL_TIMESTAMP_MS) AS mn, MAX($COL_TIMESTAMP_MS) AS mx, COUNT(*) AS cnt " +
                "FROM $TABLE_RECORDING_FRAME WHERE $COL_RECORDING_ID=?",
            arrayOf(recordingId.toString())
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val mn = it.getLong(it.getColumnIndexOrThrow("mn"))
                val mx = it.getLong(it.getColumnIndexOrThrow("mx"))
                val cnt = it.getInt(it.getColumnIndexOrThrow("cnt"))
                if (cnt > 0) {
                    return org.json.JSONObject().apply {
                        put("firstTs", mn)
                        put("lastTs", mx)
                        put("count", cnt)
                    }
                }
            }
        }
        return null
    }

    /** 查询录像帧（按 seq 升序），offset/limit 非空时分页返回。 */
    fun queryRecordingFrames(
        recordingId: Long,
        offset: Int? = null,
        limit: Int? = null
    ): List<org.json.JSONObject> {
        val result = mutableListOf<org.json.JSONObject>()
        val limitClause = when {
            limit != null && limit > 0 && offset != null && offset > 0 -> "$limit OFFSET $offset"
            limit != null && limit > 0 -> limit.toString()
            else -> null
        }
        val cursor = open().query(
            TABLE_RECORDING_FRAME,
            null,
            "$COL_RECORDING_ID=?",
            arrayOf(recordingId.toString()),
            null,
            null,
            "$COL_SEQ ASC",
            limitClause
        )
        cursor?.use {
            val seqIdx = it.getColumnIndexOrThrow(COL_SEQ)
            val tsIdx = it.getColumnIndexOrThrow(COL_TIMESTAMP_MS)
            val dataIdx = it.getColumnIndexOrThrow(COL_DATA)
            while (it.moveToNext()) {
                val obj = org.json.JSONObject()
                obj.put("seq", it.getInt(seqIdx))
                obj.put("timestampMs", it.getLong(tsIdx))
                obj.put("data", org.json.JSONObject(it.getString(dataIdx)))
                result.add(obj)
            }
        }
        return result
    }

    /** 删除一条录像及其帧数据。 */
    fun deleteRecording(id: Long): Boolean {
        val db = open()
        val deleted = db.delete(TABLE_RECORDING, "$COL_ID=?", arrayOf(id.toString())) > 0
        if (deleted) {
            db.delete(TABLE_RECORDING_FRAME, "$COL_RECORDING_ID=?", arrayOf(id.toString()))
        }
        return deleted
    }

    // ---------- EnvState CRUD（环境引擎上次使用的配置持久化） ----------

    /** 保存某类型环境引擎的持久化状态（含开关与数据）。 */
    fun saveEnvState(type: String, enabled: Boolean, dataJson: String, snapshotId: Long) {
        val values = android.content.ContentValues().apply {
            put(COL_TYPE, type)
            put(COL_ENABLED, if (enabled) 1 else 0)
            put(COL_DATA, dataJson)
            put(COL_SNAPSHOT_ID, snapshotId)
            put(COL_UPDATE_TIME, System.currentTimeMillis())
        }
        open().insertWithOnConflict(TABLE_ENV_STATE, null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** 加载全部环境引擎持久化状态。 */
    fun loadEnvStates(): List<org.json.JSONObject> {
        val result = mutableListOf<org.json.JSONObject>()
        val cursor = open().query(
            TABLE_ENV_STATE,
            null,
            null,
            null,
            null,
            null,
            "$COL_UPDATE_TIME ASC"
        )
        cursor?.use {
            val typeIdx = it.getColumnIndexOrThrow(COL_TYPE)
            val enabledIdx = it.getColumnIndexOrThrow(COL_ENABLED)
            val dataIdx = it.getColumnIndexOrThrow(COL_DATA)
            val sidIdx = it.getColumnIndexOrThrow(COL_SNAPSHOT_ID)
            while (it.moveToNext()) {
                val type = it.getString(typeIdx)
                val obj = org.json.JSONObject()
                obj.put("type", type)
                obj.put("enabled", it.getInt(enabledIdx) != 0)
                obj.put("snapshotId", it.getLong(sidIdx))
                val raw = it.getString(dataIdx)
                if (raw.isNotBlank()) {
                    try {
                        obj.put("data", org.json.JSONObject(raw))
                    } catch (t: Throwable) {
                        ZLog.w(TAG_SCOPE, "env state data parse failed type=$type", t)
                    }
                }
                result.add(obj)
            }
        }
        return result
    }

    /** 删除某类型环境引擎的持久化状态（清除配置时调用）。 */
    fun deleteEnvState(type: String) {
        open().delete(TABLE_ENV_STATE, "$COL_TYPE=?", arrayOf(type))
    }
}