package io.github.fairyxh.VirtualEnv.core

import android.location.Location
import io.github.fairyxh.VirtualEnv.core.engine.LocationEngine
import io.github.fairyxh.VirtualEnv.core.engine.SinglePointLocationEngine
import io.github.fairyxh.VirtualEnv.core.engine.EnvStateEngine
import io.github.fairyxh.VirtualEnv.core.model.LocationState
import io.github.fairyxh.VirtualEnv.profile.ProfileManager
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.io.File

/**
 * Backend Core 门面。
 *
 * 运行在 system_server 进程（由 Hook 入口初始化），
 * 聚合配置、数据库、引擎；ApiServer 与 Hook Adapter 均通过本类访问业务能力。
 *
 * 约定：
 * - Hook 层只调用 [currentLocation] 获取数据快照，不保存业务状态。
 * - App 控制端通过 ApiServer HTTP API 调用，不直接访问本类。
 */
class Backend private constructor(private val dataDir: File) {

    companion object {
        private const val TAG_SCOPE = "Backend"
        private const val DIR_NAME = "zve"

        @Volatile
        private var instance: Backend? = null

        /**
         * 初始化（仅一次，由 system_server 进程的 Hook 入口调用）。
         *
         * @param systemDataDir system_server 可写目录（Phase 1 使用 /data/system）
         */
        fun initialize(systemDataDir: File): Backend {
            synchronized(this) {
                instance?.let { return it }
                val dir = File(systemDataDir, DIR_NAME)
                if (!dir.exists()) dir.mkdirs()
                val backend = Backend(dir)
                backend.start()
                instance = backend
                ZLog.i(TAG_SCOPE, "Backend initialized at ${dir.absolutePath}")
                return backend
            }
        }

        fun get(): Backend? = instance
    }

    lateinit var configManager: ConfigManager
        private set
    lateinit var databaseManager: DatabaseManager
        private set
    lateinit var timelineEngine: TimelineEngine
        private set
    lateinit var environmentManager: EnvironmentManager
        private set
    lateinit var locationEngine: LocationEngine
        private set
    lateinit var profileManager: ProfileManager
        private set

    /** 虚拟 WiFi / 基站 / BLE 环境状态（Hook 层只读快照）。 */
    val wifiEngine = EnvStateEngine("wifi")
    val cellEngine = EnvStateEngine("cell")
    val bleEngine = EnvStateEngine("ble")

    @Volatile
    var apiServer: ApiServer? = null
        private set

    private fun start() {
        configManager = ConfigManager(dataDir)
        databaseManager = DatabaseManager(File(dataDir, "zve.db"))
        timelineEngine = DefaultTimelineEngine()
        environmentManager = DefaultEnvironmentManager(databaseManager)
        locationEngine = SinglePointLocationEngine()
        profileManager = ProfileManager(dataDir)

        // 恢复上次持久化的单点位置与开关
        if (configManager.isLocationEnabled()) {
            val cfg = configManager.load()
            val loc = cfg.optJSONObject("location")
            if (loc != null) {
                (locationEngine as SinglePointLocationEngine).setPoint(
                    loc.optDouble("latitude", 0.0),
                    loc.optDouble("longitude", 0.0),
                    loc.optDouble("speed", 0.0).toFloat(),
                    loc.optDouble("bearing", 0.0).toFloat()
                )
                locationEngine.setEnabled(true)
            }
        }
        ZLog.i(TAG_SCOPE, "Backend components started")
    }

    // ---------- Location API（Hook Adapter 调用） ----------

    /** Hook 层数据入口：返回当前虚拟位置；未启用时返回 null（放行真实数据）。 */
    fun currentLocation(): Location? = locationEngine.currentLocation()

    /** App 状态查询入口。 */
    fun locationState(): LocationState = locationEngine.currentState()

    /** App 设置单点位置（经 ApiServer 调用）。 */
    fun setLocationPoint(latitude: Double, longitude: Double, speed: Float, bearing: Float) {
        locationEngine.setPoint(latitude, longitude, speed, bearing)
        configManager.setPoint(latitude, longitude, speed, bearing)
    }

    /** App 开关虚拟定位（经 ApiServer 调用）。 */
    fun setLocationEnabled(enabled: Boolean) {
        locationEngine.setEnabled(enabled)
        configManager.setLocationEnabled(enabled)
    }

    /** 启动 HTTP API 服务。 */
    fun startApiServer(port: Int = ApiServer.DEFAULT_PORT): Boolean {
        if (apiServer != null) return true
        val server = ApiServer(port, this)
        return if (server.start()) {
            apiServer = server
            true
        } else {
            false
        }
    }

