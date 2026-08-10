package io.github.fairyxh.VirtualEnv.hook

import io.github.fairyxh.VirtualEnv.core.EnvStateCache
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.lang.reflect.Method

/**
 * BLE 全局虚拟化（com.android.bluetooth 蓝牙栈内 Hook）。
 *
 * 框架层 `BluetoothLeScanner.startScan` Hook 只在 scope 进程生效，第三方扫描 App
 * 不在 scope.list，无法虚拟化（硬性约束：不得加入第三方 App）。因此改在蓝牙栈进程
 * `com.android.bluetooth` 内 Hook Binder 服务端统一落点：
 *
 * ```
 * App BluetoothLeScanner.startScan
 *   → Binder IBluetoothGatt/IBluetoothScan.startScan
 *   → TransitionalScanHelper.startScan(scannerId, ScanSettings, filters, AttributionSource)
 *       ← 此处 Hook：投递虚拟结果并阻断真实扫描
 * ```
 *
 * 回调投递链路（JADX 逆向 framework.jar + 蓝牙_15.0.0.apk，ColorOS 15）：
 * - App 端 `BluetoothLeScanner.BleScanCallbackWrapper.onScanResult(ScanResult)` 会先检查
 *   `mScannerId > 0`，未注册（onScannerRegistered 未到达）时直接丢弃结果；
 * - 真实时序：App `startScan` → Binder `registerScanner` → 栈内 HAL 注册 →
 *   `onScannerRegistered(0, scannerId)` 回调 App → App 端 `mScannerId = scannerId`
 *   后才 Binder `startScan(scannerId, ...)`。因此本 Hook 被调用时 App 端已注册，
 *   直接 `IScannerCallback.onScanResult(ScanResult)` 即可到达 ScanCallback。
 * - Oplus `TransitionalScanHelper.ScannerMap` 继承 `ContextMap`：`getById(int)`
 *   返回 `ContextMap.App`，`App.callback` 为 public 字段（IScannerCallback Binder Proxy）。
 *
 * 若 ContextMap 中找不到 scannerId（Oplus UUID 注册时序差异），遍历全部已注册 App
 * 兜底解析 callback；仍失败则放行真实扫描（fail-open）。
 */
