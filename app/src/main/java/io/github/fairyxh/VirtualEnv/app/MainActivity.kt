package io.github.fairyxh.VirtualEnv.app

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.util.concurrent.Executors

/**
 * 控制端主界面（Phase 1）。
 *
 * 纯 UI：展示 Backend 状态、配置单点坐标、开关虚拟定位。
 * 所有操作经 [ApiClient] 调用 Backend，不直接访问配置/数据库。
 */
class MainActivity : Activity() {

    private lateinit var backendStatus: TextView
    private lateinit var profileInfo: TextView
    private lateinit var enableSwitch: Switch
    private lateinit var latitudeInput: EditText
    private lateinit var longitudeInput: EditText
    private lateinit var applyButton: Button
    private lateinit var statusText: TextView

    private val executor = Executors.newSingleThreadExecutor()

    /** 防止状态回填触发 listener 回环。 */
    private var updatingFromBackend = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        backendStatus = findViewById(R.id.backendStatus)
        profileInfo = findViewById(R.id.profileInfo)
        enableSwitch = findViewById(R.id.enableSwitch)
        latitudeInput = findViewById(R.id.latitudeInput)
        longitudeInput = findViewById(R.id.longitudeInput)
        applyButton = findViewById(R.id.applyButton)
        statusText = findViewById(R.id.statusText)

        enableSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingFromBackend) return@setOnCheckedChangeListener
            executor.execute {
                val result = ApiClient.setLocationEnabled(checked)
                runOnUiThread {
                    Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }

        applyButton.setOnClickListener {
            val lat = latitudeInput.text.toString().toDoubleOrNull()
            val lon = longitudeInput.text.toString().toDoubleOrNull()
            if (lat == null || lon == null) {
                Toast.makeText(this, "请输入有效的经纬度", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            executor.execute {
                val result = ApiClient.setLocation(lat, lon, 0f, 0f)
                runOnUiThread {
                    Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                    refreshStatus()
                }
            }
        }

        refreshStatus()
    }

    private fun refreshStatus() {
        executor.execute {
            val reachable = ApiClient.ping()
            runOnUiThread {
                backendStatus.text = if (reachable) "Backend: 已连接" else "Backend: 未连接"
            }
            if (!reachable) return@execute
            val status = ApiClient.getLocationStatus()
            runOnUiThread {
                val data = status.data
                if (data != null) {
                    val enabled = data.optBoolean("enabled", false)
                    updatingFromBackend = true
                    enableSwitch.isChecked = enabled
                    updatingFromBackend = false
                    latitudeInput.setText(data.optDouble("latitude", 0.0).toString())
                    longitudeInput.setText(data.optDouble("longitude", 0.0).toString())
                    statusText.text = "状态: ${if (enabled) "已启用" else "未启用"} " +
                        "位置: ${data.optDouble("latitude", 0.0)}, ${data.optDouble("longitude", 0.0)}"
                }
            }
            val info = ApiClient.getSystemInfo()
            runOnUiThread {
                profileInfo.text = "API: ${info.data?.optString("phase", "-")}"
            }
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
