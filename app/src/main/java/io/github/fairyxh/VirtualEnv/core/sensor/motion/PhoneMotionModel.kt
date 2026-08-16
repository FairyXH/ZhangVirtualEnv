package io.github.fairyxh.VirtualEnv.core.sensor.motion

import java.util.concurrent.ThreadLocalRandom

/**
 * 手机姿态模型。
 *
 * 模拟手持设备时自然发生的姿态变化：
 * - 手持：pitch -10°~15°、roll ±5°（基值随低频漂移）
 * - 走路：手腕/手臂摆动引起 pitch/roll 周期变化
 * - 跑步：更大的 pitch/roll 变化幅度
 *
 * 输出：
 * - [gravityVector]：设备坐标系下的重力向量（9.81 m/s²）
 * - [gyroValues]：陀螺仪角速度（rad/s）
 * - [rotationAxis]：步态加速度投影到设备坐标系的旋转方向
 */
class PhoneMotionModel(private val state: VirtualMovementState) {

    companion object {
        private const val DEG2RAD = Math.PI / 180.0

        /** 手持姿态基值（度）。 */
        private const val BASE_PITCH_DEG = 5.0
        private const val BASE_ROLL_DEG = 0.0

        /** 低频姿态漂移幅度（度）。 */
        private const val DRIFT_PITCH_AMP = 6.0
        private const val DRIFT_ROLL_AMP = 3.0

        /** 走路摆臂幅度（度）。 */
        private const val WALK_SWING_AMP = 7.0
        private const val WALK_ROLL_AMP = 3.0

        /** 跑步摆臂幅度（度）。 */
        private const val RUN_SWING_AMP = 14.0
        private const val RUN_ROLL_AMP = 6.0
    }

    private var tSec = 0.0
    private var pitchDeg = BASE_PITCH_DEG
    private var rollDeg = BASE_ROLL_DEG

    /** 推进姿态（每次 tick 调用）。 */
    fun update(dtSec: Double) {
        tSec += dtSec
        val swingAmp = when (state.activity) {
            ActivityMode.STATIONARY -> 0.5
            ActivityMode.RUN -> RUN_SWING_AMP
            else -> WALK_SWING_AMP
        }
        val rollAmp = when (state.activity) {
            ActivityMode.STATIONARY -> 0.5
            ActivityMode.RUN -> RUN_ROLL_AMP
            else -> WALK_ROLL_AMP
        }
        val freq = if (state.stepFrequency > 0) state.stepFrequency / 60.0 else 0.0
        // 低频姿态漂移（0.1~0.3Hz）+ 步频摆臂
        pitchDeg = BASE_PITCH_DEG +
            DRIFT_PITCH_AMP * Math.sin(2.0 * Math.PI * 0.13 * tSec) +
            DRIFT_PITCH_AMP * 0.5 * Math.sin(2.0 * Math.PI * 0.07 * tSec) +
            swingAmp * Math.sin(2.0 * Math.PI * freq * tSec)
        rollDeg = BASE_ROLL_DEG +
            DRIFT_ROLL_AMP * Math.sin(2.0 * Math.PI * 0.11 * tSec + 0.7) +
            rollAmp * Math.sin(2.0 * Math.PI * freq * tSec + 1.3)
    }

    /** 设备坐标系重力向量（m/s²）：绕 X(pitch)、Y(roll) 旋转的 (0,0,9.81)。 */
    fun gravityVector(): FloatArray {
        val p = pitchDeg * DEG2RAD
        val r = rollDeg * DEG2RAD
        val g = 9.81f
        // Rx(p) * Ry(r) * (0,0,g)
        val x = g * Math.sin(r).toFloat()
        val y = -g * Math.sin(p).toFloat()
        val z = (g * Math.cos(p) * Math.cos(r)).toFloat()
        return floatArrayOf(x, y, z)
    }

    /** 设备坐标系陀螺仪角速度（rad/s）：姿态变化率 + 摆臂 + 低频漂移。 */
    fun gyroValues(): FloatArray {
        val driftP = 0.02 * Math.sin(2.0 * Math.PI * 0.13 * tSec)
        val driftR = 0.015 * Math.sin(2.0 * Math.PI * 0.11 * tSec + 0.7)
        val swingAmp = when (state.activity) {
            ActivityMode.STATIONARY -> 0.02
            ActivityMode.RUN -> 0.9
            else -> 0.45
        }
        val freq = if (state.stepFrequency > 0) state.stepFrequency / 60.0 else 0.0
        val pitchRate = driftP + swingAmp * Math.cos(2.0 * Math.PI * freq * tSec)
        val rollRate = driftR + swingAmp * 0.6 * Math.cos(2.0 * Math.PI * freq * tSec + 1.3)
        val yawRate = 0.01 * Math.sin(2.0 * Math.PI * 0.09 * tSec)
        return floatArrayOf(
            pitchRate.toFloat(),
            rollRate.toFloat(),
            yawRate.toFloat(),
        )
    }

    /** 当前姿态（度，供日志/状态）。 */
    fun attitudeDegrees(): Pair<Double, Double> = pitchDeg to rollDeg

    /** 步态加速度投影方向（设备坐标归一化），随 pitch/roll 变化。 */
    fun gaitDirection(): FloatArray {
        val p = pitchDeg * DEG2RAD
        val r = rollDeg * DEG2RAD
        // 重力方向上的反方向（设备向上）作为步态主方向，叠加轻微水平
        val x = (-Math.sin(r)).toFloat()
        val y = Math.sin(p).toFloat()
        val z = (-Math.cos(p) * Math.cos(r)).toFloat()
        return floatArrayOf(x, y, z)
    }
}
