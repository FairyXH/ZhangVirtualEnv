package io.github.fairyxh.VirtualEnv.core

import org.json.JSONObject

/**
 * 各进程 Hook 点注册状态登记（进程内）。
 *
 * VirtualEnvEntry 的 registrar 每次注册后调用 [record] 记录
 * “类.方法(参数类型)” 与成功/失败；快照通过 [HookStatusReporter] 上报给
 * system_server 的 Backend（/api/hook/status），用于设置页展示各作用域
 * Hook 状态并导出调试报告。
 */
object HookStatusRegistry {

    private val lock = Any()
    @Volatile
    private var processName: String = "unknown"

    private val entries = LinkedHashMap<String, Boolean>()

    fun setProcess(name: String) {
        synchronized(lock) { processName = name }
    }

    fun reset() {
        synchronized(lock) { entries.clear() }
    }

    fun record(key: String, ok: Boolean) {
        synchronized(lock) { entries[key] = ok }
    }

    fun snapshot(): JSONObject {
        return synchronized(lock) {
            val points = JSONObject()
            var ok = 0
            var fail = 0
            entries.forEach { (k, v) ->
                points.put(k, v)
                if (v) ok++ else fail++
            }
            JSONObject().apply {
                put("process", processName)
                put("hooked", ok)
                put("failed", fail)
                put("points", points)
            }
        }
    }
}
