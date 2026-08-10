package io.github.fairyxh.VirtualEnv.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
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
    private lateinit var searchInput: EditText
    private lateinit var searchButton: Button
    private lateinit var searchResults: LinearLayout

    private var mapView: MapView? = null
    private var amap: AMap? = null
    private var selectedMarker: Marker? = null
    private var amapLocationClient: com.amap.api.location.AMapLocationClient? = null
    private var mapCollapsed = false
    private lateinit var mapCollapseButton: TextView
    private lateinit var mapPanel: View

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
        searchInput = root.findViewById(R.id.searchInput)
        searchButton = root.findViewById(R.id.searchButton)
        searchResults = root.findViewById(R.id.searchResults)

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

        root.findViewById<Button>(R.id.floatWindowButton).setOnClickListener { openFloatWindow() }
        root.findViewById<Button>(R.id.closeFloatButton).setOnClickListener {
            io.github.fairyxh.VirtualEnv.app.FloatControlService.stop(requireContext())
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
        setupSearch()
        mapCollapseButton = root.findViewById(R.id.mapCollapseButton)
        mapPanel = root.findViewById(R.id.mapPanel)
        mapCollapseButton.setOnClickListener { toggleMapCollapsed() }

        initMapSafely(savedInstanceState)
        refreshSavedPoints()
        refreshStatus()
        return root
    }

    override fun onResume() {
        super.onResume()
        if (!mapCollapsed) {
            try {
                mapView?.onResume()
            } catch (_: Throwable) {
            }
        }
        refreshStatus()
        refreshSavedPoints()
    }

    /** 收起/展开地图面板：从搜索框到当前位置按钮整体收起（GONE 时暂停 GLSurfaceView）。 */
    private fun toggleMapCollapsed() {
        mapCollapsed = !mapCollapsed
        mapPanel.visibility = if (mapCollapsed) View.GONE else View.VISIBLE
        mapCollapseButton.text = getString(
            if (mapCollapsed) R.string.map_panel_expand else R.string.map_panel_collapse
        )
        try {
            if (mapCollapsed) mapView?.onPause() else mapView?.onResume()
        } catch (_: Throwable) {
        }
    }

    override fun onPause() {
        super.onPause()
        if (!mapCollapsed) {
            try {
                mapView?.onPause()
            } catch (_: Throwable) {
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            amapLocationClient?.stopLocation()
            amapLocationClient?.onDestroy()
        } catch (_: Throwable) {
        }
        amapLocationClient = null
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

    /**
     * 地图选点（坐标来自高德地图，GCJ-02）：更新 marker 与经纬度输入框。
     * 输入框统一显示 WGS-84（虚拟定位实际输出坐标），选点坐标自动转换。
     */
    private fun selectOnMap(latLng: LatLng) {
        val map = amap ?: return
        selectedMarker?.remove()
        selectedMarker = map.addMarker(
            MarkerOptions()
                .position(latLng)
                .title(getString(R.string.location_map_hint))
        )
        // 高德地图坐标是 GCJ-02，转换为 WGS-84 后填入输入框（注入系统/保存地点统一用 WGS-84）
        val wgs = io.github.fairyxh.VirtualEnv.util.GeoCoordConverter.gcj02ToWgs84(latLng)
        latitudeInput.setText(formatCoord(wgs.latitude))
        longitudeInput.setText(formatCoord(wgs.longitude))
        ZLog.d(TAG_SCOPE, "map picked gcj=${latLng.latitude},${latLng.longitude} wgs=${wgs.latitude},${wgs.longitude}")
    }

    /**
     * 系统定位结果（WGS-84）回填：输入框直接填 WGS-84；
     * marker 需按 WGS→GCJ 转换后显示在高德地图上（否则图标偏移）。
     * @return 地图显示坐标（GCJ-02），供 moveCamera 使用。
     */
    private fun selectOnMapFromWgs(wgsLat: Double, wgsLon: Double): LatLng? {
        val map = amap ?: return null
        val gcj = io.github.fairyxh.VirtualEnv.util.GeoCoordConverter.wgs84ToGcj02(wgsLat, wgsLon)
        selectedMarker?.remove()
        selectedMarker = map.addMarker(
            MarkerOptions()
                .position(LatLng(gcj.first, gcj.second))
                .title(getString(R.string.location_map_hint))
        )
        latitudeInput.setText(formatCoord(wgsLat))
        longitudeInput.setText(formatCoord(wgsLon))
        ZLog.d(TAG_SCOPE, "system locate wgs=$wgsLat,$wgsLon gcj=${gcj.first},${gcj.second}")
        return LatLng(gcj.first, gcj.second)
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

    private fun openFloatWindow() {
        val context = requireContext()
        if (!android.provider.Settings.canDrawOverlays(context)) {
            Toast.makeText(context, R.string.float_permission_required, Toast.LENGTH_LONG).show()
            try {
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}")
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t: Throwable) {
                startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        io.github.fairyxh.VirtualEnv.app.FloatControlService.start(context)
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
                    // 开关只反映单点引擎；路线运行中位置由路线引擎提供，开关保持关闭
                    val enabled = data.optBoolean("singleEnabled", false)
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

    /** 定位到当前位置并在地图上显示（失败时回退最近已知位置）。 */
    private fun locateCurrentPosition() {
        val context = requireContext()
        if (!AmapPrivacyManager.isAgreed(context)) {
            Toast.makeText(context, R.string.route_privacy_prompt, Toast.LENGTH_LONG).show()
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(context, R.string.route_location_permission, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val client = amapLocationClient
                ?: com.amap.api.location.AMapLocationClient(context).also { amapLocationClient = it }
            client.setLocationListener { location ->
                if (location == null || location.errorCode != 0) {
                    ZLog.w(TAG_SCOPE, "locate error=${location?.errorCode} ${location?.errorInfo}")
                    if (location?.errorCode == 7) {
                        requireActivity().runOnUiThread {
                            Toast.makeText(
                                requireContext(),
                                R.string.location_amap_key_error,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    fallbackLastKnown()
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
            fallbackLastKnown()
        }
    }

    /** 高德定位失败时：先请求一次真实定位（不读被虚拟注入污染的 lastKnown），再退最近已知。 */
    private fun fallbackLastKnown() {
        try {
            io.github.fairyxh.VirtualEnv.app.location.SystemLocationHelper.requestOnce(requireContext()) { loc ->
                requireActivity().runOnUiThread {
                    if (loc != null) {
                        ZLog.i(TAG_SCOPE, "system locate ${loc.latitude},${loc.longitude}")
                        val display = selectOnMapFromWgs(loc.latitude, loc.longitude)
                        if (display != null) {
                            amap?.moveCamera(CameraUpdateFactory.newLatLngZoom(display, 16f))
                        }
                        Toast.makeText(requireContext(), R.string.location_locate_fallback, Toast.LENGTH_SHORT).show()
                    } else {
                        useLastKnownFallback()
                    }
                }
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "system locate failed", t)
            useLastKnownFallback()
        }
    }

    /** 最后的兜底：系统最近已知位置（仅作最后手段）。 */
    private fun useLastKnownFallback() {
        try {
            val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val loc = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            if (loc != null) {
                ZLog.i(TAG_SCOPE, "fallback last known ${loc.latitude},${loc.longitude}")
                requireActivity().runOnUiThread {
                    val display = selectOnMapFromWgs(loc.latitude, loc.longitude)
                    if (display != null) {
                        amap?.moveCamera(CameraUpdateFactory.newLatLngZoom(display, 16f))
                    }
                    Toast.makeText(requireContext(), R.string.location_locate_fallback, Toast.LENGTH_SHORT).show()
                }
            } else {
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), R.string.route_locate_failed, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "fallback last known failed", t)
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), R.string.route_locate_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- 地址搜索 ----------

    private fun setupSearch() {
        searchButton.setOnClickListener { searchPoi() }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                searchPoi()
                true
            } else {
                false
            }
        }
    }

    private fun searchPoi() {
        val keyword = searchInput.text.toString().trim()
        if (keyword.isEmpty()) return
        val context = requireContext()
        if (!AmapPrivacyManager.isAgreed(context)) {
            Toast.makeText(context, R.string.route_privacy_prompt, Toast.LENGTH_LONG).show()
            return
        }
        try {
            val query = com.amap.api.services.poisearch.PoiSearch.Query(keyword, "", "")
            query.pageSize = 5
            query.pageNum = 0
            val search = com.amap.api.services.poisearch.PoiSearch(context, query)
            search.setOnPoiSearchListener(object : com.amap.api.services.poisearch.PoiSearch.OnPoiSearchListener {
                override fun onPoiSearched(result: com.amap.api.services.poisearch.PoiResult?, rCode: Int) {
                    requireActivity().runOnUiThread {
                        if (rCode != 1000 || result == null) {
                            Toast.makeText(requireContext(), R.string.location_search_failed, Toast.LENGTH_SHORT).show()
                            return@runOnUiThread
                        }
                        renderSearchResults(result.pois ?: emptyList())
                    }
                }

                override fun onPoiItemSearched(poiItem: com.amap.api.services.core.PoiItem?, rCode: Int) {
                }
            })
            search.searchPOIAsyn()
            hideKeyboard()
        } catch (t: Throwable) {
            ZLog.e(TAG_SCOPE, "poi search failed", t)
            Toast.makeText(context, R.string.location_search_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderSearchResults(pois: List<com.amap.api.services.core.PoiItem>) {
        searchResults.removeAllViews()
        if (pois.isEmpty()) {
            searchResults.visibility = View.GONE
            Toast.makeText(requireContext(), R.string.location_search_empty, Toast.LENGTH_SHORT).show()
            return
        }
        pois.forEach { poi ->
            val row = TextView(requireContext())
            row.text = "${poi.title} · ${poi.snippet}"
            row.setTextColor(resources.getColor(R.color.text_primary, null))
            row.setTextSize(13f)
            row.setPadding(48, 40, 48, 40)
            row.setOnClickListener { jumpToSearchResult(poi) }
            searchResults.addView(row)
            if (poi != pois.last()) {
                val divider = View(requireContext())
                divider.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                divider.setBackgroundColor(resources.getColor(R.color.separator, null))
                searchResults.addView(divider)
            }
        }
        searchResults.visibility = View.VISIBLE
    }

    private fun jumpToSearchResult(poi: com.amap.api.services.core.PoiItem) {
        val point = poi.latLonPoint ?: return
        val latLng = LatLng(point.latitude, point.longitude)
        amap?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
        selectOnMap(latLng)
        searchResults.visibility = View.GONE
        hideKeyboard()
    }

    private fun hideKeyboard() {
        try {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
        } catch (_: Throwable) {
        }
    }
}