    /** 停止 HTTP API 服务。 */
    fun stopApiServer() {
        apiServer?.stop()
        apiServer = null
    }

    // ---------- Route API（App 控制端调用） ----------

    fun createRoute(name: String, remark: String, pointsJson: String, speed: Double, stepFrequency: Int): Long {
        return databaseManager.insertRoute(name, remark, pointsJson, speed, stepFrequency)
    }

    fun listRoutes(): List<org.json.JSONObject> {
        return databaseManager.queryRoutes()
    }

    fun getRoute(id: Long): org.json.JSONObject? {
        return databaseManager.getRoute(id)
    }

    fun deleteRoute(id: Long): Boolean {
        return databaseManager.deleteRoute(id)
    }

    // ---------- LocationPoint API ----------

    fun createLocationPoint(name: String, remark: String, latitude: Double, longitude: Double): Long {
        return databaseManager.insertLocationPoint(name, remark, latitude, longitude)
    }

    fun listLocationPoints(): List<org.json.JSONObject> {
        return databaseManager.queryLocationPoints()
    }

    fun getLocationPoint(id: Long): org.json.JSONObject? {
        return databaseManager.getLocationPoint(id)
    }

    /** 一键使用已保存地点：设置坐标并启用虚拟定位。 */
    fun useLocationPoint(id: Long): org.json.JSONObject? {
        val point = databaseManager.getLocationPoint(id) ?: return null
        val latitude = point.optDouble("latitude", Double.NaN)
        val longitude = point.optDouble("longitude", Double.NaN)
        if (latitude.isNaN() || longitude.isNaN()) return null
        setLocationPoint(latitude, longitude, 0f, 0f)
        setLocationEnabled(true)
        return point
    }

    fun deleteLocationPoint(id: Long): Boolean {
        return databaseManager.deleteLocationPoint(id)
    }

    // ---------- EnvSnapshot API ----------

    fun createEnvSnapshot(name: String, remark: String, type: String, dataJson: String): Long {
        return databaseManager.insertEnvSnapshot(name, remark, type, dataJson)
    }

    fun listEnvSnapshots(): List<org.json.JSONObject> {
        return databaseManager.queryEnvSnapshots()
    }

    fun deleteEnvSnapshot(id: Long): Boolean {
        return databaseManager.deleteEnvSnapshot(id)
    }

    // ---------- 虚拟环境加载（App 控制端调用） ----------

    /**
     * 一键使用环境快照：把已保存的 env_snapshot 数据加载到对应模拟引擎。
     *
     * @return 加载的快照 JSON；快照不存在或类型不支持时返回 null
     */
    fun useEnvSnapshot(id: Long): org.json.JSONObject? {
        val snapshot = databaseManager.queryEnvSnapshots()
            .firstOrNull { it.optLong("id", -1L) == id }
            ?: return null
        val type = snapshot.optString("type", "")
        val data = snapshot.optJSONObject("data") ?: return null
        when (type) {
            "wifi" -> wifiEngine.update(data)
            "cell" -> cellEngine.update(data)
            "ble" -> bleEngine.update(data)
            "collect" -> loadCollectSnapshot(data)
            else -> return null
        }
        ZLog.i(TAG_SCOPE, "env snapshot used id=$id type=$type")
        return snapshot
    }

    /** 一键采集包：拆分到 wifi / cell / ble 引擎（gnss/sensor 后续 Phase 接入）。 */
    private fun loadCollectSnapshot(data: org.json.JSONObject) {
        data.optJSONObject("wifi")?.let { wifiEngine.update(it) }
        data.optJSONObject("cell")?.let { cellEngine.update(it) }
        data.optJSONObject("bluetooth")?.let { bleEngine.update(it) }
    }

    /** 清除指定类型的虚拟环境。 */
    fun clearEnv(type: String) {
        when (type) {
            "wifi" -> wifiEngine.clear()
            "cell" -> cellEngine.clear()
            "ble" -> bleEngine.clear()
            "collect" -> {
                wifiEngine.clear()
                cellEngine.clear()
                bleEngine.clear()
            }
        }
        ZLog.i(TAG_SCOPE, "env cleared type=$type")
    }

    /** 当前虚拟环境状态（App 展示用）。 */
    fun envStatusJson(): org.json.JSONObject {
        return org.json.JSONObject().apply {
            put("wifi", wifiEngine.statusJson())
            put("cell", cellEngine.statusJson())
            put("ble", bleEngine.statusJson())
        }
    }
}
