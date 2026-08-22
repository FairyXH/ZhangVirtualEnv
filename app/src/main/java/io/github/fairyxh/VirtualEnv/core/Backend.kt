package io.github.fairyxh.VirtualEnv.core

import android.location.Location
import io.github.fairyxh.VirtualEnv.core.engine.LocationEngine
import io.github.fairyxh.VirtualEnv.core.engine.SinglePointLocationEngine
import io.github.fairyxh.VirtualEnv.core.engine.EnvStateEngine
import io.github.fairyxh.VirtualEnv.core.engine.RouteEngine
import io.github.fairyxh.VirtualEnv.core.engine.JoystickEngine
import io.github.fairyxh.VirtualEnv.core.engine.RecordingEngine
import io.github.fairyxh.VirtualEnv.core.model.LocationState
import io.github.fairyxh.VirtualEnv.core.gnss.GNSSSimulationEngine
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
    val routeEngine = RouteEngine(jitterEnabled = { configManager.isJitterEnabled() })

    /** 悬浮窗摇杆引擎：位移叠加在路线/单点输出之上。 */
    val joystickEngine = JoystickEngine()

    /** 虚拟 WiFi / 基站 / BLE 环境状态（Hook 层只读快照）。 */
    val wifiEngine = EnvStateEngine("wifi")
    val cellEngine = EnvStateEngine("cell")
    val bleEngine = EnvStateEngine("ble")

    /** 虚拟 GNSS / 传感器（步频）状态（Phase 4 接入 Hook，状态由同一引擎管理）。 */
    val gnssEngine = EnvStateEngine("gnss")
    /** GNSS geometry/signal model. It never owns a position; Backend supplies the current snapshot. */
    val gnssSimulationEngine = GNSSSimulationEngine()
    val sensorEngine = EnvStateEngine("sensor")

    /** 虚拟 SIM 卡身份 / 信号状态（com.android.phone / system_server Hook 读取）。 */
    val simEngine = EnvStateEngine("sim")

    /** 环境引擎类型清单（持久化/恢复/状态展示共用）。 */
    private val ENV_ENGINE_TYPES = listOf("wifi", "cell", "ble", "gnss", "sensor", "sim")

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


    // ---------- Hook 层真实数据观测（采集检验） ----------

    /** 开启 Hook 层观测：清空快照并开始记录各 Hook 点经手的真实数据。 */
    fun beginHookObserve() {
        HookObserver.begin()
    }

    /** 结束 Hook 层观测（保留最后一次快照供读取）。 */
    fun endHookObserve() {
        HookObserver.end()
    }

    /**
     * 读取 Hook 层观测快照。
     *
     * 采集挂起（suspendAll 生效）时补充拉取真实基站：system_server 直连 phone
     * Binder（ITelephony.getAllCellInfo），phone 进程 Hook 在虚拟化关闭时放行
     * 原始真实数据，保证基站采集也有 Hook 层证据而不依赖 TelephonyRegistry 推送。
     */
    fun hookObserveSnapshotJson(): org.json.JSONObject {
        if (isSuspended()) {
            HookObserver.pullRealCellsFromPhone()
        }
        return HookObserver.snapshotJson()
    }

    // ---------- 模块总开关（总闸） ----------

    @Volatile
    private var moduleEnabled: Boolean = true

    /** 总开关关闭前的完整状态快照（重新开启时恢复）。 */
    @Volatile
    private var preMasterOffSnapshot: org.json.JSONObject? = null

    /** 模块 APK 路径（用于读取 scope.list 生成报告）。 */
    @Volatile
    private var moduleApkPath: String? = null

    /** 模块版本号（报告适配用）。 */
    @Volatile
    private var moduleVersion: String = ""

    fun setModuleApkPath(path: String?) {
        moduleApkPath = path
    }

    fun setModuleVersion(version: String) {
        moduleVersion = version
    }

    fun moduleVersion(): String = moduleVersion

    /** 模块总开关是否开启（关闭时 Hook 全部放行真实数据）。 */
    fun isModuleEnabled(): Boolean = moduleEnabled

    /**
     * 模块总开关：关闭时一键停用所有功能（位置/路线/摇杆/全部环境引擎/Sim 固化），
     * 数据保留在引擎内；重新开启时恢复关闭前的完整状态。
     */
    fun setModuleEnabled(enabled: Boolean) {
        synchronized(this) {
            if (enabled == moduleEnabled) return
            moduleEnabled = enabled
            configManager.setModuleEnabled(enabled)
            if (enabled) {
                val snap = preMasterOffSnapshot
                preMasterOffSnapshot = null
                if (snap != null) {
                    applyEnvStateSnapshot(snap)
                }
                ZLog.i(TAG_SCOPE, "module master enabled (restored)")
            } else {
                preMasterOffSnapshot = envStateSnapshotJson()
                locationEngine.setEnabled(false)
                routeEngine.stop()
                joystickEngine.setVector(false, 0.0, 0.0, 5.0)
                wifiEngine.setEnabled(false)
                cellEngine.setEnabled(false)
                bleEngine.setEnabled(false)
                gnssEngine.setEnabled(false)
                sensorEngine.setEnabled(false)
                simEngine.setEnabled(false)
                CarrierConfigPersister.resetAll()
                ENV_ENGINE_TYPES.forEach { persistEnvState(it) }
                notifySensorBackendConfigChanged()
                ZLog.i(TAG_SCOPE, "module master disabled: all engines stopped (data retained)")
            }
        }
    }

    fun moduleStatusJson(): org.json.JSONObject {
        return org.json.JSONObject().apply {
            put("enabled", moduleEnabled)
        }
    }

    // ---------- Hook 状态报告（各作用域 Hook 点状态） ----------

    /** 其他进程（phone/bluetooth/app）上报的 Hook 状态快照。 */
    private val hookStatusByProcess = java.util.concurrent.ConcurrentHashMap<String, org.json.JSONObject>()

    fun reportHookStatus(process: String, status: org.json.JSONObject) {
        if (process.isBlank()) return
        hookStatusByProcess[process] = status
        ZLog.i(
            TAG_SCOPE,
            "hook status reported process=$process hooked=${status.optInt("hooked")} failed=${status.optInt("failed")}"
        )
    }

    /** 汇总各作用域 Hook 状态（system_server 实时 + 其他进程上报）。 */
    fun hookStatusJson(): org.json.JSONObject {
        val processes = org.json.JSONObject()
        processes.put("system", io.github.fairyxh.VirtualEnv.core.HookStatusRegistry.snapshot())
        hookStatusByProcess.forEach { (k, v) -> processes.put(k, v) }
        return org.json.JSONObject().apply {
            put("exportedAt", System.currentTimeMillis())
            put("moduleEnabled", moduleEnabled)
            put("moduleVersion", moduleVersion)
            put("device", deviceInfoJson())
            put("profile", profileInfoJson())
            put("scopes", scopeListJson())
            put("processes", processes)
        }
    }

    /** 设备信息（报告适配用）。 */
    fun deviceInfoJson(): org.json.JSONObject {
        return org.json.JSONObject().apply {
            put("model", android.os.Build.MODEL)
            put("manufacturer", android.os.Build.MANUFACTURER)
            put("device", android.os.Build.DEVICE)
            put("product", android.os.Build.PRODUCT)
            put("fingerprint", android.os.Build.FINGERPRINT)
            put("sdk", android.os.Build.VERSION.SDK_INT)
            put("release", android.os.Build.VERSION.RELEASE)
        }
    }

    /** 从模块 APK 读取 scope.list（作用域清单）。 */
    private fun scopeListJson(): org.json.JSONArray {
        val arr = org.json.JSONArray()
        try {
            val apkPath = moduleApkPath
            if (apkPath != null) {
                java.util.zip.ZipFile(apkPath).use { zip ->
                    val entry = zip.getEntry("assets/META-INF/xposed/scope.list")
                        ?: zip.getEntry("META-INF/xposed/scope.list")
                    if (entry != null) {
                        zip.getInputStream(entry).use { stream ->
                            stream.readBytes().toString(Charsets.UTF_8).lines()
                        }.map { it.trim() }.filter { it.isNotBlank() }.forEach { arr.put(it) }
                    }
                }
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "read scope list failed", t)
        }
        return arr
    }

    /** 完整调试报告（设置页导出用：Hook 状态 + 全引擎状态 + 配置 + 观测 + 测试报告）。 */
    fun fullDebugReportJson(): org.json.JSONObject {
        return org.json.JSONObject().apply {
            put("reportType", "module")
            put("exportedAt", System.currentTimeMillis())
            put("hookStatus", hookStatusJson())
            put("location", locationStatusJson())
            put("route", routeStatusJson())
            put("joystick", joystickStatusJson())
            put("env", envStatusJson())
            put("settings", settingsStatusJson())
            put("observe", HookObserver.snapshotJson())
            put("testReport", testReport ?: org.json.JSONObject())
            put("configExport", exportConfigJson())
        }
    }

    private fun start() {
        configManager = ConfigManager(dataDir)
        moduleEnabled = configManager.isModuleEnabled()
        databaseManager = DatabaseManager(File(dataDir, "zve.db"))
        timelineEngine = DefaultTimelineEngine()
        environmentManager = DefaultEnvironmentManager(databaseManager)
        locationEngine = SinglePointLocationEngine(jitterEnabled = { configManager.isJitterEnabled() })
        profileManager = ProfileManager(dataDir)
        recordingEngine = RecordingEngine(databaseManager, this)

        // 启动兜底：system_server 重启/崩溃后，把未正常结束的录像按实际帧数据收尾，
        // 保留已录制内容（时长/帧数修正并标记中断）。
        try {
            val recovered = recordingEngine.recoverInterruptedRecordings()
            if (recovered > 0) {
                ZLog.w(TAG_SCOPE, "interrupted recording recovery done: $recovered")
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "interrupted recording recovery failed", t)
        }

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
        // 恢复各环境引擎上次使用的配置（wifi/cell/ble/gnss/sensor/sim）
        restoreEnvStates()
        // 自动托管：各环境引擎的“最优配置”由 Backend 基于当前虚拟位置自动生成
        setupAutoEnvProviders()
        // 总开关持久化关闭：不恢复任何虚拟输出（Hook 全部放行真实数据）
        if (!moduleEnabled) {
            locationEngine.setEnabled(false)
            routeEngine.stop()
            joystickEngine.setVector(false, 0.0, 0.0, 5.0)
            wifiEngine.setEnabled(false)
            cellEngine.setEnabled(false)
            bleEngine.setEnabled(false)
            gnssEngine.setEnabled(false)
            sensorEngine.setEnabled(false)
            simEngine.setEnabled(false)
            CarrierConfigPersister.resetAll()
            ZLog.w(TAG_SCOPE, "module master is OFF from persisted config: all engines disabled")
        }
        ZLog.i(TAG_SCOPE, "Backend components started")
    }

    // ---------- 自动托管（Auto Managed Environment） ----------

    /**
     * 为环境引擎注入自动数据生成器。勾选“自动托管”后，用户配置不再直接生效，
     * Hook 层读到的是基于当前虚拟位置自动生成的最优配置：
     * - GNSS：卫星数充足（usedInFix > 2），百度等 SDK 才会采纳 GPS fix
     * - Cell：带虚拟坐标且 ID 合法的基站（CDMA fallback / 派生基站）
     * - WiFi：基于虚拟位置派生的合法扫描列表（不阻断网络定位数据源）
     * - BLE / Sensor：合法默认值
     */
    private fun setupAutoEnvProviders() {
        wifiEngine.autoDataProvider = { autoWifiData() }
        cellEngine.autoDataProvider = { autoCellData() }
        bleEngine.autoDataProvider = { autoBleData() }
        gnssEngine.autoDataProvider = { autoGnssData() }
        sensorEngine.autoDataProvider = { autoSensorData() }
        ZLog.i(TAG_SCOPE, "auto env providers configured")
    }

    /** 自动托管基站缓存：换点后由 app 端经 /api/cell/auto 写入的 OpenCellID 真实基站。 */
    private val autoCellCache = java.util.concurrent.atomic.AtomicReference<org.json.JSONObject?>(null)

    /** 设置自动托管基站缓存（OpenCellID 查询结果；null = 附近无基站，返回空基站）。 */
    fun setAutoCellCache(data: org.json.JSONObject?) {
        autoCellCache.set(data)
        ZLog.i(TAG_SCOPE, "auto cell cache set entries=${data?.optJSONArray("entries")?.length() ?: 0}")
    }

    /** 自动托管：优先返回 OpenCellID 真实基站；未查询到则返回空基站（附近无基站）。 */
    private fun autoCellData(): org.json.JSONObject {
        return autoCellCache.get() ?: org.json.JSONObject().apply { put("entries", org.json.JSONArray()) }
    }

    /** 自动托管：derive GNSS geometry and signal fields from the authoritative virtual Location. */
    private fun autoGnssData(): org.json.JSONObject {
        val location = currentLocation() ?: return org.json.JSONObject().apply { put("satellites", org.json.JSONArray()) }
        return gnssSimulationEngine.toJson(location)
    }

    /** 自动托管：WiFi 扫描列表（基于虚拟位置派生，BSSID/SSID 合法）。 */
    private fun autoWifiData(): org.json.JSONObject {
        val loc = currentLocation()
        val lat = loc?.latitude ?: 24.6
        val lon = loc?.longitude ?: 118.0
        val seed = ((lat * 1e6).toLong() * 31 + (lon * 1e6).toLong()).toInt()
        val rnd = java.util.Random(seed.toLong())
        val networks = org.json.JSONArray()
        val count = 3 + rnd.nextInt(3)
        for (i in 0 until count) {
            // 常见真实 OUI 前缀（单播、非本地管理），避免 02: 本地管理地址被过滤
            val oui = listOf(0x00, 0x04, 0x8C, 0xA4, 0x34, 0x38)[rnd.nextInt(6)]
            val bssid = String.format(
                "%02X:%02X:%02X:%02X:%02X:%02X",
                oui, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)
            )
            val ssid = listOf("ChinaNet-", "CMCC-", "TP-LINK_", "ZVE-Auto-")[rnd.nextInt(4)] + (1000 + rnd.nextInt(9000))
            networks.put(org.json.JSONObject().apply {
                put("ssid", ssid)
                put("bssid", bssid)
                put("rssi", -40 - rnd.nextInt(40))
                put("frequency", if (rnd.nextBoolean()) 2412 + rnd.nextInt(5) * 5 else 5180 + rnd.nextInt(4) * 5)
                put("capabilities", "[WPA2-PSK-CCMP]")
            })
        }
        return org.json.JSONObject().apply { put("networks", networks) }
    }

    /** 自动托管：BLE 设备（2 个合法 beacon）+ 适配器身份（MAC/名称，VirtualRegion 同款覆盖）。 */
    private fun autoBleData(): org.json.JSONObject {
        val loc = currentLocation()
        val seed = ((loc?.latitude ?: 24.6) * 1e6).toLong()
        val rnd = java.util.Random(seed)
        val devices = org.json.JSONArray()
        for (i in 0 until 2) {
            val addr = String.format(
                "AA:%02X:%02X:%02X:%02X:%02X",
                rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)
            )
            devices.put(org.json.JSONObject().apply {
                put("name", "ZVE-Beacon-${100 + rnd.nextInt(900)}")
                put("address", addr)
                put("rssi", -60 - rnd.nextInt(25))
            })
        }
        // 适配器身份：确定性虚拟 MAC（locally administered）+ 名称，供 BluetoothIdentityHookAdapter 使用
        val lat = loc?.latitude ?: 24.6
        val lon = loc?.longitude ?: 118.0
        val macSeed = ((lat * 1e5).toLong() shl 16) xor (lon * 1e5).toLong()
        val crc = java.util.zip.CRC32().apply { update(macSeed.toString().toByteArray()) }.value
        val adapterMac = String.format(
            "02:00:00:%02x:%02x:%02x",
            (crc shr 16) and 0xff,
            (crc shr 8) and 0xff,
            crc and 0xff
        ).uppercase()
        return org.json.JSONObject().apply {
            put("devices", devices)
            put("adapterMac", adapterMac)
            put("adapterName", "ZVE-BT-${adapterMac.takeLast(6).replace(":", "")}")
        }
    }

    /** 自动托管：传感器步频默认值。 */
    private fun autoSensorData(): org.json.JSONObject {
        return org.json.JSONObject().apply { put("stepFrequency", 110) }
    }

    /** 设置某环境类型自动托管开关（用户配置保留，但 currentData 改为自动生成）。 */
    fun setEnvAutoManaged(type: String, auto: Boolean): Boolean {
        val engine = envEngine(type) ?: return false
        engine.setAutoManaged(auto)
        persistEnvState(type)
        ZLog.i(TAG_SCOPE, "env type=$type autoManaged=$auto")
        return true
    }

    // ---------- Location API（Hook Adapter 调用） ----------

    /** Hook 层数据入口：路线运行时输出路线位置，否则输出单点；未启用时 null（放行真实数据）。摇杆开启时在基准位置叠加位移。 */
    fun currentLocation(): Location? {
        // 模块总开关关闭：Hook 放行真实数据
        if (!moduleEnabled) return null
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
        if (enabled && !moduleEnabled) {
            ZLog.w(TAG_SCOPE, "setLocationEnabled ignored: module master OFF")
            return
        }
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
            simEngine.clear()
            CarrierConfigPersister.resetAll()
            activeEnvSnapshotIds.keys.removeAll(
                setOf("wifi", "cell", "ble", "gnss", "sensor", "sim")
            )
            activeCollectSnapshotName = ""
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
            put("sim", simEngine.statusJson())
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
            applyEnvSnapshotEngine(snap, "sim", simEngine)
            ZLog.i(TAG_SCOPE, "env state snapshot applied (${snap.length()} groups)")
        }
    }

    private fun applyEnvSnapshotEngine(snap: org.json.JSONObject, key: String, engine: EnvStateEngine) {
        val status = snap.optJSONObject(key) ?: return
        val data = status.optJSONObject("data")
        if (status.optBoolean("enabled", false) && data != null) {
            engine.update(data)
            if (key == "sim") persistSimConfig(data)
        } else {
            engine.clear()
            if (key == "sim") CarrierConfigPersister.resetAll()
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
                    put("sim", simEngine.statusJson())
                }
                // 停止所有虚拟输出，Hook 层立即放行真实数据
                setLocationEnabled(false)
                routeEngine.stop()
                wifiEngine.clear()
                cellEngine.clear()
                bleEngine.clear()
                gnssEngine.clear()
                sensorEngine.clear()
                simEngine.clear()
                CarrierConfigPersister.resetAll()
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
                    restoreEngine(snap, "sim", simEngine)
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
        if (status.optBoolean("enabled", false) && data != null) {
            engine.update(data)
            if (key == "sim") persistSimConfig(data)
        } else {
            engine.clear()
            if (key == "sim") CarrierConfigPersister.resetAll()
        }
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

    /** 悬浮窗/App 最近一次使用的路线循环配置（start 未传时沿用，避免悬浮窗启动丢失循环设置）。 */
    @Volatile
    private var lastRouteLoop = false

    @Volatile
    private var lastRouteSmoothReturn = false

    /** 一键启动路线模拟：加载路线点并按速度开始播放。与单点虚拟定位互斥。loop/smoothReturn 传 null 时沿用上次配置。 */
    fun startRoute(
        id: Long,
        speedKmh: Double,
        stepFrequency: Int = 0,
        loop: Boolean? = null,
        smoothReturn: Boolean? = null
    ): org.json.JSONObject? {
        val route = databaseManager.getRoute(id) ?: return null
        if (!moduleEnabled) {
            ZLog.w(TAG_SCOPE, "startRoute ignored id=$id: module master OFF")
            return null
        }
        // 互斥：启动路线模拟时关闭单点虚拟定位
        if (locationEngine.isEnabled()) {
            setLocationEnabled(false)
        }
        val points = route.optString("points", "")
        val speed = if (speedKmh > 0) speedKmh else route.optDouble("speed", 3.5)
        val stepFreq = if (stepFrequency > 0) stepFrequency else route.optInt("stepFrequency", 120)
        val useLoop = loop ?: lastRouteLoop
        val useSmooth = smoothReturn ?: lastRouteSmoothReturn
        lastRouteLoop = useLoop
        lastRouteSmoothReturn = useSmooth
        routeEngine.start(points, speed, stepFreq, useLoop, useSmooth)
        ZLog.i(TAG_SCOPE, "route started id=$id speed=$speed stepFrequency=$stepFreq loop=$useLoop smoothReturn=$useSmooth")
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

    /** 更新路线运行参数：speedKmh/stepFrequency 传 0 表示不修改；loop/smoothReturn 传 null 表示不修改。 */
    fun configRoute(
        speedKmh: Double,
        stepFrequency: Int,
        loop: Boolean? = null,
        smoothReturn: Boolean? = null
    ) {
        routeEngine.config(speedKmh, stepFrequency, loop, smoothReturn)
        if (loop != null) lastRouteLoop = loop
        if (smoothReturn != null) lastRouteSmoothReturn = smoothReturn
        ZLog.i(TAG_SCOPE, "route config speedKmh=$speedKmh stepFrequency=$stepFrequency loop=${loop ?: lastRouteLoop} smoothReturn=${smoothReturn ?: lastRouteSmoothReturn}")
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
        if (enabled && !moduleEnabled) {
            ZLog.w(TAG_SCOPE, "setJoystickVector ignored: module master OFF")
            return
        }
        joystickEngine.setVector(enabled, dx, dy, speedKmh)
    }

    fun joystickStatusJson(): org.json.JSONObject {
        return joystickEngine.statusJson()
    }

    /** 显式复位摇杆位移（回基准位置）。 */
    fun resetJoystickOffset() {
        joystickEngine.resetOffset()
    }

    // ---------- 设置（随机抖动等） ----------

    /** 随机抖动开关（单点/路线模拟共用，默认开启）。 */
    fun isJitterEnabled(): Boolean = configManager.isJitterEnabled()

    fun setJitterEnabled(enabled: Boolean) {
        configManager.setJitterEnabled(enabled)
        ZLog.i(TAG_SCOPE, "settings jitterEnabled=$enabled")
    }

    fun settingsStatusJson(): org.json.JSONObject {
        return org.json.JSONObject().apply {
            put("jitterEnabled", configManager.isJitterEnabled())
        }
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
        val point = databaseManager.getLocationPoint(id)
        if (point == null) {
            ZLog.w(TAG_SCOPE, "useLocationPoint rejected id=$id: location point missing")
            return null
        }
        if (!moduleEnabled) {
            ZLog.w(TAG_SCOPE, "useLocationPoint rejected id=$id: module master OFF")
            return null
        }
        val latitude = point.optDouble("latitude", Double.NaN)
        val longitude = point.optDouble("longitude", Double.NaN)
        if (!latitude.isFinite() || !longitude.isFinite()) {
            ZLog.w(TAG_SCOPE, "useLocationPoint rejected id=$id: invalid coordinates")
            return null
        }
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

    @Volatile
    private var activeCollectSnapshotName = ""

    /** 一键使用环境快照：把已保存的 env_snapshot 数据加载到对应模拟引擎。 */
    fun useEnvSnapshot(id: Long): org.json.JSONObject? {
        if (!moduleEnabled) {
            ZLog.w(TAG_SCOPE, "useEnvSnapshot ignored id=$id: module master OFF")
            return null
        }
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
             "sim" -> {
                 simEngine.update(data)
                 activeEnvSnapshotIds["sim"] = id
                 // Nrfr 同款固化：CarrierConfig 持久化覆盖（禁用框架后仍生效）
                 persistSimConfig(data)
             }
             "collect" -> {
                 activeCollectSnapshotName = snapshot.optString("name", "")
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
             if (type in ENV_ENGINE_TYPES) {
                 persistEnvState(type)
                 } else if (type == "collect") {
            ENV_ENGINE_TYPES.forEach { persistEnvState(it) }
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
            "sensor" -> {
                sensorEngine.update(data)
                notifySensorBackendConfigChanged()
            }
            "sim" -> {
                simEngine.update(data)
                // Nrfr 同款固化：CarrierConfig 持久化覆盖（禁用框架后仍生效）
                persistSimConfig(data)
            }
            else -> return false
        }
        persistEnvState(type)
        ZLog.i(TAG_SCOPE, "env data set type=$type keys=${data.length()}")
        return true
    }

    /** 传感器配置/开关变化：通知后端管理器重新选择（system_server 全局后端或回退）。 */
    private fun notifySensorBackendConfigChanged() {
        try {
            if (io.github.fairyxh.VirtualEnv.core.sensor.SensorBackendManager.isInitialized()) {
                val config = io.github.fairyxh.VirtualEnv.core.sensor.VirtualSensorConfig
                    .fromStatus(sensorEngine.statusJson())
                io.github.fairyxh.VirtualEnv.core.sensor.SensorBackendManager.updateConfig(config)
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "notify sensor backend config change failed", t)
        }
    }

    /** 将 SIM 配置的每个卡槽固化到 CarrierConfig（Nrfr 接口：ICarrierConfigLoader.overrideConfig）。 */
    private fun persistSimConfig(data: org.json.JSONObject) {
        val slots = data.optJSONArray("slots") ?: return
        for (i in 0 until slots.length()) {
            val slot = slots.optJSONObject(i) ?: continue
            CarrierConfigPersister.applySlot(slot)
        }
    }

    /** 按类型取环境引擎；非环境类型返回 null。 */
    private fun envEngine(type: String): EnvStateEngine? = when (type) {
        "wifi" -> wifiEngine
        "cell" -> cellEngine
        "ble" -> bleEngine
        "gnss" -> gnssEngine
        "sensor" -> sensorEngine
        "sim" -> simEngine
        else -> null
    }

    /** 持久化某类型环境引擎的上次配置（含开关/数据/来源快照）。 */
    private fun persistEnvState(type: String) {
        val engine = envEngine(type) ?: return
        try {
            val status = engine.statusJson()
            val data = engine.userData() ?: status.optJSONObject("data")
            val enabled = status.optBoolean("enabled", false)
            val autoManaged = status.optBoolean("autoManaged", false)
            val snapshotId = activeEnvSnapshotIds[type] ?: -1L
            databaseManager.saveEnvState(
                type,
                enabled,
                data?.toString() ?: "",
                snapshotId,
                autoManaged
            )
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "persist env state type=$type failed", t)
        }
    }

    /** 启动时恢复各环境引擎上次使用的配置（enabled=true 自动生效，Hook 立即读到虚拟值）。 */
    private fun restoreEnvStates() {
        try {
            databaseManager.loadEnvStates().forEach { row ->
                val type = row.optString("type", "")
                val engine = envEngine(type) ?: return@forEach
                val data = row.optJSONObject("data") ?: return@forEach
                val enabled = row.optBoolean("enabled", false)
                engine.update(data)
                if (!enabled) engine.setEnabled(false)
                val autoManaged = row.optBoolean("autoManaged", false)
                if (autoManaged) engine.setAutoManaged(true)
                val snapshotId = row.optLong("snapshotId", -1L)
                if (snapshotId >= 0) activeEnvSnapshotIds[type] = snapshotId
                if (type == "sim") {
                    if (enabled) {
                        persistSimConfig(data)
                    } else {
                        CarrierConfigPersister.resetAll()
                    }
                }
                ZLog.i(TAG_SCOPE, "env state restored type=$type enabled=$enabled")
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "restore env states failed", t)
        }
    }

    /** 单类型开关：关闭时 Hook 放行真实数据，数据保留；开启时恢复。 */
    fun setEnvEnabled(type: String, enabled: Boolean): Boolean {
        if (enabled && !moduleEnabled) {
            ZLog.w(TAG_SCOPE, "setEnvEnabled ignored type=$type: module master OFF")
            return false
        }
        when (type) {
            "wifi" -> wifiEngine.setEnabled(enabled)
            "cell" -> cellEngine.setEnabled(enabled)
            "ble" -> bleEngine.setEnabled(enabled)
            "gnss" -> gnssEngine.setEnabled(enabled)
            "sensor" -> {
                sensorEngine.setEnabled(enabled)
                notifySensorBackendConfigChanged()
            }
            "sim" -> {
                simEngine.setEnabled(enabled)
                if (enabled) {
                    // 重新启用时恢复持久化固化（数据仍保留在引擎内）
                    simEngine.currentData()?.let { persistSimConfig(it) }
                } else {
                    // 关闭 = Hook 放行真实数据，同时还原 CarrierConfig 持久化覆盖
                    CarrierConfigPersister.resetAll()
                }
            }
            else -> return false
        }
        persistEnvState(type)
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
            "sensor" -> {
                val s = sensorEngine.statusJson()
                // 附加后端状态：UI 展示当前模式（SYSTEM / LEGACY / NONE）
                try {
                    s.put("backendStatus", io.github.fairyxh.VirtualEnv.core.sensor.SensorBackendManager.statusJson())
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "append sensor backend status failed", t)
                }
                s
            }
            "sim" -> simEngine.statusJson()
            else -> return null
        }
        val snapshotId = activeEnvSnapshotIds[type] ?: -1L
        status.put("activeSnapshotId", snapshotId)
        if (snapshotId >= 0) {
            try {
                val name = databaseManager.queryEnvSnapshots()
                    .firstOrNull { it.optLong("id", -1L) == snapshotId }
                    ?.optString("name", "")
                if (!name.isNullOrEmpty()) status.put("snapshotName", name)
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "env status snapshot name failed", t)
            }
        }
        return status
    }

    /** 当前生效测试配置名称，供主页状态卡显示。 */
    fun currentUsingName(): String {
        val collectName = activeCollectSnapshotName.trim()
        if (collectName.isNotEmpty()) return collectName
        return listOf("wifi", "cell", "ble", "gnss", "sensor", "sim")
            .mapNotNull { type ->
                val status = envStatus(type)
                status?.takeIf { it.optBoolean("enabled", false) }
                    ?.optString("snapshotName", "")
                    ?.takeIf { it.isNotBlank() }
            }
            .distinct()
            .joinToString("、")
    }

    /**
     * 调试辅助：生成全套随机虚拟环境并全部启用。
     *
     * 供检测器/自动化脚本快速验证 Hook 全链路（位置+基站+WiFi+BLE+传感器+GNSS）。
     * 返回生成的完整配置 JSON（检测器可据此判定 PASS/FAIL）。
     */
    fun generateRandomEnv(): org.json.JSONObject {
        if (!moduleEnabled) {
            ZLog.w(TAG_SCOPE, "generateRandomEnv ignored: module master OFF")
            return org.json.JSONObject().apply { put("disabled", true) }
        }
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
            put("pci", rnd.nextInt(0, 1008))
            // NCI 36bit 合法范围 0..68719476735（CellIdentityNr.MAX_NCI）；超出会被
            // inRangeOrUnavailable 归一化为 Long.MAX_VALUE（读回 9223372036854775807）
            put("nci", rnd.nextLong(1, 68719476736L))
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
        setEnvData("ble", org.json.JSONObject().apply {
            put("devices", bleDevices)
            // 适配器身份（随机模拟时一并生成，检测器可校验 BluetoothAdapter.getAddress/getName）
            val macSeed = ((baseLat * 1e5).toLong() shl 16) xor (baseLon * 1e5).toLong()
            val crc = java.util.zip.CRC32().apply { update(macSeed.toString().toByteArray()) }.value
            val adapterMac = String.format(
                "02:00:00:%02x:%02x:%02x",
                (crc shr 16) and 0xff,
                (crc shr 8) and 0xff,
                crc and 0xff
            ).uppercase()
            put("adapterMac", adapterMac)
            put("adapterName", "ZVE-BT-${adapterMac.takeLast(6).replace(":", "")}")
        })
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
            put("nmeaEnabled", true)
        })
        setEnvEnabled("gnss", true)

        // SIM：随机国家模板（默认中国）生成虚拟 SIM 身份 + 信号
        val simCountry = org.json.JSONObject().apply {
            put("iso", "CN")
            put("nameZh", "中国")
            put("mcc", "460")
            put("defaultMnc", "00")
            put("carrier", "中国移动")
            put("imsiPrefix", "46000")
            put("iccidPrefix", "898600")
        }
        val simMnc = rnd.nextInt(2) * 11 + rnd.nextInt(10)
        val simMcc = simCountry.optString("mcc", "460")
        val simMncStr = String.format("%02d", simMnc)
        val imsi = simCountry.optString("imsiPrefix", "46000") + rnd.nextLong(100000000, 999999999)
        val iccid = simCountry.optString("iccidPrefix", "898600") + rnd.nextLong(100000000000L, 999999999999L)
        val slots = org.json.JSONArray().put(
            org.json.JSONObject().apply {
                put("slotIndex", 0)
                put("subId", 1)
                put("enabled", true)
                put("simState", 5) // SIM_STATE_READY
                put("phoneType", 1) // PHONE_TYPE_GSM
                put("mcc", simMcc)
                put("mnc", simMncStr)
                put("countryIso", "cn")
                put("simCountryIso", "cn")
                put("networkCountryIso", "cn")
                put("simOperatorName", simCountry.optString("carrier", "China Mobile"))
                put("networkOperatorName", simCountry.optString("carrier", "China Mobile"))
                put("subscriberId", imsi)
                put("simSerialNumber", iccid)
                put("line1Number", "+86138" + String.format("%08d", rnd.nextInt(100000000)))
                put("deviceId", imsi)
                put("imei", String.format("%015d", rnd.nextLong(100000000000000L, 999999999999999L)))
                put("signal", org.json.JSONObject().apply {
                    put("gsm", 18 + rnd.nextInt(10))
                    put("lte", -100 + rnd.nextInt(20))
                    put("nr", -115 + rnd.nextInt(20))
                    put("level", 2 + rnd.nextInt(3))
                })
            }
        )
        setEnvData("sim", org.json.JSONObject().apply { put("slots", slots) })
        setEnvEnabled("sim", true)

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
                put("nmeaEnabled", true)
            })
            put("sim", org.json.JSONObject().apply { put("slots", slots) })
        }
        ZLog.i(TAG_SCOPE, "random env generated lat=$baseLat lon=$baseLon cell=${cellEntries.length()} wifi=${wifiNetworks.length()} ble=${bleDevices.length()} sim=1")
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
            "sim" -> {
                simEngine.clear()
                activeEnvSnapshotIds.remove("sim")
                // 清除虚拟 SIM 时同时还原 CarrierConfig 持久化覆盖
                CarrierConfigPersister.resetAll()
            }
            "collect" -> {
                wifiEngine.clear()
                cellEngine.clear()
                bleEngine.clear()
                gnssEngine.clear()
                sensorEngine.clear()
                simEngine.clear()
                CarrierConfigPersister.resetAll()
                activeEnvSnapshotIds.keys.removeAll(
                    setOf("wifi", "cell", "ble", "gnss", "sensor", "sim")
                )
            }
        }
        if (type in ENV_ENGINE_TYPES) {
            databaseManager.deleteEnvState(type)
        } else if (type == "collect") {
            ENV_ENGINE_TYPES.forEach { databaseManager.deleteEnvState(it) }
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
            put("sensor", sensorEngine.statusJson().also { s ->
                // 附加后端状态：App 进程 EnvStateCache 依赖此字段决定是否抑制本地 Hook 注入
                try {
                    s.put("backendStatus", io.github.fairyxh.VirtualEnv.core.sensor.SensorBackendManager.statusJson())
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "append sensor backend status failed", t)
                }
            })
            put("sim", simEngine.statusJson())
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

    fun startRecording(name: String, remark: String, intervalMs: Long = 1000L): Long {
        val id = recordingEngine.startRecording(name, remark)
        recordingEngine.setRecordingInterval(intervalMs)
        beginHookObserve()
        suspendAll()
        recordingEngine.startCoreSampling(intervalMs)
        ZLog.i(TAG_SCOPE, "system-owned recording capture enabled id=$id intervalMs=${intervalMs.coerceIn(100L, 300_000L)}")
        return id
    }

    fun appendRecordingFrame(id: Long, frame: org.json.JSONObject): Boolean {
        return recordingEngine.appendFrame(frame)
    }

    fun stopRecording(id: Long): Boolean {
        if (id <= 0) return false
        val stopped = recordingEngine.stopRecording()
        if (!stopped) return false
        endHookObserve()
        resumeAll()
        return true
    }

    fun pauseRecording(): Boolean = recordingEngine.pauseRecording()

    fun resumeRecording(): Boolean = recordingEngine.resumeRecording()

    fun listRecordings(): List<org.json.JSONObject> {
        return recordingEngine.listRecordings()
    }

    fun getRecordingFrames(id: Long): List<org.json.JSONObject> {
        return recordingEngine.getFrames(id)
    }

    /** 分页取录像帧：返回 frames + total/firstTs/lastTs 元数据。 */
    fun getRecordingFramesPaged(id: Long, offset: Int, limit: Int): org.json.JSONObject {
        val data = org.json.JSONObject()
        data.put("frames", org.json.JSONArray(recordingEngine.getFramesPaged(id, offset, limit)))
        val range = recordingEngine.getFrameRange(id)
        data.put("total", range?.optInt("count", 0) ?: 0)
        data.put("firstTs", range?.optLong("firstTs", 0L) ?: 0L)
        data.put("lastTs", range?.optLong("lastTs", 0L) ?: 0L)
        return data
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

    /** 回放帧间平滑过渡开关（插值+抖动）。 */
    fun setRecordingPlaybackSmooth(enabled: Boolean) {
        recordingEngine.setSmoothLocation(enabled)
    }

    fun recordingStatusJson(): org.json.JSONObject {
        return recordingEngine.statusJson()
    }

    // ---------- 配置状态预设（主页快速保存/加载当前配置） ----------

    /** 保存当前完整配置状态（位置/路线/摇杆 + 环境六大板块）为预设。 */
    fun saveConfigPreset(name: String, remark: String): Long {
        val data = envStateSnapshotJson().toString()
        val id = databaseManager.insertConfigPreset(name, remark, data)
        ZLog.i(TAG_SCOPE, "config preset saved id=$id name=$name")
        return id
    }

    fun listConfigPresets(): List<org.json.JSONObject> {
        return databaseManager.queryConfigPresets()
    }

    fun renameConfigPreset(id: Long, name: String, remark: String): Boolean {
        return databaseManager.updateConfigPreset(id, name, remark)
    }

    fun deleteConfigPreset(id: Long): Boolean {
        return databaseManager.deleteConfigPreset(id)
    }

    /** 一键加载配置状态预设：应用保存时的完整虚拟配置（含持久化）。 */
    fun loadConfigPreset(id: Long): org.json.JSONObject? {
        if (!moduleEnabled) {
            ZLog.w(TAG_SCOPE, "loadConfigPreset ignored id=$id: module master OFF")
            return null
        }
        val preset = databaseManager.queryConfigPresets()
            .firstOrNull { it.optLong("id", -1L) == id }
            ?: return null
        val data = preset.optJSONObject("data") ?: return null
        applyEnvStateSnapshot(data)
        ZLog.i(TAG_SCOPE, "config preset loaded id=$id name=${preset.optString("name")}")
        return preset
    }

    // ---------- 配置整体导入导出（设置页备份） ----------

    /**
     * 导出模块整体配置：config.json + 路线 + 已保存地点 + 环境快照 +
     * 环境上次配置 + 配置状态预设（不含录像数据，录像帧体积大且属于录制数据）。
     */
    fun exportConfigJson(): org.json.JSONObject {
        return org.json.JSONObject().apply {
            put("version", 1)
            put("app", "ZhangVirtualEnv")
            put("exportedAt", System.currentTimeMillis())
            put("config", configManager.load())
            put("routes", org.json.JSONArray(databaseManager.queryRoutes()))
            put("locationPoints", org.json.JSONArray(databaseManager.queryLocationPoints()))
            put("envSnapshots", org.json.JSONArray(databaseManager.queryEnvSnapshots()))
            put("envStates", org.json.JSONArray(databaseManager.loadEnvStates()))
            put("presets", org.json.JSONArray(databaseManager.queryConfigPresets()))
        }
    }

    /** 导入模块整体配置：事务覆盖全部配置表并重新应用到运行引擎。 */
    fun importConfigJson(json: org.json.JSONObject): Boolean {
        return try {
            val config = json.optJSONObject("config") ?: org.json.JSONObject()
            val ok = databaseManager.replaceConfigData(
                routes = jsonArrayToObjects(json.optJSONArray("routes")),
                locationPoints = jsonArrayToObjects(json.optJSONArray("locationPoints")),
                envSnapshots = jsonArrayToObjects(json.optJSONArray("envSnapshots")),
                envStates = jsonArrayToObjects(json.optJSONArray("envStates")),
                presets = jsonArrayToObjects(json.optJSONArray("presets"))
            )
            if (!ok) return false
            configManager.saveRoot(config)
            reloadRuntimeConfig()
            ZLog.i(TAG_SCOPE, "config imported (routes/locationPoints/envSnapshots/envStates/presets)")
            true
        } catch (t: Throwable) {
            ZLog.e(TAG_SCOPE, "import config failed", t)
            false
        }
    }

    private fun jsonArrayToObjects(arr: org.json.JSONArray?): List<org.json.JSONObject> {
        val list = mutableListOf<org.json.JSONObject>()
        if (arr == null) return list
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { list.add(it) }
        }
        return list
    }

    /** 导入后把持久化配置重新应用到运行引擎（位置开关 + 环境引擎 + SIM 固化）。 */
    private fun reloadRuntimeConfig() {
        synchronized(this) {
            activeEnvSnapshotIds.clear()
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
            } else {
                locationEngine.setEnabled(false)
            }
            routeEngine.stop()
            joystickEngine.setVector(false, 0.0, 0.0, 5.0)
            restoreEnvStates()
            ZLog.i(TAG_SCOPE, "runtime config reloaded after import")
        }
    }
}
