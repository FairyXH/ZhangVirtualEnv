package io.github.fairyxh.VirtualEnv.hook

import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import io.github.fairyxh.VirtualEnv.core.Backend
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 虚拟定位主动注入器（Phase 2，百度/微信/GMS 缓存修复）。
 *
 * 背景（见 docs/reverse/location_pipeline_analysis.md）：
 * - 百度/微信请求 gps provider 连续定位，但真实 GPS 在室内无 fix 时
 *   system_server 从不产生上报，现有"替换型" Hook 不触发，App 只能读到
 *   SDK 本地缓存真实位置。
 * - GMS fused 监听 passive provider，passive 没有持续推送时进程内缓存不刷新。
 *
 * 方案（全部系统层，scope 不变）：
 * 1. Hook `LocationProviderManager.<init>` 捕获 gps/passive provider 实例；
 * 2. 虚拟定位启用时，定时主动调用 `manager.onReportLocation(虚拟 LocationResult)`；
 * 3. `onReportLocation` 内部链：setLastLocation → deliverToListeners（百度 gps /
 *    微信 passive 收到）→ passiveManager.updateLocation（GMS fused 缓存刷新）。
 *
 * 不修改任何 provider 内部逻辑，不保存业务状态，失败静默放行（fail-open）。
 */
class VirtualFixInjector(
    private val backend: Backend,
    private val registrar: HookRegistrar,
) {

    companion object {
        private const val TAG_SCOPE = "Hook"
        private const val DEFAULT_PROVIDER_MANAGER =
            "com.android.server.location.provider.LocationProviderManager"
        private const val DEFAULT_INTERVAL_MS = 1000L
        private const val MAX_MANAGERS = 16
        private val DEFAULT_PROVIDERS = setOf("gps", "passive")
    }

    /** provider name -> LocationProviderManager 实例。 */
    private val managers = ConcurrentHashMap<String, Any>()

    /** provider manager Class -> onReportLocation(LocationResult) 方法（避免重复反射查找）。 */
    private val reportMethods = ConcurrentHashMap<Class<*>, Method>()

    /** 由 install 时解析的 LocationResult 类（boot classloader）。 */
    private val locationResultClass = AtomicReference<Class<*>?>(null)

    private val injectThread: HandlerThread by lazy {
        HandlerThread("ZVE-FixInjector").apply { start() }
    }
    private val injectHandler: Handler by lazy { Handler(injectThread.looper) }

    @Volatile
    private var started = false

    /** 虚拟位置启用（单点或路线任一开启；采集暂停时停止注入）。 */
    private fun virtualLocationEnabled(): Boolean =
        !backend.isSuspended() &&
            (backend.locationEngine.isEnabled() || backend.routeEngine.isRunning())

    /**
     * 安装注入器：Hook provider manager 构造 + 启动定时注入。
     *
     * @param classLoader system_server class loader
     */
    fun install(classLoader: ClassLoader) {
        val cfg = backend.profileManager.locationHookConfig()
        val providerManagerClass = cfg.optString(
            "providerManagerClass",
            DEFAULT_PROVIDER_MANAGER
        )
        val providers = parseProviders(cfg)

        val clazz = HookSupport.findClass(classLoader, providerManagerClass) ?: return
        val resultClass = LocationResultFactory.resolveClass(classLoader) ?: return
        locationResultClass.set(resultClass)

        var hooked = 0
        clazz.declaredConstructors.forEach { ctor ->
            // AOSP/ColorOS 15 签名：LocationProviderManager(Context, Injector, String, PassiveLocationProviderManager[, Collection])
            // 第 3 参为 provider name（"gps"/"passive"/"network"/"fused"）。
            if (ctor.parameterCount >= 4 && ctor.parameterTypes[2] == String::class.java) {
                val ok = registrar.register(ctor) { chain ->
                    chain.proceed()
                    try {
                        val name = chain.getArg(2) as? String
                        if (name != null && name in providers && managers.size < MAX_MANAGERS) {
                            val self = chain.getThisObject()
                            if (self != null) {
                                managers.putIfAbsent(name, self)
                                ZLog.i(TAG_SCOPE, "fix injector registered provider=$name (${self.javaClass.name})")
                            }
                        }
                    } catch (t: Throwable) {
                        ZLog.w(TAG_SCOPE, "fix injector capture failed", t)
                    }
                    null
                }
                if (ok) {
                    hooked++
                    ZLog.i(TAG_SCOPE, "hooked LocationProviderManager.<init>(${ctor.parameterCount} params)")
                }
            }
        }
        if (hooked == 0) {
            ZLog.w(TAG_SCOPE, "no LocationProviderManager constructor hooked, injector disabled")
            return
        }
        startInjection()
    }

    private fun parseProviders(cfg: org.json.JSONObject): Set<String> {
        val arr = cfg.optJSONArray("injectProviders") ?: return DEFAULT_PROVIDERS
        if (arr.length() == 0) return DEFAULT_PROVIDERS
        return (0 until arr.length()).mapNotNull { arr.optString(it).ifEmpty { null } }.toSet()
    }

    private fun intervalMs(): Long =
        backend.profileManager.locationHookConfig().optLong("injectIntervalMs", DEFAULT_INTERVAL_MS)
            .coerceAtLeast(500L)

    private fun startInjection() {
        if (started) return
        started = true
        val task = object : Runnable {
            var ticks = 0
            override fun run() {
                try {
                    ticks++
                    injectOnce(ticks)
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "fix inject failed", t)
                }
                injectHandler.postDelayed(this, intervalMs())
            }
        }
        injectHandler.post(task)
        ZLog.i(TAG_SCOPE, "fix injector started (interval=${intervalMs()}ms providers=${managers.keys})")
    }

    /**
     * 一轮注入：对每个已注册的 provider manager 主动上报虚拟位置。
     */
    private fun injectOnce(ticks: Int) {
        val resultClass = locationResultClass.get() ?: return
        val virtual = backend.currentLocation() ?: return
        if (managers.isEmpty()) return

        managers.forEach { (name, manager) ->
            try {
                val method = reportMethod(manager.javaClass, resultClass) ?: return@forEach
                val loc = Location(virtual)
                // gps/passive 都使用 "gps" 名：百度 SDK 的 passive listener
                // （com.baidu.location.c.f$h.onLocationChanged）明确要求
                // location.getProvider()=="gps" 才接受被动 fix；network 保持 network。
                loc.provider = if (name == "network") "network" else "gps"
                val result = LocationResultFactory.create(resultClass, loc)
                method.invoke(manager, result)
                if (ticks % 10 == 1) {
                    ZLog.i(
                        TAG_SCOPE,
                        "fix inject [$name] -> virtual ${String.format(java.util.Locale.US, "%.5f,%.5f", loc.latitude, loc.longitude)} managers=${managers.keys}"
                    )
                }
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "fix inject [$name] failed", t)
            }
        }
    }

    /** 查找并缓存 onReportLocation(LocationResult) 方法。 */
    private fun reportMethod(managerClass: Class<*>, resultClass: Class<*>): Method? {
        reportMethods[managerClass]?.let { return it }
        val method = try {
            managerClass.getMethod("onReportLocation", resultClass)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "onReportLocation not found in ${managerClass.name}", t)
            null
        }
        if (method != null) {
            reportMethods[managerClass] = method
        }
        return method
    }
}
