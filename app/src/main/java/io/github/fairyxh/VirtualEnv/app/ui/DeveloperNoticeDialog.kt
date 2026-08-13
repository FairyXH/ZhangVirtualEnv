package io.github.fairyxh.VirtualEnv.app.ui

import android.os.Build
import android.view.WindowManager
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassButton
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassCard
import io.github.fairyxh.VirtualEnv.app.ui.glass.glassColors

/**
 * 开发者用途声明弹窗：首次启动必须主动确认后才能进入主界面。
 *
 * 弹窗不可通过返回键 / 点击外部关闭；「查看项目说明」在弹窗内展开项目说明与免责声明，
 * 用户点击「同意并继续」后由调用方保存确认状态并关闭。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DeveloperNoticeDialog(
    onAgree: () -> Unit
) {
    var showDetails by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = { /* 必须主动点击「同意并继续」 */ },
    ) {
        val backdrop = rememberLayerBackdrop()

        // 毛玻璃弹窗 + 严格居中：窗口铺满全屏，卡片在 Compose 内 align(Center)，
        // 保证四边到屏幕边缘距离相等（不依赖窗口 gravity/wrap 计算）
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(dialogWindow) {
            val w = dialogWindow ?: return@LaunchedEffect
            w.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            if (Build.VERSION.SDK_INT >= 31) {
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                w.setBackgroundBlurRadius(40)
            }
        }
        DisposableEffect(dialogWindow) {
            onDispose {
                val w = dialogWindow ?: return@onDispose
                w.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                if (Build.VERSION.SDK_INT >= 31) {
                    w.setBackgroundBlurRadius(0)
                }
            }
        }

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
                    if (showDetails) {
                        NoticeDetailsContent(
                            backdrop = backdrop,
                            onBack = { showDetails = false },
                            onAgree = onAgree
                        )
                    } else {
                        NoticeMainContent(
                            backdrop = backdrop,
                            onViewInfo = { showDetails = true },
                            onAgree = onAgree
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeMainContent(
    backdrop: Backdrop,
    onViewInfo: () -> Unit,
    onAgree: () -> Unit
) {
    val colors = glassColors()
    Column(Modifier.padding(20.dp)) {
        BasicText(
            stringResource(R.string.notice_title),
            style = TextStyle(
                color = colors.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
        BasicText(
            stringResource(R.string.notice_body),
            Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState()),
            style = TextStyle(
                color = colors.textSecondary,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
        )
        GlassButton(
            onClick = onAgree,
            backdrop = backdrop,
            modifier = Modifier
                .padding(top = 16.dp)
                .fillMaxWidth(),
            tint = colors.accent
        ) {
            BasicText(
                stringResource(R.string.notice_agree_continue),
                Modifier.padding(vertical = 2.dp),
                style = TextStyle(
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        GlassButton(
            onClick = onViewInfo,
            backdrop = backdrop,
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(),
            surfaceColor = colors.bgTertiary.copy(alpha = 0.4f)
        ) {
            BasicText(
                stringResource(R.string.notice_view_info),
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

@Composable
private fun NoticeDetailsContent(
    backdrop: Backdrop,
    onBack: () -> Unit,
    onAgree: () -> Unit
) {
    val colors = glassColors()
    Column(Modifier.padding(20.dp)) {
        BasicText(
            stringResource(R.string.notice_details_title),
            style = TextStyle(
                color = colors.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
        BasicText(
            stringResource(R.string.notice_details_body),
            Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState()),
            style = TextStyle(
                color = colors.textSecondary,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
        )
        GlassButton(
            onClick = onAgree,
            backdrop = backdrop,
            modifier = Modifier
                .padding(top = 16.dp)
                .fillMaxWidth(),
            tint = colors.accent
        ) {
            BasicText(
                stringResource(R.string.notice_agree_continue),
                Modifier.padding(vertical = 2.dp),
                style = TextStyle(
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        GlassButton(
            onClick = onBack,
            backdrop = backdrop,
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(),
            surfaceColor = colors.bgTertiary.copy(alpha = 0.4f)
        ) {
            BasicText(
                stringResource(R.string.notice_back),
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
