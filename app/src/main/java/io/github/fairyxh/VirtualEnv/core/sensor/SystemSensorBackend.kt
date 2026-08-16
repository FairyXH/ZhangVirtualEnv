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
 * 全局系统传感器后端（SensorService 系统级注入，两种通道）。
 *
 * 运行在 **system_server** 进程。
 *
 * 通道 1（首选）：`SensorManager.initDataInjection / injectSensorData`
 * （@SystemApi，Oplus 15 实测 MODE_NOT_SUPPORTED，native 未实现）。
 *
 * 通道 2（回退，Oplus 15 实证可用）：`SensorService.sendRuntimeSensorEventNative`
 * （public static native，JADX 实证签名 `(long mPtr, int handle, int type,
 * long timestampNanos, float[] values):boolean`）。
 * 绕过 Java 层 `LocalService.sendSensorEvent` 的 mRuntimeSensorHandles 检查，
 * 直接以**真实传感器 handle** 调用 native：JNI 桥不校验 handle 是否 runtime sensor，
 * 反汇编确认最终调用 `SensorService::sendRuntimeSensorEvent(sensors_event_t&)`，
 * 该函数仅加锁 → 事件入队 → notify_all → SensorThread 全局分发（共享内存 →
 * 所有注册该 handle 的 App），无需任何 App 作用域。
 * type 白名单掩码 0x4efc631e0 / 0x61e 之外（如 STEP_COUNTER=19）传 values.length>=17
 * 走 memcpy(80B) 分支后同样进入分发；STEP_DETECTOR(18) 走单值分支；
 * ACCELEROMETER(1) 走三值分支。
 *
 * 失败保护（fail-open）：所有反射/注入 try/catch，异常仅记日志；
 * stop() 仅停止调度（通道 2 无状态，无需恢复原生）。
 */
