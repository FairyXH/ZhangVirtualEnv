package io.github.fairyxh.VirtualEnv.core

import android.location.Location
import io.github.fairyxh.VirtualEnv.core.engine.LocationEngine
import io.github.fairyxh.VirtualEnv.core.engine.SinglePointLocationEngine
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

    fun createRoute(name: String, pointsJson: String, speed: Double, stepFrequency: Int): Long {
        return databaseManager.insertRoute(name, pointsJson, speed, stepFrequency)
    }

    fun listRoutes(): List<org.json.JSONObject> {
        return databaseManager.queryRoutes()
    }
}
