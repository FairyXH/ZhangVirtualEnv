package io.github.fairyxh.VirtualEnv.core.engine

import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * 环境模拟状态引擎（WiFi / Cell / BLE / GNSS 共用）。
 *
 * 保存用户从控制端加载的虚拟环境数据（JSON），Hook 层通过 [currentData] 读取。
 * 不感知具体 Android 接口，职责边界与 LocationEngine 一致：
 * Hook 层只读取快照，不保存业务状态。
 *
 * 自动托管（autoManaged）：
 * 勾选后用户配置不再直接生效，[currentData] 返回 [autoDataProvider] 生成的
 * “最优环境配置”（由 Backend 注入，基于当前虚拟位置自动派生合法的基站 /
 * 卫星 / WiFi / BLE 数据）。用于适配百度等对 GPS 卫星数、NMEA 一致性、
 * 基站 ID 合法性要求严格的定位 SDK。是否启用该类型模拟仍由用户开关决定。
 */
class EnvStateEngine(private val engineName: String) {

    private val state = AtomicReference(JSONObject())

    /** 自动托管开关：true 时 [currentData] 忽略用户配置，返回自动生成数据。 */
    @Volatile
    var autoManaged: Boolean = false
        private set

    /** 自动数据生成器（Backend 注入）；autoManaged=true 且非 null 时使用。 */
    @Volatile
    var autoDataProvider: (() -> JSONObject?)? = null

    /** 当前是否启用了虚拟环境数据。 */
    fun isEnabled(): Boolean = state.get().optBoolean("enabled", false)

    /** 加载虚拟环境数据并启用。 */
    fun update(data: JSONObject) {
        state.set(
            JSONObject().apply {
                put("enabled", true)
                put("data", data)
            }
        )
        ZLog.i("Core", "EnvStateEngine[$engineName] updated: ${data.length()} keys")
    }

    /** 清除虚拟环境数据（放行真实数据），同时复位自动托管。 */
    fun clear() {
        state.set(JSONObject())
        autoManaged = false
        ZLog.i("Core", "EnvStateEngine[$engineName] cleared")
    }

    /**
     * 单独开关：关闭时保留已加载数据但 Hook 放行真实数据（currentData 返回 null）。
     * 开启时恢复上次加载的数据。
     */
    fun setEnabled(enabled: Boolean) {
        val s = state.get()
        val data = s.optJSONObject("data")
        if (data != null) {
            state.set(
                JSONObject().apply {
                    put("enabled", enabled)
                    put("data", data)
                }
            )
        } else {
            state.set(JSONObject().apply { put("enabled", enabled) })
        }
        ZLog.i("Core", "EnvStateEngine[$engineName] setEnabled=$enabled")
    }

    /** 设置自动托管开关。 */
    fun setAutoManaged(auto: Boolean) {
        autoManaged = auto
        ZLog.i("Core", "EnvStateEngine[$engineName] setAutoManaged=$auto")
    }

    /**
     * 返回当前虚拟数据；未启用时返回 null（Hook 放行真实数据）。
     * 自动托管开启时返回自动生成数据（用户配置被忽略）。
     */
    fun currentData(): JSONObject? {
        val s = state.get()
        if (!s.optBoolean("enabled", false)) return null
        if (autoManaged) {
            return autoDataProvider?.invoke() ?: s.optJSONObject("data")
        }
        return s.optJSONObject("data")
    }

    /** 返回用户原始配置（持久化用，不受自动托管影响）。 */
    fun userData(): JSONObject? = state.get().optJSONObject("data")

    /**
     * 状态快照（供 App 展示 / Hook 缓存）。
     * data 为当前**生效**数据：自动托管时是自动生成配置，Hook 层直接可用。
     */
    fun statusJson(): JSONObject {
        val s = state.get()
        return JSONObject().apply {
            put("enabled", s.optBoolean("enabled", false))
            put("autoManaged", autoManaged)
            currentData()?.let { put("data", it) }
        }
    }
}
