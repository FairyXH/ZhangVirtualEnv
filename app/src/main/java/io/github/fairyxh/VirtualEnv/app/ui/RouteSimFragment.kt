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
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.app.AmapPrivacyManager
import io.github.fairyxh.VirtualEnv.app.ApiClient
import io.github.fairyxh.VirtualEnv.app.MainActivity
import io.github.fairyxh.VirtualEnv.app.location.AmapLocationHelper
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassBackdropHost
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassButton
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassCard
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassField
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassPill
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassToggle
import io.github.fairyxh.VirtualEnv.app.ui.glass.glassColors
import io.github.fairyxh.VirtualEnv.util.ZLog
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
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
class RouteSimFragment : Fragment() {

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
    /** 循环播放：到达终点自动回到起点开始新一轮。 */
    private var loopChecked by mutableStateOf(false)
    /** 平滑回程：循环时到达终点以设定速度沿原路返回起点再开始新一轮。 */
    private var smoothReturnChecked by mutableStateOf(false)
    private var searchText by mutableStateOf("")
    private var mapCollapsed by mutableStateOf(false)
    private var mapSatellite by mutableStateOf(false)
    private var mapFullscreen by mutableStateOf(false)
    /** 触点是否在地图上：非全屏时也临时禁用页面滚动，保证地图手势 100% 可用 */
    private var mapTouchActive by mutableStateOf(false)
    private var privacyShown by mutableStateOf(false)
    private var mapReady by mutableStateOf(false)
    private val savedRoutes = mutableStateListOf<SavedRoute>()
    private val searchResults = mutableStateListOf<Pair<String, com.amap.api.services.core.PoiItem>>()
    private var searchResultsVisible by mutableStateOf(false)

    // ---------- 高德地图 ----------

    private var mapView: TextureMapView? = null
    private var amap: AMap? = null

    private val points = mutableListOf<LatLng>()
    private val markers = mutableListOf<Marker>()
    /** 路线绘制撤销栈：每次加点压入该点（WGS-84），撤销即弹出并移除最后一点。 */
    private val undoStack = ArrayDeque<LatLng>()
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
        // 地图触摸/全屏状态退出：解除页面滑动切页锁定
        if (mapFullscreen || mapTouchActive) {
            mapFullscreen = false
            mapTouchActive = false
            MainActivity.swipeLocked = false
        }
        try {
            mapView?.onDestroy()
        } catch (_: Throwable) {
        }
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
    private fun RouteScreen(fragment: RouteSimFragment, savedInstanceState: Bundle?) {
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
                    }

