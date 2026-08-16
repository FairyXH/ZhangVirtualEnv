package io.github.fairyxh.VirtualEnv.app.collect

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * 已保存采集（collect 快照）与 .vrenv.json（virtualregion-environment）双向转换。
 *
 * vrenv 文件格式（与 VirtualRegion 0.1.6 控制端导入导出完全兼容）：
 * ```
 * {
 *   "format": "virtualregion-environment",
 *   "schemaVersion": 1,
 *   "exportedAt": 1786855074127,
 *   "environment": {
 *     "name": "测试点",
 *     "sourceEnvironmentId": 7,
 *     "location": { "latitude", "longitude", "altitude", "accuracy", "speed", "bearing", "provider", "coordinateSystem" },
 *     "wifi": [ { ssid, bssid, frequencyMhz, signalDbm, connected, channel, capabilities, encryptionType, latitude, longitude, firstSeenAt, lastSeenAt } ],
 *     "cells": [ { radioType, registered, observedAt, mcc, mnc, lac, tac, cid, nci, pci, psc, arfcn, earfcn, nrarfcn, signalDbm, asuLevel, latitude, longitude } ],
 *     "bluetooth": [ { name, address, type, bondState, rssi, uuids, firstSeenAt, lastSeenAt } ]
 *   }
 * }
 * ```
 * 另支持批量格式：`format=virtualregion-environment-bundle` + `environments[]`。
 *
 * collect data 结构（EnvironmentCollector 输出 / env_snapshot.data）：
 * ```
 * {
 *   "timestamp": ...,
 *   "location": { "gps": {...}, "latitude": ..., "longitude": ..., ... },
 *   "cell": { "cells": [ {type, mcc, mnc, tac, ci, eci, cellId, pci, earfcn, rsrp, ...} ] },
 *   "wifi": { "enabled": ..., "connected": {...}, "networks": [ {ssid, bssid, rssi, frequency} ] },
 *   "bluetooth": { "enabled": ..., "bonded": [ {name, address, type} ], "devices": [ {address, rssi, txPower, name, raw, manufacturerData, serviceUuids} ] },
 *   "gnss": { ... },
 *   "sensor": { ... }
 * }
 * ```
 *
 * 导出兼容约束（对照 VirtualRegion 0.1.6 `E3.e.g()` / `com.amap.api.col.3sl.l0`）：
 * - BSSID / 蓝牙地址必须 `xx:xx:xx:xx:xx:xx`（hex）且同一板块内不允许重复
 * - SSID 按 UTF-8 字节 ≤32；蓝牙名 ≤248；capabilities ≤256；encryptionType ≤64
 * - cell 数值必须位于合法区间（MCC/MNC 0..999、LAC 0..65535、TAC 0..16777215、CID 0..268435455、
 *   NCI 0..68719476735、PCI 0..1008、PSC 0..511、ARFCN/EARFCN/NRARFCN 0..3279165、RSSI -200..100、ASU 0..255），
 *   非法/缺失一律写 null（不能写 -1）
 * - radioType 必须是 GSM/CDMA/WCDMA/TDSCDMA/LTE/NR/UNKNOWN 之一
 * - UUID 列表每个必须能 `UUID.fromString`，数量 ≤64、总长 ≤4096
 * - location 必须完整（缺坐标时导出 0,0 + WGS84 兜底）
 */
object VrenvTransfer {

    const val FORMAT = "virtualregion-environment"
    const val FORMAT_BUNDLE = "virtualregion-environment-bundle"
    const val SCHEMA_VERSION = 1

    /** 蓝牙配对状态常量（android.bluetooth.BluetoothDevice）。 */
    private const val BOND_NONE = 10
    private const val BOND_BONDED = 12

    private val MAC_PATTERN = Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")
    private val VALID_RADIO_TYPES = setOf("GSM", "CDMA", "WCDMA", "TDSCDMA", "LTE", "NR", "UNKNOWN")

