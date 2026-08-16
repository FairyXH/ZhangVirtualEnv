package io.github.fairyxh.VirtualEnv.core.sensor.motion

/**
 * 传感器生成器：全部从同一个 [VirtualMovementState]（步态相位/步数）
 * 和 [PhoneMotionModel]（姿态）取数。
 *
 * 设计原则：
 * - 不独立维护步频/步数：只读 state；
 * - 不使用纯随机：波形 = 步态基波 + 低频漂移 + 小幅噪声；
 * - STEP_COUNTER / STEP_DETECTOR / ACCEL / GRAVITY / GYRO 彼此数学一致。
 */
class SensorGenerators(private val state: VirtualMovementState) {

    /** 加速度（m/s²）：重力 + 步态幅度 × 方向 + 噪声。 */
    fun accelerometer(phone: PhoneMotionModel, profile: MotionProfile): FloatArray {
        val amp = profile.effectiveAmplitude
        val g = phone.gravityVector()
        val dir = phone.gaitDirection()
        val noiseScale = if (profile.randomNoise) 0.05f else 0f
        val vertical = GaitWaveform.verticalAccel(state.stepPhase, amp.toDouble()).toFloat()
        val horiz = GaitWaveform.horizontalAccel(state.stepPhase, amp.toDouble()).toFloat()
        return floatArrayOf(
            g[0] + dir[0] * vertical + horiz + GaitWaveform.noise(noiseScale.toDouble()).toFloat(),
            g[1] + dir[1] * vertical + horiz * 0.5f + GaitWaveform.noise(noiseScale.toDouble()).toFloat(),
            g[2] + dir[2] * vertical + GaitWaveform.noise(noiseScale.toDouble()).toFloat(),
        )
    }

    /** 线性加速度（m/s²）：加速度 - 重力。 */
    fun linearAcceleration(phone: PhoneMotionModel, profile: MotionProfile): FloatArray {
        val accel = accelerometer(phone, profile)
        val g = phone.gravityVector()
        return floatArrayOf(accel[0] - g[0], accel[1] - g[1], accel[2] - g[2])
    }

    /** 重力（m/s²）：设备坐标系重力向量 + 极小噪声。 */
    fun gravity(phone: PhoneMotionModel, profile: MotionProfile): FloatArray {
        val g = phone.gravityVector()
        val noiseScale = if (profile.randomNoise) 0.01f else 0f
        return floatArrayOf(
            g[0] + GaitWaveform.noise(noiseScale.toDouble()).toFloat(),
            g[1] + GaitWaveform.noise(noiseScale.toDouble()).toFloat(),
            g[2] + GaitWaveform.noise(noiseScale.toDouble()).toFloat(),
        )
    }

    /** 陀螺仪（rad/s）：姿态变化率 + 摆臂 + 低频漂移。 */
    fun gyroscope(phone: PhoneMotionModel, profile: MotionProfile): FloatArray {
        val g = phone.gyroValues()
        if (!profile.randomNoise) return g
        return floatArrayOf(
            g[0] + GaitWaveform.noise(0.01).toFloat(),
            g[1] + GaitWaveform.noise(0.01).toFloat(),
            g[2] + GaitWaveform.noise(0.005).toFloat(),
        )
    }

    /** 单步检测（每次相位跨步产生 1.0）。 */
    fun stepDetector(): FloatArray = floatArrayOf(1f)

    /** 累计步数。 */
    fun stepCounter(): FloatArray = floatArrayOf(state.stepCount.toFloat())
}