class BleStackHookAdapter(
    private val cache: EnvStateCache,
    private val registrar: HookRegistrar,
    private val logSink: ((Int, String, String) -> Unit)? = null,
) {

    companion object {
        private const val TAG_SCOPE = "Hook"
        private const val CLASS_TRANSITIONAL_SCAN_HELPER =
            "com.android.bluetooth.le_scan.TransitionalScanHelper"
        private const val CLASS_SCAN_CONTROLLER =
            "com.android.bluetooth.le_scan.ScanController"
        private const val CLASS_GATT_SERVICE =
            "com.android.bluetooth.gatt.GattService"
    }

    fun install(classLoader: ClassLoader): Int {
        // Binder 入口：ScanController$BluetoothScanBinder.startScan（IBluetoothScan.Stub）
        // Android 15 scanManagerRefactor 后 App BluetoothLeScanner.startScan 直接走这里，
        // 最接近调用方；同时保留 TransitionalScanHelper.startScan 兼容旧 GattService 路径。
        var hooked = hookBinderStartScan(classLoader)
        hooked += hookGattBinderStartScan(classLoader)
        hooked += hookTransitionalStartScan(classLoader)
        // 用 LSPosed logdaemon 输出（android.util.Log 在部分系统进程可能不可见）
        logSink?.invoke(4, "ZVirtualEnv", "[Hook] ble install classLoader=$classLoader hooked=$hooked")
        return hooked
    }

    private fun hookGattBinderStartScan(classLoader: ClassLoader): Int {
        var hooked = 0
        try {
            val binderClazz = HookSupport.findClass(classLoader, "$CLASS_GATT_SERVICE\$BluetoothGattBinder") ?: return 0
            HookSupport.findMethods(binderClazz, "startScan")
                .filter {
                    it.parameterCount == 4 && it.parameterTypes[3].simpleName == "AttributionSource"
                }
                .forEach { method ->
                    val ok = registrar.register(method) { chain ->
                        val binder = chain.getThisObject()
                        val scannerId = (chain.getArg(0) as? Int) ?: -1
                        // 从 binder 取 GattService → TransitionalScanHelper（用于解析 scannerMap）
                        val helper = try {
                            val service = binder.javaClass.getMethod("getService").invoke(binder)
                            service.javaClass.getMethod("getTransitionalScanHelper").invoke(service)
                        } catch (_: Throwable) {
                            null
                        }
                        if (helper != null) {
                            deliverVirtual(helper, scannerId) ?: chain.proceed()
                        } else {
                            chain.proceed()
                        }
                        null
                    }
                    if (ok) {
                        hooked++
                        ZLog.i(TAG_SCOPE, "hooked GattService.BluetoothGattBinder.startScan(${method.parameterTypes.joinToString { it.simpleName }})")
                    }
                }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "GattService.BluetoothGattBinder hook failed", t)
        }
        return hooked
    }

    private fun hookBinderStartScan(classLoader: ClassLoader): Int {
        var hooked = 0
        try {
            val binderClazz = HookSupport.findClass(classLoader, "$CLASS_SCAN_CONTROLLER\$BluetoothScanBinder") ?: return 0
            HookSupport.findMethods(binderClazz, "startScan")
                .filter {
                    it.parameterCount == 4 && it.parameterTypes[3].simpleName == "AttributionSource"
                }
                .forEach { method ->
                    val ok = registrar.register(method) { chain ->
                        val binder = chain.getThisObject()
                        val scannerId = (chain.getArg(0) as? Int) ?: -1
                        // 从 binder 取 ScanController → TransitionalScanHelper（用于解析 scannerMap）
                        val helper = try {
                            val controller = binder.javaClass.getMethod("getScanController").invoke(binder)
                            controller.javaClass.getMethod("getTransitionalScanHelper").invoke(controller)
                        } catch (_: Throwable) {
                            null
                        }
                        if (helper != null) {
                            deliverVirtual(helper, scannerId) ?: chain.proceed()
                        } else {
                            chain.proceed()
                        }
                        null
                    }
                    if (ok) {
                        hooked++
                        ZLog.i(TAG_SCOPE, "hooked ScanController.BluetoothScanBinder.startScan(${method.parameterTypes.joinToString { it.simpleName }})")
                    }
                }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "ScanController.BluetoothScanBinder hook failed", t)
        }
        return hooked
    }

    private fun hookTransitionalStartScan(classLoader: ClassLoader): Int {
        val clazz = HookSupport.findClass(classLoader, CLASS_TRANSITIONAL_SCAN_HELPER) ?: return -1
        var hooked = 0
        HookSupport.findMethods(clazz, "startScan")
            .filter {
                it.parameterCount == 4 && it.parameterTypes[3].simpleName == "AttributionSource"
            }
            .forEach { method ->
                val ok = registrar.register(method) { chain ->
                    val helper = chain.getThisObject()
                    val scannerId = (chain.getArg(0) as? Int) ?: -1
                    deliverVirtual(helper, scannerId) ?: chain.proceed()
                    null
                }
                if (ok) {
                    hooked++
                    ZLog.i(TAG_SCOPE, "hooked TransitionalScanHelper.startScan(${method.parameterTypes.joinToString { it.simpleName }})")
                }
            }
        if (hooked == 0) ZLog.w(TAG_SCOPE, "TransitionalScanHelper.startScan candidates not found")
        return hooked
    }

    /**
     * 向发起扫描的 App 投递虚拟扫描结果并阻断真实扫描。
     *
     * @return true=已投递并阻断；null=未启用虚拟 BLE 或投递失败（放行真实扫描）
     */
    private fun deliverVirtual(helper: Any, scannerId: Int): Boolean? {
        logSink?.invoke(4, "ZVirtualEnv", "[Hook] ble stack startScan id=$scannerId invoked")
        val virtual = cache.currentBle()
        if (virtual == null) {
            // 诊断：未启用虚拟 BLE（或缓存未拉到），fail-open 放行真实扫描
            logSink?.invoke(4, "ZVirtualEnv", "[Hook] ble stack startScan id=$scannerId cache=null, fail-open real scan")
            ZLog.d(TAG_SCOPE, "ble stack startScan id=$scannerId cache=null, fail-open real scan")
            return null
        }
        logSink?.invoke(4, "ZVirtualEnv", "[Hook] ble stack startScan id=$scannerId cache devices=${virtual.optJSONArray("devices")?.length() ?: 0}")
        ZLog.d(TAG_SCOPE, "ble stack startScan id=$scannerId cache enabled=${virtual.optBoolean("enabled", true)} devices=${virtual.optJSONArray("devices")?.length() ?: 0}")
        try {
            val results = VirtualBleFactory.buildScanResults(virtual, logSink)
            logSink?.invoke(4, "ZVirtualEnv", "[Hook] ble stack build results=${results.size} from devices=${virtual.optJSONArray("devices")?.length() ?: 0} bonded=${virtual.optJSONArray("bonded")?.length() ?: 0}")
            val callback = resolveCallback(helper, scannerId) ?: run {
                logSink?.invoke(4, "ZVirtualEnv", "[Hook] ble stack scan app not registered id=$scannerId, fallback real scan")
                ZLog.d(TAG_SCOPE, "ble stack scan app not registered id=$scannerId, fallback real scan")
                return null
            }
            logSink?.invoke(4, "ZVirtualEnv", "[Hook] ble stack callback resolved class=${callback.javaClass.name}")

            val resultClass = Class.forName("android.bluetooth.le.ScanResult")
            val onScanResult: Method = try {
                callback.javaClass.getMethod("onScanResult", resultClass)
            } catch (_: NoSuchMethodException) {
                Class.forName("android.bluetooth.le.IScannerCallback")
                    .getMethod("onScanResult", resultClass)
            }
            results.forEach { onScanResult.invoke(callback, it) }
            // 启用即覆盖：空配置也阻断真实扫描（App 收不到真实设备）
            logSink?.invoke(4, "ZVirtualEnv", "[Hook] ble stack startScan -> virtual ${results.size} results delivered")
            ZLog.i(TAG_SCOPE, "ble stack startScan -> virtual ${results.size} results")
            return true
        } catch (t: Throwable) {
            logSink?.invoke(4, "ZVirtualEnv", "[Hook] deliver virtual ble stack failed: ${t.message}")
            ZLog.w(TAG_SCOPE, "deliver virtual ble stack failed, fallback", t)
            return null
        }
    }

    /**
     * 从 ContextMap 中解析 scannerId 对应的 IScannerCallback。
     * Oplus 的 ScannerMap 继承 ContextMap：`getById(int)` 返回 `ContextMap.App`，
     * `App.callback` 是 public 字段。部分 ROM 用 UUID 注册而非 id，做遍历兜底。
     */
    private fun resolveCallback(helper: Any, scannerId: Int): Any? {
        try {
            val scannerMap = helper.javaClass.getMethod("getScannerMap").invoke(helper)
            val getById = scannerMap.javaClass.getMethod("getById", Int::class.java)
            val app = getById.invoke(scannerMap, scannerId)
            if (app != null) {
                val cb = app.javaClass.getField("callback").get(app)
                if (cb != null) return cb
            }
            // 兜底：遍历全部已注册 app（UUID 注册时 id 可能未回填）
            val getAllIds = scannerMap.javaClass.getMethod("getAllAppsIds")
            val ids = getAllIds.invoke(scannerMap) as? java.util.List<*> ?: return null
            for (id in ids) {
                val idInt = id as? Int ?: continue
                val app2 = getById.invoke(scannerMap, idInt) ?: continue
                val cb = app2.javaClass.getField("callback").get(app2)
                if (cb != null) return cb
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "resolve ble callback failed", t)
        }
        return null
    }
}
