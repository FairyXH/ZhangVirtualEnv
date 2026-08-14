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

    /** 节流：同一调用点 5s 内只打一条 I 级观测日志。 */
    private val lastCallLog = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun logCallOnce(key: String, msg: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastCallLog[key] ?: 0L
        if (now - last >= 5000L) {
            lastCallLog[key] = now
            ZLog.i(TAG_SCOPE, msg)
        }
    }

    fun install(classLoader: ClassLoader): Int {
        var hooked = 0
        hooked += hookGetAllCellInfo(classLoader)
        hooked += hookGetCellLocation(classLoader)
        hooked += hookGetNeighboringCellInfo(classLoader)
        hooked += hookRequestCellInfoUpdate(classLoader)
        return hooked
    }

    // ---------- requestCellInfoUpdate(int, ICellInfoCallback, String, String) ----------

    /**
     * Android 10+ 异步基站回调（百度 SDK 9.1.6 主链路，见 docs/reverse/baidu-sdk-nmea-cellinfo-analysis.md）。
     *
     * 客户端 `TelephonyManager.requestCellInfoUpdate(Executor, CellInfoCallback)` → Binder
     * `ITelephony.requestCellInfoUpdate(subId, ICellInfoCallback, pkg, attr)` →
     * 本方法。原实现通过 sendRequestAsync 异步把**真实** CellInfo 回调给 App。
     *
     * 虚拟定位启用时：不 proceed 原始链路，直接反射调用 `ICellInfoCallback.onCellInfo(List)`
     * 投递虚拟基站（优先 cell 配置，回退带虚拟经纬度的 CDMA），与 getAllCellInfo 保持同一策略。
     */
    private fun hookRequestCellInfoUpdate(classLoader: ClassLoader): Int {
        val clazz = HookSupport.findClass(classLoader, CLASS_NAME) ?: return 0
        val methods = HookSupport.findMethods(clazz, "requestCellInfoUpdate")
            .filter { it.parameterCount == 4 && it.parameterTypes[1].simpleName.contains("ICellInfoCallback") }
        if (methods.isEmpty()) {
            ZLog.w(TAG_SCOPE, "PhoneInterfaceManager.requestCellInfoUpdate(4) not found")
            return 0
        }
        var hooked = 0
        methods.forEach { method ->
            val ok = registrar.register(method) { chain ->
                // 严格放行：只有虚拟定位**且基站模拟已开启**才接管；否则走原始真实链路
                if (!virtualLocationEnabled() || cache.currentCell() == null) {
                    chain.proceed()
                    return@register null
                }
                try {
                    val callback = chain.getArg(1)
                    val pkg = chain.getArg(2) as? String ?: "?"
                    val cells = buildVirtualCells()
                    invokeCellInfoCallback(callback, cells)
                    ZLog.i(TAG_SCOPE, "PhoneInterfaceManager.requestCellInfoUpdate pkg=$pkg -> virtual ${cells.size} cells")
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "PhoneInterfaceManager.requestCellInfoUpdate virtual failed, fallback", t)
                    chain.proceed()
                }
                null
            }
            if (ok) {
                hooked++
                ZLog.i(TAG_SCOPE, "hooked PhoneInterfaceManager.requestCellInfoUpdate(${method.parameterCount})")
            }
        }
        return hooked
    }

    /** 构造虚拟基站列表：仅当基站模拟配置可用时生成；否则返回空（调用方放行真实数据）。 */
    private fun buildVirtualCells(): List<Any> {
        val cellData = cache.currentCell() ?: return emptyList()
        try {
            val list = VirtualCellFactory.buildCellInfoList(cellData)
            if (list.isNotEmpty()) return list
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "requestCellInfoUpdate config build failed, fallback cdma", t)
        }
        val cellCount = cellData.optJSONArray("entries")?.length()?.coerceIn(1, 16) ?: 1
        return (0 until cellCount).mapNotNull {
            VirtualCellFactory.buildCellInfoCdma(cache.locationLat(), cache.locationLon())
        }
    }

    /** 反射调用 ICellInfoCallback.onCellInfo(List<CellInfo>)。 */
    private fun invokeCellInfoCallback(callback: Any, cells: List<Any>) {
        val method = try {
            callback.javaClass.getMethod("onCellInfo", List::class.java)
        } catch (t: Throwable) {
            // 部分 ROM 参数为 ArrayList/具体类型，取 declared 兼容
            callback.javaClass.declaredMethods.firstOrNull {
                it.name == "onCellInfo" && it.parameterCount == 1
            } ?: throw t
        }
        method.isAccessible = true
        method.invoke(callback, cells)
    }

    private fun virtualLocationEnabled(): Boolean = cache.isLocationEnabled()

    // ---------- getAllCellInfo(String, String): List<CellInfo> ----------

    private fun hookGetAllCellInfo(classLoader: ClassLoader): Int {
        val clazz = HookSupport.findClass(classLoader, CLASS_NAME) ?: return 0
        val method = HookSupport.findMethods(clazz, "getAllCellInfo")
            .firstOrNull { it.parameterCount == 2 }
        if (method == null) {
            ZLog.w(TAG_SCOPE, "PhoneInterfaceManager.getAllCellInfo not found")
            return 0
        }
        val ok = registrar.register(method) { chain ->
            val original = chain.proceed()
            val pkg = if (chain.args.isNotEmpty()) chain.getArg(0) as? String else null
            // 严格放行：基站模拟未开启（即使虚拟定位开启）→ 返回原始真实基站
            val cellData = cache.currentCell()
            if (cellData == null) {
                logCallOnce("all|$pkg", "PhoneInterfaceManager.getAllCellInfo pkg=$pkg -> real (cell sim off)")
                return@register original
            }
            try {
                val list = VirtualCellFactory.buildCellInfoList(cellData)
                if (list.isNotEmpty()) {
                    logCallOnce("all|$pkg", "PhoneInterfaceManager.getAllCellInfo pkg=$pkg -> virtual ${list.size} cells from config")
                    return@register list
                }
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "PhoneInterfaceManager.getAllCellInfo config build failed, fallback cdma", t)
            }
            if (!virtualLocationEnabled()) return@register original
            try {
                // 配置存在但全部构建失败：按 entries 数量回退带虚拟经纬度的 CDMA
                val cellCount = cellData.optJSONArray("entries")?.length()?.coerceIn(1, 16) ?: 1
                val cells = (0 until cellCount).mapNotNull {
                    VirtualCellFactory.buildCellInfoCdma(cache.locationLat(), cache.locationLon())
                }
                if (cells.isNotEmpty()) {
                    logCallOnce(
                        "all|$pkg",
                        "PhoneInterfaceManager.getAllCellInfo pkg=$pkg -> $cellCount virtual cells (${cache.locationLat()},${cache.locationLon()})"
                    )
                    cells
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
        return if (ok) 1 else 0
    }

    // ---------- getCellLocation(String, String): CellIdentity ----------

    private fun hookGetCellLocation(classLoader: ClassLoader): Int {
        val clazz = HookSupport.findClass(classLoader, CLASS_NAME) ?: return 0
        val method = HookSupport.findMethods(clazz, "getCellLocation")
            .firstOrNull { it.parameterCount == 2 }
        if (method == null) {
            ZLog.w(TAG_SCOPE, "PhoneInterfaceManager.getCellLocation not found")
            return 0
        }
        val ok = registrar.register(method) { chain ->
            val original = chain.proceed()
            // 基站模拟未开启 → 放行真实 CellIdentity
            if (cache.currentCell() == null) return@register original
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
        return if (ok) 1 else 0
    }

    // ---------- getNeighboringCellInfo(String, String): List<NeighboringCellInfo> ----------

    private fun hookGetNeighboringCellInfo(classLoader: ClassLoader): Int {
        val clazz = HookSupport.findClass(classLoader, CLASS_NAME) ?: return 0
        val method = HookSupport.findMethods(clazz, "getNeighboringCellInfo")
            .firstOrNull { it.parameterCount == 2 }
        if (method == null) {
            ZLog.w(TAG_SCOPE, "PhoneInterfaceManager.getNeighboringCellInfo not found")
            return 0
        }
        val ok = registrar.register(method) { chain ->
            val original = chain.proceed()
            // 基站模拟未开启 → 放行真实邻区
            if (cache.currentCell() == null) return@register original
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
        return if (ok) 1 else 0
    }
}
