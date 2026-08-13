package io.github.fairyxh.VirtualEnv.hook

import android.location.Location
import io.github.fairyxh.VirtualEnv.core.Backend
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.util.Collections
import java.util.HashSet

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
 */
class LocationHookAdapter(
    private val backend: Backend,
    private val registrar: HookRegistrar,
) {

    companion object {
        private const val TAG_SCOPE = "Hook"
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
        hookProviderEnabled(classLoader, managerClass)
        hookRegisterGnssStatusCallback(classLoader, managerClass)
    }

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
            val virtual = backend.currentLocation()
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
            val virtual: Location? = backend.currentLocation()
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
            val virtual: Location? = backend.currentLocation()
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
            // before：替换上报位置为虚拟位置，然后继续原始分发链路
            val virtual: Location? = backend.currentLocation()
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
                val virtual: Location? = backend.currentLocation()
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
            val virtual: Location? = backend.currentLocation()
            if (virtual != null) {
                try {
                    val listener = chain.getArg(2) ?: return@register null
                    val pkg = chain.getArg(3) as? String ?: "?"
                    hookListenerOnLocationChanged(listener, virtual)
                    pushVirtualLocation(listener, virtual)
                    ZLog.i(TAG_SCOPE, "registerLocationListener pkg=$pkg listener=${listener.javaClass.name} pushed virtual")
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "registerLocationListener virtual push failed", t)
                }
            }
            null
        }
        if (ok) {
            ZLog.i(TAG_SCOPE, "hooked $className.registerLocationListener")
        }
    }

    /** 已 hook 过 onLocationChanged 的 listener class（Binder Proxy class 全局共享，只需 hook 一次）。 */
    private val hookedListenerClasses = Collections.synchronizedSet(HashSet<Class<*>>())

    /**
     * 对 listener 的 onLocationChanged 做参数替换。
     *
     * 支持 Android 12+ 的 onLocationChanged(List<Location>, IRemoteCallback)
     * 与旧式 onLocationChanged(Location)。
     */
    private fun hookListenerOnLocationChanged(listener: Any, virtual: Location) {
        val cls = listener.javaClass
        if (!hookedListenerClasses.add(cls)) return
        cls.methods.filter { it.name == "onLocationChanged" }.forEach { m ->
            try {
                val ok = registrar.register(m) { chain ->
                    val virtualNow: Location? = backend.currentLocation()
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
            ZLog.w(TAG_SCOPE, "push virtual location to listener failed", t)
        }
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
}
