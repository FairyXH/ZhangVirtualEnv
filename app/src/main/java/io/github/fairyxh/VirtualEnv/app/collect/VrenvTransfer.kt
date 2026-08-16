package io.github.fairyxh.VirtualEnv.app.collect

import org.json.JSONArray
import org.json.JSONObject

/**
 * 已保存采集（collect 快照）与 .vrenv.json（virtualregion-environment）双向转换。
 *
 * vrenv 文件格式（与 VirtualRegion 控制端导出完全兼容）：
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
 */
object VrenvTransfer {

    const val FORMAT = "virtualregion-environment"
    const val SCHEMA_VERSION = 1

    /** 蓝牙配对状态常量（android.bluetooth.BluetoothDevice）。 */
    private const val BOND_NONE = 10
    private const val BOND_BONDED = 12

    /** 从 vrenv 文件解析 environment 对象；不是合法 vrenv 时返回 null。 */
    fun parseEnvironment(text: String): JSONObject? {
        return try {
            val root = JSONObject(text)
            if (root.optString("format", "") != FORMAT) return null
            val env = root.optJSONObject("environment") ?: return null
            if (env.length() == 0) return null
            env
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
            put("name", name)
            put("sourceEnvironmentId", id)
            put("location", collectLocationToVrenv(data.optJSONObject("location")))
            put("wifi", collectWifiToVrenv(data.optJSONObject("wifi"), ts))
            put("cells", collectCellsToVrenv(data.optJSONObject("cell"), ts))
            put("bluetooth", collectBluetoothToVrenv(data.optJSONObject("bluetooth"), ts))
        }
        return env
    }

    private fun collectLocationToVrenv(loc: JSONObject?): JSONObject {
        val out = JSONObject()
        if (loc == null) return out
        var lat = loc.optDouble("latitude", Double.NaN)
        var lon = loc.optDouble("longitude", Double.NaN)
        var accuracy = loc.optDouble("accuracy", 0.0)
        var speed = loc.optDouble("speed", 0.0)
        var provider = "network"
        // 老版本只有 provider 键对象（gps/network/fused），取时间最新一条
        var bestTime = Long.MIN_VALUE
        loc.keys().forEach { k ->
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
        if (lat.isNaN() || lon.isNaN()) return out
        out.put("latitude", lat)
        out.put("longitude", lon)
        out.put("altitude", 0.0)
        out.put("accuracy", accuracy)
        out.put("speed", speed)
        out.put("bearing", 0.0)
        out.put("provider", provider)
        out.put("coordinateSystem", "WGS84")
        return out
    }

    private fun collectWifiToVrenv(wifi: JSONObject?, ts: Long): JSONArray {
        val out = JSONArray()
        val networks = wifi?.optJSONArray("networks") ?: return out
        val connected = wifi.optJSONObject("connected")
        val connectedBssid = connected?.optString("bssid", "")
        for (i in 0 until networks.length()) {
            val n = networks.optJSONObject(i) ?: continue
            val bssid = n.optString("bssid", "")
            out.put(JSONObject().apply {
                put("ssid", n.optString("ssid", ""))
                put("bssid", bssid)
                put("frequencyMhz", n.optInt("frequency", 2412))
                put("signalDbm", n.optInt("rssi", -70))
                put("connected", bssid.isNotBlank() && bssid == connectedBssid)
                put("channel", frequencyToChannel(n.optInt("frequency", 2412)))
                put("capabilities", JSONObject.NULL)
                put("encryptionType", JSONObject.NULL)
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
            out.put(JSONObject().apply {
                put("radioType", c.optString("type", "LTE"))
                put("registered", c.optBoolean("registered", true))
                put("observedAt", ts)
                put("mcc", c.optInt("mcc", -1))
                put("mnc", c.optInt("mnc", -1))
                if (c.has("lac")) put("lac", c.optInt("lac", -1)) else put("lac", JSONObject.NULL)
                if (c.has("tac")) put("tac", c.optInt("tac", -1)) else put("tac", JSONObject.NULL)
                val ci = c.optLong("ci", -1L)
                if (ci > 0) put("cid", ci) else put("cid", JSONObject.NULL)
                val nci = c.optLong("nci", -1L)
                if (nci > 0) put("nci", nci) else put("nci", JSONObject.NULL)
                if (c.has("pci")) put("pci", c.optInt("pci", -1)) else put("pci", JSONObject.NULL)
                put("psc", JSONObject.NULL)
                put("arfcn", JSONObject.NULL)
                if (c.has("earfcn")) put("earfcn", c.optInt("earfcn", -1)) else put("earfcn", JSONObject.NULL)
                if (c.has("nrArfcn")) put("nrarfcn", c.optInt("nrArfcn", -1)) else put("nrarfcn", JSONObject.NULL)
                val signal = if (c.has("rsrp")) c.optInt("rsrp", -1) else c.optInt("rssi", -1)
                if (signal != -1) put("signalDbm", signal) else put("signalDbm", JSONObject.NULL)
                put("asuLevel", JSONObject.NULL)
                if (c.has("latitude")) put("latitude", c.optDouble("latitude", 0.0)) else put("latitude", JSONObject.NULL)
                if (c.has("longitude")) put("longitude", c.optDouble("longitude", 0.0)) else put("longitude", JSONObject.NULL)
            })
        }
        return out
    }

    private fun collectBluetoothToVrenv(bt: JSONObject?, ts: Long): JSONArray {
        val out = JSONArray()
        if (bt == null) return out
        fun append(d: JSONObject, bondState: Int) {
            val address = d.optString("address", "")
            if (address.isBlank()) return
            out.put(JSONObject().apply {
                put("name", d.optString("name", ""))
                put("address", address)
                put("type", d.optInt("type", 0))
                put("bondState", bondState)
                if (d.has("rssi")) put("rssi", d.optInt("rssi", -70)) else put("rssi", JSONObject.NULL)
                val uuids = d.optJSONArray("serviceUuids")
                if (uuids != null && uuids.length() > 0) {
                    val sb = StringBuilder()
                    for (i in 0 until uuids.length()) {
                        if (i > 0) sb.append(",")
                        sb.append(uuids.optString(i, ""))
                    }
                    put("uuids", sb.toString())
                } else {
                    put("uuids", JSONObject.NULL)
                }
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

    /** 频率 MHz → 信道（2.4G/5G 常用映射，未知返回 null）。 */
    private fun frequencyToChannel(freq: Int): Int? {
        return when {
            freq in 2412..2484 -> (freq - 2412) / 5 + 1
            freq in 5180..5825 -> (freq - 5180) / 5 + 36
            else -> null
        }
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
                val radioType = c.optString("radioType", "LTE").uppercase()
                val n = JSONObject().apply {
                    put("type", radioType)
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
                    if (b.has("rssi")) put("rssi", b.optInt("rssi", -70))
                    b.optString("uuids", "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.let { list ->
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
