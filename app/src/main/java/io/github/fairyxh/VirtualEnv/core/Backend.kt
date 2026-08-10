package io.github.fairyxh.VirtualEnv.core

import android.location.Location
import io.github.fairyxh.VirtualEnv.core.engine.LocationEngine
import io.github.fairyxh.VirtualEnv.core.engine.SinglePointLocationEngine
import io.github.fairyxh.VirtualEnv.core.engine.EnvStateEngine
import io.github.fairyxh.VirtualEnv.core.engine.RouteEngine
import io.github.fairyxh.VirtualEnv.core.engine.JoystickEngine
import io.github.fairyxh.VirtualEnv.core.engine.RecordingEngine
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

    /** 路线模拟引擎（优先于单点输出）。 */
    val routeEngine = RouteEngine()

    /** 悬浮窗摇杆引擎：位移叠加在路线/单点输出之上。 */
    val joystickEngine = JoystickEngine()

    /** 虚拟 WiFi / 基站 / BLE 环境状态（Hook 层只读快照）。 */
    val wifiEngine = EnvStateEngine("wifi")
    val cellEngine = EnvStateEngine("cell")
    val bleEngine = EnvStateEngine("ble")

    /** 虚拟 GNSS / 传感器（步频）状态（Phase 4 接入 Hook，状态由同一引擎管理）。 */
    val gnssEngine = EnvStateEngine("gnss")
    val sensorEngine = EnvStateEngine("sensor")

    /** 环境录制与回放引擎。 */
    lateinit var recordingEngine: RecordingEngine
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
        recordingEngine = RecordingEngine(databaseManager, this)

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

    /** Hook 层数据入口：路线运行时输出路线位置，否则输出单点；未启用时 null（放行真实数据）。摇杆开启时在基准位置叠加位移。 */
    fun currentLocation(): Location? {
        val base = routeEngine.currentLocation() ?: locationEngine.currentLocation()
        return joystickEngine.applyTo(base) ?: base
    }

    /** App 状态查询入口（路线运行时优先返回路线位置；摇杆开启时叠加位移）。 */
    fun locationState(): LocationState {
        val base = routeEngine.currentState()
        if (!base.enabled) {
            val single = locationEngine.currentState()
            return if (single.enabled) joystickEngine.applyTo(single) else single
        }
        return joystickEngine.applyTo(base)
    }

    /** App 设置单点位置（经 ApiServer 调用）。 */
    fun setLocationPoint(latitude: Double, longitude: Double, speed: Float, bearing: Float) {
        locationEngine.setPoint(latitude, longitude, speed, bearing)
        configManager.setPoint(latitude, longitude, speed, bearing)
    }

    /** App 开关虚拟定位（经 ApiServer 调用）。开启时与路线模拟互斥：先停路线。 */
    fun setLocationEnabled(enabled: Boolean) {
        if (enabled) {
            // 互斥：单点虚拟定位与路线模拟不能同时开启
            routeEngine.stop()
        }
        locationEngine.setEnabled(enabled)
        configManager.setLocationEnabled(enabled)
    }

    // ---------- 采集时临时停用虚拟环境 ----------

    @Volatile
    private var suspendCount = 0

    @Volatile
    private var locationWasEnabled = false

    @Volatile
    private var routeSnapshot: org.json.JSONObject? = null

    @Volatile
    private var envSnapshot: org.json.JSONObject? = null

    /** 是否处于临时停用状态（Hook 层按此放行真实数据）。 */
    fun isSuspended(): Boolean = suspendCount > 0

    /**
     * 临时停用全部虚拟环境（采集真实环境前调用）。
     *
     * 可嵌套：内部计数，多次调用需对应次数的 [resumeAll]。
     *
     * @return true 表示本次调用真正执行了停用（首次）
     */
    fun suspendAll(): Boolean {
        synchronized(this) {
            val first = suspendCount == 0
            suspendCount++
            if (first) {
                locationWasEnabled = locationEngine.isEnabled()
                routeSnapshot = if (routeEngine.isEnabled()) routeEngine.snapshotJson() else null
                envSnapshot = org.json.JSONObject().apply {
                    put("wifi", wifiEngine.statusJson())
                    put("cell", cellEngine.statusJson())
                    put("ble", bleEngine.statusJson())
                    put("gnss", gnssEngine.statusJson())
                    put("sensor", sensorEngine.statusJson())
                }
                // 停止所有虚拟输出，Hook 层立即放行真实数据
                setLocationEnabled(false)
                routeEngine.stop()
                wifiEngine.clear()
                cellEngine.clear()
                bleEngine.clear()
                gnssEngine.clear()
                sensorEngine.clear()
                ZLog.i(TAG_SCOPE, "env suspended")
            }
            return first
        }
    }

    /** 恢复被 [suspendAll] 停用的虚拟环境（计数归零时真正恢复）。 */
    fun resumeAll(): Boolean {
        synchronized(this) {
            if (suspendCount <= 0) return false
            suspendCount--
            if (suspendCount == 0) {
                if (locationWasEnabled) setLocationEnabled(true)
                routeSnapshot?.let { routeEngine.restoreFrom(it) }
                routeSnapshot = null
                envSnapshot?.let { snap ->
                    restoreEngine(snap, "wifi", wifiEngine)
                    restoreEngine(snap, "cell", cellEngine)
                    restoreEngine(snap, "ble", bleEngine)
                    restoreEngine(snap, "gnss", gnssEngine)
                    restoreEngine(snap, "sensor", sensorEngine)
                }
                envSnapshot = null
                ZLog.i(TAG_SCOPE, "env resumed")
            }
            return suspendCount == 0
        }
    }

    private fun restoreEngine(snap: org.json.JSONObject, key: String, engine: EnvStateEngine) {
        val status = snap.optJSONObject(key) ?: return
        val data = status.optJSONObject("data")
        if (status.optBoolean("enabled", false) && data != null) engine.update(data) else engine.clear()
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

    // ---------- 路线模拟控制 ----------

    /** 一键启动路线模拟：加载路线点并按速度开始播放。与单点虚拟定位互斥。 */
    fun startRoute(id: Long, speedKmh: Double): org.json.JSONObject? {
        val route = databaseManager.getRoute(id) ?: return null
        // 互斥：启动路线模拟时关闭单点虚拟定位
        if (locationEngine.isEnabled()) {
            setLocationEnabled(false)
        }
        val points = route.optString("points", "")
        val speed = if (speedKmh > 0) speedKmh else route.optDouble("speed", 3.5)
        val stepFrequency = route.optInt("stepFrequency", 120)
        routeEngine.start(points, speed, stepFrequency)
        ZLog.i(TAG_SCOPE, "route started id=$id speed=$speed stepFrequency=$stepFrequency")
        return route
    }

    fun pauseRoute() {
        routeEngine.pause()
    }

    /** 暂停后继续。 */
    fun resumeRoute() {
        routeEngine.resume()
    }

    /** 重置到路线起点并继续运行。 */
    fun resetRoute() {
        routeEngine.reset()
    }

    /** 更新路线运行参数：speedKmh/stepFrequency 传 0 表示不修改。 */
    fun configRoute(speedKmh: Double, stepFrequency: Int) {
        routeEngine.config(speedKmh, stepFrequency)
        ZLog.i(TAG_SCOPE, "route config speedKmh=$speedKmh stepFrequency=$stepFrequency")
    }

    fun stopRoute() {
        routeEngine.stop()
    }

    fun routeStatusJson(): org.json.JSONObject {
        return routeEngine.statusJson()
    }

    // ---------- 摇杆控制 ----------

    /** 悬浮窗摇杆向量更新（App 控制端调用）。 */
    fun setJoystickVector(enabled: Boolean, dx: Double, dy: Double, speedKmh: Double) {
        joystickEngine.setVector(enabled, dx, dy, speedKmh)
    }

    fun joystickStatusJson(): org.json.JSONObject {
        return joystickEngine.statusJson()
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
            "gnss" -> gnssEngine.update(data)
            "sensor" -> sensorEngine.update(data)
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
        data.optJSONObject("gnss")?.let { gnssEngine.update(it) }
        data.optJSONObject("sensor")?.let { sensorEngine.update(it) }
    }

    /** 直接设置指定类型的虚拟环境数据（经 ApiServer 调用，/api/<type>/set）。 */
    fun setEnvData(type: String, data: org.json.JSONObject): Boolean {
        when (type) {
            "wifi" -> wifiEngine.update(data)
            "cell" -> cellEngine.update(data)
            "ble" -> bleEngine.update(data)
            "gnss" -> gnssEngine.update(data)
            "sensor" -> sensorEngine.update(data)
            else -> return false
        }
        ZLog.i(TAG_SCOPE, "env data set type=$type keys=${data.length()}")
        return true
    }

    /** 查询指定类型的虚拟环境状态。 */
    fun envStatus(type: String): org.json.JSONObject? {
        return when (type) {
            "wifi" -> wifiEngine.statusJson()
            "cell" -> cellEngine.statusJson()
            "ble" -> bleEngine.statusJson()
            "gnss" -> gnssEngine.statusJson()
            "sensor" -> sensorEngine.statusJson()
            else -> null
        }
    }

    /** 清除指定类型的虚拟环境。 */
    fun clearEnv(type: String) {
        when (type) {
            "wifi" -> wifiEngine.clear()
            "cell" -> cellEngine.clear()
            "ble" -> bleEngine.clear()
            "gnss" -> gnssEngine.clear()
            "sensor" -> sensorEngine.clear()
            "collect" -> {
                wifiEngine.clear()
                cellEngine.clear()
                bleEngine.clear()
                gnssEngine.clear()
                sensorEngine.clear()
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
            put("gnss", gnssEngine.statusJson())
            put("sensor", sensorEngine.statusJson())
        }
    }

    // ---------- Profile API ----------

    /** 当前 Profile 信息（App 展示 / 排障用）。 */
    fun profileInfoJson(): org.json.JSONObject {
        val p = profileManager.current
        val hooks = p?.optJSONObject("hooks")
        return org.json.JSONObject().apply {
            put("name", p?.optString("name", "") ?: "")
            put("device", p?.optString("device", "") ?: "")
            put("minSdk", p?.optInt("minSdk", 0) ?: 0)
            put("maxSdk", p?.optInt("maxSdk", 99) ?: 99)
            put("hookModules", hooks?.length() ?: 0)
            put("apiVersion", 1)
        }
    }

    // ---------- Recording API（App 控制端调用） ----------

    fun startRecording(name: String, remark: String): Long {
        return recordingEngine.startRecording(name, remark)
    }

    fun appendRecordingFrame(id: Long, frame: org.json.JSONObject): Boolean {
        return recordingEngine.appendFrame(frame)
    }

    fun stopRecording(id: Long): Boolean {
        return if (id > 0) recordingEngine.stopRecording() else false
    }

    fun listRecordings(): List<org.json.JSONObject> {
        return recordingEngine.listRecordings()
    }

    fun getRecordingFrames(id: Long): List<org.json.JSONObject> {
        return recordingEngine.getFrames(id)
    }

    fun deleteRecording(id: Long): Boolean {
        return recordingEngine.deleteRecording(id)
    }

    fun playRecordings(ids: List<Long>, loop: Boolean): Boolean {
        return recordingEngine.play(ids, loop)
    }

    fun pauseRecordingPlayback() {
        recordingEngine.pausePlayback()
    }

    fun resumeRecordingPlayback() {
        recordingEngine.resumePlayback()
    }

    fun stopRecordingPlayback() {
        recordingEngine.stopPlayback()
    }

    fun recordingStatusJson(): org.json.JSONObject {
        return recordingEngine.statusJson()
    }
}
