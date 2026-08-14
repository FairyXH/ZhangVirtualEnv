package io.github.fairyxh.VirtualEnv.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * 控制端运行日志环形存储。
 *
 * - 线程安全：任意线程可写（main / ApiClient 协程 / Hook 回调）。
 * - 内存保留最近 [MAX_LINES] 条，避免无界增长。
 * - 同时维护崩溃记录列表（含崩溃时间 / 线程 / 异常摘要）。
 * - 设置页实时展示；导出时合并为纯文本。
 */
object LogStore {

    const val MAX_LINES = 500
    const val MAX_CRASHES = 20

    private val lines: ConcurrentLinkedDeque<String> = ConcurrentLinkedDeque()
    private val crashes: ConcurrentLinkedDeque<CrashRecord> = ConcurrentLinkedDeque()
    private val idCounter = AtomicInteger(0)

    data class CrashRecord(
        val id: Int,
        val time: String,
        val thread: String,
        val summary: String,
        val stack: String,
    )

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** 追加一行日志（带时间戳前缀）。 */
    fun log(scope: String, level: String, message: String) {
        val stamp = timeFormat.format(Date())
        lines.addLast("$stamp [$level][$scope] $message")
        while (lines.size > MAX_LINES) lines.pollFirst()
    }

    /** 记录一次未捕获崩溃。 */
    fun recordCrash(thread: Thread, throwable: Throwable) {
        val time = dateFormat.format(Date())
        val summary = throwable.javaClass.simpleName + ": " + (throwable.message ?: "")
        val stack = throwable.stackTrace.joinToString("\n") { "    at $it" }
        crashes.addLast(CrashRecord(idCounter.incrementAndGet(), time, thread.name, summary, stack))
        while (crashes.size > MAX_CRASHES) crashes.pollFirst()
    }

    /** 当前全部内存日志（最新在后）。 */
    fun snapshot(): List<String> = lines.toList()

    /** 当前全部崩溃记录（最新在后）。 */
    fun crashRecords(): List<CrashRecord> = crashes.toList()

    /** 导出为纯文本（日志 + 崩溃记录）。 */
    fun exportText(): String {
        val sb = StringBuilder()
        sb.append("ZhangVirtualEnv 运行日志\n")
        sb.append("导出时间: ").append(dateFormat.format(Date())).append('\n')
        sb.append("== 崩溃记录 ==").append('\n')
        val cr = crashes.toList()
        if (cr.isEmpty()) {
            sb.append("(无)\n")
        } else {
            for (c in cr) {
                sb.append('\n').append("[").append(c.id).append("] ").append(c.time)
                    .append(" 线程=").append(c.thread).append('\n')
                sb.append(c.summary).append('\n').append(c.stack).append('\n')
            }
        }
        sb.append('\n').append("== 运行日志 ==").append('\n')
        val ls = lines.toList()
        if (ls.isEmpty()) {
            sb.append("(空)\n")
        } else {
            for (l in ls) sb.append(l).append('\n')
        }
        return sb.toString()
    }

    fun clear() {
        lines.clear()
    }
}
