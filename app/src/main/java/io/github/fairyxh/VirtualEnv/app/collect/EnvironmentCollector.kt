package io.github.fairyxh.VirtualEnv.app.collect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.TelephonyManager
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * 真实环境采集器（控制端）。
 *
 * 采集当前设备的位置、基站、WiFi、蓝牙（含附近 BLE 设备）、GNSS 状态，输出 JSON。
 * 输出结构即 Hook 层消费的数据格式（networks/cells/bonded+devices），
 * 采集结果保存为快照后可被 /api/env/use 直接加载到模拟引擎。
 *
 * 注：CellIdentity 相关 API 各版本差异大，运行时已按 SDK_INT 分支处理，
 * 此处集中抑制 NewApi 以便统一在运行时判断。
 */
@SuppressLint("MissingPermission", "NewApi")
class EnvironmentCollector(private val context: Context) {

    companion object {
        private const val TAG_SCOPE = "Collect"
        private const val SCAN_TIMEOUT_MS = 4000L
        private const val MAX_NEARBY = 20
    }

    @SuppressLint("MissingPermission")
    fun collectAll(onDone: (JSONObject) -> Unit) {
        val result = JSONObject()
        result.put("timestamp", System.currentTimeMillis())
        result.put("location", collectLocation())
        result.put("cell", collectCell())
        result.put("wifi", collectWifi())
        result.put("gnss", collectGnss())
        collectBluetooth { bt ->
            result.put("bluetooth", bt)
            onDone(result)
        }
    }

    @SuppressLint("MissingPermission")
    private fun collectLocation(): JSONObject {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val out = JSONObject()
        val providers = lm.getProviders(true) + lm.getProviders(false)
        providers.distinct().forEach { provider ->
            try {
                val loc: Location? = if (Build.VERSION.SDK_INT >= 30) {
                    lm.getCurrentLocation(provider, null, context.mainExecutor) { }
                    lm.getLastKnownLocation(provider)
                } else {
                    @Suppress("DEPRECATION")
                    lm.getLastKnownLocation(provider)
                }
                if (loc != null) {
                    out.put(provider, JSONObject().apply {
                        put("latitude", loc.latitude)
                        put("longitude", loc.longitude)
                        put("accuracy", loc.accuracy)
                        put("speed", loc.speed)
                        put("time", loc.time)
                    })
                }
            } catch (t: Throwable) {
                ZLog.d(TAG_SCOPE, "location provider $provider failed: ${t.message}")
            }
        }
        return out
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun collectCell(): JSONObject {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val out = JSONObject()
        try {
            val all = if (Build.VERSION.SDK_INT >= 30) tm.allCellInfo else @Suppress("DEPRECATION") tm.allCellInfo
            val arr = JSONArray()
            all?.forEach { info ->
                val id = if (Build.VERSION.SDK_INT >= 30) {
                    info.cellIdentity
                } else {
                    @Suppress("DEPRECATION")
                    info.cellIdentity
                }
                val item = JSONObject()
                item.put("registered", info.isRegistered)
                when (id) {
                    is CellIdentityLte -> {
                        item.put("type", "LTE")
                        item.put("mcc", cellInt(id, "mcc"))
                        item.put("mnc", cellInt(id, "mnc"))
                        item.put("tac", id.tac)
                        item.put("ci", id.ci)
                        item.put("pci", id.pci)
                    }
                    is CellIdentityNr -> {
                        item.put("type", "NR")
                        item.put("mcc", cellInt(id, "mcc"))
                        item.put("mnc", cellInt(id, "mnc"))
                        item.put("tac", id.tac)
                        item.put("nci", id.nci)
                        item.put("pci", id.pci)
                    }
                    is CellIdentityGsm -> {
                        item.put("type", "GSM")
                        item.put("mcc", id.mcc)
                        item.put("mnc", id.mnc)
                        item.put("lac", id.lac)
                        item.put("cid", id.cid)
                    }
                    is CellIdentityWcdma -> {
                        item.put("type", "WCDMA")
                        item.put("mcc", id.mcc)
                        item.put("mnc", id.mnc)
                        item.put("lac", id.lac)
                        item.put("cid", id.cid)
                    }
                    else -> item.put("type", "OTHER")
                }
                arr.put(item)
            }
            out.put("cells", arr)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "collectCell failed", t)
            out.put("cells", JSONArray())
            out.put("error", t.message)
        }
        return out
    }

    /** 反射读取 CellIdentity 的隐藏字段（mcc/mnc 等）。 */
    private fun cellInt(identity: Any, field: String): Int {
        return try {
            val m = identity.javaClass.getMethod("get$field")
            m.isAccessible = true
            m.invoke(identity) as? Int ?: -1
        } catch (t: Throwable) {
            -1
        }
    }

