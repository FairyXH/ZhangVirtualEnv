package io.github.fairyxh.VirtualEnv.hook

import android.location.Location
import io.github.fairyxh.VirtualEnv.core.Backend
import io.github.fairyxh.VirtualEnv.profile.ProfileManager
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject

/**
 * Location Hook Adapter（Phase 1）。
 *
 * 职责边界：
 * - 只做 Android 接口适配（类名/方法/参数差异由 Profile 决定）
 * - 不保存任何业务状态，每次调用通过 [Backend.currentLocation] 获取虚拟位置
 * - 失败时放行原始逻辑（fail-open）
 *
 * Hook 点（依据 docs/reverse/目标类分析.md）：
 * 1. LocationManagerService.getLastLocation(...)  → after 替换返回值
 * 2. GnssLocationProvider.onReportLocation(boolean, Location) → before 替换位置参数
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
        val gnssClass = cfg.optString("gnssClass", "com.android.server.location.gnss.GnssLocationProvider")

        hookGetLastLocation(classLoader, managerClass)
        hookReportLocation(classLoader, gnssClass)
    }

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

    private fun hookReportLocation(classLoader: ClassLoader, className: String) {
        val clazz = HookSupport.findClass(classLoader, className) ?: return
        val method = HookSupport.findMethods(clazz, "onReportLocation")
            .firstOrNull { m ->
                m.parameterTypes.size == 2 &&
                    m.parameterTypes[0] == Boolean::class.java &&
                    Location::class.java.isAssignableFrom(m.parameterTypes[1])
            }
        if (method == null) {
            ZLog.w(TAG_SCOPE, "onReportLocation not found in $className")
            return
        }
        val ok = registrar.register(method) { chain ->
            // before：替换上报位置为虚拟位置，然后继续原始分发链路
            val virtual: Location? = backend.currentLocation()
            if (virtual != null) {
                chain.proceed(arrayOf(chain.getArg(0), virtual))
            } else {
                chain.proceed()
            }
            null
        }
        if (ok) {
            ZLog.i(TAG_SCOPE, "hooked $className.onReportLocation")
        }
    }
}
