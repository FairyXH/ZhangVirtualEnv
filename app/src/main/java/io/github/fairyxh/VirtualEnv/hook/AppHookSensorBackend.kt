package io.github.fairyxh.VirtualEnv.hook

import io.github.fairyxh.VirtualEnv.core.EnvStateCache
import io.github.fairyxh.VirtualEnv.core.sensor.SensorBackend
import io.github.fairyxh.VirtualEnv.core.sensor.SensorBackendStatus
import io.github.fairyxh.VirtualEnv.core.sensor.SensorBackendType
import io.github.fairyxh.VirtualEnv.core.sensor.VirtualSensorConfig
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 应用兼容传感器后端（Legacy App Hook）。
 *
 * 包装现有 [StepSensorInjector]，**不修改其核心逻辑**：仍通过
 * `SensorManager.registerListener` Hook 在 **scope 内 App 进程**注入事件。
 *
 * 新增统一接口：
 * - [start]/[stop]/[updateConfig]/[getStatus] 与 SystemSensorBackend 一致；
 * - 由 [io.github.fairyxh.VirtualEnv.core.sensor.SensorBackendManager] 统一调度，
 *   业务层不直接触碰本类。
 *
 * 双重注入防护：当 system_server 的全局后端（SYSTEM）已生效时，
 * 本后端保持 inactive（不注入），避免同一 App 收到两份虚拟事件。
 */
class AppHookSensorBackend(private val cache: EnvStateCache) : SensorBackend {

    companion object {
        private const val TAG_SCOPE = "SensorBackend"
    }

    private val injector = StepSensorInjector(cache)

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
        // 不 shutdown StepSensorInjector 内部 scheduler：unsuppress 后仍可复用
        injector.stopAll()
        ZLog.i(TAG_SCOPE, "AppHookSensorBackend stopped")
    }

    override fun updateConfig(config: VirtualSensorConfig?) {
        // Legacy 后端状态源为 EnvStateCache，无需直接应用 config；
        // 仅用于刷新判定（系统全局后端是否接管）。
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

    /** 系统全局后端已接管：暂停本地注入（避免双重注入）。 */
    fun suppress() {
        if (suppressed) return
        suppressed = true
        injector.stopAll()
        ZLog.i(TAG_SCOPE, "AppHookSensorBackend suppressed (system backend active)")
    }

    /** 恢复本地注入。 */
    fun unsuppress() {
        suppressed = false
        refresh()
        ZLog.i(TAG_SCOPE, "AppHookSensorBackend unsuppressed (legacy hook active)")
    }

    fun onListenerRegistered(listener: Any, sensor: Any, type: Int) {
        if (!active || suppressed) return
        injector.onListenerRegistered(listener, sensor, type)
    }

    fun onListenerUnregistered(listener: Any) {
        injector.onListenerUnregistered(listener)
    }

    fun refresh() {
        if (!active || suppressed) return
        // 系统全局后端生效 → 本地不注入（suppress 已 stopAll，这里直接跳过）
        injector.refresh()
    }

    fun shutdown() {
        stop()
    }
}
