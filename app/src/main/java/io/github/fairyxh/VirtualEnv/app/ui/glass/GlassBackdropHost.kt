package io.github.fairyxh.VirtualEnv.app.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 页面级 Backdrop 宿主（黑底阶段）。
 *
 * 不绘制任何背景：窗口/根视图为纯黑色，卡片直接浮在黑色之上。
 * 保留 LayerBackdrop 导出层（透明），供 GlassCard / GlassButton /
 * GlassBottomTabs 采样自身高光/内阴影效果。
 */
@Composable
fun GlassBackdropHost(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(backdrop: LayerBackdrop) -> Unit
) {
    val backdrop = rememberLayerBackdrop()

    Box(modifier.fillMaxSize()) {
        // 背景层：完全透明（不绘制任何底色）
        Box(
            Modifier
                .layerBackdrop(backdrop)
                .fillMaxSize()
        )

        // 内容层：避开状态栏/导航栏（insets 缓存来自 AppInsets.attachConsume）
        Box(
            Modifier
                .fillMaxSize()
                .padding(AppInsets.systemBars.asPaddingValues())
        ) {
            content(backdrop)
        }
    }
}
