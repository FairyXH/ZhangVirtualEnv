package io.github.fairyxh.VirtualEnv.hook

import android.util.Log
import io.github.fairyxh.VirtualEnv.core.Backend
import io.github.fairyxh.VirtualEnv.core.HookStatusRegistry
import io.github.fairyxh.VirtualEnv.core.HookStatusReporter
import io.github.fairyxh.VirtualEnv.util.ZLog
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LSPosed API 101 模块入口。
 *
 * 只负责：
 * 1. system_server 启动时初始化 Backend Core
 * 2. 启动 ApiServer（供 App 控制端 HTTP 调用）
 * 3. 加载 Profile 并安装 Hook Adapter
 *
 * 重要：`ModuleLoadedParam` 不提供宿主 classLoader。system 进程（com.android.phone /
 * com.android.bluetooth）的 Hook 目标类位于各自 APK，必须用 `onPackageReady` 的
 * `PackageReadyParam.getClassLoader()` 安装；`ClassLoader.getSystemClassLoader()`
 * 只能解析 boot classpath 的 framework 类，会导致这些 Hook 静默失败。
 * 进程识别以 `onModuleLoaded` 的 processName 为准（onPackageReady 无 processName）。
 */
class VirtualEnvEntry : XposedModule() {

    companion object {
        private const val TAG = "ZVirtualEnv"
        private const val TAG_SCOPE = "Entry"
    }

    private var backend: Backend? = null
    private var appCache: io.github.fairyxh.VirtualEnv.core.EnvStateCache? = null

    @Volatile
    private var processName: String = ""

