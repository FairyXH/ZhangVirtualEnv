package io.github.fairyxh.VirtualEnv.app

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.app.ui.EnvFragment
import io.github.fairyxh.VirtualEnv.app.ui.HomeFragment
import io.github.fairyxh.VirtualEnv.app.ui.LocationSimFragment
import io.github.fairyxh.VirtualEnv.app.ui.RouteSimFragment
import io.github.fairyxh.VirtualEnv.app.ui.SettingsFragment
import io.github.fairyxh.VirtualEnv.app.ui.glass.AppBackground
import io.github.fairyxh.VirtualEnv.app.ui.glass.AppInsets
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassBottomTab
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassBottomTabs
import io.github.fairyxh.VirtualEnv.app.ui.glass.LiquidGlassBarRefraction
import io.github.fairyxh.VirtualEnv.app.ui.glass.glassColors
import io.github.fairyxh.VirtualEnv.util.ZLog

/**
 * 控制端主界面（单 Activity + Fragment 导航 + Liquid Glass 底栏）。
 *
 * 底栏五个入口：
 * - 主页（模块状态 / 一键采集）
 * - 位置模拟
 * - 路线模拟（地图绘制）
 * - 环境（基站 / WiFi / GNSS 骨架）
 * - 设置（高德 Key 配置等）
 *
 * 视图结构：FragmentContainerView 保留在普通 View 树（FragmentManager 依赖它在
 * onCreate/onStart 时立即可用），底栏单独用 ComposeView 渲染 GlassBottomTabs。
 * currentTab 为 Compose state，底栏滑块通过 snapshotFlow 跟随点击动画。
 */
class MainActivity : FragmentActivity() {

    companion object {
        private const val TAG_SCOPE = "UI"
        private const val KEY_TAB = "current_tab"
        /** 地图全屏等沉浸场景下锁定横向滑动切页。 */
        @Volatile
        var swipeLocked = false
        private val TAB_ICONS = intArrayOf(
            R.drawable.ic_tab_home,
            R.drawable.ic_tab_location,
            R.drawable.ic_tab_route,
            R.drawable.ic_tab_env,
            R.drawable.ic_tab_settings,
        )
        private val TAB_LABELS = intArrayOf(
            R.string.tab_home,
            R.string.tab_location,
            R.string.tab_route,
            R.string.tab_env,
            R.string.tab_settings,
        )
    }

    /** Compose state：底栏滑块跟随此值动画。 */
    private var currentTab by mutableIntStateOf(0)

    private var barRefraction: LiquidGlassBarRefraction? = null

    private var rootView: SwipeAwareFrameLayout? = null

    /** Fragment 容器：动画过渡时露出背景须与页面主题一致，避免白色闪烁 */
    private var fragmentContainer: FragmentContainerView? = null

