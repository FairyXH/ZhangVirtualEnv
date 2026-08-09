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
 */
class EnvStateEngine(private val engineName: String) {

    private val state = AtomicReference(JSONObject())

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

    /** 清除虚拟环境数据（放行真实数据）。 */
    fun clear() {
        state.set(JSONObject())
        ZLog.i("Core", "EnvStateEngine[$engineName] cleared")
    }

    /** 返回当前虚拟数据；未启用时返回 null（Hook 放行真实数据）。 */
    fun currentData(): JSONObject? {
        val s = state.get()
        return if (s.optBoolean("enabled", false)) s.optJSONObject("data") else null
    }

    /** 状态快照（供 App 展示）。 */
    fun statusJson(): JSONObject = state.get()
}
