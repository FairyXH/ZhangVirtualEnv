package io.github.fairyxh.VirtualEnv.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 控制端全局未捕获异常处理。
 *
 * - 接管主线程 / 全部线程的未捕获异常，避免“点击后闪退回桌面”时无从排查。
 * - 崩溃信息写入模块私有目录 `crash/crash_yyyyMMdd_HHmmss.txt`。
 * - 写入 LogStore（内存）+ 弹窗 [CrashReportActivity] 展示日志与导出入口。
 * - 最后仍透传给原默认 handler，保证系统行为不被破坏。
 */
object CrashCatcher {

    private const val TAG_SCOPE = "Crash"
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    @Volatile
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastCrashAt = 0L

    /** 是否已安装（防止重复安装）。 */
    @Volatile
    private var installed = false

    /** 崩溃弹窗冷却（同一进程崩溃可能连环触发；避免刷屏）。 */
    private const val DIALOG_COOLDOWN_MS = 2000L

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        appContext = context.applicationContext
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handle(thread, throwable)
            originalHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun handle(thread: Thread, throwable: Throwable) {
        try {
            val now = System.currentTimeMillis()
            LogStore.recordCrash(thread, throwable)
            val file = persistCrash(thread, throwable)
            if (now - lastCrashAt > DIALOG_COOLDOWN_MS) {
                lastCrashAt = now
                showDialog(file)
            }
        } catch (_: Throwable) {
            // 捕获器自身异常绝不允许再抛
        }
        android.util.Log.e(TAG_SCOPE, "uncaught exception on ${thread.name}", throwable)
    }

    /** 崩溃内容落盘到私有目录，返回文件。 */
    @SuppressLint("DefaultLocale")
    private fun persistCrash(thread: Thread, throwable: Throwable): File {
        val context = appContext ?: throw IllegalStateException("CrashCatcher not installed")
        val dir = File(context.filesDir, "crash").apply { mkdirs() }
        val name = "crash_${dateFormat.format(Date())}_${android.os.Process.myTid()}.txt"
        val file = File(dir, name)
        val sb = StringBuilder()
        sb.append("ZhangVirtualEnv 崩溃报告\n")
        sb.append("时间: ").append(dateFormat.format(Date())).append('\n')
        sb.append("线程: ").append(thread.name).append('\n')
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" / Android ").append(Build.VERSION.RELEASE).append(" (API ")
            .append(Build.VERSION.SDK_INT).append(")\n")
        sb.append('\n').append(throwable.javaClass.name).append(": ")
            .append(throwable.message ?: "").append('\n')
        sb.append(throwable.stackTrace.joinToString("\n") { "    at $it" }).append('\n')
        var cause = throwable.cause
        while (cause != null) {
            sb.append("Caused by: ").append(cause.javaClass.name).append(": ")
                .append(cause.message ?: "").append('\n')
            sb.append(cause.stackTrace.joinToString("\n") { "    at $it" }).append('\n')
            cause = cause.cause
        }
        sb.append('\n').append("== 崩溃前运行日志 ==").append('\n')
        LogStore.snapshot().takeLast(80).forEach { sb.append(it).append('\n') }
        file.writeText(sb.toString())
        return file
    }

    private fun showDialog(file: File) {
        val context = appContext ?: return
        val intent = Intent(context, io.github.fairyxh.VirtualEnv.app.CrashReportActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.putExtra(io.github.fairyxh.VirtualEnv.app.CrashReportActivity.EXTRA_CRASH_FILE, file.absolutePath)
        try {
            context.startActivity(intent)
        } catch (_: Throwable) {
        }
    }
}
