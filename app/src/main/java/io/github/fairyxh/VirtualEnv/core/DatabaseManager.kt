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
        private const val DATABASE_VERSION = 2

        const val TABLE_ROUTE = "route"
        const val TABLE_LOCATION_POINT = "location_point"
        const val TABLE_ENV_SNAPSHOT = "env_snapshot"

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
            migrate(opened)
            db = opened
            ZLog.i(TAG_SCOPE, "DatabaseManager opened ${dbFile.absolutePath}, version=$DATABASE_VERSION")
            return opened
        }
    }

    /** 老库升级：route 表补充 remark 列。 */
    private fun migrate(database: SQLiteDatabase) {
        try {
            val columns = mutableSetOf<String>()
            database.rawQuery("PRAGMA table_info($TABLE_ROUTE)", null).use { c ->
                val nameIdx = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) columns.add(c.getString(nameIdx))
            }
            if (!columns.contains(COL_REMARK)) {
                database.execSQL("ALTER TABLE $TABLE_ROUTE ADD COLUMN $COL_REMARK TEXT DEFAULT ''")
                ZLog.i(TAG_SCOPE, "migrated: route add remark column")
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "migrate failed", t)
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
        return open().delete(TABLE_ENV_SNAPSHOT, "$COL_ID=?", arrayOf(id.toString())) > 0
    }
}
