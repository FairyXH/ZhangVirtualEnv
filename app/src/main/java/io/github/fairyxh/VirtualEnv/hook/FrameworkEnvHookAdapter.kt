package io.github.fairyxh.VirtualEnv.hook

import io.github.fairyxh.VirtualEnv.core.EnvStateCache
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.lang.reflect.Method

/**
 * Framework API Hook Adapter（第一层：覆盖普通应用）。
 *
 * 运行在 App 进程（libxposed onModuleLoaded），通过 [EnvStateCache] 读取
 * system_server Backend 的虚拟环境状态，Hook 常用 framework API：
 *
 * 1. TelephonyManager.getAllCellInfo()    → 虚拟基站列表（LTE / GSM / NR）
 * 2. BluetoothLeScanner.startScan(...)    → 虚拟 BLE beacon 扫描结果回调
 * 3. WifiManager.getScanResults()         → 虚拟 WiFi 扫描结果
 *
 * 签名依据（JADX 逆向 Oplus 15 framework.jar）：
 * - CellInfoLte/CellInfoGsm/CellInfoNr 均有 public 无参构造 + setCellIdentity
 * - CellIdentityLte(mcc,mnc,tac,ci,pci)、CellSignalStrengthLte(6 参) 等均为 public
 * - ScanResult(BluetoothDevice, ScanRecord, int rssi, long timestampNanos) public
 * - BluetoothAdapter.getRemoteDevice(String) public static
 * - ScanRecord.parseFromBytes(byte[]) public static
 *
 * 职责边界：不保存业务状态；任何 Hook 点失败放行原始逻辑（fail-open）。
 */
