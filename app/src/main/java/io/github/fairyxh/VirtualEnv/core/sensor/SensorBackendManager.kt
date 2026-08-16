package io.github.fairyxh.VirtualEnv.core.sensor

import io.github.fairyxh.VirtualEnv.core.EnvStateCache
import io.github.fairyxh.VirtualEnv.hook.AppHookSensorBackend
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * 传感器后端管理器（统一门面）。
 *
 * 业务层 / UI 只与 [SensorBackendManager] 交互，不感知具体后端实现：
 *
 * ```
 * VirtualSensorEngine
 *        ↓
 * SensorBackendManager
 *        ├─ SystemSensorBackend   （全局模式：SensorService Data Injection，system_server）
 *        └─ AppHookSensorBackend  （兼容模式：SensorManager.registerListener Hook，App 进程）
 * ```
 *
 * 进程角色：
 * - **system_server**：调用 [initSystemServer]，探测 Data Injection 能力并启动全局后端；
 * - **App 进程**：调用 [initAppProcess]，包装 legacy Hook；若全局后端已生效则自动抑制本地注入。
 *
 * 选择策略（配置 `sensor.backend`）：
 * - `auto`：SYSTEM 探测成功 → SYSTEM；失败 → LEGACY（回退）；
 * - `system`：强制 SYSTEM，失败则停用（不回退）；
 * - `legacy`：强制 LEGACY。
 */
object SensorBackendManager {

    private const val TAG_SCOPE = "SensorBackend"

    private enum class Role { NONE, SYSTEM_SERVER, APP_PROCESS }

    private val role = AtomicReference(Role.NONE)
    private val statusRef = AtomicReference(SensorBackendStatus())

    // system_server 侧
    private var systemBackend: SystemSensorBackend? = null
    private var engine: VirtualSensorEngine? = null
    private var systemConfigProvider: (() -> VirtualSensorConfig?)? = null

    // App 进程侧
    private var appBackend: AppHookSensorBackend? = null

    // ---------- 初始化 ----------

    /** system_server 初始化：创建引擎 + 全局后端。configProvider 返回当前 sensor 配置。 */
    fun initSystemServer(configProvider: () -> VirtualSensorConfig?) {
        synchronized(this) {
            if (role.get() == Role.SYSTEM_SERVER) return
            role.set(Role.SYSTEM_SERVER)
            systemConfigProvider = configProvider
            engine = VirtualSensorEngine(configProvider)
            systemBackend = SystemSensorBackend(
                sensorManagerProvider = { SystemSensorBackend.systemServerSensorManager() },
                engine = engine!!
            )
            ZLog.i(TAG_SCOPE, "SensorBackendManager initialized for system_server")
        }
    }

    /** App 进程初始化：包装 legacy Hook。 */
    fun initAppProcess(cache: EnvStateCache) {
        synchronized(this) {
            if (role.get() == Role.APP_PROCESS) return
            role.set(Role.APP_PROCESS)
            appBackend = AppHookSensorBackend(cache)
            ZLog.i(TAG_SCOPE, "SensorBackendManager initialized for app process")
        }
    }

    /** 当前进程是否已初始化。 */
    fun isInitialized(): Boolean = role.get() != Role.NONE

    // ---------- 生命周期 ----------

    /**
     * 启动后端：按配置选择策略执行探测与切换。
     *
     * system_server：探测 Data Injection，成功 → SYSTEM；auto 失败 → LEGACY（状态记录，App 进程回退）；
     * App 进程：读取最近状态，若 SYSTEM 已生效则抑制本地注入，否则启动 LEGACY。
     */
    fun start() {
        when (role.get()) {
            Role.SYSTEM_SERVER -> startSystemSide()
            Role.APP_PROCESS -> startAppSide()
            Role.NONE -> ZLog.w(TAG_SCOPE, "SensorBackendManager.start() called before init")
        }
    }

    /** 停止当前后端并恢复原生行为。 */
    fun stop() {
        synchronized(this) {
            systemBackend?.stop()
            appBackend?.stop()
            statusRef.set(SensorBackendStatus())
            ZLog.i(TAG_SCOPE, "SensorBackendManager stopped")
        }
    }

    /** 更新配置（UI / 引擎变化时调用）；后端自行决定是否重启注入。 */
    fun updateConfig(config: VirtualSensorConfig?) {
        synchronized(this) {
            systemBackend?.updateConfig(config)
            appBackend?.updateConfig(config)
        }
        start() // 配置变化后重新执行选择（启用/停用）
    }

