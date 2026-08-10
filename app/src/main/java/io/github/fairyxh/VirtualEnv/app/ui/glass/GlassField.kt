package io.github.fairyxh.VirtualEnv.app.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop

/**
 * Liquid Glass 输入框：玻璃背板 + 无边框文字输入，placeholder 在空态显示。
 */
@Composable
fun GlassField(
    value: String,
    onValueChange: (String) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    textStyle: TextStyle = TextStyle(fontSize = 15.sp),
    cornerRadius: Dp = 12.dp,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    val colors = glassColors()

    GlassCard(
        backdrop = backdrop,
        modifier = modifier,
        cornerRadius = cornerRadius,
        blurRadius = 12f,
        refractionHeight = 12f,
        refractionAmount = 12f,
        containerColor = colors.bgSecondary.copy(alpha = 0.45f),
        contentPadding = 16.dp
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 48.dp)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterStart),
                textStyle = textStyle.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                singleLine = singleLine,
                visualTransformation = visualTransformation,
                keyboardOptions = keyboardOptions,
                decorationBox = { innerTextField ->
                    Box(Modifier.fillMaxWidth()) {
                        if (value.isEmpty()) {
                            androidx.compose.foundation.text.BasicText(
                                text = placeholder,
                                style = textStyle.copy(color = colors.textTertiary)
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
    }
}
