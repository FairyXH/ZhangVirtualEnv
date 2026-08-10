package io.github.fairyxh.VirtualEnv.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
 * 环境模拟入口页：基站 / WiFi / GNSS 三卡片 → 子页面（EnvDetailActivity）。
 *
 * 子页面支持多基站 / 多 WiFi / GNSS 详细设置，以及多配置保存与一键切换。
 */
class EnvFragment : Fragment() {

    companion object {
        private const val TAG_SCOPE = "UI"

        private const val TYPE_CELL = "cell"
        private const val TYPE_WIFI = "wifi"
        private const val TYPE_GNSS = "gnss"
    }

    private lateinit var cellStatus: TextView
    private lateinit var wifiStatus: TextView
    private lateinit var gnssStatus: TextView
    private lateinit var savedEnvEmpty: TextView
    private lateinit var savedEnvList: LinearLayout

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_env, container, false)
        cellStatus = root.findViewById(R.id.cellStatus)
        wifiStatus = root.findViewById(R.id.wifiStatus)
        gnssStatus = root.findViewById(R.id.gnssStatus)
        savedEnvEmpty = root.findViewById(R.id.savedEnvEmpty)
        savedEnvList = root.findViewById(R.id.savedEnvList)

        root.findViewById<View>(R.id.cellCard).setOnClickListener {
            EnvDetailActivity.start(requireContext(), TYPE_CELL)
        }
        root.findViewById<View>(R.id.wifiCard).setOnClickListener {
            EnvDetailActivity.start(requireContext(), TYPE_WIFI)
        }
        root.findViewById<View>(R.id.gnssCard).setOnClickListener {
            EnvDetailActivity.start(requireContext(), TYPE_GNSS)
        }

        refreshSavedEnv()
        refreshStatuses()
        return root
    }

    override fun onResume() {
        super.onResume()
        refreshSavedEnv()
        refreshStatuses()
    }

    override fun onDestroyView() {
        executor.shutdown()
        super.onDestroyView()
    }

    private fun refreshStatuses() {
        listOf(TYPE_CELL to cellStatus, TYPE_WIFI to wifiStatus, TYPE_GNSS to gnssStatus).forEach { (type, view) ->
            executor.execute {
                val result = ApiClient.getEnvStatus(type)
                requireActivity().runOnUiThread {
                    val data = result.data
                    val enabled = data != null && data.optBoolean("enabled", false)
                    view.text = getString(
                        if (enabled) R.string.env_status_active else R.string.env_status_inactive
                    )
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
        val label = typeLabel(item.optString("type", ""))
        executor.execute {
            val result = ApiClient.useEnvSnapshot(id)
            requireActivity().runOnUiThread {
                if (result.code == ApiResult.CODE_OK) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.env_use_applied, label),
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshSavedEnv()
                    refreshStatuses()
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
