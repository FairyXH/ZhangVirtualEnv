package io.github.fairyxh.VirtualEnv.hook

import android.location.Location
import io.github.fairyxh.VirtualEnv.core.Backend
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject

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
        private const val LOCATION_RESULT_CLASS = "android.location.LocationResult"
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
                    invokeCallbackOnLocation(callbackClass, callback, virtual)
                    ZLog.d(TAG_SCOPE, "getCurrentLocation -> virtual ${virtual.latitude},${virtual.longitude}")
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
        val locationResultClass = try {
            Class.forName(LOCATION_RESULT_CLASS, false, classLoader)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "LocationResult class not found", t)
            null
        }
        if (locationResultClass == null) return

        val ok = registrar.register(method) { chain ->
            val virtual: Location? = backend.currentLocation()
            if (virtual != null) {
                try {
                    // 构造 LocationResult 替换上报位置（兼容 AOSP wrap(Location) 与 ColorOS wrap(List)）
                    val virtualResult = createLocationResult(locationResultClass, virtual)
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

    /**
     * 反射构造 LocationResult。
     *
     * Android 15 AOSP：LocationResult.wrap(Location)
     * ColorOS/Oplus 15：LocationResult.wrap(List<Location>)（已实测，见真机日志）
     * 兜底：LocationResult.create(List<Location>)
     */
    private fun createLocationResult(locationResultClass: Class<*>, location: Location): Any {
        // 1. wrap(Location)
        try {
            val m = locationResultClass.getMethod("wrap", Location::class.java)
            return m.invoke(null, location)
        } catch (_: NoSuchMethodException) {
        }
        // 2. wrap(List)
        try {
            val m = locationResultClass.getMethod("wrap", List::class.java)
            return m.invoke(null, listOf(location))
        } catch (_: NoSuchMethodException) {
        }
        // 3. create(List)
        try {
            val m = locationResultClass.getMethod("create", List::class.java)
            return m.invoke(null, listOf(location))
        } catch (_: NoSuchMethodException) {
        }
        throw NoSuchMethodException("no LocationResult factory: wrap(Location)/wrap(List)/create(List)")
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
        val locationResultClass = try {
            Class.forName(LOCATION_RESULT_CLASS, false, classLoader)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "LocationResult class not found", t)
            null
        }
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
                        val virtualResult = createLocationResult(locationResultClass, virtual)
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
}
