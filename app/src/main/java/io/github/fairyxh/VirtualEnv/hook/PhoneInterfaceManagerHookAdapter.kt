package io.github.fairyxh.VirtualEnv.hook

import io.github.fairyxh.VirtualEnv.core.EnvStateCache
import io.github.fairyxh.VirtualEnv.util.ZLog

/**
 * PhoneInterfaceManager Hook Adapter（基站网络定位虚拟化）。
 *
 * 地图网络定位 SDK 调用 `TelephonyManager.getAllCellInfo()` /
 * `getCellLocation()` / `getNeighboringCellInfo()` 读取基站信息，发往厂商
 * 服务器换算坐标。Binder 服务端实现位于 com.android.phone 进程的
 * `com.android.phone.PhoneInterfaceManager`（scope 已包含该进程）。
 *
 * 策略（虚拟定位启用时，fail-open）：
 * - `getAllCellInfo`：返回携带虚拟经纬度的 CellInfoCdma 列表（registered=true）。
 *   与早期"返回空列表"不同：返回空会让百度/微信 SDK 网络定位直接失败，
 *   参考 GhostMapX，改为提供带虚拟坐标的基站，SDK 服务器即可换算到虚拟位置。
 * - `getCellLocation`：返回携带虚拟经纬度的 CellIdentityCdma。
 * - `getNeighboringCellInfo`：返回空列表（邻区不参与主定位）。
 *
 * 本进程通过 [EnvStateCache] 轮询读取 system_server Backend 的状态。
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
                val cell = VirtualCellFactory.buildCellInfoCdma(cache.locationLat(), cache.locationLon())
                if (cell != null) {
                    ZLog.d(TAG_SCOPE, "PhoneInterfaceManager.getAllCellInfo -> virtual cell (${cache.locationLat()},${cache.locationLon()})")
                    listOf(cell)
                } else {
                    ZLog.w(TAG_SCOPE, "PhoneInterfaceManager.getAllCellInfo virtual cell build failed, fallback empty")
                    emptyList<Any>()
                }
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
                val identity = VirtualCellFactory.buildCellIdentityCdma(cache.locationLat(), cache.locationLon())
                if (identity != null) {
                    ZLog.d(TAG_SCOPE, "PhoneInterfaceManager.getCellLocation -> virtual identity")
                    identity
                } else {
                    null
                }
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
