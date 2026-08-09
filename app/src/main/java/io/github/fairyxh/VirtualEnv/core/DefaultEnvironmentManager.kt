package io.github.fairyxh.VirtualEnv.core

import android.database.Cursor
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 环境数据管理器基础实现（Phase 1）。
 *
 * 基于 [DatabaseManager] 提供 location 环境记录的存取骨架；
 * 其余类型（cell/wifi/ble/sensor/gnss）在后续 Phase 扩展表结构。
 */
class DefaultEnvironmentManager(
    private val db: DatabaseManager,
) : EnvironmentManager {

    companion object {
        private const val TAG_SCOPE = "Core"
        private const val TABLE_ENV = "environment"
        private const val COL_ID = "id"
        private const val COL_TYPE = "type"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_LATITUDE = "latitude"
        private const val COL_LONGITUDE = "longitude"
        private const val COL_DATA = "data"

        private const val SQL_CREATE_ENV =
            "CREATE TABLE IF NOT EXISTS $TABLE_ENV (" +
                "$COL_ID INTEGER PRIMARY KEY AUTOINCREMENT," +
                "$COL_TYPE TEXT NOT NULL," +
                "$COL_TIMESTAMP INTEGER NOT NULL," +
                "$COL_LATITUDE REAL NOT NULL DEFAULT 0," +
                "$COL_LONGITUDE REAL NOT NULL DEFAULT 0," +
                "$COL_DATA TEXT NOT NULL" +
                ")"
    }

    private val initialized = AtomicBoolean(false)

    private fun ensureTable() {
        if (initialized.compareAndSet(false, true)) {
            db.open().execSQL(SQL_CREATE_ENV)
            ZLog.i(TAG_SCOPE, "EnvironmentManager ensure table $TABLE_ENV")
        }
    }

    override fun saveRecord(type: String, record: JSONObject) {
        ensureTable()
        val timestamp = record.optLong("timestamp", System.currentTimeMillis())
        val latitude = record.optDouble("latitude", 0.0)
        val longitude = record.optDouble("longitude", 0.0)
        val data = record.optJSONObject("data")?.toString() ?: "{}"
        val values = android.content.ContentValues().apply {
            put(COL_TYPE, type)
            put(COL_TIMESTAMP, timestamp)
            put(COL_LATITUDE, latitude)
            put(COL_LONGITUDE, longitude)
            put(COL_DATA, data)
        }
        db.open().insert(TABLE_ENV, null, values)
        ZLog.d(TAG_SCOPE, "saveRecord type=$type ts=$timestamp")
    }

    override fun queryRecords(type: String, limit: Int): JSONArray {
        ensureTable()
        val result = JSONArray()
        val cursor: Cursor? = db.open().query(
            TABLE_ENV,
            null,
            "$COL_TYPE = ?",
            arrayOf(type),
            null,
            null,
            "$COL_TIMESTAMP DESC",
            limit.toString()
        )
        cursor?.use {
            val idIdx = it.getColumnIndexOrThrow(COL_ID)
            val tsIdx = it.getColumnIndexOrThrow(COL_TIMESTAMP)
            val latIdx = it.getColumnIndexOrThrow(COL_LATITUDE)
            val lonIdx = it.getColumnIndexOrThrow(COL_LONGITUDE)
            val dataIdx = it.getColumnIndexOrThrow(COL_DATA)
            while (it.moveToNext()) {
                val obj = JSONObject()
                obj.put("id", it.getLong(idIdx))
                obj.put("timestamp", it.getLong(tsIdx))
                obj.put("latitude", it.getDouble(latIdx))
                obj.put("longitude", it.getDouble(lonIdx))
                obj.put("data", JSONObject(it.getString(dataIdx)))
                result.put(obj)
            }
        }
        return result
    }

    override fun clearRecords(type: String) {
        ensureTable()
        db.open().delete(TABLE_ENV, "$COL_TYPE = ?", arrayOf(type))
        ZLog.i(TAG_SCOPE, "clearRecords type=$type")
    }

    override fun exportPackage(): JSONObject {
        val pkg = JSONObject()
        pkg.put("version", 1)
        pkg.put("location", queryRecords("location", 1000))
        return pkg
    }

    override fun importPackage(pkg: JSONObject) {
        val location = pkg.optJSONArray("location")
        if (location != null) {
            clearRecords("location")
            for (i in 0 until location.length()) {
                saveRecord("location", location.getJSONObject(i))
            }
        }
        ZLog.i(TAG_SCOPE, "importPackage done")
    }
}
