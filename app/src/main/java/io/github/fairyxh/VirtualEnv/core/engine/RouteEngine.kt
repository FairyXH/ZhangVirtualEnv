package io.github.fairyxh.VirtualEnv.core.engine

import android.location.Location
import android.os.SystemClock
import io.github.fairyxh.VirtualEnv.core.model.LocationState
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 路线模拟引擎（Phase 1.3）。
 *
 * 输入：路线点列表 + 速度（km/h）。
 * 输出：android.location.Location，位置沿路线按速度推进。
 *
 * 推进方式：惰性插值。Hook 每次调用 [currentLocation] 时按
 * 距上次调用的时间差 × 速度推进游标，无需后台线程；路线播放期间
 * Hook 驱动即实时，无 Hook 调用则自然暂停。
 */
class RouteEngine : LocationEngine {

    override val name: String = "route"

    private data class RouteState(
        val enabled: Boolean = false,
        val running: Boolean = false,
        val points: List<Pair<Double, Double>> = emptyList(),
        val speedMps: Double = 1.4,
        val stepFrequency: Int = 120,
        val stepCount: Double = 0.0,
        val segmentIndex: Int = 0,
        val progress: Double = 0.0,
        val lastTime: Long = 0L,
        val updateTime: Long = 0L,
    )

    private val state = AtomicReference(RouteState())

    override fun isEnabled(): Boolean = state.get().enabled

    override fun setEnabled(enabled: Boolean) {
        // 路线引擎启用由 start/stop 控制，不直接使用
        ZLog.d("Core", "RouteEngine.setEnabled($enabled) ignored, use start/stop")
    }

    override fun setPoint(latitude: Double, longitude: Double, speed: Float, bearing: Float) {
        // 路线引擎不使用单点设置
        ZLog.d("Core", "RouteEngine.setPoint ignored")
    }

    /** 加载路线并开始播放。 */
    fun start(pointsJson: String, speedKmh: Double, stepFrequency: Int = 120) {
        val points = parsePoints(pointsJson)
        if (points.size < 2) {
            ZLog.w("Core", "RouteEngine.start ignored, need at least 2 points")
            return
        }
        val now = SystemClock.elapsedRealtime()
        state.set(
            RouteState(
                enabled = true,
                running = true,
                points = points,
                speedMps = (speedKmh / 3.6).coerceAtLeast(0.1),
                stepFrequency = stepFrequency.coerceIn(0, 600),
                lastTime = now,
                updateTime = now
            )
        )
        ZLog.i("Core", "RouteEngine started points=${points.size} speedKmh=$speedKmh stepFrequency=$stepFrequency")
    }

    fun pause() {
        val s = state.get()
        if (!s.running) return
        state.set(s.copy(running = false))
        ZLog.i("Core", "RouteEngine paused at segment=${s.segmentIndex} progress=${s.progress}")
    }

    /** 暂停后继续（不重置游标）。 */
    fun resume() {
        val s = state.get()
        if (!s.enabled || s.running || s.points.size < 2) return
        state.set(s.copy(running = true, lastTime = SystemClock.elapsedRealtime()))
        ZLog.i("Core", "RouteEngine resumed at segment=${s.segmentIndex} progress=${s.progress}")
    }

    /** 重置到路线起点并继续运行。 */
    fun reset() {
        val s = state.get()
        if (!s.enabled || s.points.size < 2) return
        val now = SystemClock.elapsedRealtime()
        state.set(
            s.copy(
                running = true,
                segmentIndex = 0,
                progress = 0.0,
                stepCount = 0.0,
                lastTime = now,
                updateTime = now
            )
        )
        ZLog.i("Core", "RouteEngine reset to start")
    }

    /** 运行时更新速度（km/h）与步频（steps/min）；传 0 表示不修改。 */
    fun config(speedKmh: Double, stepFrequency: Int) {
        val s = state.get()
        if (!s.enabled) return
        state.set(
            s.copy(
                speedMps = if (speedKmh > 0) (speedKmh / 3.6).coerceAtLeast(0.1) else s.speedMps,
                stepFrequency = if (stepFrequency > 0) stepFrequency.coerceIn(0, 600) else s.stepFrequency
            )
        )
        ZLog.i("Core", "RouteEngine config speedKmh=$speedKmh stepFrequency=$stepFrequency")
    }

    fun stop() {
        state.set(RouteState())
        ZLog.i("Core", "RouteEngine stopped")
    }

    fun isRunning(): Boolean = state.get().running

    override fun currentLocation(): Location? {
        val s = state.get()
        if (!s.enabled || !s.running || s.points.size < 2) return null
        val now = SystemClock.elapsedRealtime()
        val updated = advance(s, now)
        state.set(updated)
        return buildLocation(updated)
    }

    /** 按时间差推进游标；返回更新后的状态。 */
    private fun advance(s: RouteState, now: Long): RouteState {
        val deltaSec = (now - s.lastTime) / 1000.0
        if (deltaSec <= 0) return s.copy(updateTime = now)

        var remaining = s.speedMps * deltaSec
        var seg = s.segmentIndex
        var progress = s.progress
        while (remaining > 0 && seg < s.points.size - 1) {
            val segLen = distanceMeters(s.points[seg], s.points[seg + 1])
            if (segLen <= 0) {
                seg++
                progress = 0.0
                continue
            }
            val segRemaining = (1.0 - progress) * segLen
            if (remaining < segRemaining) {
                progress += remaining / segLen
                remaining = 0.0
            } else {
                remaining -= segRemaining
                seg++
                progress = 0.0
            }
        }
        val finished = seg >= s.points.size - 1
        val stepDelta = if (!finished) s.stepFrequency * deltaSec / 60.0 else 0.0
        return s.copy(
            segmentIndex = if (finished) s.points.size - 1 else seg,
            progress = if (finished) 1.0 else progress,
            running = !finished,
            stepCount = s.stepCount + stepDelta.coerceAtLeast(0.0),
            lastTime = now,
            updateTime = now
        )
    }

