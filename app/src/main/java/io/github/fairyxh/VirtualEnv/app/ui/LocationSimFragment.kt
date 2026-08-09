package io.github.fairyxh.VirtualEnv.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.app.ApiClient
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.util.concurrent.Executors

/**
 * 位置模拟页：单点虚拟定位（经 Backend API 设置）。
 */
class LocationSimFragment : Fragment() {

    companion object {
        private const val TAG_SCOPE = "UI"
    }

    private lateinit var enableSwitch: Switch
    private lateinit var latitudeInput: EditText
    private lateinit var longitudeInput: EditText
    private lateinit var applyButton: Button
    private lateinit var statusText: TextView

    private val executor = Executors.newSingleThreadExecutor()

    /** 防止状态回填触发 listener 回环。 */
    private var updatingFromBackend = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_location, container, false)
        enableSwitch = root.findViewById(R.id.enableSwitch)
        latitudeInput = root.findViewById(R.id.latitudeInput)
        longitudeInput = root.findViewById(R.id.longitudeInput)
        applyButton = root.findViewById(R.id.applyButton)
        statusText = root.findViewById(R.id.statusText)

        enableSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingFromBackend) return@setOnCheckedChangeListener
            executor.execute {
                val result = ApiClient.setLocationEnabled(checked)
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                    refreshStatus()
                }
            }
        }

        applyButton.setOnClickListener {
            val lat = latitudeInput.text.toString().toDoubleOrNull()
            val lon = longitudeInput.text.toString().toDoubleOrNull()
            if (lat == null || lon == null) {
                Toast.makeText(requireContext(), R.string.location_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            executor.execute {
                val result = ApiClient.setLocation(lat, lon, 0f, 0f)
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                    refreshStatus()
                }
            }
        }

        refreshStatus()
        return root
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        executor.execute {
            val result = ApiClient.getLocationStatus()
            requireActivity().runOnUiThread {
                val data = result.data
                if (data != null) {
                    val enabled = data.optBoolean("enabled", false)
                    updatingFromBackend = true
                    enableSwitch.isChecked = enabled
                    updatingFromBackend = false
                    latitudeInput.setText(data.optDouble("latitude", 0.0).toString())
                    longitudeInput.setText(data.optDouble("longitude", 0.0).toString())
                    statusText.text = getString(
                        R.string.location_status_value,
                        if (enabled) getString(R.string.location_enabled) else getString(R.string.location_disabled),
                        data.optDouble("latitude", 0.0),
                        data.optDouble("longitude", 0.0)
                    )
                } else {
                    statusText.text = getString(R.string.location_status_offline)
                }
            }
        }
    }

    override fun onDestroyView() {
        executor.shutdown()
        super.onDestroyView()
    }
}
