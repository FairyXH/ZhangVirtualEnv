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
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.app.AmapPrivacyManager
import io.github.fairyxh.VirtualEnv.app.ui.glass.AppBackground
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassBackdropHost
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassButton
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassCard
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassCheckbox
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassField
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassToggle
import io.github.fairyxh.VirtualEnv.app.ui.glass.glassColors
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 设置页：应用标识（包名 / SHA1）复制 + 高德地图 Key 配置 + 隐私合规同意 +
 * 桌面图标隐藏 + 环境实时测试（普通 App 视角，不 Suspend）。
 *
 * 视图层已迁移到 Compose Liquid Glass，业务逻辑保持不变。
 */
class SettingsFragment : Fragment() {

    companion object {
        private const val TAG_SCOPE = "UI"
        private const val PREFS = "amap_config"
        private const val KEY_AMAP_KEY = "amap_key"
        private const val KEY_AMAP_SECURITY = "amap_security_key"
        private const val PREFS_UI = "zve_ui"
        private const val KEY_LAUNCHER_HIDDEN = "launcher_hidden"
        private const val REFRESH_MS = 1000L
        private const val BLE_RESULTS_LIMIT = 20

        private const val AMAP_PRIVACY_URL = "https://lbs.amap.com/api/android-sdk/guide/create-project/dev-attention"
    }

    private enum class Verdict { PASS, FAIL, NOT_ENABLED }

    private data class EnvTestField(
        val title: String,
        val status: String = "",
        val statusColor: Color = Color.Unspecified,
        val value: String = ""
    )

    // ---------- Compose 视图状态 ----------

    private var packageValue by mutableStateOf("")
    private var sha1Value by mutableStateOf("")
    private var amapKey by mutableStateOf("")
    private var amapSecurity by mutableStateOf("")
    private var privacyAgreed by mutableStateOf(false)
    private var launcherHidden by mutableStateOf(false)

    private var envTestRunningState by mutableStateOf(false)
    private var envTestFields by mutableStateOf(
        listOf(
            EnvTestField("位置"), EnvTestField("基站"), EnvTestField("蓝牙"),
            EnvTestField("WiFi"), EnvTestField("传感器"), EnvTestField("GNSS"),
            EnvTestField("SIM")
        )
    )

    // ---- 环境实时测试状态 ----
    private val envTestRunning = AtomicBoolean(false)
    private var envTestScheduler: ScheduledExecutorService? = null

    private var locationManager: LocationManager? = null
    private var telephonyManager: TelephonyManager? = null
    private var wifiManager: WifiManager? = null
    private var sensorManager: SensorManager? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var stepSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var magSensor: Sensor? = null
    private var lightSensor: Sensor? = null
    private var proxSensor: Sensor? = null

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
    // 原始传感器值缓存（加速度/陀螺仪/磁力/光线/距离）
    private val sensorRaw = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val rawSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val vals = event.values.joinToString(", ") { String.format("%.3f", it) }
            sensorRaw[event.sensor.type] = "${event.sensor.name} [$vals]"
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    @Volatile
    private var lastStepCount: Long = -1L
    @Volatile
    private var lastStepTickMs: Long = 0L

    // ---- 虚拟配置期望（判定依据） ----
    @Volatile
    private var expectEnv: org.json.JSONObject? = null
    @Volatile
    private var expectLocation: org.json.JSONObject? = null
    @Volatile
    private var expectRoute: org.json.JSONObject? = null

    // ---- 最近一次采集快照（供判定） ----
    @Volatile
    private var lastLocation: Location? = null
    @Volatile
    private var lastCellText: String = ""
    @Volatile
    private var lastWifiText: String = ""
    @Volatile
    private var lastSimText: String = ""

