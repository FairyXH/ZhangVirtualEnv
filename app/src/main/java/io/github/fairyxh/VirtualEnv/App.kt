package io.github.fairyxh.VirtualEnv

import android.app.Application
import io.github.fairyxh.VirtualEnv.util.CrashCatcher
import io.github.fairyxh.VirtualEnv.util.ZLog

/**
 * 控制端 Application。
 *
 * 控制端是纯 UI 层：不持有业务状态，只负责调用 Backend API。
 * 与 Hook/Backend 的通信统一走 ApiClient（HTTP 127.0.0.1）。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 全局异常捕获需最早注册（任何页面 / 线程崩溃都能弹窗 + 落盘）。
        CrashCatcher.install(this)
        io.github.fairyxh.VirtualEnv.app.ApiClient.initTokenFromAssets(this)
        ZLog.i("App", "ZhangVirtualEnvironment control app started")
    }
}