    private fun buildLocation(s: RouteState): Location {
        val p = interpolate(s.points, s.segmentIndex, s.progress)
        val location = Location("gps")
        // 随机抖动 ±0.000005°（约 ±0.5m），模拟真实 GPS 噪声
        val jitter = java.util.concurrent.ThreadLocalRandom.current().nextDouble(-0.000005, 0.000005)
        location.latitude = p.first + jitter
        location.longitude = p.second + jitter
        location.accuracy = 5f
        location.speed = s.speedMps.toFloat()
        location.bearing = bearingAt(s).toFloat()
        location.altitude = 0.0
        location.time = System.currentTimeMillis()
        location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        return location
    }

    override fun currentState(): LocationState {
        val s = state.get()
        if (!s.enabled || !s.running || s.points.isEmpty()) {
            return LocationState(enabled = false, updateTime = System.currentTimeMillis())
        }
        val p = interpolate(s.points, s.segmentIndex, s.progress)
        return LocationState(
            enabled = true,
            latitude = p.first,
            longitude = p.second,
            speed = s.speedMps.toFloat(),
            bearing = bearingAt(s).toFloat(),
            accuracy = 5f,
            updateTime = System.currentTimeMillis()
        )
    }

    /** 路线运行状态（App 展示用）。 */
    fun statusJson(): org.json.JSONObject {
        val s = state.get()
        return org.json.JSONObject().apply {
            put("running", s.running)
            put("enabled", s.enabled)
            put("speedKmh", s.speedMps * 3.6)
            put("stepFrequency", s.stepFrequency)
            put("stepCount", s.stepCount.toLong())
            put("points", s.points.size)
            put("segmentIndex", s.segmentIndex)
        }
    }

    /** 导出可恢复的完整运行快照（采集暂停时使用）。 */
    fun snapshotJson(): org.json.JSONObject {
        val s = state.get()
        val arr = org.json.JSONArray()
        s.points.forEach { (lat, lon) ->
            arr.put(org.json.JSONObject().apply {
                put("lat", lat)
                put("lon", lon)
            })
        }
        return org.json.JSONObject().apply {
            put("enabled", s.enabled)
            put("running", s.running)
            put("points", arr)
            put("speedMps", s.speedMps)
            put("stepFrequency", s.stepFrequency)
            put("stepCount", s.stepCount)
            put("segmentIndex", s.segmentIndex)
            put("progress", s.progress)
        }
    }

    /** 从快照恢复路线（暂停后恢复，保留段索引/进度）。 */
    fun restoreFrom(json: org.json.JSONObject) {
        val arr = json.optJSONArray("points") ?: run {
            ZLog.w("Core", "RouteEngine.restoreFrom: no points")
            return
        }
        val points = (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val lat = obj.optDouble("lat", Double.NaN)
            val lon = obj.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) null else Pair(lat, lon)
        }
        if (points.size < 2) return
        state.set(
            RouteState(
                enabled = json.optBoolean("enabled", true),
                running = json.optBoolean("running", true),
                points = points,
                speedMps = json.optDouble("speedMps", 1.4).coerceAtLeast(0.1),
                stepFrequency = json.optInt("stepFrequency", 120).coerceIn(0, 600),
                stepCount = json.optDouble("stepCount", 0.0),
                segmentIndex = json.optInt("segmentIndex", 0).coerceIn(0, points.size - 1),
                progress = json.optDouble("progress", 0.0).coerceIn(0.0, 1.0),
                lastTime = SystemClock.elapsedRealtime(),
                updateTime = SystemClock.elapsedRealtime()
            )
        )
        ZLog.i("Core", "RouteEngine restored points=${points.size} segment=${state.get().segmentIndex}")
    }

    private fun parsePoints(pointsJson: String): List<Pair<Double, Double>> {
        return try {
            val arr = JSONArray(pointsJson)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val lat = obj.optDouble("lat", Double.NaN)
                val lon = obj.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) null else Pair(lat, lon)
            }
        } catch (t: Throwable) {
            ZLog.w("Core", "parse route points failed", t)
            emptyList()
        }
    }

    private fun interpolate(
        points: List<Pair<Double, Double>>,
        segment: Int,
        progress: Double
    ): Pair<Double, Double> {
        if (points.isEmpty()) return Pair(0.0, 0.0)
        if (segment >= points.size - 1) return points.last()
        val a = points[segment]
        val b = points[segment + 1]
        val t = progress.coerceIn(0.0, 1.0)
        return Pair(
            a.first + (b.first - a.first) * t,
            a.second + (b.second - a.second) * t
        )
    }

    private fun bearingAt(s: RouteState): Double {
        if (s.points.isEmpty()) return 0.0
        val idx = s.segmentIndex.coerceAtMost(s.points.size - 2).coerceAtLeast(0)
        val a = s.points[idx]
        val b = s.points[idx + 1]
        return bearing(a, b)
    }

    companion object {
        private const val EARTH_RADIUS = 6371000.0

        fun distanceMeters(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
            val lat1 = Math.toRadians(a.first)
            val lat2 = Math.toRadians(b.first)
            val dLat = Math.toRadians(b.first - a.first)
            val dLon = Math.toRadians(b.second - a.second)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * EARTH_RADIUS * asin(sqrt(h))
        }

        fun bearing(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
            val lat1 = Math.toRadians(a.first)
            val lat2 = Math.toRadians(b.first)
            val dLon = Math.toRadians(b.second - a.second)
            val y = sin(dLon) * cos(lat2)
            val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
            return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        }
    }
}
