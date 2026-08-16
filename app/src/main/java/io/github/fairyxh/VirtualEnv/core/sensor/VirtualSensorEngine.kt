package io.github.fairyxh.VirtualEnv.core.sensor

import android.os.SystemClock
import java.util.concurrent.ThreadLocalRandom

/**
 * 虚拟传感器数据引擎（与具体后端解耦）。
 *
 * 负责：
 * - 步频算法（steps/min → 事件周期）
 * - 步数累计（单调递增，跨注入共享）
 * - 事件时间戳生成
 * - 运动状态与随机噪声
 *
 * 数据按传感器类型生成，供 [SystemSensorBackend]（全局注入）使用；
 * Legacy App Hook 后端保留原有 EnvStateCache 逻辑，不经过本引擎。
 */
class VirtualSensorEngine(private val configProvider: () -> VirtualSensorConfig?) {

    companion object {
        private const val TAG_SCOPE = "SensorEngine"
        private const val MIN_INJECT_PERIOD_MS = 100L
        private const val DEFAULT_INJECT_PERIOD_MS = 200L

        /** STEP_COUNTER / STEP_DETECTOR 的注入周期（ms）。 */
        fun stepInjectPeriodMs(frequency: Int): Long {
            if (frequency <= 0) return DEFAULT_INJECT_PERIOD_MS
            // 每步之间注入 1 次；上限 5Hz，下限避免注入风暴
            return (60000L / frequency.coerceAtLeast(1))
                .coerceIn(MIN_INJECT_PERIOD_MS, 1000L)
        }

        /** ACCELEROMETER 注入周期（ms）：约 10Hz 已足够模拟步行波形。 */
        fun accelInjectPeriodMs(): Long = 100L
    }

    @Volatile
    private var stepCounter: Long = 0L
    @Volatile
    private var lastTickElapsed: Long = 0L

    /** 重置状态（配置切换/启动时调用）。 */
    fun reset() {
        stepCounter = 0L
        lastTickElapsed = 0L
    }

    /** 当前累计步数（供状态展示）。 */
    fun currentStepCount(): Long = stepCounter

    /**
     * 按传感器类型生成一次虚拟数据。
     *
     * @param type 传感器类型（1 加速度 / 18 单步检测 / 19 步数累计）
     * @return values；模拟关闭或不支持该类型时返回 null
     */
    fun tick(type: Int): FloatArray? {
        val cfg = configProvider() ?: return null
        if (!cfg.enabled) return null
        if (type !in cfg.sensorTypes) return null

        val now = SystemClock.elapsedRealtime()
        if (lastTickElapsed == 0L) {
            lastTickElapsed = now
        }
        val dtSec = (now - lastTickElapsed) / 1000.0
        lastTickElapsed = now

        // 步数累计：全局共享单调递增
        if (cfg.stepFrequency > 0) {
            stepCounter += (cfg.stepFrequency * dtSec / 60.0).toLong()
        } else {
            // 未配置步频时保持起始步数
            if (stepCounter <= 0) stepCounter = cfg.stepCount
        }

        val noise = if (cfg.randomNoise) {
            ThreadLocalRandom.current().nextDouble(-0.05, 0.05).toFloat()
        } else 0f

        return when (type) {
            VirtualSensorConfig.TYPE_STEP_COUNTER -> floatArrayOf(stepCounter.toFloat())
            VirtualSensorConfig.TYPE_STEP_DETECTOR -> floatArrayOf(1f)
            VirtualSensorConfig.TYPE_ACCELEROMETER -> accelValues(cfg, noise)
            else -> null
        }
    }

    /** 步行/跑步垂直轴波形：以步频为频率的正弦叠加，水平轴小幅晃动。 */
    private fun accelValues(cfg: VirtualSensorConfig, noise: Float): FloatArray {
        val phase = (stepCounter % 64) * 2 * Math.PI / 64.0
        val stepHz = cfg.stepFrequency / 60.0
        val amp = when (cfg.mode) {
            "running" -> 2.8f
            "walking" -> 1.4f
            else -> 1.0f
        }
        val vertical = 9.81f + amp * Math.sin(phase + stepHz * stepCounter).toFloat() + noise
        val x = amp * 0.25f * Math.cos(phase).toFloat() + noise * 0.5f
        val y = amp * 0.2f * Math.sin(phase * 1.3).toFloat() + noise * 0.5f
        return floatArrayOf(x, y, vertical)
    }
}
