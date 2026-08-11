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
 * 底部玻璃条带实时模糊（RenderNode + AGSL RuntimeShader）。
 *
 * 底栏是独立 ComposeView，无法跨组合与 Fragment 页面共享 backdrop 采样层，且
 * ColorOS 移除了 View#setBackgroundBlurRadius（反射也拿不到）。因此这里直接对
 * Fragment 容器的 RenderNode 挂 RuntimeShader：仅玻璃胶囊覆盖的条带被实时模糊 +
 * 轻微折射，条带上/左/右边缘羽化过渡，不会出现平切直线。
 *
 * 参数：胶囊相对底栏 View 的左/右内边距（默认 16dp 外边距 + 4dp 内边距）。
 */
class LiquidGlassBarBlur(
    private val container: View,
    private val bar: View,
    private val capsuleLeftDp: Float = 20f,
    private val capsuleRightDp: Float = 20f,
    private val featherDp: Float = 12f,
    private val blurRadiusDp: Float = 28f,
    private val refractionDp: Float = 4f
) : ViewTreeObserver.OnGlobalLayoutListener {

    private var shader: RuntimeShader? = null
    private var renderEffect: RenderEffect? = null
    private var attached = false

    /** 尝试挂载 GPU 模糊；失败返回 false（调用方可走系统 blur 兜底）。 */
    @SuppressLint("NewApi")
    fun attach(): Boolean {
        if (attached) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            attachS()
            attached = true
            container.viewTreeObserver.addOnGlobalLayoutListener(this)
            update()
            ZLog.d("UI", "liquid glass band blur attached")
            true
        } catch (t: Throwable) {
            ZLog.w("UI", "liquid glass band blur unavailable", t)
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
        val shader = RuntimeShader(BAND_BLUR_SHADER)
        val density = container.resources.displayMetrics.density
        shader.setFloatUniform("feather", featherDp * density)
        shader.setFloatUniform("blurRadius", blurRadiusDp * density)
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
        // 重新挂载以刷新 uniform（RenderEffect 引用同一 shader 实例）
        renderEffect?.let { container.setRenderEffect(it) }
        container.invalidate()
    }

    private companion object {
        // 内容采样：content = 容器 RenderNode 输出。模糊仅作用于玻璃胶囊条带，
        // 条带外原样透出；上/左/右边缘 smoothstep 羽化，避免“一刀切”的平直边界。
        const val BAND_BLUR_SHADER = """
            uniform shader content;

            uniform float2 size;
            uniform float bandTop;
            uniform float bandLeft;
            uniform float bandRight;
            uniform float feather;
            uniform float blurRadius;
            uniform float refraction;

            half4 blurAt(float2 coord) {
                float r = blurRadius;
                half4 color = content.eval(coord) * 0.227027;
                color += (content.eval(coord + float2(1.3846, 0.0) * r) +
                          content.eval(coord - float2(1.3846, 0.0) * r)) * 0.3162162;
                color += (content.eval(coord + float2(3.2307, 0.0) * r) +
                          content.eval(coord - float2(3.2307, 0.0) * r)) * 0.0702703;
                color += (content.eval(coord + float2(0.0, 1.3846) * r) +
                          content.eval(coord - float2(0.0, 1.3846) * r)) * 0.3162162;
                color += (content.eval(coord + float2(0.0, 3.2307) * r) +
                          content.eval(coord - float2(0.0, 3.2307) * r)) * 0.0702703;
                return color;
            }

            half4 main(float2 coord) {
                float xFactor = smoothstep(bandLeft - feather, bandLeft, coord.x) *
                                (1.0 - smoothstep(bandRight, bandRight + feather, coord.x));
                float yFactor = smoothstep(bandTop - feather, bandTop, coord.y);
                float bandFactor = xFactor * yFactor;
                if (bandFactor <= 0.001) {
                    return content.eval(coord);
                }

                // 轻微折射：条带内容向玻璃中心轻微挤压，形成厚度感
                float2 center = float2((bandLeft + bandRight) * 0.5, bandTop + size.y * 0.25);
                float2 dir = (coord - center) / max(size.x, size.y);
                float2 refracted = coord + dir * refraction * bandFactor;

                half4 blurred = blurAt(refracted);
                half4 base = content.eval(coord);
                return mix(base, blurred, bandFactor);
            }
        """
    }
}
