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
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.app.AmapPrivacyManager
import io.github.fairyxh.VirtualEnv.app.ApiClient
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.util.concurrent.Executors

/**
 * 路线模拟页：高德地图上点击绘制路线，保存到 Backend。
 *
 * 隐私合规：未同意高德隐私政策前不创建 MapView（避免白屏）；
 * 初始化任何 SDK 接口前先调用 updatePrivacyShow / updatePrivacyAgree。
 */
class RouteSimFragment : Fragment(), AMapLocationListener {

    companion object {
        private const val TAG_SCOPE = "UI"
        private const val PREFS = "amap_config"
        private const val KEY_AMAP_KEY = "amap_key"

        private val DEFAULT_CENTER = LatLng(31.2304, 121.4737)
        private const val DEFAULT_ZOOM = 12f
    }

    private lateinit var mapContainer: FrameLayout
    private lateinit var privacyPrompt: View
    private lateinit var routeNameInput: EditText
    private lateinit var routeRemarkInput: EditText
    private lateinit var routeSpeedInput: EditText
    private lateinit var routeStepInput: EditText
    private lateinit var drawHint: TextView
    private lateinit var locateButton: Button
    private lateinit var savedRoutesEmpty: TextView
    private lateinit var savedRouteList: android.widget.LinearLayout
    private lateinit var routeEnableSwitch: android.widget.Switch
    private lateinit var routeStatusText: TextView
    private lateinit var searchInput: EditText
    private lateinit var searchButton: Button
    private lateinit var searchResults: android.widget.LinearLayout

    private var mapView: MapView? = null
    private var amap: AMap? = null
    private var locationClient: AMapLocationClient? = null
    private var mapCollapsed = false
    private lateinit var mapCollapseButton: TextView
    private lateinit var mapPanel: View

    private val points = mutableListOf<LatLng>()
    private val markers = mutableListOf<Marker>()
    private var polyline: Polyline? = null
    private val executor = Executors.newSingleThreadExecutor()

    /** 当前选中的已保存路线（开关启动/一键启动使用）。 */
    private var currentRouteId = -1L
    private var currentRouteName = ""

