package io.github.fairyxh.VirtualEnv.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import io.github.fairyxh.VirtualEnv.R

/**
 * 环境模拟页（Phase 3 骨架）。
 *
 * 基站 / WiFi / GNSS 模拟入口卡片。
 * 当前阶段仅展示状态占位；具体模拟逻辑由后续 Phase 实现。
 */
class EnvFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_env, container, false)

        root.findViewById<View>(R.id.cellCard).setOnClickListener {
            showStatus(R.id.cellStatus, R.string.env_cell_coming)
        }
        root.findViewById<View>(R.id.wifiCard).setOnClickListener {
            showStatus(R.id.wifiStatus, R.string.env_wifi_coming)
        }
        root.findViewById<View>(R.id.gnssCard).setOnClickListener {
            showStatus(R.id.gnssStatus, R.string.env_gnss_coming)
        }
        return root
    }

    private fun showStatus(viewId: Int, stringRes: Int) {
        view?.findViewById<TextView>(viewId)?.text = getString(stringRes)
    }
}
