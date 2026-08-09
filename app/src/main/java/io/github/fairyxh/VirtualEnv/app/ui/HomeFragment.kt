package io.github.fairyxh.VirtualEnv.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.app.ApiClient
import io.github.fairyxh.VirtualEnv.app.collect.EnvironmentCollector
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 主页：模块激活状态 + 一键环境采集。
 */
class HomeFragment : Fragment() {

    companion object {
        private const val TAG_SCOPE = "UI"
        private const val REQ_PERMISSIONS = 1001

        private val REQUIRED_PERMISSIONS: Array<String>
            get() {
                val base = mutableListOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.READ_PHONE_STATE,
                )
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    base.add(Manifest.permission.BLUETOOTH_SCAN)
                    base.add(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    base.add(Manifest.permission.BLUETOOTH)
                    base.add(Manifest.permission.BLUETOOTH_ADMIN)
                }
                return base.toTypedArray()
            }
    }

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var collectButton: Button
    private lateinit var collectResult: TextView
    private lateinit var collectNameInput: android.widget.EditText
    private lateinit var collectRemarkInput: android.widget.EditText
    private lateinit var saveCollectButton: Button
    private lateinit var savedCollectEmpty: TextView
    private lateinit var savedCollectList: android.widget.LinearLayout

    private val executor = Executors.newSingleThreadExecutor()
    private var collector: EnvironmentCollector? = null
    private var lastCollectResult: JSONObject? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_home, container, false)
        statusDot = root.findViewById(R.id.statusDot)
        statusText = root.findViewById(R.id.statusText)
        statusDetail = root.findViewById(R.id.statusDetail)
        collectButton = root.findViewById(R.id.collectButton)
        collectResult = root.findViewById(R.id.collectResult)
        collectNameInput = root.findViewById(R.id.collectNameInput)
        collectRemarkInput = root.findViewById(R.id.collectRemarkInput)
        saveCollectButton = root.findViewById(R.id.saveCollectButton)
        savedCollectEmpty = root.findViewById(R.id.savedCollectEmpty)
        savedCollectList = root.findViewById(R.id.savedCollectList)
        collector = EnvironmentCollector(requireContext())

        collectButton.setOnClickListener { startCollect() }
        saveCollectButton.setOnClickListener { saveCollect() }
        refreshBackendStatus()
        refreshSavedCollects()
        return root
    }

    override fun onResume() {
        super.onResume()
        refreshBackendStatus()
        refreshSavedCollects()
    }

    private fun refreshBackendStatus() {
        executor.execute {
            val reachable = ApiClient.ping()
            val info = if (reachable) ApiClient.getSystemInfo() else null
            requireActivity().runOnUiThread {
                statusDot.isEnabled = reachable
                if (reachable) {
                    statusText.text = getString(R.string.home_status_ok)
                    val enabledText = if (ApiClient.getLocationStatus().data?.optBoolean("enabled", false) == true) {
                        getString(R.string.location_enabled)
                    } else {
                        getString(R.string.location_disabled)
                    }
                    statusDetail.text = getString(
                        R.string.home_status_detail,
                        info?.data?.optString("phase", "1") ?: "-",
                        enabledText
                    )
                } else {
                    statusText.text = getString(R.string.home_status_offline)
                    statusDetail.text = getString(R.string.home_status_offline_detail)
                }
            }
        }
    }

    private fun startCollect() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
            return
        }
        doCollect()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            doCollect()
        }
    }

    private fun doCollect() {
        collectButton.isEnabled = false
        collectResult.text = getString(R.string.home_collect_running)
        collectResult.visibility = View.VISIBLE
        executor.execute {
            collector?.collectAll { result ->
                lastCollectResult = result
                requireActivity().runOnUiThread {
                    collectButton.isEnabled = true
                    collectResult.text = summarize(result)
                    collectResult.visibility = View.VISIBLE
                    ZLog.i(TAG_SCOPE, "collect done: ${result.length()}")
                }
            }
        }
    }

    // ---------- 已保存采集 ----------

    private fun saveCollect() {
        val name = collectNameInput.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), R.string.home_collect_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        val result = lastCollectResult
        if (result == null) {
            Toast.makeText(requireContext(), R.string.home_collect_none, Toast.LENGTH_SHORT).show()
            return
        }
        val remark = collectRemarkInput.text.toString().trim()
        executor.execute {
            val apiResult = ApiClient.createEnvSnapshot(name, remark, "collect", result)
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), apiResult.message, Toast.LENGTH_SHORT).show()
                if (apiResult.code == io.github.fairyxh.VirtualEnv.core.model.ApiResult.CODE_OK) {
                    collectNameInput.text.clear()
                    collectRemarkInput.text.clear()
                    refreshSavedCollects()
                }
            }
        }
    }

    private fun refreshSavedCollects() {
        executor.execute {
            val result = ApiClient.listEnvSnapshots()
            requireActivity().runOnUiThread {
                renderSavedCollects(result)
            }
        }
    }

    private fun renderSavedCollects(result: io.github.fairyxh.VirtualEnv.core.model.ApiResult) {
        savedCollectList.removeAllViews()
        val snapshots = result.data?.optJSONArray("snapshots") ?: return
        val collects = mutableListOf<JSONObject>()
        for (i in 0 until snapshots.length()) {
            val item = snapshots.optJSONObject(i) ?: continue
            if (item.optString("type", "") == "collect") collects.add(item)
        }
        savedCollectEmpty.visibility = if (collects.isEmpty()) View.VISIBLE else View.GONE
        if (collects.isEmpty()) return

        collects.forEach { item ->
            val row = layoutInflater.inflate(R.layout.item_saved_collect, savedCollectList, false)
            row.findViewById<TextView>(R.id.collectName).text = item.optString("name", "")
            val remark = item.optString("remark", "")
            val remarkView = row.findViewById<TextView>(R.id.collectRemark)
            if (remark.isBlank()) {
                remarkView.visibility = View.GONE
            } else {
                remarkView.text = getString(R.string.location_point_remark_format, remark)
            }
            row.findViewById<TextView>(R.id.collectMeta).text = formatTime(item.optLong("createTime", 0L))
            row.findViewById<Button>(R.id.useButton).setOnClickListener {
                useCollect(item)
            }
            row.findViewById<Button>(R.id.deleteButton).setOnClickListener {
                deleteCollect(item.optLong("id"))
            }
            savedCollectList.addView(row)
        }
    }

    /** 一键使用采集包：整体加载到 WiFi / 基站 / BLE 模拟引擎。 */
    private fun useCollect(item: JSONObject) {
        val id = item.optLong("id")
        val name = item.optString("name", "")
        executor.execute {
            val result = ApiClient.useEnvSnapshot(id)
            requireActivity().runOnUiThread {
                if (result.code == io.github.fairyxh.VirtualEnv.core.model.ApiResult.CODE_OK) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.home_collect_applied, name),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun deleteCollect(id: Long) {
        executor.execute {
            val result = ApiClient.deleteEnvSnapshot(id)
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                if (result.code == io.github.fairyxh.VirtualEnv.core.model.ApiResult.CODE_OK) {
                    refreshSavedCollects()
                }
            }
        }
    }

    private fun formatTime(millis: Long): String {
        if (millis <= 0) return ""
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(millis))
    }

    private fun summarize(result: JSONObject): String {
        val loc = result.optJSONObject("location")
        val cell = result.optJSONObject("cell")
        val wifi = result.optJSONObject("wifi")
        val bt = result.optJSONObject("bluetooth")
        val gnss = result.optJSONObject("gnss")

        val sb = StringBuilder()
        sb.append("位置: ")
        if (loc != null && loc.length() > 0) {
            val first = loc.keys().next()
            val item = loc.optJSONObject(first)
            sb.append(item?.optString("latitude")).append(", ").append(item?.optString("longitude"))
        } else {
            sb.append("无")
        }
        sb.append("\n")
        sb.append("基站: ").append(cell?.optJSONArray("cells")?.length() ?: 0).append(" 个\n")
        sb.append("WiFi: ").append(wifi?.optJSONArray("networks")?.length() ?: 0).append(" 个\n")
        val bondedCount = bt?.optJSONArray("bonded")?.length() ?: 0
        val nearbyCount = bt?.optJSONArray("devices")?.length() ?: 0
        sb.append("蓝牙: 已配对 ").append(bondedCount).append(" 个 · 附近 ").append(nearbyCount).append(" 个\n")
        val gnssCount = gnss?.optInt("satelliteCount", 0) ?: 0
        sb.append("GNSS: ").append(gnss?.optString("available", "false")).append(
            if (gnssCount > 0) {
                " ($gnssCount 颗卫星)"
            } else {
                ""
            }
        )
        return sb.toString()
    }

    override fun onDestroyView() {
        executor.shutdown()
        super.onDestroyView()
    }
}
