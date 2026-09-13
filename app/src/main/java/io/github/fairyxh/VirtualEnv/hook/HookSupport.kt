package io.github.fairyxh.VirtualEnv.hook

import io.github.fairyxh.VirtualEnv.util.ZLog
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * Hook 反射工具。
 *
 * 统一管理目标类的解析与方法匹配，所有 Hook Adapter 共用，
 * 避免每个 Adapter 重复 try/catch 样板。
 */
object HookSupport {

    private const val TAG_SCOPE = "Hook"

    /**
     * 解析目标类。
     *
     * @param classLoader system_server 的 class loader
     * @param className 类名
     * @return Class 或 null（解析失败时记录日志并返回 null，不抛出）
     */
    fun findClass(classLoader: ClassLoader, className: String): Class<*>? {
        return try {
            Class.forName(className, false, classLoader)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "class not found: $className", t)
            null
        }
    }

    /**
     * 在类及其父类中查找同名方法。
     *
     * @param clazz 目标类
     * @param methodName 方法名
     * @return 匹配的方法列表（可能为空）
     */
    fun findMethods(clazz: Class<*>, methodName: String): List<Method> {
        val result = mutableListOf<Method>()
        var current: Class<*>? = clazz
        while (current != null) {
            current.declaredMethods.forEach { m ->
                if (m.name == methodName && !result.contains(m)) {
                    result.add(m)
                }
            }
            current = current.superclass
        }
        return result
    }

    /**
     * 用 [safeHookResult] 包装 Hook 回调。
     *
     * 所有 Hook 安装点（VirtualEnvEntry 的两处 HookRegistrar）统一经由本函数注册，
     * 保证回调返回值始终与目标方法返回类型匹配，避免宿主进程被 Hook 桥的解包异常杀死。
     */
    fun guardInterceptor(
        executable: Executable,
        interceptor: XposedInterface.Hooker,
    ): XposedInterface.Hooker = XposedInterface.Hooker { chain ->
        safeHookResult(executable, interceptor.intercept(chain))
    }

    /**
     * Hook 返回值类型安全化（防宿主进程崩溃）。
     *
     * LSPosed 生成的 Hook 桥按目标方法**返回类型**解包回调返回值：目标返回基本类型
     * `boolean` 而回调返回 null 时，桥内执行 `((Boolean) null).booleanValue()`，
     * 直接抛 NullPointerException，且异常在**宿主进程**内抛出 —— 宿主进程随即被系统
     * 判定 CRASH 并重启。
     *
     * 真机实测（ColorOS 15 / Oplus 15，2026-09-13）：`SensorManager.registerListener`
     * 的 Hook 在"未接管"路径丢弃了原返回值并返回 null，导致
     * `com.google.android.gms.persistent` 与 `com.android.bluetooth` 反复崩溃
     * （NPE: `Attempt to invoke virtual method 'boolean java.lang.Boolean.booleanValue()'
     * on a null object reference`，am_crash 崩溃签名恒定）。
     *
     * 规则（fail-safe，不改变已定义行为）：
     * - 目标返回 void：null 正确，原样返回；
     * - 目标返回引用类型：null 合法，原样返回；
     * - 目标返回基本类型：null 或类型不匹配 → 回落该类型默认值（false/0/0f/...）并记录警告，
     *   便于后续定位是哪个 Hook 返回了 null。
     *
     * 注意：本函数不会调用 `chain.proceed()`；需要恢复真实行为的 Hook 必须在回调内
     * `return chain.proceed()`（返回原始返回值），而不是返回 null。
     */
    fun safeHookResult(executable: Executable, result: Any?): Any? {
        // 注意：返回类型只在 Method 上可用（Executable 本身没有 getReturnType），
        // Constructor 视作 void（返回 null 即正确）。
        val returnType: Class<*> = (executable as? Method)?.returnType ?: Void.TYPE
        if (returnType == Void.TYPE) return null
        if (!returnType.isPrimitive) return result
        val coerced = coercePrimitive(returnType, result)
        if (coerced == null) {
            ZLog.w(
                TAG_SCOPE,
                "hook ${executable.declaringClass.name}.${executable.name} returned " +
                    "${if (result == null) "null" else result.javaClass.name} for primitive " +
                    "${returnType.name}, fallback ${defaultPrimitive(returnType)}"
            )
            return defaultPrimitive(returnType)
        }
        return coerced
    }

    /** 基本类型返回值的默认值（void 由调用方处理，不在本表内）。 */
    private fun defaultPrimitive(returnType: Class<*>): Any? = when (returnType) {
        java.lang.Boolean.TYPE -> false
        java.lang.Character.TYPE -> '\u0000'
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }

    /** 把回调返回值适配为目标基本类型；无法适配（含 null）返回 null，由调用方回落默认值。 */
    private fun coercePrimitive(returnType: Class<*>, result: Any?): Any? = when (returnType) {
        java.lang.Boolean.TYPE -> when (result) {
            null, is Boolean -> result
            is Number -> result.toInt() != 0
            else -> null
        }
        java.lang.Character.TYPE -> result as? Char
        java.lang.Byte.TYPE -> (result as? Number)?.toByte()
        java.lang.Short.TYPE -> (result as? Number)?.toShort()
        java.lang.Integer.TYPE -> (result as? Number)?.toInt()
        java.lang.Long.TYPE -> (result as? Number)?.toLong()
        java.lang.Float.TYPE -> (result as? Number)?.toFloat()
        java.lang.Double.TYPE -> (result as? Number)?.toDouble()
        else -> null
    }
}
