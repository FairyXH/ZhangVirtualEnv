package io.github.fairyxh.VirtualEnv.core.sensor.motion

/**
 * 运动状态快照（统一模型内部状态）。
 *
 * 单一时钟推进：stepPhase 是全局步态相位（0..1），所有传感器生成器
 * 在同一相位取数，保证 accel/gyro/linear accel 与步频严格同步。
 *
 * GPS 联动预留：[updateFromGps] 可由路线/定位引擎写入 speed/heading，
 * [MotionProfile.Companion.fromSpeedKmh] 再据此推导活动模式。
 */
class VirtualMovementState {

    /** 当前活动模式（STATIONARY/WALK/RUN）。 */
    @Volatile
    var activity: ActivityMode = ActivityMode.STATIONARY
        private set

    /** 当前步频（steps/min；STATIONARY 为 0）。 */
    @Volatile
    var stepFrequency: Int = 0
        private set

    /** 当前速度（km/h）。 */
    @Volatile
    var speedKmh: Double = 0.0
        private set

    /** 累计步数（单调递增，跨注入共享）。 */
    @Volatile
    var stepCount: Long = 0L
        private set

    /** 累计距离（m）。 */
    @Volatile
    var distanceM: Double = 0.0
        private set

    /** 行进方向（度，0=北，低频漂移）。 */
    @Volatile
    var headingDeg: Double = 0.0
        private set

    /** 步态相位 0..1（0=着地，0.5=摆动中段）。 */
    @Volatile
    var stepPhase: Double = 0.0
        private set

    /** 上次检测到一步的 elapsedRealtimeNanos。 */
    @Volatile
    var lastStepAtNanos: Long = 0L
        private set

    /** 引擎内部时间（elapsedRealtimeNanos）。 */
    @Volatile
    var nowNanos: Long = 0L
        private set

    /** 重置状态（配置切换/启动时调用）。 */
    fun reset() {
        activity = ActivityMode.STATIONARY
        stepFrequency = 0
        speedKmh = 0.0
        stepCount = 0L
        distanceM = 0.0
        headingDeg = 0.0
        stepPhase = 0.0
        lastStepAtNanos = 0L
        nowNanos = 0L
    }

    /** 应用新的运动配置。 */
    fun applyProfile(profile: MotionProfile) {
        activity = profile.activity
        stepFrequency = profile.stepFrequency
        speedKmh = profile.effectiveSpeedKmh
    }

    /** 推进步态相位；返回是否跨过至少一步（step detector 触发）。 */
    fun advancePhase(dtSec: Double, now: Long): Boolean {
        nowNanos = now
        if (stepFrequency <= 0) {
            stepPhase = 0.0
            return false
        }
        val stepHz = stepFrequency / 60.0
        stepPhase += stepHz * dtSec
        var stepped = false
        while (stepPhase >= 1.0) {
            stepPhase -= 1.0
            stepCount++
            distanceM += stepMeters()
            lastStepAtNanos = now
            stepped = true
        }
        return stepped
    }

    /** 每步距离（m），由速度与步频推导。 */
    private fun stepMeters(): Double {
        if (stepFrequency <= 0) return 0.0
        return (speedKmh * 1000.0 / 3600.0) / (stepFrequency / 60.0)
    }

    /** 设置当前步频（不重置步数；活动模式自动推导）。 */
    fun updateStepFrequency(steps: Int) {
        stepFrequency = steps.coerceAtLeast(0)
        activity = when {
            stepFrequency <= 0 -> ActivityMode.STATIONARY
            stepFrequency < MotionProfile.RUN_MIN_STEPS -> ActivityMode.WALK
            else -> ActivityMode.RUN
        }
    }

    /** GPS 联动预留：外部定位引擎写回速度/方向。 */
    fun updateFromGps(speedKmh: Double, headingDeg: Double) {
        this.speedKmh = speedKmh.coerceAtLeast(0.0)
        this.headingDeg = ((headingDeg % 360.0) + 360.0) % 360.0
    }
}
