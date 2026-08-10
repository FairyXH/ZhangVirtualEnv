package io.github.fairyxh.VirtualEnv.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassBackdropHost
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassButton
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassCard
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassField
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassPill
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassToggle
import io.github.fairyxh.VirtualEnv.app.ui.glass.glassColors
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.util.concurrent.Executors

/**
 * 路线模拟页：高德地图上点击绘制路线，保存到 Backend。
 *
 * 隐私合规：未同意高德隐私政策前不创建 MapView（避免白屏）；
 * 初始化任何 SDK 接口前先调用 updatePrivacyShow / updatePrivacyAgree。
 *
 * 视图层已迁移到 Compose Liquid Glass，高德 MapView 通过 AndroidView 保留，
 * 全部业务逻辑（绘制/保存/启动/搜索/定位）不变。
 */
class RouteSimFragment : Fragment(), AMapLocationListener {

    companion object {
        private const val TAG_SCOPE = "UI"
        private const val PREFS = "amap_config"
        private const val KEY_AMAP_KEY = "amap_key"

        private val DEFAULT_CENTER = LatLng(31.2304, 121.4737)
        private const val DEFAULT_ZOOM = 12f
    }

    private data class SavedRoute(
        val id: Long,
        val name: String,
        val remark: String,
        val meta: String,
        val pointsCount: Int,
        val pointsArr: org.json.JSONArray? = null
    )

    // ---------- Compose 视图状态 ----------

    private var routeName by mutableStateOf("")
    private var routeRemark by mutableStateOf("")
    private var routeSpeed by mutableStateOf("")
    private var routeStep by mutableStateOf("")
    private var drawHint by mutableStateOf("")
    private var statusText by mutableStateOf("")
    private var switchChecked by mutableStateOf(false)
    private var searchText by mutableStateOf("")
    private var mapCollapsed by mutableStateOf(false)
    private var mapSatellite by mutableStateOf(false)
    private var privacyShown by mutableStateOf(false)
    private var mapReady by mutableStateOf(false)
    private val savedRoutes = mutableStateListOf<SavedRoute>()
    private val searchResults = mutableStateListOf<Pair<String, com.amap.api.services.core.PoiItem>>()
    private var searchResultsVisible by mutableStateOf(false)

    // ---------- 高德地图 ----------

    private var mapView: MapView? = null
    private var amap: AMap? = null
    private var locationClient: AMapLocationClient? = null

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
        routeName = io.github.fairyxh.VirtualEnv.util.DefaultNames.timeName(getString(R.string.route_title))
        drawHint = getString(R.string.route_draw_hint)
        privacyShown = !AmapPrivacyManager.isAgreed(requireContext())
        refreshSavedRoutes()
        refreshRouteStatus()
        return androidx.compose.ui.platform.ComposeView(requireContext()).apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                RouteScreen(this@RouteSimFragment, savedInstanceState)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (privacyShown && AmapPrivacyManager.isAgreed(requireContext())) {
            privacyShown = false
        }
        if (!mapCollapsed) {
            try {
                mapView?.onResume()
            } catch (_: Throwable) {
            }
        }
        refreshSavedRoutes()
        refreshRouteStatus()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            if (privacyShown && AmapPrivacyManager.isAgreed(requireContext())) {
                privacyShown = false
            }
            if (!mapCollapsed) {
                try {
                    mapView?.onResume()
                } catch (_: Throwable) {
                }
            }
            refreshSavedRoutes()
            refreshRouteStatus()
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

    // ---------- Compose UI ----------

