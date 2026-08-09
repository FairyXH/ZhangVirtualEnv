package io.github.fairyxh.VirtualEnv.hook

import android.location.Location
import io.github.fairyxh.VirtualEnv.util.ZLog

/**
 * 反射构造 android.location.LocationResult 的统一工厂。
 *
 * Android 15 AOSP：LocationResult.wrap(Location)
 * ColorOS/Oplus 15：LocationResult.wrap(List<Location>)（已实测）
 * 兜底：LocationResult.create(List<Location>)
 *
 * LocationResult 由 boot classloader 加载，必须用目标进程的 classLoader 解析，
 * 不能引用编译期类型。
 */
object LocationResultFactory {

    private const val TAG_SCOPE = "Hook"
    private const val LOCATION_RESULT_CLASS = "android.location.LocationResult"

    /**
     * 解析 LocationResult 类。
     *
     * @param classLoader 目标进程 class loader（system_server 传入 boot classloader）
     */
    fun resolveClass(classLoader: ClassLoader): Class<*>? {
        return try {
            Class.forName(LOCATION_RESULT_CLASS, false, classLoader)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "LocationResult class not found", t)
            null
        }
    }

    /**
     * 构造包含单个虚拟位置的 LocationResult。
     *
     * @param locationResultClass 由 [resolveClass] 解析的类
     * @param location 虚拟位置
     */
    fun create(locationResultClass: Class<*>, location: Location): Any {
        // 1. wrap(Location)
        try {
            val m = locationResultClass.getMethod("wrap", Location::class.java)
            return m.invoke(null, location)!!
        } catch (_: NoSuchMethodException) {
        }
        // 2. wrap(List)
        try {
            val m = locationResultClass.getMethod("wrap", List::class.java)
            return m.invoke(null, listOf(location))!!
        } catch (_: NoSuchMethodException) {
        }
        // 3. create(List)
        try {
            val m = locationResultClass.getMethod("create", List::class.java)
            return m.invoke(null, listOf(location))!!
        } catch (_: NoSuchMethodException) {
        }
        throw NoSuchMethodException("no LocationResult factory: wrap(Location)/wrap(List)/create(List)")
    }
}
