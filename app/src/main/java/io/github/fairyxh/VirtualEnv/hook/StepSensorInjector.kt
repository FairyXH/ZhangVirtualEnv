package io.github.fairyxh.VirtualEnv.hook

import io.github.fairyxh.VirtualEnv.core.EnvStateCache
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 步频模拟注入器（进程内）。
 *
 * 在已注册 TYPE_STEP_COUNTER / TYPE_STEP_DETECTOR 的监听上周期性投递合成
 * [android.hardware.SensorEvent]。依据 JADX 逆向（docs/reverse/sensor-step-simulation-analysis.md）：
 * - 本 ROM 的 SensorEvent 提供 public 4 参构造，无需反射 values 数组；
 * - Android 15 传感器事件经共享内存分发，服务端 Hook SensorService 无法逐 App 改写，
 *   因此在本进程（scope 内系统进程）的框架层注入。
 *
 * 线程模型：单 HandlerThread 调度器；每个 listener 一个周期任务，频率 = stepFrequency。
 * 状态从 [EnvStateCache] 轮询快照读取，不在 Hook 层保存业务状态。
 */
class StepSensorInjector(private val cache: EnvStateCache) {

    companion object {
        private const val TAG_SCOPE = "StepHook"
        const val TYPE_STEP_DETECTOR = 18
        const val TYPE_STEP_COUNTER = 19

        private const val MIN_PERIOD_MS = 100L
    }

    private data class ListenerEntry(
        val listener: Any,
        val sensor: Any,
        val type: Int,
        val future: ScheduledFuture<*>,
    )

    private val lock = Any()
    private val listeners = ConcurrentHashMap<Any, ListenerEntry>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ZVE-StepInjector").apply { isDaemon = true }
    }

    /** 注册监听：步频开启且类型命中时启动注入；否则保持原生行为。 */
    fun onListenerRegistered(listener: Any, sensor: Any, type: Int) {
        if (listeners.containsKey(listener)) return
        if (!cache.isStepEnabled()) return
        if (type != TYPE_STEP_COUNTER && type != TYPE_STEP_DETECTOR) return
        val period = (60000L / cache.stepFrequency().coerceAtLeast(1)).coerceAtLeast(MIN_PERIOD_MS)
        val future = scheduler.scheduleWithFixedDelay(
            { tick(listener, sensor, type) },
            period,
            period,
            TimeUnit.MILLISECONDS
        )
        listeners[listener] = ListenerEntry(listener, sensor, type, future)
        ZLog.i(TAG_SCOPE, "step injector started type=$type period=${period}ms")
    }

    /** 取消该 listener 的注入（unregister 时调用）。 */
    fun onListenerUnregistered(listener: Any) {
        val entry = listeners.remove(listener) ?: return
        entry.future.cancel(false)
        ZLog.i(TAG_SCOPE, "step injector stopped type=${entry.type}")
    }

    /** 状态变化时检查：步频关闭则停止全部注入。 */
    fun refresh() {
        if (cache.isStepEnabled()) return
        if (listeners.isEmpty()) return
        val iter = listeners.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            entry.value.future.cancel(false)
            iter.remove()
        }
        ZLog.i(TAG_SCOPE, "step injector cleared (disabled)")
    }

    private fun tick(listener: Any, sensor: Any, type: Int) {
        try {
            if (!cache.isStepEnabled()) {
                refresh()
                return
            }
            val event = buildEvent(sensor, type)
            val listenerClass = listener.javaClass
            val method = listenerClass.methods.firstOrNull {
                it.name == "onSensorChanged" && it.parameterCount == 1 &&
                    it.parameterTypes[0].simpleName == "SensorEvent"
            } ?: return
            method.invoke(listener, event)
            ZLog.d(TAG_SCOPE, "step inject -> onSensorChanged(type=$type counter=${cache.stepCounter()})")
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "step inject failed", t)
        }
    }

    private fun buildEvent(sensor: Any, type: Int): Any {
        val sensorEventClass = Class.forName("android.hardware.SensorEvent")
        val values = if (type == TYPE_STEP_COUNTER) {
            floatArrayOf(cache.stepCounter().toFloat())
        } else {
            floatArrayOf(1f)
        }
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
