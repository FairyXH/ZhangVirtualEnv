package io.github.fairyxh.VirtualEnv.app.ui.glass

import android.annotation.SuppressLint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import io.github.fairyxh.VirtualEnv.util.ZLog

/**
 * 底部玻璃条带透镜折射（RenderEffect + AGSL RuntimeShader）。
 *
 * 与模糊方案不同：这里只对容器底部条带做**清晰的光线折射**（content 采样坐标
 * 按透镜方向偏移），不 blur、不磨砂、不马赛克。折射偏移量很小（默认 3.5dp），
 * 且条带边缘距屏幕边缘远大于偏移量，因此不需要 clamp —— 上次容器级 shader
 * 黑屏与 clamp/多 tap 模糊相关，本实现刻意避开这两点。
 */
class LiquidGlassBarRefraction(
    private val container: View,
    private val bar: View,
    private val capsuleLeftDp: Float = 20f,
    private val capsuleRightDp: Float = 20f,
    private val featherDp: Float = 16f,
    private val refractionDp: Float = 3.5f
) : ViewTreeObserver.OnGlobalLayoutListener {

    private var shader: RuntimeShader? = null
    private var renderEffect: RenderEffect? = null
    private var attached = false

    /** 尝试挂载 GPU 折射；失败返回 false（调用方静默降级为透明底栏）。 */
    @SuppressLint("NewApi")
    fun attach(): Boolean {
        if (attached) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            attachS()
            attached = true
            container.viewTreeObserver.addOnGlobalLayoutListener(this)
            update()
            ZLog.d("UI", "liquid glass bar refraction attached")
            true
        } catch (t: Throwable) {
            ZLog.w("UI", "liquid glass bar refraction unavailable", t)
            false
        }
    }

    @SuppressLint("NewApi")
    fun detach() {
        if (!attached) return
        container.viewTreeObserver.removeOnGlobalLayoutListener(this)
        try {
            container.setRenderEffect(null)
        } catch (_: Throwable) {
        }
        attached = false
    }

    override fun onGlobalLayout() {
        update()
    }

    @SuppressLint("NewApi")
    @RequiresApi(Build.VERSION_CODES.S)
    private fun attachS() {
        val shader = RuntimeShader(REFRACTION_SHADER)
        val density = container.resources.displayMetrics.density
        shader.setFloatUniform("feather", featherDp * density)
        shader.setFloatUniform("refraction", refractionDp * density)
        this.shader = shader
        renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content")
        container.setRenderEffect(renderEffect)
    }

    @SuppressLint("NewApi")
    private fun update() {
        val shader = shader ?: return
        val w = container.width
        val h = container.height
        if (w <= 0 || h <= 0) return
        val density = container.resources.displayMetrics.density
        val containerLeft = container.left
        val containerTop = container.top
        val barLeft = bar.left - containerLeft
        val barTop = bar.top - containerTop
        val barWidth = bar.width
        if (barWidth <= 0) return
        shader.setFloatUniform("size", w.toFloat(), h.toFloat())
        shader.setFloatUniform("bandTop", barTop.toFloat())
        shader.setFloatUniform("bandLeft", barLeft + capsuleLeftDp * density)
        shader.setFloatUniform("bandRight", barLeft + barWidth - capsuleRightDp * density)
        renderEffect?.let { container.setRenderEffect(it) }
        container.invalidate()
    }

    private companion object {
        // 清晰透镜：内容坐标向条带中心方向偏移，形成放大/扭曲感；
        // 条带外原样透出，边缘 smoothstep 羽化避免硬边。无 blur tap，无 clamp。
        const val REFRACTION_SHADER = """
            uniform shader content;

            uniform float2 size;
            uniform float bandTop;
            uniform float bandLeft;
            uniform float bandRight;
            uniform float feather;
            uniform float refraction;

            half4 main(float2 coord) {
                float xFactor = smoothstep(bandLeft - feather, bandLeft, coord.x) *
                                (1.0 - smoothstep(bandRight, bandRight + feather, coord.x));
                float yFactor = smoothstep(bandTop - feather, bandTop, coord.y);
                float bandFactor = xFactor * yFactor;
                if (bandFactor <= 0.001) {
                    return content.eval(coord);
                }

                // 透镜：向条带中心方向轻微挤压内容（refraction 很小，不会越出节点边界）
                float2 center = float2((bandLeft + bandRight) * 0.5, (bandTop + size.y) * 0.5);
                float2 dir = (coord - center) / max(size.x, size.y);
                float2 refracted = coord + dir * refraction * bandFactor;

                return content.eval(refracted);
            }
        """
    }
}
