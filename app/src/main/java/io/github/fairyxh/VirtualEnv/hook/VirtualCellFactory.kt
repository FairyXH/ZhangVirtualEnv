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

    // ---------- 按 cell 配置构建对应类型虚拟基站（LTE/GSM/NR/WCDMA，App 层与 phone 栈共用） ----------

    /** 兼容两种数据键：采集包用 cells[]，环境模拟配置页用 entries[]。 */
    fun buildCellInfoList(data: org.json.JSONObject): List<Any> {
        val cells = data.optJSONArray("cells") ?: data.optJSONArray("entries") ?: return emptyList()
        val result = mutableListOf<Any>()
        for (i in 0 until cells.length()) {
            val c = cells.optJSONObject(i) ?: continue
            try {
                when (c.optString("type", "").uppercase()) {
                    "LTE" -> buildLteCell(c)?.let { result.add(it) }
                    "GSM" -> buildGsmCell(c)?.let { result.add(it) }
                    "NR" -> buildNrCell(c)?.let { result.add(it) }
                    "WCDMA" -> buildWcdmaCell(c)?.let { result.add(it) }
                    "" -> buildLteCell(c)?.let { result.add(it) } // 缺省按 LTE
                }
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "build cell[${c.optString("type")}] failed", t)
            }
        }
        return result
    }

    private fun buildLteCell(c: org.json.JSONObject): Any? {
        val infoClass = Class.forName("android.telephony.CellInfoLte")
        val info = infoClass.getDeclaredConstructor().newInstance()

        val identityClass = Class.forName("android.telephony.CellIdentityLte")
        val ci = c.optLong("ci", -1L).toInt()
        val pci = c.optInt("pci", -1)
        val tac = c.optLong("tac", -1L).toInt()
        // AOSP/Oplus 5 参公开构造顺序：(mcc, mnc, ci, pci, tac)
        val identity = identityClass.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java, Int::class.java, Int::class.java
        ).newInstance(
            c.optInt("mcc", 460),
            c.optInt("mnc", 0),
            ci,
            pci,
            tac
        )
        infoClass.getMethod("setCellIdentity", identityClass).invoke(info, identity)

        val signalClass = Class.forName("android.telephony.CellSignalStrengthLte")
        val unknown = Int.MAX_VALUE
        val signal = signalClass.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            Int::class.java, Int::class.java
        ).newInstance(
            unknown,
            c.optInt("rsrp", -110),
            unknown,
            unknown,
            unknown,
            unknown
        )
        infoClass.getMethod("setCellSignalStrength", signalClass).invoke(info, signal)
        return info
    }

    private fun buildGsmCell(c: org.json.JSONObject): Any? {
        val infoClass = Class.forName("android.telephony.CellInfoGsm")
        val info = infoClass.getDeclaredConstructor().newInstance()

        val identityClass = Class.forName("android.telephony.CellIdentityGsm")
        val identity = identityClass.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java,
            java.util.Collection::class.java
        ).newInstance(
            c.optInt("mcc", 460),
            c.optInt("mnc", 0),
            c.optLong("lac", -1L).toInt(),
            c.optLong("cid", -1L).toInt(),
            "", "", "", "",
            java.util.Collections.emptyList<Any>()
        )
        infoClass.getMethod("setCellIdentity", identityClass).invoke(info, identity)

        val signalClass = Class.forName("android.telephony.CellSignalStrengthGsm")
        val signal = signalClass.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java
        ).newInstance(
            c.optInt("rssi", -90),
            -1,
            -1
        )
        infoClass.getMethod("setCellSignalStrength", signalClass).invoke(info, signal)
        return info
    }

    private fun buildNrCell(c: org.json.JSONObject): Any? {
        val infoClass = Class.forName("android.telephony.CellInfoNr")
        val info = infoClass.getDeclaredConstructor().newInstance()

        val identityClass = Class.forName("android.telephony.CellIdentityNr")
        val mcc = c.optInt("mcc", 460)
        val mnc = c.optInt("mnc", 0)
        // additionalPlmns 不能为 null：构造器会调 Collection.size() 直接 NPE
        val identity = identityClass.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java, IntArray::class.java,
            String::class.java, String::class.java, Long::class.java,
            String::class.java, String::class.java, java.util.Collection::class.java
        ).newInstance(
            mcc,
            mnc,
            c.optLong("tac", -1L).toInt(),
            intArrayOf(),
            mcc.toString(),
            String.format("%02d", mnc),
            c.optLong("nci", -1L),
            "", "",
            java.util.Collections.emptyList<Any>()
        )
        infoClass.getMethod("setCellIdentity", identityClass).invoke(info, identity)
        return info
    }

    private fun buildWcdmaCell(c: org.json.JSONObject): Any? {
        val infoClass = Class.forName("android.telephony.CellInfoWcdma")
        val info = infoClass.getDeclaredConstructor().newInstance()

        val identityClass = Class.forName("android.telephony.CellIdentityWcdma")
        val identity = identityClass.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java,
            java.util.Collection::class.java
        ).newInstance(
            c.optInt("mcc", 460),
            c.optInt("mnc", 0),
            c.optLong("lac", -1L).toInt(),
            c.optLong("cid", -1L).toInt(),
            "", "", "", "",
            java.util.Collections.emptyList<Any>()
        )
        infoClass.getMethod("setCellIdentity", identityClass).invoke(info, identity)

        val signalClass = Class.forName("android.telephony.CellSignalStrengthWcdma")
        val signal = signalClass.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java
        ).newInstance(
            c.optInt("rssi", -90),
            -1,
            -1
        )
        infoClass.getMethod("setCellSignalStrength", signalClass).invoke(info, signal)
        return info
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
