package io.github.fairyxh.VirtualEnv.core.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.github.fairyxh.VirtualEnv.core.sensor.motion.ActivityMode
import io.github.fairyxh.VirtualEnv.core.sensor.motion.MotionProfile
import io.github.fairyxh.VirtualEnv.core.sensor.motion.VirtualMotionEngine
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 全局系统传感器后端（SensorService 系统级注入，多通道）。
 *
 * 运行在 **system_server** 进程。数据统一来自 [VirtualMotionEngine]
 * （人体运动模型），本类只负责探测/调度/注入。
 *
 * 通道 0（首选）：Native 全局 Hook —— `SensorEventQueue::write`
 * （libsensor.so）inline hook，所有离开 SensorService 的事件在写入
 * 连接共享内存前被改写，任何 App 均生效且无需作用域；实证见
 * `docs/reverse/native-sensor-hook-oplus15.md`。
 *
 * 通道 1（回退）：`SensorManager.initDataInjection / injectSensorData`
 * （@SystemApi，Oplus 15 实测 MODE_NOT_SUPPORTED，native 未实现）。
 *
 * 通道 2（回退）：`SensorService.sendRuntimeSensorEventNative`
 * （实证：仅更新 last-event 缓存，不进入 App 分发；保留 fail-open 探测）。
 *
 * 失败保护（fail-open）：所有反射/注入 try/catch，异常仅记日志；
 * stop() 仅停止调度（通道 2 无状态，无需恢复原生）。
 */
