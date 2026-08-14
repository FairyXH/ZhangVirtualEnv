package io.github.fairyxh.VirtualEnv.util

import org.json.JSONObject

/**
 * CellIdentity 字段统一读取（Oplus/Android 15 兼容）。
 *
 * 真机 framework.jar JADX 确认（见 docs/reverse/hook-observe-and-collect-fixes.md）：
 * CellIdentityLte.getMcc()/getMnc() 已废弃，且在 mMccStr 为 null / 空 / 非数字时返回
 * Integer.MAX_VALUE 或抛 NumberFormatException —— 这是采集结果 mcc/mnc 恒为 -1 的根因。
 * 必须先读 getMccString()/getMncString() 并安全解析，再回退旧 int getter。
 */
object CellInfoRead {

    private const val UNAVAILABLE = -1

    /** 读取 MCC（字符串 getter 优先，哨兵归一为 -1）。 */
    fun mcc(identity: Any): Int = readIdentityInt(identity, "mcc")

    /** 读取 MNC（字符串 getter 优先，哨兵归一为 -1）。 */
    fun mnc(identity: Any): Int = readIdentityInt(identity, "mnc")

    private fun readIdentityInt(identity: Any, field: String): Int {
        val capitalized = field.replaceFirstChar { it.uppercaseChar() }
        try {
            val m = identity.javaClass.getMethod("get${capitalized}String")
            val s = m.invoke(identity) as? String
            if (!s.isNullOrBlank()) {
                s.toIntOrNull()?.let { return it }
            }
        } catch (_: Throwable) {
        }
        try {
            val m = identity.javaClass.getMethod("get$capitalized")
            val v = m.invoke(identity) as? Int ?: return UNAVAILABLE
            return if (v == Int.MAX_VALUE || v == -1) UNAVAILABLE else v
        } catch (_: Throwable) {
        }
        return UNAVAILABLE
    }

    /** 反射提取 CellInfo 的 cellIdentity。 */
    fun identity(info: Any): Any? = try {
        info.javaClass.getMethod("getCellIdentity").invoke(info)
    } catch (_: Throwable) {
        null
    }

    /** 反射读取 CellIdentity 的 int getter，哨兵（MAX_VALUE / -1）归一为 -1。 */
    fun intField(identity: Any, getter: String): Int = try {
        val v = identity.javaClass.getMethod(getter).invoke(identity) as? Int ?: UNAVAILABLE
        if (v == Int.MAX_VALUE || v == -1) UNAVAILABLE else v
    } catch (_: Throwable) {
        UNAVAILABLE
    }

    /** 反射读取 CellIdentity 的 long getter，哨兵归一为 -1。 */
    fun longField(identity: Any, getter: String): Long = try {
        val v = identity.javaClass.getMethod(getter).invoke(identity) as? Long ?: -1L
        if (v == Long.MAX_VALUE || v == Int.MAX_VALUE.toLong() || v == -1L) -1L else v
    } catch (_: Throwable) {
        -1L
    }

    /** 按类名识别小区类型。 */
    fun typeOf(identity: Any): String = when (identity.javaClass.name) {
        "android.telephony.CellIdentityLte" -> "LTE"
        "android.telephony.CellIdentityNr" -> "NR"
        "android.telephony.CellIdentityGsm" -> "GSM"
        "android.telephony.CellIdentityWcdma" -> "WCDMA"
        "android.telephony.CellIdentityCdma" -> "CDMA"
        else -> "OTHER"
    }

    /** 提取单个 CellInfo 的 JSON 摘要（Hook 层观测与采集端共用）。 */
    fun cellToJson(info: Any): JSONObject {
        val out = JSONObject()
        val id = identity(info) ?: return out
        val type = typeOf(id)
        out.put("type", type)
        out.put("registered", try {
            info.javaClass.getMethod("isRegistered").invoke(info) as? Boolean ?: false
        } catch (_: Throwable) {
            false
        })
        when (type) {
            "LTE" -> {
                out.put("mcc", mcc(id))
                out.put("mnc", mnc(id))
                out.put("tac", intField(id, "getTac"))
                out.put("ci", intField(id, "getCi").toLong())
                out.put("pci", intField(id, "getPci"))
            }
            "NR" -> {
                out.put("mcc", mcc(id))
                out.put("mnc", mnc(id))
                out.put("tac", intField(id, "getTac"))
                out.put("nci", longField(id, "getNci"))
                out.put("pci", intField(id, "getPci"))
            }
            "GSM" -> {
                out.put("mcc", mcc(id))
                out.put("mnc", mnc(id))
                out.put("lac", intField(id, "getLac"))
                out.put("cid", intField(id, "getCid"))
            }
            "WCDMA" -> {
                out.put("mcc", mcc(id))
                out.put("mnc", mnc(id))
                out.put("lac", intField(id, "getLac"))
                out.put("cid", intField(id, "getCid"))
            }
            "CDMA" -> {
                out.put("latitude", try {
                    (id.javaClass.getMethod("getLatitude").invoke(id) as? Double) ?: 0.0
                } catch (_: Throwable) {
                    0.0
                })
                out.put("longitude", try {
                    (id.javaClass.getMethod("getLongitude").invoke(id) as? Double) ?: 0.0
                } catch (_: Throwable) {
                    0.0
                })
            }
        }
        return out
    }
}
