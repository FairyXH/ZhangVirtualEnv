package io.github.fairyxh.VirtualEnv.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 控制端运行日志环形存储。
 *
 * - 线程安全：任意线程可写（main / ApiClient 协程 / Hook 回调）。
 * - 内存保留最近 [MAX_LINES] 条，避免无界增长。
 * - 同时维护崩溃记录列表（含崩溃时间 / 线程 / 异常摘要）。
 * - 设置页实时展示；导出时合并为纯文本。
 */
object LogStore {

    const val MAX_LINES = 5000
    const val MAX_CRASHES = 50

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

    /** 导出为完整文本。 */
    fun exportText(): String = snapshotText(includeHeader = true)

    /** 返回完整日志文本，供压缩包切片。 */
    fun snapshotText(includeHeader: Boolean = true): String {
        val sb = StringBuilder()
        if (includeHeader) {
            sb.append("ZhangVirtualEnv 运行日志\n")
            sb.append("导出时间: ").append(dateFormat.format(Date())).append('\n')
        }
        sb.append("== 崩溃记录 ==\n")
        val cr = crashes.toList()
        if (cr.isEmpty()) sb.append("(无)\n") else cr.forEach { c ->
            sb.append('\n').append("[").append(c.id).append("] ").append(c.time)
                .append(" 线程=").append(c.thread).append('\n')
            sb.append(c.summary).append('\n').append(c.stack).append('\n')
        }
        sb.append("\n== 运行日志 ==\n")
        val ls = lines.toList()
        if (ls.isEmpty()) sb.append("(空)\n") else ls.forEach { sb.append(it).append('\n') }
        return sb.toString()
    }

    /**
     * 将日志切成固定行数的片段，避免单文件过大，也避免导出时丢掉旧日志。
     */
    fun snapshotSlices(linesPerSlice: Int = 500): List<String> {
        val all = lines.toList()
        if (all.isEmpty()) return listOf("(空)\n")
        return all.chunked(linesPerSlice).mapIndexed { index, chunk ->
            buildString {
                append("ZhangVirtualEnv runtime log slice ").append(index + 1).append('\n')
                append("lines=").append(chunk.size).append("\n\n")
                chunk.forEach { append(it).append('\n') }
            }
        }
    }

    /**
     * 导出完整诊断包：运行日志切片、崩溃栈、内存统计、当前进程信息和 logcat 快照。
     * logcat 采集失败不会阻断其余文件导出。
     */
    fun exportDiagnosticZip(
        output: File,
        metadata: Map<String, String> = emptyMap(),
        extraFiles: List<Pair<String, File>> = emptyList(),
        extraTexts: Map<String, String> = emptyMap(),
        logcatLines: Int = 20000,
        linesPerSlice: Int = 500,
    ): File {
        output.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            val meta = buildString {
                append("ZhangVirtualEnv diagnostic bundle\n")
                append("created=").append(dateFormat.format(Date())).append('\n')
                metadata.forEach { (key, value) -> append(key).append('=').append(value).append('\n') }
                append("memory_log_lines=").append(lines.size).append('\n')
                append("crash_records=").append(crashes.size).append('\n')
            }
            putText(zip, "00_manifest.txt", meta)
            putText(zip, "01_runtime_summary.txt", snapshotText())
            snapshotSlices(linesPerSlice).forEachIndexed { index, slice ->
                putText(zip, "logs/runtime_%03d.txt".format(Locale.US, index + 1), slice)
            }
            val crashesText = crashes.joinToString("\n\n") { c ->
                "[${c.id}] ${c.time} thread=${c.thread}\n${c.summary}\n${c.stack}"
            }.ifEmpty { "(none)\n" }
            putText(zip, "logs/crashes.txt", crashesText)
            putText(zip, "logs/logcat_capture.txt", captureLogcat(logcatLines))
            val runtime = Runtime.getRuntime()
            putText(zip, "00_memory.txt", "max=${runtime.maxMemory()}\ntotal=${runtime.totalMemory()}\nfree=${runtime.freeMemory()}\n")
            extraTexts.forEach { (entryName, text) -> putText(zip, entryName, text) }
            extraFiles.forEach { (entryName, file) -> if (file.isFile) putFile(zip, entryName, file) }
        }
        return output
    }

    private fun captureLogcat(lines: Int): String {
        return try {
            val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", lines.toString())
                .redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            "logcat capture failed: ${t.javaClass.name}: ${t.message}\n"
        }
    }

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }

    private fun putFile(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    fun clear() {
        lines.clear()
        crashes.clear()
    }
}