    @SuppressLint("MissingPermission")
    private fun collectWifi(): JSONObject {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val out = JSONObject()
        try {
            out.put("enabled", wm.isWifiEnabled)
            val results: List<ScanResult> = wm.scanResults
            val arr = JSONArray()
            results.take(20).forEach { r ->
                arr.put(JSONObject().apply {
                    put("ssid", r.SSID)
                    put("bssid", r.BSSID)
                    put("rssi", r.level)
                    put("frequency", r.frequency)
                })
            }
            out.put("networks", arr)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "collectWifi failed", t)
            out.put("networks", JSONArray())
            out.put("error", t.message)
        }
        return out
    }

    @SuppressLint("MissingPermission")
    private fun collectBluetooth(onDone: (JSONObject) -> Unit) {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = bm.adapter
        val out = JSONObject()
        try {
            out.put("enabled", adapter?.isEnabled ?: false)
            val arr = JSONArray()
            adapter?.bondedDevices?.take(20)?.forEach { d ->
                arr.put(JSONObject().apply {
                    put("name", d.name)
                    put("address", d.address)
                    put("type", d.type)
                })
            }
            out.put("bonded", arr)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "collectBluetooth bonded failed", t)
            out.put("bonded", JSONArray())
        }

        val scanner = try {
            adapter?.bluetoothLeScanner
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "bluetoothLeScanner unavailable", t)
            null
        }
        if (scanner == null) {
            out.put("devices", JSONArray())
            onDone(out)
            return
        }

        // 附近 BLE 设备扫描：回调异步收集，超时后停止并返回
        val devices = JSONArray()
        val mainHandler = Handler(Looper.getMainLooper())

        var callback: ScanCallback? = null

        fun finish() {
            try {
                callback?.let { scanner.stopScan(it) }
            } catch (_: Throwable) {
            }
            out.put("devices", devices)
            onDone(out)
        }

        callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                if (devices.length() >= MAX_NEARBY) return
                val item = JSONObject()
                val device = result.device
                item.put("address", device.address)
                item.put("rssi", result.rssi)
                item.put("txPower", result.txPower)
                val name = result.scanRecord?.deviceName ?: device.name
                item.put("name", name ?: "")
                result.scanRecord?.manufacturerSpecificData?.let { map ->
                    val sb = StringBuilder()
                    for (i in 0 until map.size()) {
                        val key = map.keyAt(i)
                        val value = map.valueAt(i)
                        sb.append(String.format("%04X:", key))
                        value.forEach { sb.append(String.format("%02X", it)) }
                        sb.append(";")
                    }
                    item.put("manufacturerData", sb.toString())
                }
                result.scanRecord?.serviceUuids?.let { uuids ->
                    if (uuids.isNotEmpty()) {
                        val ua = JSONArray()
                        uuids.forEach { ua.put(it.toString()) }
                        item.put("serviceUuids", ua)
                    }
                }
                devices.put(item)
            }

            override fun onScanFailed(errorCode: Int) {
                ZLog.w(TAG_SCOPE, "ble scan failed code=$errorCode")
                finish()
            }
        }

        val timeout = Runnable { finish() }
        mainHandler.postDelayed(timeout, SCAN_TIMEOUT_MS)
        try {
            scanner.startScan(callback)
            ZLog.i(TAG_SCOPE, "ble nearby scan started")
        } catch (t: Throwable) {
            mainHandler.removeCallbacks(timeout)
            ZLog.w(TAG_SCOPE, "ble startScan failed", t)
            out.put("devices", devices)
            out.put("error", t.message)
            onDone(out)
        }
    }

    @SuppressLint("MissingPermission")
    private fun collectGnss(): JSONObject {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val out = JSONObject()
        try {
            if (lm.getProvider(LocationManager.GPS_PROVIDER) == null) {
                out.put("available", false)
                return out
            }
            out.put("available", true)
            val callback = object : GnssStatus.Callback() {
                override fun onStarted() {
                    out.put("started", true)
                }

                override fun onStopped() {
                    out.put("started", false)
                }

                override fun onFirstFix(ttffMillis: Int) {
                    out.put("firstFix", ttffMillis)
                }

                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    out.put("satelliteCount", status.satelliteCount)
                    val sats = JSONArray()
                    var used = 0
                    for (i in 0 until status.satelliteCount) {
                        if (status.usedInFix(i)) used++
                        sats.put(JSONObject().apply {
                            put("svid", status.getSvid(i))
                            put("cn0", status.getCn0DbHz(i))
                            put("elevation", status.getElevationDegrees(i))
                            put("azimuth", status.getAzimuthDegrees(i))
                            put("used", status.usedInFix(i))
                        })
                    }
                    out.put("usedInFix", used)
                    out.put("satellites", sats)
                }
            }
            if (Build.VERSION.SDK_INT >= 30) {
                lm.registerGnssStatusCallback(context.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                lm.registerGnssStatusCallback(callback)
            }
            // 主动拉取一次状态；回调异步补充
            out.put("registered", true)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "collectGnss failed", t)
            out.put("error", t.message)
        }
        return out
    }
}
