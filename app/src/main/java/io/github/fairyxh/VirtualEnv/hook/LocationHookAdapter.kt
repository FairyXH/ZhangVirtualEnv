package io.github.fairyxh.VirtualEnv.hook

import android.location.Location
import io.github.fairyxh.VirtualEnv.core.Backend
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.util.Collections
import java.util.HashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Location Hook Adapter（Phase 1.1）。
 *
 * 职责边界：
 * - 只做 Android 接口适配（类名/方法/参数差异由 Profile 决定）
 * - 不保存任何业务状态，每次调用通过 [Backend.currentLocation] 获取虚拟位置
 * - 失败时放行原始逻辑（fail-open）
 *
 * Hook 点（依据 docs/reverse/目标类分析.md + services.jar 逆向）：
 * 1. LocationManagerService.getLastLocation(...)     → after 替换返回值（单次查询）
 * 2. LocationManagerService.getCurrentLocation(...)  → before 直接回调虚拟位置（异步单次）
 * 3. LocationProviderManager.onReportLocation(LocationResult) → before 替换上报位置（连续定位统一分发点）
 * 4. GnssLocationProvider.onReportLocation(...)       → before 替换 GPS provider 上报（兜底）
 * 5. ILocationListener$Stub$Proxy.onLocationChanged  → before 全局替换（仿 Paopao：任何到达 App 的 fix 都换为虚拟位置）
 * 6. registerLocationListener / unregisterLocationListener → 维护活跃 listener，虚拟定位启用时周期主动推送
 */
