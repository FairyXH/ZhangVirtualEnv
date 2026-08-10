package io.github.fairyxh.VirtualEnv.app.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 页面级 Backdrop 宿主。
 *
 * 在内容下层绘制一张动态渐变“背景画布”，并通过 [LayerBackdrop] 把该层导出给
 * 所有 GlassCard / GlassButton / GlassBottomTabs 采样，实现真正的 Backdrop blur
 * 与透镜折射（而非普通 alpha 透明）。
 */
@Composable
fun GlassBackdropHost(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(backdrop: LayerBackdrop) -> Unit
) {
    val colors = glassColors()
    val backdrop = rememberLayerBackdrop()

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .layerBackdrop(backdrop)
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        Brush.verticalGradient(
                            0f to colors.bgPrimary,
                            0.5f to colors.bgPrimary,
                            1f to colors.bgTertiary.copy(alpha = 0.55f)
                        )
                    )
                    // 装饰性光斑：让玻璃折射有真实内容可采样（iOS 26 壁纸下的光感）
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                colors.accent.copy(alpha = 0.10f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.22f, size.height * 0.18f),
                            radius = size.maxDimension * 0.55f
                        ),
                        radius = size.maxDimension * 0.55f,
                        center = Offset(size.width * 0.22f, size.height * 0.18f)
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                colors.success.copy(alpha = 0.07f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.82f, size.height * 0.62f),
                            radius = size.maxDimension * 0.5f
                        ),
                        radius = size.maxDimension * 0.5f,
                        center = Offset(size.width * 0.82f, size.height * 0.62f)
                    )
                }
        )

        content(backdrop)
    }
}
