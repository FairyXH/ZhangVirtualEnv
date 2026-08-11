package io.github.fairyxh.VirtualEnv.app.ui.glass

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.kyant.backdrop.Backdrop
import io.github.fairyxh.VirtualEnv.R

/**
 * 液态玻璃详情弹窗：替代原生 AlertDialog，背景用 GlassBackdropHost 的
 * 渐变+光斑层采样，玻璃卡片带透镜/高光/内阴影，文本区可滚动。
 */
@Composable
fun GlassTextDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        GlassBackdropHost(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) { backdrop ->
            GlassCard(
                backdrop = backdrop,
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 22.dp,
                containerColor = glassColors().bgSecondary.copy(alpha = 0.9f),
                contentPadding = 0.dp
            ) {
                GlassDialogContent(title, text, backdrop, onDismiss)
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