                // 卡片1：搜索 + 地图 + 当前位置 + 收起展开/卫星图（同一卡片；地图收起时整卡收起）
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
                                            initMapView(ctx, savedInstanceState)
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
                                    onClick = { fragment.undoLastPoint() },
                                    backdrop = backdrop,
                                    modifier = Modifier.weight(1f),
                                    isInteractive = mapReady,
                                    surfaceColor = colors.bgTertiary.copy(alpha = 0.4f)
                                ) {
                                    BasicText(
                                        getString(R.string.route_undo),
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

                // 卡片2：启用路线模拟开关
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
                        // 循环播放：到达终点自动回到起点开始新一轮
                        Row(
                            Modifier.padding(top = 12.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                BasicText(
                                    getString(R.string.route_loop_title),
                                    style = TextStyle(color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                )
                                BasicText(
                                    getString(R.string.route_loop_desc),
                                    Modifier.padding(top = 2.dp),
                                    style = TextStyle(color = colors.textSecondary, fontSize = 12.sp)
                                )
                            }
                            GlassToggle(
                                selected = { fragment.loopChecked },
                                onSelect = { checked ->
                                    fragment.loopChecked = checked
                                    fragment.applyRouteLoopOptions()
                                },
                                backdrop = backdrop
                            )
                        }
                        // 平滑回程：循环时到达终点以设定速度沿原路返回起点再开始新一轮
                        Row(
                            Modifier.padding(top = 12.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                BasicText(
                                    getString(R.string.route_smooth_title),
                                    style = TextStyle(color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                )
                                BasicText(
                                    getString(R.string.route_smooth_desc),
                                    Modifier.padding(top = 2.dp),
                                    style = TextStyle(color = colors.textSecondary, fontSize = 12.sp)
                                )
                            }
                            GlassToggle(
                                selected = { fragment.smoothReturnChecked },
                                onSelect = { checked ->
                                    fragment.smoothReturnChecked = checked
                                    fragment.applyRouteLoopOptions()
                                },
                                backdrop = backdrop
                            )
                        }
                    }
                }

                // 卡片3：路线参数输入框和按钮
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        BasicText(
                            getString(R.string.route_config_title),
                            style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        )
                        BasicText(
                            drawHint,
                            Modifier.padding(top = 8.dp),
                            style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                        )
                        // 预设：步行/跑步/自行车/驾车文字横向排布
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
                                        Modifier.padding(horizontal = 4.dp),
                                        maxLines = 1,
                                        style = TextStyle(color = colors.textSecondary, fontSize = 12.sp)
                                    )
                                }
                            }
                        }
                        // 速度/步频输入 + 名称 + 备注
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
                        Row(
                            Modifier.padding(top = 10.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
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
                            GlassButton(
                                onClick = { fragment.saveRoute() },
                                backdrop = backdrop,
                                modifier = Modifier.weight(1f),
                                tint = colors.accent
                            ) {
                                BasicText(
                                    getString(R.string.route_save),
                                    style = TextStyle(color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }

                // 卡片4：已保存路线列表
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
                                Modifier.padding(top = 4.dp),
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
                } // if (!fragment.mapFullscreen) 结束（卡片2/3/4 仅非全屏显示）
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

            val mv = TextureMapView(ctx).also { view ->
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
            // 地图 POI 标注/文字会吞掉底图点击事件：POI 点击同样视为加点
            map.setOnPOIClickListener { poi -> addPoint(poi.coordinate) }
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
        val wgs = io.github.fairyxh.VirtualEnv.util.GeoCoordConverter.gcj02ToWgs84(latLng)
        points.add(wgs)
        undoStack.addLast(wgs)
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
        undoStack.clear()
        polyline?.remove()
        polyline = null
        drawHint = getString(R.string.route_draw_hint)
    }

    /** 撤销最后一步绘制（可多次撤销）：移除最后一个点与其 marker。 */
    private fun undoLastPoint() {
        if (points.isEmpty() || markers.isEmpty() || undoStack.isEmpty()) {
            Toast.makeText(requireContext(), R.string.route_undo_empty, Toast.LENGTH_SHORT).show()
            return
        }
        undoStack.removeLast()
        points.removeAt(points.size - 1)
        markers.removeAt(markers.size - 1).remove()
        redrawPolyline()
        drawHint = getString(R.string.route_points_count, points.size)
        Toast.makeText(requireContext(), R.string.route_undo_done, Toast.LENGTH_SHORT).show()
        ZLog.d(TAG_SCOPE, "undo point, remaining=${points.size}")
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

    /** 地图全屏/退出：全屏时锁定页面横向滑动切页，避免与地图手势冲突。 */
    private fun updateMapFullscreen(fullscreen: Boolean) {
        if (mapFullscreen == fullscreen) return
        mapFullscreen = fullscreen
        MainActivity.swipeLocked = fullscreen
    }

    // ---------- 开关与状态 ----------

    /** 读取输入框中的速度/步频（空/非法用 0 表示走路线默认）。 */
    private fun readSpeedFreq(): Pair<Double, Int> {
        val speed = routeSpeed.trim().toDoubleOrNull() ?: 0.0
        val freq = routeStep.trim().toIntOrNull() ?: 0
        return speed to freq
    }

    /** 循环/平滑过渡开关变化：路线运行中立即生效；未运行时作为下次启动配置。 */
    private fun applyRouteLoopOptions() {
        executor.execute {
            ApiClient.configRoute(0.0, 0, loopChecked, smoothReturnChecked)
        }
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
            val result = ApiClient.startRoute(currentRouteId, speed, freq, loopChecked, smoothReturnChecked)
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
                loopChecked = data.optBoolean("loop", false)
                smoothReturnChecked = data.optBoolean("smoothReturn", false)
                statusText = when {
                    running && data.optBoolean("returning", false) ->
                        getString(R.string.route_status_returning, data.optInt("points", 0))
                    running ->
                        getString(R.string.route_status_running, data.optInt("points", 0))
                    else -> getString(R.string.route_status_idle)
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
            val result = ApiClient.startRoute(route.id, speed, freq, loopChecked, smoothReturnChecked)
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

    /**
     * 定位到当前位置并在地图上显示。
     *
     * 以普通 App 视角优先走高德 SDK 一次性定位（独立网络定位链路，不读测试适配层
     * 数据）；SDK 失败时回退系统定位，最后回退最近已知位置。
     */
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
        Toast.makeText(context, R.string.route_locating, Toast.LENGTH_SHORT).show()
        AmapLocationHelper.locateOnce(context) { amapLoc ->
            requireActivity().runOnUiThread {
                if (amapLoc != null) {
                    ZLog.i(TAG_SCOPE, "amap locate ok ${amapLoc.latitude},${amapLoc.longitude}")
                    amap?.moveCamera(
                        CameraUpdateFactory.newLatLngZoom(LatLng(amapLoc.latitude, amapLoc.longitude), 16f)
                    )
                    Toast.makeText(requireContext(), R.string.route_located, Toast.LENGTH_SHORT).show()
                } else {
                    fallbackLastKnown()
                }
            }
        }
    }

    /** 高德 SDK 定位失败时：先请求一次系统定位（不读被虚拟注入污染的 lastKnown），再退最近已知。 */
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
    @android.annotation.SuppressLint("MissingPermission")
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
