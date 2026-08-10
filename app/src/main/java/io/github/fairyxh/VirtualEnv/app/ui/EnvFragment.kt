package io.github.fairyxh.VirtualEnv.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.app.ApiClient
import io.github.fairyxh.VirtualEnv.core.model.ApiResult
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 环境模拟页：基站 / WiFi / GNSS 三卡片。
 *
 * 每个卡片可填写名称/备注与数据字段并保存（Backend env_snapshot），
 * 已保存环境支持删除；一键使用把快照加载到对应模拟引擎（wifi/cell/gnss 已接入，
 * ble/sensor 通过 /api/<type>/set 直接设置）。
 */
class EnvFragment : Fragment() {

    companion object {
        private const val TAG_SCOPE = "UI"

        private const val TYPE_CELL = "cell"
        private const val TYPE_WIFI = "wifi"
        private const val TYPE_GNSS = "gnss"
    }

    private lateinit var savedEnvEmpty: TextView
    private lateinit var savedEnvList: LinearLayout

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_env, container, false)
        savedEnvEmpty = root.findViewById(R.id.savedEnvEmpty)
        savedEnvList = root.findViewById(R.id.savedEnvList)

        root.findViewById<View>(R.id.cellCard).setOnClickListener { showCellDialog() }
        root.findViewById<View>(R.id.wifiCard).setOnClickListener { showWifiDialog() }
        root.findViewById<View>(R.id.gnssCard).setOnClickListener { showGnssDialog() }

        refreshSavedEnv()
        return root
    }

    override fun onResume() {
        super.onResume()
        refreshSavedEnv()
    }

    override fun onDestroyView() {
        executor.shutdown()
        super.onDestroyView()
    }

    // ---------- 编辑对话框 ----------

    private fun showCellDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_env_cell, null)
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.env_cell_title)
            .setView(view)
            .setPositiveButton(R.string.env_save) { _, _ -> saveCell(view) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showWifiDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_env_wifi, null)
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.env_wifi_title)
            .setView(view)
            .setPositiveButton(R.string.env_save) { _, _ -> saveWifi(view) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showGnssDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_env_gnss, null)
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.env_gnss_title)
            .setView(view)
            .setPositiveButton(R.string.env_save) { _, _ -> saveGnss(view) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveCell(view: View) {
        val name = view.findViewById<EditText>(R.id.cellNameInput).text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), R.string.location_point_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        val remark = view.findViewById<EditText>(R.id.cellRemarkInput).text.toString().trim()
        val data = JSONObject().apply {
            val type = view.findViewById<EditText>(R.id.cellTypeInput).text.toString().trim()
            put("type", if (type.isEmpty()) "LTE" else type)
            put("mcc", view.findViewById<EditText>(R.id.cellMccInput).text.toString().toIntOrNull() ?: -1)
            put("mnc", view.findViewById<EditText>(R.id.cellMncInput).text.toString().toIntOrNull() ?: -1)
            put("tac", view.findViewById<EditText>(R.id.cellTacInput).text.toString().toIntOrNull() ?: -1)
            put("ci", view.findViewById<EditText>(R.id.cellCiInput).text.toString().toLongOrNull() ?: -1L)
            put("pci", view.findViewById<EditText>(R.id.cellPciInput).text.toString().toIntOrNull() ?: -1)
            put("rsrp", view.findViewById<EditText>(R.id.cellRsrpInput).text.toString().toIntOrNull() ?: -1)
        }
        saveEnv(name, remark, TYPE_CELL, data)
    }

    private fun saveWifi(view: View) {
        val name = view.findViewById<EditText>(R.id.wifiNameInput).text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), R.string.location_point_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        val remark = view.findViewById<EditText>(R.id.wifiRemarkInput).text.toString().trim()
        val data = JSONObject().apply {
            put("ssid", view.findViewById<EditText>(R.id.wifiSsidInput).text.toString().trim())
            put("bssid", view.findViewById<EditText>(R.id.wifiBssidInput).text.toString().trim())
            put("rssi", view.findViewById<EditText>(R.id.wifiRssiInput).text.toString().toIntOrNull() ?: -1)
            put("frequency", view.findViewById<EditText>(R.id.wifiFrequencyInput).text.toString().toIntOrNull() ?: -1)
        }
        saveEnv(name, remark, TYPE_WIFI, data)
    }

    private fun saveGnss(view: View) {
        val name = view.findViewById<EditText>(R.id.gnssNameInput).text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), R.string.location_point_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        val remark = view.findViewById<EditText>(R.id.gnssRemarkInput).text.toString().trim()
        val data = JSONObject().apply {
            put("satelliteCount", view.findViewById<EditText>(R.id.gnssCountInput).text.toString().toIntOrNull() ?: -1)
            put("usedInFix", view.findViewById<EditText>(R.id.gnssUsedInput).text.toString().toIntOrNull() ?: -1)
            put("cn0", view.findViewById<EditText>(R.id.gnssCn0Input).text.toString().toDoubleOrNull() ?: -1.0)
        }
        saveEnv(name, remark, TYPE_GNSS, data)
    }

    private fun saveEnv(name: String, remark: String, type: String, data: JSONObject) {
        executor.execute {
            val result = ApiClient.createEnvSnapshot(name, remark, type, data)
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                if (result.code == ApiResult.CODE_OK) {
                    refreshSavedEnv()
                }
            }
        }
    }

    // ---------- 已保存环境列表 ----------

    private fun refreshSavedEnv() {
        executor.execute {
            val result = ApiClient.listEnvSnapshots()
            requireActivity().runOnUiThread {
                renderSavedEnv(result)
            }
        }
    }

    private fun renderSavedEnv(result: ApiResult) {
        savedEnvList.removeAllViews()
        val snapshots = result.data?.optJSONArray("snapshots") ?: return
        val envs = mutableListOf<JSONObject>()
        for (i in 0 until snapshots.length()) {
            val item = snapshots.optJSONObject(i) ?: continue
            val type = item.optString("type", "")
            if (type == TYPE_CELL || type == TYPE_WIFI || type == TYPE_GNSS) envs.add(item)
        }
        savedEnvEmpty.visibility = if (envs.isEmpty()) View.VISIBLE else View.GONE
        if (envs.isEmpty()) return

        envs.forEach { item ->
            val row = layoutInflater.inflate(R.layout.item_saved_env, savedEnvList, false)
            row.findViewById<TextView>(R.id.envType).text = typeLabel(item.optString("type", ""))
            row.findViewById<TextView>(R.id.envName).text = item.optString("name", "")
            val remark = item.optString("remark", "")
            val remarkView = row.findViewById<TextView>(R.id.envRemark)
            if (remark.isBlank()) {
                remarkView.visibility = View.GONE
            } else {
                remarkView.text = getString(R.string.location_point_remark_format, remark)
            }
            row.findViewById<TextView>(R.id.envMeta).text = formatTime(item.optLong("createTime", 0L))
            row.findViewById<Button>(R.id.useButton).setOnClickListener {
                useEnv(item)
            }
            row.findViewById<Button>(R.id.deleteButton).setOnClickListener {
                deleteEnv(item.optLong("id"))
            }
            savedEnvList.addView(row)
        }
    }

    /** 一键使用：把环境快照加载到对应模拟引擎（Hook 层随即生效）。 */
    private fun useEnv(item: JSONObject) {
        val id = item.optLong("id")
        val typeLabel = typeLabel(item.optString("type", ""))
        executor.execute {
            val result = ApiClient.useEnvSnapshot(id)
            requireActivity().runOnUiThread {
                if (result.code == ApiResult.CODE_OK) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.env_use_applied, typeLabel),
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshSavedEnv()
                } else {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun typeLabel(type: String): String {
        return when (type) {
            TYPE_CELL -> getString(R.string.env_type_cell)
            TYPE_WIFI -> getString(R.string.env_type_wifi)
            TYPE_GNSS -> getString(R.string.env_type_gnss)
            else -> type
        }
    }

    private fun deleteEnv(id: Long) {
        executor.execute {
            val result = ApiClient.deleteEnvSnapshot(id)
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                if (result.code == ApiResult.CODE_OK) {
                    refreshSavedEnv()
                }
            }
        }
    }

    private fun formatTime(millis: Long): String {
        if (millis <= 0) return ""
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(millis))
    }
}
