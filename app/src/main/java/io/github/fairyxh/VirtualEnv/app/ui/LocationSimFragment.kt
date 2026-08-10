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
import androidx.compose.foundation.layout.size
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
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassBackdropHost
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassButton
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassCard
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassField
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassPill
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassToggle
import io.github.fairyxh.VirtualEnv.app.ui.glass.glassColors
import io.github.fairyxh.VirtualEnv.core.model.ApiResult
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 位置模拟页：单点虚拟定位。
 *
 * 支持高德地图点选坐标、手动输入坐标、多个地点带备注保存，
 * 已保存地点一键使用（设置坐标并启用虚拟定位）。
 *
 * 视图层已迁移到 Compose Liquid Glass，高德 MapView 通过 AndroidView 保留，
 * 全部业务逻辑（点选/搜索/保存/定位/坐标转换）不变。
 */
class LocationSimFragment : Fragment() {

    companion object {
        private const val TAG_SCOPE = "UI"
        private const val PREFS = "amap_config"
        private const val KEY_AMAP_KEY = "amap_key"

        private val DEFAULT_CENTER = LatLng(31.2304, 121.4737)
        private const val DEFAULT_ZOOM = 12f
    }

    private data class SavedPoint(
        val id: Long,
        val name: String,
        val remark: String,
        val lat: Double,
        val lon: Double
    )

    // ---------- Compose 视图状态 ----------

    private var enableChecked by mutableStateOf(false)
    private var latitudeText by mutableStateOf("")
    private var longitudeText by mutableStateOf("")
    private var statusText by mutableStateOf("")
    private var pointName by mutableStateOf("")
    private var pointRemark by mutableStateOf("")
    private var searchText by mutableStateOf("")
    private var mapCollapsed by mutableStateOf(false)
    private var mapSatellite by mutableStateOf(false)
    private var privacyShown by mutableStateOf(false)
    private var mapReady by mutableStateOf(false)
    private val savedPoints = mutableStateListOf<SavedPoint>()
    private val searchResults = mutableStateListOf<Pair<String, com.amap.api.services.core.PoiItem>>()
    private var searchResultsVisible by mutableStateOf(false)

    // ---------- 高德地图 ----------

    private var mapView: MapView? = null
    private var amap: AMap? = null
    private var selectedMarker: Marker? = null
    private var amapLocationClient: com.amap.api.location.AMapLocationClient? = null

    private val executor = Executors.newSingleThreadExecutor()

    /** 防止状态回填触发 listener 回环。 */
    private var updatingFromBackend = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        pointName = io.github.fairyxh.VirtualEnv.util.DefaultNames.timeName(getString(R.string.location_title))
        privacyShown = !AmapPrivacyManager.isAgreed(requireContext())
        refreshSavedPoints()
        refreshStatus()
        return androidx.compose.ui.platform.ComposeView(requireContext()).apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LocationScreen(this@LocationSimFragment, savedInstanceState)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户可能在设置页同意隐私后返回，此时再初始化地图
        if (privacyShown && AmapPrivacyManager.isAgreed(requireContext())) {
            privacyShown = false
        }
        if (!mapCollapsed) {
            try {
                mapView?.onResume()
            } catch (_: Throwable) {
            }
        }
        refreshStatus()
        refreshSavedPoints()
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
            refreshStatus()
            refreshSavedPoints()
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

    // ---------- Compose UI ----------

