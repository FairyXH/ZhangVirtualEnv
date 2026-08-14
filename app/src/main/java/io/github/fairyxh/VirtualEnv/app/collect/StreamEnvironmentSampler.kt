package io.github.fairyxh.VirtualEnv.app.collect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.TelephonyManager
import io.github.fairyxh.VirtualEnv.util.CellInfoRead
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 流式环境采集器（控制端）。
 *
 * 与旧 [EnvironmentCollector] 的“每次采集重新注册监听、采样间隔最低 2s”不同：
 * 本采集器启动后**持续监听**位置 / GNSS / 蓝牙 / 传感器，并每 1s 轮询基站与 WiFi，
 * 把最新状态保存在内存快照；录像采样线程可按任意间隔（最低 0.1s）从快照**截取一帧**，
 * 获得更高的采集分辨率。
 *
 * 线程模型：
 * - 传感器/GNSS/BLE 回调线程只更新 @Volatile / 并发集合，不做网络。
 * - [snapshot] 在录像采样线程（后台）调用，组装当前帧 JSON。
 * - stop 时统一注销监听与取消轮询。
 */
@SuppressLint("MissingPermission", "NewApi")
class StreamEnvironmentSampler(private val context: Context) {

    companion object {
        private const val TAG_SCOPE = "Collect"
        private const val REFRESH_MS = 1000L
        private const val BLE_RESULTS_LIMIT = 30
    }

    @Volatile
    private var lastLocation: Location? = null
    @Volatile
    private var lastGnssStatus: GnssStatus? = null
    @Volatile
    private var lastStepCount = -1L
    private val sensorRaw = ConcurrentHashMap<Int, String>()
    private val bleFound = LinkedHashMap<String, String>()

    private var locationManager: LocationManager? = null
    private var telephonyManager: TelephonyManager? = null
    private var wifiManager: WifiManager? = null
    private var sensorManager: SensorManager? = null
    private var bleScanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var stepSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null