    private fun isDarkMode(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** 跟随系统主题设置窗口/root/容器背景与系统栏图标颜色。 */
    private fun applyThemeBackground() {
        val dark = isDarkMode()
        val bg = if (dark) AndroidColor.BLACK else AndroidColor.parseColor("#F2F2F7")
        rootView?.setBackgroundColor(bg)
        fragmentContainer?.setBackgroundColor(bg)
        window.setBackgroundDrawable(ColorDrawable(bg))
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyThemeBackground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentTab = savedInstanceState?.getInt(KEY_TAB, 0) ?: 0
        AppBackground.load(applicationContext)

        // Fragment 容器必须在视图树中立即可用（FragmentManager onStart 时按 id 查找），
        // 因此不放进 Compose AndroidView，底栏单独用 ComposeView 叠加。
        val root = SwipeAwareFrameLayout(this).apply {
            setBackgroundColor(AndroidColor.BLACK)
        }
        rootView = root
        // 触屏横向滑动切换页面：只在快速 fling 时切换（dispatch 阶段观察，不消费事件，
        // 因此不影响页面纵向滚动与地图拖拽）
        root.onSwipe = { dx, dy, vx ->
            // 地图全屏等场景锁定切页，避免与地图手势冲突
            if (!swipeLocked &&
                kotlin.math.abs(dx) > 120 && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f &&
                kotlin.math.abs(vx) > 600f
            ) {
                if (dx < 0 && currentTab < 4) {
                    switchTab(currentTab + 1, true)
                } else if (dx > 0 && currentTab > 0) {
                    switchTab(currentTab - 1, true)
                }
            }
        }
        val container = FragmentContainerView(this).apply {
            id = R.id.fragmentContainer
        }
        fragmentContainer = container
        root.addView(
            container,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val bottomBar = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            // 底栏完全透明悬浮：不启用任何系统背景模糊。
            // 部分 ROM 的 View#setBackgroundBlurRadius 反射成功后会把整个
            // ComposeView 覆盖区渲染成全宽模糊条带，看起来就是包裹底栏的矩形。
            background = ColorDrawable(AndroidColor.TRANSPARENT)
            // 关键：ViewGroup 默认 clipChildren=true，选中胶囊被拖动放大后顶部会
            // 超出底栏 View 边界并被硬裁剪成一条平直线（“上半部分被削平”）。
            // 关闭裁剪让胶囊真正“浮起”出底栏区域。
            clipChildren = false
            clipToPadding = false
            setContent {
                LiquidBottomBar(
                    selectedTabIndex = { currentTab },
                    onTabSelected = { switchTab(it, true) },
                    tabIcons = TAB_ICONS,
                    tabLabels = TAB_LABELS
                )
            }
        }
        root.addView(
            bottomBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
        setContentView(root)
        // attach 后再消费窗口 insets：AndroidComposeView 默认把 insets 当作
        // contentPadding（ColorOS 曲面安全区 left=56px 会让整页从 x=56 开始、
        // 左侧漏底），root 消费后所有 Compose 内容真正全屏；insets 缓存在
        // AppInsets，页面内容层与底栏用它手动避让系统栏。
        io.github.fairyxh.VirtualEnv.app.ui.glass.AppInsets.attachConsume(root)
        applyThemeBackground()
        // edge-to-edge：关闭 fitsSystemWindows 自动推移，否则 Fragment 的 ComposeView
        // 被系统 insets（顶部状态栏/左侧曲面安全区/底部导航栏）整体挤小，
        // 窗口透明后这些区域会露出黑边。insets 由 GlassBackdropHost 内容层自行处理。
        window.setDecorFitsSystemWindows(false)
        // 沉浸式系统栏：状态栏/导航栏背景透明 + 关闭对比度增强，让手势小白条
        // 透明融入背景（不隐藏系统栏，保留小白条但沉浸化）
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 28) {
            window.navigationBarDividerColor = AndroidColor.TRANSPARENT
        }
        // 内容区不做底部预留：卡片可以滚动穿过底栏（底栏悬浮在页面之上）

        // ComposeView 内部还有 AndroidComposeView 布局根，默认同样裁剪子绘制；
        // 每次布局都执行（不提前移除监听），确保子视图就绪后也把裁剪链关掉，
        // 否则放大胶囊顶部会在底栏上边缘被硬裁成平直线。
        bottomBar.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    var view: View? = bottomBar
                    while (view is ViewGroup) {
                        view.clipChildren = false
                        view.clipToPadding = false
                        view.clipToOutline = false
                        view = if (view.childCount > 0) view.getChildAt(0) else null
                    }
                }
            }
        )

        // 透镜折射：对页面内容做清晰的光线折射（非磨砂/非模糊），
        // 挂在 Fragment 容器上，只影响玻璃条带区域。
        // 注意：ColorOS/Oplus 上 View#setRenderEffect(RuntimeShader) 的输出区域
        // 会从 x=56 开始（左 56px 不渲染，露出窗口背景黑边），因此默认不挂载；
        // 玻璃感由底栏自身 drawBackdrop 的 blur/lens 承担。
        if (false) {
            val refraction = LiquidGlassBarRefraction(
                container = container,
                bar = bottomBar,
                capsuleLeftDp = 20f,
                capsuleRightDp = 20f,
                contentTopOffsetDp = 33f,
                featherDp = 16f,
                refractionDp = 3.5f
            )
            if (refraction.attach()) {
                barRefraction = refraction
            }
        }

        if (savedInstanceState == null) {
            // switchTab 会因 currentTab == index 提前返回；先把索引置 -1，
            // 确保启动时真正提交首页 Fragment（否则主页空白，需切换后才显示）
            currentTab = -1
            switchTab(0, false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
    }

    override fun onDestroy() {
        super.onDestroy()
        barRefraction?.detach()
        barRefraction = null
    }

    private fun switchTab(index: Int, animate: Boolean) {
        if (currentTab == index) return
        val fm = supportFragmentManager
        val ft = fm.beginTransaction()
        // 优雅无缝切换：交叉淡入淡出（旧页淡出时新页淡入，重叠过渡不露背景），
        // 不使用滑动动画——slide 会让新旧页错开，露出主题背景造成白色闪烁
        ft.setCustomAnimations(
            R.anim.fade_in, R.anim.fade_out,
            R.anim.fade_in, R.anim.fade_out
        )
        // 隐藏当前页（保留其视图与状态，切回时不重建）
        if (currentTab in 0..4) {
            fm.findFragmentByTag("tab$currentTab")?.let { ft.hide(it) }
        }
        currentTab = index
        // 优先复用已创建 Fragment，避免主页录制/回放等状态被重置
        var fragment = fm.findFragmentByTag("tab$index")
        if (fragment == null) {
            fragment = when (index) {
                0 -> HomeFragment()
                1 -> LocationSimFragment()
                2 -> RouteSimFragment()
                3 -> EnvFragment()
                else -> SettingsFragment()
            }
            ft.add(R.id.fragmentContainer, fragment, "tab$index")
        } else {
            ft.show(fragment)
        }
        ft.commit()
        ZLog.d(TAG_SCOPE, "switch tab -> $index")
    }
}

