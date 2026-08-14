package io.github.fairyxh.VirtualEnv.hook

import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.util.HashSet

/**
 * 虚拟 BLE 扫描结果构造器（App 进程框架 Hook 与 com.android.bluetooth 栈内 Hook 共用）。
 *
 * 依据 JADX 逆向（Oplus 15 framework.jar + 蓝牙_15.0.0.apk）：
 * - `android.bluetooth.le.ScanResult(BluetoothDevice, ScanRecord, int rssi, long timestampNanos)` public
 * - `ScanRecord.parseFromBytes(byte[])` public static
 * - `BluetoothAdapter.getRemoteDevice(String)` public static
 *
 * 数据格式兼容采集包与模拟配置：`devices[]` + `bonded[]`，按 address 去重。
 */
object VirtualBleFactory {

    private const val TAG_SCOPE = "Hook"

    /** 构造虚拟 BLE 扫描结果列表；失败时返回空列表。 */
    fun buildScanResults(
        data: JSONObject,
        logSink: ((Int, String, String) -> Unit)? = null,
    ): List<Any> {
        return try {
            val resultClass = Class.forName("android.bluetooth.le.ScanResult")
            val ctor = resultClass.getConstructor(
                Class.forName("android.bluetooth.BluetoothDevice"),
                Class.forName("android.bluetooth.le.ScanRecord"),
                Int::class.java,
                Long::class.java
            )
            val adapterClass = Class.forName("android.bluetooth.BluetoothAdapter")
            val getDefaultAdapter = adapterClass.getMethod("getDefaultAdapter")
            val getRemoteDevice = adapterClass.getMethod("getRemoteDevice", String::class.java)
            // getRemoteDevice 是实例方法：需要先取默认 Adapter 再调用
            val adapterInstance = getDefaultAdapter.invoke(null)
            if (adapterInstance == null) {
                logSink?.invoke(4, "ZVirtualEnv", "[Hook] BluetoothAdapter.getDefaultAdapter() null")
                return emptyList()
            }
            val recordClass = Class.forName("android.bluetooth.le.ScanRecord")
            val parseFromBytes = recordClass.getMethod("parseFromBytes", ByteArray::class.java)
            // LE General Discoverable | BR/EDR Not Supported + 完整本地名
            fun buildAdvBytes(name: String): ByteArray {
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                val adv = java.io.ByteArrayOutputStream()
                adv.write(2)
                adv.write(1)
                adv.write(0x1A)
                if (nameBytes.isNotEmpty() && nameBytes.size <= 29) {
                    adv.write(nameBytes.size + 1)
                    adv.write(0x09)
                    adv.write(nameBytes)
                }
                return adv.toByteArray()
            }

            val result = mutableListOf<Any>()
            val seen = HashSet<String>()
            // mode=classic 的设备只在经典发现中出现，不进 BLE 扫描结果；bonded 保持兼容全部加入
            fun isBleVisible(d: JSONObject): Boolean {
                val mode = d.optString("mode", "").lowercase()
                return mode != "classic"
            }
            fun addEntry(d: JSONObject, filterClassicOnly: Boolean) {
                if (filterClassicOnly && !isBleVisible(d)) return
                val address = d.optString("address", "").uppercase()
                if (address.isBlank() || !seen.add(address)) return
                try {
                    val device = getRemoteDevice.invoke(adapterInstance, address)
                    val record = parseFromBytes.invoke(null, buildAdvBytes(d.optString("name", "")))
                    result.add(
                        ctor.newInstance(
                            device,
                            record,
                            d.optInt("rssi", -70),
                            android.os.SystemClock.elapsedRealtimeNanos()
                        )
                    )
                } catch (t: Throwable) {
                    logSink?.invoke(4, "ZVirtualEnv", "[Hook] build scan result $address failed: ${t.message}")
                    ZLog.w(TAG_SCOPE, "build scan result $address failed", t)
                }
            }
            data.optJSONArray("devices")?.let { arr ->
                for (i in 0 until arr.length()) addEntry(arr.optJSONObject(i) ?: continue, true)
            }
            data.optJSONArray("bonded")?.let { arr ->
                for (i in 0 until arr.length()) addEntry(arr.optJSONObject(i) ?: continue, false)
            }
            result
        } catch (t: Throwable) {
            logSink?.invoke(4, "ZVirtualEnv", "[Hook] build virtual ble results failed: ${t.message}")
            ZLog.w(TAG_SCOPE, "build virtual ble results failed", t)
            emptyList()
        }
    }
}
