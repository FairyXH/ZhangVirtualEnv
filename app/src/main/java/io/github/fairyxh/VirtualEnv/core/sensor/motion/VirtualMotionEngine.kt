package io.github.fairyxh.VirtualEnv.core.sensor.motion

import android.os.SystemClock

/**
 * 虚拟运动引擎（统一时间轴）。
 *
 * 每个 tick：
 * 1. 推进步态相位（stepPhase / stepCount / distance）；
 * 2. 推进手机姿态（pitch/roll 低频漂移 + 摆臂）；
 * 3. 各传感器生成器在同一相位取数。
 *
 * 注入层（StepSensorInjector / SystemSensorBackend）只调用
 * [sample]，不包含任何运动/波形逻辑。
 */
class VirtualMotionEngine {

    private val state = VirtualMovementState()
    private val phone = PhoneMotionModel(state)
    private val generators = SensorGenerators(state)

    @Volatile
    private var profile: MotionProfile = MotionProfile.STATIONARY

    private var lastElapsedRealtimeMs: Long = 0L

    /** 跨步后待消费的 STEP_DETECTOR 事件（每步恰好触发一次）。 */
    @Volatile
    private var pendingStepDetector = false

    private val lock = Any()

    /** 更新运动配置。 */
    fun updateProfile(profile: MotionProfile) {
        synchronized(lock) {
            this.profile = profile
            state.applyProfile(profile)
            lastElapsedRealtimeMs = SystemClock.elapsedRealtime()
        }
    }

    /** 重置状态（配置切换/启动时调用）。 */
    fun reset() {
        synchronized(lock) {
            state.reset()
            profile = MotionProfile.STATIONARY
            lastElapsedRealtimeMs = 0L
            pendingStepDetector = false
        }
    }

    /** 当前运动状态快照（日志/UI）。 */
    fun stateSnapshot(): VirtualMovementState = state

    /**
     * 取某类型传感器当前值；模拟关闭或类型不支持时返回 null。
     *
     * @param type android.hardware.Sensor 类型常量
     * @param stepTriggered 外部已确认为一步（仅 STEP_DETECTOR 需要；
     *                      引擎内部会在推进相位时自动判断，通常无需外部传入）
     */
    fun sample(type: Int): FloatArray? {
        synchronized(lock) {
            val nowMs = SystemClock.elapsedRealtime()
            val dtSec = if (lastElapsedRealtimeMs == 0L) {
                lastElapsedRealtimeMs = nowMs
                0.0
            } else {
                ((nowMs - lastElapsedRealtimeMs).coerceAtLeast(0L)) / 1000.0
            }
            lastElapsedRealtimeMs = nowMs
            // 推进相位（同时维护 stepCount/distance）
            val stepped = state.advancePhase(dtSec, SystemClock.elapsedRealtimeNanos())
            if (stepped) pendingStepDetector = true
            phone.update(dtSec)
            return when (type) {
                SensorType.ACCELEROMETER -> generators.accelerometer(phone, profile)
                SensorType.LINEAR_ACCELERATION -> generators.linearAcceleration(phone, profile)
                SensorType.GRAVITY -> generators.gravity(phone, profile)
                SensorType.GYROSCOPE -> generators.gyroscope(phone, profile)
                SensorType.STEP_DETECTOR -> {
                    if (pendingStepDetector) {
                        pendingStepDetector = false
                        generators.stepDetector()
                    } else null
                }
                SensorType.STEP_COUNTER -> generators.stepCounter()
                else -> null
            }
        }
    }

    /** 传感器类型常量（与 android.hardware.Sensor 一致，避免 App 进程依赖 framework 常量）。 */
    object SensorType {
        const val ACCELEROMETER = 1
        const val MAGNETIC_FIELD = 2
        const val GYROSCOPE = 4
        const val GRAVITY = 9
        const val LINEAR_ACCELERATION = 10
        const val STEP_DETECTOR = 18
        const val STEP_COUNTER = 19
    }
}
