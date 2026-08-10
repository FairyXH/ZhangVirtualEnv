package io.github.fairyxh.VirtualEnv.app.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.ScanResult as WifiScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.app.AmapPrivacyManager
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 设置页：应用标识（包名 / SHA1）复制 + 高德地图 Key 配置 + 隐私合规同意。
 *
 * 底部「环境实时测试（普通 App 视角）」：不 Suspend，用标准系统 API 持续读取
 * 位置 / 基站 / 蓝牙 / WiFi / 传感器 / GNSS，实时分栏刷新，点击结束停止。
 */
class SettingsFragment : Fragment() {

    companion object {
        private const val TAG_SCOPE = "UI"
        private const val PREFS = "amap_config"
        private const val KEY_AMAP_KEY = "amap_key"
        private const val KEY_AMAP_SECURITY = "amap_security_key"
        private const val REFRESH_MS = 1000L
        private const val BLE_RESULTS_LIMIT = 20

        private const val AMAP_PRIVACY_URL = "https://lbs.amap.com/api/android-sdk/guide/create-project/dev-attention"
    }

    private lateinit var packageValue: TextView
    private lateinit var sha1Value: TextView
    private lateinit var amapKeyInput: EditText
    private lateinit var amapSecurityInput: EditText
    private lateinit var privacyAgreeCheck: CheckBox

    private lateinit var envTestStartButton: Button
    private lateinit var envTestStopButton: Button
    private lateinit var envTestLocationValue: TextView
    private lateinit var envTestCellValue: TextView
    private lateinit var envTestBleValue: TextView
    private lateinit var envTestWifiValue: TextView
    private lateinit var envTestSensorValue: TextView
    private lateinit var envTestGnssValue: TextView

    // ---- 环境实时测试状态 ----
    private val envTestRunning = AtomicBoolean(false)
    private var envTestScheduler: ScheduledExecutorService? = null

    private var locationManager: LocationManager? = null
    private var telephonyManager: TelephonyManager? = null
    private var wifiManager: WifiManager? = null
    private var sensorManager: SensorManager? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var stepSensor: Sensor? = null

