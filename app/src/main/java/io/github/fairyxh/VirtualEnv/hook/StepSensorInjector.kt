package io.github.fairyxh.VirtualEnv.hook

import io.github.fairyxh.VirtualEnv.core.EnvStateCache
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 传感器连续模拟注入器（进程内）。
 *
 * 在已注册传感器监听上周期性投递合成 [android.hardware.SensorEvent]。依据 JADX 逆向
 * （docs/reverse/sensor-step-simulation-analysis.md）：
 * - 本 ROM 的 SensorEvent 提供 public 4 参构造，无需反射 values 数组；
 * - Android 15 传感器事件经共享内存分发，服务端 Hook SensorService 无法逐 App 改写，
 *   因此在本进程（scope 内系统进程）的框架层注入。
 *
 * 支持两类连续模拟：
 * 1. 步频模拟（TYPE_STEP_COUNTER / TYPE_STEP_DETECTOR）：按 stepFrequency 或录像
 *    sensor 数据中的 sampleRateMs 连续输出；
 * 2. 传感器连续流（TYPE_ACCELEROMETER / TYPE_GYROSCOPE）：录像/模拟数据存在对应
 *    字段时，按 sampleRateMs 连续输出 —— 即“连续采集模拟数据 + 重放”，而非按
 *    采样间隔帧跳变。
 *
 * 线程模型：单 HandlerThread 调度器；每个 listener 一个周期任务。
 * 状态从 [EnvStateCache] 轮询快照读取，不在 Hook 层保存业务状态。
 */
class StepSensorInjector(private val cache: EnvStateCache) {

    companion object {
        private const val TAG_SCOPE = "StepHook"
        const val TYPE_ACCELEROMETER = 1
        const val TYPE_MAGNETIC_FIELD = 2
        const val TYPE_GYROSCOPE = 4
        const val TYPE_GRAVITY = 9
        const val TYPE_STEP_DETECTOR = 18
        const val TYPE_STEP_COUNTER = 19

        private const val MIN_PERIOD_MS = 50L
        private const val DEFAULT_SAMPLE_RATE_MS = 100L
    }

    private data class ListenerEntry(
        val listener: Any,
        val sensor: Any,
        val type: Int,
        val future: ScheduledFuture<*>,
        val sessionStartElapsed: Long = android.os.SystemClock.elapsedRealtime(),
        @Volatile var lastEventIndex: Int = -1,
    )