class SystemSensorBackend(
    private val sensorManagerProvider: () -> SensorManager?,
    private val engine: VirtualMotionEngine,
    private val systemServerClassLoader: ClassLoader? = null,
    private val moduleApkPath: String? = null,
    private val nativeLibDir: String? = null,
) : SensorBackend {

    companion object {
        private const val TAG_SCOPE = "SensorBackend"

        /** DATA_INJECTION / REPLAY / HAL_BYPASS_REPLAY（对应 SystemSensorManager switch 分支）。 */
        /** DATA_INJECTION / REPLAY / HAL_BYPASS_REPLAY（对应 SystemSensorManager switch 分支）。 */
        private const val MODE_DATA_INJECTION = 1

        /** HAL_BYPASS_REPLAY（绕过 sensor.isDataInjectionSupported()）。 */
        private const val MODE_HAL_BYPASS_REPLAY = 4

        /** 直连 injectSensorData（不先 initDataInjection 门禁；AIDL ISensors 标准接口）。 */
        private const val MODE_DIRECT_INJECT = 2

        /** 通道 2：SensorService.sendRuntimeSensorEventNative（native 事件注入）。 */
        private const val MODE_RUNTIME_EVENT_NATIVE = 10

        /** 通道 0：Native 全局 Hook（SensorEventQueue::write inline hook）。 */
        private const val MODE_NATIVE_GLOBAL = NativeSensorBridge.MODE_NATIVE_GLOBAL

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

    /** Native 通道送达探测：system_server 内注册真实监听，制造事件流驱动 deliveryVerified。 */
    private var probeThread: HandlerThread? = null
    private var probeListener: SensorEventListener? = null

    private var lastConfig: VirtualSensorConfig? = null

    override fun start() {
        synchronized(lock) {
            if (started) return
            // 通道 0（首选）：Native 全局 Hook，不依赖 SensorManager 就绪
            if (NativeSensorBridge.loadLibrary(moduleApkPath, nativeLibDir)) {
                val rc = NativeSensorBridge.install()
                if (rc == 0 || rc == 1) {
                    activeMode = MODE_NATIVE_GLOBAL
                    started = true
                    reason = ""
                    ZLog.i(TAG_SCOPE, "[✓] Native global sensor hook installed (mode=$MODE_NATIVE_GLOBAL rc=$rc)")
                    applyNativeConfig(lastConfig)
                    startDeliveryProbe()
                    return
                }
                ZLog.w(TAG_SCOPE, "Native global hook unavailable rc=$rc, fallback to Java channels")
            }
            val sm = sensorManagerProvider() ?: run {
                reason = "SENSOR_MANAGER_UNAVAILABLE"
                ZLog.w(TAG_SCOPE, "SystemSensorBackend start failed: SensorManager unavailable")
                return
            }
            var mode = detectInjectionMode(sm)
            if (mode < 0) {
                // 直连 injectSensorData 探测：不依赖 initDataInjection 门禁
                // （AIDL ISensors.injectSensorData 是标准接口，Oplus15 可能支持但 framework 门禁拒绝）
                if (probeDirectInject(sm)) {
                    mode = MODE_DIRECT_INJECT
                    ZLog.i(TAG_SCOPE, "Data Injection mode probe failed, direct injectSensorData probe OK (mode=2)")
                }
            }
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
            if (activeMode == MODE_NATIVE_GLOBAL) {
                // Native 通道：卸载 hook 恢复原指令（fail-open）
                stopDeliveryProbe()
                val rc = NativeSensorBridge.uninstall()
                // 删除提取的 .so：SELinux context 随文件删除释放，不残留规则
                NativeSensorBridge.cleanup()
                ZLog.i(TAG_SCOPE, "SystemSensorBackend stopped, native hook uninstalled rc=$rc (original restored)")
                activeMode = -1
                return
            }
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
        engine.updateProfile(config?.toMotionProfile() ?: MotionProfile.STATIONARY)
        if (activeMode == MODE_NATIVE_GLOBAL) {
            // Native 通道：直接同步配置到 native 引擎（无调度器）
            applyNativeConfig(config)
            return
        }
        if (old?.enabled != config?.enabled || old?.stepFrequency != config?.stepFrequency ||
            old?.mode != config?.mode || old?.amplitude != config?.amplitude
        ) {
            synchronized(lock) {
                if (!started) return
                // 频率/模式变化：重建注入调度
                entries.values.forEach { it.future.cancel(false) }
                entries.clear()
                scheduler?.let { startSchedules(it) }
            }
        }
    }

    override fun getStatus(): SensorBackendStatus {
        if (activeMode == MODE_NATIVE_GLOBAL) {
            val st = NativeSensorBridge.status()
            return SensorBackendStatus(
                type = if (started) SensorBackendType.SYSTEM else SensorBackendType.NONE,
                started = started,
                injectMode = MODE_NATIVE_GLOBAL,
                reason = reason,
                eventCount = st.optLong("eventsRewritten", 0L),
                deliveryVerified = st.optBoolean("deliveryVerified", false),
                detail = st.toString(),
            )
        }
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

    /**
     * Native 通道送达探测：system_server 内注册一个真实 ACCEL 监听。
     * 背景：App 侧 LEGACY 拦截 registerListener 后，SensorService 没有真实
     * 客户端，事件流不经过 SensorEventQueue::write，Native 改写永远无法
     * 证实送达（deliveryVerified 死锁）。注册本监听后 SensorService 激活
     * 传感器并持续向该连接写事件 → 必经 write → Native 重写 → deliveryVerified。
     * 送达后 App 侧才抑制 LEGACY，随后 App 重新注册走真实全局链路。
     */
    private fun startDeliveryProbe() {
        try {
            if (probeThread != null) return
            val sm = sensorManagerProvider() ?: run {
                ZLog.w(TAG_SCOPE, "delivery probe: sensor manager unavailable")
                return
            }
            val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: run {
                ZLog.w(TAG_SCOPE, "delivery probe: no accel sensor")
                return
            }
            val th = HandlerThread("zve-native-probe").apply { start() }
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) = Unit
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            if (sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI, Handler(th.looper))) {
                probeThread = th
                probeListener = listener
                ZLog.i(TAG_SCOPE, "native delivery probe registered (accel, delay=UI)")
            } else {
                th.quitSafely()
                ZLog.w(TAG_SCOPE, "native delivery probe register failed")
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "native delivery probe start failed", t)
        }
    }

    private fun stopDeliveryProbe() {
        try {
            val sm = sensorManagerProvider()
            probeListener?.let { sm?.unregisterListener(it) }
            probeListener = null
            probeThread?.quitSafely()
            probeThread = null
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "native delivery probe stop failed", t)
        }
    }

    /** 同步配置到 Native 引擎（模式/步频/速度/幅度/噪声）。 */
    private fun applyNativeConfig(config: VirtualSensorConfig?) {
        val cfg = config ?: VirtualSensorConfig()
        val profile = cfg.toMotionProfile()
        val mode = when (profile.activity) {
            ActivityMode.STATIONARY -> 0
            ActivityMode.RUN -> 2
            else -> 1
        }
        NativeSensorBridge.setConfig(
            enabled = cfg.enabled,
            mode = mode,
            stepFrequency = profile.stepFrequency.toFloat(),
            speedKmh = profile.effectiveSpeedKmh.toFloat(),
            amplitude = profile.amplitudeOverride ?: -1f,
            randomNoise = profile.randomNoise,
            headingDeg = 0f,
            initialStepCount = cfg.stepCount,
        )
        ZLog.d(TAG_SCOPE, "native config: enabled=${cfg.enabled} mode=$mode steps=${profile.stepFrequency} speed=${profile.effectiveSpeedKmh} amp=${profile.amplitudeOverride ?: -1f} noise=${profile.randomNoise}")
    }

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

    /** 直连 injectSensorData 探测：选 ACCEL（无权限门槛）注入一帧，返回是否成功。 */
    private fun probeDirectInject(sm: SensorManager): Boolean {
        return try {
            val sensor = findSensor(sm, VirtualSensorConfig.TYPE_ACCELEROMETER) ?: return false
            // 1) Java 包装层（会过 isDataInjectionSupported 门禁）
            val javaOk = invokeInjectSensorData(
                sm, sensor, floatArrayOf(0f, 0f, 9.81f), 3, SystemClock.elapsedRealtimeNanos()
            )
            ZLog.i(TAG_SCOPE, "probeDirectInject(accel java) -> $javaOk")
            if (javaOk) return true
            // 2) 绕过 Java 门禁：直接调 SystemSensorManager.nativeInjectSensorData
            val nativeOk = invokeNativeInjectSensorData(
                sm, sensor, floatArrayOf(0f, 0f, 9.81f), 3, SystemClock.elapsedRealtimeNanos()
            )
            ZLog.i(TAG_SCOPE, "probeDirectInject(accel native) -> $nativeOk")
            nativeOk
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "probeDirectInject failed", t)
            false
        }
    }

    /** 绕过 Java 门禁：反射调用 SystemSensorManager.nativeInjectSensorData(instance, handle, values, accuracy, ts)。 */
    private fun invokeNativeInjectSensorData(
        sm: SensorManager,
        sensor: Sensor,
        values: FloatArray,
        accuracy: Int,
        timestampNanos: Long
    ): Boolean {
        return try {
            // sm 本身就是 SystemSensorManager 实例（继承 SensorManager），直接取其 native 指针与方法
            val ssmClass = sm.javaClass
            val handle = sensor.javaClass.getMethod("getHandle").invoke(sensor) as Int

            // 1) mInstance（long native 指针）
            val instance = readLongField(sm, "mInstance", "mNativeInstance", "mPtr") ?: run {
                ZLog.w(TAG_SCOPE, "invokeNativeInject: mInstance not found; fields=${ssmClass.declaredFields.map { it.name }}")
                return false
            }

            // 2) 定位绕过门禁的实例方法：injectSensorDataImpl(Sensor, float[], int, long) -> boolean
            //    （R8 可能改名，按签名匹配）
            val sig = arrayOf(
                Sensor::class.java,
                FloatArray::class.java,
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType
            )
            val method = ssmClass.declaredMethods.firstOrNull { m ->
                (m.name.contains("injectSensorData", ignoreCase = true)) &&
                    m.parameterTypes.contentEquals(sig) &&
                    m.returnType == Boolean::class.javaPrimitiveType
            } ?: run {
                val names = ssmClass.declaredMethods
                    .filter { it.name.contains("inject", ignoreCase = true) }
                    .map { it.name + "(" + it.parameterTypes.joinToString { p -> p.simpleName } + ")" }
                ZLog.w(TAG_SCOPE, "invokeNativeInject: method not found; methods=$names")
                return false
            }
            method.isAccessible = true
            val ret = method.invoke(sm, sensor, values, accuracy, timestampNanos) as? Boolean
            ZLog.i(TAG_SCOPE, "invokeNativeInject: method=${method.name} handle=$handle -> $ret")
            ret == true
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "invokeNativeInjectSensorData failed", t)
            false
        }
    }

    private fun startSchedules(exec: ScheduledExecutorService) {
        val cfg = lastConfig
        val sm = sensorManagerProvider() ?: return
        val types = cfg?.sensorTypes ?: VirtualSensorConfig.DEFAULT_SENSOR_TYPES
        for (type in types) {
            val sensor = findSensor(sm, type) ?: continue
            val period = when (type) {
                VirtualSensorConfig.TYPE_STEP_COUNTER, VirtualSensorConfig.TYPE_STEP_DETECTOR ->
                    VirtualSensorEngine.stepInjectPeriodMs(cfg?.stepFrequency ?: 120)
                else -> ACCEL_PERIOD_MS
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
            val values = engine.sample(type) ?: return
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
                VirtualSensorConfig.TYPE_ACCELEROMETER,
                VirtualSensorConfig.TYPE_LINEAR_ACCELERATION,
                VirtualSensorConfig.TYPE_GRAVITY,
                VirtualSensorConfig.TYPE_GYROSCOPE,
                VirtualSensorConfig.TYPE_MAGNETIC_FIELD -> FloatArray(3).also {
                    System.arraycopy(values, 0, it, 0, minOf(values.size, 3))
                }
                VirtualSensorConfig.TYPE_STEP_DETECTOR -> floatArrayOf(values.firstOrNull() ?: 1f)
                else -> floatArrayOf(values.firstOrNull() ?: 0f)
            }
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
