package io.github.fairyxh.VirtualEnv.hook

import io.github.fairyxh.VirtualEnv.util.ZLog

/**
 * ColorOS/Oplus 服务启动限制绕过（百度定位服务适配辅助）。
 *
 * 现象：应用内置的百度定位服务 com.baidu.location.f 声明在独立进程，
 * ColorOS 的 FGS/后台启动限制（dumpsys 中 ServiceRecord.mAllowStart_byBindings=DENIED）
 * 会拦截 bindService 导致的进程创建，SDK 服务端永不启动 → BDLocation 167/66/67。
 *
 * 策略（仅对百度定位服务放行，其余服务完全放行原始行为，fail-open）：
 * 1. ServiceRecord.isFgsAllowedStart() → 定位服务返回 true（FGS 判定路径）
 * 2. ActiveServices.setFgsRestrictionLocked(...) → 定位服务跳过限制写入
 * 3. ActiveServices.bringUpServiceLocked(ServiceRecord, ...) → 强制
 *    mAllowStart_byBindings=ALLOWED（兜底）
 */
class OplusServiceStartBypass(
    private val registrar: HookRegistrar,
) {
    companion object {
        private const val TAG_SCOPE = "Hook"
        private const val SERVICE_RECORD = "com.android.server.am.ServiceRecord"
        private const val ACTIVE_SERVICES = "com.android.server.am.ActiveServices"

        /** 志愿汇等应用会内置该服务，不能按宿主包名限定。 */
        private const val TARGET_SERVICE = "com.baidu.location.f"
    }

    fun install(classLoader: ClassLoader): Int {
        val sr = HookSupport.findClass(classLoader, SERVICE_RECORD) ?: return 0
        var hooked = 0
        hooked += hookServiceRecordInit(sr)
        hooked += hookIsFgsAllowedStart(sr)
        val asClazz = HookSupport.findClass(classLoader, ACTIVE_SERVICES)
        if (asClazz != null) {
            hooked += hookSetFgsRestriction(asClazz)
            hooked += hookBringUpService(asClazz)
        }
        ZLog.i(TAG_SCOPE, "oplus service start bypass installed hooked=$hooked")
        return hooked
    }

    /** ServiceRecord 构造后立即把 allow-start 字段写为 ALLOWED（兜底默认 DENIED 的 ROM）。 */
    private fun hookServiceRecordInit(sr: Class<*>): Int {
        var hooked = 0
        sr.declaredConstructors.forEach { ctor ->
            if (ctor.parameterCount < 8) return@forEach
            val ok = registrar.register(ctor) { chain ->
                val original = chain.proceed()
                try {
                    val self = chain.getThisObject()
                    if (isBaiduLocService(self)) {
                        forceAllowStart(self)
                        ZLog.i(TAG_SCOPE, "ServiceRecord.<init> -> baidu loc service force allow")
                    }
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "ServiceRecord init hook failed", t)
                }
                original
            }
            if (ok) hooked++
        }
        return hooked
    }

    private fun hookIsFgsAllowedStart(sr: Class<*>): Int {
        var hooked = 0
        HookSupport.findMethods(sr, "isFgsAllowedStart").forEach { method ->
            if (method.parameterCount != 0) return@forEach
            val ok = registrar.register(method) { chain ->
                val self = chain.getThisObject()
                if (isBaiduLocService(self)) {
                    ZLog.i(TAG_SCOPE, "isFgsAllowedStart -> true (baidu loc service bypass)")
                    true
                } else {
                    chain.proceed()
                }
            }
            if (ok) hooked++
        }
        return hooked
    }

    private fun hookSetFgsRestriction(asClazz: Class<*>): Int {
        var hooked = 0
        HookSupport.findMethods(asClazz, "setFgsRestrictionLocked").forEach { method ->
            if (method.parameterCount < 8) return@forEach
            val ok = registrar.register(method) { chain ->
                val callingPkg = chain.getArg(0) as? String
                val r = chain.getArg(4)
                if (isBaiduLocService(r)) {
                    ZLog.i(TAG_SCOPE, "setFgsRestrictionLocked target baidu loc service (pkg=$callingPkg), force allow after")
                    val original = chain.proceed()
                    try {
                        forceAllowStart(r)
                    } catch (t: Throwable) {
                        ZLog.w(TAG_SCOPE, "forceAllowStart after setFgsRestriction failed", t)
                    }
                    original
                } else {
                    chain.proceed()
                }
            }
            if (ok) hooked++
        }
        return hooked
    }

    private fun hookBringUpService(asClazz: Class<*>): Int {
        var hooked = 0
        HookSupport.findMethods(asClazz, "bringUpServiceLocked").forEach { method ->
            if (method.parameterCount < 7) return@forEach
            val ok = registrar.register(method) { chain ->
                val r = chain.getArg(0)
                if (isBaiduLocService(r)) {
                    try {
                        forceAllowStart(r)
                        ZLog.i(TAG_SCOPE, "bringUpServiceLocked: force mAllowStart_byBindings=ALLOWED")
                    } catch (t: Throwable) {
                        ZLog.w(TAG_SCOPE, "force allow start field failed", t)
                    }
                }
                chain.proceed()
                null
            }
            if (ok) hooked++
        }
        return hooked
    }

    /** 强制写入 allow-start 相关字段（0=ALLOWED）。 */
    private fun forceAllowStart(r: Any) {
        val names = arrayOf(
            "mAllowStart_noBinding",
            "mAllowStart_inBindService",
            "mAllowStart_byBindings"
        )
        for (n in names) {
            try {
                val f = r.javaClass.getDeclaredField(n)
                f.isAccessible = true
                f.set(r, 0)
            } catch (t: Throwable) {
                ZLog.d(TAG_SCOPE, "force field $n failed: ${t.message}")
            }
        }
    }

    /** 反射判断 ServiceRecord 是否为任意宿主包内的百度定位服务组件。 */
    private fun isBaiduLocService(self: Any?): Boolean {
        if (self == null) return false
        return try {
            val pkg = fieldValue(self, "packageName") as? String
            // 组件类名优先取 intent.component（dumpsys 中 cmp=host/com.baidu.location.f），
            // 其次 serviceInfo.name。name 继承自 PackageItemInfo，必须沿父类查找；
            // 直接 getDeclaredField 在 ServiceInfo 上会抛 NoSuchFieldException 导致误判。
            val intent = fieldValue(self, "intent") as? android.content.Intent
            val cmp = intent?.component
            if (cmp != null && cmp.className == TARGET_SERVICE) {
                ZLog.d(TAG_SCOPE, "matched baidu loc service pkg=$pkg (intent)")
                return true
            }
            val serviceInfo = fieldValue(self, "serviceInfo")
            if (serviceInfo != null) {
                val cn = serviceInfo as? android.content.ComponentName
                if (cn != null && cn.className == TARGET_SERVICE) {
                    ZLog.d(TAG_SCOPE, "matched baidu loc service pkg=$pkg (component)")
                    return true
                }
                val name = fieldValue(serviceInfo, "name") as? String
                if (name == TARGET_SERVICE) {
                    ZLog.d(TAG_SCOPE, "matched baidu loc service pkg=$pkg (serviceInfo.name)")
                    return true
                }
            }
            false
        } catch (t: Throwable) {
            ZLog.d(TAG_SCOPE, "isBaiduLocService reflect failed: ${t.message}")
            false
        }
    }

    /** 沿类继承链向上查找字段（覆盖父类声明字段，如 ServiceInfo.name 声明在 PackageItemInfo）。 */
    private fun fieldValue(obj: Any, name: String): Any? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(name)
                f.isAccessible = true
                return f.get(obj)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            } catch (t: Throwable) {
                ZLog.d(TAG_SCOPE, "fieldValue $name failed: ${t.message}")
                return null
            }
        }
        return null
    }
}
