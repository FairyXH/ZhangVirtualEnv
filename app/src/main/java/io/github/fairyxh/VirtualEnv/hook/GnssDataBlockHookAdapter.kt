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
        private const val INJECT_INTERVAL_MS = 500L
    }

    /** 虚拟定位启用（单点或路线任一开启；采集暂停时放行）。 */
    private fun virtualLocationEnabled(): Boolean =
        !backend.isSuspended() &&
            (backend.locationEngine.isEnabled() || backend.routeEngine.isRunning())

    private val statusExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ZVE-GnssInject").apply { isDaemon = true }
    }

    /** listener -> 周期任务（GnssStatus 注入）。 */
    private val statusTasks = ConcurrentHashMap<Any, java.util.concurrent.ScheduledFuture<*>>()
    /** listener -> 周期任务（NMEA 注入）。 */
    private val nmeaTasks = ConcurrentHashMap<Any, java.util.concurrent.ScheduledFuture<*>>()

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
        return hooked
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
            if (!virtualLocationEnabled()) {
                chain.proceed()
                return@register null
            }
            try {
                val listener = chain.getArg(0)
                val pkg = chain.getArg(1) as? String ?: "?"
                startStatusInject(listener)
                ZLog.i(TAG_SCOPE, "GnssStatus callback taken over pkg=$pkg listener=${listener.javaClass.name} (virtual)")
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "GnssStatus takeover failed, fallback", t)
                chain.proceed()
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
            // Stub$Proxy 方法签名在不同 ROM 可能不同（参数类型/可见性），
            // 用 declaredMethods 遍历 name+参数个数匹配，避免 getMethod 精确签名失败。
            val method = listener.javaClass.declaredMethods.firstOrNull {
                it.name == "onSatelliteStatusChanged" && it.parameterCount == 1
            } ?: return
            method.isAccessible = true
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
            ZLog.w(TAG_SCOPE, "deliver virtual GnssStatus failed", t)
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
            val total = cfg?.optInt("satelliteCount", DEFAULT_SATELLITE_COUNT)?.coerceIn(0, 64)
                ?: DEFAULT_SATELLITE_COUNT
            val used = cfg?.optInt("usedInFix", DEFAULT_USED_IN_FIX)?.coerceIn(0, total)
                ?: DEFAULT_USED_IN_FIX
            val constellations = intArrayOf(
                android.location.GnssStatus.CONSTELLATION_GPS,
                android.location.GnssStatus.CONSTELLATION_GLONASS,
                android.location.GnssStatus.CONSTELLATION_BEIDOU,
                android.location.GnssStatus.CONSTELLATION_GALILEO
            )
            for (i in 0 until total) {
                val svid = i + 1
                val cn0 = 18f + (i % 22)
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
            if (!virtualLocationEnabled()) {
                chain.proceed()
                return@register null
            }
            try {
                val listener = chain.getArg(0)
                val pkg = chain.getArg(1) as? String ?: "?"
                startNmeaInject(listener)
                ZLog.i(TAG_SCOPE, "GnssNmea callback taken over pkg=$pkg listener=${listener.javaClass.name} (virtual)")
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "GnssNmea takeover failed, fallback", t)
                chain.proceed()
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
            val method = listener.javaClass.declaredMethods.firstOrNull {
                it.name == "onNmeaReceived" && it.parameterCount == 2
            } ?: return
            method.isAccessible = true
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
            ZLog.w(TAG_SCOPE, "deliver virtual NMEA failed", t)
        }
    }

    /**
     * 基于虚拟位置构造 $GPRMC 语句（百度 SDK 只解析 $GPGGA/$GPRMC 的经纬度与状态）。
     * 字段：$GPRMC,hhmmss.000,A,纬度,N/S,经度,E/W,速度,航向,日期,,,A*校验和
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
                "GPRMC,120000.000,A,%02d%07.4f,%s,%03d%07.4f,%s,0.0,0.0,130826,,,A",
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
