package io.github.fairyxh.VirtualEnv.hook

import android.location.Location
import android.os.SystemClock

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
 */
object LocationFresh {

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
            io.github.fairyxh.VirtualEnv.util.ZLog.w(
                "Hook",
                "setElapsedRealtimeNanos unavailable",
                t
            )
        }
        return loc
    }
}