    private val bleFound = LinkedHashMap<String, String>()
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // 刷新线程也会读取 getLastKnownLocation，这里只作触发
        }
    }
    private val gnssListener = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            lastGnssStatus = status
        }
    }
    @Volatile
    private var lastGnssStatus: GnssStatus? = null
    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_STEP_COUNTER && event.values.isNotEmpty()) {
                lastStepCount = event.values[0].toLong()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    @Volatile
    private var lastStepCount: Long = -1L
    @Volatile
    private var lastStepTickMs: Long = 0L

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName ?: device.name ?: "(no name)"
            val line = "${name} ${device.address} ${result.rssi}dBm"
            synchronized(bleFound) {
                bleFound[device.address] = line
                while (bleFound.size > BLE_RESULTS_LIMIT) {
                    val it = bleFound.entries.iterator()
                    if (it.hasNext()) it.remove()
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            ZLog.w(TAG_SCOPE, "env test ble scan failed errorCode=$errorCode")
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            startEnvTest()
        } else {
            envTestStartButton.isEnabled = true
            Toast.makeText(requireContext(), R.string.settings_env_test_perm, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_settings, container, false)
        packageValue = root.findViewById(R.id.packageValue)
        sha1Value = root.findViewById(R.id.sha1Value)
        amapKeyInput = root.findViewById(R.id.amapKeyInput)
        amapSecurityInput = root.findViewById(R.id.amapSecurityInput)
        privacyAgreeCheck = root.findViewById(R.id.privacyAgreeCheck)

        envTestStartButton = root.findViewById(R.id.envTestStartButton)
        envTestStopButton = root.findViewById(R.id.envTestStopButton)
        envTestLocationValue = root.findViewById(R.id.envTestLocationValue)
        envTestCellValue = root.findViewById(R.id.envTestCellValue)
        envTestBleValue = root.findViewById(R.id.envTestBleValue)
        envTestWifiValue = root.findViewById(R.id.envTestWifiValue)
        envTestSensorValue = root.findViewById(R.id.envTestSensorValue)
        envTestGnssValue = root.findViewById(R.id.envTestGnssValue)

        val context = requireContext()
        packageValue.text = context.packageName
        sha1Value.text = signingSha1(context) ?: getString(R.string.settings_sha1_unknown)

        root.findViewById<Button>(R.id.copyPackageButton).setOnClickListener {
            copyText(context.packageName)
        }
        root.findViewById<Button>(R.id.copySha1Button).setOnClickListener {
            sha1Value.text?.toString()?.let { copyText(it) }
        }
        privacyAgreeCheck.setOnCheckedChangeListener { _, checked ->
            AmapPrivacyManager.setAgreed(requireContext(), checked)
        }
        root.findViewById<TextView>(R.id.privacyPolicyLink).setOnClickListener {
            try {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(AMAP_PRIVACY_URL)
                )
                startActivity(intent)
            } catch (t: Throwable) {
                Toast.makeText(requireContext(), R.string.settings_no_browser, Toast.LENGTH_SHORT).show()
            }
        }
        root.findViewById<Button>(R.id.saveAmapButton).setOnClickListener { saveAmapConfig() }

        envTestStartButton.setOnClickListener { onEnvTestStart() }
        envTestStopButton.setOnClickListener { stopEnvTest() }

        loadAmapConfig()
        return root
    }

    override fun onDestroyView() {
        stopEnvTest()
        super.onDestroyView()
    }

    // ---------- 环境实时测试（普通 App 视角，不 Suspend） ----------

    private fun onEnvTestStart() {
        if (envTestRunning.get()) return
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            startEnvTest()
        }
    }

    private fun requiredPermissions(): List<String> {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return perms
    }

    private fun startEnvTest() {
        if (!isAdded) return
        val context = requireContext()
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val adapter = BluetoothAdapter.getDefaultAdapter()
        bleScanner = adapter?.bluetoothLeScanner

        envTestRunning.set(true)
        envTestStartButton.isEnabled = false
        envTestStopButton.isEnabled = true
        envTestLocationValue.text = getString(R.string.settings_env_test_running)
        envTestCellValue.text = getString(R.string.settings_env_test_running)
        envTestBleValue.text = getString(R.string.settings_env_test_running)
        envTestWifiValue.text = getString(R.string.settings_env_test_running)
        envTestSensorValue.text = getString(R.string.settings_env_test_running)
        envTestGnssValue.text = getString(R.string.settings_env_test_running)
        synchronized(bleFound) { bleFound.clear() }
        lastStepCount = -1L
        lastStepTickMs = 0L

        // 传感器：计步器监听（步频注入会表现为持续/加快的 step counter）
        try {
            stepSensor?.let {
                sensorManager?.registerListener(stepListener, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "env test sensor register failed", t)
        }

        // 位置：请求一次定位更新（普通 App 视角：getLastKnownLocation + 回调）
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper()
            )
        } catch (_: Throwable) {
        }
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper()
            )
        } catch (_: Throwable) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                locationManager?.registerGnssStatusCallback(
                    Executors.newSingleThreadExecutor(),
                    gnssListener
                )
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "env test gnss register failed", t)
        }

        // 蓝牙：持续 LE 扫描（蓝牙栈 Hook 会投递虚拟设备）
        try {
            bleScanner?.startScan(bleScanCallback)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "env test ble startScan failed", t)
        }
        // 主动触发 WiFi 扫描（每 2 个周期一次）
        try {
            wifiManager?.startScan()
        } catch (_: Throwable) {
        }

        val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ZVE-EnvTest").apply { isDaemon = true }
        }
        envTestScheduler = scheduler
        scheduler.scheduleWithFixedDelay(
            { refreshEnvTest() },
            500,
            REFRESH_MS,
            TimeUnit.MILLISECONDS
        )
        ZLog.i(TAG_SCOPE, "env test started")
    }

    private fun refreshEnvTest() {
        if (!envTestRunning.get() || !isAdded) return
        val ctx = requireContext()
        try {
            // 位置：优先 last known，兼容虚拟定位（栈内注入）
            val locationText = buildLocationText(ctx)
            requireActivity().runOnUiThread {
                envTestLocationValue.text = locationText
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "env test location read failed", t)
        }
        try {
            val cellText = buildCellText()
            requireActivity().runOnUiThread {
                envTestCellValue.text = cellText
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "env test cell read failed", t)
        }
        try {
            val bleText = buildBleText()
            requireActivity().runOnUiThread {
                envTestBleValue.text = bleText
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "env test ble read failed", t)
        }
        try {
            val wifiText = buildWifiText()
            requireActivity().runOnUiThread {
                envTestWifiValue.text = wifiText
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "env test wifi read failed", t)
        }
        try {
            val sensorText = buildSensorText()
            requireActivity().runOnUiThread {
                envTestSensorValue.text = sensorText
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "env test sensor read failed", t)
        }
        try {
            val gnssText = buildGnssText()
            requireActivity().runOnUiThread {
                envTestGnssValue.text = gnssText
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "env test gnss read failed", t)
        }
    }

    private fun buildLocationText(context: Context): String {
        val lm = locationManager ?: return "LocationManager 不可用"
        val sb = StringBuilder()
        for (provider in arrayOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )) {
            try {
                val loc = lm.getLastKnownLocation(provider)
                if (loc != null) {
                    sb.append(provider).append(": ")
                        .append(String.format("%.6f", loc.latitude)).append(",")
                        .append(String.format("%.6f", loc.longitude))
                        .append(" acc=").append(loc.accuracy)
                        .append(" alt=").append(String.format("%.1f", loc.altitude))
                        .append(" speed=").append(String.format("%.1f", loc.speed))
                        .append(" time=").append(loc.time)
                        .append("\n")
                }
            } catch (t: Throwable) {
                sb.append(provider).append(": ").append(t.message).append("\n")
            }
        }
        if (sb.isEmpty()) sb.append("无位置（等待定位或虚拟位置未启用）")
        return sb.toString().trim()
    }

    private fun buildCellText(): String {
        val tm = telephonyManager ?: return "TelephonyManager 不可用"
        val cells: List<CellInfo> = try {
            @Suppress("DEPRECATION")
            tm.allCellInfo ?: emptyList()
        } catch (t: Throwable) {
            return "读取失败: ${t.message}"
        }
        if (cells.isEmpty()) return "无基站（虚拟基站未启用或权限不足）"
        val sb = StringBuilder()
        cells.forEach { info ->
            when (info) {
                is CellInfoLte -> {
                    val id = info.cellIdentity
                    sb.append("LTE mcc=").append(id.mcc)
                        .append(" mnc=").append(id.mnc)
                        .append(" tac=").append(id.tac)
                        .append(" ci=").append(id.ci)
                        .append(" pci=").append(id.pci)
                        .append(" rsrp=").append(info.cellSignalStrength?.dbm).append("\n")
                }
                is CellInfoNr -> {
                    val id = info.cellIdentity as? android.telephony.CellIdentityNr
                    sb.append("NR")
                    if (id != null) {
                        sb.append(" mcc=").append(id.mccString)
                            .append(" mnc=").append(id.mncString)
                            .append(" tac=").append(id.tac)
                            .append(" nci=").append(id.nci)
                    }
                    sb.append(" ss=").append(info.cellSignalStrength?.dbm).append("\n")
                }
                is CellInfoGsm -> {
                    val id = info.cellIdentity
                    sb.append("GSM mcc=").append(id.mcc)
                        .append(" mnc=").append(id.mnc)
                        .append(" lac=").append(id.lac)
                        .append(" cid=").append(id.cid)
                        .append(" asu=").append(info.cellSignalStrength?.asuLevel).append("\n")
                }
                is CellInfoWcdma -> {
                    val id = info.cellIdentity
                    sb.append("WCDMA mcc=").append(id.mcc)
                        .append(" mnc=").append(id.mnc)
                        .append(" lac=").append(id.lac)
                        .append(" cid=").append(id.cid)
                        .append(" asu=").append(info.cellSignalStrength?.asuLevel).append("\n")
                }
                else -> sb.append(info.javaClass.simpleName).append("\n")
            }
        }
        return sb.toString().trim()
    }

    private fun buildBleText(): String {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) return "蓝牙未开启"
        val sb = StringBuilder()
        synchronized(bleFound) {
            bleFound.values.forEach { sb.append(it).append("\n") }
        }
        val bonded = adapter.bondedDevices
        if (bonded.isNotEmpty()) {
            sb.append("已配对: ").append(bonded.map { it.name ?: it.address }.joinToString(", ")).append("\n")
        }
        if (sb.isEmpty()) sb.append("扫描中未发现设备（等待虚拟 BLE 或真实设备）")
        return sb.toString().trim()
    }

    private fun buildWifiText(): String {
        val wm = wifiManager ?: return "WifiManager 不可用"
        // 每 2 秒主动触发一次扫描（普通 App 视角），读取最近结果
        try {
            wm.startScan()
        } catch (_: Throwable) {
        }
        val results: List<WifiScanResult> = try {
            wm.scanResults ?: emptyList()
        } catch (t: Throwable) {
            return "读取失败: ${t.message}"
        }
        if (results.isEmpty()) return "无 WiFi 结果（虚拟 WiFi 未启用或未扫描）"
        val sb = StringBuilder()
        results.sortedByDescending { it.level }.take(10).forEach { r ->
            val ssid = r.SSID.ifEmpty { "(hidden)" }
            sb.append(ssid).append(" ").append(r.BSSID)
                .append(" ").append(r.level).append("dBm")
                .append(" ").append(if (r.frequency > 0) "${r.frequency}MHz" else "")
                .append("\n")
        }
        return sb.toString().trim()
    }

    private fun buildSensorText(): String {
        val sm = sensorManager ?: return "SensorManager 不可用"
        val sb = StringBuilder()
        if (stepSensor != null) {
            sb.append("计步器: ")
            if (lastStepCount >= 0) {
                sb.append(lastStepCount).append(" 步")
            } else {
                sb.append("等待事件")
            }
            sb.append("\n")
        }
        // 读取当前常用传感器列表（普通 App 视角可见的）
        val present = listOf(
            Sensor.TYPE_ACCELEROMETER to "加速度",
            Sensor.TYPE_GYROSCOPE to "陀螺仪",
            Sensor.TYPE_MAGNETIC_FIELD to "磁力",
            Sensor.TYPE_LIGHT to "光线",
            Sensor.TYPE_PROXIMITY to "距离"
        ).mapNotNull { (t, label) ->
            sm.getDefaultSensor(t)?.let { "$label: 有" }
        }
        if (present.isNotEmpty()) sb.append(present.joinToString(", ")).append("\n")
        if (sb.isEmpty()) sb.append("无可用传感器")
        return sb.toString().trim()
    }

    private fun buildGnssText(): String {
        val lm = locationManager ?: return "LocationManager 不可用"
        val sb = StringBuilder()
        val status = lastGnssStatus
        if (status != null) {
            val used = (0 until status.satelliteCount).count { status.usedInFix(it) }
            sb.append("卫星: ").append(status.satelliteCount)
                .append(" 使用: ").append(used).append("\n")
        } else {
            sb.append("GNSS 回调未收到数据\n")
        }
        try {
            val isGnssEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            sb.append("GPS 开关: ").append(if (isGnssEnabled) "开" else "关").append("\n")
        } catch (_: Throwable) {
        }
        if (sb.isEmpty()) sb.append("无 GNSS 数据")
        return sb.toString().trim()
    }

    private fun stopEnvTest() {
        if (!envTestRunning.compareAndSet(true, false)) {
            envTestScheduler?.shutdownNow()
            envTestScheduler = null
            return
        }
        envTestScheduler?.shutdownNow()
        envTestScheduler = null
        try {
            bleScanner?.stopScan(bleScanCallback)
        } catch (_: Throwable) {
        }
        try {
            stepSensor?.let { sensorManager?.unregisterListener(stepListener, it) }
        } catch (_: Throwable) {
        }
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: Throwable) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                locationManager?.unregisterGnssStatusCallback(gnssListener)
            }
        } catch (_: Throwable) {
        }
        if (isAdded) {
            envTestStartButton.isEnabled = true
            envTestStopButton.isEnabled = false
        }
        ZLog.i(TAG_SCOPE, "env test stopped")
    }

    private fun loadAmapConfig() {
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        amapKeyInput.setText(prefs.getString(KEY_AMAP_KEY, ""))
        amapSecurityInput.setText(prefs.getString(KEY_AMAP_SECURITY, ""))
        privacyAgreeCheck.isChecked = AmapPrivacyManager.isAgreed(requireContext())
    }

    private fun saveAmapConfig() {
        val key = amapKeyInput.text.toString().trim()
        if (key.isEmpty()) {
            Toast.makeText(requireContext(), R.string.settings_amap_key_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (!privacyAgreeCheck.isChecked) {
            Toast.makeText(requireContext(), R.string.settings_amap_privacy_required, Toast.LENGTH_LONG).show()
            return
        }
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_AMAP_KEY, key)
            .putString(KEY_AMAP_SECURITY, amapSecurityInput.text.toString().trim())
            .apply()
        ZLog.i(TAG_SCOPE, "amap config saved")
        Toast.makeText(requireContext(), R.string.settings_amap_saved, Toast.LENGTH_SHORT).show()
    }

    private fun copyText(text: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("zve", text))
        Toast.makeText(requireContext(), R.string.settings_copied, Toast.LENGTH_SHORT).show()
    }

    /** 读取应用签名 SHA1（高德开放平台 APP 信息校验用）。 */
    private fun signingSha1(context: Context): String? {
        return try {
            val info = if (Build.VERSION.SDK_INT >= 28) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
            val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= 28) {
                info.signingInfo?.apkContentsSigners ?: arrayOf()
            } else {
                @Suppress("DEPRECATION")
                info.signatures ?: arrayOf()
            }
            val sha1 = MessageDigest.getInstance("SHA1").digest(signatures.first().toByteArray())
            sha1.joinToString(":") { "%02X".format(it) }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "get signing sha1 failed", t)
            null
        }
    }
}
