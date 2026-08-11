package io.github.fairyxh.VirtualEnv.app.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.github.fairyxh.VirtualEnv.R

/**
 * 液态玻璃详情弹窗：替代原生 AlertDialog。
 * 注意：不要在 Dialog 里用 GlassBackdropHost —— 它的背景层会 fillMaxSize 铺满
 * 整个 Dialog 窗口，形成“非常大的矩形白底”。这里只创建局部 backdrop 层
 * （透明，仅限卡片区域），玻璃感来自卡片自身的透镜/高光/内阴影。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GlassTextDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        val backdrop = rememberLayerBackdrop()

        // 毛玻璃弹窗 + 严格居中：窗口铺满全屏，卡片在 Compose 内 align(Center)，
        // 保证四边到屏幕边缘距离相等（不依赖窗口 gravity/wrap 计算）
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(dialogWindow) {
            val w = dialogWindow ?: return@LaunchedEffect
            w.setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT
            )
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                w.setBackgroundBlurRadius(40)
            }
        }
        DisposableEffect(dialogWindow) {
            onDispose {
                val w = dialogWindow ?: return@onDispose
                w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    w.setBackgroundBlurRadius(0)
                }
            }
        }

        // 窗口铺满全屏后，Compose Dialog 内容区仍默认避开系统栏
        // （内容高度 = 屏幕 − 状态栏 − 导航栏），align(Center) 只相对内容区居中。
        // 用系统栏 inset 把卡片中心补偿回屏幕中心，保证四边到屏幕边缘距离相等。
        val density = LocalDensity.current
        val sysBars = WindowInsets.systemBars.asPaddingValues()
        val centerShift = with(density) {
            (sysBars.calculateTopPadding() + sysBars.calculateBottomPadding()) / 2
        }
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .offset(y = centerShift)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                // 透明 backdrop 采样层：只占卡片区域，不绘制任何底色
                Box(
                    Modifier
                        .fillMaxSize()
                        .layerBackdrop(backdrop)
                )
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 22.dp,
                    containerColor = glassColors().bgSecondary.copy(alpha = 0.62f),
                    contentPadding = 0.dp
                ) {
                    GlassDialogContent(title, text, backdrop, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun GlassDialogContent(
    title: String,
    text: String,
    backdrop: Backdrop,
    onDismiss: () -> Unit
) {
    val colors = glassColors()
    Column(Modifier.padding(20.dp)) {
        BasicText(
            title,
            style = TextStyle(
                color = colors.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
        BasicText(
            text,
            Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            style = TextStyle(
                color = colors.textSecondary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
        )
        GlassButton(
            onClick = onDismiss,
            backdrop = backdrop,
            modifier = Modifier
                .padding(top = 16.dp)
                .fillMaxWidth(),
            surfaceColor = colors.bgTertiary.copy(alpha = 0.4f)
        ) {
            BasicText(
                androidx.compose.ui.res.stringResource(R.string.dialog_close),
                Modifier.padding(vertical = 2.dp),
                style = TextStyle(
                    color = colors.accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}
