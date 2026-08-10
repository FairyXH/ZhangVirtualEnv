package io.github.fairyxh.VirtualEnv.app.ui.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Liquid Glass 风格复选钮：玻璃圆角方框 + 选中态勾形（非 Material Checkbox）。
 */
@Composable
fun GlassCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = glassColors()
    Box(
        modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Checkbox,
                onClick = { onCheckedChange(!checked) }
            )
            .size(22.dp)
            .drawBehind {
                val stroke = 1.5.dp.toPx()
                val radius = min(size.width, size.height) / 2f
                drawRoundRect(
                    color = if (checked) colors.accent else colors.textTertiary.copy(alpha = 0.55f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
                )
                if (checked) {
                    // 勾形
                    val w = size.width
                    val h = size.height
                    val check = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.26f, h * 0.52f)
                        lineTo(w * 0.44f, h * 0.68f)
                        lineTo(w * 0.76f, h * 0.34f)
                    }
                    drawPath(
                        path = check,
                        color = Color.White,
                        style = Stroke(
                            width = 2.dp.toPx(),
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }
    )
}
