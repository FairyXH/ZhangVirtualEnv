package io.github.fairyxh.VirtualEnv.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.app.AmapPrivacyManager
import io.github.fairyxh.VirtualEnv.app.ApiClient
import io.github.fairyxh.VirtualEnv.app.MainActivity
import io.github.fairyxh.VirtualEnv.app.cell.CellInfo
import io.github.fairyxh.VirtualEnv.app.cell.CellRepository
import io.github.fairyxh.VirtualEnv.app.cell.CellSignalCalculator
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassBackdropHost
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassButton
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassCard
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassField
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassPill
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassToggle
import io.github.fairyxh.VirtualEnv.app.ui.glass.glassColors
import io.github.fairyxh.VirtualEnv.core.model.ApiResult
import io.github.fairyxh.VirtualEnv.util.ZLog
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
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
        private const val CELL_QUERY_PAGE_SIZE = 20
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
    private var mapFullscreen by mutableStateOf(false)
    /** 触点是否在地图上：非全屏时也临时禁用页面滚动，保证地图手势 100% 可用 */
    private var mapTouchActive by mutableStateOf(false)
    private var privacyShown by mutableStateOf(false)
    private var mapReady by mutableStateOf(false)
    private val savedPoints = mutableStateListOf<SavedPoint>()
    private val searchResults = mutableStateListOf<Pair<String, com.amap.api.services.core.PoiItem>>()
    private var searchResultsVisible by mutableStateOf(false)

    // ---------- OpenCellID 基站查询 ----------
    private var cellQueryCollapsed by mutableStateOf(true)
    private var cellQueryRadius by mutableStateOf("1500")
    private var cellQueryBusy by mutableStateOf(false)
    private var cellQueryStatus by mutableStateOf("")
    private var cellQueryPointText by mutableStateOf("")
    private var cellQuerySourceText by mutableStateOf("")
    private val cellQueryAllResults = mutableStateListOf<CellInfo>()
    private val cellQuerySelected = androidx.compose.runtime.mutableStateMapOf<Int, Boolean>()
    private var cellQueryResultLat by mutableStateOf(0.0)
    private var cellQueryResultLon by mutableStateOf(0.0)
    private var cellQueryPage by mutableStateOf(0)
    private var cellQueryTruncated by mutableStateOf(false)
    private var cellQuerySaveName by mutableStateOf("")
    private var cellQuerySaveRemark by mutableStateOf("")

    // ---------- 高德地图 ----------

    private var mapView: TextureMapView? = null
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
        // 地图触摸/全屏状态退出：解除页面滑动切页锁定
        if (mapFullscreen || mapTouchActive) {
            mapFullscreen = false
            mapTouchActive = false
            MainActivity.swipeLocked = false
        }
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
    @OptIn(ExperimentalComposeUiApi::class)
    private fun LocationScreen(fragment: LocationSimFragment, savedInstanceState: Bundle?) {
        // 搜索框全局锚点：结果面板悬浮在搜索框正下方（页面级，不被地图覆盖）
        var rootLeft by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        var searchAnchor by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
        GlassBackdropHost(
            modifier = Modifier
                .fillMaxSize()
        ) { backdrop ->
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { rootLeft = it.positionInRoot() }
            ) {
                val fullMapHeight = maxHeight
                val colors = glassColors()
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(
                            rememberScrollState(),
                            enabled = !fragment.mapFullscreen && !fragment.mapTouchActive
                        )
                        .padding(
                            if (fragment.mapFullscreen) {
                                PaddingValues(0.dp)
                            } else {
                                PaddingValues(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 130.dp)
                            }
                        ),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (!fragment.mapFullscreen) {
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
                }

                // 卡片1：搜索 + 地图 + 当前位置 + 收起展开/卫星图（同一卡片）
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = if (fragment.mapFullscreen) 0.dp else 18.dp,
                    containerColor = if (fragment.mapFullscreen) Color.Transparent
                        else colors.bgSecondary.copy(alpha = 0.45f)
                ) {
                    Column(Modifier.padding(if (fragment.mapFullscreen) 0.dp else 16.dp)) {
                        if (!fragment.mapFullscreen) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicText(
                                getString(R.string.location_search),
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
                        }
                        if (!mapCollapsed) {
                            if (!fragment.mapFullscreen) {
                            // 搜索框：输入时在正下方弹出悬浮候选列表（面板在页面级绘制，见下方 searchAnchor 面板）
                            Row(
                                Modifier
                                    .padding(top = 10.dp)
                                    .fillMaxWidth()
                                    .height(52.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                GlassField(
                                    value = searchText,
                                    onValueChange = { searchText = it },
                                    backdrop = backdrop,
                                    modifier = Modifier
                                        .weight(1f)
                                        .onGloballyPositioned { coords ->
                                            val pos = coords.positionInRoot()
                                            searchAnchor = androidx.compose.ui.geometry.Rect(
                                                pos.x, pos.y,
                                                pos.x + coords.size.width,
                                                pos.y + coords.size.height
                                            )
                                        },
                                    placeholder = getString(R.string.location_search_hint)
                                )
                                if (searchText.isNotEmpty()) {
                                    // 清空按钮：方形“清空”，与搜索框同排垂直居中，点击清空并关闭候选
                                    GlassButton(
                                        onClick = {
                                            searchText = ""
                                            searchResultsVisible = false
                                        },
                                        backdrop = backdrop,
                                        modifier = Modifier
                                            .width(64.dp)
                                            .height(40.dp),
                                        surfaceColor = colors.bgTertiary.copy(alpha = 0.35f)
                                    ) {
                                        BasicText(
                                            "清空",
                                            style = TextStyle(color = colors.textSecondary, fontSize = 12.sp)
                                        )
                                    }
                                }
                            }
                            }
                            if (privacyShown && !fragment.mapFullscreen) {
                                BasicText(
                                    getString(R.string.route_privacy_prompt),
                                    Modifier.padding(top = 10.dp),
                                    style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                                )
                            } else {
                                Box(
                                    Modifier
                                        .padding(top = if (fragment.mapFullscreen) 0.dp else 10.dp)
                                        .fillMaxWidth()
                                        .height(if (fragment.mapFullscreen) fullMapHeight else 240.dp)
                                ) {
                                    AndroidView(
                                        factory = { ctx ->
                                            try {
                                                // 隐私合规接口必须在任何 SDK 调用前执行
                                                AmapPrivacyManager.applyPrivacyIfAgreed(ctx)
                                                com.amap.api.maps.MapsInitializer.initialize(ctx)
                                                val key = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                                    .getString(KEY_AMAP_KEY, "")
                                                if (!key.isNullOrEmpty()) {
                                                    com.amap.api.maps.MapsInitializer.setApiKey(key)
                                                }
                                                TextureMapView(ctx).also { mv ->
                                                    mapView = mv
                                                    mv.onCreate(savedInstanceState)
                                                    amap = mv.map
                                                    setupMap()
                                                    mapReady = true
                                                }
                                            } catch (t: Throwable) {
                                                ZLog.e(TAG_SCOPE, "map init failed", t)
                                                privacyShown = true
                                                Toast.makeText(ctx, R.string.location_map_init_failed, Toast.LENGTH_LONG).show()
                                                android.view.View(ctx)
                                            }
                                        },
                                        // 地图手势直接交给 MapView：拖动/双指缩放正常，
                                        // 同时消费事件避免页面 verticalScroll 抢手势
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .pointerInteropFilter { event ->
                                                // 触点在地图上：按下即锁定页面滚动与横向切页
                                                // （地图独占手势，横向滑动松手不切 Tab），
                                                // 抬起/取消恢复；事件一律消费并转交 MapView
                                                when (event.action) {
                                                    android.view.MotionEvent.ACTION_DOWN -> {
                                                        mapTouchActive = true
                                                        MainActivity.swipeLocked = true
                                                    }
                                                    android.view.MotionEvent.ACTION_UP,
                                                    android.view.MotionEvent.ACTION_CANCEL -> {
                                                        mapTouchActive = false
                                                        MainActivity.swipeLocked = mapFullscreen
                                                    }
                                                }
                                                mapView?.dispatchTouchEvent(event)
                                                true
                                            },
                                        onRelease = {
                                            // 生命周期由 Fragment 管理
                                        }
                                    )
                                    if (fragment.mapFullscreen) {
                                        // 全屏时右上角悬浮退出按钮
                                        GlassButton(
                                            onClick = { fragment.updateMapFullscreen(false) },
                                            backdrop = backdrop,
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(16.dp),
                                            tint = colors.bgTertiary.copy(alpha = 0.75f)
                                        ) {
                                            BasicText(
                                                getString(R.string.map_exit_fullscreen),
                                                Modifier.padding(horizontal = 8.dp),
                                                style = TextStyle(
                                                    color = colors.textPrimary,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                            if (!fragment.mapFullscreen) {
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
                                    onClick = { fragment.updateMapFullscreen(true) },
                                    backdrop = backdrop,
                                    modifier = Modifier.weight(1f),
                                    isInteractive = mapReady,
                                    tint = colors.bgTertiary.copy(alpha = 0.55f)
                                ) {
                                    BasicText(
                                        getString(R.string.map_fullscreen),
                                        style = TextStyle(color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                            }
                        }
                    }
                }

                // 卡片2：启用虚拟定位开关
                if (!fragment.mapFullscreen) {
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

                // 卡片3：坐标输入 + 名称 + 备注 + 保存按钮
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
                        Row(
                            Modifier.padding(top = 10.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            GlassButton(
                                onClick = { fragment.teleportToPoint() },
                                backdrop = backdrop,
                                modifier = Modifier.weight(1f),
                                tint = colors.accent
                            ) {
                                BasicText(
                                    getString(R.string.location_point_teleport),
                                    style = TextStyle(color = androidx.compose.ui.graphics.Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                )
                            }
                            GlassButton(
                                onClick = { fragment.saveCurrentPoint() },
                                backdrop = backdrop,
                                modifier = Modifier.weight(1f),
                                surfaceColor = colors.bgTertiary.copy(alpha = 0.35f)
                            ) {
                                BasicText(
                                    getString(R.string.location_point_save),
                                    style = TextStyle(color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }

                // 卡片4：已保存地点列表
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
                        if (savedPoints.isEmpty()) {
                            BasicText(
                                getString(R.string.location_saved_empty),
                                Modifier.padding(top = 4.dp),
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
                // 卡片5：基站查询（OpenCellID，可收起）
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
                                    getString(R.string.location_cell_query_title),
                                    style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                )
                                BasicText(
                                    getString(R.string.location_cell_query_desc),
                                    Modifier.padding(top = 2.dp),
                                    style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                                )
                            }
                            GlassPill(
                                onClick = { fragment.toggleCellQueryCollapsed() },
                                backdrop = backdrop,
                                modifier = Modifier.padding(end = 6.dp),
                                selected = cellQueryCollapsed,
                                containerColor = colors.bgTertiary.copy(alpha = 0.4f),
                                height = 34.dp
                            ) {
                                BasicText(
                                    getString(if (cellQueryCollapsed) R.string.location_cell_query_expand else R.string.location_cell_query_collapse),
                                    Modifier.padding(horizontal = 14.dp),
                                    style = TextStyle(color = colors.textPrimary, fontSize = 12.sp)
                                )
                            }
                        }
                        if (!cellQueryCollapsed) {
                            if (latitudeText.toDoubleOrNull() == null || longitudeText.toDoubleOrNull() == null) {
                                BasicText(
                                    getString(R.string.location_cell_query_no_coord),
                                    Modifier.padding(top = 8.dp).fillMaxWidth(),
                                    style = TextStyle(color = colors.textTertiary, fontSize = 12.sp)
                                )
                            } else {
                                BasicText(
                                    getString(
                                        R.string.location_cell_query_point,
                                        latitudeText.toDoubleOrNull() ?: 0.0,
                                        longitudeText.toDoubleOrNull() ?: 0.0
                                    ),
                                    Modifier.padding(top = 8.dp).fillMaxWidth(),
                                    style = TextStyle(color = colors.textSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                )
                            }
                            Row(
                                Modifier.padding(top = 8.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                GlassField(
                                    value = cellQueryRadius,
                                    onValueChange = { cellQueryRadius = it },
                                    backdrop = backdrop,
                                    modifier = Modifier.weight(1f),
                                    placeholder = "1500"
                                )
                                GlassButton(
                                    onClick = { fragment.queryNearbyCells() },
                                    backdrop = backdrop,
                                    modifier = Modifier.width(112.dp),
                                    isInteractive = !cellQueryBusy,
                                    tint = colors.accent
                                ) {
                                    BasicText(
                                        getString(R.string.location_cell_query_button),
                                        style = TextStyle(
                                            color = if (cellQueryBusy) colors.textSecondary else Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                            }
                            BasicText(
                                getString(R.string.location_cell_query_radius_hint),
                                Modifier.padding(top = 2.dp).fillMaxWidth(),
                                style = TextStyle(color = colors.textTertiary, fontSize = 12.sp)
                            )
                            if (cellQuerySourceText.isNotEmpty()) {
                                BasicText(
                                    cellQuerySourceText,
                                    Modifier.padding(top = 6.dp).fillMaxWidth(),
                                    style = TextStyle(color = colors.textSecondary, fontSize = 12.sp)
                                )
                            }
                            if (cellQueryStatus.isNotEmpty()) {
                                BasicText(
                                    cellQueryStatus,
                                    Modifier.padding(top = 4.dp).fillMaxWidth(),
                                    style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                                )
                            }
                            if (cellQueryAllResults.isNotEmpty()) {
                                BasicText(
                                    getString(R.string.location_cell_query_results_hint),
                                    Modifier.padding(top = 10.dp).fillMaxWidth(),
                                    style = TextStyle(color = colors.textTertiary, fontSize = 12.sp)
                                )
                                if (cellQueryTruncated) {
                                    BasicText(
                                        getString(R.string.location_cell_query_truncated),
                                        Modifier.padding(top = 2.dp).fillMaxWidth(),
                                        style = TextStyle(color = colors.danger, fontSize = 12.sp)
                                    )
                                }
                                Row(
                                    Modifier.padding(top = 8.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    GlassPill(
                                        onClick = { fragment.cellQuerySelectAll() },
                                        backdrop = backdrop,
                                        modifier = Modifier.weight(1f),
                                        selected = false,
                                        containerColor = colors.bgTertiary.copy(alpha = 0.4f),
                                        height = 32.dp
                                    ) {
                                        BasicText(
                                            getString(R.string.location_cell_query_select_all),
                                            Modifier.padding(horizontal = 8.dp),
                                            style = TextStyle(color = colors.textPrimary, fontSize = 12.sp)
                                        )
                                    }
                                    GlassPill(
                                        onClick = { fragment.cellQuerySelectInvert() },
                                        backdrop = backdrop,
                                        modifier = Modifier.weight(1f),
                                        selected = false,
                                        containerColor = colors.bgTertiary.copy(alpha = 0.4f),
                                        height = 32.dp
                                    ) {
                                        BasicText(
                                            getString(R.string.location_cell_query_select_invert),
                                            Modifier.padding(horizontal = 8.dp),
                                            style = TextStyle(color = colors.textPrimary, fontSize = 12.sp)
                                        )
                                    }
                                    GlassPill(
                                        onClick = { fragment.cellQuerySelectNone() },
                                        backdrop = backdrop,
                                        modifier = Modifier.weight(1f),
                                        selected = false,
                                        containerColor = colors.bgTertiary.copy(alpha = 0.4f),
                                        height = 32.dp
                                    ) {
                                        BasicText(
                                            getString(R.string.location_cell_query_select_none),
                                            Modifier.padding(horizontal = 8.dp),
                                            style = TextStyle(color = colors.textPrimary, fontSize = 12.sp)
                                        )
                                    }
                                }
                                val pageStart = fragment.cellQueryPage * CELL_QUERY_PAGE_SIZE
                                val pageEnd = minOf(pageStart + CELL_QUERY_PAGE_SIZE, cellQueryAllResults.size)
                                (pageStart until pageEnd).forEach { index ->
                                    val cell = cellQueryAllResults[index]
                                    CellQueryRow(
                                        index = index,
                                        cell = cell,
                                        queryLat = cellQueryResultLat,
                                        queryLon = cellQueryResultLon,
                                        selected = cellQuerySelected[index] ?: true,
                                        onToggle = { fragment.toggleCellQuerySelection(index) },
                                        backdrop = backdrop
                                    )
                                }
                                val totalPages = maxOf(1, (cellQueryAllResults.size + CELL_QUERY_PAGE_SIZE - 1) / CELL_QUERY_PAGE_SIZE)
                                Row(
                                    Modifier.padding(top = 8.dp).fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    GlassPill(
                                        onClick = { if (fragment.cellQueryPage > 0) fragment.cellQueryPrevPage() },
                                        backdrop = backdrop,
                                        modifier = Modifier.weight(1f),
                                        selected = false,
                                        containerColor = colors.bgTertiary.copy(alpha = 0.4f),
                                        height = 34.dp
                                    ) {
                                        BasicText(
                                            getString(R.string.location_cell_query_prev),
                                            Modifier.padding(horizontal = 12.dp),
                                            style = TextStyle(color = if (fragment.cellQueryPage > 0) colors.textPrimary else colors.textTertiary, fontSize = 12.sp)
                                        )
                                    }
                                    BasicText(
                                        getString(R.string.location_cell_query_page, fragment.cellQueryPage + 1, totalPages, cellQueryAllResults.size),
                                        Modifier.weight(1f),
                                        style = TextStyle(color = colors.textSecondary, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                    )
                                    GlassPill(
                                        onClick = { if (fragment.cellQueryPage < totalPages - 1) fragment.cellQueryNextPage() },
                                        backdrop = backdrop,
                                        modifier = Modifier.weight(1f),
                                        selected = false,
                                        containerColor = colors.bgTertiary.copy(alpha = 0.4f),
                                        height = 34.dp
                                    ) {
                                        BasicText(
                                            getString(R.string.location_cell_query_next),
                                            Modifier.padding(horizontal = 12.dp),
                                            style = TextStyle(color = if (fragment.cellQueryPage < totalPages - 1) colors.textPrimary else colors.textTertiary, fontSize = 12.sp)
                                        )
                                    }
                                }
                                GlassField(
                                    value = cellQuerySaveName,
                                    onValueChange = { cellQuerySaveName = it },
                                    backdrop = backdrop,
                                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                                    placeholder = getString(R.string.location_cell_query_save_name_hint)
                                )
                                GlassField(
                                    value = cellQuerySaveRemark,
                                    onValueChange = { cellQuerySaveRemark = it },
                                    backdrop = backdrop,
                                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                                    placeholder = getString(R.string.location_cell_query_save_remark_hint)
                                )
                                GlassButton(
                                    onClick = { fragment.saveCellsToSimulation() },
                                    backdrop = backdrop,
                                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                                    isInteractive = !cellQueryBusy && cellQuerySelected.values.any { it },
                                    tint = colors.accent
                                ) {
                                    BasicText(
                                        getString(R.string.location_cell_query_save),
                                        style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                        }
                    }
                }
                } // if (!fragment.mapFullscreen) 结束（卡片2/3/4/5 仅非全屏显示）
            }
            // 搜索框输入实时搜索提示（300ms 防抖；全屏时暂停）
            LaunchedEffect(searchText, fragment.mapFullscreen) {
                if (!fragment.mapFullscreen && searchText.isNotBlank()) {
                    delay(300)
                    fragment.searchPoi(hideKey = false)
                }
            }
            // 候选列表：悬浮于地图之上、与搜索框等宽无缝接壤、磨砂背景、带分隔符。
            // 必须在页面级绘制（Column 之后），否则会被地图等后续内容覆盖。
            val density = LocalDensity.current
            val anchor = searchAnchor
            if (searchResultsVisible && anchor != null && !fragment.mapFullscreen) {
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (anchor.left - rootLeft.x).roundToInt(),
                                (anchor.top - rootLeft.y + anchor.height).roundToInt()
                            )
                        }
                        .width(with(density) { anchor.width.toDp() })
                        .zIndex(10f),
                    cornerRadius = 14.dp,
                    // 候选框背景更透（磨砂玻璃感）；候选项各自带材质底
                    containerColor = colors.bgSecondary.copy(alpha = 0.35f)
                ) {
                    // 候选项较多时面板内部可滚动，整体限高防止超出屏幕
                    Column(
                        Modifier
                            .padding(vertical = 6.dp)
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        searchResults.forEachIndexed { index, (title, poi) ->
                            GlassPill(
                                onClick = { fragment.jumpToSearchResult(poi) },
                                backdrop = backdrop,
                                modifier = Modifier
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                    .fillMaxWidth(),
                                selected = false,
                                containerColor = colors.bgTertiary.copy(alpha = 0.4f),
                                height = 52.dp
                            ) {
                                BasicText(
                                    title,
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp),
                                    maxLines = 2,
                                    style = TextStyle(color = colors.textPrimary, fontSize = 14.sp)
                                )
                            }
                            if (index < searchResults.lastIndex) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(0.6.dp)
                                        .padding(horizontal = 14.dp)
                                        .background(colors.textTertiary.copy(alpha = 0.3f))
                                )
                            }
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

    // ---------- OpenCellID 基站查询 ----------

    @Composable
    private fun CellQueryRow(
        index: Int,
        cell: CellInfo,
        queryLat: Double,
        queryLon: Double,
        selected: Boolean,
        onToggle: () -> Unit,
        backdrop: com.kyant.backdrop.Backdrop
    ) {
        val colors = glassColors()
        GlassPill(
            onClick = onToggle,
            backdrop = backdrop,
            modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
            selected = selected,
            containerColor = if (selected) colors.accent.copy(alpha = 0.16f)
            else colors.bgTertiary.copy(alpha = 0.3f),
            height = 76.dp
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    BasicText(
                        cell.summary(),
                        style = TextStyle(color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    )
                    // 基站自身经纬度：便于确认是否真的在查询范围内
                    BasicText(
                        String.format("%.6f, %.6f", cell.latitude, cell.longitude),
                        style = TextStyle(color = colors.accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    )
                    BasicText(
                        CellSignalCalculator.describe(cell, queryLat, queryLon),
                        style = TextStyle(color = colors.textSecondary, fontSize = 11.sp)
                    )
                }
                BasicText(
                    getString(if (selected) R.string.location_cell_query_selected else R.string.location_cell_query_unselected),
                    style = TextStyle(
                        color = if (selected) colors.accent else colors.textTertiary,
                        fontSize = 11.sp
                    )
                )
            }
        }
    }

    private fun toggleCellQueryCollapsed() {
        cellQueryCollapsed = !cellQueryCollapsed
    }

    private fun queryNearbyCells() {
        if (cellQueryBusy) return
        val lat = latitudeText.toDoubleOrNull()
        val lon = longitudeText.toDoubleOrNull()
        if (lat == null || lon == null) {
            Toast.makeText(requireContext(), R.string.location_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val radius = cellQueryRadius.toIntOrNull()?.coerceIn(200, 50000) ?: 1500
        cellQueryRadius = radius.toString()
        cellQueryBusy = true
        cellQueryStatus = getString(R.string.location_cell_query_running)
        cellQuerySourceText = ""
        executor.execute {
            try {
                val repository = CellRepository(requireContext())
                val query = repository.queryNearbyCells(lat, lon, radius)
                requireActivity().runOnUiThread {
                    cellQueryBusy = false
                    cellQueryAllResults.clear()
                    cellQueryAllResults.addAll(query.cells)
                    cellQuerySelected.clear()
                    query.cells.indices.forEach { cellQuerySelected[it] = true }
                    cellQueryResultLat = lat
                    cellQueryResultLon = lon
                    cellQueryPage = 0
                    cellQueryTruncated = query.truncated
                    if (cellQuerySaveName.isBlank()) {
                        cellQuerySaveName = io.github.fairyxh.VirtualEnv.util.DefaultNames.timeName(getString(R.string.location_cell_query_title))
                    }
                    cellQuerySourceText = buildSourceText(query)
                    cellQueryStatus = buildResultStatus(query, lat, lon, radius)
                    if (query.cells.isEmpty()) {
                        cellQueryAllResults.clear()
                    }
                }
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "cell query failed", t)
                requireActivity().runOnUiThread {
                    cellQueryBusy = false
                    cellQuerySourceText = ""
                    cellQueryStatus = "查询失败：${t.message}"
                }
            }
        }
    }

    /** 来源与在线状态文案：区分离线命中 / 在线命中 / 在线请求失败 / 在线真的无结果。 */
    private fun buildSourceText(query: CellRepository.NearbyQuery): String {
        return when (query.source) {
            "offline" -> getString(R.string.location_cell_query_source_offline)
            "hybrid-offline" -> getString(R.string.location_cell_query_source_hybrid_offline)
            "online-cache" -> getString(R.string.location_cell_query_source_online_cache)
            "hybrid-online" -> getString(R.string.location_cell_query_source_hybrid_online)
            "online" -> getString(R.string.location_cell_query_source_online)
            else -> ""
        }
    }

    private fun buildResultStatus(
        query: CellRepository.NearbyQuery,
        lat: Double,
        lon: Double,
        radius: Int
    ): String {
        val count = query.cells.size
        if (query.onlineError != null) {
            // 在线请求失败（含未配置 Key）
            return getString(R.string.location_cell_query_online_failed, query.onlineError)
        }
        if (query.onlineEmpty) {
            // 在线成功但无结果
            return getString(R.string.location_cell_query_online_empty, count, radius)
        }
        return when (query.source) {
            "offline" -> getString(R.string.location_cell_query_found, count, radius)
            "hybrid-offline" -> getString(R.string.location_cell_query_found, count, radius)
            else -> getString(R.string.location_cell_query_found_online, count, radius)
        }
    }

    private fun cellQueryPrevPage() {
        if (cellQueryPage > 0) cellQueryPage--
    }

    private fun cellQueryNextPage() {
        val totalPages = maxOf(1, (cellQueryAllResults.size + CELL_QUERY_PAGE_SIZE - 1) / CELL_QUERY_PAGE_SIZE)
        if (cellQueryPage < totalPages - 1) cellQueryPage++
    }

    private fun toggleCellQuerySelection(index: Int) {
        val current = cellQuerySelected[index] ?: true
        cellQuerySelected[index] = !current
    }

    /** 全选：当前查询结果全部选中（跨页）。 */
    private fun cellQuerySelectAll() {
        cellQueryAllResults.indices.forEach { cellQuerySelected[it] = true }
    }

    /** 反选：当前查询结果选中状态全部取反（跨页）。 */
    private fun cellQuerySelectInvert() {
        cellQueryAllResults.indices.forEach {
            val current = cellQuerySelected[it] ?: true
            cellQuerySelected[it] = !current
        }
    }

    /** 全不选：清空当前查询结果选中状态（跨页）。 */
    private fun cellQuerySelectNone() {
        cellQueryAllResults.indices.forEach { cellQuerySelected[it] = false }
    }

    private fun saveCellsToSimulation() {
        if (cellQueryBusy) return
        val lat = cellQueryResultLat
        val lon = cellQueryResultLon
        val selected = cellQueryAllResults.filterIndexed { index, _ -> cellQuerySelected[index] ?: true }
        if (selected.isEmpty()) {
            Toast.makeText(requireContext(), R.string.location_cell_query_none_selected, Toast.LENGTH_SHORT).show()
            return
        }
        val name = cellQuerySaveName.trim().ifEmpty {
            io.github.fairyxh.VirtualEnv.util.DefaultNames.timeName(getString(R.string.location_cell_query_title))
        }
        val remark = cellQuerySaveRemark.trim()
        cellQueryBusy = true
        cellQueryStatus = getString(R.string.location_cell_query_saving)
        executor.execute {
            try {
                val config = CellSignalCalculator.buildCellConfig(selected, lat, lon)
                val entries = config.optJSONArray("entries")
                if (entries == null || entries.length() == 0) {
                    requireActivity().runOnUiThread {
                        cellQueryBusy = false
                        cellQueryStatus = getString(R.string.location_cell_query_save_empty)
                        Toast.makeText(requireContext(), R.string.location_cell_query_save_empty, Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }
                // 1) 立即写入 cell 引擎并关闭自动托管（否则自动托管会覆盖用户配置，界面显示 0 个基站）
                ApiClient.setEnvData("cell", config)
                ApiClient.setEnvAutoManaged("cell", false)
                // 2) 保存为环境快照（type=cell），环境页「已保存环境」可见，点击「使用」可再次加载
                val snapshot = ApiClient.createEnvSnapshot(name, remark, "cell", config)
                requireActivity().runOnUiThread {
                    cellQueryBusy = false
                    if (snapshot.code == ApiResult.CODE_OK) {
                        cellQueryStatus = getString(R.string.location_cell_query_saved, entries.length(), name)
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.location_cell_query_saved, entries.length(), name),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        cellQueryStatus = "保存失败：${snapshot.message}"
                        Toast.makeText(requireContext(), "保存失败：${snapshot.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "cell save failed", t)
                requireActivity().runOnUiThread {
                    cellQueryBusy = false
                    cellQueryStatus = "保存失败：${t.message}"
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

    /**
     * 直接传送到输入坐标（不保存到列表）：设置坐标并启用单点虚拟定位。
     */
    private fun teleportToPoint() {
        val lat = latitudeText.toDoubleOrNull()
        val lon = longitudeText.toDoubleOrNull()
        if (lat == null || lon == null) {
            Toast.makeText(requireContext(), R.string.location_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        executor.execute {
            val result = ApiClient.setLocation(lat, lon, 0f, 0f)
            ApiClient.setLocationEnabled(true)
            autoQueryCell(lat, lon)
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
        }
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
            autoQueryCell(lat, lon)
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
        }
    }

    /** 换点后自动查询 OpenCellID 真实基站并写入自动托管缓存；未查到 → 空基站。 */
    private fun autoQueryCell(lat: Double, lon: Double) {
        executor.execute {
            try {
                val cellStatus = ApiClient.getEnvStatus("cell")
                val autoManaged = cellStatus.data?.optBoolean("autoManaged", false) ?: false
                if (!autoManaged) return@execute
                val repository = CellRepository(requireContext())
                val query = repository.queryNearbyCells(lat, lon, 1500)
                val config = if (query.cells.isNotEmpty()) {
                    CellSignalCalculator.buildCellConfig(query.cells, lat, lon)
                } else {
                    null
                }
                ApiClient.setAutoCell(config)
                ZLog.i(TAG_SCOPE, "auto cell query done cells=${query.cells.size} source=${query.source}")
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "auto cell query failed", t)
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

    /** 地图全屏/退出：全屏时锁定页面横向滑动切页，避免与地图手势冲突。 */
    private fun updateMapFullscreen(fullscreen: Boolean) {
        if (mapFullscreen == fullscreen) return
        mapFullscreen = fullscreen
        MainActivity.swipeLocked = fullscreen
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

    private fun searchPoi(hideKey: Boolean = true) {
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
            if (hideKey) hideKeyboard()
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
