package io.github.fairyxh.VirtualEnv.app.cell

import android.content.Context
import android.content.SharedPreferences

/**
 * OpenCellID 设置存储（BYOK）。
 *
 * - API Key 由用户自行填写，仅保存在本地 SharedPreferences，不写入日志/源码/Git。
 * - 贡献开关默认关闭，只有用户明确开启后才采集并上传真实测量数据。
 */
object OpenCellIdSettings {

    private const val PREFS = "opencellid_config"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_CONTRIBUTE = "contribute_enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getApiKey(context: Context): String? =
        prefs(context).getString(KEY_API_KEY, "")?.trim()?.ifEmpty { null }

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun clearApiKey(context: Context) {
        prefs(context).edit().remove(KEY_API_KEY).apply()
    }

    /** 是否参与 OpenCellID 数据贡献（默认关闭）。 */
    fun isContributeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONTRIBUTE, false)

    fun setContributeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONTRIBUTE, enabled).apply()
    }

    /** 脱敏显示：只保留前 4 位，其余打码。 */
    fun maskKey(key: String?): String {
        if (key.isNullOrBlank()) return ""
        return if (key.length <= 4) "****" else key.take(4) + "****" + key.takeLast(2)
    }

    /** 日志脱敏：禁止打印完整 Key。 */
    fun logSafe(key: String?): String = if (key.isNullOrBlank()) "(empty)" else maskKey(key)
}
