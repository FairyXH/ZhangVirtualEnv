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
import android.os.SystemClock
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.TelephonyManager
import io.github.fairyxh.VirtualEnv.util.CellInfoRead
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
        private const val WIFI_POLL_TIMEOUT_MS = 3500L
        private const val GNSS_WAIT_TIMEOUT_MS = 3000L
        private const val MAX_NEARBY = 20
    }

    @SuppressLint("MissingPermission")
    fun collectAll(onDone: (JSONObject) -> Unit) {
        val result = JSONObject()
        result.put("timestamp", System.currentTimeMillis())
        result.put("location", collectLocation())
        result.put("cell", collectCell())
        // WiFi / GNSS / 蓝牙 / 传感器均异步：串行完成后回调，避免回调晚于 onDone 丢失
        collectWifi { wifi ->
            result.put("wifi", wifi)
            collectGnss { gnss ->
                result.put("gnss", gnss)
                collectBluetooth { bt ->
                    result.put("bluetooth", bt)
                    collectSensors { sensor ->
                        result.put("sensor", sensor)
                        onDone(result)
                    }
                }
            }
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
        // 扁平主字段：取最新 provider 结果，供“保存到位置模拟/已保存地点”直接使用
        var best: JSONObject? = null
        out.keys().forEach { k ->
            val item = out.optJSONObject(k) ?: return@forEach
            if (best == null || item.optLong("time", 0L) > (best?.optLong("time", 0L) ?: 0L)) {
                best = item
            }
        }
        best?.let {
            out.put("latitude", it.optDouble("latitude", 0.0))
            out.put("longitude", it.optDouble("longitude", 0.0))
            out.put("accuracy", it.optDouble("accuracy", 0.0))
            out.put("speed", it.optDouble("speed", 0.0))
            out.put("time", it.optLong("time", 0L))
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
                        item.put("mcc", CellInfoRead.mcc(id))
                        item.put("mnc", CellInfoRead.mnc(id))
                        item.put("tac", id.tac)
                        item.put("ci", id.ci)
                        item.put("pci", id.pci)
                    }
                    is CellIdentityNr -> {
                        item.put("type", "NR")
                        item.put("mcc", CellInfoRead.mcc(id))
                        item.put("mnc", CellInfoRead.mnc(id))
                        item.put("tac", id.tac)
                        item.put("nci", id.nci)
                        item.put("pci", id.pci)
                    }
                    is CellIdentityGsm -> {
                        item.put("type", "GSM")
                        item.put("mcc", CellInfoRead.mcc(id))
                        item.put("mnc", CellInfoRead.mnc(id))
                        item.put("lac", id.lac)
                        item.put("cid", id.cid)
                    }
                    is CellIdentityWcdma -> {
                        item.put("type", "WCDMA")
                        item.put("mcc", CellInfoRead.mcc(id))
                        item.put("mnc", CellInfoRead.mnc(id))
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

    @SuppressLint("MissingPermission")
    private fun collectWifi(onDone: (JSONObject) -> Unit) {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val out = JSONObject()
        try {
            out.put("enabled", wm.isWifiEnabled)
            val handler = Handler(Looper.getMainLooper())
            val start = SystemClock.elapsedRealtime()
            val poll = object : Runnable {
                override fun run() {
                    val results = wm.scanResults
                    if (results.isNotEmpty() || SystemClock.elapsedRealtime() - start > WIFI_POLL_TIMEOUT_MS) {
                        out.put("networks", JSONArray().apply {
                            results.take(20).forEach { r ->
                                put(JSONObject().apply {
                                    put("ssid", r.SSID)
                                    put("bssid", r.BSSID)
                                    put("rssi", r.level)
                                    put("frequency", r.frequency)
                                })
                            }
                        })
                        ZLog.i(TAG_SCOPE, "wifi collected ${results.size} networks")
                        onDone(out)
                    } else {
                        handler.postDelayed(this, 500)
                    }
                }
            }
            // 主动触发一次扫描，随后轮询缓存结果（Android 13+ 需定位权限，调用方已申请）
            try {
                wm.startScan()
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "wifi startScan failed, use cache", t)
            }
            handler.post(poll)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "collectWifi failed", t)
            out.put("networks", JSONArray())
            out.put("error", t.message)
            onDone(out)
        }
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

    /**
     * 传感器快照采集：注册加速度/陀螺仪/计步监听，拿到首批事件或超时后输出。
     * 输出结构即传感器模拟/回放引擎消费的格式（accelerometer/gyroscope/stepCounter）。
     */
    @SuppressLint("MissingPermission")
    private fun collectSensors(onDone: (JSONObject) -> Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val out = JSONObject()
        try {
            val handler = Handler(Looper.getMainLooper())
            val accel = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
            val gyro = sm.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)
            val step = sm.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER)
            var done = false
            var accelV: FloatArray? = null
            var gyroV: FloatArray? = null
            var stepV = -1L
            var sensorListener: android.hardware.SensorEventListener? = null

            fun finish() {
                if (done) return
                done = true
                try {
                    sensorListener?.let { sm.unregisterListener(it) }
                } catch (_: Throwable) {
                }
                handler.removeCallbacksAndMessages(null)
                accelV?.let { out.put("accelerometer", JSONArray().apply { put(it[0]); put(it[1]); put(it[2]) }) }
                gyroV?.let { out.put("gyroscope", JSONArray().apply { put(it[0]); put(it[1]); put(it[2]) }) }
                if (stepV >= 0) out.put("stepCounter", stepV)
                out.put("accuracy", 3)
                out.put("sampleRateMs", 100)
                ZLog.i(TAG_SCOPE, "sensors collected accel=${accelV != null} gyro=${gyroV != null} step=$stepV")
                onDone(out)
            }

            sensorListener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(event: android.hardware.SensorEvent) {
                    when (event.sensor?.type) {
                        android.hardware.Sensor.TYPE_ACCELEROMETER -> accelV = event.values.copyOf()
                        android.hardware.Sensor.TYPE_GYROSCOPE -> gyroV = event.values.copyOf()
                        android.hardware.Sensor.TYPE_STEP_COUNTER -> stepV = event.values[0].toLong()
                    }
                    if (accelV != null && gyroV != null && stepV >= 0) finish()
                }

                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
            }

            val listener = sensorListener ?: run { onDone(out); return }

            if (accel != null) sm.registerListener(listener, accel, android.hardware.SensorManager.SENSOR_DELAY_GAME, handler)
            if (gyro != null) sm.registerListener(listener, gyro, android.hardware.SensorManager.SENSOR_DELAY_GAME, handler)
            if (step != null) sm.registerListener(listener, step, android.hardware.SensorManager.SENSOR_DELAY_NORMAL, handler)
            handler.postDelayed({ finish() }, 800L)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "collectSensors failed", t)
            out.put("error", t.message)
            onDone(out)
        }
    }

    @SuppressLint("MissingPermission")
    private fun collectGnss(onDone: (JSONObject) -> Unit) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val out = JSONObject()
        try {
            if (lm.getProvider(LocationManager.GPS_PROVIDER) == null) {
                out.put("available", false)
                onDone(out)
                return
            }
            out.put("available", true)
            val handler = Handler(Looper.getMainLooper())
            var callback: GnssStatus.Callback? = null
            var done = false

            fun finish() {
                if (done) return
                done = true
                try {
                    callback?.let { lm.unregisterGnssStatusCallback(it) }
                } catch (_: Throwable) {
                }
                handler.removeCallbacksAndMessages(null)
                onDone(out)
            }

            callback = object : GnssStatus.Callback() {
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
                    ZLog.i(TAG_SCOPE, "gnss collected sats=${status.satelliteCount} used=$used")
                    finish()
                }
            }
            if (Build.VERSION.SDK_INT >= 30) {
                lm.registerGnssStatusCallback(context.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                lm.registerGnssStatusCallback(callback)
            }
            out.put("registered", true)
            handler.postDelayed({ finish() }, GNSS_WAIT_TIMEOUT_MS)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "collectGnss failed", t)
            out.put("error", t.message)
            onDone(out)
        }
    }
}