    /** 从 vrenv 文件解析 environment 对象；支持单环境与 bundle（bundle 取第一个）。不是合法 vrenv 时返回 null。 */
    fun parseEnvironment(text: String): JSONObject? {
        return try {
            val root = JSONObject(text)
            if (root.optString("format", "") == FORMAT) {
                val env = root.optJSONObject("environment") ?: return null
                if (env.length() == 0) return null
                env
            } else if (root.optString("format", "") == FORMAT_BUNDLE) {
                val arr = root.optJSONArray("environments") ?: return null
                if (arr.length() == 0) return null
                arr.optJSONObject(0) ?: return null
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 构造完整 vrenv 文件（导出用）。 */
    fun buildVrenvFile(environment: JSONObject): JSONObject {
        return JSONObject().apply {
            put("format", FORMAT)
            put("schemaVersion", SCHEMA_VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("environment", environment)
        }
    }

    // ---------- collect → vrenv ----------

    /** 把一条 collect 快照（含 id/name/data）转成 vrenv environment 对象。 */
    fun collectToVrenvEnvironment(snapshot: JSONObject): JSONObject {
        val data = snapshot.optJSONObject("data") ?: JSONObject()
        val name = snapshot.optString("name", "")
        val id = snapshot.optLong("id", -1L)
        val ts = data.optLong("timestamp", snapshot.optLong("createTime", System.currentTimeMillis()))

        val env = JSONObject().apply {
            put("name", name.take(100))
            if (id >= 0) put("sourceEnvironmentId", id)
            put("location", collectLocationToVrenv(data.optJSONObject("location")))
            put("wifi", collectWifiToVrenv(data.optJSONObject("wifi"), ts))
            put("cells", collectCellsToVrenv(data.optJSONObject("cell"), ts))
            put("bluetooth", collectBluetoothToVrenv(data.optJSONObject("bluetooth"), ts))
        }
        return env
    }

    private fun collectLocationToVrenv(loc: JSONObject?): JSONObject {
        val out = JSONObject()
        var lat = loc?.optDouble("latitude", Double.NaN) ?: Double.NaN
        var lon = loc?.optDouble("longitude", Double.NaN) ?: Double.NaN
        var accuracy = loc?.optDouble("accuracy", 0.0) ?: 0.0
        var speed = loc?.optDouble("speed", 0.0) ?: 0.0
        var provider = "network"
        // 老版本只有 provider 键对象（gps/network/fused），取时间最新一条
        var bestTime = Long.MIN_VALUE
        loc?.keys()?.forEach { k ->
            val item = loc.optJSONObject(k) ?: return@forEach
            val t = item.optLong("time", Long.MIN_VALUE)
            if (t >= bestTime) {
                bestTime = t
                lat = item.optDouble("latitude", Double.NaN)
                lon = item.optDouble("longitude", Double.NaN)
                accuracy = item.optDouble("accuracy", 0.0)
                speed = item.optDouble("speed", 0.0)
                provider = k
            }
        }
        // 对方要求 location 完整；缺坐标时以 0,0 + WGS84 兜底保证文件可导入
        if (lat.isNaN() || lon.isNaN()) {
            lat = 0.0
            lon = 0.0
        }
        if (provider.isBlank()) provider = "network"
        out.put("latitude", lat)
        out.put("longitude", lon)
        out.put("altitude", 0.0)
        out.put("accuracy", accuracy.coerceIn(0.0, 1000000.0))
        out.put("speed", speed.coerceIn(0.0, 20000.0))
        out.put("bearing", 0.0)
        out.put("provider", provider.take(32))
        out.put("coordinateSystem", "WGS84")
        return out
    }

    private fun collectWifiToVrenv(wifi: JSONObject?, ts: Long): JSONArray {
        val out = JSONArray()
        val networks = wifi?.optJSONArray("networks") ?: return out
        val connected = wifi.optJSONObject("connected")
        val connectedBssid = connected?.optString("bssid", "")
        val seen = HashSet<String>()
        for (i in 0 until networks.length()) {
            val n = networks.optJSONObject(i) ?: continue
            val bssid = n.optString("bssid", "").uppercase(Locale.ROOT)
            // 对方要求 BSSID 为 MAC 格式且不重复；非法/重复跳过
            if (!MAC_PATTERN.matches(bssid) || !seen.add(bssid)) continue
            val freq = n.optInt("frequency", 2412).coerceIn(0, 100000)
            val rssi = n.optInt("rssi", -70).coerceIn(-200, 100)
            out.put(JSONObject().apply {
                put("ssid", truncateUtf8Bytes(n.optString("ssid", ""), 32))
                put("bssid", bssid)
                put("frequencyMhz", freq)
                put("signalDbm", rssi)
                put("connected", bssid == connectedBssid?.uppercase(Locale.ROOT))
                frequencyToChannel(freq)?.let { put("channel", it) } ?: put("channel", JSONObject.NULL)
                // 对方 virtual_wifi.capabilities / encryption_type 为 TEXT NOT NULL：
                // 空字符串可写入，JSONObject.NULL 会触发 NOT NULL constraint failed
                put("capabilities", "")
                put("encryptionType", "")
                put("latitude", JSONObject.NULL)
                put("longitude", JSONObject.NULL)
                put("firstSeenAt", ts)
                put("lastSeenAt", ts)
            })
        }
        return out
    }

    private fun collectCellsToVrenv(cell: JSONObject?, ts: Long): JSONArray {
        val out = JSONArray()
        val cells = cell?.optJSONArray("cells") ?: return out
        for (i in 0 until cells.length()) {
            val c = cells.optJSONObject(i) ?: continue
            val radioType = sanitizeRadioType(c.optString("type", "LTE"))
            out.put(JSONObject().apply {
                put("radioType", radioType)
                put("registered", c.optBoolean("registered", true))
                put("observedAt", ts.coerceAtLeast(0L))
                putOptInt(this, "mcc", c, 0, 999)
                putOptInt(this, "mnc", c, 0, 999)
                putOptInt(this, "lac", c, 0, 65535)
                putOptInt(this, "tac", c, 0, 16777215)
                val ci = c.optLong("ci", -1L)
                if (ci in 0..268435455L) put("cid", ci) else put("cid", JSONObject.NULL)
                val nci = c.optLong("nci", -1L)
                if (nci in 0..68719476735L) put("nci", nci) else put("nci", JSONObject.NULL)
                putOptInt(this, "pci", c, 0, 1008)
                putOptInt(this, "psc", c, 0, 511)
                putOptInt(this, "arfcn", c, 0, 3279165)
                putOptInt(this, "earfcn", c, 0, 3279165)
                putOptInt(this, "nrArfcn", c, 0, 3279165, "nrarfcn")
                putOptInt(this, "rsrp", c, -200, 100, "signalDbm", "rssi")
                putOptInt(this, "asuLevel", c, 0, 255)
                // 经纬度必须成对
                val lat = c.optDouble("latitude", Double.NaN)
                val lon = c.optDouble("longitude", Double.NaN)
                if (!lat.isNaN() && !lon.isNaN() && lat in -90.0..90.0 && lon in -180.0..180.0) {
                    put("latitude", lat)
                    put("longitude", lon)
                } else {
                    put("latitude", JSONObject.NULL)
                    put("longitude", JSONObject.NULL)
                }
            })
        }
        return out
    }

    private fun collectBluetoothToVrenv(bt: JSONObject?, ts: Long): JSONArray {
        val out = JSONArray()
        if (bt == null) return out
        val seen = HashSet<String>()
        fun append(d: JSONObject, bondState: Int) {
            val address = d.optString("address", "").uppercase(Locale.ROOT)
            // 对方要求地址 MAC 格式且不重复；bonded/devices 合并去重
            if (!MAC_PATTERN.matches(address) || !seen.add(address)) return
            val type = d.optInt("type", 0).coerceIn(0, 3)
            val state = if (bondState == BOND_BONDED) BOND_BONDED else BOND_NONE
            out.put(JSONObject().apply {
                put("name", d.optString("name", "").take(248))
                put("address", address)
                put("type", type)
                put("bondState", state)
                if (d.has("rssi")) {
                    put("rssi", d.optInt("rssi", -70).coerceIn(-200, 100))
                } else {
                    put("rssi", JSONObject.NULL)
                }
                val uuids = sanitizeUuids(d.optJSONArray("serviceUuids"))
                if (uuids != null) put("uuids", uuids) else put("uuids", JSONObject.NULL)
                put("firstSeenAt", ts)
                put("lastSeenAt", ts)
            })
        }
        bt.optJSONArray("bonded")?.let { arr ->
            for (i in 0 until arr.length()) append(arr.optJSONObject(i) ?: continue, BOND_BONDED)
        }
        bt.optJSONArray("devices")?.let { arr ->
            for (i in 0 until arr.length()) append(arr.optJSONObject(i) ?: continue, BOND_NONE)
        }
        return out
    }

    /** 按 UTF-8 字节长度截断（对方 SSID 校验 getBytes().length ≤32）。 */
    private fun truncateUtf8Bytes(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        var end = value.length
        while (end > 0 && value.substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) end--
        return value.substring(0, end)
    }

    /** 频率 MHz → 信道（2.4G/5G 常用映射，未知返回 null）。 */
    private fun frequencyToChannel(freq: Int): Int? {
        return when {
            freq in 2412..2484 -> (freq - 2412) / 5 + 1
            freq in 5180..5825 -> (freq - 5180) / 5 + 36
            else -> null
        }
    }

    /** radioType 归一为对方枚举（GSM/CDMA/WCDMA/TDSCDMA/LTE/NR/UNKNOWN）。 */
    private fun sanitizeRadioType(raw: String): String {
        val upper = raw.uppercase(Locale.ROOT)
        return if (upper in VALID_RADIO_TYPES) upper else "UNKNOWN"
    }

    /** 从 JSONObject 读 int（支持多个候选 key），合法区间内写入，否则写 null。 */
    private fun putOptInt(
        out: JSONObject,
        outKey: String,
        src: JSONObject,
        min: Int,
        max: Int,
        vararg keys: String
    ) {
        for (k in keys) {
            if (!src.has(k) || src.isNull(k)) continue
            val v = src.optInt(k, Int.MIN_VALUE)
            if (v in min..max) {
                out.put(outKey, v)
                return
            }
        }
        out.put(outKey, JSONObject.NULL)
    }

    /** 过滤为合法 UUID 列表：数量 ≤64、总长 ≤4096、非法项丢弃；空则 null。 */
    private fun sanitizeUuids(uuids: JSONArray?): String? {
        if (uuids == null || uuids.length() == 0) return null
        val valid = ArrayList<String>()
        for (i in 0 until uuids.length()) {
            val raw = uuids.optString(i, "").trim()
            if (raw.isBlank()) continue
            // "null" 字符串（JSONObject.NULL 误序列化）直接丢弃
            if (raw.equals("null", ignoreCase = true)) continue
            val ok = try {
                UUID.fromString(raw)
                true
            } catch (_: Throwable) {
                false
            }
            if (ok) valid.add(raw)
            if (valid.size >= 64) break
        }
        if (valid.isEmpty()) return null
        val joined = valid.joinToString(",")
        if (joined.length > 4096) return joined.take(4096)
        return joined
    }

    // ---------- vrenv → collect ----------

    /**
     * 把 vrenv environment 转成 collect 快照 data（与 EnvironmentCollector 结构一致），
     * 供 createEnvSnapshot(type=collect) 保存；缺少的板块用空对象占位。
     */
    fun vrenvToCollectData(environment: JSONObject, exportedAt: Long = System.currentTimeMillis()): JSONObject {
        return JSONObject().apply {
            put("timestamp", exportedAt)
            put("location", vrenvLocationToCollect(environment.optJSONObject("location"), exportedAt))
            put("cell", JSONObject().apply {
                put("cells", vrenvCellsToCollect(environment.optJSONArray("cells")))
            })
            put("wifi", vrenvWifiToCollect(environment.optJSONArray("wifi")))
            put("bluetooth", vrenvBluetoothToCollect(environment.optJSONArray("bluetooth")))
        }
    }

    private fun vrenvLocationToCollect(loc: JSONObject?, exportedAt: Long): JSONObject {
        val out = JSONObject()
        if (loc == null) return out
        val lat = loc.optDouble("latitude", Double.NaN)
        val lon = loc.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return out
        val provider = loc.optString("provider", "network").ifBlank { "network" }
        val item = JSONObject().apply {
            put("latitude", lat)
            put("longitude", lon)
            put("accuracy", loc.optDouble("accuracy", 0.0))
            put("speed", loc.optDouble("speed", 0.0))
            put("time", exportedAt)
        }
        out.put(provider, item)
        out.put("latitude", lat)
        out.put("longitude", lon)
        out.put("accuracy", loc.optDouble("accuracy", 0.0))
        out.put("speed", loc.optDouble("speed", 0.0))
        out.put("time", exportedAt)
        return out
    }

    private fun vrenvWifiToCollect(wifi: JSONArray?): JSONObject {
        val out = JSONObject()
        val networks = JSONArray()
        var connected: JSONObject? = null
        wifi?.let { arr ->
            for (i in 0 until arr.length()) {
                val w = arr.optJSONObject(i) ?: continue
                val n = JSONObject().apply {
                    put("ssid", w.optString("ssid", ""))
                    put("bssid", w.optString("bssid", ""))
                    put("rssi", w.optInt("signalDbm", -70))
                    put("frequency", w.optInt("frequencyMhz", 2412))
                }
                networks.put(n)
                if (connected == null && w.optBoolean("connected", false)) {
                    connected = n
                }
            }
        }
        out.put("networks", networks)
        connected?.let { out.put("connected", it) }
        return out
    }

    private fun vrenvCellsToCollect(cells: JSONArray?): JSONArray {
        val out = JSONArray()
        cells?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val radioType = sanitizeRadioType(c.optString("radioType", "LTE"))
                val n = JSONObject().apply {
                    // 内部统一为我们的 type；UNKNOWN 映射 OTHER 以匹配 EnvironmentCollector 输出
                    put("type", if (radioType == "UNKNOWN") "OTHER" else radioType)
                    if (!c.isNull("mcc")) put("mcc", c.optInt("mcc", -1))
                    if (!c.isNull("mnc")) put("mnc", c.optInt("mnc", -1))
                    if (!c.isNull("tac")) put("tac", c.optInt("tac", -1))
                    if (!c.isNull("lac")) put("lac", c.optInt("lac", -1))
                    val cid = c.optLong("cid", -1L)
                    if (cid > 0) put("ci", cid)
                    val nci = c.optLong("nci", -1L)
                    if (nci > 0) put("nci", nci)
                    if (!c.isNull("pci")) put("pci", c.optInt("pci", -1))
                    if (!c.isNull("earfcn")) put("earfcn", c.optInt("earfcn", -1))
                    if (!c.isNull("nrarfcn")) put("nrArfcn", c.optInt("nrarfcn", -1))
                }
                out.put(n)
            }
        }
        return out
    }

    private fun vrenvBluetoothToCollect(bt: JSONArray?): JSONObject {
        val out = JSONObject()
        val bonded = JSONArray()
        val devices = JSONArray()
        bt?.let { arr ->
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                val address = b.optString("address", "")
                if (address.isBlank()) continue
                val n = JSONObject().apply {
                    put("name", b.optString("name", ""))
                    put("address", address)
                    put("type", b.optInt("type", 0))
                    if (b.has("rssi") && !b.isNull("rssi")) put("rssi", b.optInt("rssi", -70))
                    if (!b.isNull("uuids")) {
                        val list = b.optString("uuids", "").split(',').map { it.trim() }
                            .filter { it.isNotEmpty() && it != "null" }
                        if (list.isNotEmpty()) {
                            put("serviceUuids", JSONArray().apply { list.forEach { put(it) } })
                        }
                    }
                }
                if (b.optInt("bondState", BOND_NONE) == BOND_BONDED) bonded.put(n) else devices.put(n)
            }
        }
        out.put("bonded", bonded)
        out.put("devices", devices)
        return out
    }
}
