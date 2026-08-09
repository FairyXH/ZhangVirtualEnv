package io.github.fairyxh.VirtualEnv.hook

import io.github.fairyxh.VirtualEnv.util.ZLog
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
}