    private val lock = Any()
    private val listeners = ConcurrentHashMap<Any, ListenerEntry>()
    private val pending = ConcurrentHashMap<Any, Pair<Any, Int>>() // listener -> (sensor, type)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ZVE-StepInjector").apply { isDaemon = true }
    }

    /** 注册监听：传感器模拟开启且类型命中时启动注入；否则保持原生行为。 */
    fun onListenerRegistered(listener: Any, sensor: Any, type: Int) {
        if (listeners.containsKey(listener)) return
        val period = resolvePeriod(type)
        if (period == null) {
            // 模拟尚未就绪（例如配置刚启用、缓存未刷新）：先挂起，等 refresh() 补启动
            pending[listener] = sensor to type
            ZLog.d(TAG_SCOPE, "sensor injector pending type=$type (config not ready)")
            return
        }
        startInject(listener, sensor, type, period)
    }

    /** 启动注入（内部）。 */
    private fun startInject(listener: Any, sensor: Any, type: Int, period: Long) {
        val future = scheduler.scheduleWithFixedDelay(
            { tick(listener, sensor, type) },
            period,
            period,
            TimeUnit.MILLISECONDS
        )
        listeners[listener] = ListenerEntry(listener, sensor, type, future)
        pending.remove(listener)
        ZLog.i(TAG_SCOPE, "sensor injector started type=$type period=${period}ms")
    }

    /** 取消该 listener 的注入（unregister 时调用）。 */
    fun onListenerUnregistered(listener: Any) {
        pending.remove(listener)
        val entry = listeners.remove(listener) ?: return
        entry.future.cancel(false)
        ZLog.i(TAG_SCOPE, "sensor injector stopped type=${entry.type}")
    }

    /** 状态变化时检查：模拟关闭则停止全部注入；配置就绪则补启动挂起的 listener。 */
    fun refresh() {
        if (!cache.isSensorStreamActive()) {
            if (listeners.isEmpty()) return
            val iter = listeners.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                entry.value.future.cancel(false)
                iter.remove()
            }
            ZLog.i(TAG_SCOPE, "sensor injector cleared (disabled)")
            return
        }
        if (pending.isEmpty()) return
        val iter = pending.entries.iterator()
        while (iter.hasNext()) {
            val (listener, pair) = iter.next()
            val (sensor, type) = pair
            if (listeners.containsKey(listener)) {
                iter.remove()
                continue
            }
            val period = resolvePeriod(type)
            if (period != null) {
                startInject(listener, sensor, type, period)
            }
        }
    }

    /** 停止全部注入但保留调度器（suppress 时调用，后续 refresh 可恢复）。 */
    fun stopAll() {
        val iter = listeners.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            entry.value.future.cancel(false)
            iter.remove()
        }
        pending.clear()
        ZLog.i(TAG_SCOPE, "sensor injector stopped all (suppressed)")
    }

    /** 计算该传感器类型的注入周期；未命中任何模拟模式时返回 null（不注入）。 */
    private fun resolvePeriod(type: Int): Long? {
        val sensorData = cache.currentSensor()
        val streamActive = cache.isSensorStreamActive()
        if (!streamActive) return null
        // 录像事件流：按事件间隔推进（完整重放），不再固定周期
        val events = sensorData?.optJSONArray("events")
        if (events != null && events.length() >= 2) {
            // 事件间隔中位数作为 tick 周期，保证不漏事件
            val deltas = mutableListOf<Long>()
            var prev = events.optJSONObject(0)?.optLong("t", 0L) ?: 0L
            for (i in 1 until events.length()) {
                val t = events.optJSONObject(i)?.optLong("t", prev) ?: prev
                deltas.add((t - prev).coerceAtLeast(1L))
                prev = t
            }
            deltas.sort()
            val period = deltas.getOrNull(deltas.size / 2) ?: DEFAULT_SAMPLE_RATE_MS
            return period.coerceIn(MIN_PERIOD_MS, 2000L)
        }
        return when (type) {
            TYPE_STEP_COUNTER, TYPE_STEP_DETECTOR -> {
                // 步频配置优先（60s / steps-per-min）；录像传感器流按 sampleRateMs
                if (sensorData?.has("stepCounter") == true && !sensorData.has("stepFrequency")) {
                    sensorData.optLong("sampleRateMs", DEFAULT_SAMPLE_RATE_MS).coerceIn(MIN_PERIOD_MS, 2000L)
                } else if (cache.isStepEnabled()) {
                    (60000L / cache.stepFrequency().coerceAtLeast(1)).coerceAtLeast(MIN_PERIOD_MS)
                } else {
                    sensorData?.optLong("sampleRateMs", DEFAULT_SAMPLE_RATE_MS)?.coerceIn(MIN_PERIOD_MS, 2000L)
                }
            }
            TYPE_ACCELEROMETER -> {
                if (sensorData?.optJSONArray("accelerometer") != null) {
                    sensorData.optLong("sampleRateMs", DEFAULT_SAMPLE_RATE_MS).coerceIn(MIN_PERIOD_MS, 2000L)
                } else if (cache.isStepEnabled()) {
                    // 步频模拟模式：附加连续加速度流（步行波形），非仅录像
                    DEFAULT_SAMPLE_RATE_MS
                } else null
            }
            TYPE_GRAVITY -> {
                if (sensorData?.optJSONArray("gravity") != null) {
                    sensorData.optLong("sampleRateMs", DEFAULT_SAMPLE_RATE_MS).coerceIn(MIN_PERIOD_MS, 2000L)
                } else if (cache.isStepEnabled()) {
                    DEFAULT_SAMPLE_RATE_MS
                } else null
            }
            TYPE_MAGNETIC_FIELD -> {
                if (sensorData?.optJSONArray("magnetic") != null) {
                    sensorData.optLong("sampleRateMs", DEFAULT_SAMPLE_RATE_MS).coerceIn(MIN_PERIOD_MS, 2000L)
                } else if (cache.isStepEnabled()) {
                    DEFAULT_SAMPLE_RATE_MS
                } else null
            }
            TYPE_GYROSCOPE -> {
                if (sensorData?.optJSONArray("gyroscope") != null) {
                    sensorData.optLong("sampleRateMs", DEFAULT_SAMPLE_RATE_MS).coerceIn(MIN_PERIOD_MS, 2000L)
                } else null
            }
            else -> null
        }
    }

    private fun tick(listener: Any, sensor: Any, type: Int) {
        try {
            if (!cache.isSensorStreamActive()) {
                refresh()
                return
            }
            val sensorData = cache.currentSensor()
            val events = sensorData?.optJSONArray("events")
            if (events != null && events.length() > 0) {
                tickEventStream(listener, sensor, type, events)
                return
            }
            val event = buildEvent(sensor, type)
            val listenerClass = listener.javaClass
            val method = listenerClass.methods.firstOrNull {
                it.name == "onSensorChanged" && it.parameterCount == 1 &&
                    it.parameterTypes[0].simpleName == "SensorEvent"
            } ?: return
            method.invoke(listener, event)
            ZLog.d(TAG_SCOPE, "sensor inject -> onSensorChanged(type=$type)")
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "sensor inject failed", t)
        }
    }

    /** 录像事件流回放：按相对时间推进事件索引，完整重放录制时的事件序列。 */
    private fun tickEventStream(listener: Any, sensor: Any, type: Int, events: org.json.JSONArray) {
        val entry = listeners[listener] ?: return
        val elapsed = android.os.SystemClock.elapsedRealtime() - entry.sessionStartElapsed
        // 二分查找最后一个 t <= elapsed 的事件
        var lo = 0
        var hi = events.length() - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val t = events.optJSONObject(mid)?.optLong("t", 0L) ?: 0L
            if (t <= elapsed) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (idx < 0) return
        if (idx == entry.lastEventIndex) return
        val ev = events.optJSONObject(idx) ?: return
        val values = when (type) {
            TYPE_STEP_COUNTER -> floatArrayOf(ev.optLong("stepCounter", -1L).takeIf { it >= 0 }?.toFloat() ?: 0f)
            TYPE_STEP_DETECTOR -> floatArrayOf(1f)
            TYPE_ACCELEROMETER -> ev.optJSONArray("accelerometer")?.let { arr ->
                if (arr.length() >= 3) floatArrayOf(
                    arr.optDouble(0).toFloat(), arr.optDouble(1).toFloat(), arr.optDouble(2).toFloat()
                ) else null
            } ?: floatArrayOf(0f, 0f, 0f)
            TYPE_GRAVITY -> ev.optJSONArray("gravity")?.let { arr ->
                if (arr.length() >= 3) floatArrayOf(
                    arr.optDouble(0).toFloat(), arr.optDouble(1).toFloat(), arr.optDouble(2).toFloat()
                ) else null
            } ?: floatArrayOf(0f, 0f, 9.81f)
            TYPE_MAGNETIC_FIELD -> ev.optJSONArray("magnetic")?.let { arr ->
                if (arr.length() >= 3) floatArrayOf(
                    arr.optDouble(0).toFloat(), arr.optDouble(1).toFloat(), arr.optDouble(2).toFloat()
                ) else null
            } ?: floatArrayOf(35f, -12f, 48f)
            TYPE_GYROSCOPE -> ev.optJSONArray("gyroscope")?.let { arr ->
                if (arr.length() >= 3) floatArrayOf(
                    arr.optDouble(0).toFloat(), arr.optDouble(1).toFloat(), arr.optDouble(2).toFloat()
                ) else null
            } ?: floatArrayOf(0f, 0f, 0f)
            else -> floatArrayOf(0f)
        }
        val event = buildEvent(sensor, type, values)
        val listenerClass = listener.javaClass
        val method = listenerClass.methods.firstOrNull {
            it.name == "onSensorChanged" && it.parameterCount == 1 &&
                it.parameterTypes[0].simpleName == "SensorEvent"
        } ?: return
        method.invoke(listener, event)
        entry.lastEventIndex = idx
        ZLog.d(TAG_SCOPE, "sensor event stream inject -> onSensorChanged(type=$type idx=$idx/${events.length() - 1})")
    }

    private fun buildEvent(sensor: Any, type: Int): Any {
        return buildEvent(sensor, type, valuesFor(sensor, type))
    }

    private fun valuesFor(sensor: Any, type: Int): FloatArray {
        val sensorData = cache.currentSensor()
        return when (type) {
            TYPE_STEP_COUNTER -> floatArrayOf(
                (sensorData?.optLong("stepCounter", -1L)?.takeIf { it >= 0 } ?: cache.stepCounter()).toFloat()
            )
            TYPE_STEP_DETECTOR -> floatArrayOf(1f)
            TYPE_ACCELEROMETER -> sensorData?.optJSONArray("accelerometer")?.let { arr ->
                if (arr.length() >= 3) floatArrayOf(
                    arr.optDouble(0).toFloat(), arr.optDouble(1).toFloat(), arr.optDouble(2).toFloat()
                ) else null
            } ?: syntheticAccel()
            TYPE_GRAVITY -> sensorData?.optJSONArray("gravity")?.let { arr ->
                if (arr.length() >= 3) floatArrayOf(
                    arr.optDouble(0).toFloat(), arr.optDouble(1).toFloat(), arr.optDouble(2).toFloat()
                ) else null
            } ?: floatArrayOf(0f, 0f, 9.81f)
            TYPE_MAGNETIC_FIELD -> sensorData?.optJSONArray("magnetic")?.let { arr ->
                if (arr.length() >= 3) floatArrayOf(
                    arr.optDouble(0).toFloat(), arr.optDouble(1).toFloat(), arr.optDouble(2).toFloat()
                ) else null
            } ?: floatArrayOf(35f, -12f, 48f)
            TYPE_GYROSCOPE -> sensorData?.optJSONArray("gyroscope")?.let { arr ->
                if (arr.length() >= 3) floatArrayOf(
                    arr.optDouble(0).toFloat(), arr.optDouble(1).toFloat(), arr.optDouble(2).toFloat()
                ) else null
            } ?: floatArrayOf(0f, 0f, 0f)
            else -> floatArrayOf(0f)
        }
    }

    /** 步频模式下生成步行/跑步垂直轴波形（与 VirtualSensorEngine 一致）。 */
    private fun syntheticAccel(): FloatArray {
        val steps = cache.stepCounter()
        val phase = (steps % 64) * 2 * Math.PI / 64.0
        val stepHz = cache.stepFrequency() / 60.0
        val amp = 1.4f
        val vertical = 9.81f + amp * Math.sin(phase + stepHz * steps).toFloat()
        val x = amp * 0.25f * Math.cos(phase).toFloat()
        val y = amp * 0.2f * Math.sin(phase * 1.3).toFloat()
        return floatArrayOf(x, y, vertical)
    }

    private fun buildEvent(sensor: Any, type: Int, values: FloatArray): Any {
        val sensorEventClass = Class.forName("android.hardware.SensorEvent")
        return try {
            // 本 ROM：public SensorEvent(Sensor, int accuracy, long timestamp, float[] values)
            sensorEventClass.getConstructor(
                Class.forName("android.hardware.Sensor"),
                Int::class.java,
                Long::class.java,
                FloatArray::class.java
            ).newInstance(
                sensor,
                3,
                android.os.SystemClock.elapsedRealtimeNanos(),
                values
            )
        } catch (t: Throwable) {
            // 兜底：反射读取隐藏构造 SensorEvent(int valueSize) 后设置字段
            val ctor = sensorEventClass.getDeclaredConstructor(Int::class.java)
            ctor.isAccessible = true
            val event = ctor.newInstance(values.size)
            sensorEventClass.getField("sensor").set(event, sensor)
            sensorEventClass.getField("accuracy").setInt(event, 3)
            sensorEventClass.getField("timestamp").setLong(event, android.os.SystemClock.elapsedRealtimeNanos())
            System.arraycopy(values, 0, sensorEventClass.getField("values").get(event) as Any, 0, values.size)
            event
        }
    }

    fun shutdown() {
        listeners.values.forEach { it.future.cancel(false) }
        listeners.clear()
        scheduler.shutdownNow()
    }
}