class SystemSensorBackend(
    private val sensorManagerProvider: () -> SensorManager?,
    private val engine: VirtualSensorEngine,
    private val systemServerClassLoader: ClassLoader? = null,
) : SensorBackend {

    companion object {
        private const val TAG_SCOPE = "SensorBackend"

        /** DATA_INJECTION / REPLAY / HAL_BYPASS_REPLAY（对应 SystemSensorManager switch 分支）。 */
        private const val MODE_DATA_INJECTION = 1
        private const val MODE_HAL_BYPASS_REPLAY = 4

        /** 通道 2：SensorService.sendRuntimeSensorEventNative（native 事件注入）。 */
        private const val MODE_RUNTIME_EVENT_NATIVE = 10

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

    /** 通道 2：SensorService 实例 mPtr（反射获取，native 句柄）。 */
    private var nativeSensorServicePtr: Long = 0L

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
            var mode = detectInjectionMode(sm)
            if (mode < 0) {
                // 通道 1 不可用：尝试通道 2（SensorService.sendRuntimeSensorEventNative）
                val ptr = resolveSensorServicePtr()
                if (ptr != 0L) {
                    mode = MODE_RUNTIME_EVENT_NATIVE
                    nativeSensorServicePtr = ptr
                    ZLog.i(TAG_SCOPE, "Data Injection unavailable, using SensorService native runtime event channel (ptr=${ptr})")
                }
            }
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
                // 找不到任何可注入的传感器：禁用注入（通道 1 需恢复，通道 2 无状态）
                ZLog.w(TAG_SCOPE, "[!] No target sensors found for injection, disabling system injection")
                started = false
                reason = "NO_TARGET_SENSOR"
                exec.shutdownNow()
                scheduler = null
                if (activeMode == MODE_HAL_BYPASS_REPLAY || activeMode == MODE_DATA_INJECTION) {
                    try {
                        invokeInitDataInjection(sm, false, activeMode)
                    } catch (t: Throwable) {
                        ZLog.w(TAG_SCOPE, "disable data injection after no-sensor failed", t)
                    }
                }
                nativeSensorServicePtr = 0L
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
            if (activeMode == MODE_HAL_BYPASS_REPLAY || activeMode == MODE_DATA_INJECTION) {
                val sm = sensorManagerProvider()
                if (sm != null) {
                    try {
                        invokeInitDataInjection(sm, false, activeMode)
                        ZLog.i(TAG_SCOPE, "SystemSensorBackend stopped, data injection disabled (native restored)")
                    } catch (t: Throwable) {
                        ZLog.w(TAG_SCOPE, "disable data injection failed", t)
                    }
                }
            } else {
                ZLog.i(TAG_SCOPE, "SystemSensorBackend stopped (native runtime event channel, stateless)")
            }
            nativeSensorServicePtr = 0L
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
            val ok = when (activeMode) {
                MODE_RUNTIME_EVENT_NATIVE -> invokeSendRuntimeSensorEventNative(sensor, type, values)
                else -> {
                    val sm = sensorManagerProvider() ?: return
                    invokeInjectSensorData(sm, sensor, values, 3, SystemClock.elapsedRealtimeNanos())
                }
            }
            if (ok) {
                eventCount.incrementAndGet()
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "inject sensor type=$type failed", t)
        }
    }

    // ---------- 通道 2：SensorService.sendRuntimeSensorEventNative（system_server 反射） ----------

    /**
     * 解析 SensorService 实例的 native mPtr。
     *
     * 路径：LocalServices.getService(SensorManagerInternal.class) → LocalService
     * → 内部类外引用（this$0）→ SensorService.mPtr。
     * 失败返回 0（fail-open）。
     */
    private fun resolveSensorServicePtr(): Long {
        return try {
            val sys = systemServerClassLoader() ?: return 0L
            val localServices = Class.forName("com.android.server.LocalServices", false, sys)
            val smiCls = Class.forName("com.android.server.sensors.SensorManagerInternal", false, sys)
            val localService = localServices.getMethod("getService", Class::class.java)
                .invoke(null, smiCls)
                ?: return 0L
            val sensorService = extractOuterInstance(localService, sys)
                ?: return 0L
            val ptr = readLongField(sensorService, "mPtr")
                ?: invokeNestGetter(sensorService)
                ?: 0L
            ptr
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "resolve SensorService.mPtr failed", t)
            0L
        }
    }

    /** system_server 应用类加载器（含 services.jar；LSPosed 下 getSystemClassLoader 会被替换成模块 loader）。 */
    private fun systemServerClassLoader(): ClassLoader? {
        // 优先使用 LSPosed SystemServerStartingParam 传入的 classLoader（即 system_server PathClassLoader）
        if (systemServerClassLoader != null) return systemServerClassLoader
        return try {
            // 兜底：system_server 中 com.android.server.SystemServer 由 PathClassLoader 加载（含 services.jar）
            Class.forName("com.android.server.SystemServer").classLoader
                ?: runCatching {
                    val app = Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication")
                        .invoke(null) as? Context
                    app?.classLoader
                }.getOrNull()
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "resolve system_server classloader failed", t)
            null
        }
    }

    /** 反射读取内部类对象的外类引用：优先 this$0，其次按字段类型匹配。 */
    private fun extractOuterInstance(inner: Any, sys: ClassLoader): Any? {
        var cls: Class<*>? = inner.javaClass
        while (cls != null) {
            runCatching {
                val f = cls.getDeclaredField("this\$0")
                f.isAccessible = true
                return f.get(inner)
            }
            runCatching {
                val target = Class.forName("com.android.server.sensors.SensorService", false, sys)
                for (f in cls.declaredFields) {
                    if (f.type == target) {
                        f.isAccessible = true
                        return f.get(inner)
                    }
                }
            }
            cls = cls.superclass
        }
        return null
    }

    /** 多候选读取 long 字段（R8 可能重命名）。 */
    private fun readLongField(obj: Any, vararg names: String): Long? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            for (name in names) {
                runCatching {
                    val f = cls.getDeclaredField(name)
                    f.isAccessible = true
                    return f.getLong(obj)
                }
            }
            cls = cls.superclass
        }
        return null
    }

    /** 通过 R8 Nest 桥方法读取 mPtr（`m869$$Nest$fgetmPtr` 模式）。 */
    private fun invokeNestGetter(instance: Any): Long? {
        return try {
            val cls = instance.javaClass
            for (m in cls.declaredMethods) {
                val name = m.name
                if (name.contains("Nest\$fgetmPtr", ignoreCase = true) || name.endsWith("\$\$Nest\$fgetmPtr")) {
                    m.isAccessible = true
                    return m.invoke(null, instance) as? Long
                }
            }
            null
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "invoke Nest getter for mPtr failed", t)
            null
        }
    }

    /**
     * 直接调用 SensorService.sendRuntimeSensorEventNative(mPtr, handle, type, ts, values)。
     *
     * 注意：Java 侧 LocalService.sendSensorEvent 会检查 mRuntimeSensorHandles，
     * 这里绕过该检查用真实 handle 注入。values 长度要求：
     * - STEP_COUNTER(19) 不在 type 掩码 → 必须 >=17（用 20 槽位）
     * - STEP_DETECTOR(18) 单值
     * - ACCELEROMETER(1) 三值
     */
    private fun invokeSendRuntimeSensorEventNative(
        sensor: Sensor,
        type: Int,
        values: FloatArray
    ): Boolean {
        return try {
            val ptr = nativeSensorServicePtr
            if (ptr == 0L) return false
            val handle = runCatching {
                sensor.javaClass.getMethod("getHandle").invoke(sensor) as Int
            }.getOrNull() ?: return false
            val payload = when (type) {
                VirtualSensorConfig.TYPE_ACCELEROMETER -> FloatArray(3).also { System.arraycopy(values, 0, it, 0, minOf(values.size, 3)) }
                VirtualSensorConfig.TYPE_STEP_DETECTOR -> floatArrayOf(values.firstOrNull() ?: 1f)
                // STEP_COUNTER：JNI 桥要求 len<17（>=17 报 "exceeds the maximum"），memcpy(len*4, 上限80B)
                else -> FloatArray(16).also { System.arraycopy(values, 0, it, 0, minOf(values.size, 16)) }
            }
            // 临时诊断：确认注入的 payload 首值（验证引擎步数是否真正递增）
            ZLog.d(TAG_SCOPE, "inject payload type=$type v0=${payload[0]}")
            val clazz = Class.forName(
                "com.android.server.sensors.SensorService",
                false,
                systemServerClassLoader() ?: return false
            )
            val method = findSendRuntimeSensorEventMethod(clazz) ?: return false
            method.isAccessible = true
            (method.invoke(null, ptr, handle, type, SystemClock.elapsedRealtimeNanos(), payload) as? Boolean) == true
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "sendRuntimeSensorEventNative type=$type failed", t)
            false
        }
    }

    /** 定位 sendRuntimeSensorEventNative 方法（R8 可能重命名，用名称+签名多候选匹配）。 */
    private fun findSendRuntimeSensorEventMethod(clazz: Class<*>): java.lang.reflect.Method? {
        val sig = arrayOf(
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            FloatArray::class.java
        )
        for (name in listOf("sendRuntimeSensorEventNative", "sendRuntimeSensorEvent", "m881\$\$Nest\$smsendRuntimeSensorEventNative")) {
            runCatching {
                val m = clazz.getDeclaredMethod(name, *sig)
                if (m.returnType == Boolean::class.javaPrimitiveType) return m
            }
        }
        // 兜底：遍历 declaredMethods，名称含 RuntimeSensorEvent 且签名匹配
        return runCatching {
            clazz.declaredMethods.firstOrNull { m ->
                (m.name.contains("RuntimeSensorEvent") || m.name.contains("sendRuntimeSensorEvent")) &&
                    m.parameterTypes.contentEquals(sig) &&
                    m.returnType == Boolean::class.javaPrimitiveType
            }
        }.getOrNull()
    }

    // ---------- 通道 1：@SystemApi Data Injection 反射（system_server 有权限） ----------

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
