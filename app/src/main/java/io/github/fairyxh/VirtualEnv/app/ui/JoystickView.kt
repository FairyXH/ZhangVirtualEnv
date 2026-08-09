package io.github.fairyxh.VirtualEnv.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * 摇杆控件：圆形底座 + 可拖动圆钮，输出归一化方向向量。
 *
 * 回调：
 * - [onVectorChanged] 拖动中（dx/dy ∈ -1..1）
 * - [onReleased] 松手（内部会回调 onVectorChanged(0,0) 后再回调本方法）
 */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 拖动回调；dx/dy ∈ -1..1，y 正方向为屏幕向上。 */
    var onVectorChanged: ((dx: Double, dy: Double) -> Unit)? = null

    /** 松手回调（可选，用于通知后端停止）。 */
    var onReleased: (() -> Unit)? = null

    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f
    private var knobX = 0f
    private var knobY = 0f
    private var touching = false

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x220071E3.toInt()
        style = Paint.Style.FILL
    }
    private val baseBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x660071E3.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0071E3.toInt()
        style = Paint.Style.FILL
    }
    private val knobShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000.toInt()
        style = Paint.Style.FILL
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = minOf(w, h) / 2f - 8f
        knobX = centerX
        knobY = centerY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(centerX, centerY, radius, basePaint)
        canvas.drawCircle(centerX, centerY, radius, baseBorderPaint)
        // 十字辅助线
        canvas.drawLine(centerX - radius * 0.55f, centerY, centerX + radius * 0.55f, centerY, baseBorderPaint)
        canvas.drawLine(centerX, centerY - radius * 0.55f, centerX, centerY + radius * 0.55f, baseBorderPaint)
        val knobRadius = radius * 0.32f
        canvas.drawCircle(knobX + 2f, knobY + 2f, knobRadius, knobShadowPaint)
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                parent?.requestDisallowInterceptTouchEvent(true)
                updateKnob(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (touching) updateKnob(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (touching) {
                    touching = false
                    knobX = centerX
                    knobY = centerY
                    invalidate()
                    onVectorChanged?.invoke(0.0, 0.0)
                    onReleased?.invoke()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateKnob(x: Float, y: Float) {
        val dx = x - centerX
        val dy = y - centerY
        val dist = hypot(dx, dy)
        val max = radius * 0.68f
        val scale = if (dist > max) max / dist else 1f
        knobX = centerX + dx * scale
        knobY = centerY + dy * scale
        invalidate()
        val normDx = (dx * scale / max).coerceIn(-1f, 1f).toDouble()
        // 屏幕 y 向下为正，向上移动摇杆应产生北向位移（dy 正 = 北）
        val normDy = (-dy * scale / max).coerceIn(-1f, 1f).toDouble()
        onVectorChanged?.invoke(normDx, normDy)
    }
}
