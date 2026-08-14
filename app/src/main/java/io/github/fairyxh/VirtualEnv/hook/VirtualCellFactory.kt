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
     *
     * nid/sid/bid 必须为合法正数：百度 SDK 解析 `a(int)` 会把 Integer.MAX_VALUE
     * 归一为 -1，随后 `com.baidu.location.c.a.b()`（要求 lac > -1 && cid > 0）
     * 判定基站无效并丢弃——这是百度网络定位拿不到基站数据的根因。
     * 这里从虚拟坐标派生稳定 ID，保证同一虚拟点每次生成一致且合法。
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
            // 派生合法基站 ID：CDMA sid/nid/bid 均在 1..65534，>0 通过百度 b() 校验
            val seed = Math.abs(lonQ.toLong() * 31 + latQ.toLong()).toInt()
            val nid = 1 + (seed and 0x7FFF)
            val sid = 1 + ((seed ushr 15) and 0x7FFF)
            val bid = 1 + ((seed ushr 2) and 0x7FFF)
            val identity = ctor.newInstance(nid, sid, bid, lonQ, latQ, null, null)
            ZLog.d(TAG_SCOPE, "CellIdentityCdma 7-arg ctor nid=$nid sid=$sid bid=$bid lon=$lonQ lat=$latQ")
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
                    "UMTS" -> buildWcdmaCell(c)?.let { result.add(it) }
                    "CDMA" -> buildCdmaCell(c)?.let { result.add(it) }
                    "" -> buildLteCell(c)?.let { result.add(it) } // 缺省按 LTE
                }
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "build cell[${c.optString("type")}] failed", t)
            }
        }
        return result
    }

    /**
     * 构建显式 CDMA 小区（用户配置 sid/nid/bid + 经纬度 + 信号）。
     * 与自动 fallback 的区别：使用配置的 sid/nid/bid，不再从坐标派生。
     */
    fun buildCdmaCell(c: org.json.JSONObject): Any? {
        return try {
            val infoClass = Class.forName(CELL_INFO_CDMA)
            val identityClass = Class.forName(CELL_IDENTITY_CDMA)
            val signalClass = Class.forName(CELL_SIGNAL_CDMA)
            // 经纬度：配置显式坐标优先，缺失时用虚拟位置（调用方已传入？这里由 cache 侧填 lat/lon）
            val lat = c.optDouble("lat", c.optDouble("_cellLat", 0.0))
            val lon = c.optDouble("lon", c.optDouble("_cellLon", 0.0))
            val nid = sanitizeInt(c, "nid", 1, 65534, 1)
            val sid = sanitizeInt(c, "sid", 1, 65534, 1)
            val bid = sanitizeInt(c, "bid", 1, 65534, 1)
            val lonQ = (lon * 14400.0).toInt()
            val latQ = (lat * 14400.0).toInt()
            val ctor = identityClass.getConstructor(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java
            )
            val identity: Any = ctor.newInstance(nid, sid, bid, lonQ, latQ, null, null)
            val signal: Any = newInstance(signalClass) ?: return null
            // CellSignalStrengthCdma 无公开 setter 且无参构造后字段为 0/MAX 哨兵，尝试反射设置
            try {
                signalClass.getMethod("setDbm", Int::class.javaPrimitiveType).invoke(signal, sanitizeInt(c, "dbm", -120, -40, -90))
            } catch (_: Throwable) {
            }
            val info = try {
                infoClass.getConstructor(
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    identityClass,
                    signalClass
                ).newInstance(0, true, System.nanoTime(), identity, signal)
            } catch (_: NoSuchMethodException) {
                newInstance(infoClass)?.also {
                    call(it, "setCellIdentity", identity)
                    call(it, "setCellSignalStrength", signal)
                } ?: return null
            }
            ZLog.d(TAG_SCOPE, "CellInfoCdma configured nid=$nid sid=$sid bid=$bid lat=$lat lon=$lon")
            info
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "build configured CdmaCell failed", t)
            null
        }
    }

    private fun buildLteCell(c: org.json.JSONObject): Any? {
        val infoClass = Class.forName("android.telephony.CellInfoLte")
        val info = infoClass.getDeclaredConstructor().newInstance()

        val identityClass = Class.forName("android.telephony.CellIdentityLte")
        // Oplus 15 JADX 确认：CellIdentityLte(int mcc, int mnc, int ci, int pci, int tac)
        // 字段范围：ci 0..268435455（28bit）、pci 0..503、tac 0..16777215（24bit）
        // EARFCN 可选：优先 6 参构造 (mcc, mnc, ci, pci, tac, earfcn)，失败回退 5 参
        val ciRaw = c.optLong("ci", c.optLong("eci", -1L))
        val ci = if (ciRaw in 0..268435455L) ciRaw.toInt() else {
            // eNodeB ID / Cell ID 组合：eNodeB<<8 | Cell
            val enb = c.optLong("enodebId", -1L)
            val cellPart = c.optLong("cellId", -1L)
            if (enb >= 0 && cellPart >= 0) ((enb and 0xFFFFF) shl 8 or (cellPart and 0xFF)).toInt()
            else 0
        }
        val pci = sanitizeInt(c, "pci", 0, 503)
        val tac = sanitizeInt(c, "tac", 0, 16777215)
        val earfcn = sanitizeInt(c, "earfcn", 0, 262143, -1)
        val identity: Any = try {
            if (earfcn >= 0) {
                identityClass.getDeclaredConstructor(
                    Int::class.java, Int::class.java, Int::class.java, Int::class.java, Int::class.java, Int::class.java
                ).newInstance(
                    sanitizeInt(c, "mcc", 0, 999, 460),
                    sanitizeInt(c, "mnc", 0, 999, 0),
                    ci,
                    pci,
                    tac,
                    earfcn
                )
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } ?: identityClass.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java, Int::class.java, Int::class.java
        ).newInstance(
            sanitizeInt(c, "mcc", 0, 999, 460),
            sanitizeInt(c, "mnc", 0, 999, 0),
            ci,
            pci,
            tac
        ) as Any
        // 5 参构造的 CellIdentityLte.earfcn 默认为 CellInfo.UNAVAILABLE（Int.MAX_VALUE），
        // 会导致 App/检测器读到 2147483647 哨兵；反射写回 -1 表示"未配置"
        if (earfcn < 0) {
            try {
                identity.javaClass.getDeclaredField("mEarfcn").also { it.isAccessible = true }.set(identity, -1)
            } catch (t: Throwable) {
                try {
                    identity.javaClass.getField("earfcn").set(identity, -1)
                } catch (_: Throwable) {
                }
            }
        }
        infoClass.getMethod("setCellIdentity", identityClass).invoke(info, identity)

        val signalClass = Class.forName("android.telephony.CellSignalStrengthLte")
        // Oplus 15 JADX 确认：CellSignalStrengthLte(int rssi, int rsrp, int rsrq, int rssnr,
        //   int cqi, int timingAdvance)；rsrp 合法范围 -156..-31
        val signal = signalClass.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            Int::class.java, Int::class.java
        ).newInstance(
            Int.MAX_VALUE,
            sanitizeInt(c, "rsrp", -156, -31, -110),
            sanitizeInt(c, "rsrq", -43, 20, -15),
            sanitizeInt(c, "sinr", -23, 40, 20),
            Int.MAX_VALUE,
            sanitizeInt(c, "ta", 0, 65535, 0)
        )
        infoClass.getMethod("setCellSignalStrength", signalClass).invoke(info, signal)
        return info
    }

    private fun buildGsmCell(c: org.json.JSONObject): Any? {
        val infoClass = Class.forName("android.telephony.CellInfoGsm")
        val info = infoClass.getDeclaredConstructor().newInstance()

        val identityClass = Class.forName("android.telephony.CellIdentityGsm")
        val mcc = sanitizeInt(c, "mcc", 0, 999, 460)
        val mnc = sanitizeInt(c, "mnc", 0, 999, 0)
        // Oplus 15 JADX 确认：CellIdentityGsm(int lac, int cid, int arfcn, int bsic,
        //   String mccStr, String mncStr, String alphal, String alphas, Collection)
        // 字段范围：lac/cid 0..65535、arfcn 0..65535、bsic 0..63
        val identity = identityClass.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java,
            java.util.Collection::class.java
        ).newInstance(
            sanitizeInt(c, "lac", 0, 65535, 0),
            sanitizeInt(c, "cid", 0, 65535, 0),
            sanitizeInt(c, "arfcn", 0, 65535, 0),
            sanitizeInt(c, "bsic", 0, 63, 0),
            mcc.toString(),
            mnc.toString(),
            "", "",
            java.util.Collections.emptyList<Any>()
        )
        infoClass.getMethod("setCellIdentity", identityClass).invoke(info, identity)

        val signalClass = Class.forName("android.telephony.CellSignalStrengthGsm")
        // Oplus 15 JADX 确认：CellSignalStrengthGsm(int rssi, int ber, int ta)
        // rssi 合法范围 -113..-51（ASU 99 表示不可用）
        val signal = signalClass.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java
        ).newInstance(
            sanitizeInt(c, "rssi", -113, -51, -90),
            -1,
            sanitizeInt(c, "ta", 0, 63, -1)
        )
        infoClass.getMethod("setCellSignalStrength", signalClass).invoke(info, signal)
        return info
    }

    private fun buildNrCell(c: org.json.JSONObject): Any? {
        val infoClass = Class.forName("android.telephony.CellInfoNr")
        val identityClass = Class.forName("android.telephony.CellIdentityNr")
        val signalClass = Class.forName("android.telephony.CellSignalStrengthNr")
        val mcc = sanitizeInt(c, "mcc", 0, 999, 460)
        val mnc = sanitizeInt(c, "mnc", 0, 999, 0)
        // Oplus 15 JADX 确认：CellIdentityNr(int pci, int tac, int nrArfcn, int[] bands,
        //   String mccStr, String mncStr, long nci, String alphal, String alphas, Collection)
        // 字段范围：pci 0..1007、tac 0..16777215、nrArfcn 0..3279165、nci 0..68719476735（36bit）
        val pci = sanitizeInt(c, "pci", 0, 1007, 0)
        val tac = sanitizeInt(c, "tac", 0, 16777215, 0)
        val nrArfcn = if (c.has("nrArfcn")) sanitizeInt(c, "nrArfcn", 0, 3279165, 0)
            else sanitizeInt(c, "arfcn", 0, 3279165, 0)
        val nci = sanitizeNrNci(c)
        val identity = identityClass.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java, IntArray::class.java,
            String::class.java, String::class.java, Long::class.java,
            String::class.java, String::class.java, java.util.Collection::class.java
        ).newInstance(
            pci,
            tac,
            nrArfcn,
            intArrayOf(),
            mcc.toString(),
            String.format("%02d", mnc),
            nci,
            "", "",
            java.util.Collections.emptyList<Any>()
        )
        // Oplus 15 CellInfoNr 只有 setCellIdentity、无 setCellSignalStrength：
        // 必须用 5 参公开构造 CellInfoNr(int connectionStatus, boolean registered,
        //   long timeStamp, CellIdentityNr, CellSignalStrengthNr) 附带信号。
        val signal = buildNrSignal(c)
        val info = try {
            infoClass.getConstructor(
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                identityClass,
                signalClass
            ).newInstance(0, true, System.nanoTime(), identity, signal)
        } catch (t: Throwable) {
            // 老 ROM 回退：no-arg + setCellIdentity（信号保留默认不可用）
            ZLog.w(TAG_SCOPE, "CellInfoNr 5-arg ctor not found, fallback no-arg", t)
            infoClass.getDeclaredConstructor().newInstance().also {
                infoClass.getMethod("setCellIdentity", identityClass).invoke(it, identity)
            }
        }
        return info
    }

    /** NR 信号强度：Oplus 15 JADX 确认 6 参公开构造 (csiRsrp, csiRsrq, csiSinr, ssRsrp, ssRsrq, ssSinr)。 */
    private fun buildNrSignal(c: org.json.JSONObject): Any {
        val signalClass = Class.forName("android.telephony.CellSignalStrengthNr")
        // ssRsrp 合法范围 -156..-31；csi 参数用 Integer.MAX_VALUE（不可用）
        return signalClass.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            Int::class.java, Int::class.java
        ).newInstance(
            Int.MAX_VALUE,
            Int.MAX_VALUE,
            Int.MAX_VALUE,
            sanitizeInt(c, "rsrp", -156, -31, -105),
            sanitizeInt(c, "rsrq", -43, 20, -15),
            sanitizeInt(c, "sinr", -23, 40, 20)
        )
    }

    private fun buildWcdmaCell(c: org.json.JSONObject): Any? {
        val infoClass = Class.forName("android.telephony.CellInfoWcdma")
        val info = infoClass.getDeclaredConstructor().newInstance()

        val identityClass = Class.forName("android.telephony.CellIdentityWcdma")
        val mcc = sanitizeInt(c, "mcc", 0, 999, 460)
        val mnc = sanitizeInt(c, "mnc", 0, 999, 0)
        // Oplus 15 JADX 确认：CellIdentityWcdma(int lac, int cid, int psc, int uarfcn,
        //   String mccStr, String mncStr, String alphal, String alphas, Collection, ClosedSubscriberGroupInfo)
        // 字段范围：lac 0..65535、cid 0..268435455、psc 0..511、uarfcn 0..16383
        val identity = identityClass.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java,
            java.util.Collection::class.java, Class.forName("android.telephony.ClosedSubscriberGroupInfo")
        ).newInstance(
            sanitizeInt(c, "lac", 0, 65535, 0),
            sanitizeInt(c, "cid", 0, 268435455, 0),
            sanitizeInt(c, "psc", 0, 511, 0),
            sanitizeInt(c, "uarfcn", 0, 16383, 0),
            mcc.toString(),
            mnc.toString(),
            "", "",
            java.util.Collections.emptyList<Any>(),
            null
        )
        infoClass.getMethod("setCellIdentity", identityClass).invoke(info, identity)

        val signalClass = Class.forName("android.telephony.CellSignalStrengthWcdma")
        // Oplus 15 JADX 确认：CellSignalStrengthWcdma(int rssi, int ber, int rscp, int ecno)
        // rssi 合法范围 -113..-51、rscp -120..-25、ecno -24..0
        val signal = signalClass.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java, Int::class.java
        ).newInstance(
            sanitizeInt(c, "rssi", -113, -51, -90),
            -1,
            sanitizeInt(c, "rscp", -120, -25, -95),
            sanitizeInt(c, "ecno", -24, 0, -9)
        )
        infoClass.getMethod("setCellSignalStrength", signalClass).invoke(info, signal)
        return info
    }

    // ---------- 数值消毒 ----------

    private const val MAX_NCI = 68719476735L // 2^36-1，CellIdentityNr.MAX_NCI

    /** 读配置 int 并夹到合法范围；缺失/非法时返回 [defaultValue]。 */
    private fun sanitizeInt(c: org.json.JSONObject, key: String, min: Int, max: Int, defaultValue: Int = min): Int {
        if (!c.has(key)) return defaultValue
        val v = c.optInt(key, defaultValue)
        return if (v in min..max) v else defaultValue
    }

    /** NR NCI：0..MAX_NCI 合法；配置缺失/越界/哨兵值（Long.MAX_VALUE）时派生确定性合法值。 */
    private fun sanitizeNrNci(c: org.json.JSONObject): Long {
        val raw = c.optLong("nci", -1L)
        if (raw in 1..MAX_NCI) return raw
        // 派生：mcc 10bit<<26 | mnc 10bit<<16 | tac 8bit<<8 | pci 8bit，恒在 36bit 内
        val mcc = c.optInt("mcc", 460).toLong() and 0x3FF
        val mnc = c.optInt("mnc", 0).toLong() and 0x3FF
        val tac = c.optInt("tac", 0).toLong() and 0xFF
        val pci = c.optInt("pci", 0).toLong() and 0xFF
        val derived = (mcc shl 26) or (mnc shl 16) or (tac shl 8) or pci
        return if (derived in 1..MAX_NCI) derived else 1L
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
