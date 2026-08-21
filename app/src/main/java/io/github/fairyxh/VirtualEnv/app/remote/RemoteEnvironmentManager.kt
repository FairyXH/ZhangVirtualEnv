package io.github.fairyxh.VirtualEnv.app.remote

import android.content.Context
import io.github.fairyxh.VirtualEnv.app.ApiClient
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Control-app-side remote manager. Transport and arbitration are kept separate from Hook code.
 * Remote data is adapted to existing Backend EnvStateEngine schemas through ApiClient.
 */
class RemoteEnvironmentManager(context: Context) {
    companion object {
        val SUPPORTED_TYPES = linkedSetOf("ble", "wifi", "cell")
    }

    private val repository = RemoteServerRepository(context.applicationContext)
    private var socket: RemoteWebSocketClient? = null
    private var activeConfig: RemoteServerConfig? = null
    private val latest = mutableMapOf<String, JSONObject>()
    private val remoteEnabled = mutableMapOf<String, Boolean>()
    private var activeDeviceId: String? = null
    private var useRemote = false

    var listener: Listener? = null

    interface Listener {
        fun onState(state: String)
        fun onServersChanged(servers: List<RemoteServerConfig>)
        fun onDevicesChanged(devices: List<RemoteDevice>)
        fun onDataChanged(data: Map<String, JSONObject>)
    }

    fun servers(): List<RemoteServerConfig> = repository.list()

    fun saveServer(name: String, url: String, token: String, id: String? = null) {
        repository.save(RemoteServerConfig(id ?: UUID.randomUUID().toString(), name, url, token, true, "未连接"))
        listener?.onServersChanged(servers())
    }

    fun deleteServer(id: String) {
        if (activeConfig?.id == id) disconnect()
        repository.delete(id)
        listener?.onServersChanged(servers())
    }

    fun connect(config: RemoteServerConfig) {
        disconnect()
        activeConfig = config
        socket = RemoteWebSocketClient(
            config = config,
            onAuth = { success, state ->
                listener?.onState(state)
                if (success) listener?.onDevicesChanged(emptyList())
            },
            onDevices = { devices -> listener?.onDevicesChanged(devices) },
            onData = { deviceId, dataType, data ->
                if (deviceId != activeDeviceId) return@RemoteWebSocketClient
                latest[dataType] = data
                if (useRemote && remoteEnabled[dataType] == true) applyRemote(dataType, data)
                listener?.onDataChanged(latest.toMap())
            },
            onState = { state -> listener?.onState(state) },
        ).also { it.connect() }
    }

    fun disconnect() {
        socket?.disconnect()
        socket = null
        activeConfig = null
        activeDeviceId = null
        latest.clear()
        listener?.onDataChanged(emptyMap())
    }

    fun selectDevice(deviceId: String) {
        val old = activeDeviceId
        if (old != null && old != deviceId) {
            socket?.unsubscribe(old, SUPPORTED_TYPES)
            latest.clear()
            SUPPORTED_TYPES.forEach { remoteEnabled[it] = false }
        }
        activeDeviceId = deviceId
        socket?.subscribe(deviceId, SUPPORTED_TYPES)
        listener?.onDataChanged(latest.toMap())
    }

    fun setUseRemote(enabled: Boolean) {
        useRemote = enabled
        if (!enabled) {
            // Keep local engine data and only disable remote ownership through the arbitration layer.
            SUPPORTED_TYPES.forEach { remoteEnabled[it] = false }
            listener?.onState("本地环境模拟正常工作")
            return
        }
        listener?.onState("远程环境模拟启用")
        SUPPORTED_TYPES.forEach { type ->
            if (latest[type] != null) {
                remoteEnabled[type] = true
                applyRemote(type, latest.getValue(type))
            }
        }
    }

    fun setTypeEnabled(type: String, enabled: Boolean) {
        require(type in SUPPORTED_TYPES)
        remoteEnabled[type] = enabled
        if (useRemote && enabled) latest[type]?.let { applyRemote(type, it) }
    }

    fun isUseRemote(): Boolean = useRemote
    fun isTypeEnabled(type: String): Boolean = remoteEnabled[type] == true
    fun currentDeviceId(): String? = activeDeviceId
    fun currentData(): Map<String, JSONObject> = latest.toMap()

    private fun applyRemote(type: String, data: JSONObject) {
        val envType = if (type == "ble") "ble" else type
        val normalized = when (type) {
            "ble" -> JSONObject().apply {
                put("devices", data.optJSONArray("devices") ?: JSONArray())
                data.opt("adapterMac")?.let { put("adapterMac", it) }
                data.opt("adapterName")?.let { put("adapterName", it) }
            }
            "wifi" -> JSONObject().apply { put("networks", data.optJSONArray("networks") ?: JSONArray()) }
            "cell" -> JSONObject().apply { put("entries", data.optJSONArray("entries") ?: JSONArray()) }
            else -> data
        }
        try {
            val result = ApiClient.setEnvData(envType, normalized)
            if (result.code != io.github.fairyxh.VirtualEnv.core.model.ApiResult.CODE_OK) {
                ZLog.w("Remote", "apply remote $type failed: ${result.message}")
            }
            ApiClient.setEnvEnabled(envType, true)
        } catch (t: Throwable) {
            ZLog.w("Remote", "apply remote $type exception: ${t.message}")
        }
    }
}
