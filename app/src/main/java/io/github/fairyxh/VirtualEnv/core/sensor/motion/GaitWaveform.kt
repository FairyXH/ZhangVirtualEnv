package io.github.fairyxh.VirtualEnv.core.sensor.motion

import java.util.concurrent.ThreadLocalRandom

/**
 * 步态波形模型（每步四相）：
 *
 * 着地冲击 → 上升 → 摆动 → 恢复。
 *
 * gait(t) 归一化 0..1，作为加速度垂直方向/陀螺仪摆动的公共基波。
 * 所有传感器生成器调用同一个 [gait]，保证步频与波形相位一致。
 */
object GaitWaveform {

    /** 着地冲击区间（相位 0..0.15）：高斜率上升。 */
    private const val IMPACT_END = 0.15

    /** 上升区间（0.15..0.35）：达到峰值。 */
    private const val RISE_END = 0.35

    /** 摆动区间（0.35..0.70）：峰值后下降。 */
    private const val SWING_END = 0.70

    /** 恢复区间（0.70..1.0）：回落到下次着地。 */
    private const val RECOVERY_END = 1.0

    /**
     * 计算步态相位 [phase]（0..1）对应的归一化垂直加速度（0..1）。
     * 使用分段正弦，保证连续性；[phase] 会被安全回绕。
     */
    fun gait(phase: Double): Double {
        val p = ((phase % 1.0) + 1.0) % 1.0
        return when {
            p < IMPACT_END -> 0.5 - 0.5 * Math.cos(Math.PI * p / IMPACT_END) // 0 → 1
            p < RISE_END -> 1.0 // 保持峰值
            p < SWING_END -> 0.5 + 0.5 * Math.cos(Math.PI * (p - RISE_END) / (SWING_END - RISE_END)) // 1 → 0
            p < RECOVERY_END -> {
                // 恢复：轻微下凹后回 0
                val q = (p - SWING_END) / (RECOVERY_END - SWING_END)
                0.0 - 0.2 * Math.sin(Math.PI * q)
            }
            else -> 0.0
        }
    }

    /** 垂直加速度波形（m/s²）：gait 映射到幅度。 */
    fun verticalAccel(phase: Double, amplitude: Double): Double {
        return amplitude * (gait(phase) * 2.0 - 1.0)
    }

    /** 水平晃动（m/s²）：与垂直波形成 90° 相位差，幅度约为垂直的 20%。 */
    fun horizontalAccel(phase: Double, amplitude: Double): Double {
        return amplitude * 0.2 * Math.sin(2.0 * Math.PI * phase)
    }

    /** 陀螺仪摆动（rad/s）：步频基频正弦 + 次谐波。 */
    fun gyroSwing(phase: Double, amplitude: Double): Double {
        return amplitude * (
            Math.sin(2.0 * Math.PI * phase) * 0.7 +
                Math.sin(2.0 * Math.PI * phase * 2.0) * 0.3
            )
    }

    /** 轻噪声（m/s² 或 rad/s）：±[scale]，可选。 */
    fun noise(scale: Double): Double {
        return ThreadLocalRandom.current().nextDouble(-scale, scale)
    }
}