class LocationHookAdapter(
    private val backend: Backend,
    private val registrar: HookRegistrar,
) {

    companion object {
        private const val TAG_SCOPE = "Hook"
        private const val PUSH_INTERVAL_MS = 500L
        private const val PUSH_INTERVAL_JOYSTICK_MS = 220L
        private const val MAX_PUSH_LISTENERS = 128
    }

    /** 活跃的 ILocationListener（system_server 内 App Binder 代理实例）。 */
    private val activeListeners = ConcurrentHashMap.newKeySet<Any>()

    /** 周期主动推送是否已启动（幂等）。 */
    private val pushStarted = AtomicBoolean(false)

    private val pushExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ZVE-LocPush").apply { isDaemon = true }
    }

    /**
     * 安装 Location Hook。逐个 Hook 点独立 try/catch，失败不阻断其余 Hook。
     *
     * @param classLoader system_server class loader
     */
    fun install(classLoader: ClassLoader) {
        val cfg: JSONObject = backend.profileManager.locationHookConfig()

        val managerClass = cfg.optString("managerClass", "com.android.server.location.LocationManagerService")
        val providerManagerClass = cfg.optString(
            "providerManagerClass",
            "com.android.server.location.provider.LocationProviderManager"
        )
        val gnssClass = cfg.optString("gnssClass", "com.android.server.location.gnss.GnssLocationProvider")

        hookGetLastLocation(classLoader, managerClass)
        hookGetCurrentLocation(classLoader, managerClass)
        hookProviderManagerReportLocation(classLoader, providerManagerClass)
        hookGnssReportLocation(classLoader, gnssClass)
        hookDeliverOnLocationChanged(classLoader, providerManagerClass)
        hookRegisterLocationListener(classLoader, managerClass)
        hookUnregisterLocationListener(classLoader, managerClass)
        hookGlobalListenerProxy(classLoader)
        hookProviderEnabled(classLoader, managerClass)
        hookRegisterGnssStatusCallback(classLoader, managerClass)
        hookRegistrationFilter(classLoader, providerManagerClass)
        startPushLoop()
    }

    /** 取刷新时间戳后的虚拟位置（所有对外投递点统一使用，防 SDK 新鲜度拒收）。 */
    private fun currentVirtual(provider: String? = null): Location? {
        val virtual = backend.currentLocation() ?: return null
        return LocationFresh.fresh(virtual, provider)
    }

    /** 虚拟定位启用（单点或路线任一开启；采集暂停时放行原始行为）。 */
    private fun virtualLocationEnabled(): Boolean =
        !backend.isSuspended() &&
            (backend.locationEngine.isEnabled() || backend.routeEngine.isRunning())

    // ---------- 单次查询：getLastLocation ----------

    private fun hookGetLastLocation(classLoader: ClassLoader, className: String) {
        val clazz = HookSupport.findClass(classLoader, className) ?: return
        val method = HookSupport.findMethods(clazz, "getLastLocation")
            .firstOrNull { it.parameterTypes.isNotEmpty() }
        if (method == null) {
            ZLog.w(TAG_SCOPE, "getLastLocation not found in $className")
            return
        }
        val ok = registrar.register(method) { chain ->
            // after：先走原始逻辑，再决定是否替换返回值
            val original = chain.proceed()
            // Hook 层真实数据观测：原始返回值（虚拟替换前）
            if (original is Location) {
                io.github.fairyxh.VirtualEnv.core.HookObserver.recordLocation(original)
            }
            val virtual = currentVirtual()
            if (virtual != null) {
                ZLog.d(TAG_SCOPE, "getLastLocation -> virtual ${virtual.latitude},${virtual.longitude}")
                virtual
            } else {
                original
            }
        }
        if (ok) {
            ZLog.i(TAG_SCOPE, "hooked $className.getLastLocation")
        }
    }

    // ---------- 异步单次：getCurrentLocation ----------

    private fun hookGetCurrentLocation(classLoader: ClassLoader, className: String) {
        val clazz = HookSupport.findClass(classLoader, className) ?: return
        // 签名：ICancellationSignal getCurrentLocation(String, LocationRequest, ILocationCallback, String, String, String)
        val method = HookSupport.findMethods(clazz, "getCurrentLocation")
            .firstOrNull { m ->
                m.parameterTypes.size == 6 &&
                    m.parameterTypes[2].simpleName.contains("ILocationCallback")
            }
        if (method == null) {
            ZLog.w(TAG_SCOPE, "getCurrentLocation not found in $className")
            return
        }
        val callbackClass = method.parameterTypes[2]
        val ok = registrar.register(method) { chain ->
            val virtual: Location? = currentVirtual()
            if (virtual != null) {
                // before：直接向回调投递虚拟位置，并返回已取消的 cancellation signal，阻止原链路
                try {
                    val callback = chain.getArg(2)
                    val pkg = chain.getArg(3) as? String ?: "?"
                    invokeCallbackOnLocation(callbackClass, callback, virtual)
                    ZLog.i(TAG_SCOPE, "getCurrentLocation -> virtual pkg=$pkg ${virtual.latitude},${virtual.longitude}")
                    newCancellationSignal()
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "getCurrentLocation virtual callback failed, fallback to original", t)
                    chain.proceed()
                }
            } else {
                chain.proceed()
            }
        }
        if (ok) {
            ZLog.i(TAG_SCOPE, "hooked $className.getCurrentLocation")
        }
    }

    /** 反射创建 android.os.ICancellationSignal.Stub（隐藏 API，编译期不可引用）。 */
    private fun newCancellationSignal(): Any {
        val cls = Class.forName("android.os.ICancellationSignal")
        val stubCls = cls.declaredClasses.first { it.simpleName == "Stub" }
        val ctor = stubCls.getDeclaredConstructor()
        ctor.isAccessible = true
        return ctor.newInstance()
    }

    private fun invokeCallbackOnLocation(callbackClass: Class<*>, callback: Any, location: Location) {
        val method = callbackClass.getMethod("onLocation", Location::class.java)
        method.invoke(callback, location)
    }

    // ---------- 连续定位统一分发点：LocationProviderManager.onReportLocation ----------

    private fun hookProviderManagerReportLocation(classLoader: ClassLoader, className: String) {
        val clazz = HookSupport.findClass(classLoader, className) ?: return
        val method = HookSupport.findMethods(clazz, "onReportLocation")
            .firstOrNull { m -> m.parameterTypes.size == 1 }
        if (method == null) {
            ZLog.w(TAG_SCOPE, "provider manager onReportLocation not found in $className")
            return
        }
        val locationResultClass = LocationResultFactory.resolveClass(classLoader)
        if (locationResultClass == null) return

        val ok = registrar.register(method) { chain ->
            // Hook 层真实数据观测：provider 上报的原始 LocationResult（虚拟替换前）
            io.github.fairyxh.VirtualEnv.core.HookObserver.recordLocationResult(chain.getArg(0))
            val virtual: Location? = currentVirtual()
            if (virtual != null) {
                try {
                    // 构造 LocationResult 替换上报位置（兼容 AOSP wrap(Location) 与 ColorOS wrap(List)）
                    val virtualResult = LocationResultFactory.create(locationResultClass, virtual)
                    chain.proceed(arrayOf(virtualResult))
                    ZLog.d(TAG_SCOPE, "provider onReportLocation -> virtual ${virtual.latitude},${virtual.longitude}")
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "provider onReportLocation virtual failed, fallback", t)
                    chain.proceed()
                }
            } else {
                chain.proceed()
            }
            null
        }
        if (ok) {
            ZLog.i(TAG_SCOPE, "hooked $className.onReportLocation")
        }
    }

    // ---------- GPS provider 上报兜底：GnssLocationProvider.onReportLocation ----------

    private fun hookGnssReportLocation(classLoader: ClassLoader, className: String) {
        val clazz = HookSupport.findClass(classLoader, className) ?: return
        val method = HookSupport.findMethods(clazz, "onReportLocation")
            .firstOrNull { m ->
                m.parameterTypes.size == 2 &&
                    m.parameterTypes[0] == Boolean::class.java &&
                    Location::class.java.isAssignableFrom(m.parameterTypes[1])
            }
        if (method == null) {
            ZLog.w(TAG_SCOPE, "gnss onReportLocation not found in $className")
            return
        }
        val ok = registrar.register(method) { chain ->
            // Hook 层真实数据观测：GNSS HAL 上报的真实位置（虚拟替换前）
            (chain.getArg(1) as? Location)?.let {
                io.github.fairyxh.VirtualEnv.core.HookObserver.recordLocation(it)
            }
            // before：替换上报位置为虚拟位置，然后继续原始分发链路
            val virtual: Location? = currentVirtual()
            if (virtual != null) {
                chain.proceed(arrayOf(chain.getArg(0), virtual))
                ZLog.d(TAG_SCOPE, "gnss onReportLocation -> virtual ${virtual.latitude},${virtual.longitude}")
            } else {
                chain.proceed()
            }
            null
        }
        if (ok) {
            ZLog.i(TAG_SCOPE, "hooked $className.onReportLocation")
        }
    }

    // ---------- 连续定位 App 分发点：LocationProviderManager$XxxTransport.deliverOnLocationChanged ----------

    /**
     * Hook Oplus/AOSP 15 的 listener 分发点（逆向 services.jar 确认）：
     *
     * LocationProviderManager$LocationListenerTransport.deliverOnLocationChanged(
     *     LocationResult locationResult, IRemoteCallback onCompleteCallback)
     *     → mListener.onLocationChanged(locationResult.asList(), onCompleteCallback)
     *
     * 以及 LocationPendingIntentTransport 的同名方法（后台 PendingIntent 定位）。
     * 此点是 provider 上报后、真正回调给 App 的最后一环，替换 LocationResult
     * 可覆盖 requestLocationUpdates 连续定位被真实位置覆盖的场景。
     */
    private fun hookDeliverOnLocationChanged(classLoader: ClassLoader, providerManagerClass: String) {
        val locationResultClass = LocationResultFactory.resolveClass(classLoader)
        if (locationResultClass == null) return

        listOf(
            "$providerManagerClass\$LocationListenerTransport",
            "$providerManagerClass\$LocationPendingIntentTransport"
        ).forEach { transportName ->
            val clazz = HookSupport.findClass(classLoader, transportName) ?: return@forEach
            val method = HookSupport.findMethods(clazz, "deliverOnLocationChanged")
                .firstOrNull { m ->
                    m.parameterCount == 2 && m.parameterTypes[0] == locationResultClass
                }
            if (method == null) {
                ZLog.w(TAG_SCOPE, "deliverOnLocationChanged not found in $transportName")
                return@forEach
            }
            val ok = registrar.register(method) { chain ->
                val virtual: Location? = currentVirtual()
                if (virtual != null) {
                    try {
                        val virtualResult = LocationResultFactory.create(locationResultClass, virtual)
                        chain.proceed(arrayOf(virtualResult, chain.getArg(1)))
                        ZLog.d(TAG_SCOPE, "$transportName deliver -> virtual ${virtual.latitude},${virtual.longitude}")
                    } catch (t: Throwable) {
                        ZLog.w(TAG_SCOPE, "$transportName deliver virtual failed, fallback", t)
                        chain.proceed()
                    }
                } else {
                    chain.proceed()
                }
                null
            }
            if (ok) {
                ZLog.i(TAG_SCOPE, "hooked $transportName.deliverOnLocationChanged")
            }
        }
    }

    // ---------- 连续定位 listener 注册点：LocationManagerService.registerLocationListener ----------

    /**
     * Hook Android 12+ 的 listener 注册入口，借鉴 GhostMapX：
     *
     * 签名：registerLocationListener(String provider, LocationRequest request,
     *     ILocationListener listener, String packageName, String attributionTag,
     *     String listenerId)
     *
     * 注册时捕获 ILocationListener（App 进程 Binder 代理，class 为
     * ILocationListener$Stub$Proxy），对其 `onLocationChanged` 方法做参数替换，
     * 并立即主动推送一次虚拟位置。这样即使 provider 从不产生上报，SDK 在
     * 注册后也能立刻收到虚拟位置（解决百度/微信 gps 无 fix 时收不到位置）。
     */
    private fun hookRegisterLocationListener(classLoader: ClassLoader, className: String) {
        val clazz = HookSupport.findClass(classLoader, className) ?: return
        val method = HookSupport.findMethods(clazz, "registerLocationListener")
            .firstOrNull { m ->
                m.parameterCount == 6 && m.parameterTypes[2].simpleName.contains("ILocationListener")
            }
        if (method == null) {
            ZLog.w(TAG_SCOPE, "registerLocationListener not found in $className")
            return
        }
        val ok = registrar.register(method) { chain ->
            chain.proceed()
            val virtual: Location? = currentVirtual()
            try {
                val listener = chain.getArg(2) ?: return@register null
                val pkg = chain.getArg(3) as? String ?: "?"
                // 维护活跃 listener 集合：虚拟定位启用时周期主动推送（摇杆移动实时生效）
                if (activeListeners.size < MAX_PUSH_LISTENERS) {
                    activeListeners.add(listener)
                }
                hookListenerOnLocationChanged(listener, virtual)
                if (virtual != null) {
                    pushVirtualLocation(listener, virtual)
                    ZLog.i(TAG_SCOPE, "registerLocationListener pkg=$pkg listener=${listener.javaClass.name} pushed virtual")
                } else {
                    ZLog.i(TAG_SCOPE, "registerLocationListener pkg=$pkg listener=${listener.javaClass.name} (virtual off)")
                }
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "registerLocationListener virtual push failed", t)
            }
            null
        }
        if (ok) {
            ZLog.i(TAG_SCOPE, "hooked $className.registerLocationListener")
        }
    }

    /** 已 hook 过 onLocationChanged 的 listener class（Binder Proxy class 全局共享，只需 hook 一次）。 */
    private val hookedListenerClasses = Collections.synchronizedSet(HashSet<Class<*>>())

    // ---------- 连续定位 listener 注销点：unregisterLocationListener ----------

    /**
     * Hook unregisterLocationListener，从活跃 listener 集合移除实例，
     * 避免周期主动推送向已注销 listener 投递（DeadObjectException 噪音）。
     */
    private fun hookUnregisterLocationListener(classLoader: ClassLoader, className: String) {
        val clazz = HookSupport.findClass(classLoader, className) ?: return
        val method = HookSupport.findMethods(clazz, "unregisterLocationListener")
            .firstOrNull { it.parameterCount == 1 && it.parameterTypes[0].simpleName.contains("ILocationListener") }
        if (method == null) {
            ZLog.w(TAG_SCOPE, "unregisterLocationListener not found in $className")
            return
        }
        val ok = registrar.register(method) { chain ->
            try {
                val listener = chain.getArg(0)
                if (listener != null) {
                    activeListeners.remove(listener)
                    ZLog.i(TAG_SCOPE, "unregisterLocationListener removed ${listener.javaClass.name}")
                }
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "unregisterLocationListener hook failed", t)
            }
            chain.proceed()
            null
        }
        if (ok) {
            ZLog.i(TAG_SCOPE, "hooked $className.unregisterLocationListener")
        }
    }

    // ---------- 全局 Binder 出口替换：ILocationListener$Stub$Proxy.onLocationChanged ----------

    /**
     * 全局替换所有 App 的 ILocationListener Binder 出口（仿 Paopao hookGlobalLocationListener）。
     *
     * 在 system_server 进程中 hook `android.location.ILocationListener$Stub$Proxy` 的
     * onLocationChanged：无论 provider 上报真实还是虚拟位置，只要虚拟定位启用，
     * 到达 App 进程之前统一替换为虚拟位置。百度/微信等 SDK 的 listener 都是该
     * Binder Proxy 实例，覆盖 requestLocationUpdates / registerLocationListener /
     * passive 等全部注册路径，且不 Hook 任何第三方 App 进程（约束不变）。
     */
    private fun hookGlobalListenerProxy(classLoader: ClassLoader) {
        val proxyClass = try {
            Class.forName("android.location.ILocationListener\$Stub\$Proxy", false, classLoader)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "ILocationListener\$Stub\$Proxy not found, skip global proxy hook", t)
            return
        }
        var hooked = 0
        HookSupport.findMethods(proxyClass, "onLocationChanged").forEach { m ->
            if (m.parameterCount < 1) return@forEach
            val ok = registrar.register(m) { chain ->
                val virtualNow: Location? = currentVirtual()
                if (virtualNow != null && chain.args.isNotEmpty()) {
                    val arg0 = chain.getArg(0)
                    when {
                        arg0 is List<*> -> {
                            chain.proceed(arrayOf(listOf(virtualNow), chain.getArg(1)))
                            ZLog.d(TAG_SCOPE, "proxy onLocationChanged(List) -> virtual ${virtualNow.latitude},${virtualNow.longitude}")
                        }
                        arg0 is Location -> {
                            chain.proceed(arrayOf(virtualNow))
                            ZLog.d(TAG_SCOPE, "proxy onLocationChanged(Location) -> virtual ${virtualNow.latitude},${virtualNow.longitude}")
                        }
                        else -> chain.proceed()
                    }
                } else {
                    chain.proceed()
                }
                null
            }
            if (ok) {
                hooked++
                ZLog.i(TAG_SCOPE, "hooked ILocationListener\$Stub\$Proxy.onLocationChanged (${m.parameterCount} params)")
            }
        }
        if (hooked == 0) {
            ZLog.w(TAG_SCOPE, "ILocationListener\$Stub\$Proxy.onLocationChanged candidates not found")
        }
    }

    // ---------- 周期主动推送（摇杆/路线实时投递兜底） ----------

    /**
     * 虚拟定位启用时，每 [PUSH_INTERVAL_MS] 向所有活跃 listener 主动推送一次虚拟位置。
     *
     * 背景：百度地图摇杆无效的根因是 SDK 在无真实 GPS fix 时收不到 provider 上报，
     * 而 provider 注入（VirtualFixInjector）依赖 LocationProviderManager 实例捕获，
     * Oplus 15 上存在签名差异时注入器可能不工作。这里直接调用 App listener 的
     * Binder onLocationChanged，等效于 Paopao 的 callOnLocationChanged()：
     * 不依赖 provider 链路，位置变化（摇杆位移）实时到达百度/微信。
     */
    private fun startPushLoop() {
        if (!pushStarted.compareAndSet(false, true)) return
        pushExecutor.execute(object : Runnable {
            override fun run() {
                try {
                    pushToActiveListeners()
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "push loop failed", t)
                }
                // 摇杆移动时加速推送（与 JoystickEngine tick 200ms 对齐），
                // 避免百度等 SDK 看到阶梯式位置更新
                val delay = if (backend.joystickEngine.isEnabled()) {
                    PUSH_INTERVAL_JOYSTICK_MS
                } else {
                    PUSH_INTERVAL_MS
                }
                pushExecutor.schedule(this, delay, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        })
        ZLog.i(TAG_SCOPE, "listener push loop started (interval=${PUSH_INTERVAL_MS}ms, joystick=${PUSH_INTERVAL_JOYSTICK_MS}ms)")
    }

    private fun pushToActiveListeners() {
        if (!virtualLocationEnabled()) return
        if (activeListeners.isEmpty()) return
        val virtual = currentVirtual() ?: return
        val iter = activeListeners.iterator()
        while (iter.hasNext()) {
            val listener = iter.next()
            try {
                pushVirtualLocation(listener, virtual)
            } catch (t: Throwable) {
                // DeadObject / 已注销 listener：移除避免持续噪音
                iter.remove()
                ZLog.d(TAG_SCOPE, "push listener removed (dead): ${listener.javaClass.name} ${t.message}")
            }
        }
    }

    /**
     * 对 listener 的 onLocationChanged 做参数替换。
     *
     * 支持 Android 12+ 的 onLocationChanged(List<Location>, IRemoteCallback)
     * 与旧式 onLocationChanged(Location)。virtual 为 null（虚拟定位未启用）时
     * 仅注册替换钩子，启用后由 currentVirtual() 实时取数。
     */
    private fun hookListenerOnLocationChanged(listener: Any, virtual: Location?) {
        val cls = listener.javaClass
        if (!hookedListenerClasses.add(cls)) return
        cls.methods.filter { it.name == "onLocationChanged" }.forEach { m ->
            try {
                val ok = registrar.register(m) { chain ->
                    val virtualNow: Location? = currentVirtual()
                    if (virtualNow != null && chain.args.isNotEmpty()) {
                        val arg0 = chain.getArg(0)
                        if (arg0 is List<*>) {
                            chain.proceed(arrayOf(listOf(virtualNow), chain.getArg(1)))
                        } else if (arg0 is Location) {
                            chain.proceed(arrayOf(virtualNow))
                        } else {
                            chain.proceed()
                        }
                    } else {
                        chain.proceed()
                    }
                    null
                }
                if (ok) {
                    ZLog.i(TAG_SCOPE, "hooked listener.onLocationChanged on ${cls.name} (${m.parameterCount} params)")
                }
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "hook listener onLocationChanged failed on ${cls.name}", t)
            }
        }
    }

    /**
     * 立即向 listener 主动推送虚拟位置。
     *
     * 优先 List<Location> 形式（Android 12+ ILocationListener），IRemoteCallback
     * 传 null；失败回退 Location 单参形式。全部 try/catch，失败静默（依赖
     * 注入器周期推送兜底）。
     */
    private fun pushVirtualLocation(listener: Any, virtual: Location) {
        val cls = listener.javaClass
        try {
            val listMethod = cls.methods.firstOrNull { m ->
                m.name == "onLocationChanged" && m.parameterCount >= 1 &&
                    List::class.java.isAssignableFrom(m.parameterTypes[0])
            }
            if (listMethod != null) {
                val args = arrayOfNulls<Any?>(listMethod.parameterCount)
                args[0] = listOf(virtual)
                listMethod.invoke(listener, *args)
                ZLog.d(TAG_SCOPE, "registerLocationListener -> pushed virtual (${virtual.latitude},${virtual.longitude})")
                return
            }
            val singleMethod = cls.methods.firstOrNull { m ->
                m.name == "onLocationChanged" && m.parameterCount == 1 &&
                    Location::class.java.isAssignableFrom(m.parameterTypes[0])
            }
            if (singleMethod != null) {
                singleMethod.invoke(listener, virtual)
                ZLog.d(TAG_SCOPE, "registerLocationListener -> pushed virtual single (${virtual.latitude},${virtual.longitude})")
            }
        } catch (t: Throwable) {
            // 已注销/进程退出：移除活跃集合，避免周期推送持续向死 Binder 投递
            if (isDeadObject(t)) {
                activeListeners.remove(listener)
                ZLog.d(TAG_SCOPE, "push listener removed (dead): ${cls.name}")
            } else {
                ZLog.w(TAG_SCOPE, "push virtual location to listener failed", t)
            }
        }
    }

    /** 剥开 InvocationTargetException 判断 DeadObjectException。 */
    private fun isDeadObject(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is android.os.DeadObjectException) return true
            cur = cur.cause
        }
        return false
    }

    // ---------- provider 启用状态：isProviderEnabledForUser / isProviderEnabled ----------

    private fun hookProviderEnabled(classLoader: ClassLoader, className: String) {
        val clazz = HookSupport.findClass(classLoader, className) ?: return
        listOf("isProviderEnabledForUser", "isProviderEnabled").forEach { name ->
            HookSupport.findMethods(clazz, name).forEach { method ->
                val ok = registrar.register(method) { chain ->
                    val original = chain.proceed()
                    val virtual = backend.currentLocation()
                    if (virtual != null) {
                        ZLog.d(TAG_SCOPE, "$name -> true (virtual location)")
                        true
                    } else {
                        original
                    }
                }
                if (ok) {
                    ZLog.i(TAG_SCOPE, "hooked $className.$name")
                }
            }
        }
    }

    // ---------- GNSS 状态回调注册：registerGnssStatusCallback ----------

    private fun hookRegisterGnssStatusCallback(classLoader: ClassLoader, className: String) {
        val clazz = HookSupport.findClass(classLoader, className) ?: return
        val method = HookSupport.findMethods(clazz, "registerGnssStatusCallback")
            .firstOrNull { it.parameterCount >= 2 }
        if (method == null) {
            ZLog.w(TAG_SCOPE, "registerGnssStatusCallback not found in $className")
            return
        }
        val ok = registrar.register(method) { chain ->
            val original = chain.proceed()
            val virtual = backend.currentLocation()
            if (virtual != null) {
                ZLog.d(TAG_SCOPE, "registerGnssStatusCallback -> true (virtual location)")
                true
            } else {
                original
            }
        }
        if (ok) {
            ZLog.i(TAG_SCOPE, "hooked $className.registerGnssStatusCallback")
        }
    }

    // ---------- 投递过滤旁路：LocationRegistration$1.test / LocationRequest getters ----------

    /**
     * system_server 对每个 listener 的投递过滤（Android 15 services.jar 逆向确认）：
     *
     * LocationProviderManager$LocationRegistration.acceptLocationChange(LocationResult)
     *   → permittedLocationResult.filter(LocationRegistration$1.test(Location))：
     *     - `deltaMs < minUpdateIntervalMillis - maxJitterMs` → 丢弃（"too fast"）
     *     - `distanceTo(prev) <= minUpdateDistanceMeters` → 丢弃（"too close"）
     *
     * 虚拟定位启用时，注入的 fix 坐标静止且时间戳相同，会被这两道过滤丢弃：
     * 志愿汇 gps listener 带 minUpdateDistance=10m → 只收到第 1 次 fix（locations=1），
     * 之后地图不再更新（摇杆无法移动）。虚拟定位启用时直接放行所有投递。
     */
    private fun hookRegistrationFilter(classLoader: ClassLoader, providerManagerClass: String) {
        // 优先挂匿名 Predicate：只影响投递过滤，不影响 provider request 计算
        val predicateName = "$providerManagerClass\$LocationRegistration\$1"
        val predicateClazz = HookSupport.findClass(classLoader, predicateName)
        if (predicateClazz != null) {
            val method = HookSupport.findMethods(predicateClazz, "test")
                .firstOrNull { it.parameterCount == 1 && it.parameterTypes[0] == Location::class.java }
            if (method != null) {
                val ok = registrar.register(method) { chain ->
                    if (virtualLocationEnabled()) {
                        true
                    } else {
                        chain.proceed()
                    }
                }
                if (ok) {
                    ZLog.i(TAG_SCOPE, "hooked $predicateName.test (delivery filter bypass)")
                    return
                }
            }
            ZLog.w(TAG_SCOPE, "LocationRegistration\$1.test not found, fallback LocationRequest getters")
        }

        // 回退：LocationRequest 过滤 getter 返回 0（虚拟定位启用时）
        val requestClass = HookSupport.findClass(classLoader, "android.location.LocationRequest")
            ?: return
        // getMinUpdateDistanceMeters(): double, getMinUpdateIntervalMillis(): long
        listOf(
            "getMinUpdateDistanceMeters" to 0.0,
            "getMinUpdateIntervalMillis" to 0L,
        ).forEach { (name, zero) ->
            val method = HookSupport.findMethods(requestClass, name)
                .firstOrNull { it.parameterCount == 0 }
            if (method == null) {
                ZLog.w(TAG_SCOPE, "LocationRequest.$name not found")
                return@forEach
            }
            val ok = registrar.register(method) { chain ->
                if (virtualLocationEnabled()) {
                    zero
                } else {
                    chain.proceed()
                }
            }
            if (ok) {
                ZLog.i(TAG_SCOPE, "hooked LocationRequest.$name -> 0 (virtual)")
            }
        }
    }
}
