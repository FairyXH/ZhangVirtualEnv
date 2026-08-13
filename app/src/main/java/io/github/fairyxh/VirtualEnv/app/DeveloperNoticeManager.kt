package io.github.fairyxh.VirtualEnv.app

import android.content.Context
import android.content.SharedPreferences
import io.github.fairyxh.VirtualEnv.util.ZLog

/**
 * 开发者用途声明确认状态管理器。
 *
 * 首次启动必须展示开发者用途声明，用户点击「同意并继续」后记录确认状态；
 * 后续启动不再重复弹出，设置页「关于本项目」提供重新查看入口。
 */
object DeveloperNoticeManager {

    private const val TAG_SCOPE = "Notice"
    private const val PREFS = "zve_ui"
    private const val KEY_ACCEPTED = "developer_notice_accepted_v1"

    /** 用户是否已确认开发者用途声明。 */
    fun isAccepted(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ACCEPTED, false)
    }

    /** 保存用户确认状态（true=已同意，false=重置为未确认）。 */
    fun setAccepted(context: Context, accepted: Boolean) {
        prefs(context).edit().putBoolean(KEY_ACCEPTED, accepted).apply()
        ZLog.i(TAG_SCOPE, "developer notice accepted=$accepted")
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