@Composable
private fun LiquidBottomBar(
    selectedTabIndex: () -> Int,
    onTabSelected: (Int) -> Unit,
    tabIcons: IntArray,
    tabLabels: IntArray
) {
    val backdrop = rememberLayerBackdrop()

    Column(Modifier.fillMaxWidth()) {
        // 避免被 ComposeView 的 View 边界硬裁剪（clipChildren 对 Compose 内部
        // RenderNode 无效，这里直接用布局空间解决）。该区域透明且不拦截触摸。
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth()) {
            // 玻璃采样层：完全透明（不绘制任何底色），恢复全透底栏。
            // 导航栏避让用 AppInsets 缓存（窗口 insets 已在 root 层被消费）
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(AppInsets.navigationBars.asPaddingValues())
                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                    .height(64.dp)
                    .layerBackdrop(backdrop)
            )

            val current = selectedTabIndex()
            GlassBottomTabs(
                selectedTabIndex = selectedTabIndex,
                onTabSelected = onTabSelected,
                backdrop = backdrop,
                tabsCount = tabIcons.size,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppInsets.navigationBars.asPaddingValues())
                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
            ) {
                repeat(tabIcons.size) { index ->
                    GlassBottomTab(
                        onClick = { onTabSelected(index) },
                        modifier = Modifier
                    ) {
                        TabIcon(
                            painter = painterResource(tabIcons[index]),
                            active = index == current,
                            label = tabLabels[index]
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabIcon(
    painter: Painter,
    active: Boolean,
    label: Int
) {
    val colors = glassColors()
    val tint = if (active) colors.tabIconActive else colors.tabIconNormal
    Image(
        painter = painter,
        contentDescription = null,
        modifier = Modifier.size(26.dp),
        colorFilter = ColorFilter.tint(tint)
    )
    BasicText(
        text = androidx.compose.ui.res.stringResource(label),
        style = TextStyle(color = tint, fontSize = 10.sp)
    )
}

/**
 * 在 dispatch 阶段观察触摸事件的根布局：横向快速滑动时触发页面切换回调。
 * 不消费任何事件，因此不影响 Fragment 内容滚动与地图拖拽。
 */
private class SwipeAwareFrameLayout(context: android.content.Context) : FrameLayout(context) {

    var onSwipe: ((dx: Float, dy: Float, velocityX: Float) -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downTime = SystemClock.uptimeMillis()
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                val dt = (SystemClock.uptimeMillis() - downTime).coerceAtLeast(1L)
                val vx = dx / (dt / 1000f)
                onSwipe?.invoke(dx, dy, vx)
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}
