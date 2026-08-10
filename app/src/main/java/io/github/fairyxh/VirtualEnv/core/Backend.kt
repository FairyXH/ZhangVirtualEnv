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
import java.util.concurrent.ThreadLocalRandom

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

        /** 采集拆分轨道快照的来源标记（追加在 remark 中，用于关联与识别）。 */
        const val TRACK_SOURCE_TAG = "（来自环境采集）"
        const val TRACK_SOURCE_ROUTE_TAG = "（来自录像）"

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

    /** 环境实时测试最新报告（App 上报，供外部查看/自动化修正 Hook）。 */
    @Volatile
    private var testReport: org.json.JSONObject? = null

    fun setTestReport(report: org.json.JSONObject) {
        testReport = report
    }

    fun getTestReport(): org.json.JSONObject? = testReport

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

    /**
     * 位置状态 JSON（App 展示用）。
     *
     * 附加 singleEnabled / mode：单点开关应只反映单点引擎，避免路线运行
     * 时位置页开关误显示为开启（两个开关视觉上保持互斥）。
     */
    fun locationStatusJson(): org.json.JSONObject {
        val json = locationState().toJson()
        json.put("singleEnabled", locationEngine.isEnabled())
        json.put(
            "mode",
            when {
                routeEngine.isEnabled() -> "route"
                locationEngine.isEnabled() -> "single"
                else -> "none"
            }
        )
        return json
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

    /** 回放停止时同步停用回放产生的虚拟环境：关闭虚拟定位并清空环境引擎（fail-open 放行真实数据）。 */
    fun stopRecordingPlaybackEnv() {
        synchronized(this) {
            setLocationEnabled(false)
            routeEngine.stop()
            wifiEngine.clear()
            cellEngine.clear()
            bleEngine.clear()
            gnssEngine.clear()
            sensorEngine.clear()
            activeEnvSnapshotIds.keys.removeAll(
                setOf("wifi", "cell", "ble", "gnss", "sensor")
            )
            ZLog.i(TAG_SCOPE, "recording playback env stopped (location + env cleared)")
        }
    }

    // ---------- 环境状态快照（录像编程器：录制/回放完整环境状态） ----------

    /**
     * 当前完整虚拟环境状态快照：位置（单点+开关）、路线、摇杆、
     * 以及全部环境引擎（wifi/cell/ble/gnss/sensor）的 enabled+data。
     *
     * 录制时由 RecordingEngine 自动附加到每帧，回放时按帧应用，
     * 实现“录制编程器”——在合适时间点自动操作位置/路线/所有环境开关和配置。
     */
    fun envStateSnapshotJson(): org.json.JSONObject {
        return org.json.JSONObject().apply {
            put("location", org.json.JSONObject().apply {
                put("enabled", locationEngine.isEnabled())
                val s = locationEngine.currentState()
                put("latitude", s.latitude)
                put("longitude", s.longitude)
                put("speed", s.speed)
                put("bearing", s.bearing)
                put("accuracy", s.accuracy)
            })
            put("route", routeEngine.snapshotJson())
            put("joystick", joystickEngine.statusJson())
            put("wifi", wifiEngine.statusJson())
            put("cell", cellEngine.statusJson())
            put("ble", bleEngine.statusJson())
            put("gnss", gnssEngine.statusJson())
            put("sensor", sensorEngine.statusJson())
        }
    }

    /**
     * 应用一帧环境状态快照（录像回放时调用）。
     *
     * 位置/路线/摇杆与各环境引擎的 enabled+data 全部同步；
     * enabled=false 的引擎 clear（放行真实数据），数据保留语义由调用方快照保证。
     */
    fun applyEnvStateSnapshot(snap: org.json.JSONObject) {
        synchronized(this) {
            // 位置（单点）：开关与坐标同步
            snap.optJSONObject("location")?.let { loc ->
                val enabled = loc.optBoolean("enabled", false)
                if (enabled) {
                    setLocationPoint(
                        loc.optDouble("latitude", 0.0),
                        loc.optDouble("longitude", 0.0),
                        loc.optDouble("speed", 0.0).toFloat(),
                        loc.optDouble("bearing", 0.0).toFloat()
                    )
                    setLocationEnabled(true)
                } else {
                    setLocationEnabled(false)
                }
            }
            // 路线：快照含 points/speed/stepFrequency/segment/progress
            snap.optJSONObject("route")?.let { route ->
                if (route.optBoolean("enabled", false) && route.optJSONArray("points")?.length() ?: 0 >= 2) {
                    routeEngine.restoreFrom(route)
                } else {
                    routeEngine.stop()
                }
            }
            // 摇杆
            snap.optJSONObject("joystick")?.let { j ->
                joystickEngine.setVector(
                    j.optBoolean("enabled", false),
                    j.optDouble("dx", 0.0),
                    j.optDouble("dy", 0.0),
                    j.optDouble("speedKmh", 5.0)
                )
            }
            applyEnvSnapshotEngine(snap, "wifi", wifiEngine)
            applyEnvSnapshotEngine(snap, "cell", cellEngine)
            applyEnvSnapshotEngine(snap, "ble", bleEngine)
            applyEnvSnapshotEngine(snap, "gnss", gnssEngine)
            applyEnvSnapshotEngine(snap, "sensor", sensorEngine)
            ZLog.i(TAG_SCOPE, "env state snapshot applied (${snap.length()} groups)")
        }
    }

    private fun applyEnvSnapshotEngine(snap: org.json.JSONObject, key: String, engine: EnvStateEngine) {
        val status = snap.optJSONObject(key) ?: return
        val data = status.optJSONObject("data")
        if (status.optBoolean("enabled", false) && data != null) {
            engine.update(data)
        } else {
            engine.clear()
        }
    }

    // ---------- 录像回放前保存 / 回放后恢复 ----------

    @Volatile
    private var prePlaybackSnapshot: org.json.JSONObject? = null

    /** 录像回放开始前保存当前环境状态（回放结束时恢复）。 */
    fun savePrePlaybackState() {
        synchronized(this) {
            prePlaybackSnapshot = envStateSnapshotJson()
            ZLog.i(TAG_SCOPE, "pre-playback env saved")
        }
    }

    /** 回放结束后恢复回放前环境；无快照时清空（fail-open 放行真实数据）。 */
    fun restoreAfterPlayback() {
        val snap = synchronized(this) {
            val s = prePlaybackSnapshot
            prePlaybackSnapshot = null
            s
        }
        if (snap != null) {
            applyEnvStateSnapshot(snap)
            ZLog.i(TAG_SCOPE, "playback env restored from pre-playback snapshot")
        } else {
            stopRecordingPlaybackEnv()
        }
    }

    // ---------- 录制基线（采集开始前保存用户虚拟环境状态） ----------

    @Volatile
    private var recordingBaseSnapshot: org.json.JSONObject? = null

    /** 录像开始时保存当前环境状态（采集真实数据前调用，作为回放初始状态）。 */
    fun saveRecordingBaseState() {
        synchronized(this) {
            recordingBaseSnapshot = envStateSnapshotJson()
            ZLog.i(TAG_SCOPE, "recording base env saved")
        }
    }

    /** 录像停止时清除录制基线（避免残留被下一次回放误用）。 */
    fun clearRecordingBaseState() {
        synchronized(this) {
            recordingBaseSnapshot = null
        }
    }

    /**
     * 录制期间读取录制基线（不消费）。采集模式下 suspendAll 会把各引擎清空，
     * 帧内 envState 需要以录制基线为准，而不是空的“挂起状态”。
     */
    fun recordingBaseSnapshotJson(): org.json.JSONObject? {
        return synchronized(this) { recordingBaseSnapshot }
    }

    /**
     * 回放录像前应用录制基线：恢复用户录制开始时的位置/路线/摇杆/环境开关与配置。
     * 使录像回放从与录制时一致的环境起点开始（采集帧数据随后逐帧覆盖）。
     * 只读应用，不消费基线：同一录像可多次回放；基线在停止录制时清除。
     */
    fun applyRecordingBaseState() {
        val snap = synchronized(this) { recordingBaseSnapshot }
        if (snap != null) {
            applyEnvStateSnapshot(snap)
            ZLog.i(TAG_SCOPE, "recording base env applied (${snap.length()} groups)")
        } else {
            // 无基线（老录像）：沿用原有“清空后由帧数据重建”的行为
            ZLog.i(TAG_SCOPE, "recording base env absent, replay builds from frames")
        }
    }

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

    /** 启动 HTTP API 服务。token 为空时拒绝所有请求（fail-closed）。 */
    fun startApiServer(port: Int = ApiServer.DEFAULT_PORT, token: String = ""): Boolean {
        if (apiServer != null) return true
        val server = ApiServer(port, this, token)
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
    fun startRoute(id: Long, speedKmh: Double, stepFrequency: Int = 0): org.json.JSONObject? {
        val route = databaseManager.getRoute(id) ?: return null
        // 互斥：启动路线模拟时关闭单点虚拟定位
        if (locationEngine.isEnabled()) {
            setLocationEnabled(false)
        }
        val points = route.optString("points", "")
        val speed = if (speedKmh > 0) speedKmh else route.optDouble("speed", 3.5)
        val stepFreq = if (stepFrequency > 0) stepFrequency else route.optInt("stepFrequency", 120)
        routeEngine.start(points, speed, stepFreq)
        ZLog.i(TAG_SCOPE, "route started id=$id speed=$speed stepFrequency=$stepFreq")
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

    /** 当前正在使用的环境快照 id（按类型），供 App 子页面标识“使用中”配置。 */
    private val activeEnvSnapshotIds = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 一键使用环境快照：把已保存的 env_snapshot 数据加载到对应模拟引擎。 */
    fun useEnvSnapshot(id: Long): org.json.JSONObject? {
         val snapshot = databaseManager.queryEnvSnapshots()
             .firstOrNull { it.optLong("id", -1L) == id }
             ?: return null
         val type = snapshot.optString("type", "")
         val data = snapshot.optJSONObject("data") ?: return null
         when (type) {
             "wifi" -> {
                 wifiEngine.update(data)
                 activeEnvSnapshotIds["wifi"] = id
             }
             "cell" -> {
                 cellEngine.update(data)
                 activeEnvSnapshotIds["cell"] = id
             }
             "ble" -> {
                 bleEngine.update(data)
                 activeEnvSnapshotIds["ble"] = id
             }
             "gnss" -> {
                 gnssEngine.update(data)
                 activeEnvSnapshotIds["gnss"] = id
             }
             "sensor" -> {
                 sensorEngine.update(data)
                 activeEnvSnapshotIds["sensor"] = id
             }
             "collect" -> {
                 loadCollectSnapshot(data)
                 // 轨道化回放：同名+来源标记的轨道快照存在则用轨道数据覆盖，
                 // 不存在（被单独删除）则清空该轨道 = 留空轨（真实信息）
                 applyTrackOverrides(snapshot.optString("name", ""))
                 // 自动启用该采集保存的位置轨道（已保存地点）
                 enableCollectLocation(snapshot.optString("name", ""))
                 // 采集包不等于某个具体配置，清除子类型的“使用中”标记
                 activeEnvSnapshotIds.keys.removeAll(
                     setOf("wifi", "cell", "ble", "gnss", "sensor")
                 )
             }
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

    /** 轨道化回放：按采集名查找拆分轨道（type=cell/wifi/gnss 且 remark 含来源标记）。 */
    private fun applyTrackOverrides(collectName: String) {
        val tracks = databaseManager.queryEnvSnapshots()
            .filter { it.optString("remark", "").contains(TRACK_SOURCE_TAG) }
        fun findTrack(type: String): org.json.JSONObject? =
            tracks.firstOrNull { it.optString("type", "") == type && it.optString("name", "") == collectName }

        findTrack("cell")?.optJSONObject("data")?.let { cellEngine.update(it) } ?: cellEngine.clear()
        findTrack("wifi")?.optJSONObject("data")?.let { wifiEngine.update(it) } ?: wifiEngine.clear()
        findTrack("gnss")?.optJSONObject("data")?.let { gnssEngine.update(it) } ?: gnssEngine.clear()
        findTrack("ble")?.optJSONObject("data")?.let { bleEngine.update(it) } ?: bleEngine.clear()
        ZLog.i(TAG_SCOPE, "collect track overrides applied for name=$collectName")
    }

    /** 采集回放时自动启用同名位置轨道（来自采集的已保存地点）。 */
    private fun enableCollectLocation(collectName: String) {
        val point = databaseManager.queryLocationPoints()
            .firstOrNull {
                it.optString("name", "") == collectName &&
                    it.optString("remark", "").contains(TRACK_SOURCE_TAG)
            }
            ?: return
        val id = point.optLong("id", -1L)
        if (id > 0) {
            useLocationPoint(id)
            ZLog.i(TAG_SCOPE, "collect location track enabled id=$id name=$collectName")
        }
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

    /** 单类型开关：关闭时 Hook 放行真实数据，数据保留；开启时恢复。 */
    fun setEnvEnabled(type: String, enabled: Boolean): Boolean {
        when (type) {
            "wifi" -> wifiEngine.setEnabled(enabled)
            "cell" -> cellEngine.setEnabled(enabled)
            "ble" -> bleEngine.setEnabled(enabled)
            "gnss" -> gnssEngine.setEnabled(enabled)
            "sensor" -> sensorEngine.setEnabled(enabled)
            else -> return false
        }
        ZLog.i(TAG_SCOPE, "env type=$type enabled=$enabled")
        return true
    }

    /** 查询指定类型的虚拟环境状态（附带当前正在使用的配置 id）。 */
    fun envStatus(type: String): org.json.JSONObject? {
        val status = when (type) {
            "wifi" -> wifiEngine.statusJson()
            "cell" -> cellEngine.statusJson()
            "ble" -> bleEngine.statusJson()
            "gnss" -> gnssEngine.statusJson()
            "sensor" -> sensorEngine.statusJson()
            else -> return null
        }
        status.put("activeSnapshotId", activeEnvSnapshotIds[type] ?: -1L)
        return status
    }

    /**
     * 调试辅助：生成全套随机虚拟环境并全部启用。
     *
     * 供检测器/自动化脚本快速验证 Hook 全链路（位置+基站+WiFi+BLE+传感器+GNSS）。
     * 返回生成的完整配置 JSON（检测器可据此判定 PASS/FAIL）。
     */
    fun generateRandomEnv(): org.json.JSONObject {
        val rnd = ThreadLocalRandom.current()

        // 单点位置：国内常见区域随机偏移
        val baseLat = 24.6 + rnd.nextDouble(-0.5, 0.5)
        val baseLon = 118.0 + rnd.nextDouble(-0.5, 0.5)
        setLocationPoint(baseLat, baseLon, 0f, 0f)
        setLocationEnabled(true)

        // 基站：1~2 个 LTE + 1 个 NR
        val cellEntries = org.json.JSONArray()
        val cellCount = 1 + rnd.nextInt(2)
        for (i in 0 until cellCount) {
            cellEntries.put(org.json.JSONObject().apply {
                put("type", "LTE")
                put("mcc", 460)
                put("mnc", 11)
                // CellIdentityLte 字段范围：TAC 16 位 0~65535，CI 28 位 0~268435455，
                // PCI 0~503；超出会被构造器归一化为 Integer.MAX_VALUE（读回全 MAX）。
                put("tac", rnd.nextInt(0, 65536))
                put("ci", rnd.nextInt(0, 1 shl 28))
                put("pci", rnd.nextInt(0, 504))
            })
        }
        cellEntries.put(org.json.JSONObject().apply {
            put("type", "NR")
            put("mcc", 460)
            put("mnc", 11)
            put("tac", rnd.nextInt(0, 65536))
            put("nci", 140000000000L + rnd.nextInt(100000000))
        })
        setEnvData("cell", org.json.JSONObject().apply { put("entries", cellEntries) })
        setEnvEnabled("cell", true)

        // WiFi：3~5 个虚拟网络
        val wifiNetworks = org.json.JSONArray()
        val wifiCount = 3 + rnd.nextInt(3)
        for (i in 0 until wifiCount) {
            wifiNetworks.put(org.json.JSONObject().apply {
                put("ssid", "ZVE-Rand-$i")
                put("bssid", String.format(
                    "AA:BB:CC:%02X:%02X:%02X",
                    rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)
                ))
                put("level", -40 - rnd.nextInt(50))
                put("frequency", 2412 + rnd.nextInt(6) * 5)
            })
        }
        setEnvData("wifi", org.json.JSONObject().apply { put("networks", wifiNetworks) })
        setEnvEnabled("wifi", true)

        // BLE：2~4 个虚拟设备
        val bleDevices = org.json.JSONArray()
        val bleCount = 2 + rnd.nextInt(3)
        for (i in 0 until bleCount) {
            bleDevices.put(org.json.JSONObject().apply {
                put("name", "ZVE-Device-$i")
                put("address", String.format(
                    "AA:BB:CC:%02X:%02X:%02X",
                    rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)
                ))
                put("rssi", -50 - rnd.nextInt(40))
            })
        }
        setEnvData("ble", org.json.JSONObject().apply { put("devices", bleDevices) })
        setEnvEnabled("ble", true)

        // 传感器：步频 90~150
        val stepFrequency = 90 + rnd.nextInt(61)
        setEnvData("sensor", org.json.JSONObject().apply {
            put("stepFrequency", stepFrequency)
            put("stepCounter", rnd.nextLong(0, 100000))
        })
        setEnvEnabled("sensor", true)

        // GNSS：卫星 12~24，使用 4~10
        val satelliteCount = 12 + rnd.nextInt(13)
        val usedInFix = 4 + rnd.nextInt(7)
        setEnvData("gnss", org.json.JSONObject().apply {
            put("satelliteCount", satelliteCount)
            put("usedInFix", usedInFix.coerceAtMost(satelliteCount))
        })
        setEnvEnabled("gnss", true)

        val result = org.json.JSONObject().apply {
            put("location", org.json.JSONObject().apply {
                put("latitude", baseLat)
                put("longitude", baseLon)
                put("mode", "single")
            })
            put("cell", cellEntries)
            put("wifi", wifiNetworks)
            put("ble", bleDevices)
            put("sensor", org.json.JSONObject().apply { put("stepFrequency", stepFrequency) })
            put("gnss", org.json.JSONObject().apply {
                put("satelliteCount", satelliteCount)
                put("usedInFix", usedInFix)
            })
        }
        ZLog.i(TAG_SCOPE, "random env generated lat=$baseLat lon=$baseLon cell=${cellEntries.length()} wifi=${wifiNetworks.length()} ble=${bleDevices.length()}")
        return result
    }

    /** 清除指定类型的虚拟环境。 */
    fun clearEnv(type: String) {
        when (type) {
            "wifi" -> {
                wifiEngine.clear()
                activeEnvSnapshotIds.remove("wifi")
            }
            "cell" -> {
                cellEngine.clear()
                activeEnvSnapshotIds.remove("cell")
            }
            "ble" -> {
                bleEngine.clear()
                activeEnvSnapshotIds.remove("ble")
            }
            "gnss" -> {
                gnssEngine.clear()
                activeEnvSnapshotIds.remove("gnss")
            }
            "sensor" -> {
                sensorEngine.clear()
                activeEnvSnapshotIds.remove("sensor")
            }
            "collect" -> {
                wifiEngine.clear()
                cellEngine.clear()
                bleEngine.clear()
                gnssEngine.clear()
                sensorEngine.clear()
                activeEnvSnapshotIds.keys.removeAll(
                    setOf("wifi", "cell", "ble", "gnss", "sensor")
                )
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

    fun setRecordingPlaybackSpeed(speed: Float) {
        recordingEngine.setPlaybackSpeed(speed)
    }

    fun recordingStatusJson(): org.json.JSONObject {
        return recordingEngine.statusJson()
    }
}
