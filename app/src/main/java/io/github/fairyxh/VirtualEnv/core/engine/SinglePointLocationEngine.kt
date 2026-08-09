package io.github.fairyxh.VirtualEnv.core.engine

import android.location.Location
import io.github.fairyxh.VirtualEnv.core.model.LocationState
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.util.concurrent.atomic.AtomicReference

/**
 * 单点虚拟定位引擎（Phase 1）。
 *
 * 输入：经纬度（+可选 speed/bearing）。
 * 输出：android.location.Location 对象，provider 默认 gps。
 *
 * 线程安全：状态保存在 [AtomicReference]，Hook 任意线程可并发读取。
 */
class SinglePointLocationEngine : LocationEngine {

    override val name: String = "single-point"

    private val state = AtomicReference(
        LocationState(enabled = false, updateTime = System.currentTimeMillis())
    )

    override fun isEnabled(): Boolean = state.get().enabled

    override fun setEnabled(enabled: Boolean) {
        val current = state.get()
        state.set(current.copy(enabled = enabled, updateTime = System.currentTimeMillis()))
        ZLog.i("Core", "SinglePointLocationEngine enabled=$enabled")
    }

    override fun setPoint(latitude: Double, longitude: Double, speed: Float, bearing: Float) {
        state.set(
            LocationState(
                enabled = state.get().enabled,
                latitude = latitude,
                longitude = longitude,
                speed = speed,
                bearing = bearing,
                accuracy = 5f,
                updateTime = System.currentTimeMillis()
            )
        )
        ZLog.i("Core", "SinglePointLocationEngine setPoint lat=$latitude lon=$longitude speed=$speed bearing=$bearing")
    }

    override fun currentLocation(): Location? {
        val s = state.get()
        if (!s.enabled) return null
        return buildLocation(s)
    }

    override fun currentState(): LocationState = state.get()

    companion object {
        /**
         * 由 [LocationState] 构建 [Location]。
         *
         * 每次调用都刷新 time/elapsedRealtimeNanos 为当前时间，
         * 避免客户端因位置时间戳过旧而丢弃虚拟位置。
         */
        fun buildLocation(s: LocationState): Location {
            val location = Location(s.provider.ifEmpty { "gps" })
            location.latitude = s.latitude
            location.longitude = s.longitude
            location.accuracy = s.accuracy
            location.speed = s.speed
            location.bearing = s.bearing
            location.altitude = s.altitude
            val now = System.currentTimeMillis()
            location.time = now
            location.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
            return location
        }
    }
}
