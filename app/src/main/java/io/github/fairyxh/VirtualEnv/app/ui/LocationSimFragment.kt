package io.github.fairyxh.VirtualEnv.app.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.app.AmapPrivacyManager
import io.github.fairyxh.VirtualEnv.app.ApiClient
import io.github.fairyxh.VirtualEnv.core.model.ApiResult
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 位置模拟页：单点虚拟定位。
 *
 * 支持高德地图点选坐标、手动输入坐标、多个地点带备注保存，
 * 已保存地点一键使用（设置坐标并启用虚拟定位）。
 */
class LocationSimFragment : Fragment() {

    companion object {
        private const val TAG_SCOPE = "UI"
        private const val PREFS = "amap_config"
        private const val KEY_AMAP_KEY = "amap_key"

        private val DEFAULT_CENTER = LatLng(31.2304, 121.4737)
        private const val DEFAULT_ZOOM = 12f
    }

    private lateinit var enableSwitch: Switch
    private lateinit var latitudeInput: EditText
    private lateinit var longitudeInput: EditText
    private lateinit var applyButton: Button
    private lateinit var statusText: TextView
    private lateinit var pointNameInput: EditText
    private lateinit var pointRemarkInput: EditText
    private lateinit var savePointButton: Button
    private lateinit var savedPointsEmpty: TextView
    private lateinit var savedPointList: LinearLayout
    private lateinit var mapContainer: android.widget.FrameLayout
    private lateinit var privacyPrompt: View
    private lateinit var locateButton: Button

