package io.github.fairyxh.VirtualEnv.core.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.SystemClock
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 全局系统传感器后端（SensorService Data Injection）。
 *
 * 运行在 **system_server** 进程。通过 Android @SystemApi
 * `SensorManager.initDataInjection / injectSensorData` 向 SensorService
 * 注入真实传感器 handle 的虚拟数据，事件经原生共享内存分发到**所有 App**，
 * 无需任何第三方 App 作用域。
 *
 * 能力探测：
 * 1. mode=4（HAL_BYPASS_REPLAY）：优先，绕过 `sensor.isDataInjectionSupported()` 限制；
 * 2. mode=1（DATA_INJECTION）：回退，要求 native 支持且传感器 flag 支持注入。
 *
 * 失败保护（fail-open）：
 * - `initDataInjection` 失败 → 停止并报告 MODE_NOT_SUPPORTED，由 Manager 切换 LEGACY；
 * - 每次 `injectSensorData` try/catch，异常仅记日志，绝不抛到系统调用栈；
 * - `stop()` 调用 `initDataInjection(false)` 恢复原生事件流。
 */
class SystemSensorBackend(
    private val sensorManagerProvider: () -> SensorManager?,
    private val engine: VirtualSensorEngine,
) : SensorBackend {

    companion object {
        private const val TAG_SCOPE = "SensorBackend"

        /** DATA_INJECTION / REPLAY / HAL_BYPASS_REPLAY（对应 SystemSensorManager switch 分支）。 */
        private const val MODE_DATA_INJECTION = 1
        private const val MODE_HAL_BYPASS_REPLAY = 4

        /** 各类型注入周期：步频按 stepFrequency 动态，加速度固定 100ms。 */
        private const val ACCEL_PERIOD_MS = 100L

        /** system_server 中获取 SensorManager（ActivityThread.currentApplication 反射）。 */
        fun systemServerSensorManager(): SensorManager? {
            return try {
                val app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null) as? Context
                app?.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "resolve system_server SensorManager failed", t)
                null
            }
        }
    }

    private data class SensorEntry(
        val sensor: Sensor,
        val future: ScheduledFuture<*>,
    )

    private val lock = Any()
    private val entries = ConcurrentHashMap<Int, SensorEntry>()
    private var scheduler: ScheduledExecutorService? = null
    private var activeMode: Int = -1

    @Volatile
    private var started = false

    @Volatile
    private var reason = ""

    private val eventCount = AtomicLong(0L)

    private var lastConfig: VirtualSensorConfig? = null

    override fun start() {
        synchronized(lock) {
            if (started) return
            val sm = sensorManagerProvider() ?: run {
                reason = "SENSOR_MANAGER_UNAVAILABLE"
                ZLog.w(TAG_SCOPE, "SystemSensorBackend start failed: SensorManager unavailable")
                return
            }
            val mode = detectInjectionMode(sm)
            if (mode < 0) {
                reason = "MODE_NOT_SUPPORTED"
                ZLog.w(TAG_SCOPE, "[!] Sensor injection unavailable\nReason: MODE_NOT_SUPPORTED\nFallback: LEGACY App Hook enabled")
                return
            }
            activeMode = mode
            started = true
            reason = ""
            val exec = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "ZVE-SystemSensor").apply { isDaemon = true }
            }
            scheduler = exec
            ZLog.i(TAG_SCOPE, "[✓] System injection available (mode=$mode)\nSelected backend: SYSTEM")
            startSchedules(exec)
            if (entries.isEmpty()) {
                // 找不到任何可注入的传感器：禁用注入，避免 SensorService 进入注入模式却无数据
                ZLog.w(TAG_SCOPE, "[!] No target sensors found for injection, disabling data injection")
                started = false
                reason = "NO_TARGET_SENSOR"
                exec.shutdownNow()
                scheduler = null
                try {
                    invokeInitDataInjection(sm, false, activeMode)
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "disable data injection after no-sensor failed", t)
                }
                activeMode = -1
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            entries.values.forEach { it.future.cancel(false) }
            entries.clear()
            scheduler?.shutdownNow()
            scheduler = null
            val sm = sensorManagerProvider()
            if (sm != null) {
                try {
                    invokeInitDataInjection(sm, false, activeMode)
                    ZLog.i(TAG_SCOPE, "SystemSensorBackend stopped, data injection disabled (native restored)")
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "disable data injection failed", t)
                }
            }
            activeMode = -1
        }
    }

    override fun updateConfig(config: VirtualSensorConfig?) {
        val old = lastConfig
        lastConfig = config
        if (old?.enabled != config?.enabled || old?.stepFrequency != config?.stepFrequency) {
            synchronized(lock) {
                if (!started) return
                // 频率变化：重建步频类型调度
                entries.values.forEach { it.future.cancel(false) }
                entries.clear()
                scheduler?.let { startSchedules(it) }
            }
        }
    }

    override fun getStatus(): SensorBackendStatus {
        val running = started && entries.isNotEmpty()
        return SensorBackendStatus(
            type = if (started) SensorBackendType.SYSTEM else SensorBackendType.NONE,
            started = running,
            injectMode = activeMode,
            reason = reason,
            eventCount = eventCount.get(),
        )
    }

    // ---------- 内部 ----------

    /** 探测 Data Injection 可用性：优先 mode=4，失败回退 mode=1；均失败返回 -1。 */
    private fun detectInjectionMode(sm: SensorManager): Int {
        return try {
            // mode=4：HAL_BYPASS_REPLAY，绕过 sensor.isDataInjectionSupported()
            if (invokeInitDataInjection(sm, true, MODE_HAL_BYPASS_REPLAY)) {
                ZLog.d(TAG_SCOPE, "initDataInjection(mode=4) OK")
                MODE_HAL_BYPASS_REPLAY
            } else if (invokeInitDataInjection(sm, true, MODE_DATA_INJECTION)) {
                ZLog.d(TAG_SCOPE, "initDataInjection(mode=1) OK")
                MODE_DATA_INJECTION
            } else {
                -1
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "detect injection mode failed", t)
            -1
        }
    }

    private fun startSchedules(exec: ScheduledExecutorService) {
        val cfg = lastConfig
        val sm = sensorManagerProvider() ?: return
        val types = cfg?.sensorTypes ?: VirtualSensorConfig.DEFAULT_SENSOR_TYPES
        for (type in types) {
            val sensor = findSensor(sm, type) ?: continue
            val period = when (type) {
                VirtualSensorConfig.TYPE_ACCELEROMETER -> ACCEL_PERIOD_MS
                else -> VirtualSensorEngine.stepInjectPeriodMs(cfg?.stepFrequency ?: 120)
            }
            val future = exec.scheduleWithFixedDelay(
                { tick(sensor, type) },
                period,
                period,
                TimeUnit.MILLISECONDS
            )
            entries[type] = SensorEntry(sensor, future)
            val handle = runCatching {
                sensor.javaClass.getMethod("getHandle").invoke(sensor)
            }.getOrNull()
            ZLog.i(TAG_SCOPE, "system sensor inject scheduled type=$type period=${period}ms handle=$handle")
        }
    }

    /** 从 SensorManager 查找目标类型传感器（优先 default，其次列表首个）。 */
    private fun findSensor(sm: SensorManager, type: Int): Sensor? {
        return try {
            sm.getDefaultSensor(type) ?: sm.getSensorList(type).firstOrNull()
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "find sensor type=$type failed", t)
            null
        }
    }

    private fun tick(sensor: Sensor, type: Int) {
        if (!started) return
        try {
            val values = engine.tick(type) ?: return
            val sm = sensorManagerProvider() ?: return
            val ok = invokeInjectSensorData(sm, sensor, values, 3, SystemClock.elapsedRealtimeNanos())
            if (ok) {
                eventCount.incrementAndGet()
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "injectSensorData type=$type failed", t)
        }
    }

    // ---------- @SystemApi 反射（system_server 有权限；普通 SDK 编译不可见） ----------

    private fun invokeInitDataInjection(sm: SensorManager, enable: Boolean, mode: Int): Boolean {
        return try {
            val method = SensorManager::class.java.getMethod(
                "initDataInjection",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            (method.invoke(sm, enable, mode) as? Boolean) == true
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "initDataInjection(enable=$enable mode=$mode) invoke failed", t)
            false
        }
    }

    private fun invokeInjectSensorData(
        sm: SensorManager,
        sensor: Sensor,
        values: FloatArray,
        accuracy: Int,
        timestampNanos: Long
    ): Boolean {
        return try {
            val method = SensorManager::class.java.getMethod(
                "injectSensorData",
                Sensor::class.java,
                FloatArray::class.java,
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType
            )
            (method.invoke(sm, sensor, values, accuracy, timestampNanos) as? Boolean) == true
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "injectSensorData invoke failed", t)
            false
        }
    }
}
