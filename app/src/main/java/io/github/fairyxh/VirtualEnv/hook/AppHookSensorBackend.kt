package io.github.fairyxh.VirtualEnv.hook

import io.github.fairyxh.VirtualEnv.core.EnvStateCache
import io.github.fairyxh.VirtualEnv.core.sensor.SensorBackend
import io.github.fairyxh.VirtualEnv.core.sensor.SensorBackendStatus
import io.github.fairyxh.VirtualEnv.core.sensor.SensorBackendType
import io.github.fairyxh.VirtualEnv.core.sensor.VirtualSensorConfig
import io.github.fairyxh.VirtualEnv.core.sensor.motion.MotionProfile
import io.github.fairyxh.VirtualEnv.core.sensor.motion.VirtualMotionEngine
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 应用兼容传感器后端（Legacy App Hook）。
 *
 * 包装 [StepSensorInjector]，运动数据统一来自 [VirtualMotionEngine]：
 * - [refresh] 周期从 EnvStateCache 解析 MotionProfile 并更新引擎；
 * - [onListenerRegistered] 返回是否接管（true=Hook 层屏蔽真实注册）。
 */
class AppHookSensorBackend(private val cache: EnvStateCache) : SensorBackend {

    companion object {
        private const val TAG_SCOPE = "SensorBackend"
    }

    private val motionEngine = VirtualMotionEngine()
    private val injector = StepSensorInjector(cache, motionEngine)

    @Volatile
    private var active = false

    @Volatile
    private var suppressed = false

    private var refreshExecutor: java.util.concurrent.ScheduledExecutorService? = null

    // ---------- SensorBackend 接口 ----------

    override fun start() {
        if (active) return
        active = true
        suppressed = false
        if (refreshExecutor == null || refreshExecutor!!.isShutdown) {
            refreshExecutor = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "ZVE-AppSensorRefresh").apply { isDaemon = true }
            }
        }
        refreshExecutor?.scheduleWithFixedDelay(
            { runCatching { refresh() } },
            300,
            500,
            TimeUnit.MILLISECONDS
        )
        ZLog.i(TAG_SCOPE, "AppHookSensorBackend started (legacy app hook)")
    }

    override fun stop() {
        active = false
        refreshExecutor?.shutdownNow()
        refreshExecutor = null
        injector.stopAll()
        ZLog.i(TAG_SCOPE, "AppHookSensorBackend stopped")
    }

    override fun updateConfig(config: VirtualSensorConfig?) {
        refresh()
    }

    override fun getStatus(): SensorBackendStatus {
        return SensorBackendStatus(
            type = SensorBackendType.LEGACY,
            started = active && !suppressed,
            reason = if (suppressed) "SYSTEM_BACKEND_ACTIVE" else "",
        )
    }

    // ---------- Hook 层转发（FrameworkEnvHookAdapter 调用） ----------

    fun suppress() {
        if (suppressed) return
        suppressed = true
        injector.stopAll()
        ZLog.i(TAG_SCOPE, "AppHookSensorBackend suppressed (system backend active)")
    }

    fun unsuppress() {
        if (!suppressed) return
        suppressed = false
        refresh()
        ZLog.i(TAG_SCOPE, "AppHookSensorBackend unsuppressed (legacy hook active)")
    }

    /**
     * 注册监听。返回 true 表示由注入器接管（Hook 层不 proceed 原注册，
     * 屏蔽真实传感器）；false 表示放行真实注册。
     */
    fun onListenerRegistered(listener: Any, sensor: Any, type: Int): Boolean {
        if (!active || suppressed) return false
        return injector.onListenerRegistered(listener, sensor, type)
    }

    fun onListenerUnregistered(listener: Any) {
        injector.onListenerUnregistered(listener)
    }

    fun refresh() {
        if (!active || suppressed) return
        try {
            val profile = MotionProfile.fromJson(cache.currentSensor())
            motionEngine.updateProfile(profile)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "update motion profile failed", t)
        }
        injector.refresh()
    }

    fun shutdown() {
        stop()
    }
}
