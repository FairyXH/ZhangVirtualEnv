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
 * ColorOS/Android 15 实测：CellInfo* 有 public no-arg 构造 + setter；
 * CellIdentityCdma 优先尝试 7 参公开构造，失败回退 no-arg + setter。
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
            val info = newInstance(infoClass) ?: return null
            call(info, "setRegistered", true)
            call(info, "setTimeStamp", System.nanoTime())
            callQuiet(info, "setCellConnectionStatus", 0)
            val identity = buildCellIdentityCdma(latitude, longitude) ?: return null
            call(info, "setCellIdentity", identity)
            val signal = newInstance(Class.forName(CELL_SIGNAL_CDMA))
            if (signal != null) {
                callQuiet(info, "setCellSignalStrength", signal)
            }
            info
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "build CellInfoCdma failed", t)
            null
        }
    }

    /**
     * 构造携带虚拟经纬度的 CellIdentityCdma。
     *
     * 优先 7 参公开构造（networkId, systemId, baseStationId, baseStationLatitude,
     * baseStationLongitude, alphanumeric, operatorAlpha）；失败回退 no-arg + setter。
     */
    fun buildCellIdentityCdma(latitude: Double, longitude: Double): Any? {
        return try {
            val cls = Class.forName(CELL_IDENTITY_CDMA)
            // 1. 7 参构造（API 30+ 公开签名）
            try {
                val ctor = cls.getConstructor(
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    String::class.java
                )
                val identity = ctor.newInstance(
                    Int.MAX_VALUE,
                    Int.MAX_VALUE,
                    Int.MAX_VALUE,
                    (latitude * 14400.0).toInt(),
                    (longitude * 14400.0).toInt(),
                    null,
                    null
                )
                ZLog.d(TAG_SCOPE, "CellIdentityCdma via 7-arg ctor lat=$latitude lon=$longitude")
                return identity
            } catch (_: NoSuchMethodException) {
            }
            // 2. no-arg + setter（Oplus 15 支持）
            val identity = newInstance(cls) ?: return null
            callQuiet(identity, "setNetworkId", Int.MAX_VALUE)
            callQuiet(identity, "setSystemId", Int.MAX_VALUE)
            callQuiet(identity, "setBasestationId", Int.MAX_VALUE)
            callQuiet(identity, "setBasestationLatitude", (latitude * 14400.0).toInt())
            callQuiet(identity, "setBasestationLongitude", (longitude * 14400.0).toInt())
            ZLog.d(TAG_SCOPE, "CellIdentityCdma via no-arg ctor+setters lat=$latitude lon=$longitude")
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
        val m = target.javaClass.getMethod(name, value.javaClass)
        m.invoke(target, value)
    }

    private fun callQuiet(target: Any, name: String, value: Any) {
        try {
            val candidates = target.javaClass.methods.filter { it.name == name && it.parameterCount == 1 }
            for (m in candidates) {
                try {
                    m.invoke(target, boxFor(m, value))
                    return
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "call $name failed on ${target.javaClass.name}", t)
        }
    }

    private fun boxFor(m: Method, value: Any): Any {
        val param = m.parameterTypes[0]
        return when {
            param == java.lang.Boolean.TYPE -> value
            param == java.lang.Integer.TYPE && value is Int -> value
            param == java.lang.Long.TYPE && value is Long -> value
            param == Int::class.java -> (value as Number).toInt()
            param == Long::class.java -> (value as Number).toLong()
            else -> value
        }
    }
}