class FrameworkEnvHookAdapter(
    private val cache: EnvStateCache,
    private val registrar: HookRegistrar,
) {

    companion object {
        private const val TAG_SCOPE = "Hook"
        private const val CALLBACK_TYPE_ALL_MATCH = 1
    }

    fun install(classLoader: ClassLoader) {
        hookTelephonyGetAllCellInfo(classLoader)
        hookBleStartScan(classLoader)
        hookBluetoothDeviceIdentity(classLoader)
        hookWifiGetScanResults(classLoader)
        hookSensorRegister(classLoader)
        hookGnssStatus(classLoader)
        startRefreshLoop()
    }

    /** 传感器多级后端统一入口（App 进程侧由 SensorBackendManager 路由到 AppHookSensorBackend）。 */
    private val sensorManager
        get() = io.github.fairyxh.VirtualEnv.core.sensor.SensorBackendManager

    /** 周期刷新：配置就绪后补启动挂起的 sensor 注入（register 时配置未就绪的竞态）。 */
    private val refreshExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ZVE-EnvRefresh").apply { isDaemon = true }
    }

    /** 配置未就绪时暂存的 BLE 扫描回调（配置就绪后补投递虚拟结果）。 */
    private val pendingBleCallbacks = java.util.concurrent.ConcurrentHashMap.newKeySet<Any>()

    private fun startRefreshLoop() {
        refreshExecutor.scheduleWithFixedDelay(
            {
                runCatching { sensorManager.refresh() }
                runCatching { flushPendingBle() }
            },
            300,
            500,
            java.util.concurrent.TimeUnit.MILLISECONDS
        )
        ZLog.i(TAG_SCOPE, "env refresh loop started")
    }

    /** BLE 配置就绪后补投递挂起的扫描回调（与传感器 pending 机制一致）。 */
    private fun flushPendingBle() {
        if (pendingBleCallbacks.isEmpty()) return
        if (cache.currentBle() == null) return
        val iter = pendingBleCallbacks.iterator()
        while (iter.hasNext()) {
            val callback = iter.next()
            iter.remove()
            try {
                deliverVirtualBle(callback)
                ZLog.i(TAG_SCOPE, "pending ble callback flushed -> virtual")
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "flush pending ble failed", t)
            }
        }
    }

    // ---------- 步频：SensorManager.registerListener ----------

    private fun hookSensorRegister(classLoader: ClassLoader) {
        val clazz = HookSupport.findClass(classLoader, "android.hardware.SensorManager") ?: return
        var hooked = 0
        HookSupport.findMethods(clazz, "registerListener")
            .filter { it.parameterCount in 3..6 }
            .forEach { method ->
                if (method.parameterTypes[1].simpleName != "Sensor") return@forEach
                val ok = registrar.register(method) { chain ->
                    try {
                        val listener = chain.getArg(0)
                        val sensor = chain.getArg(1)
                        val type = sensor.javaClass.getMethod("getType").invoke(sensor) as? Int ?: -1
                        val taken = sensorManager.onListenerRegistered(listener, sensor, type)
                        if (taken) {
                            // 注入器接管：屏蔽真实传感器（不 proceed 原注册，真实事件不再到达）
                            ZLog.d(TAG_SCOPE, "registerListener type=$type intercepted (virtual active)")
                            return@register when (method.returnType) {
                                java.lang.Boolean.TYPE -> true
                                else -> null
                            }
                        }
                    } catch (t: Throwable) {
                        ZLog.w(TAG_SCOPE, "step register hook failed", t)
                    }
                    chain.proceed()
                    null
                }
                if (ok) {
                    hooked++
                    ZLog.i(TAG_SCOPE, "hooked SensorManager.registerListener(${method.parameterCount} params)")
                }
            }
        HookSupport.findMethods(clazz, "unregisterListener")
            .filter { it.parameterCount in 1..2 }
            .forEach { method ->
                val ok = registrar.register(method) { chain ->
                    val listener = chain.getArg(0)
                    try {
                        sensorManager.onListenerUnregistered(listener)
                    } catch (t: Throwable) {
                        ZLog.w(TAG_SCOPE, "step unregister hook failed", t)
                    }
                    chain.proceed()
                    null
                }
                if (ok) {
                    hooked++
                    ZLog.i(TAG_SCOPE, "hooked SensorManager.unregisterListener(${method.parameterCount} params)")
                }
            }
        if (hooked == 0) ZLog.w(TAG_SCOPE, "SensorManager.registerListener candidates not found")
    }
    // ---------- 基站：TelephonyManager.getAllCellInfo ----------

    private fun hookTelephonyGetAllCellInfo(classLoader: ClassLoader) {
        val clazz = HookSupport.findClass(classLoader, "android.telephony.TelephonyManager") ?: return
        val method = HookSupport.findMethods(clazz, "getAllCellInfo")
            .firstOrNull { it.parameterCount == 0 }
        if (method == null) {
            ZLog.w(TAG_SCOPE, "TelephonyManager.getAllCellInfo not found")
            return
        }
        val ok = registrar.register(method) { chain ->
            val original = chain.proceed()
            // 严格放行：基站模拟未开启（即使虚拟定位开启）→ 返回原始真实基站
            val virtual = cache.currentCell() ?: return@register original
            // 虚拟定位未启用：放行真实基站（检测器/普通场景不误判）
            if (!cache.isLocationEnabled()) return@register original
            try {
                val entries = virtual.optJSONArray("entries") ?: virtual.optJSONArray("cells") ?: org.json.JSONArray()
                val list = if (entries.length() == 0) {
                    // 空基站配置：尊重 0 基站，返回空列表（不 fallback CDMA）
                    ZLog.d(TAG_SCOPE, "getAllCellInfo -> empty (0 cells configured)")
                    emptyList()
                } else {
                    VirtualCellFactory.buildCellInfoList(virtual, cache.locationLat(), cache.locationLon()).ifEmpty {
                        // 配置非空但构建为空：回退带虚拟经纬度的 CDMA（供网络定位 SDK 换算坐标）
                        listOfNotNull(
                            VirtualCellFactory.buildCellInfoCdma(cache.locationLat(), cache.locationLon())
                        )
                    }
                }
                ZLog.d(TAG_SCOPE, "getAllCellInfo -> virtual ${list.size} cells")
                return@register list
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "build virtual cells failed, fallback", t)
            }
            original
        }
        if (ok) ZLog.i(TAG_SCOPE, "hooked TelephonyManager.getAllCellInfo")
    }

    // ---------- GNSS：LocationManager.registerGnssStatusCallback / getGnssStatus ----------

    /** GNSS 虚拟注入调度器（进程内）。 */
    private val gnssExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ZVE-GnssInject").apply { isDaemon = true }
    }
    private val gnssListeners = java.util.concurrent.ConcurrentHashMap<Any, java.util.concurrent.ScheduledFuture<*>>()

    private fun hookGnssStatus(classLoader: ClassLoader) {
        val clazz = HookSupport.findClass(classLoader, "android.location.LocationManager") ?: return
        var hooked = 0
        // 注册回调：总是启动周期投递（虚拟数据可用后自动覆盖真实回调）。
        // 虚拟启用时**不 proceed**（测试进程中接管真实 GNSS 回调）；虚拟未启用时 proceed
        // 保留真实注册，但周期投递会在配置恢复后立即接管，避免时序竞态。
        HookSupport.findMethods(clazz, "registerGnssStatusCallback")
            .filter { it.parameterTypes.any { p -> p.simpleName == "Callback" } }
            .forEach { method ->
                val ok = registrar.register(method) { chain ->
                    try {
                        val callbackClass = method.parameterTypes.firstOrNull { it.simpleName == "Callback" }
                        val callback = callbackClass?.let { findCallbackArg(chain, it) }
                        if (callback != null) {
                            startGnssInject(callback)
                            if (cache.currentGnss() != null) {
                                return@register true // registerGnssStatusCallback 返回 Boolean
                            }
                        }
                    } catch (t: Throwable) {
                        ZLog.w(TAG_SCOPE, "gnss register intercept failed", t)
                    }
                    chain.proceed()
                }
                if (ok) {
                    hooked++
                    ZLog.i(TAG_SCOPE, "hooked LocationManager.registerGnssStatusCallback(${method.parameterCount} params)")
                }
            }
        // unregister：清理周期任务（放行原始注销）
        HookSupport.findMethods(clazz, "unregisterGnssStatusCallback")
            .filter { it.parameterCount in 1..2 }
            .forEach { method ->
                val ok = registrar.register(method) { chain ->
                    try {
                        val callback = chain.getArg(0)
                        gnssListeners.remove(callback)?.cancel(false)
                    } catch (_: Throwable) {
                    }
                    chain.proceed()
                    null
                }
                if (ok) {
                    hooked++
                    ZLog.i(TAG_SCOPE, "hooked LocationManager.unregisterGnssStatusCallback(${method.parameterCount} params)")
                }
            }
        // getGnssStatus()：直接返回虚拟状态（API 30+）
        HookSupport.findMethods(clazz, "getGnssStatus")
            .firstOrNull { it.parameterCount == 0 }
            ?.let { method ->
                val ok = registrar.register(method) { chain ->
                    val virtual = cache.currentGnss()
                    if (virtual != null) {
                        try {
                            val status = buildVirtualGnssStatus(virtual)
                            if (status != null) return@register status
                        } catch (t: Throwable) {
                            ZLog.w(TAG_SCOPE, "gnss get virtual failed", t)
                        }
                    }
                    chain.proceed()
                }
                if (ok) {
                    hooked++
                    ZLog.i(TAG_SCOPE, "hooked LocationManager.getGnssStatus")
                }
            }
        if (hooked == 0) ZLog.w(TAG_SCOPE, "GnssStatus candidates not found")
    }

    /** 立即投递一次虚拟状态并启动周期投递（虚拟关闭时自动停止）。 */
    private fun startGnssInject(callback: Any) {
        if (gnssListeners.containsKey(callback)) return
        deliverVirtualGnss(callback)
        val future = gnssExecutor.scheduleWithFixedDelay(
            { deliverVirtualGnss(callback) },
            300,
            300,
            java.util.concurrent.TimeUnit.MILLISECONDS
        )
        gnssListeners[callback] = future
        ZLog.i(TAG_SCOPE, "gnss injector started for $callback")
    }

    /** 投递虚拟卫星状态；虚拟关闭或无状态时静默跳过（保持周期任务，配置恢复后自动生效）。 */
    private fun deliverVirtualGnss(callback: Any) {
        try {
            val virtual = cache.currentGnss() ?: return
            val status = buildVirtualGnssStatus(virtual) ?: return
            callback.javaClass
                .getMethod("onSatelliteStatusChanged", Class.forName("android.location.GnssStatus"))
                .invoke(callback, status)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "gnss virtual deliver failed", t)
        }
    }

    /** 在调用参数里找到目标类型的对象。 */
    private fun findCallbackArg(chain: Any, target: Class<*>): Any? {
        try {
            // libxposed Chain 没有 getArgCount；用 getArgs() 遍历（getArg(int) 也可用）
            val args = chain.javaClass.getMethod("getArgs").invoke(chain) as? List<*> ?: return null
            for (arg in args) {
                if (arg != null && target.isInstance(arg)) return arg
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "findCallbackArg failed", t)
        }
        return null
    }

    /** 用 GnssStatus.Builder 构造虚拟卫星状态（API 30+，反射避免编译期 API 门槛）。 */
    private fun buildVirtualGnssStatus(data: org.json.JSONObject): Any? {
        return try {
            // 注意：嵌套类 Class.forName 必须用 $ 分隔，点号写法永远 ClassNotFoundException
            val builderClass = Class.forName("android.location.GnssStatus\$Builder")
            val builder = builderClass.getDeclaredConstructor().newInstance()
            // Oplus 隐藏了 8 参 addSatellite，仅暴露 12 参版本（与 AOSP 相比
            // hasBasebandCn0/basebandCn0 顺序交换）：
            // (svid, constellation, cn0, elev, azim, hasEphemeris, hasAlmanac, usedInFix,
            //  hasBasebandCn0, basebandCn0, isBasebandInFix, carrierFrequencyHz)
            val addSatellite = builderClass.getMethod(
                "addSatellite",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            )
            val total = data.optInt("satelliteCount", 24).coerceIn(0, 64)
            val used = data.optInt("usedInFix", 0).coerceIn(0, total)
            val constellations = intArrayOf(
                android.location.GnssStatus.CONSTELLATION_GPS,
                android.location.GnssStatus.CONSTELLATION_GLONASS,
                android.location.GnssStatus.CONSTELLATION_BEIDOU,
                android.location.GnssStatus.CONSTELLATION_GALILEO
            )
            for (i in 0 until total) {
                val svid = i + 1
                val cn0 = 18f + (i % 22)
                val usedInFix = i < used
                addSatellite.invoke(
                    builder,
                    svid,
                    constellations[i % constellations.size],
                    cn0,
                    10f + i,
                    90f + i * 7,
                    true, // hasEphemeris
                    true, // hasAlmanac
                    usedInFix,
                    false, // hasBasebandCn0
                    cn0,  // basebandCn0（与 cn0 一致）
                    false, // isBasebandInFix
                    1575.42f // carrierFrequencyHz (L1)
                )
            }
            builderClass.getMethod("build").invoke(builder)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "build virtual gnss status failed", t)
            null
        }
    }

    // ---------- BLE：BluetoothLeScanner.startScan ----------

    private fun hookBleStartScan(classLoader: ClassLoader) {
        val clazz = HookSupport.findClass(classLoader, "android.bluetooth.le.BluetoothLeScanner") ?: return
        // void startScan(ScanCallback)
        val oneParam = HookSupport.findMethods(clazz, "startScan")
            .firstOrNull { it.parameterCount == 1 && it.parameterTypes[0].simpleName == "ScanCallback" }
        // void startScan(List<ScanFilter>, ScanSettings, ScanCallback)
        val threeParam = HookSupport.findMethods(clazz, "startScan")
            .firstOrNull {
                it.parameterCount == 3 &&
                    it.parameterTypes[2].simpleName == "ScanCallback"
            }
        if (oneParam != null) {
            val ok = registrar.register(oneParam) { chain ->
                val callback = chain.getArg(0)
                try {
                    if (deliverVirtualBle(callback) == true) return@register null
                    // 未启用/未就绪：暂存回调，配置就绪后补投递虚拟结果；同时放行真实扫描
                    pendingBleCallbacks.add(callback)
                    ZLog.d(TAG_SCOPE, "startScan(1) -> pending virtual ble")
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "startScan(1) hook failed", t)
                }
                chain.proceed()
                null
            }
            if (ok) ZLog.i(TAG_SCOPE, "hooked BluetoothLeScanner.startScan(ScanCallback)")
        }
        if (threeParam != null) {
            val ok = registrar.register(threeParam) { chain ->
                val callback = chain.getArg(2)
                try {
                    if (deliverVirtualBle(callback) == true) return@register null
                    pendingBleCallbacks.add(callback)
                    ZLog.d(TAG_SCOPE, "startScan(3) -> pending virtual ble")
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "startScan(3) hook failed", t)
                }
                chain.proceed()
                null
            }
            if (ok) ZLog.i(TAG_SCOPE, "hooked BluetoothLeScanner.startScan(List,ScanSettings,ScanCallback)")
        }
    }

    /**
     * 向 [ScanCallback] 投递虚拟扫描结果。
     *
     * @return 投递成功返回 true（阻止原链路）；未启用虚拟 BLE 或投递失败返回 null（放行）
     */
    private fun deliverVirtualBle(callback: Any): Boolean? {
        val virtual = cache.currentBle() ?: return null
        try {
            val results = VirtualBleFactory.buildScanResults(virtual)
            val callbackClass = callback.javaClass
            val resultClass = Class.forName("android.bluetooth.le.ScanResult")
            val onScanResult: Method = try {
                callbackClass.getMethod("onScanResult", Int::class.java, resultClass)
            } catch (_: NoSuchMethodException) {
                callbackClass.getMethod("onScanResult", java.lang.Integer.TYPE, resultClass)
            }
            results.forEach { r ->
                onScanResult.invoke(callback, CALLBACK_TYPE_ALL_MATCH, r)
            }
            // 启用即覆盖：空配置也投递 0 个结果并阻断真实扫描
            ZLog.d(TAG_SCOPE, "startScan -> virtual ${results.size} results")
            return true
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "deliver virtual ble failed, fallback", t)
            return null
        }
    }

    // ---------- 蓝牙设备身份：AdapterService.getRemoteName / getRemoteUuids（com.android.bluetooth 进程） ----------

    /**
     * 对应 VirtualRegion 的 BluetoothDevice.getRemoteName/getRemoteUuids 类 Hook。
     * 仅在 com.android.bluetooth 进程命中 AdapterService；BLE 虚拟化启用时按设备地址
     * 在虚拟 devices[] 中查找名称/UUID，命中返回虚拟值，未命中放行真实数据（fail-open）。
     */
    private fun hookBluetoothDeviceIdentity(classLoader: ClassLoader) {
        val clazz = HookSupport.findClass(classLoader, "com.android.bluetooth.btservice.AdapterService") ?: return
        val deviceClass = Class.forName("android.bluetooth.BluetoothDevice")
        // getRemoteName(BluetoothDevice) -> String
        HookSupport.findMethods(clazz, "getRemoteName")
            .firstOrNull { it.parameterCount == 1 && it.parameterTypes[0] == deviceClass }
            ?.let { method ->
                val ok = registrar.register(method) { chain ->
                    val virtual = cache.currentBle()
                    if (virtual != null) {
                        try {
                            val device = chain.getArg(0)
                            val address = device.javaClass.getMethod("getAddress").invoke(device) as? String
                            val entry = findBleEntry(virtual, address)
                            val name = entry?.optString("name", "")?.takeIf { it.isNotBlank() }
                            if (name != null) {
                                ZLog.d(TAG_SCOPE, "AdapterService.getRemoteName -> virtual $name")
                                return@register name
                            }
                        } catch (t: Throwable) {
                            ZLog.w(TAG_SCOPE, "getRemoteName virtual failed, fallback", t)
                        }
                    }
                    chain.proceed()
                }
                if (ok) ZLog.i(TAG_SCOPE, "hooked AdapterService.getRemoteName")
            }
        // getRemoteUuids(BluetoothDevice) -> ParcelUuid[]
        HookSupport.findMethods(clazz, "getRemoteUuids")
            .firstOrNull { it.parameterCount == 1 && it.parameterTypes[0] == deviceClass }
            ?.let { method ->
                val ok = registrar.register(method) { chain ->
                    val virtual = cache.currentBle()
                    if (virtual != null) {
                        try {
                            val device = chain.getArg(0)
                            val address = device.javaClass.getMethod("getAddress").invoke(device) as? String
                            val entry = findBleEntry(virtual, address)
                            val uuid = entry?.optString("uuid", "")?.takeIf { it.isNotBlank() }
                            if (uuid != null) {
                                val puClass = Class.forName("android.os.ParcelUuid")
                                val pu = puClass.getMethod("fromString", String::class.java).invoke(null, uuid)
                                val arr = java.lang.reflect.Array.newInstance(puClass, 1)
                                java.lang.reflect.Array.set(arr, 0, pu)
                                ZLog.d(TAG_SCOPE, "AdapterService.getRemoteUuids -> virtual $uuid")
                                return@register arr
                            }
                        } catch (t: Throwable) {
                            ZLog.w(TAG_SCOPE, "getRemoteUuids virtual failed, fallback", t)
                        }
                    }
                    chain.proceed()
                }
                if (ok) ZLog.i(TAG_SCOPE, "hooked AdapterService.getRemoteUuids")
            }
    }

    /** 在虚拟 BLE 数据 devices/bonded 中按地址查找条目。 */
    private fun findBleEntry(data: JSONObject, address: String?): JSONObject? {
        if (address.isNullOrBlank()) return null
        val key = address.uppercase()
        val arrays = listOfNotNull(data.optJSONArray("devices"), data.optJSONArray("bonded"))
        for (arr in arrays) {
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                if (entry.optString("address", "").uppercase() == key) return entry
            }
        }
        return null
    }

    // ---------- WiFi：WifiManager.getScanResults ----------

    private fun hookWifiGetScanResults(classLoader: ClassLoader) {
        val clazz = HookSupport.findClass(classLoader, "android.net.wifi.WifiManager") ?: return
        val method = HookSupport.findMethods(clazz, "getScanResults")
            .firstOrNull { it.parameterCount == 0 }
        if (method == null) {
            ZLog.w(TAG_SCOPE, "WifiManager.getScanResults not found")
            return
        }
        val ok = registrar.register(method) { chain ->
            val original = chain.proceed()
            val virtual = cache.currentWifi()
            if (virtual != null) {
                try {
                    val list = buildWifiResults(virtual)
                    // 启用即覆盖：即使空配置也返回空列表，绝不放行真实 WiFi
                    ZLog.d(TAG_SCOPE, "getScanResults -> virtual ${list.size} networks")
                    return@register list
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "build virtual wifi failed, fallback", t)
                }
            }
            original
        }
        if (ok) ZLog.i(TAG_SCOPE, "hooked WifiManager.getScanResults")
    }

    private fun buildWifiResults(data: JSONObject): List<Any> {
        val networks = data.optJSONArray("networks") ?: return emptyList()
        val resultClass = Class.forName("android.net.wifi.ScanResult")
        val ctor = try {
            resultClass.getDeclaredConstructor().also { it.isAccessible = true }
        } catch (t: Throwable) {
            resultClass.getConstructor()
        }
        val result = mutableListOf<Any>()
        for (i in 0 until networks.length()) {
            val n = networks.optJSONObject(i) ?: continue
            try {
                val scan = ctor.newInstance()
                setField(scan, "SSID", n.optString("ssid", ""))
                setField(scan, "BSSID", n.optString("bssid", ""))
                setField(scan, "level", n.optInt("rssi", -70))
                setField(scan, "frequency", n.optInt("frequency", 2412))
                setField(scan, "capabilities", n.optString("capabilities", "[WPA2-PSK-CCMP]"))
                setField(scan, "timestamp", android.os.SystemClock.elapsedRealtimeNanos())
                // 必须设置信息元素数组：Oplus DCS 统计回调 getInformationElements()
                // 会 Arrays.asList 该字段，null 直接 NPE 导致 system_server 崩溃
                setEmptyInformationElements(scan)
                result.add(scan)
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "build virtual wifi result failed", t)
            }
        }
        return result
    }

    /** 设置空的 InformationElement[]，避免 Oplus 统计 NPE。
     *  Oplus 15 字段名为 informationElements（无 m 前缀，JADX 真机确认），
     *  其他 ROM 兼容 mInformationElements。 */
    private fun setEmptyInformationElements(scan: Any) {
        try {
            val ieClass = Class.forName("android.net.wifi.ScanResult\$InformationElement")
            val empty = java.lang.reflect.Array.newInstance(ieClass, 0)
            val field = try {
                scan.javaClass.getField("informationElements")
            } catch (_: NoSuchFieldException) {
                scan.javaClass.getField("mInformationElements")
            }
            field.set(scan, empty)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "set informationElements failed", t)
        }
    }

    private fun setField(target: Any, fieldName: String, value: Any) {
        try {
            target.javaClass.getField(fieldName).set(target, value)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "set field $fieldName failed", t)
        }
    }
}
