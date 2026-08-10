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
        hookWifiGetScanResults(classLoader)
        hookSensorRegister(classLoader)
    }

    /** 步频模拟注入器（进程内单例）。 */
    private val stepInjector = StepSensorInjector(cache)

    // ---------- 步频：SensorManager.registerListener ----------

    private fun hookSensorRegister(classLoader: ClassLoader) {
        val clazz = HookSupport.findClass(classLoader, "android.hardware.SensorManager") ?: return
        var hooked = 0
        HookSupport.findMethods(clazz, "registerListener")
            .filter { it.parameterCount in 3..6 }
            .forEach { method ->
                if (method.parameterTypes[1].simpleName != "Sensor") return@forEach
                val ok = registrar.register(method) { chain ->
                    val original = chain.proceed()
                    try {
                        val listener = chain.getArg(0)
                        val sensor = chain.getArg(1)
                        val type = sensor.javaClass.getMethod("getType").invoke(sensor) as? Int ?: -1
                        stepInjector.onListenerRegistered(listener, sensor, type)
                    } catch (t: Throwable) {
                        ZLog.w(TAG_SCOPE, "step register hook failed", t)
                    }
                    original
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
                        stepInjector.onListenerUnregistered(listener)
                    } catch (t: Throwable) {
                        ZLog.w(TAG_SCOPE, "step unregister hook failed", t)
                    }
                    chain.proceed()
                    null
                }
                if (ok) {
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
            val virtual = cache.currentCell()
            if (virtual != null) {
                try {
                    val list = buildCellInfoList(virtual)
                    // 启用即覆盖：即使空配置也返回空列表，绝不放行真实基站
                    ZLog.d(TAG_SCOPE, "getAllCellInfo -> virtual ${list.size} cells")
                    return@register list
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "build virtual cells failed, fallback", t)
                }
            }
            original
        }
        if (ok) ZLog.i(TAG_SCOPE, "hooked TelephonyManager.getAllCellInfo")
    }

    private fun buildCellInfoList(data: JSONObject): List<Any> {
        // 兼容两种数据键：采集包用 cells[]，环境模拟配置页用 entries[]
        val cells = data.optJSONArray("cells") ?: data.optJSONArray("entries") ?: return emptyList()
        val result = mutableListOf<Any>()
        for (i in 0 until cells.length()) {
            val c = cells.optJSONObject(i) ?: continue
            try {
                when (c.optString("type", "").uppercase()) {
                    "LTE" -> result.add(buildLteCell(c))
                    "GSM" -> result.add(buildGsmCell(c))
                    "NR" -> result.add(buildNrCell(c))
                    "WCDMA" -> result.add(buildWcdmaCell(c))
                }
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "build cell[${c.optString("type")}] failed", t)
            }
        }
        return result
    }

    private fun buildLteCell(c: JSONObject): Any {
        val infoClass = Class.forName("android.telephony.CellInfoLte")
        val info = infoClass.getDeclaredConstructor().newInstance()

        val identityClass = Class.forName("android.telephony.CellIdentityLte")
        val identity = identityClass.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java, Int::class.java, Int::class.java
        ).newInstance(
            c.optInt("mcc", 460),
            c.optInt("mnc", 0),
            c.optInt("tac", -1),
            c.optInt("ci", -1),
            c.optInt("pci", -1)
        )
        infoClass.getMethod("setCellIdentity", identityClass).invoke(info, identity)

        val signalClass = Class.forName("android.telephony.CellSignalStrengthLte")
        val unknown = Int.MAX_VALUE
        val signal = signalClass.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            Int::class.java, Int::class.java
        ).newInstance(
            unknown,
            c.optInt("rsrp", -110),
            unknown,
            unknown,
            unknown,
            unknown
        )
        infoClass.getMethod("setCellSignalStrength", signalClass).invoke(info, signal)
        return info
    }

    private fun buildGsmCell(c: JSONObject): Any {
        val infoClass = Class.forName("android.telephony.CellInfoGsm")
        val info = infoClass.getDeclaredConstructor().newInstance()

        val identityClass = Class.forName("android.telephony.CellIdentityGsm")
        val identity = identityClass.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java,
            java.util.Collection::class.java
        ).newInstance(
            c.optInt("mcc", 460),
            c.optInt("mnc", 0),
            c.optInt("lac", -1),
            c.optInt("cid", -1),
            "", "", "", "",
            null
        )
        infoClass.getMethod("setCellIdentity", identityClass).invoke(info, identity)

        val signalClass = Class.forName("android.telephony.CellSignalStrengthGsm")
        val signal = signalClass.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java
        ).newInstance(
            c.optInt("rssi", -90),
            -1,
            -1
        )
        infoClass.getMethod("setCellSignalStrength", signalClass).invoke(info, signal)
        return info
    }

    private fun buildNrCell(c: JSONObject): Any {
        val infoClass = Class.forName("android.telephony.CellInfoNr")
        val info = infoClass.getDeclaredConstructor().newInstance()

        val identityClass = Class.forName("android.telephony.CellIdentityNr")
        val mcc = c.optInt("mcc", 460)
        val mnc = c.optInt("mnc", 0)
        val identity = identityClass.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java, IntArray::class.java,
            String::class.java, String::class.java, Long::class.java,
            String::class.java, String::class.java, java.util.Collection::class.java
        ).newInstance(
            mcc,
            mnc,
            c.optInt("tac", -1),
            intArrayOf(),
            mcc.toString(),
            String.format("%02d", mnc),
            c.optLong("nci", -1L),
            "", "",
            null
        )
        infoClass.getMethod("setCellIdentity", identityClass).invoke(info, identity)
        return info
    }

    private fun buildWcdmaCell(c: JSONObject): Any {
        val infoClass = Class.forName("android.telephony.CellInfoWcdma")
        val info = infoClass.getDeclaredConstructor().newInstance()

        val identityClass = Class.forName("android.telephony.CellIdentityWcdma")
        val identity = identityClass.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java,
            java.util.Collection::class.java
        ).newInstance(
            c.optInt("mcc", 460),
            c.optInt("mnc", 0),
            c.optInt("lac", -1),
            c.optInt("cid", -1),
            "", "", "", "",
            null
        )
        infoClass.getMethod("setCellIdentity", identityClass).invoke(info, identity)

        val signalClass = Class.forName("android.telephony.CellSignalStrengthWcdma")
        val signal = signalClass.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java
        ).newInstance(
            c.optInt("rssi", -90),
            -1,
            -1
        )
        infoClass.getMethod("setCellSignalStrength", signalClass).invoke(info, signal)
        return info
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
                deliverVirtualBle(chain.getArg(0)) ?: chain.proceed()
                null
            }
            if (ok) ZLog.i(TAG_SCOPE, "hooked BluetoothLeScanner.startScan(ScanCallback)")
        }
        if (threeParam != null) {
            val ok = registrar.register(threeParam) { chain ->
                deliverVirtualBle(chain.getArg(2)) ?: chain.proceed()
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
