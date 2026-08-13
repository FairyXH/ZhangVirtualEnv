package io.github.fairyxh.VirtualEnv.core

import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.io.File

/**
 * 配置管理器（Phase 1）。
 *
 * 负责持久化虚拟定位开关、单点坐标、以及后续 Phase 的扩展配置。
 * 存储：模块私有目录下的 config.json。
 *
 * 前端禁止直接读写该文件，必须通过 Backend API。
 */
class ConfigManager(private val configDir: File) {

    companion object {
        private const val FILE_NAME = "config.json"
        private const val KEY_LOCATION = "location"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
        private const val KEY_SPEED = "speed"
        private const val KEY_BEARING = "bearing"
    }

    private val configFile = File(configDir, FILE_NAME)
    private val lock = Any()

    init {
        if (!configDir.exists()) {
            configDir.mkdirs()
        }
    }

    /** 加载全部配置；文件不存在或损坏时返回默认配置。 */
    fun load(): JSONObject {
        synchronized(lock) {
            return try {
                if (configFile.exists()) {
                    JSONObject(configFile.readText())
                } else {
                    JSONObject()
                }
            } catch (t: Throwable) {
                ZLog.w("Core", "load config failed, use default", t)
                JSONObject()
            }
        }
    }

    fun isLocationEnabled(): Boolean = load().optJSONObject(KEY_LOCATION)?.optBoolean(KEY_ENABLED, false) ?: false

    fun setLocationEnabled(enabled: Boolean) {
        update(KEY_LOCATION) { it.put(KEY_ENABLED, enabled) }
    }

    fun setPoint(latitude: Double, longitude: Double, speed: Float, bearing: Float) {
        update(KEY_LOCATION) {
            it.put(KEY_LATITUDE, latitude)
                .put(KEY_LONGITUDE, longitude)
                .put(KEY_SPEED, speed)
                .put(KEY_BEARING, bearing)
        }
    }

    private fun update(key: String, transform: (JSONObject) -> JSONObject) {
        synchronized(lock) {
            val root = load()
            val section = root.optJSONObject(key) ?: JSONObject()
            root.put(key, transform(section))
            save(root)
        }
    }

    /** 整体覆盖配置（配置导入时使用）。 */
    fun saveRoot(root: JSONObject) {
        synchronized(lock) {
            save(root)
        }
    }

    private fun save(root: JSONObject) {
        try {
            configFile.writeText(root.toString(2))
            ZLog.d("Core", "config saved: $root")
        } catch (t: Throwable) {
            ZLog.e("Core", "save config failed", t)
        }
    }
}
