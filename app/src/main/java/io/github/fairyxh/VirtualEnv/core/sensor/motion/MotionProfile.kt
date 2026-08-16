package io.github.fairyxh.VirtualEnv.core.sensor.motion

import org.json.JSONObject

/**
 * 运动模式参数集。
 *
 * 所有传感器生成器只依赖本 Profile 与 [VirtualMovementState] 取数，
 * 保证步频/速度/幅度/姿态全部来自同一个运动模型。
 */
enum class ActivityMode {
    STATIONARY, WALK, RUN;

    companion object {
        fun fromString(v: String?): ActivityMode = when (v?.uppercase()) {
            "WALK", "WALKING" -> WALK
            "RUN", "RUNNING" -> RUN
            "STATIONARY", "STATIC", "STILL" -> STATIONARY
            else -> STATIONARY
        }

        fun fromSpeedKmh(speed: Double): ActivityMode = when {
            speed <= 0.5 -> STATIONARY
            speed < 7.0 -> WALK
            else -> RUN
        }
    }
}

/**
 * 运动配置（由 VirtualSensorConfig / EnvStateCache JSON 解析）。
 *
 * @param activity 活动模式；为空时按 stepFrequency 自动推导
 * @param stepFrequency 步频（steps/min）
 * @param speedKmh 目标速度；<=0 时按步频+步长模型推导
 * @param amplitudeOverride 加速度幅度覆盖；null 时用模式默认
 * @param randomNoise 是否叠加低频/高频噪声
 */
data class MotionProfile(
    val activity: ActivityMode = ActivityMode.WALK,
    val stepFrequency: Int = 120,
    val speedKmh: Double = -1.0,
    val amplitudeOverride: Float? = null,
    val randomNoise: Boolean = true,
) {
    companion object {
        /** WALK：60~120 steps/min、1~6 km/h、1~3 m/s²。 */
        const val WALK_MIN_STEPS = 60
        const val WALK_MAX_STEPS = 120
        const val WALK_MIN_KMH = 1.0
        const val WALK_MAX_KMH = 6.0
        const val WALK_AMP_MIN = 1.0f
        const val WALK_AMP_MAX = 3.0f

        /** RUN：150~220 steps/min、7~20 km/h、3~8 m/s²。 */
        const val RUN_MIN_STEPS = 150
        const val RUN_MAX_STEPS = 220
        const val RUN_MIN_KMH = 7.0
        const val RUN_MAX_KMH = 20.0
        const val RUN_AMP_MIN = 3.0f
        const val RUN_AMP_MAX = 8.0f

        /** 站立：无步态周期。 */
        val STATIONARY = MotionProfile(
            activity = ActivityMode.STATIONARY,
            stepFrequency = 0,
            speedKmh = 0.0,
            amplitudeOverride = 0.1f,
            randomNoise = true,
        )

        fun resolve(activity: ActivityMode, stepFrequency: Int): MotionProfile {
            val steps = when (activity) {
                ActivityMode.WALK -> stepFrequency.coerceIn(WALK_MIN_STEPS, WALK_MAX_STEPS)
                ActivityMode.RUN -> stepFrequency.coerceIn(RUN_MIN_STEPS, RUN_MAX_STEPS)
                ActivityMode.STATIONARY -> 0
            }
            return MotionProfile(activity = activity, stepFrequency = steps)
        }

        fun fromStepFrequency(steps: Int): MotionProfile {
            val mode = when {
                steps <= 0 -> ActivityMode.STATIONARY
                steps < RUN_MIN_STEPS -> ActivityMode.WALK
                else -> ActivityMode.RUN
            }
            return resolve(mode, steps)
        }

        /** 从 JSON 解析（兼容 data: {activity, mode, stepFrequency, speed, amplitude, randomNoise}）。 */
        fun fromJson(json: JSONObject?): MotionProfile {
            if (json == null) return fromStepFrequency(120)
            val activity = ActivityMode.fromString(
                json.optString("activity", "").ifBlank { json.optString("mode", "") }
            )
            val steps = json.optInt("stepFrequency", 120)
            val speed = json.optDouble("speed", -1.0)
            val amp = if (json.has("amplitude")) json.optDouble("amplitude", -1.0) else -1.0
            return MotionProfile(
                activity = activity,
                stepFrequency = if (activity == ActivityMode.STATIONARY) 0 else steps,
                speedKmh = speed,
                amplitudeOverride = if (amp > 0) amp.toFloat() else null,
                randomNoise = json.optBoolean("randomNoise", true),
            )
        }

        /** 步长模型：由步频推导速度（约 0.5~1.2 m/step）。 */
        fun speedFor(steps: Int): Double {
            if (steps <= 0) return 0.0
            val strideMeters = when {
                steps < RUN_MIN_STEPS -> 0.45 + (steps - WALK_MIN_STEPS) / 60.0 * 0.25 // WALK 0.45~0.70
                else -> 0.85 + (steps - RUN_MIN_STEPS) / 70.0 * 0.35 // RUN 0.85~1.20
            }
            return strideMeters * steps * 60.0 / 1000.0 // m/s * 3.6 -> km/h
        }
    }

    /** 实际速度（km/h）；未显式配置时按步长模型推导。 */
    val effectiveSpeedKmh: Double
        get() = if (speedKmh > 0) speedKmh else speedFor(stepFrequency)

    /** 实际加速度幅度（m/s²）。 */
    val effectiveAmplitude: Float
        get() = amplitudeOverride ?: when (activity) {
            ActivityMode.STATIONARY -> 0.05f
            ActivityMode.WALK -> WALK_AMP_MIN + (WALK_AMP_MAX - WALK_AMP_MIN) *
                ((stepFrequency - WALK_MIN_STEPS).toFloat() / (WALK_MAX_STEPS - WALK_MIN_STEPS))
            ActivityMode.RUN -> RUN_AMP_MIN + (RUN_AMP_MAX - RUN_AMP_MIN) *
                ((stepFrequency - RUN_MIN_STEPS).toFloat() / (RUN_MAX_STEPS - RUN_MIN_STEPS))
        }
}
