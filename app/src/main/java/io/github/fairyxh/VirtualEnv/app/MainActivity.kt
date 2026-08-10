package io.github.fairyxh.VirtualEnv.app

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.app.ui.HomeFragment
import io.github.fairyxh.VirtualEnv.app.ui.LocationSimFragment
import io.github.fairyxh.VirtualEnv.app.ui.RouteSimFragment
import io.github.fairyxh.VirtualEnv.app.ui.EnvFragment
import io.github.fairyxh.VirtualEnv.app.ui.SettingsFragment
import io.github.fairyxh.VirtualEnv.util.ZLog
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * 控制端主界面（单 Activity + Fragment 导航 + 液态玻璃底栏）。
 *
 * 底栏五个入口：
 * - 主页（模块状态 / 一键采集）
 * - 位置模拟
 * - 路线模拟（地图绘制）
 * - 环境（基站 / WiFi / GNSS 骨架）
 * - 设置（高德 Key 配置等）
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

    private lateinit var bottomBar: LinearLayout
    private var currentTab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bottomBar = findViewById(R.id.bottomBar)
        currentTab = savedInstanceState?.getInt(KEY_TAB, 0) ?: 0
        buildBottomBar()

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

    private fun buildBottomBar() {
        bottomBar.removeAllViews()
        TAB_LABELS.indices.forEach { index ->
            val item = layoutInflater.inflate(R.layout.item_tab, bottomBar, false)
            item.findViewById<android.widget.ImageView>(R.id.tabIcon).setImageResource(TAB_ICONS[index])
            item.findViewById<TextView>(R.id.tabLabel).setText(TAB_LABELS[index])
            item.setOnClickListener { switchTab(index, true) }
            bottomBar.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        updateTabVisual(currentTab)
    }

    private fun switchTab(index: Int, animate: Boolean) {
        if (currentTab == index) return
        currentTab = index
        val fragment: Fragment = when (index) {
            0 -> HomeFragment()
            1 -> LocationSimFragment()
            2 -> RouteSimFragment()
            3 -> EnvFragment()
            else -> SettingsFragment()
        }
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.fade_in, R.anim.fade_out,
                R.anim.fade_in, R.anim.fade_out
            )
            .replace(R.id.fragmentContainer, fragment, "tab$index")
            .commit()
        updateTabVisual(index)
        ZLog.d(TAG_SCOPE, "switch tab -> $index")
    }

    private fun updateTabVisual(active: Int) {
        for (i in 0 until bottomBar.childCount) {
            val item = bottomBar.getChildAt(i)
            val icon = item.findViewById<android.widget.ImageView>(R.id.tabIcon)
            val label = item.findViewById<TextView>(R.id.tabLabel)
            val activeNow = i == active
            icon.setColorFilter(
                if (activeNow) getColor(R.color.tab_icon_active) else getColor(R.color.tab_icon_normal)
            )
            label.setTextColor(
                if (activeNow) getColor(R.color.tab_icon_active) else getColor(R.color.tab_icon_normal)
            )
        }
    }
}
