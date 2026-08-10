package io.github.fairyxh.VirtualEnv.app.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 设置页：应用标识（包名 / SHA1）复制 + 高德地图 Key 配置 + 隐私合规同意 + BLE 扫描测试（不 Suspend）。
 */
class SettingsFragment : Fragment() {

    companion object {
        private const val TAG_SCOPE = "UI"
        private const val PREFS = "amap_config"
        private const val KEY_AMAP_KEY = "amap_key"
        private const val KEY_AMAP_SECURITY = "amap_security_key"
        private const val BLE_TEST_SCAN_MS = 8_000L

        private const val AMAP_PRIVACY_URL = "https://lbs.amap.com/api/android-sdk/guide/create-project/dev-attention"
    }

    private lateinit var packageValue: TextView
    private lateinit var sha1Value: TextView
    private lateinit var amapKeyInput: EditText
    private lateinit var amapSecurityInput: EditText
    private lateinit var privacyAgreeCheck: CheckBox

    private lateinit var bleTestButton: Button
    private lateinit var bleTestResult: TextView
    private val bleTestBusy = AtomicBoolean(false)
    private var bleScanner: BluetoothLeScanner? = null
    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            // 虚拟设备无 BluetoothDevice 系统缓存名：优先读 ScanRecord 中的本地名
            val name = result.scanRecord?.deviceName ?: device.name ?: "(no name)"
            val line = "${name}  ${device.address}  RSSI=${result.rssi}"
            ZLog.i(TAG_SCOPE, "ble test result: $line")
            requireActivity().runOnUiThread {
                bleTestResult.append(line + "\n")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            ZLog.w(TAG_SCOPE, "ble test scan failed errorCode=$errorCode")
            requireActivity().runOnUiThread {
                bleTestResult.text = getString(R.string.settings_ble_test_none) + "\nerrorCode=$errorCode"
                bleTestButton.isEnabled = true
            }
        }
    }

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        startBleTestScan()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_settings, container, false)
        packageValue = root.findViewById(R.id.packageValue)
        sha1Value = root.findViewById(R.id.sha1Value)
        amapKeyInput = root.findViewById(R.id.amapKeyInput)
        amapSecurityInput = root.findViewById(R.id.amapSecurityInput)
        privacyAgreeCheck = root.findViewById(R.id.privacyAgreeCheck)
        bleTestButton = root.findViewById(R.id.bleTestButton)
        bleTestResult = root.findViewById(R.id.bleTestResult)

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
        bleTestButton.setOnClickListener { onBleTestClicked() }

        loadAmapConfig()
        return root
    }

    // ---------- BLE 扫描测试（不 Suspend） ----------

    private fun onBleTestClicked() {
        if (!bleTestBusy.compareAndSet(false, true)) return
        val missing = bleRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            blePermissionLauncher.launch(missing.toTypedArray())
        } else {
            startBleTestScan()
        }
    }

    private fun bleRequiredPermissions(): List<String> {
        val perms = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return perms
    }

    private fun startBleTestScan() {
        if (!isAdded) {
            bleTestBusy.set(false)
            return
        }
        if (bleRequiredPermissions().any {
                ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
            }
        ) {
            bleTestResult.text = getString(R.string.settings_ble_test_perm)
            bleTestButton.isEnabled = true
            bleTestBusy.set(false)
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            bleTestResult.text = "蓝牙未开启"
            bleTestButton.isEnabled = true
            bleTestBusy.set(false)
            return
        }
        bleScanner = adapter.bluetoothLeScanner
        val scanner = bleScanner
        if (scanner == null) {
            bleTestResult.text = "BluetoothLeScanner 不可用"
            bleTestButton.isEnabled = true
            bleTestBusy.set(false)
            return
        }
        bleTestResult.text = getString(R.string.settings_ble_test_running)
        bleTestButton.isEnabled = false
        ZLog.i(TAG_SCOPE, "ble test startScan (no suspend)")
        try {
            scanner.startScan(bleScanCallback)
        } catch (t: Throwable) {
            bleTestResult.text = "startScan 异常: ${t.message}"
            bleTestButton.isEnabled = true
            bleTestBusy.set(false)
            ZLog.w(TAG_SCOPE, "ble test startScan failed", t)
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                scanner.stopScan(bleScanCallback)
            } catch (_: Throwable) {
            }
            bleTestButton.isEnabled = true
            bleTestBusy.set(false)
            if (bleTestResult.text.toString() == getString(R.string.settings_ble_test_running)) {
                bleTestResult.text = getString(R.string.settings_ble_test_none)
            }
            ZLog.i(TAG_SCOPE, "ble test scan stopped")
        }, BLE_TEST_SCAN_MS)
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
