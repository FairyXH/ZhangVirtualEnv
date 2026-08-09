package io.github.fairyxh.VirtualEnv.hook

import io.github.fairyxh.VirtualEnv.util.ZLog
import java.lang.reflect.Method

/**
 * 虚拟基站数据工厂（借鉴 GhostMapX：给地图 SDK 提供带虚拟经纬度的基站，
 * 而不是返回空列表，否则 SDK 网络定位直接失败）。
 *
 * 核心思路：CellIdentityCdma 携带 baseStationLatitude / baseStationLongitude
 * 字段，地图 SDK 读取后发往厂商服务器，服务器按基站坐标换算即得到虚拟位置。
 *
 * Oplus/Android 15 实测签名（JADX framework.jar 确认）：
 * - CellIdentityCdma(int nid, int sid, int bid, int lon, int lat, String alphal, String alphas)
 *   —— 第 4 参是经度、第 5 参是纬度；字段为 final，无 setter，必须用 7 参构造。
 * - CellInfoCdma(int, boolean registered, long timestamp, CellIdentityCdma, CellSignalStrengthCdma)
 * - CellSignalStrengthCdma() no-arg public
 */
object VirtualCellFactory {

    private const val TAG_SCOPE = "Hook"
    private const val CELL_INFO_CDMA = "android.telephony.CellInfoCdma"
    private const val CELL_IDENTITY_CDMA = "android.telephony.CellIdentityCdma"
    private const val CELL_SIGNAL_CDMA = "android.telephony.CellSignalStrengthCdma"

    /**
     * 构造一个注册态虚拟小区（CellInfoCdma）。
     *
     * @param latitude 虚拟纬度
     * @param longitude 虚拟经度
     */
    fun buildCellInfoCdma(latitude: Double, longitude: Double): Any? {
        return try {
            val infoClass = Class.forName(CELL_INFO_CDMA)
            val identity = buildCellIdentityCdma(latitude, longitude) ?: return null
            val signal = newInstance(Class.forName(CELL_SIGNAL_CDMA))
            // 1. 5 参构造（registered=true, timestamp 新鲜）
            try {
                val ctor = infoClass.getConstructor(
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    Class.forName(CELL_IDENTITY_CDMA),
                    Class.forName(CELL_SIGNAL_CDMA)
                )
                val info = ctor.newInstance(0, true, System.nanoTime(), identity, signal)
                ZLog.d(TAG_SCOPE, "CellInfoCdma via 5-arg ctor lat=$latitude lon=$longitude")
                return info
            } catch (_: NoSuchMethodException) {
            }
            // 2. no-arg + setCellIdentity/setCellSignalStrength
            val info = newInstance(infoClass) ?: return null
            call(info, "setCellIdentity", identity)
            if (signal != null) {
                call(info, "setCellSignalStrength", signal)
            }
            ZLog.d(TAG_SCOPE, "CellInfoCdma via no-arg ctor+setters lat=$latitude lon=$longitude")
            info
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "build CellInfoCdma failed", t)
            null
        }
    }

    /**
     * 构造携带虚拟经纬度的 CellIdentityCdma（7 参公开构造，顺序 nid/sid/bid/lon/lat）。
     */
    fun buildCellIdentityCdma(latitude: Double, longitude: Double): Any? {
        return try {
            val cls = Class.forName(CELL_IDENTITY_CDMA)
            val ctor = cls.getConstructor(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java
            )
            val lonQ = (longitude * 14400.0).toInt()
            val latQ = (latitude * 14400.0).toInt()
            val identity = ctor.newInstance(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, lonQ, latQ, null, null)
            ZLog.d(TAG_SCOPE, "CellIdentityCdma 7-arg ctor lon=$lonQ lat=$latQ")
            identity
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "build CellIdentityCdma failed", t)
            null
        }
    }

    private fun newInstance(cls: Class<*>): Any? {
        return try {
            cls.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "no-arg ctor not found on ${cls.name}", t)
            null
        }
    }

    private fun call(target: Any, name: String, value: Any) {
        val m = findMethod(target.javaClass, name, 1) ?: return
        m.invoke(target, boxFor(m, value))
    }

    private fun findMethod(clazz: Class<*>, name: String, paramCount: Int): Method? {
        return clazz.methods.firstOrNull { it.name == name && it.parameterCount == paramCount }
    }

    private fun boxFor(m: Method, value: Any): Any {
        val param = m.parameterTypes[0]
        return when {
            param == java.lang.Boolean.TYPE && value is Boolean -> value
            param == java.lang.Integer.TYPE && value is Int -> value
            param == java.lang.Long.TYPE && value is Long -> value
            param == Int::class.java -> (value as Number).toInt()
            param == Long::class.java -> (value as Number).toLong()
            else -> value
        }
    }
}
