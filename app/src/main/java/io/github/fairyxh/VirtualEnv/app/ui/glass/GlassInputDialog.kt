package io.github.fairyxh.VirtualEnv.app.ui.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
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

/**
 * 液态玻璃输入弹窗：一个必填主输入（如名称）+ 一个可选次输入（如备注），
 * 用于“保存配置状态 / 编辑配置”等需要用户填写的场景。
 * 窗口/背景处理与 GlassTextDialog 一致，不在 Dialog 内使用 GlassBackdropHost。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GlassInputDialog(
    title: String,
    primaryLabel: String,
    primaryValue: String,
    onPrimaryChange: (String) -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    secondaryLabel: String? = null,
    secondaryValue: String = "",
    onSecondaryChange: ((String) -> Unit)? = null,
    confirmEnabled: Boolean = true,
    cancelText: String = "取消"
) {
    Dialog(onDismissRequest = onDismiss) {
        val backdrop = rememberLayerBackdrop()

        // 毛玻璃弹窗 + 严格居中：窗口铺满全屏，卡片在 Compose 内 align(Center)
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
                    InputDialogContent(
                        title = title,
                        primaryLabel = primaryLabel,
                        primaryValue = primaryValue,
                        onPrimaryChange = onPrimaryChange,
                        confirmText = confirmText,
                        onConfirm = onConfirm,
                        onDismiss = onDismiss,
                        secondaryLabel = secondaryLabel,
                        secondaryValue = secondaryValue,
                        onSecondaryChange = onSecondaryChange,
                        confirmEnabled = confirmEnabled,
                        cancelText = cancelText,
                        backdrop = backdrop
                    )
                }
            }
        }
    }
}

@Composable
private fun InputDialogContent(
    title: String,
    primaryLabel: String,
    primaryValue: String,
    onPrimaryChange: (String) -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    secondaryLabel: String?,
    secondaryValue: String,
    onSecondaryChange: ((String) -> Unit)?,
    confirmEnabled: Boolean,
    cancelText: String,
    backdrop: Backdrop
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
            primaryLabel,
            Modifier.padding(top = 12.dp),
            style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
        )
        GlassField(
            value = primaryValue,
            onValueChange = onPrimaryChange,
            backdrop = backdrop,
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth(),
            placeholder = primaryLabel
        )
        if (secondaryLabel != null) {
            BasicText(
                secondaryLabel,
                Modifier.padding(top = 12.dp),
                style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
            )
            GlassField(
                value = secondaryValue,
                onValueChange = onSecondaryChange ?: {},
                backdrop = backdrop,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth(),
                placeholder = secondaryLabel
            )
        }
        Row(
            Modifier
                .padding(top = 16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GlassButton(
                onClick = onDismiss,
                backdrop = backdrop,
                modifier = Modifier.weight(1f),
                surfaceColor = colors.bgTertiary.copy(alpha = 0.4f)
            ) {
                BasicText(
                    cancelText,
                    Modifier.padding(vertical = 2.dp),
                    style = TextStyle(
                        color = colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
            GlassButton(
                onClick = onConfirm,
                backdrop = backdrop,
                modifier = Modifier.weight(1f),
                tint = colors.accent,
                isInteractive = confirmEnabled
            ) {
                BasicText(
                    confirmText,
                    Modifier.padding(vertical = 2.dp),
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    }
}