    /** 当前生效后端状态（UI / 日志 / 跨进程同步用）。 */
    fun getStatus(): SensorBackendStatus = statusRef.get()

    /** 状态 JSON（附加到 sensor env status 供 App 进程同步）。 */
    fun statusJson(): JSONObject = statusRef.get().toJson()

    // ---------- App 进程 Hook 层转发 ----------

    /** registerListener Hook 回调（仅 App 进程调用）。 */
    fun onListenerRegistered(listener: Any, sensor: Any, type: Int) {
        appBackend?.onListenerRegistered(listener, sensor, type)
    }

    /** unregisterListener Hook 回调（仅 App 进程调用）。 */
    fun onListenerUnregistered(listener: Any) {
        appBackend?.onListenerUnregistered(listener)
    }

    /** 周期刷新（App 进程 EnvStateCache 变化后调用）。 */
    fun refresh() {
        appBackend?.refresh()
    }

    /** App 进程从跨进程状态感知 SYSTEM 后端生效时抑制本地注入。 */
    fun onSystemBackendStatus(systemStatus: SensorBackendStatus) {
        val backend = appBackend ?: return
        if (systemStatus.type == SensorBackendType.SYSTEM && systemStatus.started) {
            backend.suppress()
        } else {
            backend.unsuppress()
        }
    }

    // ---------- 内部 ----------

    private fun startSystemSide() {
        val backend = systemBackend ?: return
        val provider = systemConfigProvider ?: return
        val cfg = provider() ?: VirtualSensorConfig()
        synchronized(this) {
            backend.stop()
            if (!cfg.enabled) {
                statusRef.set(
                    SensorBackendStatus(
                        type = SensorBackendType.NONE,
                        started = false,
                        reason = "SENSOR_SIM_DISABLED"
                    )
                )
                ZLog.i(TAG_SCOPE, "Sensor Backend Manager:\n[!] Sensor simulation disabled\nSelected backend: NONE")
                return
            }
            val preference = cfg.backend.ifBlank { VirtualSensorConfig.BACKEND_AUTO }
            if (preference == VirtualSensorConfig.BACKEND_LEGACY) {
                statusRef.set(
                    SensorBackendStatus(
                        type = SensorBackendType.LEGACY,
                        started = false,
                        reason = "USER_PREF_LEGACY"
                    )
                )
                ZLog.i(TAG_SCOPE, "Sensor Backend Manager:\n[!] User preference legacy\nSelected backend: LEGACY")
                return
            }
            // auto / system：尝试全局后端
            backend.updateConfig(cfg)
            backend.start()
            val status = backend.getStatus()
            val ok = status.type == SensorBackendType.SYSTEM && status.started
            if (ok) {
                statusRef.set(status)
                ZLog.i(TAG_SCOPE, "Sensor Backend Manager:\n[✓] System injection available\nSelected backend: SYSTEM")
            } else if (preference == VirtualSensorConfig.BACKEND_SYSTEM) {
                statusRef.set(status.copy(reason = "FORCED_SYSTEM_FAILED"))
                ZLog.w(TAG_SCOPE, "Sensor Backend Manager:\n[!] Forced system backend unavailable\nReason: ${status.reason}\nSelected backend: NONE")
            } else {
                statusRef.set(
                    SensorBackendStatus(
                        type = SensorBackendType.LEGACY,
                        started = false,
                        reason = status.reason.ifBlank { "SYSTEM_INJECTION_FAILED" }
                    )
                )
                ZLog.w(TAG_SCOPE, "Sensor Backend Manager:\n[!] Sensor injection unavailable\nReason: ${status.reason.ifBlank { "UNKNOWN" }}\nFallback: LEGACY App Hook enabled")
            }
        }
    }

    private fun startAppSide() {
        val backend = appBackend ?: return
        synchronized(this) {
            // 全局后端状态由 EnvStateCache 同步（getStatus 来源为 system_server 附加字段）
            val sysStatus = statusRef.get()
            if (sysStatus.type == SensorBackendType.SYSTEM && sysStatus.started) {
                backend.suppress()
                ZLog.i(TAG_SCOPE, "App process: system backend active, local hook suppressed")
            } else {
                backend.unsuppress()
                backend.start()
                ZLog.i(TAG_SCOPE, "App process: legacy app hook backend enabled")
            }
        }
    }

    /** App 进程侧更新跨进程 system 状态（EnvStateCache 轮询写入）。 */
    fun updateSystemStatusFromCache(systemStatus: SensorBackendStatus) {
        statusRef.set(systemStatus)
        onSystemBackendStatus(systemStatus)
    }
}
