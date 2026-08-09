package io.github.fairyxh.VirtualEnv.util

import android.util.Log

/**
 * 统一日志门面。
 *
 * 所有模块代码必须通过 [ZLog] 输出日志，统一 tag 便于真机 logcat 过滤。
 * - hook 层日志带 [Hook] 前缀
 * - core 层日志带 [Core] 前缀
 * - app 层日志带 [App] 前缀
 */
object ZLog {
    const val TAG = "ZVirtualEnv"

    @Volatile
    var debugEnabled: Boolean = true

    fun d(scope: String, msg: String) {
        if (debugEnabled) Log.d(TAG, "[$scope] $msg")
    }

    fun i(scope: String, msg: String) {
        Log.i(TAG, "[$scope] $msg")
    }

    fun w(scope: String, msg: String, t: Throwable? = null) {
        if (t == null) Log.w(TAG, "[$scope] $msg") else Log.w(TAG, "[$scope] $msg", t)
    }

    fun e(scope: String, msg: String, t: Throwable? = null) {
        if (t == null) Log.e(TAG, "[$scope] $msg") else Log.e(TAG, "[$scope] $msg", t)
    }
}
