package io.github.fairyxh.VirtualEnv.hook

import io.github.fairyxh.VirtualEnv.core.EnvStateCache
import io.github.fairyxh.VirtualEnv.core.sensor.motion.SensorEventComposer
import io.github.fairyxh.VirtualEnv.core.sensor.motion.VirtualMotionEngine
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 进程内传感器注入器（LEGACY 兼容链路）。
 *
 * 职责边界：
 * - 只负责「调度 + 投递」：从 [VirtualMotionEngine] 取统一运动模型数据，
 *   反射构造 SensorEvent 并调用 listener.onSensorChanged；
 * - **不包含任何运动/波形/步数逻辑**（已迁入 motion 包）；
 * - 注册时是否接管由 [onListenerRegistered] 返回值决定（true=屏蔽真实传感器，
 *   由 Hook 层不 proceed 原注册）。
 *
 * 线程模型：单 HandlerThread 调度器；每个 (listener,type) 一个周期任务。
 */
class StepSensorInjector(
    private val cache: EnvStateCache,
    private val motionEngine: VirtualMotionEngine,
) {

    companion object {
        private const val TAG_SCOPE = "StepHook"

        const val TYPE_ACCELEROMETER = 1
        const val TYPE_MAGNETIC_FIELD = 2
        const val TYPE_GYROSCOPE = 4
        const val TYPE_GRAVITY = 9
        const val TYPE_LINEAR_ACCELERATION = 10
        const val TYPE_STEP_DETECTOR = 18
        const val TYPE_STEP_COUNTER = 19

        private const val MIN_PERIOD_MS = 50L
        private const val DEFAULT_SAMPLE_RATE_MS = 100L

        /** 引擎支持的传感器类型。 */
        val SUPPORTED_TYPES = setOf(
            TYPE_ACCELEROMETER, TYPE_MAGNETIC_FIELD, TYPE_GYROSCOPE,
            TYPE_GRAVITY, TYPE_LINEAR_ACCELERATION, TYPE_STEP_DETECTOR, TYPE_STEP_COUNTER,
        )
    }

    private data class ListenerEntry(
        val listener: Any,
        val sensor: Any,
        val type: Int,
        val future: ScheduledFuture<*>,
    )

    private val listeners = ConcurrentHashMap<Pair<Any, Int>, ListenerEntry>() // (listener,type) -> entry
    private val pending = ConcurrentHashMap<Pair<Any, Int>, Pair<Any, Int>>() // (listener,type) -> (sensor, type)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ZVE-StepInjector").apply { isDaemon = true }
    }

    /**
     * 注册监听。返回 true 表示本注入器接管（Hook 层应**不 proceed** 原注册，
     * 屏蔽真实传感器）；false 表示类型未启用，放行真实注册。
     */
    fun onListenerRegistered(listener: Any, sensor: Any, type: Int): Boolean {
        if (type !in SUPPORTED_TYPES) return false
        if (!cache.isSensorStreamActive()) return false
        val key = listener to type
        if (listeners.containsKey(key)) return true
        val period = resolvePeriod(type)
        if (period == null) {
            // 模拟尚未就绪（配置刚启用、缓存未刷新）：先挂起，等 refresh() 补启动
            pending[key] = sensor to type
            ZLog.d(TAG_SCOPE, "sensor injector pending type=$type (config not ready)")
            return true
        }
        startInject(listener, sensor, type, period)
        return true
    }

    /** 启动注入（内部）。 */
    private fun startInject(listener: Any, sensor: Any, type: Int, period: Long) {
        val key = listener to type
        val future = scheduler.scheduleWithFixedDelay(
            { tick(listener, sensor, type) },
            period,
            period,
            TimeUnit.MILLISECONDS
        )
        listeners[key] = ListenerEntry(listener, sensor, type, future)
        pending.remove(key)
        ZLog.i(TAG_SCOPE, "sensor injector started type=$type period=${period}ms")
    }

    /** 取消该 listener 的注入（unregister 时调用）。 */
    fun onListenerUnregistered(listener: Any) {
        val keys = listeners.keys.filter { it.first === listener }
        keys.forEach { key ->
            listeners.remove(key)?.future?.cancel(false)
            ZLog.i(TAG_SCOPE, "sensor injector stopped type=${key.second}")
        }
        pending.keys.filter { it.first === listener }.forEach { pending.remove(it) }
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
            val (key, pair) = iter.next()
            val (sensor, type) = pair
            if (listeners.containsKey(key)) {
                iter.remove()
                continue
            }
            val period = resolvePeriod(type)
            if (period != null) {
                startInject(key.first, sensor, type, period)
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
        if (!cache.isSensorStreamActive()) return null
        return when (type) {
            TYPE_STEP_COUNTER, TYPE_STEP_DETECTOR -> {
                // 静止模式（stepFrequency=0）不注入计步事件
                if (cache.stepFrequency() <= 0) null
                else if (cache.isStepEnabled()) {
                    (60000L / cache.stepFrequency()).coerceAtLeast(MIN_PERIOD_MS)
                } else DEFAULT_SAMPLE_RATE_MS
            }
            TYPE_ACCELEROMETER, TYPE_LINEAR_ACCELERATION, TYPE_GRAVITY,
            TYPE_GYROSCOPE, TYPE_MAGNETIC_FIELD -> DEFAULT_SAMPLE_RATE_MS
            else -> null
        }
    }

    private fun tick(listener: Any, sensor: Any, type: Int) {
        try {
            if (!cache.isSensorStreamActive()) {
                refresh()
                return
            }
            val values = motionEngine.sample(type) ?: return
            val method = SensorEventComposer.findOnSensorChanged(listener) ?: return
            val event = SensorEventComposer.buildEvent(sensor, values)
            method.invoke(listener, event)
            ZLog.d(TAG_SCOPE, "sensor inject -> onSensorChanged(type=$type)")
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "sensor inject failed", t)
        }
    }

    fun shutdown() {
        listeners.values.forEach { it.future.cancel(false) }
        listeners.clear()
        scheduler.shutdownNow()
    }
}
