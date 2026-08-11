package io.github.fairyxh.VirtualEnv.app.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 页面级 Backdrop 宿主（黑底 + 可选壁纸）。
 *
 * 默认不绘制背景（窗口/根视图纯黑，卡片直接浮在黑色之上）；设置里开启
 * “用桌面背景”后，读取壁纸位图全屏自绘（绕过 ColorOS FLAG_SHOW_WALLPAPER
 * 曲面左缘黑遮罩），并叠暗化层保证浅色文字/玻璃卡片对比度 + 极淡雾化
 * 模拟轻微磨砂。通过 [LayerBackdrop] 把背景层导出给所有 GlassCard /
 * GlassButton / GlassBottomTabs 采样。
 */
@Composable
fun GlassBackdropHost(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(backdrop: LayerBackdrop) -> Unit
) {
    val backdrop = rememberLayerBackdrop()
    // 主题在 Composable 作用域读取，drawBehind 内不能调用 @Composable
    val darkBackground = androidx.compose.foundation.isSystemInDarkTheme()

    Box(modifier.fillMaxSize()) {
        // 背景层：默认透明（黑底透出）；壁纸模式全屏自绘壁纸
        Box(
            Modifier
                .layerBackdrop(backdrop)
                .fillMaxSize()
                .drawBehind {
                    if (AppBackground.useWallpaper) {
                        val wallpaper = AppBackground.wallpaperBitmap
                        if (wallpaper != null) {
                            // bitmap 裁剪铺满全屏（cover）：无系统左缘黑遮罩
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
                        }
                        // 壁纸基础上：暗化保证对比度 + 极淡雾化模拟磨砂
                        drawRect(Color.Black.copy(alpha = 0.25f))
                        drawRect(Color.White.copy(alpha = 0.03f))
                    } else {
                        // 关闭壁纸：按系统主题铺背景色（浅色浅灰 / 深色纯黑），
                        // 与 MainActivity 的 window/root 背景一致，避免边缘露出异色
                        drawRect(
                            if (darkBackground) Color(0xFF000000) else Color(0xFFF2F2F7)
                        )
                    }
                }
        )

        // 内容层：避开状态栏/导航栏（insets 缓存来自 AppInsets.attachConsume），
        // 背景层仍全屏
        Box(
            Modifier
                .fillMaxSize()
                .padding(AppInsets.systemBars.asPaddingValues())
        ) {
            content(backdrop)
        }
    }
}
