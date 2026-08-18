package io.github.fairyxh.VirtualEnv.app

import android.os.Process
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Root-only LMK protection for this module's own processes. */
object RootProcessProtector {
    private const val TAG = "ProcessGuard"
    private const val PACKAGE_NAME = "io.github.fairyxh.VirtualEnv"
    private const val OOM_ADJ = -1000
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ZVE-ProcessGuard").apply { isDaemon = true }
    }
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        executor.execute { protectNow() }
        executor.scheduleWithFixedDelay({ protectNow() }, 15L, 30L, TimeUnit.SECONDS)
    }

    fun protectNow(): Boolean {
        val pid = Process.myPid()
        val command = "echo $OOM_ADJ > /proc/$pid/oom_score_adj 2>/dev/null"
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val ok = process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
            ZLog.i(TAG, "oom protection pid=$pid adj=$OOM_ADJ ok=$ok${if (output.isBlank()) "" else " output=$output"}")
            ok
        } catch (t: Throwable) {
            ZLog.w(TAG, "oom protection failed pid=$pid", t)
            false
        }
    }
}