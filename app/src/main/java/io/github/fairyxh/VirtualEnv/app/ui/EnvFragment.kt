package io.github.fairyxh.VirtualEnv.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.app.ApiClient
import java.util.concurrent.Executors

/**
 * 环境模拟入口页：基站 / WiFi / GNSS 三卡片 → 子页面（EnvDetailActivity）。
 *
 * 已保存环境模块已删除：采集保存统一由主页「已保存采集」管理（含轨道化拆分）。
 */
class EnvFragment : Fragment() {

    companion object {
        private const val TYPE_CELL = "cell"
        private const val TYPE_WIFI = "wifi"
        private const val TYPE_GNSS = "gnss"
    }

    private lateinit var cellStatus: TextView
    private lateinit var wifiStatus: TextView
    private lateinit var gnssStatus: TextView
    private lateinit var cellSwitch: android.widget.Switch
    private lateinit var wifiSwitch: android.widget.Switch
    private lateinit var gnssSwitch: android.widget.Switch
    private var updatingSwitch = false

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_env, container, false)
        cellStatus = root.findViewById(R.id.cellStatus)
        wifiStatus = root.findViewById(R.id.wifiStatus)
        gnssStatus = root.findViewById(R.id.gnssStatus)
        cellSwitch = root.findViewById(R.id.cellSwitch)
        wifiSwitch = root.findViewById(R.id.wifiSwitch)
        gnssSwitch = root.findViewById(R.id.gnssSwitch)

        root.findViewById<View>(R.id.cellCard).setOnClickListener {
            EnvDetailActivity.start(requireContext(), TYPE_CELL)
        }
        root.findViewById<View>(R.id.wifiCard).setOnClickListener {
            EnvDetailActivity.start(requireContext(), TYPE_WIFI)
        }
        root.findViewById<View>(R.id.gnssCard).setOnClickListener {
            EnvDetailActivity.start(requireContext(), TYPE_GNSS)
        }

        cellSwitch.setOnCheckedChangeListener { _, checked -> toggleEnv(TYPE_CELL, checked) }
        wifiSwitch.setOnCheckedChangeListener { _, checked -> toggleEnv(TYPE_WIFI, checked) }
        gnssSwitch.setOnCheckedChangeListener { _, checked -> toggleEnv(TYPE_GNSS, checked) }

        refreshStatuses()
        return root
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
    }

    override fun onDestroyView() {
        executor.shutdown()
        super.onDestroyView()
    }

    /** 快捷开关：关闭时 Hook 放行真实数据（数据保留），开启时恢复。 */
    private fun toggleEnv(type: String, enabled: Boolean) {
        if (updatingSwitch) return
        executor.execute {
            val result = ApiClient.setEnvEnabled(type, enabled)
            requireActivity().runOnUiThread {
                if (result.code != io.github.fairyxh.VirtualEnv.core.model.ApiResult.CODE_OK) {
                    android.widget.Toast.makeText(requireContext(), result.message, android.widget.Toast.LENGTH_SHORT).show()
                    refreshStatuses()
                }
            }
        }
    }

    private fun refreshStatuses() {
        listOf(
            TYPE_CELL to (cellStatus to cellSwitch),
            TYPE_WIFI to (wifiStatus to wifiSwitch),
            TYPE_GNSS to (gnssStatus to gnssSwitch)
        ).forEach { (type, pair) ->
            val (statusView, switchView) = pair
            executor.execute {
                val result = ApiClient.getEnvStatus(type)
                requireActivity().runOnUiThread {
                    val data = result.data
                    val enabled = data != null && data.optBoolean("enabled", false)
                    statusView.text = getString(
                        if (enabled) R.string.env_status_active else R.string.env_status_inactive
                    )
                    updatingSwitch = true
                    switchView.isChecked = enabled
                    updatingSwitch = false
                }
            }
        }
    }
}
