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
    private var appCache: io.github.fairyxh.VirtualEnv.core.EnvStateCache? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "[$TAG_SCOPE] onModuleLoaded process=${param.processName} systemServer=${param.isSystemServer}")
        if (param.isSystemServer) return

        // App 进程：安装第一层 Framework API Hook（Telephony / BLE / WiFi）。
        // 虚拟环境状态保存在 system_server Backend，此处通过 EnvStateCache 轮询获取。
        try {
            if (appCache != null) return
            val cache = io.github.fairyxh.VirtualEnv.core.EnvStateCache()
            appCache = cache
            val registrar = HookRegistrar { method, interceptor ->
                try {
                    hook(method).intercept(interceptor)
                    true
                } catch (t: Throwable) {
                    ZLog.e(TAG_SCOPE, "app hook register failed: ${method.declaringClass.name}.${method.name}", t)
                    false
                }
            }
            // com.android.phone 是基站 Binder 服务端所在进程：Hook 服务端方法，
            // 对所有 App（含第三方地图）全局阻断真实基站网络定位。
            if (param.processName == "com.android.phone") {
                PhoneInterfaceManagerHookAdapter(cache, registrar).install(ClassLoader.getSystemClassLoader())
                log(Log.INFO, TAG, "[$TAG_SCOPE] phone interface manager hooks installed for ${param.processName}")
                return
            }
            FrameworkEnvHookAdapter(cache, registrar).install(ClassLoader.getSystemClassLoader())
            log(Log.INFO, TAG, "[$TAG_SCOPE] framework env hooks installed for ${param.processName}")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "[$TAG_SCOPE] framework env hook install failed", t)
        }
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
            // WiFi 服务端 Hook：全局阻断第三方地图读取真实 WiFi 扫描/连接信息进行网络定位
            WifiServiceHookAdapter(backend, registrar).install(param.classLoader)

            log(Log.INFO, TAG, "[$TAG_SCOPE] system server hook install done")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "[$TAG_SCOPE] onSystemServerStarting failed", t)
        }
    }
}
