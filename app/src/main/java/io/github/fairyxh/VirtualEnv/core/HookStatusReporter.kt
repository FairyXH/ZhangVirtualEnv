package io.github.fairyxh.VirtualEnv.core

import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject

/**
 * 将本进程 Hook 状态快照上报给 system_server Backend（跨进程聚合）。
 *
 * com.android.phone / com.android.bluetooth / 模块自身 / 检测器等作用域进程各自持有
 * [HookStatusRegistry]，通过 raw TCP POST /api/hook/status 把快照推给 system_server
 * 汇总；使用与 EnvStateCache 相同的 raw socket 方式，绕开应用层网络安全策略。
 */
object HookStatusReporter {

    private const val TAG_SCOPE = "HookStatus"
    private const val HOST = "127.0.0.1"
    private const val PORT = 18790
    private const val TIMEOUT_MS = 1500

    /** 上报一次；返回是否成功。 */
    fun reportOnce(token: String, process: String): Boolean {
        val status = HookStatusRegistry.snapshot()
        if (!process.isBlank()) status.put("process", process)
        return try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(HOST, PORT), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            val body = JSONObject().apply {
                put("process", process)
                put("status", status)
            }.toString()
            val request = "POST /api/hook/status HTTP/1.1\r\n" +
                "Host: $HOST\r\n" +
                "X-ZVE-Token: $token\r\n" +
                "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
                "Connection: close\r\n\r\n" + body
            socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            socket.close()
            response.contains("200")
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "report hook status failed: ${t.message}")
            false
        }
    }

    /** 带重试上报（后台线程调用，最多 [maxAttempts] 次，间隔 [delayMs]）。 */
    fun reportWithRetry(token: String, process: String, maxAttempts: Int = 3, delayMs: Long = 2000L) {
        Thread {
            for (i in 1..maxAttempts) {
                if (reportOnce(token, process)) {
                    ZLog.i(TAG_SCOPE, "hook status reported process=$process")
                    return@Thread
                }
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
            ZLog.w(TAG_SCOPE, "hook status report failed after $maxAttempts attempts process=$process")
        }.apply { name = "ZVE-HookStatusReport"; isDaemon = true }.start()
    }
}
