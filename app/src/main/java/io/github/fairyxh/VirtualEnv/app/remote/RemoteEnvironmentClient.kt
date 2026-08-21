package io.github.fairyxh.VirtualEnv.app.remote

import android.content.Context
import android.content.SharedPreferences
import io.github.fairyxh.VirtualEnv.app.ApiClient
import io.github.fairyxh.VirtualEnv.util.ZLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/** Saved remote server definition. Token is stored in private app preferences. */
data class RemoteServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val token: String,
    val enabled: Boolean,
    val lastStatus: String,
)

data class RemoteDevice(
    val deviceId: String,
    val name: String,
    val deviceType: String,
    val capabilities: List<String>,
    val online: Boolean,
    val lastSeen: Long?,
    val lastData: Long?,
)

class RemoteServerRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("remote_environment", Context.MODE_PRIVATE)
    private val keyServers = "servers"

    fun list(): List<RemoteServerConfig> {
        val array = runCatching { JSONArray(prefs.getString(keyServers, "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { json ->
                RemoteServerConfig(
                    id = json.optString("id"),
                    name = json.optString("name"),
                    url = json.optString("url"),
                    token = json.optString("token"),
                    enabled = json.optBoolean("enabled", true),
                    lastStatus = json.optString("lastStatus", "未连接"),
                )
            }
        }
    }

    fun save(config: RemoteServerConfig) {
        val values = list().filterNot { it.id == config.id }.toMutableList()
        values += config
        val array = JSONArray()
        values.forEach { value ->
            array.put(JSONObject().apply {
                put("id", value.id)
                put("name", value.name)
                put("url", value.url)
                put("token", value.token)
                put("enabled", value.enabled)
                put("lastStatus", value.lastStatus)
            })
        }
        prefs.edit().putString(keyServers, array.toString()).apply()
    }

    fun delete(id: String) {
        list().filterNot { it.id == id }.let { values ->
            val array = JSONArray()
            values.forEach { value ->
                array.put(JSONObject().apply {
                    put("id", value.id); put("name", value.name); put("url", value.url)
                    put("token", value.token); put("enabled", value.enabled); put("lastStatus", value.lastStatus)
                })
            }
            prefs.edit().putString(keyServers, array.toString()).apply()
        }
    }
}

/** WebSocket consumer for one server. It owns transport only, not Hook state. */
class RemoteWebSocketClient(
    private val config: RemoteServerConfig,
    private val onAuth: (Boolean, String) -> Unit,
    private val onDevices: (List<RemoteDevice>) -> Unit,
    private val onData: (String, String, JSONObject) -> Unit,
    private val onState: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var reconnectAttempt = 0
    private var selectedDeviceId: String? = null
    private val selectedTypes = linkedSetOf<String>()

    fun connect() {
        closed = false
        val request = Request.Builder().url(normalizeUrl(config.url)).build()
        onState("连接中")
        socket = client.newWebSocket(request, Listener())
    }

    fun disconnect() {
        closed = true
        socket?.close(1000, "client disconnect")
        socket = null
        onState("未连接")
    }

    fun subscribe(deviceId: String, dataTypes: Set<String>) {
        selectedDeviceId = deviceId
        selectedTypes.clear()
        selectedTypes.addAll(dataTypes)
        send(JSONObject().apply {
            put("type", "subscribe")
            put("device_id", deviceId)
            put("data_types", JSONArray(dataTypes.toList()))
        })
    }

    fun unsubscribe(deviceId: String, dataTypes: Set<String>) {
        send(JSONObject().apply {
            put("type", "unsubscribe")
            put("device_id", deviceId)
            put("data_types", JSONArray(dataTypes.toList()))
        })
    }

    fun sendPing() {
        send(JSONObject().apply { put("type", "ping") })
    }

    private fun send(json: JSONObject) {
        socket?.send(json.toString())
    }

    private fun restoreSubscription() {
        selectedDeviceId?.let { subscribe(it, selectedTypes) }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempt = 0
            send(JSONObject().apply {
                put("type", "auth")
                put("role", "consumer")
                put("token", config.token)
            })
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching {
                val message = JSONObject(text)
                when (message.optString("type")) {
                    "auth_result" -> {
                        val success = message.optBoolean("success", false)
                        onAuth(success, if (success) "已认证" else "认证失败")
                        if (success) restoreSubscription()
                    }
                    "device_list" -> onDevices(parseDevices(message.optJSONArray("devices")))
                    "environment_data" -> {
                        val payload = JSONObject(message.optJSONObject("data")?.toString() ?: "{}")
                        normalizeRemoteBleRaw(payload)
                        payload.put("_timestamp", message.optLong("timestamp", 0L))
                        payload.put("_sequence", message.optLong("sequence", 0L))
                        onData(
                            message.optString("device_id"),
                            message.optString("data_type"),
                            payload
                        )
                    }
                    "pong" -> onState("已连接 · 心跳正常")
                    "error" -> onState(message.optString("message", "服务器错误"))
                }
            }.onFailure { error -> ZLog.w("Remote", "parse websocket message failed: ${error.message}") }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onAuth(false, t.message ?: "连接失败")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!closed) scheduleReconnect()
            else onState("未连接")
        }
    }

    private fun scheduleReconnect() {
        if (closed) return
        val attempt = reconnectAttempt++
        val delay = min(60_000L, 1_000L * (1L shl min(attempt, 6))) + Random.nextLong(0, 500)
        onState("${delay / 1000} 秒后重连")
        Thread {
            Thread.sleep(delay)
            if (!closed) connect()
        }.apply { name = "ZVE-RemoteReconnect"; isDaemon = true }.start()
    }

    private fun normalizeRemoteBleRaw(payload: JSONObject) {
        val devices = payload.optJSONArray("devices") ?: return
        for (index in 0 until devices.length()) {
            val device = devices.optJSONObject(index) ?: continue
            val raw = device.optString("raw", "")
            if (raw.isBlank()) {
                val rawHex = device.optString("rawHex", "")
                if (rawHex.matches(Regex("[0-9A-Fa-f]+")) && rawHex.length % 2 == 0) {
                    runCatching {
                        val bytes = ByteArray(rawHex.length / 2) { offset ->
                            rawHex.substring(offset * 2, offset * 2 + 2).toInt(16).toByte()
                        }
                        device.put("raw", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                    }
                }
            }
            if (!device.has("rawLength") && device.optString("raw", "").isNotBlank()) {
                runCatching {
                    device.put("rawLength", android.util.Base64.decode(device.getString("raw"), android.util.Base64.DEFAULT).size)
                }
            }
        }
    }

    private fun parseDevices(array: JSONArray?): List<RemoteDevice> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { json ->
                RemoteDevice(
                    deviceId = json.optString("device_id"), name = json.optString("name"),
                    deviceType = json.optString("device_type"),
                    capabilities = (0 until (json.optJSONArray("capabilities")?.length() ?: 0)).mapNotNull {
                        json.optJSONArray("capabilities")?.optString(it)
                    },
                    online = json.optBoolean("online"), lastSeen = json.optLong("last_seen").takeIf { it > 0 },
                    lastData = json.optLong("last_data").takeIf { it > 0 },
                )
            }
        }
    }

    private fun normalizeUrl(value: String): String {
        val input = value.trim().removeSuffix("/")
        return when {
            input.startsWith("ws://") || input.startsWith("wss://") -> input
            input.startsWith("https://") -> "wss://" + input.removePrefix("https://") + "/ws"
            input.startsWith("http://") -> "ws://" + input.removePrefix("http://") + "/ws"
            else -> "wss://$input/ws"
        }
    }
}