    private var mapView: MapView? = null
    private var amap: AMap? = null
    private var selectedMarker: Marker? = null

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
        pointNameInput = root.findViewById(R.id.pointNameInput)
        pointRemarkInput = root.findViewById(R.id.pointRemarkInput)
        savePointButton = root.findViewById(R.id.savePointButton)
        savedPointsEmpty = root.findViewById(R.id.savedPointsEmpty)
        savedPointList = root.findViewById(R.id.savedPointList)
        mapContainer = root.findViewById(R.id.mapContainer)
        privacyPrompt = root.findViewById(R.id.privacyPrompt)
        locateButton = root.findViewById(R.id.locateButton)

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
            applyPoint(lat, lon)
        }

        savePointButton.setOnClickListener { saveCurrentPoint() }
        locateButton.setOnClickListener { locateCurrentPosition() }

        initMapSafely(savedInstanceState)
        refreshSavedPoints()
        refreshStatus()
        return root
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
        refreshStatus()
        refreshSavedPoints()
    }

    override fun onPause() {
        super.onPause()
        mapView?.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mapView?.onDestroy()
        mapView = null
        executor.shutdown()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView?.onSaveInstanceState(outState)
    }

    private fun initMapSafely(savedInstanceState: Bundle?) {
        val context = requireContext()
        if (!AmapPrivacyManager.isAgreed(context)) {
            privacyPrompt.visibility = View.VISIBLE
            locateButton.visibility = View.GONE
            ZLog.w(TAG_SCOPE, "privacy not agreed, skip MapView init")
            return
        }
        try {
            AmapPrivacyManager.applyPrivacyIfAgreed(context)
            MapsInitializer.initialize(context)

            val key = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_AMAP_KEY, "")
            if (!key.isNullOrEmpty()) {
                MapsInitializer.setApiKey(key)
            }

            mapView = MapView(context).also { mv ->
                mapContainer.addView(
                    mv,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                mv.onCreate(savedInstanceState)
                amap = mv.map
            }
            setupMap()
            privacyPrompt.visibility = View.GONE
            locateButton.visibility = View.VISIBLE
        } catch (t: Throwable) {
            ZLog.e(TAG_SCOPE, "map init failed", t)
            privacyPrompt.visibility = View.VISIBLE
            locateButton.visibility = View.GONE
            Toast.makeText(context, R.string.location_map_init_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun setupMap() {
        amap?.let { map ->
            map.setOnMapClickListener { latLng ->
                selectOnMap(latLng)
            }
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT_CENTER, DEFAULT_ZOOM))
            map.uiSettings.apply {
                isZoomControlsEnabled = true
                isCompassEnabled = true
                isScaleControlsEnabled = true
            }
        }
    }

    /** 地图选点：更新 marker 与经纬度输入框。 */
    private fun selectOnMap(latLng: LatLng) {
        val map = amap ?: return
        selectedMarker?.remove()
        selectedMarker = map.addMarker(
            MarkerOptions()
                .position(latLng)
                .title(getString(R.string.location_map_hint))
        )
        latitudeInput.setText(formatCoord(latLng.latitude))
        longitudeInput.setText(formatCoord(latLng.longitude))
        ZLog.d(TAG_SCOPE, "map picked ${latLng.latitude},${latLng.longitude}")
    }

    private fun formatCoord(value: Double): String {
        return if (value == 0.0) "0.0" else String.format("%.6f", value)
    }

    private fun applyPoint(lat: Double, lon: Double) {
        executor.execute {
            val result = ApiClient.setLocation(lat, lon, 0f, 0f)
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
        }
    }

    /** 保存当前输入坐标（含名称/备注），成功后刷新列表。 */
    private fun saveCurrentPoint() {
        val name = pointNameInput.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), R.string.location_point_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        val lat = latitudeInput.text.toString().toDoubleOrNull()
        val lon = longitudeInput.text.toString().toDoubleOrNull()
        if (lat == null || lon == null) {
            Toast.makeText(requireContext(), R.string.location_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val remark = pointRemarkInput.text.toString().trim()
        executor.execute {
            val result = ApiClient.createLocationPoint(name, remark, lat, lon)
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                if (result.code == ApiResult.CODE_OK) {
                    pointNameInput.text.clear()
                    pointRemarkInput.text.clear()
                    refreshSavedPoints()
                }
            }
        }
    }

    private fun refreshSavedPoints() {
        executor.execute {
            val result = ApiClient.listLocationPoints()
            requireActivity().runOnUiThread {
                renderSavedPoints(result)
            }
        }
    }

    private fun renderSavedPoints(result: ApiResult) {
        savedPointList.removeAllViews()
        val points = result.data?.optJSONArray("points") ?: return
        val count = points.length()
        savedPointsEmpty.visibility = if (count == 0) View.VISIBLE else View.GONE
        if (count == 0) return

        for (i in 0 until count) {
            val item = points.optJSONObject(i) ?: continue
            val row = layoutInflater.inflate(R.layout.item_saved_point, savedPointList, false)
            row.findViewById<TextView>(R.id.pointName).text = item.optString("name", "")
            val remark = item.optString("remark", "")
            val remarkView = row.findViewById<TextView>(R.id.pointRemark)
            if (remark.isBlank()) {
                remarkView.visibility = View.GONE
            } else {
                remarkView.text = getString(R.string.location_point_remark_format, remark)
            }
            row.findViewById<TextView>(R.id.pointCoord).text = getString(
                R.string.location_point_coord_format,
                item.optDouble("latitude", 0.0),
                item.optDouble("longitude", 0.0)
            )
            row.findViewById<Button>(R.id.useButton).setOnClickListener {
                useSavedPoint(item.optLong("id"), item.optString("name", ""))
            }
            row.findViewById<Button>(R.id.deleteButton).setOnClickListener {
                deleteSavedPoint(item.optLong("id"))
            }
            savedPointList.addView(row)
        }
    }

    private fun useSavedPoint(id: Long, name: String) {
        executor.execute {
            val result = ApiClient.useLocationPoint(id)
            requireActivity().runOnUiThread {
                if (result.code == ApiResult.CODE_OK) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.location_point_applied, name),
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshStatus()
                } else {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun deleteSavedPoint(id: Long) {
        executor.execute {
            val result = ApiClient.deleteLocationPoint(id)
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                if (result.code == ApiResult.CODE_OK) {
                    refreshSavedPoints()
                }
            }
        }
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
                    latitudeInput.setText(formatCoord(data.optDouble("latitude", 0.0)))
                    longitudeInput.setText(formatCoord(data.optDouble("longitude", 0.0)))
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

    /** 定位到当前位置并在地图上显示。 */
    private fun locateCurrentPosition() {
        val context = requireContext()
        if (!AmapPrivacyManager.isAgreed(context)) {
            Toast.makeText(context, R.string.route_privacy_prompt, Toast.LENGTH_LONG).show()
            return
        }
        try {
            val client = com.amap.api.location.AMapLocationClient(context)
            client.setLocationListener { location ->
                if (location == null || location.errorCode != 0) {
                    ZLog.w(TAG_SCOPE, "locate error=${location?.errorCode} ${location?.errorInfo}")
                    requireActivity().runOnUiThread {
                        Toast.makeText(requireContext(), R.string.route_locate_failed, Toast.LENGTH_SHORT).show()
                    }
                    return@setLocationListener
                }
                val latLng = LatLng(location.latitude, location.longitude)
                ZLog.i(TAG_SCOPE, "located at ${location.latitude},${location.longitude}")
                requireActivity().runOnUiThread {
                    selectOnMap(latLng)
                    amap?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
                }
            }
            val option = com.amap.api.location.AMapLocationClientOption().apply {
                locationMode = com.amap.api.location.AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                isOnceLocation = true
                isNeedAddress = false
            }
            client.setLocationOption(option)
            client.startLocation()
            Toast.makeText(context, R.string.route_locating, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            ZLog.e(TAG_SCOPE, "locate failed", t)
            Toast.makeText(context, R.string.route_locate_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