    /** 防止状态回填触发开关回环。 */
    private var updatingSwitch = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_route, container, false)
        mapContainer = root.findViewById(R.id.mapContainer)
        privacyPrompt = root.findViewById(R.id.privacyPrompt)
        routeNameInput = root.findViewById(R.id.routeNameInput)
        routeRemarkInput = root.findViewById(R.id.routeRemarkInput)
        drawHint = root.findViewById(R.id.drawHint)
        locateButton = root.findViewById(R.id.locateButton)
        savedRoutesEmpty = root.findViewById(R.id.savedRoutesEmpty)
        savedRouteList = root.findViewById(R.id.savedRouteList)
        routeEnableSwitch = root.findViewById(R.id.routeEnableSwitch)
        routeStatusText = root.findViewById(R.id.routeStatusText)
        searchInput = root.findViewById(R.id.searchInput)
        searchButton = root.findViewById(R.id.searchButton)
        searchResults = root.findViewById(R.id.searchResults)

        routeEnableSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            if (checked) enableRouteSimulation() else disableRouteSimulation()
        }
        setupSearch()
        routeSpeedInput = root.findViewById(R.id.routeSpeedInput)
        routeStepInput = root.findViewById(R.id.routeStepInput)
        setupPresets(root)
        mapCollapseButton = root.findViewById(R.id.mapCollapseButton)
        mapPanel = root.findViewById(R.id.mapPanel)
        mapCollapseButton.setOnClickListener { toggleMapCollapsed() }

        locateButton.setOnClickListener { locateCurrentPosition() }
        root.findViewById<Button>(R.id.floatWindowButton).setOnClickListener { openFloatWindow() }
        root.findViewById<Button>(R.id.closeFloatButton).setOnClickListener {
            io.github.fairyxh.VirtualEnv.app.FloatControlService.stop(requireContext())
        }
        root.findViewById<Button>(R.id.clearButton).setOnClickListener { clearRoute() }
        root.findViewById<Button>(R.id.saveButton).setOnClickListener { saveRoute() }

        initMapSafely(savedInstanceState)
        refreshSavedRoutes()
        refreshRouteStatus()
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
        refreshSavedRoutes()
        refreshRouteStatus()
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

    /** 速度/步频预设：步行/跑步/自行车/驾车。 */
    private fun setupPresets(root: View) {
        fun bind(id: Int, speed: Double, freq: Int) {
            root.findViewById<TextView>(id).setOnClickListener {
                routeSpeedInput.setText(speed.toString())
                routeStepInput.setText(freq.toString())
            }
        }
        bind(R.id.presetWalk, 5.0, 110)
        bind(R.id.presetRun, 10.0, 180)
        bind(R.id.presetBike, 20.0, 90)
        bind(R.id.presetDrive, 60.0, 60)
    }

    /** 读取输入框中的速度/步频（空/非法用 0 表示走路线默认）。 */
    private fun readSpeedFreq(): Pair<Double, Int> {
        val speed = routeSpeedInput.text.toString().trim().toDoubleOrNull() ?: 0.0
        val freq = routeStepInput.text.toString().trim().toIntOrNull() ?: 0
        return speed to freq
    }

    /** 开关打开：以当前选中的已保存路线启动路线模拟。 */
    private fun enableRouteSimulation() {
        if (currentRouteId <= 0) {
            Toast.makeText(requireContext(), R.string.route_select_first, Toast.LENGTH_SHORT).show()
            setSwitchChecked(false)
            return
        }
        val (speed, freq) = readSpeedFreq()
        executor.execute {
            val result = ApiClient.startRoute(currentRouteId, speed, freq)
            requireActivity().runOnUiThread {
                if (result.code == io.github.fairyxh.VirtualEnv.core.model.ApiResult.CODE_OK) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.route_started, currentRouteName),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                    setSwitchChecked(false)
                }
                refreshRouteStatus()
            }
        }
    }

    /** 开关关闭：停止路线模拟。 */
    private fun disableRouteSimulation() {
        executor.execute {
            val result = ApiClient.stopRoute()
            requireActivity().runOnUiThread {
                if (result.code != io.github.fairyxh.VirtualEnv.core.model.ApiResult.CODE_OK) {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
                refreshRouteStatus()
            }
        }
    }

    /** 刷新路线运行状态：开关与状态文本同步 Backend。 */
    private fun refreshRouteStatus() {
        executor.execute {
            val result = ApiClient.getRouteStatus()
            requireActivity().runOnUiThread {
                val data = result.data
                if (data == null) {
                    routeStatusText.text = getString(R.string.route_status_offline)
                    setSwitchChecked(false)
                    return@runOnUiThread
                }
                val running = data.optBoolean("running", false)
                setSwitchChecked(running)
                routeStatusText.text = if (running) {
                    getString(R.string.route_status_running, data.optInt("points", 0))
                } else {
                    getString(R.string.route_status_idle)
                }
            }
        }
    }

    /** 程序性设置开关（不触发业务回调）。 */
    private fun setSwitchChecked(checked: Boolean) {
        updatingSwitch = true
        routeEnableSwitch.isChecked = checked
        updatingSwitch = false
    }

    /**
     * 安全初始化地图：
     * 1. 先检查隐私合规（未同意则显示提示，不创建 MapView，避免白屏）
     * 2. 初始化 SDK 前调用 updatePrivacyShow / updatePrivacyAgree
     * 3. 任何初始化异常捕获后提示，不崩溃
     */
    private fun initMapSafely(savedInstanceState: Bundle?) {
        val context = requireContext()
        if (!AmapPrivacyManager.isAgreed(context)) {
            privacyPrompt.visibility = View.VISIBLE
            locateButton.visibility = View.GONE
            ZLog.w(TAG_SCOPE, "privacy not agreed, skip MapView init")
            return
        }
        try {
            // 隐私合规接口必须在任何 SDK 调用前执行
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
            // MapView 初始化异常：显示提示而非白屏
            ZLog.e(TAG_SCOPE, "map init failed", t)
            privacyPrompt.visibility = View.VISIBLE
            locateButton.visibility = View.GONE
            Toast.makeText(context, R.string.route_map_init_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun setupMap() {
        amap?.let { map ->
            map.setOnMapClickListener { latLng -> addPoint(latLng) }
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT_CENTER, DEFAULT_ZOOM))
            map.uiSettings.apply {
                isZoomControlsEnabled = true
                isCompassEnabled = true
                isScaleControlsEnabled = true
            }
        }
    }

    /** 地图点击加点（latLng 为高德 GCJ-02 显示坐标）：marker 显示在点击处，内部点统一存 WGS-84。 */
    private fun addPoint(latLng: LatLng) {
        val map = amap ?: return
        val marker = map.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("${points.size + 1}")
        )
        markers.add(marker)
        // 业务层坐标统一 WGS-84（虚拟定位输出），地图显示层需要 GCJ-02
        points.add(io.github.fairyxh.VirtualEnv.util.GeoCoordConverter.gcj02ToWgs84(latLng))
        redrawPolyline()
        drawHint.text = getString(R.string.route_points_count, points.size)
        ZLog.d(TAG_SCOPE, "add point ${points.size}: gcj=${latLng.latitude},${latLng.longitude}")
    }

    private fun redrawPolyline() {
        val map = amap ?: return
        polyline?.remove()
        if (points.size >= 2) {
            // 内部点（WGS-84）转回 GCJ-02 绘制到高德地图，避免路线偏移
            val display = points.map { io.github.fairyxh.VirtualEnv.util.GeoCoordConverter.wgs84ToGcj02(it) }
            polyline = map.addPolyline(
                PolylineOptions()
                    .addAll(display)
                    .width(8f)
                    .color(0xFF0071E3.toInt())
                    .geodesic(true)
            )
        } else {
            polyline = null
        }
    }

    private fun clearRoute() {
        markers.forEach { it.remove() }
        markers.clear()
        points.clear()
        polyline?.remove()
        polyline = null
        drawHint.text = getString(R.string.route_draw_hint)
    }

    private fun saveRoute() {
        val name = routeNameInput.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), R.string.route_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (points.size < 2) {
            Toast.makeText(requireContext(), R.string.route_points_required, Toast.LENGTH_SHORT).show()
            return
        }
        val remark = routeRemarkInput.text.toString().trim()
        executor.execute {
            val result = ApiClient.createRoute(name, remark, points)
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                if (result.code == io.github.fairyxh.VirtualEnv.core.model.ApiResult.CODE_OK) {
                    currentRouteId = result.data?.optLong("id", -1L) ?: -1L
                    currentRouteName = name
                    clearRoute()
                    routeNameInput.text.clear()
                    routeRemarkInput.text.clear()
                    refreshSavedRoutes()
                }
            }
        }
    }

    // ---------- 已保存路线列表 ----------

    private fun refreshSavedRoutes() {
        executor.execute {
            val result = ApiClient.listRoutes()
            requireActivity().runOnUiThread {
                renderSavedRoutes(result)
            }
        }
    }

    private fun renderSavedRoutes(result: io.github.fairyxh.VirtualEnv.core.model.ApiResult) {
        savedRouteList.removeAllViews()
        val routes = result.data?.optJSONArray("routes") ?: return
        val count = routes.length()
        savedRoutesEmpty.visibility = if (count == 0) View.VISIBLE else View.GONE
        if (count == 0) return

        for (i in 0 until count) {
            val item = routes.optJSONObject(i) ?: continue
            val row = layoutInflater.inflate(R.layout.item_saved_route, savedRouteList, false)
            row.findViewById<android.widget.TextView>(R.id.routeName).text = item.optString("name", "")
            val remark = item.optString("remark", "")
            val remarkView = row.findViewById<android.widget.TextView>(R.id.routeRemark)
            if (remark.isBlank()) {
                remarkView.visibility = View.GONE
            } else {
                remarkView.text = getString(R.string.location_point_remark_format, remark)
            }
            row.findViewById<android.widget.TextView>(R.id.routeMeta).text = getString(
                R.string.route_point_count_format,
                item.optJSONArray("points")?.length() ?: 0
            )
            row.findViewById<Button>(R.id.useButton).setOnClickListener {
                startRouteSimulation(item)
            }
            row.findViewById<Button>(R.id.deleteButton).setOnClickListener {
                deleteRoute(item.optLong("id"))
            }
            // 点击行文本区域：加载到地图继续编辑
            row.setOnClickListener { loadRoute(item) }
            savedRouteList.addView(row)
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

    /** 一键启动路线模拟（Backend RouteEngine 沿路线推进）。 */
    private fun startRouteSimulation(item: org.json.JSONObject) {
        val id = item.optLong("id")
        val name = item.optString("name", "")
        currentRouteId = id
        currentRouteName = name
        val (speed, freq) = readSpeedFreq()
        executor.execute {
            val result = ApiClient.startRoute(id, speed, freq)
            requireActivity().runOnUiThread {
                if (result.code == io.github.fairyxh.VirtualEnv.core.model.ApiResult.CODE_OK) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.route_started, name),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
                refreshRouteStatus()
            }
        }
    }

    /** 一键使用：把已保存路线加载到地图（可继续编辑或重新保存）。 */
    private fun loadRoute(item: org.json.JSONObject) {
        val pointsArr = item.optJSONArray("points") ?: return
        currentRouteId = item.optLong("id", -1L)
        currentRouteName = item.optString("name", "")
        if (amap == null) {
            Toast.makeText(requireContext(), R.string.route_map_init_failed, Toast.LENGTH_SHORT).show()
            return
        }
        clearRoute()
        for (i in 0 until pointsArr.length()) {
            val p = pointsArr.optJSONObject(i) ?: continue
            val lat = p.optDouble("lat", Double.NaN)
            val lon = p.optDouble("lon", Double.NaN)
            if (!lat.isNaN() && !lon.isNaN()) {
                // 后端点已统一 WGS-84，转为 GCJ-02 后画到高德地图
                val gcj = io.github.fairyxh.VirtualEnv.util.GeoCoordConverter.wgs84ToGcj02(lat, lon)
                addPoint(com.amap.api.maps.model.LatLng(gcj.first, gcj.second))
            }
        }
        routeNameInput.setText(item.optString("name", ""))
        routeRemarkInput.setText(item.optString("remark", ""))
        if (points.isNotEmpty()) {
            val center = io.github.fairyxh.VirtualEnv.util.GeoCoordConverter.wgs84ToGcj02(points.first())
            amap?.moveCamera(
                com.amap.api.maps.CameraUpdateFactory.newLatLngZoom(center, 14f)
            )
        }
        Toast.makeText(
            requireContext(),
            getString(R.string.route_loaded, item.optString("name", "")),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun deleteRoute(id: Long) {
        executor.execute {
            val result = ApiClient.deleteRoute(id)
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                if (result.code == io.github.fairyxh.VirtualEnv.core.model.ApiResult.CODE_OK) {
                    refreshSavedRoutes()
                }
            }
        }
    }

    /** 定位到当前位置（高德定位 SDK，一次定位）。 */
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
            if (locationClient == null) {
                locationClient = AMapLocationClient(context)
            }
            val client = locationClient ?: return
            client.setLocationListener(this)
            val option = AMapLocationClientOption()
            option.locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            option.isOnceLocation = true
            option.isNeedAddress = false
            client.setLocationOption(option)
            client.startLocation()
            Toast.makeText(context, R.string.route_locating, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            ZLog.e(TAG_SCOPE, "locate failed", t)
            Toast.makeText(context, R.string.route_locate_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onLocationChanged(location: AMapLocation?) {
        if (location == null || location.errorCode != 0) {
            val code = location?.errorCode ?: -1
            ZLog.w(TAG_SCOPE, "amap locate error=$code ${location?.errorInfo}")
            if (code == 7) {
                requireActivity().runOnUiThread {
                    Toast.makeText(
                        requireContext(),
                        R.string.location_amap_key_error,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            fallbackLastKnown()
            return
        }
        val latLng = LatLng(location.latitude, location.longitude)
        ZLog.i(TAG_SCOPE, "located at ${location.latitude},${location.longitude}")
        requireActivity().runOnUiThread {
            amap?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
        }
    }

    /** 高德定位失败时：先请求一次真实定位（不读被虚拟注入污染的 lastKnown），再退最近已知。 */
    private fun fallbackLastKnown() {
        try {
            io.github.fairyxh.VirtualEnv.app.location.SystemLocationHelper.requestOnce(requireContext()) { loc ->
                requireActivity().runOnUiThread {
                    if (loc != null) {
                        ZLog.i(TAG_SCOPE, "system locate ${loc.latitude},${loc.longitude}")
                        val gcj = io.github.fairyxh.VirtualEnv.util.GeoCoordConverter.wgs84ToGcj02(loc.latitude, loc.longitude)
                        amap?.moveCamera(
                            CameraUpdateFactory.newLatLngZoom(LatLng(gcj.first, gcj.second), 16f)
                        )
                        Toast.makeText(requireContext(), R.string.route_locate_fallback, Toast.LENGTH_SHORT).show()
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
                    val gcj = io.github.fairyxh.VirtualEnv.util.GeoCoordConverter.wgs84ToGcj02(loc.latitude, loc.longitude)
                    amap?.moveCamera(
                        CameraUpdateFactory.newLatLngZoom(LatLng(gcj.first, gcj.second), 16f)
                    )
                    Toast.makeText(requireContext(), R.string.route_locate_fallback, Toast.LENGTH_SHORT).show()
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

    /** 搜索跳转：仅移动地图视野（选点由用户点击地图完成）。 */
    private fun jumpToSearchResult(poi: com.amap.api.services.core.PoiItem) {
        val point = poi.latLonPoint ?: return
        val latLng = LatLng(point.latitude, point.longitude)
        amap?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
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
            locationClient?.stopLocation()
            locationClient?.onDestroy()
        } catch (_: Throwable) {
        }
        mapView?.onDestroy()
        mapView = null
        executor.shutdown()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView?.onSaveInstanceState(outState)
    }
}