    @android.annotation.SuppressLint("MissingPermission")
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
            envTestRunningState = false
            Toast.makeText(requireContext(), R.string.settings_env_test_perm, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        packageValue = context.packageName
        sha1Value = signingSha1(context) ?: getString(R.string.settings_sha1_unknown)
        val idle = getString(R.string.settings_env_test_idle)
        envTestFields = listOf(
            EnvTestField("位置", idle, value = idle),
            EnvTestField("基站", idle, value = idle),
            EnvTestField("蓝牙", idle, value = idle),
            EnvTestField("WiFi", idle, value = idle),
            EnvTestField("传感器", idle, value = idle),
            EnvTestField("GNSS", idle, value = idle),
            EnvTestField("SIM", idle, value = idle)
        )
        loadAmapConfig()
        initLauncherHideToggle()

        return androidx.compose.ui.platform.ComposeView(context).apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SettingsScreen(this@SettingsFragment)
            }
        }
    }

    override fun onDestroyView() {
        stopEnvTest()
        super.onDestroyView()
    }

    // ---------- Compose UI ----------

    @Composable
    private fun SettingsScreen(fragment: SettingsFragment) {
        GlassBackdropHost(
            modifier = Modifier
                .fillMaxSize()
        ) { backdrop ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 130.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val colors = glassColors()
                BasicText(
                    getString(R.string.settings_title),
                    style = TextStyle(
                        color = colors.textPrimary,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    )
                )
                BasicText(
                    getString(R.string.settings_subtitle),
                    style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                )

                // 应用标识卡
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        SectionTitle(getString(R.string.settings_identity_title))
                        SectionDesc(getString(R.string.settings_identity_desc))
                        SectionLabel(getString(R.string.settings_package_label))
                        Row(
                            Modifier
                                .padding(top = 4.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicText(
                                packageValue,
                                Modifier.weight(1f),
                                style = TextStyle(color = colors.textPrimary, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                            )
                            GlassButton(
                                onClick = { fragment.copyText(packageValue) },
                                backdrop = backdrop,
                                modifier = Modifier.padding(start = 8.dp),
                                isInteractive = false,
                                surfaceColor = colors.bgTertiary.copy(alpha = 0.4f)
                            ) {
                                BasicText(
                                    getString(R.string.settings_copy),
                                    style = TextStyle(color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                        SectionLabel(getString(R.string.settings_sha1_label))
                        Row(
                            Modifier
                                .padding(top = 4.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicText(
                                sha1Value,
                                Modifier.weight(1f),
                                style = TextStyle(color = colors.textPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                            )
                            GlassButton(
                                onClick = { fragment.copyText(sha1Value) },
                                backdrop = backdrop,
                                modifier = Modifier.padding(start = 8.dp),
                                isInteractive = false,
                                surfaceColor = colors.bgTertiary.copy(alpha = 0.4f)
                            ) {
                                BasicText(
                                    getString(R.string.settings_copy),
                                    style = TextStyle(color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }

                // 外观设置卡
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                ) {
                    Row(
                        Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            SectionTitle(getString(R.string.settings_appearance_title))
                            SectionDesc(getString(R.string.settings_appearance_desc))
                        }
                        GlassToggle(
                            selected = { AppBackground.useWallpaper },
                            onSelect = { fragment.setWallpaperBackground(it) },
                            backdrop = backdrop,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }

                // 高德地图 Key 卡
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        SectionTitle(getString(R.string.settings_amap_title))
                        SectionDesc(getString(R.string.settings_amap_desc))
                        SectionLabel(getString(R.string.settings_amap_key_label))
                        GlassField(
                            value = amapKey,
                            onValueChange = { amapKey = it },
                            backdrop = backdrop,
                            modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                            placeholder = getString(R.string.settings_amap_key_hint)
                        )
                        SectionLabel(getString(R.string.settings_amap_security_label))
                        GlassField(
                            value = amapSecurity,
                            onValueChange = { amapSecurity = it },
                            backdrop = backdrop,
                            modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                            placeholder = getString(R.string.settings_amap_security_hint)
                        )
                        Row(
                            Modifier
                                .padding(top = 10.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GlassCheckbox(
                                checked = privacyAgreed,
                                onCheckedChange = { checked ->
                                    privacyAgreed = checked
                                    AmapPrivacyManager.setAgreed(requireContext(), checked)
                                }
                            )
                            BasicText(
                                getString(R.string.settings_amap_privacy),
                                Modifier
                                    .padding(start = 8.dp)
                                    .weight(1f),
                                style = TextStyle(color = colors.textSecondary, fontSize = 15.sp)
                            )
                        }
                        BasicText(
                            getString(R.string.settings_amap_privacy_link),
                            Modifier
                                .padding(start = 30.dp, top = 2.dp)
                                .fillMaxWidth(),
                            style = TextStyle(color = colors.accent, fontSize = 13.sp)
                        )
                        GlassButton(
                            onClick = { fragment.saveAmapConfig() },
                            backdrop = backdrop,
                            modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                            tint = colors.accent
                        ) {
                            BasicText(
                                getString(R.string.settings_amap_save),
                                style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }

                // 桌面图标卡
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        SectionTitle(getString(R.string.settings_launcher_title))
                        SectionDesc(getString(R.string.settings_launcher_desc))
                        Row(
                            Modifier
                                .padding(top = 10.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GlassCheckbox(
                                checked = launcherHidden,
                                onCheckedChange = { checked ->
                                    launcherHidden = checked
                                    fragment.applyLauncherAlias(checked, silent = false)
                                }
                            )
                            BasicText(
                                getString(R.string.settings_launcher_hide),
                                Modifier
                                    .padding(start = 8.dp)
                                    .weight(1f),
                                style = TextStyle(color = colors.textSecondary, fontSize = 15.sp)
                            )
                        }
                    }
                }

                // 环境实时测试卡
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        SectionTitle(getString(R.string.settings_env_test_title))
                        SectionDesc(getString(R.string.settings_env_test_desc))
                        Row(
                            Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            GlassButton(
                                onClick = { fragment.onEnvTestStart() },
                                backdrop = backdrop,
                                modifier = Modifier.weight(1f),
                                tint = colors.accent
                            ) {
                                BasicText(
                                    getString(R.string.settings_env_test_start),
                                    style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                )
                            }
                            GlassButton(
                                onClick = { fragment.stopEnvTest() },
                                backdrop = backdrop,
                                modifier = Modifier.weight(1f),
                                isInteractive = envTestRunningState,
                                surfaceColor = colors.bgTertiary.copy(alpha = 0.4f)
                            ) {
                                BasicText(
                                    getString(R.string.settings_env_test_stop),
                                    style = TextStyle(color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                        envTestFields.forEach { field ->
                            Column(
                                Modifier.padding(top = 12.dp).fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    BasicText(
                                        field.title,
                                        Modifier.weight(1f),
                                        style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                                    )
                                    BasicText(
                                        field.status,
                                        style = TextStyle(
                                            color = if (field.statusColor.isSpecified) field.statusColor else colors.textTertiary,
                                            fontSize = 13.sp
                                        )
                                    )
                                }
                                BasicText(
                                    field.value,
                                    Modifier.padding(top = 4.dp).fillMaxWidth(),
                                    style = TextStyle(
                                        color = colors.textSecondary,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SectionTitle(text: String) {
        val colors = glassColors()
        BasicText(
            text,
            style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        )
    }

    @Composable
    private fun SectionDesc(text: String) {
        val colors = glassColors()
        BasicText(
            text,
            Modifier.padding(top = 4.dp),
            style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
        )
    }

    @Composable
    private fun SectionLabel(text: String) {
        val colors = glassColors()
        BasicText(
            text,
            Modifier.padding(top = 12.dp),
            style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
        )
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

    @android.annotation.SuppressLint("MissingPermission")
    private fun startEnvTest() {
        if (!isAdded) return
        val context = requireContext()
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        proxSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val adapter = BluetoothAdapter.getDefaultAdapter()
        bleScanner = adapter?.bluetoothLeScanner

        envTestRunning.set(true)
        envTestRunningState = true
        val running = getString(R.string.settings_env_test_running)
        envTestFields = envTestFields.map { it.copy(value = running) }
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
        // 原始传感器监听（加速度/陀螺仪/磁力/光线/距离）
        sensorRaw.clear()
        val rawSensors = listOfNotNull(accelSensor, gyroSensor, magSensor, lightSensor, proxSensor)
        for (s in rawSensors) {
            try {
                sensorManager?.registerListener(rawSensorListener, s, SensorManager.SENSOR_DELAY_NORMAL)
            } catch (_: Throwable) {
            }
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

    @android.annotation.SuppressLint("MissingPermission")
    private fun refreshEnvTest() {
        if (!envTestRunning.get() || !isAdded) return
        val ctx = requireContext()
        val report = org.json.JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("running", true)
        }
        // 拉取虚拟配置期望（失败时保留上次，判为未启用模拟）
        try {
            val env = io.github.fairyxh.VirtualEnv.app.ApiClient.getEnvStatus()
            if (env.code == io.github.fairyxh.VirtualEnv.core.model.ApiResult.CODE_OK) expectEnv = env.data
        } catch (_: Throwable) {
        }
        try {
            val loc = io.github.fairyxh.VirtualEnv.app.ApiClient.getLocationStatus()
            if (loc.code == io.github.fairyxh.VirtualEnv.core.model.ApiResult.CODE_OK) expectLocation = loc.data
        } catch (_: Throwable) {
        }
        try {
            val route = io.github.fairyxh.VirtualEnv.app.ApiClient.getRouteStatus()
            if (route.code == io.github.fairyxh.VirtualEnv.core.model.ApiResult.CODE_OK) expectRoute = route.data
        } catch (_: Throwable) {
        }

        val updates = mutableMapOf<String, Pair<String, Verdict?>>()

        try {
            val loc = readLastLocation()
            lastLocation = loc
            val locationText = buildLocationText(ctx, loc)
            val v = judgeLocation(loc)
            report.put("location", org.json.JSONObject().apply {
                put("verdict", v.name)
                put("data", locationText)
            })
            updates["位置"] = locationText to v
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "env test location read failed", t)
        }
        try {
            val cellText = buildCellText()
            lastCellText = cellText
            val v = judgeCell()
            report.put("cell", org.json.JSONObject().apply {
                put("verdict", v.name)
                put("data", cellText)
            })
            updates["基站"] = cellText to v
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "env test cell read failed", t)
        }
        try {
            val bleText = buildBleText()
            val v = judgeBle()
            report.put("ble", org.json.JSONObject().apply {
                put("verdict", v.name)
                put("data", bleText)
            })
            updates["蓝牙"] = bleText to v
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "env test ble read failed", t)
        }
        try {
            val wifiText = buildWifiText()
            lastWifiText = wifiText
            val v = judgeWifi()
            report.put("wifi", org.json.JSONObject().apply {
                put("verdict", v.name)
                put("data", wifiText)
            })
            updates["WiFi"] = wifiText to v
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "env test wifi read failed", t)
        }
        try {
            val sensorText = buildSensorText()
            val v = judgeSensor()
            report.put("sensor", org.json.JSONObject().apply {
                put("verdict", v.name)
                put("data", sensorText)
            })
            updates["传感器"] = sensorText to v
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "env test sensor read failed", t)
        }
        try {
            val gnssText = buildGnssText()
            val v = judgeGnss()
            report.put("gnss", org.json.JSONObject().apply {
                put("verdict", v.name)
                put("data", gnssText)
            })
            updates["GNSS"] = gnssText to v
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "env test gnss read failed", t)
        }
        try {
            val simText = buildSimText()
            lastSimText = simText
            val v = judgeSim()
            report.put("sim", org.json.JSONObject().apply {
                put("verdict", v.name)
                put("data", simText)
            })
            updates["SIM"] = simText to v
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "env test sim read failed", t)
        }
        // 上报报告（每轮一次；失败静默，Backend 保留上一份）
        try {
            io.github.fairyxh.VirtualEnv.app.ApiClient.postTestReport(report)
        } catch (_: Throwable) {
        }

        requireActivity().runOnUiThread {
            if (updates.isNotEmpty() && isAdded) {
                envTestFields = envTestFields.map { field ->
                    updates[field.title]?.let { (value, v) ->
                        field.copy(
                            value = value,
                            status = when (v) {
                                Verdict.PASS -> getString(R.string.settings_env_test_pass)
                                Verdict.FAIL -> getString(R.string.settings_env_test_fail)
                                Verdict.NOT_ENABLED -> getString(R.string.settings_env_test_not_enabled)
                                null -> field.status
                            },
                            statusColor = when (v) {
                                Verdict.PASS -> Color(0xFF34C759)
                                Verdict.FAIL -> Color(0xFFFF3B30)
                                Verdict.NOT_ENABLED -> Color.Unspecified
                                null -> field.statusColor
                            }
                        )
                    } ?: field
                }
            }
        }
    }

    // ---------- 判定 ----------

    private fun envEnabled(type: String): Boolean {
        val env = expectEnv ?: return false
        return env.optJSONObject(type)?.optBoolean("enabled", false) == true
    }

    private fun envData(type: String): org.json.JSONObject? {
        val env = expectEnv ?: return null
        return if (env.optJSONObject(type)?.optBoolean("enabled", false) == true) {
            env.optJSONObject(type)?.optJSONObject("data")
        } else null
    }

    /** 位置判定：定位/路线启用时，期望坐标与 App 读到位置误差 <= 容差。 */
    private fun judgeLocation(loc: Location?): Verdict {
        val expected = expectLocation ?: return Verdict.NOT_ENABLED
        val enabled = expected.optBoolean("enabled", false)
        val mode = expected.optString("mode", "none")
        if (!enabled || mode == "none") return Verdict.NOT_ENABLED
        val expLat = expected.optDouble("latitude", Double.NaN)
        val expLon = expected.optDouble("longitude", Double.NaN)
        if (expLat.isNaN() || expLon.isNaN()) return Verdict.FAIL
        if (loc == null) return Verdict.FAIL
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            expLat, expLon, loc.latitude, loc.longitude, results
        )
        // 允许小误差：定位 300m、路线 500m
        val tolerance = if (mode == "route") 500f else 300f
        return if (results[0] <= tolerance) Verdict.PASS else Verdict.FAIL
    }

    /** 基站判定：配置 entries（mcc/mnc/tac/ci）在 App 读到文本中出现。 */
    private fun judgeCell(): Verdict {
        val data = envData("cell") ?: return Verdict.NOT_ENABLED
        val entries = data.optJSONArray("entries") ?: return Verdict.FAIL
        if (entries.length() == 0) return Verdict.FAIL
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val mcc = e.optInt("mcc", -1)
            val mnc = e.optInt("mnc", -1)
            val tac = e.optLong("tac", -1L)
            val ci = e.optLong("ci", -1L)
            if (mcc >= 0 && mnc >= 0 &&
                lastCellText.contains("mcc=$mcc") && lastCellText.contains("mnc=$mnc")
            ) {
                if (tac < 0 || ci < 0) return Verdict.PASS
                if (lastCellText.contains("tac=$tac") && lastCellText.contains("ci=$ci")) {
                    return Verdict.PASS
                }
            }
        }
        return Verdict.FAIL
    }

    /** 蓝牙判定：配置 devices address 出现在扫描结果。 */
    private fun judgeBle(): Verdict {
        val data = envData("ble") ?: return Verdict.NOT_ENABLED
        val devices = data.optJSONArray("devices") ?: return Verdict.FAIL
        if (devices.length() == 0) return Verdict.FAIL
        val found: Set<String> = synchronized(bleFound) { bleFound.keys.toSet() }
        for (i in 0 until devices.length()) {
            val address = devices.optJSONObject(i)?.optString("address", "")?.uppercase()
            if (!address.isNullOrBlank() && found.contains(address)) return Verdict.PASS
        }
        return Verdict.FAIL
    }

    /** WiFi 判定：配置 networks ssid/bssid 出现在扫描结果。 */
    private fun judgeWifi(): Verdict {
        val data = envData("wifi") ?: return Verdict.NOT_ENABLED
        val networks = data.optJSONArray("networks") ?: return Verdict.FAIL
        if (networks.length() == 0) return Verdict.FAIL
        for (i in 0 until networks.length()) {
            val n = networks.optJSONObject(i) ?: continue
            val ssid = n.optString("ssid", "")
            val bssid = n.optString("bssid", "").uppercase()
            if (ssid.isNotEmpty() && lastWifiText.contains(ssid)) return Verdict.PASS
            if (bssid.isNotEmpty() && lastWifiText.contains(bssid)) return Verdict.PASS
        }
        return Verdict.FAIL
    }

    /** 传感器判定：配置含步频/事件时，App 计步器已收到事件。 */
    private fun judgeSensor(): Verdict {
        val data = envData("sensor") ?: return Verdict.NOT_ENABLED
        val stepFreq = data.optInt("stepFrequency", 0)
        val hasEvents = data.optJSONArray("events")?.length() ?: 0
        if (stepFreq <= 0 && hasEvents <= 0) return Verdict.NOT_ENABLED
        return if (lastStepCount >= 0) Verdict.PASS else Verdict.FAIL
    }

    /** GNSS 判定：配置含卫星/使用数时，App GNSS 回调已收到数据。 */
    private fun judgeGnss(): Verdict {
        val data = envData("gnss") ?: return Verdict.NOT_ENABLED
        val expectSat = data.optInt("satelliteCount", 0)
        val expectUsed = data.optInt("usedInFix", 0)
        if (expectSat <= 0 && expectUsed <= 0) return Verdict.NOT_ENABLED
        val status = lastGnssStatus ?: return Verdict.FAIL
        val used = (0 until status.satelliteCount).count { status.usedInFix(it) }
        // 允许小误差：卫星数 >= 期望 80%，使用数 >= 期望 80%
        val satOk = expectSat <= 0 || status.satelliteCount >= (expectSat * 0.8).toInt()
        val usedOk = expectUsed <= 0 || used >= (expectUsed * 0.8).toInt()
        return if (satOk && usedOk) Verdict.PASS else Verdict.FAIL
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun readLastLocation(): Location? {
        val lm = locationManager ?: return null
        var best: Location? = null
        for (provider in arrayOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )) {
            try {
                val loc = lm.getLastKnownLocation(provider) ?: continue
                if (best == null || loc.time > best.time) best = loc
            } catch (_: Throwable) {
            }
        }
        return best
    }

    private fun buildLocationText(context: Context, loc: Location?): String {
        if (loc == null) return "无位置（等待定位或虚拟位置未启用）"
        return "provider=${loc.provider}\n" +
            String.format("lat=%.6f lon=%.6f", loc.latitude, loc.longitude) +
            "\nacc=" + loc.accuracy +
            " alt=" + String.format("%.1f", loc.altitude) +
            " speed=" + String.format("%.1f", loc.speed) +
            "\ntime=" + loc.time
    }

    @android.annotation.SuppressLint("MissingPermission", "NewApi")
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
                is CellInfoCdma -> {
                    val id = info.cellIdentity
                    sb.append("CDMA lat=").append(id.latitude)
                        .append(" lon=").append(id.longitude)
                        .append("\n")
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

    @android.annotation.SuppressLint("MissingPermission")
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

    @android.annotation.SuppressLint("MissingPermission")
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
        // 原始传感器值（按固定顺序展示）
        val order = listOf(
            Sensor.TYPE_ACCELEROMETER to "加速度",
            Sensor.TYPE_GYROSCOPE to "陀螺仪",
            Sensor.TYPE_MAGNETIC_FIELD to "磁力",
            Sensor.TYPE_LIGHT to "光线",
            Sensor.TYPE_PROXIMITY to "距离"
        )
        for ((type, label) in order) {
            val raw = sensorRaw[type]
            if (raw != null) {
                sb.append(label).append(": ").append(raw).append("\n")
            } else {
                val present = sm.getDefaultSensor(type) != null
                sb.append(label).append(": ").append(if (present) "等待事件" else "无").append("\n")
            }
        }
        if (sb.isEmpty()) sb.append("无可用传感器")
        return sb.toString().trim()
    }

    private fun buildGnssText(): String {
        val sb = StringBuilder()
        val status = lastGnssStatus
        if (status != null) {
            val used = (0 until status.satelliteCount).count { status.usedInFix(it) }
            sb.append("卫星: ").append(status.satelliteCount)
                .append(" 使用: ").append(used).append("\n")
            // 原始卫星明细（星座/编号/载噪比），最多 12 颗
            val maxShow = minOf(status.satelliteCount, 12)
            for (i in 0 until maxShow) {
                val cn0 = status.getCn0DbHz(i)
                val const = when {
                    status.getConstellationType(i) == android.location.GnssStatus.CONSTELLATION_GPS -> "GPS"
                    status.getConstellationType(i) == android.location.GnssStatus.CONSTELLATION_GLONASS -> "GLO"
                    status.getConstellationType(i) == android.location.GnssStatus.CONSTELLATION_BEIDOU -> "BDS"
                    status.getConstellationType(i) == android.location.GnssStatus.CONSTELLATION_GALILEO -> "GAL"
                    status.getConstellationType(i) == android.location.GnssStatus.CONSTELLATION_QZSS -> "QZS"
                    status.getConstellationType(i) == android.location.GnssStatus.CONSTELLATION_IRNSS -> "IRN"
                    else -> "?"
                }
                val usedMark = if (status.usedInFix(i)) "U" else "-"
                sb.append("  ").append(const)
                    .append(" sv").append(status.getSvid(i))
                    .append(" cn0=").append(String.format("%.1f", cn0))
                    .append(" ").append(usedMark).append("\n")
            }
        } else {
            sb.append("GNSS 回调未收到数据\n")
        }
        val lm = locationManager
        if (lm != null) {
            try {
                val isGnssEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                sb.append("GPS 开关: ").append(if (isGnssEnabled) "开" else "关").append("\n")
            } catch (_: Throwable) {
            }
        }
        if (sb.isEmpty()) sb.append("无 GNSS 数据")
        return sb.toString().trim()
    }

    @android.annotation.SuppressLint("MissingPermission", "NewApi")
    private fun buildSimText(): String {
        val tm = telephonyManager ?: return "TelephonyManager 不可用"
        val sb = StringBuilder()
        // 读取所有活跃卡槽（SubscriptionManager），逐个展示 SIM 身份/信号
        val subs: List<SubscriptionInfo> = try {
            val sm = requireContext().getSystemService(SubscriptionManager::class.java)
            sm.activeSubscriptionInfoList ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }
        if (subs.isEmpty()) {
            sb.append("无活跃订阅（无卡或权限不足）\n")
        } else {
            for (sub in subs) {
                val slotIdx = try { sub.simSlotIndex } catch (t: Throwable) { -1 }
                val subId = try { sub.subscriptionId } catch (t: Throwable) { -1 }
                sb.append("== 卡槽 ").append(slotIdx).append(" (subId=").append(subId).append(") ==\n")
                try {
                    val subTm = tm.createForSubscriptionId(subId)
                    sb.append("国家码: ").append(runCatching { subTm.simCountryIso }.getOrDefault("")).append("\n")
                    sb.append("运营商: ").append(runCatching { subTm.simOperatorName }.getOrDefault("")).append("\n")
                    sb.append("网络运营商: ").append(runCatching { subTm.networkOperatorName }.getOrDefault("")).append("\n")
                    sb.append("SIM 运营商代码: ").append(runCatching { subTm.simOperator }.getOrDefault("")).append("\n")
                    sb.append("网络代码: ").append(runCatching { subTm.networkOperator }.getOrDefault("")).append("\n")
                    sb.append("IMSI: ").append(runCatching { subTm.subscriberId }.getOrDefault("")).append("\n")
                    sb.append("ICCID: ").append(runCatching { subTm.simSerialNumber }.getOrDefault("")).append("\n")
                    sb.append("号码: ").append(runCatching { subTm.line1Number }.getOrDefault("")).append("\n")
                } catch (t: Throwable) {
                    sb.append("卡槽读取失败: ").append(t.message).append("\n")
                }
            }
        }
        sb.append("状态: ").append(runCatching { tm.simState }.getOrDefault(-1)).append("\n")
        try {
            val ss = tm.signalStrength
            if (ss != null) {
                sb.append("信号 Lv:").append(runCatching { ss.level }.getOrDefault(-1))
                sb.append(" GSM:").append(runCatching { ss.gsmSignalStrength }.getOrDefault(Int.MIN_VALUE))
                if (Build.VERSION.SDK_INT >= 28) {
                    val lte = ss.getCellSignalStrengths(android.telephony.CellSignalStrengthLte::class.java)
                    if (lte.isNotEmpty()) sb.append(" LTE rsrp:").append(lte[0].dbm)
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    val nr = ss.getCellSignalStrengths(android.telephony.CellSignalStrengthNr::class.java)
                    if (nr.isNotEmpty()) sb.append(" NR rsrp:").append(nr[0].dbm)
                }
                sb.append("\n")
            }
        } catch (_: Throwable) {
        }
        if (sb.isEmpty()) sb.append("无 SIM 数据（无卡或权限不足）")
        return sb.toString().trim()
    }

    /** SIM 判定：对配置中每个设置了虚拟身份的卡槽，在其对应卡槽分段内比对 mcc/mnc/运营商/IMSI/ICCID。 */
    private fun judgeSim(): Verdict {
        val data = envData("sim") ?: return Verdict.NOT_ENABLED
        val slots = data.optJSONArray("slots") ?: return Verdict.FAIL
        if (slots.length() == 0) return Verdict.FAIL
        if (lastSimText.isBlank() || lastSimText.contains("无 SIM 数据")) return Verdict.FAIL
        val segments = splitSimSegments(lastSimText)
        var anyConfigured = false
        for (i in 0 until slots.length()) {
            val s = slots.optJSONObject(i) ?: continue
            val slotIndex = s.optInt("slotIndex", -1)
            val subId = s.optInt("subId", -1)
            val segText = findSimSegment(segments, slotIndex, subId) ?: continue
            var hit = 0
            var total = 0
            val mcc = s.optString("mcc", "")
            if (mcc.isNotEmpty()) {
                total++
                if (segText.contains(mcc)) hit++
            }
            val mnc = s.optString("mnc", "")
            if (mnc.isNotEmpty()) {
                total++
                if (segText.contains(mnc)) hit++
            }
            val operator = s.optString("simOperatorName", "").ifEmpty { s.optString("carrier", "") }
            if (operator.isNotEmpty()) {
                total++
                if (segText.contains(operator)) hit++
            }
            val imsi = s.optString("subscriberId", "")
            if (imsi.isNotEmpty()) {
                total++
                if (segText.contains(imsi)) hit++
            }
            val iccid = s.optString("simSerialNumber", "")
            if (iccid.isNotEmpty()) {
                total++
                if (segText.contains(iccid)) hit++
            }
            if (total == 0) continue
            anyConfigured = true
            // 至少 2 项命中视为生效（运营商名称可能被 ROM 截断）
            if (total >= 2 && hit >= 2) return Verdict.PASS
            if (total == 1 && hit == 1) return Verdict.PASS
        }
        return if (anyConfigured) Verdict.FAIL else Verdict.NOT_ENABLED
    }

    /** 按 "== 卡槽 N (subId=Y) ==" 分隔符拆分 SIM 文本段。 */
    private fun splitSimSegments(text: String): List<String> {
        val segments = mutableListOf<String>()
        val lines = text.lines()
        var current = StringBuilder()
        for (line in lines) {
            if (line.startsWith("== 卡槽")) {
                if (current.isNotEmpty()) segments.add(current.toString())
                current = StringBuilder()
            }
            if (current.isNotEmpty() || line.startsWith("== 卡槽")) current.append(line).append('\n')
        }
        if (current.isNotEmpty()) segments.add(current.toString())
        return segments
    }

    /** 找到匹配 slotIndex 或 subId 的卡槽分段。 */
    private fun findSimSegment(segments: List<String>, slotIndex: Int, subId: Int): String? {
        if (segments.isEmpty()) return null
        for (seg in segments) {
            val head = seg.lineSequence().firstOrNull() ?: continue
            val hasSlot = slotIndex >= 0 && head.contains("卡槽 $slotIndex")
            val hasSub = subId >= 0 && head.contains("subId=$subId")
            if (hasSlot || hasSub) return seg
        }
        if (slotIndex == 0 && segments.isNotEmpty()) return segments[0]
        return null
    }

    @android.annotation.SuppressLint("MissingPermission")
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
            for (s in listOfNotNull(accelSensor, gyroSensor, magSensor, lightSensor, proxSensor)) {
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
                locationManager?.unregisterGnssStatusCallback(gnssListener)
            }
        } catch (_: Throwable) {
        }
        envTestRunningState = false
        ZLog.i(TAG_SCOPE, "env test stopped")
    }

    private fun loadAmapConfig() {
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        amapKey = prefs.getString(KEY_AMAP_KEY, "") ?: ""
        amapSecurity = prefs.getString(KEY_AMAP_SECURITY, "") ?: ""
        privacyAgreed = AmapPrivacyManager.isAgreed(requireContext())
    }

    // ---------- 桌面图标隐藏 ----------

    /** 初始化“隐藏桌面图标”开关：按持久化状态同步 alias。 */
    private fun initLauncherHideToggle() {
        val prefs = requireContext().getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
        val hidden = prefs.getBoolean(KEY_LAUNCHER_HIDDEN, false)
        launcherHidden = hidden
        // 确保 alias 与持久化状态一致（升级/异常后自愈）
        applyLauncherAlias(hidden, silent = true)
    }

    private fun applyLauncherAlias(hidden: Boolean, silent: Boolean) {
        val context = requireContext()
        val alias = android.content.ComponentName(context.packageName, "${context.packageName}.Launcher")
        try {
            context.packageManager.setComponentEnabledSetting(
                alias,
                if (hidden) {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                },
                PackageManager.DONT_KILL_APP
            )
            context.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LAUNCHER_HIDDEN, hidden)
                .apply()
            ZLog.i(TAG_SCOPE, "launcher alias ${if (hidden) "hidden" else "shown"}")
            if (!silent && isAdded) {
                Toast.makeText(
                    context,
                    if (hidden) R.string.settings_launcher_toast else R.string.settings_launcher_show_toast,
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "launcher alias set failed", t)
        }
    }

    private fun saveAmapConfig() {
        val key = amapKey.trim()
        if (key.isEmpty()) {
            Toast.makeText(requireContext(), R.string.settings_amap_key_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (!privacyAgreed) {
            Toast.makeText(requireContext(), R.string.settings_amap_privacy_required, Toast.LENGTH_LONG).show()
            return
        }
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_AMAP_KEY, key)
            .putString(KEY_AMAP_SECURITY, amapSecurity.trim())
            .apply()
        ZLog.i(TAG_SCOPE, "amap config saved")
        Toast.makeText(requireContext(), R.string.settings_amap_saved, Toast.LENGTH_SHORT).show()
    }

    private fun setWallpaperBackground(enabled: Boolean) {
        if (enabled) {
            // ColorOS 的 WallpaperManager 只检查 READ_EXTERNAL_STORAGE
            // （targetSdk=32 时可授予）；READ_MEDIA_IMAGES 对壁纸读取无效
            val permission = Manifest.permission.READ_EXTERNAL_STORAGE
            val granted = ContextCompat.checkSelfPermission(requireContext(), permission) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                wallpaperPermissionLauncher.launch(permission)
                return
            }
        }
        AppBackground.setUseWallpaper(requireContext(), enabled)
    }

    private val wallpaperPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                AppBackground.setUseWallpaper(requireContext(), true)
            } else {
                Toast.makeText(
                    requireContext(),
                    R.string.settings_appearance_permission_denied,
                    Toast.LENGTH_SHORT
                ).show()
            }
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
