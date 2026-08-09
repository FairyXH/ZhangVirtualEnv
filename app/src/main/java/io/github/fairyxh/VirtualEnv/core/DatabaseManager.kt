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
        private const val DATABASE_VERSION = 1

        const val TABLE_ROUTE = "route"
        const val COL_ID = "id"
        const val COL_NAME = "name"
        const val COL_POINTS = "points"
        const val COL_SPEED = "speed"
        const val COL_STEP_FREQUENCY = "step_frequency"
        const val COL_CREATE_TIME = "create_time"

        private const val SQL_CREATE_ROUTE =
            "CREATE TABLE IF NOT EXISTS $TABLE_ROUTE (" +
                "$COL_ID INTEGER PRIMARY KEY AUTOINCREMENT," +
                "$COL_NAME TEXT NOT NULL," +
                "$COL_POINTS TEXT NOT NULL," +
                "$COL_SPEED REAL NOT NULL DEFAULT 3.5," +
                "$COL_STEP_FREQUENCY INTEGER NOT NULL DEFAULT 120," +
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
            db = opened
            ZLog.i(TAG_SCOPE, "DatabaseManager opened ${dbFile.absolutePath}, version=$DATABASE_VERSION")
            return opened
        }
    }

    /** 关闭数据库连接（system_server 生命周期结束前调用）。 */
    fun close() {
        synchronized(lock) {
            db?.close()
            db = null
        }
    }
}
