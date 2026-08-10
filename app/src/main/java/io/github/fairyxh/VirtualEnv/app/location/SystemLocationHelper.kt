package io.github.fairyxh.VirtualEnv.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 系统定位一次性请求（获取真实位置）。
 *
 * 不直接读 getLastKnownLocation：lastKnown 可能被虚拟注入更新（VirtualFixInjector
 * 会把虚拟位置写进 provider 缓存），关闭虚拟定位后读到的仍是虚拟点。
 *
 * API 30+ 用 getCurrentLocation(provider, ...) 并行请求 network/gps；
 * 低版本用 requestSingleUpdate。任一 provider 先返回即回调，超时返回 null。
 */
object SystemLocationHelper {

    private const val TAG_SCOPE = "SysLoc"
    private const val TIMEOUT_MS = 6000L

    @SuppressLint("MissingPermission")
    fun requestOnce(
        context: Context,
        timeoutMs: Long = TIMEOUT_MS,
        onResult: (Location?) -> Unit
    ) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = mutableListOf<String>()
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            providers.add(LocationManager.NETWORK_PROVIDER)
        }
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            providers.add(LocationManager.GPS_PROVIDER)
        }
        if (providers.isEmpty()) {
            onResult(null)
            return
        }
        val handler = Handler(Looper.getMainLooper())
        val done = AtomicBoolean(false)
        var listener: LocationListener? = null

        fun finish(loc: Location?) {
            if (!done.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)
            try {
                listener?.let { lm.removeUpdates(it) }
            } catch (_: Throwable) {
            }
            onResult(loc)
        }

        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                ZLog.i(TAG_SCOPE, "location -> ${location.latitude},${location.longitude}")
                finish(location)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        handler.postDelayed({ finish(null) }, timeoutMs)
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                providers.forEach { provider ->
                    try {
                        lm.getCurrentLocation(
                            provider,
                            null,
                            context.mainExecutor,
                            java.util.function.Consumer<Location> { loc ->
                                if (loc != null) {
                                    ZLog.i(TAG_SCOPE, "provider=$provider -> ${loc.latitude},${loc.longitude}")
                                    finish(loc)
                                }
                            }
                        )
                    } catch (t: Throwable) {
                        ZLog.w(TAG_SCOPE, "getCurrentLocation($provider) failed", t)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper())
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "request once failed", t)
            finish(null)
        }
    }
}
