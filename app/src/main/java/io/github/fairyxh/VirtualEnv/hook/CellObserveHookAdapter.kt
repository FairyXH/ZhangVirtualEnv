package io.github.fairyxh.VirtualEnv.hook

import io.github.fairyxh.VirtualEnv.core.HookObserver
import io.github.fairyxh.VirtualEnv.util.ZLog

/**
 * 基站 Hook 层真实数据观测 Adapter（system_server 进程）。
 *
 * com.android.server.TelephonyRegistry（services.jar JADX 确认，非
 * com.android.server.telephony 包）是 phone 进程向 system_server 推送真实小区
 * 信息的 Binder 服务端；notifyCellInfoForSubscriber(int, List<CellInfo>) 每来一批
 * 真实小区都会经过这里，观测开启时把摘要写入 HookObserver。
 *
 * 采集期间即使没有小区推送，Backend.hookObserveSnapshotJson 还会在挂起状态下
 * 直连 phone Binder 实时拉取真实基站兜底，本 Hook 提供被动证据。
 */
class CellObserveHookAdapter(
    private val registrar: HookRegistrar,
) {

    companion object {
        private const val TAG_SCOPE = "Hook"
        private const val CLASS_NAME = "com.android.server.TelephonyRegistry"
    }

    fun install(classLoader: ClassLoader): Int {
        val clazz = HookSupport.findClass(classLoader, CLASS_NAME) ?: return 0
        val method = HookSupport.findMethods(clazz, "notifyCellInfoForSubscriber")
            .firstOrNull { it.parameterCount == 2 && it.parameterTypes[1] == List::class.java }
        if (method == null) {
            ZLog.w(TAG_SCOPE, "TelephonyRegistry.notifyCellInfoForSubscriber(2) not found")
            return 0
        }
        val ok = registrar.register(method) { chain ->
            // 先取真实小区参数（推送前，未虚拟化），再走原逻辑
            HookObserver.recordCellList(chain.getArg(1))
            chain.proceed()
            null
        }
        if (ok) {
            ZLog.i(TAG_SCOPE, "hooked $CLASS_NAME.notifyCellInfoForSubscriber (observe)")
            return 1
        }
        return 0
    }
}
