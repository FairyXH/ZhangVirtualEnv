package io.github.fairyxh.VirtualEnv.core.engine

import android.location.Location
import android.os.SystemClock
import io.github.fairyxh.VirtualEnv.core.model.LocationState
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 摇杆移动引擎（Phase 2）。
 *
 * 悬浮窗摇杆输入方向向量（dx/dy，-1..1）与速度，本引擎把向量换算为
 * 单位时间位移并累计到 offsetLat/offsetLon；[applyTo] 把位移叠加到
 * 当前基准位置（单点或路线）之上，因此摇杆可同时用于"模拟位置"与
 * "路线模拟"两种模式。
 *
 * 位移公式：dLat = (dy/mag) * speed * dt / METERS_PER_DEG_LAT
 *           dLon = (dx/mag) * speed * dt / (METERS_PER_DEG_LON * cos(lat))
 *
 * 职责边界：只生成位置数据，不保存 Hook 业务状态。
 */
class JoystickEngine {

    private data class JoystickState(
        val enabled: Boolean = false,
        val dx: Double = 0.0,
        val dy: Double = 0.0,
        val speedMps: Double = 1.4,
        val offsetLat: Double = 0.0,
        val offsetLon: Double = 0.0,
        val lastTime: Long = 0L,
        val updateTime: Long = 0L,
    )

    private val state = AtomicReference(JoystickState())

    /** 摇杆基准纬度（tick 推进 dLon 的 cos 因子用）。 */
    private val baseLatRef = java.util.concurrent.atomic.AtomicReference(0.0)

