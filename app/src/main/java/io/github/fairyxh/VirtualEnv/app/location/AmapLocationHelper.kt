package io.github.fairyxh.VirtualEnv.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import io.github.fairyxh.VirtualEnv.app.AmapPrivacyManager
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 高德 SDK 一次性定位（普通 App 视角）。
 *
 * 控制端自身位于 LSPosed 作用域内，直接读系统 LocationManager 属于测试适配层
 * 数据链路；高德定位 SDK 走独立网络定位（WiFi/基站采集 + 高德服务端换算）与
 * 系统 GPS 数据源，与普通第三方 App 完全一致，作为控制端“当前位置”的独立数据源。
 *
 * 调用前需已同意高德隐私政策（[AmapPrivacyManager.applyPrivacyIfAgreed] 会同时
 * 调用地图与定位两套 SDK 的隐私接口）。
 */
object AmapLocationHelper {

    private const val TAG_SCOPE = "AmapLoc"
    private const val PREFS = "amap_config"
    private const val KEY_AMAP_KEY = "amap_key"
    private const val TIMEOUT_MS = 12_000L

    /**
     * 发起一次定位，[onResult] 在主线程回调：
     * - 成功返回 [AMapLocation]（errorCode == 0，坐标为 GCJ-02）
     * - 失败 / 超时返回 null，调用方自行走系统定位 / 最近已知位置兜底
     */
    @SuppressLint("MissingPermission")
    fun locateOnce(
        context: Context,
        timeoutMs: Long = TIMEOUT_MS,
        onResult: (AMapLocation?) -> Unit
    ) {
        val main = Handler(Looper.getMainLooper())
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { locateOnceInternal(context, timeoutMs, onResult) }
            return
        }
        locateOnceInternal(context, timeoutMs, onResult)
    }

    private fun locateOnceInternal(
        context: Context,
        timeoutMs: Long,
        onResult: (AMapLocation?) -> Unit
    ) {
        if (!AmapPrivacyManager.isAgreed(context)) {
            ZLog.w(TAG_SCOPE, "privacy not agreed, skip")
            onResult(null)
            return
        }

        val main = Handler(Looper.getMainLooper())
        val done = AtomicBoolean(false)

        fun finish(loc: AMapLocation?) {
            if (!done.compareAndSet(false, true)) return
            main.removeCallbacksAndMessages(null)
            onResult(loc)
        }

        val client = AMapLocationClient(context)
        val timer = Runnable {
            if (done.compareAndSet(false, true)) {
                ZLog.w(TAG_SCOPE, "amap locate timeout")
                try {
                    client.stopLocation()
                    client.onDestroy()
                } catch (_: Throwable) {
                }
                onResult(null)
            }
        }

        try {
            // 与地图共用 Key；未配置 Key 时 SDK 会回调错误码 7（KEY 错误）
            val key = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_AMAP_KEY, "").orEmpty()
            if (key.isNotEmpty()) {
                AMapLocationClient.setApiKey(key)
            }

            val option = AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                isOnceLocation = true
                isOnceLocationLatest = true
                isNeedAddress = false
                isLocationCacheEnable = false
                httpTimeOut = 8_000
                // 测试框架：允许系统注入的测试定位进入 SDK 链路，等价于普通 App
                // 打开“允许模拟位置”，避免 SDK 默认拒绝测试数据源
                isMockEnable = true
            }
            client.setLocationOption(option)
            client.setLocationListener(object : AMapLocationListener {
                override fun onLocationChanged(location: AMapLocation?) {
                    if (location != null && location.errorCode == 0) {
                        ZLog.i(
                            TAG_SCOPE,
                            "ok ${location.latitude},${location.longitude} type=${location.locationType}"
                        )
                        main.post { finish(location) }
                    } else {
                        val code = location?.errorCode ?: -1
                        ZLog.w(TAG_SCOPE, "error code=$code ${location?.errorInfo ?: "null"}")
                        main.post { finish(null) }
                    }
                }
            })
            main.postDelayed(timer, timeoutMs)
            client.startLocation()
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "amap locate start failed", t)
            main.removeCallbacks(timer)
            try {
                client.stopLocation()
                client.onDestroy()
            } catch (_: Throwable) {
            }
            onResult(null)
        }
    }
}