    @Composable
    private fun RouteScreen(fragment: RouteSimFragment, savedInstanceState: Bundle?) {
        GlassBackdropHost(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) { backdrop ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val colors = glassColors()
                BasicText(
                    getString(R.string.route_title),
                    style = TextStyle(
                        color = colors.textPrimary,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    )
                )
                BasicText(
                    getString(R.string.route_subtitle),
                    style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                )

                // 开关 + 状态卡
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                BasicText(
                                    getString(R.string.route_switch_title),
                                    style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                )
                                BasicText(
                                    getString(R.string.route_switch_desc),
                                    Modifier.padding(top = 2.dp),
                                    style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                                )
                            }
                            GlassToggle(
                                selected = { switchChecked },
                                onSelect = { checked ->
                                    if (updatingSwitch) return@GlassToggle
                                    if (checked) fragment.enableRouteSimulation() else fragment.disableRouteSimulation()
                                },
                                backdrop = backdrop
                            )
                        }
                        BasicText(
                            statusText,
                            Modifier.padding(top = 10.dp),
                            style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                        )
                    }
                }

                // 路线绘制卡
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicText(
                                getString(R.string.route_config_title),
                                Modifier.weight(1f),
                                style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            )
                            GlassPill(
                                onClick = { fragment.toggleMapCollapsed() },
                                backdrop = backdrop,
                                modifier = Modifier.padding(end = 6.dp),
                                selected = mapCollapsed,
                                containerColor = colors.bgTertiary.copy(alpha = 0.4f),
                                height = 34.dp
                            ) {
                                BasicText(
                                    getString(if (mapCollapsed) R.string.map_panel_expand else R.string.map_panel_collapse),
                                    Modifier.padding(horizontal = 14.dp),
                                    style = TextStyle(color = colors.textPrimary, fontSize = 12.sp)
                                )
                            }
                            GlassPill(
                                onClick = { fragment.toggleSatellite() },
                                backdrop = backdrop,
                                selected = mapSatellite,
                                containerColor = if (mapSatellite) colors.accent.copy(alpha = 0.82f) else colors.bgTertiary.copy(alpha = 0.4f),
                                height = 34.dp
                            ) {
                                BasicText(
                                    getString(if (mapSatellite) R.string.map_standard else R.string.map_satellite),
                                    Modifier.padding(horizontal = 14.dp),
                                    style = TextStyle(color = if (mapSatellite) androidx.compose.ui.graphics.Color.White else colors.textPrimary, fontSize = 12.sp)
                                )
                            }
                        }
                        BasicText(
                            drawHint,
                            Modifier.padding(top = 8.dp),
                            style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                        )
                        if (privacyShown) {
                            BasicText(
                                getString(R.string.route_privacy_prompt),
                                Modifier.padding(top = 10.dp),
                                style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                            )
                        } else {
                            Box(
                                Modifier
                                    .padding(top = 10.dp)
                                    .fillMaxWidth()
                                    .height(if (mapCollapsed) 0.dp else 240.dp)
                            ) {
                                AndroidView(
                                    factory = { ctx ->
                                        initMapView(ctx, savedInstanceState)
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                    onRelease = {
                                        // 生命周期由 Fragment 管理
                                    }
                                )
                            }
                        }
                        Row(
                            Modifier.padding(top = 10.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            GlassButton(
                                onClick = { fragment.locateCurrentPosition() },
                                backdrop = backdrop,
                                modifier = Modifier.weight(1f),
                                isInteractive = mapReady,
                                surfaceColor = colors.bgTertiary.copy(alpha = 0.4f)
                            ) {
                                BasicText(
                                    getString(R.string.route_locate),
                                    style = TextStyle(color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                )
                            }
                            GlassButton(
                                onClick = { fragment.clearRoute() },
                                backdrop = backdrop,
                                modifier = Modifier.weight(1f),
                                isInteractive = mapReady,
                                surfaceColor = colors.bgTertiary.copy(alpha = 0.4f)
                            ) {
                                BasicText(
                                    getString(R.string.route_clear),
                                    style = TextStyle(color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                        // 预设
                        Row(
                            Modifier.padding(top = 8.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                R.id.presetWalk to (5.0 to 110),
                                R.id.presetRun to (10.0 to 180),
                                R.id.presetBike to (20.0 to 90),
                                R.id.presetDrive to (60.0 to 60)
                            ).forEach { (_, preset) ->
                                GlassPill(
                                    onClick = {
                                        routeSpeed = preset.first.toString()
                                        routeStep = preset.second.toString()
                                    },
                                    backdrop = backdrop,
                                    modifier = Modifier.weight(1f),
                                    selected = false,
                                    containerColor = colors.bgTertiary.copy(alpha = 0.3f),
                                    height = 32.dp
                                ) {
                                    BasicText(
                                        when (preset.second) {
                                            110 -> getString(R.string.route_preset_walk)
                                            180 -> getString(R.string.route_preset_run)
                                            90 -> getString(R.string.route_preset_bike)
                                            else -> getString(R.string.route_preset_drive)
                                        },
                                        Modifier.padding(horizontal = 8.dp),
                                        style = TextStyle(color = colors.textSecondary, fontSize = 12.sp)
                                    )
                                }
                            }
                        }
                        // 速度/步频输入 + 保存
                        Row(
                            Modifier.padding(top = 10.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            GlassField(
                                value = routeSpeed,
                                onValueChange = { routeSpeed = it },
                                backdrop = backdrop,
                                modifier = Modifier.weight(1f),
                                placeholder = getString(R.string.route_speed_hint)
                            )
                            GlassField(
                                value = routeStep,
                                onValueChange = { routeStep = it },
                                backdrop = backdrop,
                                modifier = Modifier.weight(1f),
                                placeholder = getString(R.string.route_step_hint)
                            )
                        }
                        GlassField(
                            value = routeName,
                            onValueChange = { routeName = it },
                            backdrop = backdrop,
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                            placeholder = getString(R.string.route_name_hint)
                        )
                        GlassField(
                            value = routeRemark,
                            onValueChange = { routeRemark = it },
                            backdrop = backdrop,
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                            placeholder = getString(R.string.route_remark_hint)
                        )
                        GlassButton(
                            onClick = { fragment.saveRoute() },
                            backdrop = backdrop,
                            modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                            tint = colors.accent
                        ) {
                            BasicText(
                                getString(R.string.route_save),
                                style = TextStyle(color = androidx.compose.ui.graphics.Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }

                // 地址搜索卡
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        BasicText(
                            getString(R.string.location_search),
                            style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        )
                        Row(
                            Modifier.padding(top = 10.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            GlassField(
                                value = searchText,
                                onValueChange = { searchText = it },
                                backdrop = backdrop,
                                modifier = Modifier.weight(1f),
                                placeholder = getString(R.string.location_search_hint)
                            )
                            GlassButton(
                                onClick = { fragment.searchPoi() },
                                backdrop = backdrop,
                                tint = colors.accent
                            ) {
                                BasicText(
                                    getString(R.string.location_search),
                                    style = TextStyle(color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                        if (searchResultsVisible) {
                            searchResults.forEach { (title, poi) ->
                                GlassPill(
                                    onClick = { fragment.jumpToSearchResult(poi) },
                                    backdrop = backdrop,
                                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
                                    selected = false,
                                    containerColor = colors.bgTertiary.copy(alpha = 0.3f),
                                    height = 44.dp
                                ) {
                                    BasicText(
                                        title,
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp),
                                        style = TextStyle(color = colors.textPrimary, fontSize = 13.sp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 已保存路线卡
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        BasicText(
                            getString(R.string.route_saved_title),
                            style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        )
                        if (savedRoutes.isEmpty()) {
                            BasicText(
                                getString(R.string.route_saved_empty),
                                Modifier.padding(top = 8.dp),
                                style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                            )
                        } else {
                            savedRoutes.forEach { route ->
                                SavedRouteRow(
                                    route = route,
                                    backdrop = backdrop,
                                    onUse = { fragment.startRouteSimulation(route) },
                                    onLoad = { fragment.loadRoute(route) },
                                    onDelete = { fragment.deleteRoute(route.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SavedRouteRow(
        route: SavedRoute,
        backdrop: com.kyant.backdrop.Backdrop,
        onUse: () -> Unit,
        onLoad: () -> Unit,
        onDelete: () -> Unit
    ) {
        val colors = glassColors()
        GlassPill(
            onClick = onLoad,
            backdrop = backdrop,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
            selected = false,
            containerColor = colors.bgTertiary.copy(alpha = 0.35f),
            height = 64.dp
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    BasicText(
                        route.name,
                        style = TextStyle(color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    )
                    if (route.remark.isNotBlank()) {
                        BasicText(
                            getString(R.string.location_point_remark_format, route.remark),
                            style = TextStyle(color = colors.textSecondary, fontSize = 11.sp)
                        )
                    }
                    BasicText(
                        route.meta,
                        style = TextStyle(color = colors.textTertiary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    )
                }
                GlassButton(
                    onClick = onUse,
                    backdrop = backdrop,
                    modifier = Modifier.width(72.dp),
                    isInteractive = false,
                    surfaceColor = colors.accent.copy(alpha = 0.2f)
                ) {
                    BasicText(
                        getString(R.string.route_start),
                        style = TextStyle(color = colors.accent, fontSize = 12.sp)
                    )
                }
                GlassButton(
                    onClick = onDelete,
                    backdrop = backdrop,
                    modifier = Modifier.width(64.dp),
                    isInteractive = false,
                    surfaceColor = colors.danger.copy(alpha = 0.25f)
                ) {
                    BasicText(
                        getString(R.string.home_recording_delete),
                        style = TextStyle(color = colors.danger, fontSize = 12.sp)
                    )
                }
            }
        }
    }

    // ---------- 地图与绘制 ----------

    private fun initMapView(ctx: Context, savedInstanceState: Bundle?): View {
        if (mapView != null) return mapView!!
        try {
            // 隐私合规接口必须在任何 SDK 调用前执行
            AmapPrivacyManager.applyPrivacyIfAgreed(ctx)
            MapsInitializer.initialize(ctx)

            val key = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_AMAP_KEY, "")
            if (!key.isNullOrEmpty()) {
                MapsInitializer.setApiKey(key)
            }

            val mv = MapView(ctx).also { view ->
                view.onCreate(savedInstanceState)
                amap = view.map
            }
            mapView = mv
            setupMap()
            mapReady = true
            return mv
        } catch (t: Throwable) {
            // MapView 初始化异常：显示提示而非白屏
            ZLog.e(TAG_SCOPE, "map init failed", t)
            privacyShown = true
            Toast.makeText(ctx, R.string.route_map_init_failed, Toast.LENGTH_LONG).show()
            return View(ctx)
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
        drawHint = getString(R.string.route_points_count, points.size)
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
        drawHint = getString(R.string.route_draw_hint)
    }

    private fun saveRoute() {
        val name = routeName.trim()
            .ifEmpty {
                io.github.fairyxh.VirtualEnv.util.DefaultNames.locationOrRoute(getString(R.string.route_title))
            }
        if (points.size < 2) {
            Toast.makeText(requireContext(), R.string.route_points_required, Toast.LENGTH_SHORT).show()
            return
        }
        val remark = routeRemark.trim()
        executor.execute {
            val result = ApiClient.createRoute(name, remark, points)
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                if (result.code == io.github.fairyxh.VirtualEnv.core.model.ApiResult.CODE_OK) {
                    currentRouteId = result.data?.optLong("id", -1L) ?: -1L
                    currentRouteName = name
                    clearRoute()
                    routeName = io.github.fairyxh.VirtualEnv.util.DefaultNames.timeName(
                        getString(R.string.route_title)
                    )
                    routeRemark = ""
                    refreshSavedRoutes()
                }
            }
        }
    }

    /** 收起/展开地图面板（GONE 时暂停 GLSurfaceView）。 */
    private fun toggleMapCollapsed() {
        mapCollapsed = !mapCollapsed
        try {
            if (mapCollapsed) mapView?.onPause() else mapView?.onResume()
        } catch (_: Throwable) {
        }
    }

    /** 卫星图/标准图切换。 */
    private fun toggleSatellite() {
        mapSatellite = !mapSatellite
        try {
            amap?.mapType = if (mapSatellite) AMap.MAP_TYPE_SATELLITE else AMap.MAP_TYPE_NORMAL
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "map type switch failed", t)
        }
    }

    // ---------- 开关与状态 ----------

    /** 读取输入框中的速度/步频（空/非法用 0 表示走路线默认）。 */
    private fun readSpeedFreq(): Pair<Double, Int> {
        val speed = routeSpeed.trim().toDoubleOrNull() ?: 0.0
        val freq = routeStep.trim().toIntOrNull() ?: 0
        return speed to freq
    }

    /** 开关打开：以当前选中的已保存路线启动路线模拟。 */
    private fun enableRouteSimulation() {
        if (currentRouteId <= 0) {
            Toast.makeText(requireContext(), R.string.route_select_first, Toast.LENGTH_SHORT).show()
            updateSwitchState(false)
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
                    updateSwitchState(false)
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
                    statusText = getString(R.string.route_status_offline)
                    updateSwitchState(false)
                    return@runOnUiThread
                }
                val running = data.optBoolean("running", false)
                updateSwitchState(running)
                statusText = if (running) {
                    getString(R.string.route_status_running, data.optInt("points", 0))
                } else {
                    getString(R.string.route_status_idle)
                }
            }
        }
    }

    /** 程序性设置开关（不触发业务回调）。 */
    private fun updateSwitchState(checked: Boolean) {
        updatingSwitch = true
        switchChecked = checked
        updatingSwitch = false
    }

    // ---------- 已保存路线列表 ----------

    private fun refreshSavedRoutes() {
        executor.execute {
            val result = ApiClient.listRoutes()
            requireActivity().runOnUiThread {
                savedRoutes.clear()
                val routes = result.data?.optJSONArray("routes") ?: return@runOnUiThread
                for (i in 0 until routes.length()) {
                    val item = routes.optJSONObject(i) ?: continue
                    savedRoutes.add(
                        SavedRoute(
                            id = item.optLong("id", -1L),
                            name = item.optString("name", ""),
                            remark = item.optString("remark", ""),
                            meta = getString(
                                R.string.route_point_count_format,
                                item.optJSONArray("points")?.length() ?: 0
                            ),
                            pointsCount = item.optJSONArray("points")?.length() ?: 0,
                            pointsArr = item.optJSONArray("points")
                        )
                    )
                }
            }
        }
    }

    /** 一键启动路线模拟（Backend RouteEngine 沿路线推进）。 */
    private fun startRouteSimulation(route: SavedRoute) {
        currentRouteId = route.id
        currentRouteName = route.name
        val (speed, freq) = readSpeedFreq()
        executor.execute {
            val result = ApiClient.startRoute(route.id, speed, freq)
            requireActivity().runOnUiThread {
                if (result.code == io.github.fairyxh.VirtualEnv.core.model.ApiResult.CODE_OK) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.route_started, route.name),
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
    private fun loadRoute(route: SavedRoute) {
        currentRouteId = route.id
        currentRouteName = route.name
        if (amap == null) {
            Toast.makeText(requireContext(), R.string.route_map_init_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val pointsArr = route.pointsArr ?: return
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
        routeName = route.name
        routeRemark = route.remark
        if (points.isNotEmpty()) {
            val center = io.github.fairyxh.VirtualEnv.util.GeoCoordConverter.wgs84ToGcj02(points.first())
            amap?.moveCamera(
                com.amap.api.maps.CameraUpdateFactory.newLatLngZoom(center, 14f)
            )
        }
        Toast.makeText(
            requireContext(),
            getString(R.string.route_loaded, route.name),
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

    // ---------- 定位 ----------

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

    private fun searchPoi() {
        val keyword = searchText.trim()
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
        searchResults.clear()
        if (pois.isEmpty()) {
            searchResultsVisible = false
            Toast.makeText(requireContext(), R.string.location_search_empty, Toast.LENGTH_SHORT).show()
            return
        }
        pois.forEach { poi ->
            searchResults.add("${poi.title} · ${poi.snippet}" to poi)
        }
        searchResultsVisible = true
    }

    /** 搜索跳转：仅移动地图视野（选点由用户点击地图完成）。 */
    private fun jumpToSearchResult(poi: com.amap.api.services.core.PoiItem) {
        val point = poi.latLonPoint ?: return
        val latLng = LatLng(point.latitude, point.longitude)
        io.github.fairyxh.VirtualEnv.util.DefaultNames.rememberPoi(poi.title)
        amap?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
        searchResultsVisible = false
        hideKeyboard()
    }

    private fun hideKeyboard() {
        try {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(
                requireView().windowToken,
                0
            )
        } catch (_: Throwable) {
        }
    }
}
