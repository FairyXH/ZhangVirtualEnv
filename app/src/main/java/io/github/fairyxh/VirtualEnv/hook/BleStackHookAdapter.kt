package io.github.fairyxh.VirtualEnv.hook

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.fairyxh.VirtualEnv.core.EnvStateCache
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

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
        private const val CLASS_ADAPTER_SERVICE =
            "com.android.bluetooth.btservice.AdapterService"
        private const val CLASS_REMOTE_DEVICES =
            "com.android.bluetooth.btservice.RemoteDevices"

        const val ACTION_FOUND = "android.bluetooth.device.action.FOUND"
        const val ACTION_DISCOVERY_STARTED = "android.bluetooth.adapter.action.DISCOVERY_STARTED"
        const val ACTION_DISCOVERY_FINISHED = "android.bluetooth.adapter.action.DISCOVERY_FINISHED"
        const val ACTION_BOND_STATE_CHANGED = "android.bluetooth.device.action.BOND_STATE_CHANGED"
        const val PERM_BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
        const val PERM_BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
        const val BOND_NONE = 10
        const val BOND_BONDING = 11
        const val BOND_BONDED = 12
        private const val BOND_DELIVER_MS = 900L
        private const val CLASSIC_DELIVER_INTERVAL_MS = 800L
    }

    /** 虚拟已配对地址集合（createBond 模拟成功后加入；getBondState 返回 BONDED）。 */
    private val virtualBonded = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** 经典/双模虚拟发现进行中（阻断真实 HAL 发现与真实 deviceFoundCallback）。 */
    private val virtualDiscoveryActive = AtomicBoolean(false)
    private val classicQueue = mutableListOf<JSONObject>()
    private val callingPackages = mutableListOf<String>()
    private var discoveryHandler: Handler? = null
    private var discoveryService: Any? = null

    fun install(classLoader: ClassLoader): Int {
        // Binder 入口：ScanController$BluetoothScanBinder.startScan（IBluetoothScan.Stub）
        // Android 15 scanManagerRefactor 后 App BluetoothLeScanner.startScan 直接走这里，
        // 最接近调用方；同时保留 TransitionalScanHelper.startScan 兼容旧 GattService 路径。
        var hooked = hookBinderStartScan(classLoader)
        hooked += hookGattBinderStartScan(classLoader)
        hooked += hookTransitionalStartScan(classLoader)
        // 经典 BR/EDR 与双模设备发现（startDiscovery / 发现结果广播）
        hooked += hookClassicDiscovery(classLoader)
        // 虚拟设备配对（createBond → BONDED；getBondState/removeBond 同步）
        hooked += hookBonding(classLoader)
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
            // 广播间隔（ms）：配置 >0 时按间隔逐个投递，模拟周期性广播；0/缺省立即全部投递
            val intervalMs = virtual.optInt("intervalMs", 0).coerceAtLeast(0)
            if (intervalMs > 0 && results.size > 1) {
                val handler = Handler(Looper.getMainLooper())
                results.forEachIndexed { idx, res ->
                    handler.postDelayed({
                        try {
                            onScanResult.invoke(callback, res)
                        } catch (t: Throwable) {
                            ZLog.w(TAG_SCOPE, "deliver delayed scan result failed", t)
                        }
                    }, idx.toLong() * intervalMs)
                }
                logSink?.invoke(4, "ZVirtualEnv", "[Hook] ble stack startScan -> virtual ${results.size} results scheduled interval=${intervalMs}ms")
                ZLog.i(TAG_SCOPE, "ble stack startScan -> virtual ${results.size} results interval=${intervalMs}ms")
            } else {
                results.forEach { onScanResult.invoke(callback, it) }
                logSink?.invoke(4, "ZVirtualEnv", "[Hook] ble stack startScan -> virtual ${results.size} results delivered")
                ZLog.i(TAG_SCOPE, "ble stack startScan -> virtual ${results.size} results")
            }
            // 启用即覆盖：空配置也阻断真实扫描（App 收不到真实设备）
            return true
        } catch (t: Throwable) {
            logSink?.invoke(4, "ZVirtualEnv", "[Hook] deliver virtual ble stack failed: ${t.message}")
            ZLog.w(TAG_SCOPE, "deliver virtual ble stack failed, fallback", t)
            return null
        }
    }

    /**
     * 经典 BR/EDR 与双模设备发现虚拟化（Oplus 15 蓝牙栈）。
     *
     * 链路（JADX 逆向 Bluetooth_15.0.0.apk）：
     * - App `BluetoothAdapter.startDiscovery()` → Binder → `AdapterService.startDiscovery(AttributionSource)`
     *   → `mNativeInterface.startDiscovery()`（HAL）。本 Hook 在 HAL 前拦截：配置含经典设备时
     *   返回 true 并投递虚拟设备（阻断真实 HAL 发现）。
     * - 真实结果：HAL `deviceFoundCallback(byte[])` → `RemoteDevices.deviceFoundCallback` →
     *   对每个 DiscoveringPackage 发 ACTION_FOUND 广播（蓝牙栈进程直接发，不经 system_server）。
     *   本 Hook 在虚拟发现激活期间丢弃真实回调，改为按虚拟设备发 ACTION_FOUND。
     * - 发现状态：AdapterProperties.discoveryStateChangeCallback 发 DISCOVERY_STARTED/FINISHED
     *   （`AdapterService.sendBroadcast(Intent, BLUETOOTH_SCAN, options)`），虚拟投递同样模拟。
     */
    private fun hookClassicDiscovery(classLoader: ClassLoader): Int {
        var hooked = 0
        try {
            val serviceClass = HookSupport.findClass(classLoader, CLASS_ADAPTER_SERVICE) ?: return 0
            // AdapterService.startDiscovery(AttributionSource): boolean
            HookSupport.findMethods(serviceClass, "startDiscovery")
                .firstOrNull { it.parameterCount == 1 && it.parameterTypes[0].simpleName == "AttributionSource" }
                ?.let { method ->
                    val ok = registrar.register(method) { chain ->
                        val service = chain.getThisObject()
                        val caller = try {
                            val attr = chain.getArg(0)
                            attr?.javaClass?.getMethod("getPackageName")?.invoke(attr) as? String
                        } catch (_: Throwable) {
                            null
                        }
                        if (startVirtualDiscovery(service, caller)) {
                            return@register true
                        }
                        chain.proceed()
                    }
                    if (ok) {
                        hooked++
                        ZLog.i(TAG_SCOPE, "hooked AdapterService.startDiscovery")
                    }
                }
            // AdapterService.cancelDiscovery(AttributionSource): boolean
            HookSupport.findMethods(serviceClass, "cancelDiscovery")
                .firstOrNull { it.parameterCount == 1 && it.parameterTypes[0].simpleName == "AttributionSource" }
                ?.let { method ->
                    val ok = registrar.register(method) { chain ->
                        if (virtualDiscoveryActive.get()) {
                            finishVirtualDiscovery(chain.getThisObject())
                            return@register true
                        }
                        chain.proceed()
                    }
                    if (ok) {
                        hooked++
                        ZLog.i(TAG_SCOPE, "hooked AdapterService.cancelDiscovery")
                    }
                }
            // RemoteDevices.deviceFoundCallback(byte[]): 虚拟发现激活期间丢弃真实设备
            HookSupport.findClass(classLoader, CLASS_REMOTE_DEVICES)?.let { rdClass ->
                HookSupport.findMethods(rdClass, "deviceFoundCallback")
                    .firstOrNull { it.parameterCount == 1 }
                    ?.let { method ->
                        val ok = registrar.register(method) { chain ->
                            if (virtualDiscoveryActive.get()) {
                                ZLog.d(TAG_SCOPE, "deviceFoundCallback suppressed (virtual discovery active)")
                                return@register null
                            }
                            chain.proceed()
                        }
                        if (ok) {
                            hooked++
                            ZLog.i(TAG_SCOPE, "hooked RemoteDevices.deviceFoundCallback")
                        }
                    }
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "classic discovery hook failed", t)
        }
        return hooked
    }

    /**
     * 虚拟设备配对模拟（Oplus 15 蓝牙栈）：
     * - `createBond(BluetoothDevice, ...)`：目标地址是虚拟设备（classic/dual）→ 阻断真实
     *   BondStateMachine，投递 BONDING(11) → BONDED(12) 广播，并记入 virtualBonded。
     * - `getBondState(BluetoothDevice)`：虚拟已配对地址返回 12（BONDED）。
     * - `removeBond(BluetoothDevice)`：虚拟已配对地址 → 广播 NONE(10) 并移除，返回 true。
     * 广播格式与 framework 一致：ACTION_BOND_STATE_CHANGED + EXTRA_DEVICE/EXTRA_BOND_STATE/
     * EXTRA_PREVIOUS_BOND_STATE，权限 BLUETOOTH_CONNECT（全局广播）。
     */
    private fun hookBonding(classLoader: ClassLoader): Int {
        var hooked = 0
        try {
            val serviceClass = HookSupport.findClass(classLoader, CLASS_ADAPTER_SERVICE) ?: return 0
            HookSupport.findMethods(serviceClass, "createBond")
                .filter { it.parameterCount >= 1 && it.parameterTypes[0].simpleName == "BluetoothDevice" }
                .forEach { method ->
                    val ok = registrar.register(method) { chain ->
                        val service = chain.getThisObject()
                        val device = chain.getArg(0) as? BluetoothDevice
                        if (device != null && isVirtualDevice(device.address)) {
                            startVirtualBond(service, device)
                            return@register true
                        }
                        chain.proceed()
                    }
                    if (ok) {
                        hooked++
                        ZLog.i(TAG_SCOPE, "hooked AdapterService.createBond")
                    }
                }
            HookSupport.findMethods(serviceClass, "getBondState")
                .filter { it.parameterCount == 1 && it.parameterTypes[0].simpleName == "BluetoothDevice" }
                .forEach { method ->
                    val ok = registrar.register(method) { chain ->
                        val device = chain.getArg(0) as? BluetoothDevice
                        if (device != null && virtualBonded.containsKey(device.address)) {
                            return@register BOND_BONDED
                        }
                        chain.proceed()
                    }
                    if (ok) {
                        hooked++
                        ZLog.i(TAG_SCOPE, "hooked AdapterService.getBondState")
                    }
                }
            HookSupport.findMethods(serviceClass, "removeBond")
                .filter { it.parameterCount == 1 && it.parameterTypes[0].simpleName == "BluetoothDevice" }
                .forEach { method ->
                    val ok = registrar.register(method) { chain ->
                        val service = chain.getThisObject()
                        val device = chain.getArg(0) as? BluetoothDevice
                        if (device != null && virtualBonded.remove(device.address) != null) {
                            deliverBondBroadcast(service, device, BOND_NONE, BOND_BONDED)
                            return@register true
                        }
                        chain.proceed()
                    }
                    if (ok) {
                        hooked++
                        ZLog.i(TAG_SCOPE, "hooked AdapterService.removeBond")
                    }
                }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "bond hook failed", t)
        }
        return hooked
    }

    /** 目标地址是否属于虚拟设备（classic/dual）。 */
    private fun isVirtualDevice(address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        val virtual = cache.currentBle() ?: return false
        val devices = virtual.optJSONArray("devices") ?: return false
        val upper = address.uppercase()
        for (i in 0 until devices.length()) {
            val d = devices.optJSONObject(i) ?: continue
            if (d.optString("address", "").uppercase() == upper && isClassicDevice(d)) return true
        }
        return false
    }

    /** 投递虚拟配对广播：BONDING(11) 立即，BONDED(12) 延迟。 */
    private fun startVirtualBond(service: Any, device: BluetoothDevice) {
        try {
            logSink?.invoke(4, "ZVirtualEnv", "[Hook] ble virtual bond start ${device.address}")
            ZLog.i(TAG_SCOPE, "virtual bond start ${device.address}")
            deliverBondBroadcast(service, device, BOND_BONDING, BOND_NONE)
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({
                virtualBonded[device.address] = true
                deliverBondBroadcast(service, device, BOND_BONDED, BOND_BONDING)
                logSink?.invoke(4, "ZVirtualEnv", "[Hook] ble virtual bond bonded ${device.address}")
                ZLog.i(TAG_SCOPE, "virtual bond bonded ${device.address}")
            }, BOND_DELIVER_MS)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "virtual bond failed", t)
        }
    }

    private fun deliverBondBroadcast(service: Any, device: BluetoothDevice, state: Int, previous: Int) {
        try {
            val intent = Intent(ACTION_BOND_STATE_CHANGED)
            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            intent.putExtra(BluetoothDevice.EXTRA_BOND_STATE, state)
            intent.putExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, previous)
            invokeSendBroadcast(service, intent, PERM_BLUETOOTH_CONNECT)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "send BOND_STATE_CHANGED failed", t)
        }
    }

    /** 启动虚拟经典发现；无经典设备返回 false（调用方放行真实发现）。 */
    private fun startVirtualDiscovery(service: Any, callerPackage: String?): Boolean {
        val virtual = cache.currentBle() ?: return false
        val devices = virtual.optJSONArray("devices") ?: return false
        synchronized(classicQueue) {
            classicQueue.clear()
            for (i in 0 until devices.length()) {
                val d = devices.optJSONObject(i) ?: continue
                if (isClassicDevice(d)) classicQueue.add(d)
            }
            if (classicQueue.isEmpty()) return false
        }
        synchronized(callingPackages) {
            if (!callerPackage.isNullOrBlank() && !callingPackages.contains(callerPackage)) {
                callingPackages.add(callerPackage)
            }
        }
        virtualDiscoveryActive.set(true)
        discoveryService = service
        logSink?.invoke(4, "ZVirtualEnv", "[Hook] ble virtual classic discovery start devices=${classicQueue.size}")
        ZLog.i(TAG_SCOPE, "virtual classic discovery start devices=${classicQueue.size}")
        sendDiscoveryStarted(service)
        val handler = Handler(Looper.getMainLooper())
        discoveryHandler = handler
        val deliver = object : Runnable {
            override fun run() {
                val device = synchronized(classicQueue) { classicQueue.removeFirstOrNull() }
                if (device != null) {
                    deliverClassicDevice(service, device)
                    handler.postDelayed(this, CLASSIC_DELIVER_INTERVAL_MS)
                } else {
                    finishVirtualDiscovery(service)
                }
            }
        }
        handler.postDelayed(deliver, CLASSIC_DELIVER_INTERVAL_MS)
        return true
    }

    private fun isClassicDevice(d: JSONObject): Boolean {
        // mode: "classic" / "dual" 或旧字段 classic=true 均视为经典设备
        val mode = d.optString("mode", "").lowercase()
        return mode == "classic" || mode == "dual" || d.optBoolean("classic", false)
    }

    private fun sendDiscoveryStarted(service: Any) {
        try {
            val intent = Intent(ACTION_DISCOVERY_STARTED)
            invokeSendBroadcast(service, intent, PERM_BLUETOOTH_SCAN)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "send DISCOVERY_STARTED failed", t)
        }
    }

    private fun finishVirtualDiscovery(service: Any) {
        synchronized(classicQueue) { classicQueue.clear() }
        synchronized(callingPackages) { callingPackages.clear() }
        virtualDiscoveryActive.set(false)
        discoveryHandler?.removeCallbacksAndMessages(null)
        discoveryHandler = null
        discoveryService = null
        try {
            val intent = Intent(ACTION_DISCOVERY_FINISHED)
            invokeSendBroadcast(service, intent, PERM_BLUETOOTH_SCAN)
            logSink?.invoke(4, "ZVirtualEnv", "[Hook] ble virtual classic discovery finished")
            ZLog.i(TAG_SCOPE, "virtual classic discovery finished")
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "send DISCOVERY_FINISHED failed", t)
        }
    }

    /** 按真实链路向每个 DiscoveringPackage 发 ACTION_FOUND 广播。 */
    private fun deliverClassicDevice(service: Any, deviceJson: JSONObject) {
        try {
            val address = deviceJson.optString("address", "")
            ZLog.d(TAG_SCOPE, "deliver classic device start addr=$address")
            if (address.isBlank()) return
            val name = deviceJson.optString("name", "")
            val rssi = deviceJson.optInt("classicRssi", deviceJson.optInt("rssi", -60))
            val classOfDevice = deviceJson.optInt("classOfDevice", 0)
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            val device = adapter.getRemoteDevice(address)
            val intent = Intent(ACTION_FOUND)
            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            if (classOfDevice != 0) {
                // BluetoothClass(int) 为 @SystemApi 隐藏构造，编译期不可见，用反射构造
                try {
                    val cls = BluetoothClass::class.java.getConstructor(Int::class.javaPrimitiveType)
                    cls.isAccessible = true
                    intent.putExtra(BluetoothDevice.EXTRA_CLASS, cls.newInstance(classOfDevice))
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "build BluetoothClass failed", t)
                }
            }
            intent.putExtra(BluetoothDevice.EXTRA_RSSI, rssi)
            if (name.isNotBlank()) intent.putExtra(BluetoothDevice.EXTRA_NAME, name)

            val packages = invokeGetDiscoveringPackages(service)
            var delivered = 0
            fun deliverToPackage(pkgName: String, permission: String?) {
                try {
                    intent.setPackage(pkgName)
                    val perms = if (permission.isNullOrBlank()) {
                        arrayOf(PERM_BLUETOOTH_SCAN)
                    } else {
                        arrayOf(PERM_BLUETOOTH_SCAN, permission)
                    }
                    invokeSendBroadcastMultiplePermissions(service, intent, perms)
                    delivered++
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "deliver classic device to package $pkgName failed", t)
                }
            }
            if (packages != null) {
                for (pkg in packages) {
                    try {
                        val p = pkg ?: continue
                        val pkgName = p.javaClass.getMethod("getPackageName").invoke(p) as? String ?: continue
                        val permission = p.javaClass.getMethod("getPermission").invoke(p) as? String
                        deliverToPackage(pkgName, permission)
                    } catch (t: Throwable) {
                        ZLog.w(TAG_SCOPE, "read discovering package failed", t)
                    }
                }
            } else {
                // hook 拦截 startDiscovery 时 DiscoveringPackage 未注册（原方法未执行），
                // 用调用侧记录的包名兜底投递
                val callers = synchronized(callingPackages) { callingPackages.toList() }
                callers.forEach { deliverToPackage(it, null) }
            }
            logSink?.invoke(4, "ZVirtualEnv", "[Hook] ble virtual classic device $name $address rssi=$rssi delivered=$delivered")
            ZLog.i(TAG_SCOPE, "virtual classic device $name $address rssi=$rssi delivered=$delivered")
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "deliverClassicDevice failed", t)
        }
    }

    private fun invokeGetDiscoveringPackages(service: Any): List<*>? {
        return try {
            service.javaClass.methods.firstOrNull { it.name == "getDiscoveringPackages" }
                ?.invoke(service) as? List<*>
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "getDiscoveringPackages failed", t)
            null
        }
    }

    /** 反射调 sendBroadcast(Intent, String[, Bundle])（含继承方法，签名各版本不同）。 */
    private fun invokeSendBroadcast(service: Any, intent: Intent, permission: String) {
        val method = service.javaClass.methods.firstOrNull {
            it.name == "sendBroadcast" && it.parameterCount in 2..3 && it.parameterTypes[0] == Intent::class.java
        } ?: return
        val args = if (method.parameterCount == 3) {
            arrayOf<Any?>(intent, permission, null)
        } else {
            arrayOf<Any?>(intent, permission)
        }
        method.invoke(service, *args)
    }

    /** 反射调 sendBroadcastMultiplePermissions(Intent, String[], Bundle|BroadcastOptions)。 */
    private fun invokeSendBroadcastMultiplePermissions(service: Any, intent: Intent, permissions: Array<String>) {
        val method = service.javaClass.methods.firstOrNull {
            it.name == "sendBroadcastMultiplePermissions" &&
                it.parameterCount in 2..3 && it.parameterTypes[0] == Intent::class.java
        } ?: run {
            // 旧版本没有多权限接口：退化为单权限广播
            invokeSendBroadcast(service, intent, PERM_BLUETOOTH_SCAN)
            return
        }
        val args = if (method.parameterCount == 3) {
            arrayOf<Any?>(intent, permissions, null)
        } else {
            arrayOf<Any?>(intent, permissions)
        }
        method.invoke(service, *args)
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
