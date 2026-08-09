package io.github.fairyxh.VirtualEnv.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
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

        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.READ_PHONE_STATE,
        )
    }

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var collectButton: Button
    private lateinit var collectResult: TextView

    private val executor = Executors.newSingleThreadExecutor()
    private var collector: EnvironmentCollector? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_home, container, false)
        statusDot = root.findViewById(R.id.statusDot)
        statusText = root.findViewById(R.id.statusText)
        statusDetail = root.findViewById(R.id.statusDetail)
        collectButton = root.findViewById(R.id.collectButton)
        collectResult = root.findViewById(R.id.collectResult)
        collector = EnvironmentCollector(requireContext())

        collectButton.setOnClickListener { startCollect() }
        refreshBackendStatus()
        return root
    }

    override fun onResume() {
        super.onResume()
        refreshBackendStatus()
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
                requireActivity().runOnUiThread {
                    collectButton.isEnabled = true
                    collectResult.text = summarize(result)
                    collectResult.visibility = View.VISIBLE
                    ZLog.i(TAG_SCOPE, "collect done: ${result.length()}")
                }
            }
        }
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
        sb.append("蓝牙: ").append(bt?.optJSONArray("bonded")?.length() ?: 0).append(" 个已配对\n")
        sb.append("GNSS: ").append(gnss?.optString("available", "false")).append(
            if (gnss?.optInt("satelliteCount", 0) ?: 0 > 0) {
                " (${gnss.optInt("satelliteCount")} 颗卫星)"
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
