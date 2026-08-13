package io.github.fairyxh.VirtualEnv.hook

import android.location.Location
import io.github.fairyxh.VirtualEnv.util.ZLog
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 百度定位 SDK 调试观测 Hook（适配辅助，仅供真机调试）。
 *
 * 用法：在 LSPosed 中手动勾选需要观测的 App（如百度地图 / 志愿汇），
 * 不要加入模块 scope.list。本类不写死任何包名、不提供推荐作用域；
 * 只要宿主进程存在 BaiduLBS 类即安装观测点（scope 勾选了谁就对谁生效）。
 *
 * 观测点（全部 fail-open，只打日志不改业务逻辑）：
 * - LocationClientOption 构造与关键 setter：确认 SDK 配置（enableSimulateGps / lpcs 相关）
 * - LocationClient.start / stop：确认服务绑定与 option 全量
 * - com.baidu.location.c.f：b()（服务初始化）、c()（start gps）、
 *   f(Location)（fix 处理）、e(Location)（is_mock 判定）、n()（投递）
 * - com.baidu.location.b.b$a.a(BDLocation, int)：投递给 App 的 BDLocation
 * - BDLocation 关键 getter：locType / mockGpsStrategy / mockGpsProbability
 * - com.baidu.location.e.j 静态配置字段（m / aL / aC / aB）
 */
