package io.github.fairyxh.VirtualEnv.app

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
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
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassBottomTab
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassBottomTabs
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentTab = savedInstanceState?.getInt(KEY_TAB, 0) ?: 0

        // Fragment 容器必须在视图树中立即可用（FragmentManager onStart 时按 id 查找），
        // 因此不放进 Compose AndroidView，底栏单独用 ComposeView 叠加。
        val root = SwipeAwareFrameLayout(this)
        root.setBackgroundColor(
            ContextCompat.getColor(this, R.color.bg_primary)
        )
        // 触屏横向滑动切换页面：只在快速 fling 时切换（dispatch 阶段观察，不消费事件，
        // 因此不影响页面纵向滚动与地图拖拽）
        root.onSwipe = { dx, dy, vx ->
            if (kotlin.math.abs(dx) > 120 && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f &&
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
        root.addView(
            container,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val bottomBar = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            // 液态玻璃：Android 12+ 的 View#setBackgroundBlurRadius 会对 View 背景后的真实
            // 内容（Fragment 页面）做系统级模糊，底栏因此悬浮在页面之上、能看见并模糊下方内容。
            // 该方法未在编译期 framework stub 暴露，运行时通过反射调用（API 31+ 存在）。
            background = ColorDrawable(AndroidColor.TRANSPARENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val radiusPx = (28 * resources.displayMetrics.density).toInt()
                    val method = android.view.View::class.java.getMethod(
                        "setBackgroundBlurRadius", Int::class.java
                    )
                    method.invoke(this, radiusPx)
                } catch (t: Throwable) {
                    ZLog.w("UI", "background blur unavailable", t)
                }
            }
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
        // 内容区不做底部预留：卡片可以滚动穿过底栏（底栏悬浮在页面之上）

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

    private fun switchTab(index: Int, animate: Boolean) {
        if (currentTab == index) return
        val fm = supportFragmentManager
        val ft = fm.beginTransaction()
        if (animate) {
            // 前进：新页从右滑入、旧页向左滑出；后退方向相反
            if (index > currentTab) {
                ft.setCustomAnimations(
                    R.anim.slide_in_right, R.anim.slide_out_left,
                    R.anim.slide_in_left, R.anim.slide_out_right
                )
            } else {
                ft.setCustomAnimations(
                    R.anim.slide_in_left, R.anim.slide_out_right,
                    R.anim.slide_in_right, R.anim.slide_out_left
                )
            }
        } else {
            ft.setCustomAnimations(
                R.anim.fade_in, R.anim.fade_out,
                R.anim.fade_in, R.anim.fade_out
            )
        }
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
    val colors = glassColors()
    val density = LocalDensity.current

    Box(Modifier.fillMaxWidth()) {
        // 玻璃采样层：只覆盖底栏胶囊区域（不延伸为全宽衬底），
        // 为 GlassBottomTabs 的 blur/lens 提供可模糊的极淡内容，视觉上是磨砂玻璃面
        Box(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                .height(64.dp)
                .layerBackdrop(backdrop)
                .drawBehind {
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.06f),
                            1f to Color.White.copy(alpha = 0.03f)
                        )
                    )
                }
        )

        val current = selectedTabIndex()
        GlassBottomTabs(
            selectedTabIndex = selectedTabIndex,
            onTabSelected = onTabSelected,
            backdrop = backdrop,
            tabsCount = tabIcons.size,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
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
