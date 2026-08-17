package io.github.fairyxh.VirtualEnv.hook

import android.os.SystemClock
import io.github.fairyxh.VirtualEnv.core.Backend
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.util.concurrent.ConcurrentHashMap

/**
 * GNSS 数据虚拟注入 / 阻断 Adapter（百度地图拉回真实位置修复）。
 *
 * 背景（逆向 ZYH Dump Dex / BaiduLBS 9.1.6，见 docs/reverse/baidu-sdk-nmea-cellinfo-analysis.md）：
 * 百度定位 SDK 除了 requestLocationUpdates 之外，还直接注册三类系统级 GNSS 数据源：
 * 1. `LocationManager.registerGnssStatusCallback(...)` → Binder
 *    `LocationManagerService.registerGnssStatusCallback` → SDK 用 `usedInFix` 卫星数
 *    （com.baidu.location.c.f.a）判定 GPS 是否有效：`f(Location)` 中 `if (a > 2 ...)`
 *    才会上报 GPS 位置；室内真实卫星数为 0 → 虚拟 fix 被丢弃。
 * 2. `LocationManager.addNmeaListener(...)` → Binder
 *    `LocationManagerService.registerGnssNmeaCallback` → SDK 解析 `$GPGGA`/`$GPRMC`
 *    中的**真实经纬度**（com.baidu.location.c.f.a(String)），用于 NMEA 一致性校验
 *    （e(location) 返回 200/300/400/500 时 GPS fix 不被采纳）。
 * 3. `LocationManager.registerGnssNavigationMessageCallback(...)` → 导航消息上传服务器辅助解算。
 * 4. `LocationManager.registerGnssMeasurementsCallback(...)` → 原始观测数据。
 *
 * 策略（全部系统层，scope 不变，fail-open）：
 * - **虚拟定位启用时接管** registerGnssStatusCallback / registerGnssNmeaCallback：
 *   不注册真实回调，改为向 listener 周期投递**基于虚拟位置生成的 GnssStatus 与 NMEA**，
 *   使任意 App（含百度）获得“GPS 有 fix、卫星数充足、NMEA 与虚拟坐标一致”的完整假象，
 *   GPS 路径走正常分发（locType=61），不再拉回真实位置。
 * - 导航消息 / 原始测量直接**不注册**（阻断真实数据下传）。
 * - 虚拟定位未启用时完全放行原始行为。
 *
 * Hook 点（Oplus/Android 15 services.jar，LocationManagerService 为 Binder 实体）：
 * - registerGnssStatusCallback(IGnssStatusListener, String, String, String)
 * - registerGnssNmeaCallback(IGnssNmeaListener, String, String, String)
 * - addGnssNavigationMessageListener(IGnssNavigationMessageListener, String, String, String)
 * - addGnssMeasurementsListener(GnssMeasurementRequest, IGnssMeasurementsListener, String, String, String)
 */
