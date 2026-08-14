package io.github.fairyxh.VirtualEnv.app.cell

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OpenCellID Radio 类型（与 OpenCellID API 文档一致）。
 */
enum class RadioType {
    GSM,
    UMTS,
    LTE,
    NBIOT,
    NR,
    CDMA;

    /** 项目内部 cell entry 类型（VirtualCellFactory 支持 LTE/GSM/NR/WCDMA）。 */
    fun entryType(): String = when (this) {
        UMTS -> "WCDMA"
        NBIOT -> "LTE"
        CDMA -> "CDMA"
        else -> name
    }

    companion object {
        fun fromString(value: String?): RadioType? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * OpenCellID Cell 数据模型。
 *
 * 与 OpenCellID API 解耦：JSON DTO 由 [fromJson] 转换，上层业务（查询/保存/展示）
 * 只依赖本模型，替换数据源不影响业务层。
 */
data class CellInfo(
    val radio: String?,
    val mcc: Int?,
    val mnc: Int?,
    val lac: Int?,
    val tac: Int?,
    val cellId: Long?,
    val latitude: Double,
    val longitude: Double,
    val averageSignalStrength: Int?,
    val rangeMeters: Int?,
    val samples: Int?,
    val changeable: Boolean?,
    val rnc: Int?,
    val cid: Long?,
    val pci: Int?
) {

    /** 与目标点的大圆距离（米）。 */
    fun distanceMeters(otherLat: Double, otherLon: Double): Double {
        return haversineMeters(latitude, longitude, otherLat, otherLon)
    }

    /** 小区身份键（跨分片去重用）。 */
    fun dedupeKey(): String {
        return listOf(
            radio, mcc, mnc, lac, tac, cellId ?: cid, pci
        ).joinToString("|")
    }

    /** 显示用摘要：radio + MCC/MNC/LAC/TAC + CID。 */
    fun summary(): String {
        val radioStr = radio ?: "?"
        val idStr = when (radioStr.uppercase()) {
            "LTE" -> "TAC=${tac ?: lac ?: -1} CI=${cellId ?: cid ?: -1}"
            "NR" -> "TAC=${tac ?: lac ?: -1} NCI=${cellId ?: cid ?: -1}"
            "GSM" -> "LAC=${lac ?: tac ?: -1} CID=${cellId ?: cid ?: -1}"
            "UMTS" -> "LAC=${lac ?: tac ?: -1} CID=${cellId ?: cid ?: -1}"
            else -> "LAC=${lac ?: tac ?: -1} CID=${cellId ?: cid ?: -1}"
        }
        return "$radioStr  MCC=${mcc ?: -1} MNC=${mnc ?: -1} $idStr"
    }

    companion object {
        /** OpenCellID `cell/getInArea` 响应中单个 cell 的 DTO 转换。 */
        fun fromJson(obj: JSONObject): CellInfo {
            val cellId = if (obj.has("cellid")) {
                obj.optLong("cellid", -1L).takeIf { it >= 0 }
            } else {
                null
            }
            val cid = if (obj.has("cid")) {
                obj.optLong("cid", -1L).takeIf { it >= 0 }
            } else {
                null
            }
            return CellInfo(
                radio = obj.optString("radio", "").ifEmpty { null },
                mcc = obj.optInt("mcc", -1).takeIf { it >= 0 },
                mnc = obj.optInt("mnc", -1).takeIf { it >= 0 },
                lac = obj.optInt("lac", -1).takeIf { it >= 0 },
                tac = obj.optInt("tac", -1).takeIf { it >= 0 },
                cellId = cellId,
                latitude = obj.optDouble("lat", 0.0),
                longitude = obj.optDouble("lon", 0.0),
                averageSignalStrength = if (obj.has("averageSignalStrength")) {
                    obj.optInt("averageSignalStrength", Int.MIN_VALUE)
                        .takeIf { it != Int.MIN_VALUE }
                } else {
                    null
                },
                rangeMeters = obj.optInt("range", -1).takeIf { it >= 0 },
                samples = obj.optInt("samples", -1).takeIf { it >= 0 },
                changeable = if (obj.has("changeable")) obj.optBoolean("changeable") else null,
                rnc = obj.optInt("rnc", -1).takeIf { it >= 0 },
                cid = cid,
                pci = obj.optInt("pci", -1).takeIf { it >= 0 }
            )
        }

        fun toJsonArray(cells: List<CellInfo>): JSONArray {
            val arr = JSONArray()
            cells.forEach { c ->
                arr.put(
                    JSONObject().apply {
                        put("radio", c.radio ?: "")
                        put("mcc", c.mcc ?: -1)
                        put("mnc", c.mnc ?: -1)
                        put("lac", c.lac ?: -1)
                        put("tac", c.tac ?: -1)
                        put("cellid", c.cellId ?: c.cid ?: -1L)
                        put("lat", c.latitude)
                        put("lon", c.longitude)
                        put("averageSignalStrength", c.averageSignalStrength ?: JSONObject.NULL)
                        put("range", c.rangeMeters ?: -1)
                        put("samples", c.samples ?: -1)
                        put("changeable", c.changeable ?: JSONObject.NULL)
                        put("rnc", c.rnc ?: -1)
                        put("cid", c.cid ?: -1L)
                        put("pci", c.pci ?: -1)
                    }
                )
            }
            return arr
        }
    }
}

/** 两经纬度点之间的大圆距离（米）。 */
fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}