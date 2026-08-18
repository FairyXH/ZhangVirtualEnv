package io.github.fairyxh.VirtualEnv.hook

import io.github.fairyxh.VirtualEnv.core.EnvStateCache
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject

/**
 * RIL Java 层防御性 Hook（com.android.phone 进程）。
 *
 * 对应 VirtualRegion 的原生 RIL Hook（RIL_requestCellInfoList / RIL_requestCurrentSignalStrength
 * 等 libril 符号）。Java 层等价点：com.android.internal.telephony.RIL 的请求方法。
 *
 * 注意：Oplus 15 / Android 15 的 RIL 已把 AOSP 的 requestCellInfoList 改名为
 * requestCellInfoUpdate / requestCellInfoUpdateWithWorkSource（framework dex 确认），
 * 因此这里按“方法名包含 CellInfo / SignalStrength + 唯一 Message 参数”动态匹配，
 * 跨 ROM 版本更稳。
 *
 * 正常情况下 RIL 请求在 Phone.getAllCellInfo 等上层已由 Binder 服务端 Hook 覆盖；本适配器
 * 只作为 API 35/36 的防御性兜底。Android 17 的 RIL 请求会先创建带 serial 的 RILRequest，
 * 再由 RadioNetworkProxy 发往 Radio HAL，响应必须经过 RIL.processResponseDone 清理请求状态。
 * 因此 API 37+ 禁止在入口向 Message 直接投递 AsyncResult，避免遗留请求、串号和电话状态抖动，
 * 由上层 Binder 返回层继续提供可控测试数据。旧版本的注入失败仍 fail-open。
 */
class RilDefensiveHookAdapter(
    private val cache: EnvStateCache,
    private val registrar: HookRegistrar,
) {

    private val TAG_SCOPE = "Hook"

    /** 向 RIL 请求的 Message 投递虚拟结果（AsyncResult 标准模式）。 */
    private fun sendVirtualResult(msg: Any, result: Any): Boolean {
        return try {
            val message = msg as? android.os.Message ?: return false
            val asyncResultClass = Class.forName("android.os.AsyncResult")
            val forMessage = asyncResultClass.getMethod(
                "forMessage",
                android.os.Message::class.java,
                Object::class.java,
                Object::class.java
            )
            forMessage.invoke(null, message, result, null)
            message.sendToTarget()
            true
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "send virtual RIL result failed", t)
            false
        }
    }

    /**
     * 动态匹配 RIL 请求方法：方法名包含 [namePart] 且第 1 参为 Message。
     *
     * 参数数量兼容：Android 15 为 1 参（Message）；Android 16 的
     * `RIL.getCellInfoList(Message, WorkSource)` 为 2 参（telephony-common.jar JADX 确认），
     * 仅要求第 1 参为 Message，避免 Android 16 漏命中。
     *
     * @param source 虚拟数据源（cell/sim）；null 时放行
     * @param builder 由虚拟数据构造 RIL 响应对象
     */
    private fun hookByMethodName(
        clazz: Class<*>,
        namePart: String,
        source: () -> JSONObject?,
        builder: (JSONObject) -> Any?
    ): Int {
        var hooked = 0
        clazz.declaredMethods.forEach { method ->
            if (!method.name.contains(namePart)) return@forEach
            if (method.parameterCount < 1 || method.parameterTypes[0] != android.os.Message::class.java) {
                return@forEach
            }
            val ok = registrar.register(method) { chain ->
                val data = source()
                if (data != null) {
                    try {
                        val virtual = builder(data)
                        if (virtual != null) {
                            val msg = chain.getArg(0)
                            if (msg != null && sendVirtualResult(msg, virtual)) {
                                ZLog.d(TAG_SCOPE, "RIL.${method.name} -> virtual")
                                return@register null
                            }
                        }
                    } catch (t: Throwable) {
                        ZLog.w(TAG_SCOPE, "RIL.${method.name} virtual failed, fallback", t)
                    }
                }
                chain.proceed()
                null
            }
            if (ok) {
                hooked++
                ZLog.i(TAG_SCOPE, "hooked RIL.${method.name} (defensive)")
            }
        }
        return hooked
    }

    fun install(classLoader: ClassLoader): Int {
        // Android 17 Xiaomi uses the Radio HAL request/serial lifecycle described above.
        // Never short-circuit RIL entry points on API 37+ until a matching runtime-safe
        // response hook exists. Binder-layer adapters remain the supported test path.
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            ZLog.w(TAG_SCOPE, "RIL defensive hooks disabled on API ${android.os.Build.VERSION.SDK_INT}; preserve Radio HAL request lifecycle")
            return 0
        }
        val clazz = HookSupport.findClass(classLoader, "com.android.internal.telephony.RIL")
        if (clazz == null) {
            ZLog.w(TAG_SCOPE, "RIL class not found (fail-open)")
            return 0
        }
        var hooked = 0
        // API 37 static adaptation: use the target material's RIL signature.
        // Android 17 Xiaomi exposes getCellInfoList(Message, WorkSource) and
        // getSignalStrength(Message); requestCellInfoUpdate is not assumed.
        hooked += hookByMethodName(clazz, "CellInfo", { cache.currentCell() }) { data ->
            val list = VirtualCellFactory.buildCellInfoList(data, cache.locationLat(), cache.locationLon())
            if (list.isEmpty()) null else ArrayList(list)
        }
        // 信号强度：requestSignalStrength / requestCurrentSignalStrength 等
        hooked += hookByMethodName(clazz, "SignalStrength", { cache.currentSim() }) { data ->
            VirtualSignalFactory.build(data)
        }
        return hooked
    }
}
