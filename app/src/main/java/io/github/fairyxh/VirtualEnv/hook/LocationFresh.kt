package io.github.fairyxh.VirtualEnv.hook

import android.location.Location
import android.os.Bundle
import android.os.SystemClock
import io.github.fairyxh.VirtualEnv.util.ZLog

/**
 * 虚拟位置新鲜度工具。
 *
 * 百度原生 locSDK / 系统 LocationProviderManager 会按 Location 的
 * `getTime()` / `getElapsedRealtimeNanos()` 判断 fix 是否新鲜：
 * - system_server 的 LocationRegistration 过滤：`deltaMs < minUpdateInterval` 丢弃，
 *   若注入的 Location 沿用后端保存点的时间戳（分钟级旧值），连续注入的 delta=0 → 全部被丢弃；
 * - 百度 locSDK 8b 等原生 SDK 同样会拒收旧时间戳的 fix（表现为“生效一次后原形毕露”）。
 *
 * 所有对外投递的虚拟位置必须经 [fresh] 生成新副本并刷新时间戳。
 *
 * 百度 GPS fix 链路（com.baidu.location.c.f，BaIDU LBS 9.1.6 逆向）：
 * - C0107f.onLocationChanged 中 `a==0`（GnssStatus 未上报）时会读
 *   `location.getExtras().getInt("satellites")` 作为 usedInFix 卫星数；
 *   若 fix 不带该 extras → 卫星数 0 → `f()` 中 `a > 2` 不成立 → GPS 不上报。
 *   因此虚拟 fix 必须带 `satellites` extras（与 GnssStatus usedInFix 一致）。
 */
object LocationFresh {

    /** 默认虚拟卫星：总数 24，usedInFix 12（与 GnssDataBlockHookAdapter / 自动托管一致）。 */
    private const val DEFAULT_SATELLITES = 12

    /** 复制 Location 并刷新时间戳；可同时指定 provider 名。 */
    fun fresh(location: Location, provider: String? = null): Location {
        val loc = Location(location)
        if (provider != null) {
            loc.provider = provider
        }
        loc.time = System.currentTimeMillis()
        try {
            // hidden API：Location.setElapsedRealtimeNanos(long)
            Location::class.java.getMethod(
                "setElapsedRealtimeNanos",
                Long::class.javaPrimitiveType
            ).invoke(loc, SystemClock.elapsedRealtimeNanos())
        } catch (t: Throwable) {
            // 失败静默：部分 ROM 无该方法，仍可工作
            ZLog.w(
                "Hook",
                "setElapsedRealtimeNanos unavailable",
                t
            )
        }
        // 百度 locSDK 在 GnssStatus 未上报时从 fix extras 读卫星数；
        // 缺失会令 usedInFix 判定为 0，GPS fix 被丢弃。
        try {
            val satellites = if (loc.extras?.containsKey("satellites") == true) {
                loc.extras!!.getInt("satellites")
            } else {
                DEFAULT_SATELLITES
            }
            val old = loc.extras
            val bundle = if (old != null) Bundle(old) else Bundle()
            bundle.putInt("satellites", satellites)
            loc.extras = bundle
        } catch (t: Throwable) {
            ZLog.w("Hook", "set satellites extras failed", t)
        }
        return loc
    }
}
