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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 页面级 Backdrop 宿主。
 *
 * 在内容下层绘制背景画布（默认渐变；设置里开启后可改用桌面壁纸），
 * 并通过 [LayerBackdrop] 把该层导出给所有 GlassCard / GlassButton /
 * GlassBottomTabs 采样，实现真正的 Backdrop blur 与透镜折射
 * （而非普通 alpha 透明）。
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
                    val wallpaper = AppBackground.wallpaperBitmap
                    if (AppBackground.useWallpaper && wallpaper != null) {
                        // 壁纸模式：裁剪铺满全屏，玻璃卡片仍可采样到真实背景内容
                        val scale = max(
                            size.width / wallpaper.width,
                            size.height / wallpaper.height
                        )
                        val dstW = wallpaper.width * scale
                        val dstH = wallpaper.height * scale
                        drawImage(
                            image = wallpaper,
                            dstOffset = IntOffset(
                                ((size.width - dstW) / 2f).roundToInt(),
                                ((size.height - dstH) / 2f).roundToInt()
                            ),
                            dstSize = IntSize(dstW.roundToInt(), dstH.roundToInt())
                        )
                    } else {
                        drawRect(
                            Brush.verticalGradient(
                                0f to colors.bgPrimary,
                                0.5f to colors.bgPrimary,
                                1f to colors.bgTertiary.copy(alpha = 0.55f)
                            )
                        )
                    }
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