class GnssDataBlockHookAdapter(
    private val backend: Backend,
    private val registrar: HookRegistrar,
) {

    companion object {
        private const val TAG_SCOPE = "Hook"
        private const val MANAGER_CLASS = "com.android.server.location.LocationManagerService"

        /** 默认虚拟卫星：总数 24，usedInFix 6（>2，满足百度 GPS 有效性判定）。 */
        private const val DEFAULT_SATELLITE_COUNT = 24
        private const val DEFAULT_USED_IN_FIX = 6
        private const val INJECT_INTERVAL_MS = 1000L
        private const val DEFAULT_CN0_DBHZ = 38f
    }

    /** 虚拟定位启用（单点或路线任一开启；采集暂停时放行）。 */
    private fun virtualLocationEnabled(): Boolean =
        backend.isModuleEnabled() &&
            !backend.isSuspended() &&
            (backend.locationEngine.isEnabled() || backend.routeEngine.isRunning())

    private val statusExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ZVE-GnssInject").apply { isDaemon = true }
    }

    /** listener -> 周期任务（GnssStatus 注入）。 */
    private val statusTasks = ConcurrentHashMap<Any, java.util.concurrent.ScheduledFuture<*>>()
    /** listener -> 周期任务（NMEA 注入）。 */
    private val nmeaTasks = ConcurrentHashMap<Any, java.util.concurrent.ScheduledFuture<*>>()

    /**
     * 所有已注册的 GnssStatus listener（含虚拟定位启用前的真实注册）。
     * value=true 表示当前由虚拟投递接管。
     */
    private val allStatusListeners = ConcurrentHashMap<Any, Boolean>()
    /** 所有已注册的 NMEA listener（含虚拟定位启用前的真实注册）。 */
    private val allNmeaListeners = ConcurrentHashMap<Any, Boolean>()

    /** 全局监听：虚拟定位启用边沿对既有 listener 补齐虚拟投递，关闭边沿停止投递。 */
    private val takeoverMonitor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ZVE-GnssTakeover").apply { isDaemon = true }
    }

    /** 节流：每个 listener 每 5s 最多打一条投递日志（release 保留 I 级）。 */
    private val lastStatusLog = ConcurrentHashMap<Any, Long>()
    private val lastNmeaLog = ConcurrentHashMap<Any, Long>()

    fun install(classLoader: ClassLoader): Int {
        val clazz = HookSupport.findClass(classLoader, MANAGER_CLASS) ?: return 0
        var hooked = 0
        hooked += hookGnssStatusRegister(classLoader, clazz)
        hooked += hookGnssStatusUnregister(classLoader, clazz)
        hooked += hookNmeaRegister(classLoader, clazz)
        hooked += hookNmeaUnregister(classLoader, clazz)
        hooked += blockRegister(classLoader, clazz, "addGnssNavigationMessageListener", 4)
        hooked += blockRegister(classLoader, clazz, "addGnssMeasurementsListener", 5)
        hooked += hookOldGpsStatusTransport(classLoader)
        hooked += hookRealSvStatus(classLoader)
        hooked += hookRealNmea(classLoader)
        hooked += hookGnssManagerService(classLoader)
        startTakeoverMonitor()
        return hooked
    }

    /**
     * Oplus separates listener storage into GnssStatusProvider. Hook its service boundary too
     * when present, preventing a physical callback racing the virtual callback.
     */
    private fun hookGnssManagerService(classLoader: ClassLoader): Int {
        val clazz = HookSupport.findClass(classLoader, "com.android.server.location.gnss.GnssManagerService") ?: return 0
        val method = HookSupport.findMethods(clazz, "registerGnssStatusCallback")
            .firstOrNull { it.parameterCount >= 1 && it.parameterTypes[0].simpleName.contains("IGnssStatusListener") }
            ?: return 0
        val ok = registrar.register(method) { chain ->
            val listener = chain.getArg(0)
            if (!virtualLocationEnabled() || listener == null) return@register chain.proceed()
            allStatusListeners[listener] = true
            startStatusInject(listener)
            ZLog.i(TAG_SCOPE, "GnssManagerService register -> virtual-only listener=${listener.javaClass.name}")
            null
        }
        if (ok) {
            ZLog.i(TAG_SCOPE, "hooked GnssManagerService.registerGnssStatusCallback")
            return 1
        }
        return 0
    }

    /**
     * 旧 API addGpsStatusListener 的卫星数据写入点：
     * system_server 的 GpsStatusListenerTransport 收到真实 GnssStatus 后构造 GpsStatus
     * 并调用 GpsStatus.updateSatelliteStatus(...) 写入卫星数据，随后 Binder 回传给 App。
     * 虚拟定位启用时替换为虚拟卫星数据，让走旧 API 的 SDK（百度地图 locSDK8b、老版
     * BaiduLBS）getGpsStatus() 拿到虚拟卫星列表，usedInFix > 2 判定通过。
     */
    private fun hookOldGpsStatusTransport(classLoader: ClassLoader): Int {
        val gpsStatusClass = HookSupport.findClass(classLoader, "android.location.GpsStatus")
        if (gpsStatusClass == null) {
            ZLog.i(TAG_SCOPE, "old-api GpsStatus class NOT FOUND (fail-open)")
            return 0
        }
        // Android 15 的 GpsStatus 用 GpsStatus.create(GnssStatus, int) 工厂方法构造
        // （旧 API addGpsStatusListener 的转换点），无 updateSatelliteStatus。
        val methods = HookSupport.findMethods(gpsStatusClass, "create")
        ZLog.i(TAG_SCOPE, "old-api GpsStatus found, create candidates=${methods.size}")
        methods.forEach { method ->
            if (method.parameterCount != 2) return@forEach
            val ok = registrar.register(method) { chain ->
                // Hook 层真实数据观测：旧 API 转换前的真实 GnssStatus
                io.github.fairyxh.VirtualEnv.core.HookObserver.recordGnssStatus(chain.getArg(0))
                if (virtualLocationEnabled()) {
                    try {
                        val virtual = buildVirtualGnssStatus()
                        if (virtual != null) {
                            @Suppress("UNCHECKED_CAST")
                            (chain.getArgs() as MutableList<Any>)[0] = virtual
                            ZLog.i(TAG_SCOPE, "GpsStatus.create -> virtual GnssStatus (old-api bypass)")
                        }
                    } catch (t: Throwable) {
                        ZLog.w(TAG_SCOPE, "old-api gps status replace failed", t)
                    }
                }
                chain.proceed()
                null
            }
            if (ok) {
                ZLog.i(TAG_SCOPE, "hooked old-api GpsStatus.create")
                return 1
            }
        }
        ZLog.d(TAG_SCOPE, "old-api GpsStatus.create not found (fail-open)")
        return 0
    }

    /**
     * Hook 层真实数据观测：GnssLocationProvider.onReportNmea(long, String)。
     *
     * GNSS HAL 上报真实 NMEA 句子的入口（services.jar JADX 确认）。虚拟 NMEA 已在
     * registerGnssNmeaCallback 拦截层投递；此点仅记录真实句子供 Hook 层检验/采集包。
     */
    private fun hookRealNmea(classLoader: ClassLoader): Int {
        val clazz = HookSupport.findClass(
            classLoader,
            "com.android.server.location.gnss.GnssLocationProvider"
        ) ?: return 0
        val method = HookSupport.findMethods(clazz, "onReportNmea")
            .firstOrNull { it.parameterCount == 2 && it.parameterTypes[1] == String::class.java }
        if (method == null) {
            ZLog.d(TAG_SCOPE, "onReportNmea not found (fail-open)")
            return 0
        }
        val ok = registrar.register(method) { chain ->
            try {
                io.github.fairyxh.VirtualEnv.core.HookObserver.recordNmea(chain.getArg(1) as? String)
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "record nmea failed", t)
            }
            val virtual = if (virtualLocationEnabled()) backend.currentLocation() else null
            if (virtual != null) {
                val nmea = buildVirtualNmea(virtual.latitude, virtual.longitude)
                if (nmea != null) {
                    chain.proceed(arrayOf(chain.getArg(0), nmea))
                    ZLog.d(TAG_SCOPE, "GnssLocationProvider.onReportNmea -> virtual")
                } else {
                    chain.proceed()
                }
            } else {
                chain.proceed()
            }
            null
        }
        if (ok) {
            ZLog.i(TAG_SCOPE, "hooked GnssLocationProvider.onReportNmea (observe)")
            return 1
        }
        return 0
    }
    /**
     * Hook 层真实数据观测：GnssLocationProvider.onReportSvStatus(GnssStatus)。
     *
     * 这是 GNSS HAL 向 system_server 上报真实卫星状态的入口（services.jar JADX 确认），
     * 无论虚拟定位是否启用都会先经过这里，采集/检测期间可拿到真实卫星数。
     */
    private fun hookRealSvStatus(classLoader: ClassLoader): Int {
        val clazz = HookSupport.findClass(
            classLoader,
            "com.android.server.location.gnss.GnssLocationProvider"
        ) ?: return 0
        val method = HookSupport.findMethods(clazz, "onReportSvStatus")
            .firstOrNull {
                it.parameterCount == 1 && it.parameterTypes[0].name == "android.location.GnssStatus"
            }
        if (method == null) {
            ZLog.d(TAG_SCOPE, "GnssLocationProvider.onReportSvStatus not found (observe skip)")
            return 0
        }
        val ok = registrar.register(method) { chain ->
            val original = chain.getArg(0)
            io.github.fairyxh.VirtualEnv.core.HookObserver.recordGnssStatus(original)
            val virtual = if (virtualLocationEnabled()) buildVirtualGnssStatus() else null
            if (virtual != null) {
                chain.proceed(arrayOf(virtual))
                ZLog.d(TAG_SCOPE, "GnssLocationProvider.onReportSvStatus -> virtual")
            } else {
                chain.proceed()
            }
            null
        }
        if (ok) {
            ZLog.i(TAG_SCOPE, "hooked GnssLocationProvider.onReportSvStatus (observe)")
            return 1
        }
        return 0
    }

    /** 1s 轮询：虚拟定位启用/关闭边沿时对既有 listener 做接管/放行切换。 */
    private fun startTakeoverMonitor() {
        takeoverMonitor.scheduleWithFixedDelay(
            {
                try {
                    val enabled = virtualLocationEnabled()
                    if (enabled) {
                        // 启用边沿：对未接管但已注册的 listener 启动虚拟投递
                        allStatusListeners.forEach { (listener, injected) ->
                            if (!injected && !statusTasks.containsKey(listener)) {
                                allStatusListeners[listener] = true
                                startStatusInject(listener)
                                ZLog.i(
                                    TAG_SCOPE,
                                    "GnssStatus late takeover for ${listener.javaClass.name} (virtual enabled)"
                                )
                            }
                        }
                        allNmeaListeners.forEach { (listener, injected) ->
                            if (!injected && !nmeaTasks.containsKey(listener)) {
                                allNmeaListeners[listener] = true
                                startNmeaInject(listener)
                                ZLog.i(
                                    TAG_SCOPE,
                                    "GnssNmea late takeover for ${listener.javaClass.name} (virtual enabled)"
                                )
                            }
                        }
                    } else {
                        // 关闭边沿：停止虚拟投递，listener 保持真实注册（未启用时真实状态照常到达）
                        allStatusListeners.forEach { (listener, injected) ->
                            if (injected) {
                                allStatusListeners[listener] = false
                                statusTasks.remove(listener)?.cancel(false)
                            }
                        }
                        allNmeaListeners.forEach { (listener, injected) ->
                            if (injected) {
                                allNmeaListeners[listener] = false
                                nmeaTasks.remove(listener)?.cancel(false)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "gnss takeover monitor failed", t)
                }
            },
            1000L,
            1000L,
            java.util.concurrent.TimeUnit.MILLISECONDS
        )
    }

    // ---------- GnssStatus：接管注册 + 周期注入虚拟卫星状态 ----------

    private fun hookGnssStatusRegister(classLoader: ClassLoader, clazz: Class<*>): Int {
        val method = HookSupport.findMethods(clazz, "registerGnssStatusCallback")
            .firstOrNull { it.parameterCount == 4 && it.parameterTypes[0].simpleName.contains("IGnssStatusListener") }
        if (method == null) {
            ZLog.w(TAG_SCOPE, "registerGnssStatusCallback(4) not found in $MANAGER_CLASS")
            return 0
        }
        val ok = registrar.register(method) { chain ->
            val listener = chain.getArg(0)
            if (listener == null) {
                chain.proceed()
                return@register null
            }
            if (virtualLocationEnabled()) {
                try {
                    val pkg = chain.getArg(1) as? String ?: "?"
                    allStatusListeners[listener] = true
                    // Keep one authoritative data plane. The original provider would dispatch
                    // physical satellites in parallel with our virtual callback and inflate the
                    // satellite count (and make apps see mixed constellations).
                    startStatusInject(listener)
                    ZLog.i(TAG_SCOPE, "GnssStatus registered as virtual-only pkg=$pkg listener=${listener.javaClass.name}")
                    return@register null
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "GnssStatus takeover failed, fallback", t)
                    allStatusListeners[listener] = false
                    chain.proceed()
                }
            } else {
                // 虚拟定位未启用：真实注册并记录，启用后由 takeover monitor 补齐虚拟投递
                try {
                    allStatusListeners[listener] = false
                    chain.proceed()
                    ZLog.d(TAG_SCOPE, "GnssStatus registered (real) ${listener.javaClass.name}")
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "GnssStatus real register failed", t)
                    chain.proceed()
                }
            }
            null
        }
        if (ok) {
            ZLog.i(TAG_SCOPE, "hooked $MANAGER_CLASS.registerGnssStatusCallback")
            return 1
        }
        return 0
    }

    /** 注销时取消注入任务，避免旧 listener 持续收到虚拟数据（含观测日志）。 */
    private fun hookGnssStatusUnregister(classLoader: ClassLoader, clazz: Class<*>): Int {
        val method = HookSupport.findMethods(clazz, "unregisterGnssStatusCallback")
            .firstOrNull { it.parameterCount == 1 }
        if (method == null) {
            ZLog.w(TAG_SCOPE, "unregisterGnssStatusCallback not found in $MANAGER_CLASS")
            return 0
        }
        val ok = registrar.register(method) { chain ->
            try {
                val listener = chain.getArg(0)
                statusTasks.remove(listener)?.cancel(false)
                if (listener != null) {
                    allStatusListeners.remove(listener)
                    ZLog.i(TAG_SCOPE, "GnssStatus callback unregistered, task cancelled (${listener.javaClass.name})")
                }
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "GnssStatus unregister hook failed", t)
            }
            chain.proceed()
            null
        }
        if (ok) {
            ZLog.i(TAG_SCOPE, "hooked $MANAGER_CLASS.unregisterGnssStatusCallback")
            return 1
        }
        return 0
    }

    private fun startStatusInject(listener: Any) {
        if (statusTasks.containsKey(listener)) return
        deliverVirtualStatus(listener)
        val future = statusExecutor.scheduleWithFixedDelay(
            { deliverVirtualStatus(listener) },
            INJECT_INTERVAL_MS,
            INJECT_INTERVAL_MS,
            java.util.concurrent.TimeUnit.MILLISECONDS
        )
        statusTasks[listener] = future
    }

    private fun deliverVirtualStatus(listener: Any) {
        try {
            if (!virtualLocationEnabled()) return
            val status = buildVirtualGnssStatus() ?: return
            val method = findCallbackMethod(
                listener,
                "onSvStatusChanged",
                "onSatelliteStatusChanged"
            ) ?: return
            method.invoke(listener, status)
            val now = SystemClock.elapsedRealtime()
            val last = lastStatusLog[listener] ?: 0L
            if (now - last >= 5000L) {
                lastStatusLog[listener] = now
                val cfg = backend.gnssEngine.currentData()
                val used = cfg?.optInt("usedInFix", DEFAULT_USED_IN_FIX) ?: DEFAULT_USED_IN_FIX
                ZLog.i(TAG_SCOPE, "GnssStatus delivered to ${listener.javaClass.name} usedInFix=$used")
            }
        } catch (t: Throwable) {
            val cause = unwrap(t)
            if (cause is android.os.DeadObjectException) {
                statusTasks.remove(listener)?.cancel(false)
                allStatusListeners.remove(listener)
                ZLog.i(TAG_SCOPE, "GnssStatus listener dead, task cancelled (${listener.javaClass.name})")
            } else {
                ZLog.w(TAG_SCOPE, "deliver virtual GnssStatus failed", t)
            }
        }
    }

    /** 用 GnssStatus.Builder 构造虚拟卫星状态（复用 FrameworkEnvHookAdapter 的 12 参 addSatellite 签名）。 */
    private fun buildVirtualGnssStatus(): Any? {
        return try {
            val builderClass = Class.forName("android.location.GnssStatus\$Builder")
            val builder = builderClass.getDeclaredConstructor().newInstance()
            val addSatellite = builderClass.getMethod(
                "addSatellite",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            )
            val cfg = backend.gnssEngine.currentData()
            val total = cfg?.optInt("satelliteCount", DEFAULT_SATELLITE_COUNT)?.coerceIn(4, 64)
                ?: DEFAULT_SATELLITE_COUNT
            val used = cfg?.optInt("usedInFix", DEFAULT_USED_IN_FIX)?.coerceIn(4, total)
                ?: DEFAULT_USED_IN_FIX
            val configuredCn0 = cfg?.optDouble("cn0", DEFAULT_CN0_DBHZ.toDouble())?.toFloat()
                ?: DEFAULT_CN0_DBHZ
            val constellations = intArrayOf(
                android.location.GnssStatus.CONSTELLATION_GPS,
                android.location.GnssStatus.CONSTELLATION_GLONASS,
                android.location.GnssStatus.CONSTELLATION_BEIDOU,
                android.location.GnssStatus.CONSTELLATION_GALILEO
            )
            for (i in 0 until total) {
                val svid = i + 1
                val cn0 = (configuredCn0 - 4f + (i % 5)).coerceIn(25f, 50f)
                val usedInFix = i < used
                addSatellite.invoke(
                    builder,
                    svid,
                    constellations[i % constellations.size],
                    cn0,
                    10f + i,
                    90f + i * 7,
                    true, // hasEphemeris
                    true, // hasAlmanac
                    usedInFix,
                    false, // hasBasebandCn0
                    cn0,
                    false, // isBasebandInFix
                    1575.42f // carrierFrequencyHz (L1)
                )
            }
            builderClass.getMethod("build").invoke(builder)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "build virtual gnss status failed", t)
            null
        }
    }

    // ---------- NMEA：接管注册 + 周期注入虚拟 NMEA ----------

    private fun hookNmeaRegister(classLoader: ClassLoader, clazz: Class<*>): Int {
        val method = HookSupport.findMethods(clazz, "registerGnssNmeaCallback")
            .firstOrNull { it.parameterCount == 4 && it.parameterTypes[0].simpleName.contains("IGnssNmeaListener") }
        if (method == null) {
            ZLog.w(TAG_SCOPE, "registerGnssNmeaCallback(4) not found in $MANAGER_CLASS")
            return 0
        }
        val ok = registrar.register(method) { chain ->
            val listener = chain.getArg(0)
            if (listener == null) {
                chain.proceed()
                return@register null
            }
            if (virtualLocationEnabled()) {
                try {
                    val pkg = chain.getArg(1) as? String ?: "?"
                    allNmeaListeners[listener] = true
                    startNmeaInject(listener)
                    ZLog.i(TAG_SCOPE, "GnssNmea registered as virtual-only pkg=$pkg listener=${listener.javaClass.name}")
                    return@register null
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "GnssNmea takeover failed, fallback", t)
                    allNmeaListeners[listener] = false
                    chain.proceed()
                }
            } else {
                // 虚拟定位未启用：真实注册并记录，启用后由 takeover monitor 补齐虚拟投递
                try {
                    allNmeaListeners[listener] = false
                    chain.proceed()
                    ZLog.d(TAG_SCOPE, "GnssNmea registered (real) ${listener.javaClass.name}")
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "GnssNmea real register failed", t)
                    chain.proceed()
                }
            }
            null
        }
        if (ok) {
            ZLog.i(TAG_SCOPE, "hooked $MANAGER_CLASS.registerGnssNmeaCallback")
            return 1
        }
        return 0
    }

    /** 注销时取消 NMEA 注入任务。 */
    private fun hookNmeaUnregister(classLoader: ClassLoader, clazz: Class<*>): Int {
        val method = HookSupport.findMethods(clazz, "unregisterGnssNmeaCallback")
            .firstOrNull { it.parameterCount == 1 }
        if (method == null) {
            ZLog.w(TAG_SCOPE, "unregisterGnssNmeaCallback not found in $MANAGER_CLASS")
            return 0
        }
        val ok = registrar.register(method) { chain ->
            try {
                val listener = chain.getArg(0)
                nmeaTasks.remove(listener)?.cancel(false)
                if (listener != null) {
                    allNmeaListeners.remove(listener)
                    ZLog.i(TAG_SCOPE, "GnssNmea callback unregistered, task cancelled (${listener.javaClass.name})")
                }
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "GnssNmea unregister hook failed", t)
            }
            chain.proceed()
            null
        }
        if (ok) {
            ZLog.i(TAG_SCOPE, "hooked $MANAGER_CLASS.unregisterGnssNmeaCallback")
            return 1
        }
        return 0
    }

    private fun startNmeaInject(listener: Any) {
        if (nmeaTasks.containsKey(listener)) return
        deliverVirtualNmea(listener)
        val future = statusExecutor.scheduleWithFixedDelay(
            { deliverVirtualNmea(listener) },
            INJECT_INTERVAL_MS,
            INJECT_INTERVAL_MS,
            java.util.concurrent.TimeUnit.MILLISECONDS
        )
        nmeaTasks[listener] = future
    }

    private fun deliverVirtualNmea(listener: Any) {
        try {
            if (!virtualLocationEnabled()) return
            val loc = backend.currentLocation() ?: return
            val nmea = buildVirtualNmea(loc.latitude, loc.longitude) ?: return
            val method = findCallbackMethod(listener, "onNmeaReceived") ?: return
            method.invoke(listener, SystemClock.elapsedRealtimeNanos(), nmea)
            val now = SystemClock.elapsedRealtime()
            val last = lastNmeaLog[listener] ?: 0L
            if (now - last >= 5000L) {
                lastNmeaLog[listener] = now
                ZLog.i(
                    TAG_SCOPE,
                    "GnssNmea delivered to ${listener.javaClass.name} ${String.format(java.util.Locale.US, "%.5f,%.5f", loc.latitude, loc.longitude)}"
                )
            }
        } catch (t: Throwable) {
            val cause = unwrap(t)
            if (cause is android.os.DeadObjectException) {
                // 百度等 SDK 注销/进程重启后，Binder 代理已死：取消任务并移除，
                // 否则周期投递持续失败会让百度 SDK 的 NMEA 时间戳（ab）不再更新，
                // 触发 e() 的 aa-ab>=3000 重置 → GPS fix 被判定 mock。
                nmeaTasks.remove(listener)?.cancel(false)
                allNmeaListeners.remove(listener)
                ZLog.i(TAG_SCOPE, "GnssNmea listener dead, task cancelled (${listener.javaClass.name})")
            } else {
                ZLog.w(TAG_SCOPE, "deliver virtual NMEA failed", t)
            }
        }
    }

    /** 剥开 InvocationTargetException 取真实 cause。 */
    private fun unwrap(t: Throwable): Throwable {
        var cur = t
        while (cur is java.lang.reflect.InvocationTargetException && cur.cause != null) {
            cur = cur.cause!!
        }
        return cur
    }

    /** Binder Stub/Proxy 的回调方法可能声明在父类或接口中。 */
    private fun findCallbackMethod(listener: Any, vararg names: String): java.lang.reflect.Method? {
        var type: Class<*>? = listener.javaClass
        while (type != null) {
            type.declaredMethods.firstOrNull { it.name in names && it.parameterCount > 0 }?.let {
                it.isAccessible = true
                return it
            }
            for (iface in type.interfaces) {
                iface.methods.firstOrNull { it.name in names && it.parameterCount > 0 }?.let {
                    it.isAccessible = true
                    return it
                }
            }
            type = type.superclass
        }
        return null
    }

    /**
     * 基于虚拟位置构造 $GPRMC 语句（百度 SDK 只解析 $GPGGA/$GPRMC 的经纬度与状态）。
     * 字段：$GPRMC,hhmmss.000,A,纬度,N/S,经度,E/W,速度,航向,日期,,,A*校验和
     *
     * 状态字段必须用 **V**（Void）而非 A：
     * 逆向 com.baidu.location.c.f.e(Location) 发现，NMEA 解析出坐标（ac!=null）且
     * 状态为 A（ad=true）时 e() 返回 400 → n() 走 mock 分支 → 百度地图定位失败；
     * 状态为 V（ad=false）且坐标有效时 e() 返回 0 → n() 走正常 GPS 路径。
     * （真实设备上室内无 GPS fix 时 NMEA 也常为 V，故 V 状态更“真实”。）
     */
    private fun buildVirtualNmea(latitude: Double, longitude: Double): String? {
        return try {
            val latAbs = Math.abs(latitude)
            val lonAbs = Math.abs(longitude)
            val latDeg = latAbs.toInt()
            val latMin = (latAbs - latDeg) * 60.0
            val lonDeg = lonAbs.toInt()
            val lonMin = (lonAbs - lonDeg) * 60.0
            val latHem = if (latitude >= 0) "N" else "S"
            val lonHem = if (longitude >= 0) "E" else "W"
            val body = String.format(
                java.util.Locale.US,
                "GPRMC,120000.000,V,%02d%07.4f,%s,%03d%07.4f,%s,0.0,0.0,130826,,,A",
                latDeg, latMin, latHem, lonDeg, lonMin, lonHem
            )
            var checksum = 0
            for (c in body) checksum = checksum xor c.code
            "$$body*${String.format(java.util.Locale.US, "%02X", checksum)}"
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "build virtual nmea failed", t)
            null
        }
    }

    // ---------- 导航消息 / 原始测量：阻断注册 ----------

    /**
     * 通用阻断：匹配指定参数个数的方法，虚拟定位启用时 before 直接 return（不注册）。
     * 匹配失败不阻断其余 Hook（fail-open）。
     */
    private fun blockRegister(
        classLoader: ClassLoader,
        clazz: Class<*>,
        methodName: String,
        paramCount: Int,
    ): Int {
        val method = HookSupport.findMethods(clazz, methodName)
            .firstOrNull { it.parameterCount == paramCount }
        if (method == null) {
            ZLog.w(TAG_SCOPE, "$methodName($paramCount) not found in $MANAGER_CLASS")
            return 0
        }
        val ok = registrar.register(method) { chain ->
            if (virtualLocationEnabled()) {
                ZLog.d(TAG_SCOPE, "blocked $methodName (virtual location)")
                return@register null // void 方法：不 proceed，等于不注册 listener
            }
            chain.proceed()
            null
        }
        if (ok) {
            ZLog.i(TAG_SCOPE, "hooked $MANAGER_CLASS.$methodName")
            return 1
        }
        ZLog.w(TAG_SCOPE, "register $methodName failed")
        return 0
    }
}