    /**
     * 统一 tick 线程推进位移（200ms），applyTo 只读快照。
     * 之前 applyTo 惰性推进导致 fix 注入（慢）与 NMEA/Gnss 注入（快）各自推进，
     * 同一时刻各 Hook 点读到不同坐标 → 百度 SDK NMEA 一致性校验失败 → 定位失败。
     */
    private val tickExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ZVE-JoystickTick").apply { isDaemon = true }
    }

    init {
        tickExecutor.scheduleWithFixedDelay(
            {
                try {
                    val s = state.get()
                    if (!s.enabled) return@scheduleWithFixedDelay
                    val now = SystemClock.elapsedRealtime()
                    state.set(advance(s, now, baseLatRef.get()))
                } catch (t: Throwable) {
                    ZLog.w("Core", "joystick tick failed", t)
                }
            },
            200L,
            200L,
            java.util.concurrent.TimeUnit.MILLISECONDS
        )
    }

    fun isEnabled(): Boolean = state.get().enabled

    /**
     * 更新摇杆向量。enabled=false 时**保留累计位移**（松手后停在新位置，不溜回原点），
     * 仅 [resetOffset] 或显式 reset 才清空。
     *
     * dx/dy 做指数平滑（EMA）：Android 触摸事件常分轴采样（斜向快速拖动时一次 MOVE
     * 可能只更新 x 或 y），直接使用原始值会先横走再纵走、呈锯齿；平滑后方向连续。
     *
     * @param dx 水平分量（-1..1，正=东）
     * @param dy 垂直分量（-1..1，正=北）
     * @param speedKmh 速度 km/h
     */
    fun setVector(enabled: Boolean, dx: Double, dy: Double, speedKmh: Double) {
        val now = SystemClock.elapsedRealtime()
        val s = state.get()
        val targetDx = dx.coerceIn(-1.0, 1.0)
        val targetDy = dy.coerceIn(-1.0, 1.0)
        val smoothDx = if (enabled) s.dx + (targetDx - s.dx) * DIRECTION_SMOOTHING else targetDx
        val smoothDy = if (enabled) s.dy + (targetDy - s.dy) * DIRECTION_SMOOTHING else targetDy
        // 仅 enabled 边沿（false→true）重置 lastTime；持续拖动时保留 tick 的时间基准，
        // 否则高频 setVector（40ms 节流）会反复截断 dt，导致位移速率不稳定。
        val lastTime = if (enabled && !s.enabled) now else s.lastTime
        state.set(
            JoystickState(
                enabled = enabled,
                dx = smoothDx,
                dy = smoothDy,
                speedMps = (speedKmh / 3.6).coerceAtLeast(0.1),
                offsetLat = s.offsetLat,
                offsetLon = s.offsetLon,
                lastTime = lastTime,
                updateTime = now
            )
        )
        ZLog.i("Core", "JoystickEngine vector enabled=$enabled dx=$smoothDx dy=$smoothDy speedKmh=$speedKmh offset=${s.offsetLat},${s.offsetLon}")
    }

    /** 显式复位：清空累计位移，回到基准位置（停止按钮 / API 使用）。 */
    fun resetOffset() {
        val s = state.get()
        state.set(
            s.copy(
                offsetLat = 0.0,
                offsetLon = 0.0,
                updateTime = SystemClock.elapsedRealtime()
            )
        )
        ZLog.i("Core", "JoystickEngine offset reset")
    }

    /**
     * 把摇杆累计位移叠加到 [base] 上；offset 为 0（或 base 为 null）时返回 base。
     * enabled=false 时 offset 保留（松手停住），仍叠加。
     */
    fun applyTo(base: Location?): Location? {
        val s = state.get()
        if (base == null || (s.offsetLat == 0.0 && s.offsetLon == 0.0)) return base
        baseLatRef.set(base.latitude)
        val location = Location(base)
        location.latitude = base.latitude + s.offsetLat
        location.longitude = base.longitude + s.offsetLon
        location.speed = if (s.enabled) s.speedMps.toFloat() else 0f
        location.bearing = if (s.enabled) bearingAt(s).toFloat() else base.bearing
        return location
    }

    /** 叠加到 [state] 状态（App 展示用）；offset 为 0 时返回原状态。 */
    fun applyTo(stateIn: LocationState): LocationState {
        val s = state.get()
        if (s.offsetLat == 0.0 && s.offsetLon == 0.0) return stateIn
        baseLatRef.set(stateIn.latitude)
        return stateIn.copy(
            latitude = stateIn.latitude + s.offsetLat,
            longitude = stateIn.longitude + s.offsetLon,
            speed = if (s.enabled) s.speedMps.toFloat() else stateIn.speed,
            bearing = if (s.enabled) bearingAt(s).toFloat() else stateIn.bearing,
            updateTime = System.currentTimeMillis()
        )
    }

    private fun advance(s: JoystickState, now: Long, baseLat: Double): JoystickState {
        val dt = (now - s.lastTime) / 1000.0
        if (dt <= 0) return s.copy(updateTime = now)
        val mag = sqrt(s.dx * s.dx + s.dy * s.dy)
        if (mag <= 0.001) return s.copy(lastTime = now, updateTime = now)
        val dist = s.speedMps * dt
        val dLat = (s.dy / mag) * dist / METERS_PER_DEG_LAT
        val dLon = (s.dx / mag) * dist / (METERS_PER_DEG_LON * cos(Math.toRadians(baseLat)))
        return s.copy(
            offsetLat = s.offsetLat + dLat,
            offsetLon = s.offsetLon + dLon,
            lastTime = now,
            updateTime = now
        )
    }

    private fun bearingAt(s: JoystickState): Double {
        val mag = sqrt(s.dx * s.dx + s.dy * s.dy)
        if (mag <= 0.001) return 0.0
        return (Math.toDegrees(atan2(s.dx, s.dy)) + 360.0) % 360.0
    }

    /** 摇杆运行状态（App 展示用）。 */
    fun statusJson(): org.json.JSONObject {
        val s = state.get()
        return org.json.JSONObject().apply {
            put("enabled", s.enabled)
            put("dx", s.dx)
            put("dy", s.dy)
            put("speedKmh", s.speedMps * 3.6)
            put("offsetLat", s.offsetLat)
            put("offsetLon", s.offsetLon)
        }
    }

    companion object {
        private const val METERS_PER_DEG_LAT = 111320.0
        private const val METERS_PER_DEG_LON = 111320.0

        /** 方向指数平滑系数（0~1，越大响应越快；0.5 平衡平滑与跟手）。 */
        private const val DIRECTION_SMOOTHING = 0.5
    }
}
