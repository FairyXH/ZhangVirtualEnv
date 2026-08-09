package io.github.fairyxh.VirtualEnv.hook

import android.util.Log
import io.github.fairyxh.VirtualEnv.core.Backend
import io.github.fairyxh.VirtualEnv.util.ZLog
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.io.File

/**
 * LSPosed API 101 模块入口。
 *
 * 只负责：
 * 1. system_server 启动时初始化 Backend Core
 * 2. 启动 ApiServer（供 App 控制端 HTTP 调用）
 * 3. 加载 Profile 并安装 Hook Adapter
 *
 * 不在入口内编写任何业务逻辑；不保存业务状态。
 */
class VirtualEnvEntry : XposedModule() {

    companion object {
        private const val TAG = "ZVirtualEnv"
        private const val TAG_SCOPE = "Entry"
    }

    private var backend: Backend? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "[$TAG_SCOPE] onModuleLoaded process=${param.processName} systemServer=${param.isSystemServer}")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log(Log.INFO, TAG, "[$TAG_SCOPE] onSystemServerStarting")
        try {
            if (backend != null) return

            // Backend 数据目录：system_server 可写目录 /data/system
            val backend = Backend.initialize(File("/data/system"))
            this.backend = backend

            // 加载 Profile（从模块 APK assets/profiles 读取）
            backend.profileManager.load(moduleApplicationInfo.sourceDir)

            // 启动 HTTP API 服务（App 控制端访问入口）
            backend.startApiServer()

            // 安装 Hook Adapter
            val registrar = HookRegistrar { method, interceptor ->
                try {
                    hook(method).intercept(interceptor)
                    true
                } catch (t: Throwable) {
                    ZLog.e(TAG_SCOPE, "hook register failed: ${method.declaringClass.name}.${method.name}", t)
                    false
                }
            }
            LocationHookAdapter(backend, registrar).install(param.classLoader)

            log(Log.INFO, TAG, "[$TAG_SCOPE] system server hook install done")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "[$TAG_SCOPE] onSystemServerStarting failed", t)
        }
    }
}
