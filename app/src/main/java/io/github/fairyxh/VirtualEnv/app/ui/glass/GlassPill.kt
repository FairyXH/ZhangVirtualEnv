package io.github.fairyxh.VirtualEnv.app.ui.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule

/**
 * Liquid Glass 胶囊（次级操作/选项卡）。
 *
 * 用于页面内的分段切换（如快照/录像）与地图工具按钮；
 * 选中态通过 [tint] 叠加强调色，玻璃折射保持活动。
 */
@Composable
fun GlassPill(
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    tint: Color = Color.Unspecified,
    containerColor: Color = Color.Unspecified,
    height: Dp = 40.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(animationScope = animationScope)
    }
    val colors = glassColors()

    Box(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy()
                    blur(6f.dp.toPx())
                    lens(10f.dp.toPx(), 16f.dp.toPx())
                },
                highlight = {
                    if (selected) Highlight.Default.copy(alpha = 0.7f) else null
                },
                layerBlock = {
                    val progress = interactiveHighlight.pressProgress
                    val scale = androidx.compose.ui.util.lerp(1f, 1f + 4f.dp.toPx() / size.height, progress)
                    scaleX = scale
                    scaleY = scale
                },
                onDrawSurface = {
                    val base =
                        if (containerColor.isSpecified) containerColor
                        else if (selected) colors.accent.copy(alpha = 0.82f)
                        else colors.bgSecondary.copy(alpha = 0.55f)
                    drawRect(base)
                    if (tint.isSpecified && selected) {
                        drawRect(tint.copy(alpha = 0.28f))
                    }
                }
            )
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .then(interactiveHighlight.modifier)
            .then(interactiveHighlight.gestureModifier)
            .height(height)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/**
 * 水平分段选择器（快照/录像等两段或更多段）。
 */
@Composable
fun GlassSegmented(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    selectedIndex: () -> Int,
    onSelect: (Int) -> Unit,
    count: Int,
    itemContent: @Composable (index: Int) -> Unit
) {
    androidx.compose.foundation.layout.Row(
        modifier.height(40.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { index ->
            GlassPill(
                onClick = { onSelect(index) },
                backdrop = backdrop,
                selected = selectedIndex() == index,
                height = 40.dp,
                modifier = Modifier.weight(1f)
            ) {
                Box(Modifier.align(Alignment.Center)) {
                    itemContent(index)
                }
            }
        }
    }
}
