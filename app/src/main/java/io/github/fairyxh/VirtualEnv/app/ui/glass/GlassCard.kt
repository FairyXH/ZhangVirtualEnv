package io.github.fairyxh.VirtualEnv.app.ui.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.RoundedRectangle

/**
 * Liquid Glass 卡片。
 *
 * 与普通半透明卡片不同：背板是 RuntimeShader 透镜折射 + RenderEffect 模糊 +
 * vibrancy 色彩增强，边缘带 Fresnel 高光（Highlight.Default）与内阴影，
 * 按压时通过 [InteractiveHighlight] 产生随手指移动的动态高光。
 */
@Composable
fun GlassCard(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    cornerRadius: Dp = 18.dp,
    blurRadius: Float = 24f,
    refractionHeight: Float = 32f,
    refractionAmount: Float = 32f,
    containerColor: Color = Color.Unspecified,
    contentPadding: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(animationScope = animationScope)
    }

    Box(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedRectangle(cornerRadius) },
                effects = {
                    vibrancy()
                    blur(blurRadius)
                    lens(refractionHeight, refractionAmount)
                },
                highlight = {
                    Highlight.Default.copy(alpha = 0.9f)
                },
                shadow = {
                    Shadow(
                        radius = 16f.dp,
                        color = Color.Black.copy(alpha = 0.08f)
                    )
                },
                innerShadow = {
                    InnerShadow(
                        radius = 16f.dp,
                        offset = androidx.compose.ui.unit.DpOffset(0f.dp, 8f.dp),
                        color = Color.Black.copy(alpha = 0.12f)
                    )
                },
                layerBlock = {
                    val progress = interactiveHighlight.pressProgress
                    val scale = androidx.compose.ui.util.lerp(1f, 1f + 6f.dp.toPx() / size.height, progress)
                    scaleX = scale
                    scaleY = scale
                },
                onDrawSurface = {
                    if (containerColor.isSpecified) {
                        drawRect(containerColor)
                    }
                }
            )
            .then(
                if (onClick != null) {
                    Modifier
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            role = Role.Button,
                            onClick = onClick
                        )
                        .then(interactiveHighlight.modifier)
                        .then(interactiveHighlight.gestureModifier)
                } else {
                    Modifier
                }
            )
            .let { if (contentPadding > 0.dp) it.padding(contentPadding) else it },
        content = content
    )
}