    private val appHooksInstalled = AtomicBoolean(false)
    private val phoneHooksInstalled = AtomicBoolean(false)
    private val bleHooksInstalled = AtomicBoolean(false)

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "[$TAG_SCOPE] onModuleLoaded process=${param.processName} systemServer=${param.isSystemServer}")
        if (param.isSystemServer) return

        processName = param.processName
        HookStatusRegistry.setProcess(param.processName)
        // App 进程：初始化 EnvStateCache 与注册器。
        // 具体 Hook 安装延迟到 onPackageReady（此时才有宿主 classLoader）。
        try {
            if (appCache != null) return
            val apiToken = io.github.fairyxh.VirtualEnv.util.ApiToken.readFromApk(moduleApplicationInfo.sourceDir)
            appCache = io.github.fairyxh.VirtualEnv.core.EnvStateCache(apiToken)
            // 传感器多级后端：App 进程初始化兼容模式（legacy Hook 包装）
            try {
                io.github.fairyxh.VirtualEnv.core.sensor.SensorBackendManager.initAppProcess(appCache!!)
                io.github.fairyxh.VirtualEnv.core.sensor.SensorBackendManager.start()
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "[$TAG_SCOPE] app sensor backend init failed", t)
            }
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "[$TAG_SCOPE] onModuleLoaded cache init failed", t)
        }
    }

    /**
     * 包加载完成（宿主 classLoader 就绪）：用真实宿主 classLoader 安装 Hook。
     *
     * 对 com.android.phone / com.android.bluetooth 等系统进程，Hook 目标类位于宿主 APK，
     * 必须用 `param.getClassLoader()`；framework 层 Hook 也统一用宿主 classLoader
     * （boot classpath 委托一致，framework 类同样可解析）。
     */
    override fun onPackageReady(param: PackageReadyParam) {
        try {
            if (backend != null) return // system_server 不在此回调
            val cache = appCache ?: return
            val hostClassLoader = try {
                param.getClassLoader()
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "host classLoader unavailable, fallback system", t)
                ClassLoader.getSystemClassLoader()
            }
            val registrar = HookRegistrar { executable, interceptor ->
                val ok = try {
                    hook(executable).intercept(interceptor)
                    true
                } catch (t: Throwable) {
                    ZLog.e(TAG_SCOPE, "app hook register failed: ${executable.declaringClass.name}.${executable.name}", t)
                    false
                }
                HookStatusRegistry.record(HookStatusRegistry.hookKey(executable), ok)
                ok
            }
            val pkg = try { param.packageName } catch (t: Throwable) { "" }

            // com.android.phone：基站 Binder 服务端（对任意 App 全局阻断真实基站网络定位）
            if (processName == "com.android.phone" && phoneHooksInstalled.compareAndSet(false, true)) {
                val hooked = PhoneInterfaceManagerHookAdapter(cache, registrar).install(hostClassLoader)
                log(Log.INFO, TAG, "[$TAG_SCOPE] phone interface manager hooks installed pkg=$pkg hooked=$hooked loader=${hostClassLoader}")
                // SIM 卡身份 / 信号全局虚拟化（Binder 服务端，对任意 App 生效）
                val simCfg = readSimProfileConfig(hostClassLoader)
                val simHooked = SimTelephonyHookAdapter(
                    cache,
                    registrar,
                    simCfg.first,
                    simCfg.second,
                    simCfg.third
                ).install(hostClassLoader)
                log(Log.INFO, TAG, "[$TAG_SCOPE] sim telephony hooks installed pkg=$pkg hooked=$simHooked loader=${hostClassLoader}")
                // Oplus 15：getSimOperatorName/getSimCountryIso/getSimOperator/getNetworkOperator* 直接读系统属性，
                // 必须拦截 TelephonyProperties setter 才能全局虚拟化（属性进程级全局，不 Hook 第三方 App）
                val simPropHooked = SimSystemPropertyHookAdapter(cache, registrar).install(hostClassLoader)
                log(Log.INFO, TAG, "[$TAG_SCOPE] sim system-property hooks installed pkg=$pkg hooked=$simPropHooked loader=${hostClassLoader}")
                // RIL Java 层防御性 Hook：直达 RIL 的路径直接注入虚拟基站/信号（fail-open）
                val rilHooked = RilDefensiveHookAdapter(cache, registrar).install(hostClassLoader)
                log(Log.INFO, TAG, "[$TAG_SCOPE] ril defensive hooks installed pkg=$pkg hooked=$rilHooked loader=${hostClassLoader}")
            }
            // com.android.bluetooth：BLE 扫描 Binder 服务端（全局 BLE 虚拟化）
            if (processName == "com.android.bluetooth" && bleHooksInstalled.compareAndSet(false, true)) {
                val logSink: (Int, String, String) -> Unit = { level, tag, msg ->
                    log(level, tag, msg)
                }
                val hooked = BleStackHookAdapter(cache, registrar, logSink).install(hostClassLoader)
                log(Log.INFO, TAG, "[$TAG_SCOPE] ble stack hooks installed pkg=$pkg hooked=$hooked sdk=${android.os.Build.VERSION.SDK_INT} loader=${hostClassLoader}")
            }
            // 通用 framework 层 Hook（Telephony/WiFi/BLE 框架 API + 传感器注入）
            if (appHooksInstalled.compareAndSet(false, true)) {
                FrameworkEnvHookAdapter(cache, registrar).install(hostClassLoader)
                log(Log.INFO, TAG, "[$TAG_SCOPE] framework env hooks installed for pkg=$pkg")
            }
            // 上报本进程 Hook 状态（system_server 汇总后供设置页展示/导出报告）
            HookStatusReporter.reportWithRetry(
                io.github.fairyxh.VirtualEnv.util.ApiToken.readFromApk(moduleApplicationInfo.sourceDir),
                processName
            )
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "[$TAG_SCOPE] onPackageReady hook install failed", t)
        }
    }

    /** 从模块 APK assets 读取 sim profile 配置（phone 进程无 Backend，直接读文件）。 */
    private fun readSimProfileConfig(hostClassLoader: ClassLoader): Triple<List<String>, List<String>, List<String>> {
        val phoneInterface = mutableListOf<String>()
        val phoneSubInfo = mutableListOf<String>()
        val phoneObj = mutableListOf<String>()
        try {
            // 按 SDK 精确选择 sim profile：Android 16 使用 android16.json（类/方法候选差异见 docs/reverse/android16-adapt）。
            // 精确匹配（==36/==35/==34），API 37+ 未适配时回退 default.json，绝不误用 android16（与 ProfileManager 一致）。
            val sdk = android.os.Build.VERSION.SDK_INT
            val profileName = when (sdk) {
                36 -> "android16.json"
                35 -> "android15.json"
                34 -> "android14.json"
                else -> "default.json"
            }
            val assets = hostClassLoader.getResourceAsStream("assets/profiles/$profileName")
                ?: hostClassLoader.getResourceAsStream("assets/profiles/default.json")
            if (assets != null) {
                val text = assets.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = org.json.JSONObject(text)
                val sim = json.optJSONObject("hooks")?.optJSONObject("sim")
                if (sim != null) {
                    fun parseArr(name: String): List<String> {
                        val arr = sim.optJSONArray(name) ?: return emptyList()
                        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                    }
                    phoneInterface.addAll(parseArr("phoneInterfaceClasses"))
                    phoneSubInfo.addAll(parseArr("phoneSubInfoClasses"))
                    phoneObj.addAll(parseArr("phoneClasses"))
                }
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "read sim profile config failed, fallback defaults", t)
        }
        return Triple(phoneInterface, phoneSubInfo, phoneObj)
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log(Log.INFO, TAG, "[$TAG_SCOPE] onSystemServerStarting")
        try {
            if (backend != null) return
            HookStatusRegistry.setProcess("system")

            // Backend 数据目录：system_server 可写目录 /data/system
            val backend = Backend.initialize(File("/data/system"))
            this.backend = backend
            backend.setModuleApkPath(moduleApplicationInfo.sourceDir)
            // BuildConfig 可能未生成（AGP 9 默认关闭），用反射读取模块版本
            backend.setModuleVersion(
                runCatching {
                    Class.forName("io.github.fairyxh.VirtualEnv.BuildConfig")
                        .getField("VERSION_NAME").get(null) as? String
                }.getOrDefault("") ?: ""
            )

            // 加载 Profile（从模块 APK assets/profiles 读取）
            backend.profileManager.load(moduleApplicationInfo.sourceDir)

            // 启动 HTTP API 服务（App 控制端访问入口）；携带访问令牌，未授权请求拒绝
            val apiToken = io.github.fairyxh.VirtualEnv.util.ApiToken.readFromApk(moduleApplicationInfo.sourceDir)
            backend.startApiServer(token = apiToken)
            if (apiToken.isBlank()) {
                log(Log.WARN, TAG, "[$TAG_SCOPE] api token missing/blank, ApiServer will reject all requests")
            }

            // 传感器多级后端：system_server 侧初始化全局模式（SensorService Data Injection）
            try {
                io.github.fairyxh.VirtualEnv.core.sensor.SensorBackendManager.initSystemServer {
                    io.github.fairyxh.VirtualEnv.core.sensor.VirtualSensorConfig
                        .fromStatus(backend.sensorEngine.statusJson())
                }
                io.github.fairyxh.VirtualEnv.core.sensor.SensorBackendManager.start()
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "[$TAG_SCOPE] sensor backend init failed", t)
            }

            // 安装 Hook Adapter
            val registrar = HookRegistrar { executable, interceptor ->
                val ok = try {
                    hook(executable).intercept(interceptor)
                    true
                } catch (t: Throwable) {
                    ZLog.e(TAG_SCOPE, "hook register failed: ${executable.declaringClass.name}.${executable.name}", t)
                    false
                }
                HookStatusRegistry.record(HookStatusRegistry.hookKey(executable), ok)
                ok
            }
            LocationHookAdapter(backend, registrar).install(param.classLoader)
            // WiFi 服务端 Hook：全局阻断第三方地图读取真实 WiFi 扫描/连接信息进行网络定位
            WifiServiceHookAdapter(backend, registrar).install(param.classLoader)
            // 蓝牙适配器身份虚拟化：BluetoothAdapter.getAddress/getName/getState/isEnabled（全局）
            val btHooked = BluetoothIdentityHookAdapter(backend, registrar).install(param.classLoader)
            log(Log.INFO, TAG, "[$TAG_SCOPE] bluetooth identity hooks installed hooked=$btHooked")
            // GNSS 原始数据流阻断：NMEA/导航消息/原始测量（百度 SDK 拉回真实位置根因）
            val gnssBlocked = GnssDataBlockHookAdapter(backend, registrar).install(param.classLoader)
            log(Log.INFO, TAG, "[$TAG_SCOPE] gnss data block hooks installed hooked=$gnssBlocked")
            // ColorOS 服务启动限制绕过（百度定位服务端进程 MapCoreService 概率性被拦）
            OplusServiceStartBypass(registrar).install(param.classLoader)
            // 虚拟 fix 主动注入：百度/微信 gps 无 fix 时主动上报，GMS fused passive 缓存刷新
            VirtualFixInjector(backend, registrar).install(param.classLoader)
            // SubscriptionInfo 全局虚拟化（system_server 的 ISub.Stub 返回点，对任意 App 生效）
            val simCfg = backend.profileManager.envHookConfig("sim")
            val subscriptionClasses = simCfg.optJSONArray("subscriptionClasses")
                ?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() } }
                ?: emptyList()
            val simSubHooked = SimSubscriptionHookAdapter(
                { backend.simEngine.currentData() },
                registrar,
                subscriptionClasses
            ).install(param.classLoader)
            log(Log.INFO, TAG, "[$TAG_SCOPE] sim subscription hooks installed hooked=$simSubHooked")
            // 基站 Hook 层真实数据观测（TelephonyRegistry 推送 + 挂起时 Binder 拉取）
            val cellObserveHooked = CellObserveHookAdapter(registrar).install(param.classLoader)
            log(Log.INFO, TAG, "[$TAG_SCOPE] cell observe hooks installed hooked=$cellObserveHooked")

            log(Log.INFO, TAG, "[$TAG_SCOPE] system server hook install done")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "[$TAG_SCOPE] onSystemServerStarting failed", t)
        }
    }
}