    @Composable
    private fun LocationScreen(fragment: LocationSimFragment, savedInstanceState: Bundle?) {
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
                    getString(R.string.location_title),
                    style = TextStyle(
                        color = colors.textPrimary,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    )
                )
                BasicText(
                    getString(R.string.location_subtitle),
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
                                    getString(R.string.location_switch_title),
                                    style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                )
                                BasicText(
                                    getString(R.string.location_switch_desc),
                                    Modifier.padding(top = 2.dp),
                                    style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                                )
                            }
                            GlassToggle(
                                selected = { enableChecked },
                                onSelect = { checked ->
                                    if (updatingFromBackend) return@GlassToggle
                                    executor.execute {
                                        val result = ApiClient.setLocationEnabled(checked)
                                        requireActivity().runOnUiThread {
                                            Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                                            refreshStatus()
                                        }
                                    }
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

                // 坐标输入卡
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        BasicText(
                            getString(R.string.location_coord_title),
                            style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        )
                        BasicText(
                            getString(R.string.location_latitude),
                            Modifier.padding(top = 10.dp),
                            style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                        )
                        GlassField(
                            value = latitudeText,
                            onValueChange = { latitudeText = it },
                            backdrop = backdrop,
                            modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                            placeholder = "31.2304"
                        )
                        BasicText(
                            getString(R.string.location_longitude),
                            Modifier.padding(top = 8.dp),
                            style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                        )
                        GlassField(
                            value = longitudeText,
                            onValueChange = { longitudeText = it },
                            backdrop = backdrop,
                            modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                            placeholder = "121.4737"
                        )
                        GlassButton(
                            onClick = { fragment.applyPoint() },
                            backdrop = backdrop,
                            modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                            tint = colors.accent
                        ) {
                            BasicText(
                                getString(R.string.location_apply),
                                style = TextStyle(color = androidx.compose.ui.graphics.Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }

                // 地图卡
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
                                getString(R.string.location_map_hint),
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
                                        MapView(ctx).also { mv ->
                                            mapView = mv
                                            mv.onCreate(savedInstanceState)
                                            amap = mv.map
                                            setupMap()
                                            mapReady = true
                                        }
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

                // 保存地点卡
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        BasicText(
                            getString(R.string.location_saved_title),
                            style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        )
                        BasicText(
                            getString(R.string.location_saved_empty),
                            Modifier.padding(top = 4.dp),
                            style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                        )
                        GlassField(
                            value = pointName,
                            onValueChange = { pointName = it },
                            backdrop = backdrop,
                            modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                            placeholder = getString(R.string.location_point_name_hint)
                        )
                        GlassField(
                            value = pointRemark,
                            onValueChange = { pointRemark = it },
                            backdrop = backdrop,
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                            placeholder = getString(R.string.location_point_remark_hint)
                        )
                        GlassButton(
                            onClick = { fragment.saveCurrentPoint() },
                            backdrop = backdrop,
                            modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                            tint = colors.accent
                        ) {
                            BasicText(
                                getString(R.string.location_point_save),
                                style = TextStyle(color = androidx.compose.ui.graphics.Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            )
                        }
                        if (savedPoints.isEmpty()) {
                            BasicText(
                                getString(R.string.location_saved_empty),
                                Modifier.padding(top = 12.dp),
                                style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                            )
                        } else {
                            savedPoints.forEach { point ->
                                SavedPointRow(
                                    point = point,
                                    backdrop = backdrop,
                                    onUse = { fragment.useSavedPoint(point.id, point.name) },
                                    onDelete = { fragment.deleteSavedPoint(point.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SavedPointRow(
        point: SavedPoint,
        backdrop: com.kyant.backdrop.Backdrop,
        onUse: () -> Unit,
        onDelete: () -> Unit
    ) {
        val colors = glassColors()
        GlassPill(
            onClick = onUse,
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
                        point.name,
                        style = TextStyle(color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    )
                    if (point.remark.isNotBlank()) {
                        BasicText(
                            getString(R.string.location_point_remark_format, point.remark),
                            style = TextStyle(color = colors.textSecondary, fontSize = 11.sp)
                        )
                    }
                    BasicText(
                        getString(
                            R.string.location_point_coord_format,
                            point.lat,
                            point.lon
                        ),
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
                        getString(R.string.home_saved_select),
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

    // ---------- 地图与坐标 ----------

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

    /** 地图选点（坐标来自高德地图，GCJ-02）：更新 marker 与经纬度输入框。 */
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
        latitudeText = formatCoord(wgs.latitude)
        longitudeText = formatCoord(wgs.longitude)
        // 名称默认取最近一次高德选点/搜索的地址，没有则使用日期
        pointName = io.github.fairyxh.VirtualEnv.util.DefaultNames.locationOrRoute(getString(R.string.location_title))
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
        latitudeText = formatCoord(wgsLat)
        longitudeText = formatCoord(wgsLon)
        ZLog.d(TAG_SCOPE, "system locate wgs=$wgsLat,$wgsLon gcj=${gcj.first},${gcj.second}")
        return LatLng(gcj.first, gcj.second)
    }

    private fun formatCoord(value: Double): String {
        return if (value == 0.0) "0.0" else String.format("%.6f", value)
    }

    private fun applyPoint() {
        val lat = latitudeText.toDoubleOrNull()
        val lon = longitudeText.toDoubleOrNull()
        if (lat == null || lon == null) {
            Toast.makeText(requireContext(), R.string.location_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        executor.execute {
            val result = ApiClient.setLocation(lat, lon, 0f, 0f)
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                refreshStatus()
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
            amap?.mapType = if (mapSatellite) com.amap.api.maps.AMap.MAP_TYPE_SATELLITE
            else com.amap.api.maps.AMap.MAP_TYPE_NORMAL
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "map type switch failed", t)
        }
    }

    // ---------- 保存地点 ----------

    /** 保存当前输入坐标（含名称/备注），成功后刷新列表。 */
    private fun saveCurrentPoint() {
        val name = pointName.trim()
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), R.string.location_point_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        val lat = latitudeText.toDoubleOrNull()
        val lon = longitudeText.toDoubleOrNull()
        if (lat == null || lon == null) {
            Toast.makeText(requireContext(), R.string.location_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val remark = pointRemark.trim()
        executor.execute {
            val result = ApiClient.createLocationPoint(name, remark, lat, lon)
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                if (result.code == ApiResult.CODE_OK) {
                    pointName = io.github.fairyxh.VirtualEnv.util.DefaultNames.timeName(
                        getString(R.string.location_title)
                    )
                    pointRemark = ""
                    refreshSavedPoints()
                }
            }
        }
    }

    private fun refreshSavedPoints() {
        executor.execute {
            val result = ApiClient.listLocationPoints()
            requireActivity().runOnUiThread {
                savedPoints.clear()
                val points = result.data?.optJSONArray("points") ?: return@runOnUiThread
                for (i in 0 until points.length()) {
                    val item = points.optJSONObject(i) ?: continue
                    savedPoints.add(
                        SavedPoint(
                            id = item.optLong("id", -1L),
                            name = item.optString("name", ""),
                            remark = item.optString("remark", ""),
                            lat = item.optDouble("latitude", 0.0),
                            lon = item.optDouble("longitude", 0.0)
                        )
                    )
                }
            }
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
                    enableChecked = enabled
                    updatingFromBackend = false
                    latitudeText = formatCoord(data.optDouble("latitude", 0.0))
                    longitudeText = formatCoord(data.optDouble("longitude", 0.0))
                    statusText = getString(
                        R.string.location_status_value,
                        if (enabled) getString(R.string.location_enabled) else getString(R.string.location_disabled),
                        data.optDouble("latitude", 0.0),
                        data.optDouble("longitude", 0.0)
                    )
                } else {
                    statusText = getString(R.string.location_status_offline)
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
    @android.annotation.SuppressLint("MissingPermission")
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

    private fun jumpToSearchResult(poi: com.amap.api.services.core.PoiItem) {
        val point = poi.latLonPoint ?: return
        val latLng = LatLng(point.latitude, point.longitude)
        io.github.fairyxh.VirtualEnv.util.DefaultNames.rememberPoi(poi.title)
        amap?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
        selectOnMap(latLng)
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
