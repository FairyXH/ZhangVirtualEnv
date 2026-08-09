package io.github.fairyxh.VirtualEnv.hook

import io.github.fairyxh.VirtualEnv.core.EnvStateCache
import io.github.fairyxh.VirtualEnv.util.ZLog

/**
 * PhoneInterfaceManager Hook Adapter（基站网络定位全局阻断）。
 *
 * 高德等地图的网络定位 SDK 调用 `TelephonyManager.getAllCellInfo()` /
 * `getCellLocation()` / `getNeighboringCellInfo()` 读取真实基站信息，发往
 * 厂商服务器换算真实坐标。这三个方法的 Binder 服务端实现位于 com.android.phone
 * 进程的 `com.android.phone.PhoneInterfaceManager`（scope 已包含该进程）。
 *
 * Hook 服务端方法后对所有调用方（含第三方地图）全局生效：
 * - `getAllCellInfo`：启用时返回空 List（无基站）
 * - `getCellLocation`：启用时返回 null（无小区）
 * - `getNeighboringCellInfo`：启用时返回空 List
 *
 * 本进程通过 [EnvStateCache] 轮询读取 system_server Backend 的位置虚拟化开关，
 * 不在 Hook 内保存业务状态。
 */
class PhoneInterfaceManagerHookAdapter(
    private val cache: EnvStateCache,
    private val registrar: HookRegistrar,
) {

    companion object {
        private const val TAG_SCOPE = "Hook"
        private const val CLASS_NAME = "com.android.phone.PhoneInterfaceManager"
    }

    fun install(classLoader: ClassLoader) {
        hookGetAllCellInfo(classLoader)
        hookGetCellLocation(classLoader)
        hookGetNeighboringCellInfo(classLoader)
    }

    private fun virtualLocationEnabled(): Boolean = cache.isLocationEnabled()

    // ---------- getAllCellInfo(String, String): List<CellInfo> ----------

    private fun hookGetAllCellInfo(classLoader: ClassLoader) {
        val clazz = HookSupport.findClass(classLoader, CLASS_NAME) ?: return
        val method = HookSupport.findMethods(clazz, "getAllCellInfo")
            .firstOrNull { it.parameterCount == 2 }
        if (method == null) {
            ZLog.w(TAG_SCOPE, "PhoneInterfaceManager.getAllCellInfo not found")
            return
        }
        val ok = registrar.register(method) { chain ->
            val original = chain.proceed()
            if (!virtualLocationEnabled()) return@register original
            try {
                ZLog.d(TAG_SCOPE, "PhoneInterfaceManager.getAllCellInfo -> empty (virtual location)")
                emptyList<Any>()
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "PhoneInterfaceManager.getAllCellInfo virtual failed, fallback", t)
                original
            }
        }
        if (ok) ZLog.i(TAG_SCOPE, "hooked PhoneInterfaceManager.getAllCellInfo")
    }

    // ---------- getCellLocation(String, String): CellIdentity ----------

    private fun hookGetCellLocation(classLoader: ClassLoader) {
        val clazz = HookSupport.findClass(classLoader, CLASS_NAME) ?: return
        val method = HookSupport.findMethods(clazz, "getCellLocation")
            .firstOrNull { it.parameterCount == 2 }
        if (method == null) {
            ZLog.w(TAG_SCOPE, "PhoneInterfaceManager.getCellLocation not found")
            return
        }
        val ok = registrar.register(method) { chain ->
            val original = chain.proceed()
            if (!virtualLocationEnabled()) return@register original
            try {
                ZLog.d(TAG_SCOPE, "PhoneInterfaceManager.getCellLocation -> null (virtual location)")
                null
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "PhoneInterfaceManager.getCellLocation virtual failed, fallback", t)
                original
            }
        }
        if (ok) ZLog.i(TAG_SCOPE, "hooked PhoneInterfaceManager.getCellLocation")
    }

    // ---------- getNeighboringCellInfo(String, String): List<NeighboringCellInfo> ----------

    private fun hookGetNeighboringCellInfo(classLoader: ClassLoader) {
        val clazz = HookSupport.findClass(classLoader, CLASS_NAME) ?: return
        val method = HookSupport.findMethods(clazz, "getNeighboringCellInfo")
            .firstOrNull { it.parameterCount == 2 }
        if (method == null) {
            ZLog.w(TAG_SCOPE, "PhoneInterfaceManager.getNeighboringCellInfo not found")
            return
        }
        val ok = registrar.register(method) { chain ->
            val original = chain.proceed()
            if (!virtualLocationEnabled()) return@register original
            try {
                ZLog.d(TAG_SCOPE, "PhoneInterfaceManager.getNeighboringCellInfo -> empty (virtual location)")
                emptyList<Any>()
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "PhoneInterfaceManager.getNeighboringCellInfo virtual failed, fallback", t)
                original
            }
        }
        if (ok) ZLog.i(TAG_SCOPE, "hooked PhoneInterfaceManager.getNeighboringCellInfo")
    }
}
