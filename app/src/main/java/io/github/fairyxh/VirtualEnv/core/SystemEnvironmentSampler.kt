package io.github.fairyxh.VirtualEnv.core

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** system_server 侧主动采样器，补足 Hook 回调尚未到达时的录像数据。 */
@SuppressLint("MissingPermission", "NewApi", "HardwareIds")
class SystemEnvironmentSampler(private val context: Context) {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ZVE-SystemSampler").apply { isDaemon = true }
    }
    private var future: ScheduledFuture<*>? = null

    fun start(intervalMs: Long) {
        stop()
        val interval = intervalMs.coerceIn(100L, 300_000L)
        val task = Runnable {
            try {
                sample()
            } catch (t: Throwable) {
                ZLog.w("Collect", "system sampler failed", t)
            }
        }
        task.run()
        future = executor.scheduleWithFixedDelay(task, interval, interval, TimeUnit.MILLISECONDS)
        ZLog.i("Collect", "system sampler started intervalMs=$interval")
    }

    fun stop() {
        future?.cancel(false)
        future = null
    }

    private fun sample() {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val providers = runCatching { lm?.getProviders(false).orEmpty() }.getOrDefault(emptyList())
        providers.asSequence()
            .mapNotNull { runCatching { lm?.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let(HookObserver::recordLocation)

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        runCatching { tm?.allCellInfo }.getOrNull()?.let(HookObserver::recordCell)

        val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        runCatching { wm?.scanResults }.getOrNull()?.let(HookObserver::recordWifi)
    }
}