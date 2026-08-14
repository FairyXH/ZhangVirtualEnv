package io.github.fairyxh.VirtualEnv.hook

import io.github.fairyxh.VirtualEnv.core.EnvStateCache
import io.github.fairyxh.VirtualEnv.util.ZLog

/**
 * RIL Java 层防御性 Hook（com.android.phone 进程）。
 *
 * 对应 VirtualRegion 的原生 RIL Hook（RIL_requestCellInfoList / RIL_requestCurrentSignalStrength
 * 等 libril 符号）。Java 层等价点：com.android.internal.telephony.RIL 的请求方法。
 *
 * 正常情况下 RIL 请求在 Phone.getAllCellInfo 等上层已由 Binder 服务端 Hook 覆盖；本适配器
 * 作为**防御性兜底**：当基站引擎启用时，若仍有代码路径直达 RIL.requestCellInfoList /
 * requestSignalStrength，直接向调用方 Message 注入虚拟结果并阻断真实 RIL 请求。
 * 未启用/总开关关闭/采集暂停（cache 返回 null）时完全放行；注入失败 fail-open。
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

    private fun hookRequestCellInfoList(clazz: Class<*>): Int {
        val method = HookSupport.findMethods(clazz, "requestCellInfoList")
            .firstOrNull { it.parameterCount == 1 } ?: return 0
        val ok = registrar.register(method) { chain ->
            val cellData = cache.currentCell()
            if (cellData != null) {
                try {
                    val list = VirtualCellFactory.buildCellInfoList(cellData)
                    if (list.isNotEmpty()) {
                        val msg = chain.getArg(0)
                        if (msg != null && sendVirtualResult(msg, ArrayList(list))) {
                            ZLog.d(TAG_SCOPE, "RIL.requestCellInfoList -> virtual ${list.size} cells")
                            return@register null
                        }
                    }
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "RIL.requestCellInfoList virtual failed, fallback", t)
                }
            }
            chain.proceed()
            null
        }
        if (ok) {
            ZLog.i(TAG_SCOPE, "hooked RIL.requestCellInfoList (defensive)")
            return 1
        }
        return 0
    }

    private fun hookRequestSignalStrength(clazz: Class<*>): Int {
        val method = HookSupport.findMethods(clazz, "requestSignalStrength")
            .firstOrNull { it.parameterCount == 1 } ?: return 0
        val ok = registrar.register(method) { chain ->
            val sim = cache.currentSim()
            if (sim != null) {
                try {
                    val signal = VirtualSignalFactory.build(sim)
                    if (signal != null) {
                        val msg = chain.getArg(0)
                        if (msg != null && sendVirtualResult(msg, signal)) {
                            ZLog.d(TAG_SCOPE, "RIL.requestSignalStrength -> virtual")
                            return@register null
                        }
                    }
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "RIL.requestSignalStrength virtual failed, fallback", t)
                }
            }
            chain.proceed()
            null
        }
        if (ok) {
            ZLog.i(TAG_SCOPE, "hooked RIL.requestSignalStrength (defensive)")
            return 1
        }
        return 0
    }

    fun install(classLoader: ClassLoader): Int {
        val clazz = HookSupport.findClass(classLoader, "com.android.internal.telephony.RIL")
        if (clazz == null) {
            ZLog.w(TAG_SCOPE, "RIL class not found (fail-open)")
            return 0
        }
        return hookRequestCellInfoList(clazz) + hookRequestSignalStrength(clazz)
    }
}
