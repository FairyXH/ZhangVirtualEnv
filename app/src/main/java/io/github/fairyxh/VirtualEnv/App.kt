package io.github.fairyxh.VirtualEnv

import android.app.Application
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
        ZLog.i("App", "ZhangVirtualEnvironment control app started")
    }
}