class BaiduLocationDebugHook(
    private val registrar: HookRegistrar,
) {

    companion object {
        private const val TAG_SCOPE = "BaiduDebug"
        private const val CLIENT = "com.baidu.location.LocationClient"
        private const val OPTION = "com.baidu.location.LocationClientOption"
        private const val GPS_COLLECTOR = "com.baidu.location.c.f"
        private const val DELIVERY = "com.baidu.location.b.b\$a"
        private const val CONFIG = "com.baidu.location.e.j"
    }

    /** 已安装标记（延迟重试成功后置位，避免重复安装）。 */
    private val installed = AtomicBoolean(false)

    /** 延迟重试线程（加固壳解压完成后真实 dex 才可解析）。 */
    private val retryExecutor by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ZVE-BaiduDebugRetry").apply { isDaemon = true }
        }
    }

    fun install(classLoader: ClassLoader): Int {
        if (installed.get()) return 1
        // 宿主没有 BaiduLBS 类则延迟重试（参考 ZhiYuanHuiHooker：
        // 加固壳在 Application.attach 之后真实 dex 才可解析，onPackageReady 时机可能过早）
        if (findBaiduClass(classLoader) != null) {
            return installNow(classLoader)
        }
        ZLog.i(TAG_SCOPE, "baidu sdk class not ready yet, defer to Application.attach + retry")
        hookApplicationAttach(classLoader)
        scheduleRetry(classLoader)
        return 1
    }

    /** 仿照 ZhiYuanHuiHooker：hook Application.attach，proceed 后壳已完成初始化。 */
    private fun hookApplicationAttach(classLoader: ClassLoader) {
        try {
            val appClass = android.app.Application::class.java
            val attach = appClass.getDeclaredMethod("attach", android.content.Context::class.java)
            val ok = registrar.register(attach) { chain ->
                chain.proceed()
                try {
                    if (!installed.get() && findBaiduClass(classLoader) != null) {
                        installNow(classLoader)
                    }
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "attach-time install failed", t)
                }
                null
            }
            if (ok) {
                ZLog.i(TAG_SCOPE, "hooked Application.attach for late install")
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "hook Application.attach failed", t)
        }
    }

    /** 从宿主 classLoader 及所有已加载 classloader 中查找 BaiduLBS 入口类。 */
    private fun findBaiduClass(classLoader: ClassLoader): Class<*>? {
        HookSupport.findClass(classLoader, CLIENT)?.let { return it }
        var loaderCount = 0
        try {
            // Android 9+ 隐藏 API：枚举所有已加载 classloader（加固壳常用自定义 loader）
            val m = ClassLoader::class.java.getDeclaredMethod("getLoadedClassLoaders")
            m.isAccessible = true
            val loaders = m.invoke(null) as? Array<ClassLoader> ?: return null
            loaderCount = loaders.size
            for (l in loaders) {
                if (l === classLoader) continue
                HookSupport.findClass(l, CLIENT)?.let {
                    ZLog.i(TAG_SCOPE, "found BaiduLBS via extra loader ${l.javaClass.name}")
                    return it
                }
            }
        } catch (t: Throwable) {
            // 忽略：仅支持 API 26+ 的 ROM
        }
        ZLog.d(TAG_SCOPE, "findBaiduClass miss loaders=$loaderCount loader=${classLoader}")
        return null
    }

    /** 延迟重试：每 1s 尝试一次，最多 30s（加固壳解压完成前 SDK 类不可解析）。 */
    private fun scheduleRetry(classLoader: ClassLoader) {
        retryExecutor.schedule(object : Runnable {
            var attempts = 0
            override fun run() {
                try {
                    if (installed.get()) return
                    if (findBaiduClass(classLoader) != null) {
                        installNow(classLoader)
                        return
                    }
                    attempts++
                    if (attempts < 30) {
                        retryExecutor.schedule(this, 1000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    } else {
                        ZLog.w(TAG_SCOPE, "baidu sdk class not found after 30 attempts, give up")
                    }
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "baidu debug retry failed", t)
                }
            }
        }, 1000, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun installNow(classLoader: ClassLoader): Int {
        if (!installed.compareAndSet(false, true)) return 1
        var hooked = 0
        hooked += hookClientOption(classLoader)
        hooked += hookClientLifecycle(classLoader)
        hooked += hookGpsCollector(classLoader)
        hooked += hookDelivery(classLoader)
        hooked += hookBdLocation(classLoader)
        hooked += dumpConfig(classLoader)
        ZLog.i(TAG_SCOPE, "baidu debug hooks installed hooked=$hooked loader=${classLoader}")
        return hooked
    }

    // ---------- LocationClientOption 配置观测 ----------

    private fun hookClientOption(classLoader: ClassLoader): Int {
        val clazz = HookSupport.findClass(classLoader, OPTION) ?: return 0
        var hooked = 0
        // 构造：打印默认配置（必须 proceed，否则 SDK 配置被清空）
        clazz.declaredConstructors.forEach { ctor ->
            val ok = registrar.register(ctor) { chain ->
                try {
                    val self = chain.getThisObject()
                    chain.proceed()
                    if (self != null) {
                        ZLog.i(TAG_SCOPE, "LocationClientOption.<init> -> ${dumpOption(self)}")
                    }
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "option init dump failed", t)
                    chain.proceed()
                }
                null
            }
            if (ok) hooked++
        }
        // 关键 setter：打印参数变化（after，展示生效后配置）
        listOf(
            "setEnableSimulateGps",
            "setLocationMode",
            "setLocationPurpose",
            "setOpenGps",
            "setScanSpan",
            "setCoorType",
            "setServiceName",
            "setOnceLocation",
            "setTimeOut",
            "setPriority"
        ).forEach { name ->
            HookSupport.findMethods(clazz, name).forEach { method ->
                val ok = registrar.register(method) { chain ->
                    try {
                        val self = chain.getThisObject()
                        val arg = if (chain.args.isNotEmpty()) chain.getArg(0) else null
                        val original = chain.proceed()
                        if (self != null) {
                            ZLog.i(
                                TAG_SCOPE,
                                "LocationClientOption.$name(${arg}) -> ${dumpOption(self)}"
                            )
                        }
                        original
                    } catch (t: Throwable) {
                        ZLog.w(TAG_SCOPE, "option setter hook failed $name", t)
                        chain.proceed()
                    }
                }
                if (ok) hooked++
            }
        }
        if (hooked > 0) ZLog.i(TAG_SCOPE, "hooked LocationClientOption hooks=$hooked")
        return hooked
    }

    private fun hookClientLifecycle(classLoader: ClassLoader): Int {
        val clazz = HookSupport.findClass(classLoader, CLIENT) ?: return 0
        var hooked = 0
        HookSupport.findMethods(clazz, "start").forEach { method ->
            val ok = registrar.register(method) { chain ->
                val original = chain.proceed()
                try {
                    val self = chain.getThisObject()
                    ZLog.i(TAG_SCOPE, "LocationClient.start -> option=${dumpClientOption(self)}")
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "client start dump failed", t)
                }
                original
            }
            if (ok) hooked++
        }
        HookSupport.findMethods(clazz, "stop").forEach { method ->
            val ok = registrar.register(method) { chain ->
                val original = chain.proceed()
                ZLog.i(TAG_SCOPE, "LocationClient.stop")
                original
            }
            if (ok) hooked++
        }
        // App 端定位结果接收入口：onReceiveLocation(BDLocation)（混淆后 public void a(BDLocation)）
        HookSupport.findMethods(clazz, "a").forEach { method ->
            if (method.parameterCount == 1 && method.parameterTypes[0].name == "com.baidu.location.BDLocation") {
                val ok = registrar.register(method) { chain ->
                    val original = chain.proceed()
                    try {
                        logOnce(
                            "clientReceive",
                            "LocationClient.onReceiveLocation -> ${dumpBdLocation(chain.getArg(0))}"
                        )
                    } catch (t: Throwable) {
                        ZLog.w(TAG_SCOPE, "onReceiveLocation hook failed", t)
                    }
                    original
                }
                if (ok) hooked++
            }
        }
        // 主动查询：getLastKnownLocation
        HookSupport.findMethods(clazz, "getLastKnownLocation").forEach { method ->
            val ok = registrar.register(method) { chain ->
                val original = chain.proceed()
                try {
                    logOnce("lastKnown", "LocationClient.getLastKnownLocation -> ${dumpBdLocation(original)}")
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "getLastKnownLocation hook failed", t)
                }
                original
            }
            if (ok) hooked++
        }
        if (hooked > 0) ZLog.i(TAG_SCOPE, "hooked LocationClient lifecycle hooks=$hooked")
        return hooked
    }

    // ---------- GPS 采集器 com.baidu.location.c.f ----------

    /** 节流：同一观测点 2s 内只打一条日志。 */
    private val lastLog = ConcurrentHashMap<String, Long>()

    private fun logOnce(key: String, msg: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastLog[key] ?: 0L
        if (now - last >= 2000L) {
            lastLog[key] = now
            ZLog.i(TAG_SCOPE, msg)
        }
    }

    private fun hookGpsCollector(classLoader: ClassLoader): Int {
        var hooked = 0
        // 志愿汇 BaiduLBS 9.1.6（classes4.dex）
        hooked += hookGpsCollectorImpl(classLoader, "com.baidu.location.c.f")
        // 百度地图 Titan LBS 变体（classes15.dex）
        hooked += hookGpsCollectorImpl(classLoader, "com.baidu.location.f.e")
        return hooked
    }

    private fun hookGpsCollectorImpl(classLoader: ClassLoader, clazzName: String): Int {
        val clazz = HookSupport.findClass(classLoader, clazzName) ?: return 0
        var hooked = 0
        val tag = clazzName.substringAfterLast('.')
        // b(): 服务初始化（registerGnssStatusCallback / passive 注册点）
        HookSupport.findMethods(clazz, "b").forEach { method ->
            if (method.parameterCount != 0) return@forEach
            val ok = registrar.register(method) { chain ->
                val original = chain.proceed()
                ZLog.i(TAG_SCOPE, "collector[$tag].b() done (gps service init)")
                original
            }
            if (ok) hooked++
        }
        // c(): start gps（gps listener / NMEA 注册点）
        HookSupport.findMethods(clazz, "c").forEach { method ->
            if (method.parameterCount != 0) return@forEach
            val ok = registrar.register(method) { chain ->
                val original = chain.proceed()
                ZLog.i(TAG_SCOPE, "collector[$tag].c() done (start gps)")
                original
            }
            if (ok) hooked++
        }
        // e(Location): is_mock 判定
        HookSupport.findMethods(clazz, "e").forEach { method ->
            if (method.parameterCount == 1 && method.parameterTypes[0] == Location::class.java) {
                val ok = registrar.register(method) { chain ->
                    val original = chain.proceed()
                    try {
                        logOnce(
                            "e-$tag",
                            "collector[$tag].e(Location) -> is_mock=$original (${locBrief(chain.getArg(0) as? Location)})"
                        )
                    } catch (t: Throwable) {
                        ZLog.w(TAG_SCOPE, "collector[$tag].e() hook failed", t)
                    }
                    original
                }
                if (ok) hooked++
            }
        }
        // f(Location): fix 处理（n() 投递前）
        HookSupport.findMethods(clazz, "f").forEach { method ->
            if (method.parameterCount == 1 && method.parameterTypes[0] == Location::class.java) {
                val ok = registrar.register(method) { chain ->
                    try {
                        val loc = chain.getArg(0) as? Location
                        logOnce(
                            "f-$tag",
                            "collector[$tag].f(Location) -> ${locBrief(loc)} mock=${loc?.isFromMockProvider} sat=${fieldInt(clazz, "a")}"
                        )
                    } catch (t: Throwable) {
                        ZLog.w(TAG_SCOPE, "collector[$tag].f() hook failed", t)
                    }
                    chain.proceed()
                    null
                }
                if (ok) hooked++
            }
        }
        // n(): 投递
        HookSupport.findMethods(clazz, "n").forEach { method ->
            if (method.parameterCount != 0) return@forEach
            val ok = registrar.register(method) { chain ->
                val original = chain.proceed()
                logOnce(
                    "n-$tag",
                    "collector[$tag].n() done (deliver fix) sat=${fieldInt(clazz, "a")} e.m=${fieldBool(findConfig(clazz), "m")}"
                )
                original
            }
            if (ok) hooked++
        }
        if (hooked > 0) ZLog.i(TAG_SCOPE, "hooked collector[$tag] hooks=$hooked")
        return hooked
    }

    // ---------- 投递 com.baidu.location.b.b$a.a(BDLocation, int) ----------

    private fun hookDelivery(classLoader: ClassLoader): Int {
        val clazz = HookSupport.findClass(classLoader, DELIVERY) ?: return 0
        var hooked = 0
        HookSupport.findMethods(clazz, "a").forEach { method ->
            // 匹配 a(BDLocation, int) 与 a(BDLocation)
            val params = method.parameterTypes
            val bd = params.firstOrNull { it.name == "com.baidu.location.BDLocation" }
            if (bd == null) return@forEach
            val ok = registrar.register(method) { chain ->
                try {
                    val bdLoc = chain.getArg(0)
                    val extra = if (params.size > 1) chain.getArg(1) else null
                    ZLog.i(
                        TAG_SCOPE,
                        "b.b\$a.a(BDLocation,$extra) -> ${dumpBdLocation(bdLoc)}"
                    )
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "delivery hook failed", t)
                }
                chain.proceed()
                null
            }
            if (ok) hooked++
        }
        if (hooked > 0) ZLog.i(TAG_SCOPE, "hooked delivery hooks=$hooked")
        return hooked
    }

    // ---------- BDLocation getter 观测 ----------

    private fun hookBdLocation(classLoader: ClassLoader): Int {
        val clazz = HookSupport.findClass(classLoader, "com.baidu.location.BDLocation") ?: return 0
        var hooked = 0
        listOf(
            "getMockGpsStrategy",
            "getMockGpsProbability",
            "getLocType"
        ).forEach { name ->
            HookSupport.findMethods(clazz, name).forEach { method ->
                if (method.parameterCount != 0) return@forEach
                val ok = registrar.register(method) { chain ->
                    val original = chain.proceed()
                    try {
                        ZLog.i(TAG_SCOPE, "BDLocation.$name() -> $original")
                    } catch (t: Throwable) {
                        ZLog.w(TAG_SCOPE, "BDLocation.$name hook failed", t)
                    }
                    original
                }
                if (ok) hooked++
            }
        }
        if (hooked > 0) ZLog.i(TAG_SCOPE, "hooked BDLocation getters hooks=$hooked")
        return hooked
    }

    // ---------- 静态配置 dump ----------

    private fun dumpConfig(classLoader: ClassLoader): Int {
        val clazz = HookSupport.findClass(classLoader, CONFIG) ?: return 0
        try {
            ZLog.i(
                TAG_SCOPE,
                "e.j config: m=${fieldBool(clazz, "m")} aL=${fieldInt(clazz, "aL")} aC=${fieldInt(clazz, "aC")} " +
                    "aB=${fieldDouble(clazz, "aB")} v=${fieldInt(clazz, "v")} d=${fieldInt(clazz, "d")}"
            )
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "dump e.j config failed", t)
        }
        return 1
    }

    // ---------- 反射辅助 ----------

    private var configClassRef: Class<*>? = null

    private fun findConfig(clazz: Class<*>?): Class<*>? {
        if (configClassRef != null) return configClassRef
        configClassRef = try {
            clazz?.classLoader?.let { HookSupport.findClass(it, CONFIG) }
        } catch (t: Throwable) {
            null
        }
        return configClassRef
    }

    private fun fieldInt(clazz: Class<*>?, name: String): Any? {
        if (clazz == null) return null
        return try {
            clazz.getField(name).get(null)
        } catch (t: Throwable) {
            try {
                clazz.getDeclaredField(name).apply { isAccessible = true }.get(null)
            } catch (t2: Throwable) {
                null
            }
        }
    }

    private fun fieldBool(clazz: Class<*>?, name: String): Any? = fieldInt(clazz, name)

    private fun fieldDouble(clazz: Class<*>?, name: String): Any? = fieldInt(clazz, name)

    private fun locBrief(loc: Location?): String {
        if (loc == null) return "null"
        return String.format(
            java.util.Locale.US,
            "%.5f,%.5f acc=%.1f t=%d provider=%s",
            loc.latitude,
            loc.longitude,
            loc.accuracy,
            loc.time,
            loc.provider
        )
    }


    private fun dumpClientOption(client: Any?): String {
        if (client == null) return "null"
        return try {
            val getter = client.javaClass.getMethod("getLocOption")
            dumpOption(getter.invoke(client))
        } catch (t: Throwable) {
            "client dump failed: ${t.message}"
        }
    }

    private fun dumpOption(option: Any?): String {
        if (option == null) return "null"
        return try {
            val sb = StringBuilder()
            val fields = mutableListOf(
                "enableSimulateGps",
                "openGps",
                "scanSpan",
                "timeOut",
                "coorType",
                "addrType",
                "serviceName",
                "priority",
                "location_change_notify",
                "isOnceLocation",
                "isNeedAltitude",
                "disableLocCache"
            )
            for (name in fields) {
                try {
                    val f = option.javaClass.getField(name)
                    sb.append("$name=${f.get(option)} ")
                } catch (_: NoSuchFieldException) {
                }
            }
            sb.toString()
        } catch (t: Throwable) {
            "option dump failed: ${t.message}"
        }
    }

    private fun dumpBdLocation(bd: Any?): String {
        if (bd == null) return "null"
        return try {
            val getters = mapOf(
                "getLocType" to null,
                "getLatitude" to null,
                "getLongitude" to null,
                "getMockGpsStrategy" to null,
                "getMockGpsProbability" to null,
                "getCoorType" to null
            )
            val sb = StringBuilder()
            for ((name, _) in getters) {
                try {
                    val m: Method = bd.javaClass.getMethod(name)
                    sb.append("$name=${m.invoke(bd)} ")
                } catch (_: NoSuchMethodException) {
                }
            }
            sb.toString()
        } catch (t: Throwable) {
            "BDLocation dump failed: ${t.message}"
        }
    }
}