    private val running = AtomicBoolean(false)
    private val refreshExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ZVE-StreamSampler").apply { isDaemon = true }
    }
    private var refreshFuture: ScheduledFuture<*>? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }
    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            lastGnssStatus = status
        }
    }
    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor?.type == Sensor.TYPE_STEP_COUNTER && event.values.isNotEmpty()) {
                lastStepCount = event.values[0].toLong()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    private val rawSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val vals = event.values.joinToString(", ") { String.format("%.3f", it) }
            sensorRaw[event.sensor.type] = "${event.sensor.name} [$vals]"
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName ?: device.name ?: "(no name)"
            val line = "$name ${device.address} ${result.rssi}dBm"
            synchronized(bleFound) {
                bleFound[device.address] = line
                while (bleFound.size > BLE_RESULTS_LIMIT) {
                    val it = bleFound.entries.iterator()
                    if (it.hasNext()) it.remove()
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            ZLog.w(TAG_SCOPE, "stream ble scan failed errorCode=$errorCode")
        }
    }

    fun isRunning(): Boolean = running.get()

    /** 启动流式监听与 1s 轮询（基站/WiFi）。 */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        val ctx = context.applicationContext
        locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        telephonyManager = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val adapter = BluetoothAdapter.getDefaultAdapter()
        bleScanner = adapter?.bluetoothLeScanner

        lastLocation = null
        lastGnssStatus = null
        lastStepCount = -1L
        sensorRaw.clear()
        synchronized(bleFound) { bleFound.clear() }

        stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        try {
            stepSensor?.let { sensorManager?.registerListener(stepListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "stream step register failed", t)
        }
        for (s in listOfNotNull(accelSensor, gyroSensor)) {
            try {
                sensorManager?.registerListener(rawSensorListener, s, SensorManager.SENSOR_DELAY_GAME)
            } catch (_: Throwable) {
            }
        }
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 0L, 0f, locationListener, Looper.getMainLooper()
            )
        } catch (_: Throwable) {
        }
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 0L, 0f, locationListener, Looper.getMainLooper()
            )
        } catch (_: Throwable) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                locationManager?.registerGnssStatusCallback(
                    Executors.newSingleThreadExecutor(),
                    gnssCallback
                )
            } else {
                @Suppress("DEPRECATION")
                locationManager?.registerGnssStatusCallback(gnssCallback)
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "stream gnss register failed", t)
        }
        try {
            bleScanner?.startScan(bleScanCallback)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "stream ble startScan failed", t)
        }
        refreshFuture = refreshExecutor.scheduleWithFixedDelay(
            { refreshPoll() },
            500,
            REFRESH_MS,
            TimeUnit.MILLISECONDS
        )
        ZLog.i(TAG_SCOPE, "stream sampler started")
    }

    /** 后台轮询：触发 WiFi 扫描并读取最新基站（其余数据由回调持续更新）。 */
    private fun refreshPoll() {
        if (!running.get()) return
        try {
            wifiManager?.startScan()
        } catch (_: Throwable) {
        }
    }

    /** 停止监听与轮询。 */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        refreshFuture?.cancel(false)
        refreshFuture = null
        try {
            bleScanner?.stopScan(bleScanCallback)
        } catch (_: Throwable) {
        }
        try {
            stepSensor?.let { sensorManager?.unregisterListener(stepListener, it) }
        } catch (_: Throwable) {
        }
        try {
            for (s in listOfNotNull(accelSensor, gyroSensor)) {
                sensorManager?.unregisterListener(rawSensorListener, s)
            }
        } catch (_: Throwable) {
        }
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: Throwable) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                locationManager?.unregisterGnssStatusCallback(gnssCallback)
            } else {
                @Suppress("DEPRECATION")
                locationManager?.unregisterGnssStatusCallback(gnssCallback)
            }
        } catch (_: Throwable) {
        }
        ZLog.i(TAG_SCOPE, "stream sampler stopped")
    }

    /**
     * 从当前最新快照截取一帧（格式与 [EnvironmentCollector.collectAll] 输出一致）。
     * 在录像采样线程调用；未收集到任何数据时返回带空数组的帧（由调用方决定是否跳过）。
     */
    fun snapshot(): JSONObject {
        return JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("location", snapshotLocation())
            put("cell", snapshotCell())
            put("wifi", snapshotWifi())
            put("bluetooth", snapshotBluetooth())
            put("gnss", snapshotGnss())
            put("sensor", snapshotSensor())
        }
    }

    /** 是否有任一有效数据（位置/基站/WiFi/蓝牙/GNSS/传感器）。 */
    fun hasAnyData(): Boolean {
        return lastLocation != null ||
            lastGnssStatus != null ||
            lastStepCount >= 0 ||
            sensorRaw.isNotEmpty() ||
            synchronized(bleFound) { bleFound.isNotEmpty() } ||
            (telephonyManager?.allCellInfo?.isNotEmpty() == true) ||
            (wifiManager?.scanResults?.isNotEmpty() == true)
    }

    private fun snapshotLocation(): JSONObject {
        val out = JSONObject()
        val loc = lastLocation ?: return out
        out.put(
            loc.provider ?: "gps",
            JSONObject().apply {
                put("latitude", loc.latitude)
                put("longitude", loc.longitude)
                put("accuracy", loc.accuracy)
                put("speed", loc.speed)
                put("time", loc.time)
            }
        )
        // 扁平主字段：与快照采集一致，供“保存到位置模拟/已保存地点”直接使用
        out.put("latitude", loc.latitude)
        out.put("longitude", loc.longitude)
        out.put("accuracy", loc.accuracy)
        out.put("speed", loc.speed)
        out.put("time", loc.time)
        return out
    }

    @SuppressLint("HardwareIds")
    private fun snapshotCell(): JSONObject {
        val out = JSONObject()
        val tm = telephonyManager ?: return out
        val cells = try {
            tm.allCellInfo ?: emptyList()
        } catch (t: Throwable) {
            out.put("error", t.message)
            emptyList()
        }
        val arr = JSONArray()
        cells.forEach { info ->
            val id = info.cellIdentity
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
        return out
    }

    private fun snapshotWifi(): JSONObject {
        val out = JSONObject()
        val wm = wifiManager ?: return out
        val results = try {
            wm.scanResults ?: emptyList()
        } catch (t: Throwable) {
            out.put("error", t.message)
            emptyList()
        }
        out.put("enabled", wm.isWifiEnabled)
        out.put(
            "networks",
            JSONArray().apply {
                results.take(20).forEach { r ->
                    put(
                        JSONObject().apply {
                            put("ssid", r.SSID)
                            put("bssid", r.BSSID)
                            put("rssi", r.level)
                            put("frequency", r.frequency)
                        }
                    )
                }
            }
        )
        return out
    }

    private fun snapshotBluetooth(): JSONObject {
        val out = JSONObject()
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = bm.adapter
        out.put("enabled", adapter?.isEnabled ?: false)
        out.put(
            "bonded",
            JSONArray().apply {
                adapter?.bondedDevices?.take(20)?.forEach { d ->
                    put(
                        JSONObject().apply {
                            put("name", d.name)
                            put("address", d.address)
                            put("type", d.type)
                        }
                    )
                }
            }
        )
        out.put(
            "devices",
            JSONArray().apply {
                synchronized(bleFound) {
                    bleFound.values.take(20).forEach { line ->
                        val parts = line.split(" ")
                        if (parts.size >= 2) {
                            val address = parts[parts.size - 2]
                            val rssiStr = parts.last().removeSuffix("dBm")
                            put(
                                JSONObject().apply {
                                    put("address", address)
                                    put("name", line.substringBefore(" $address"))
                                    put("rssi", rssiStr.toIntOrNull() ?: -70)
                                }
                            )
                        }
                    }
                }
            }
        )
        return out
    }

    private fun snapshotGnss(): JSONObject {
        val out = JSONObject()
        val status = lastGnssStatus ?: return out
        out.put("available", true)
        out.put("satelliteCount", status.satelliteCount)
        val sats = JSONArray()
        var used = 0
        for (i in 0 until status.satelliteCount) {
            if (status.usedInFix(i)) used++
            sats.put(
                JSONObject().apply {
                    put("svid", status.getSvid(i))
                    put("cn0", status.getCn0DbHz(i))
                    put("elevation", status.getElevationDegrees(i))
                    put("azimuth", status.getAzimuthDegrees(i))
                    put("used", status.usedInFix(i))
                }
            )
        }
        out.put("usedInFix", used)
        out.put("satellites", sats)
        return out
    }

    private fun snapshotSensor(): JSONObject {
        val out = JSONObject()
        sensorRaw[Sensor.TYPE_ACCELEROMETER]?.let { raw ->
            val nums = raw.substringAfter('[').substringBefore(']').split(",")
            if (nums.size >= 3) {
                out.put(
                    "accelerometer",
                    JSONArray().apply {
                        nums.take(3).forEach { put(it.trim().toDoubleOrNull() ?: 0.0) }
                    }
                )
            }
        }
        sensorRaw[Sensor.TYPE_GYROSCOPE]?.let { raw ->
            val nums = raw.substringAfter('[').substringBefore(']').split(",")
            if (nums.size >= 3) {
                out.put(
                    "gyroscope",
                    JSONArray().apply {
                        nums.take(3).forEach { put(it.trim().toDoubleOrNull() ?: 0.0) }
                    }
                )
            }
        }
        if (lastStepCount >= 0) out.put("stepCounter", lastStepCount)
        out.put("accuracy", 3)
        out.put("sampleRateMs", 50)
        return out
    }
}
