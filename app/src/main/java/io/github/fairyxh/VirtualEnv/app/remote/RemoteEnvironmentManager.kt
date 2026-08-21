package io.github.fairyxh.VirtualEnv.app.remote

import android.content.Context
import android.content.SharedPreferences
import io.github.fairyxh.VirtualEnv.app.ApiClient
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Control-app-side remote manager. Transport and arbitration are kept separate from Hook code.
 * Remote data is adapted to existing Backend EnvStateEngine schemas through ApiClient.
 */
class RemoteEnvironmentManager(context: Context) {
    companion object {
        val SUPPORTED_TYPES = linkedSetOf("ble", "wifi", "cell")
        private val PROTOCOL_TYPES = setOf("bluetooth", "wifi", "cell")
    }

    private val repository = RemoteServerRepository(context.applicationContext)
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("remote_environment", Context.MODE_PRIVATE)
    private val stateLock = Any()
    private val writeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "ZVE-RemoteState").apply { isDaemon = true } }
    private var socket: RemoteWebSocketClient? = null
    private var activeConfig: RemoteServerConfig? = null
    private var currentState = "未连接"
    private var currentDevices = emptyList<RemoteDevice>()
    private val latest = mutableMapOf<String, JSONObject>()
    private val remoteEnabled = mutableMapOf<String, Boolean>()
    private var activeDeviceId: String?
        get() = prefs.getString("active_device_id", null)
        set(value) { prefs.edit().putString("active_device_id", value).apply() }
    private var useRemote = false
    private val localSnapshots = mutableMapOf<String, JSONObject?>()
    private var activeServerId: String?
        get() = prefs.getString("active_server_id", null)
        set(value) { prefs.edit().putString("active_server_id", value).apply() }

    init {
        useRemote = prefs.getBoolean("use_remote", false)
        SUPPORTED_TYPES.forEach { type ->
            remoteEnabled[type] = prefs.getBoolean("remote_enabled_$type", false)
        }
    }

    var listener: Listener? = null

    interface Listener {
        fun onState(state: String)
        fun onServersChanged(servers: List<RemoteServerConfig>)
        fun onDevicesChanged(devices: List<RemoteDevice>)
        fun onDataChanged(data: Map<String, JSONObject>)
    }

    fun servers(): List<RemoteServerConfig> = repository.list()

    fun currentState(): String = currentState
    fun currentDevices(): List<RemoteDevice> = currentDevices

    /** Replays the process-wide remote session to a newly bound UI listener. */
    fun refreshListener() {
        listener?.let { currentListener ->
            currentListener.onServersChanged(servers())
            currentListener.onState(currentState)
            currentListener.onDevicesChanged(currentDevices)
            currentListener.onDataChanged(latest.toMap())
        }
    }

    fun saveServer(name: String, url: String, token: String, id: String? = null) {
        repository.save(RemoteServerConfig(id ?: UUID.randomUUID().toString(), name, url, token, true, "未连接"))
        listener?.onServersChanged(servers())
    }

    fun activeServer(): RemoteServerConfig? = servers().firstOrNull { it.id == activeServerId }

    fun editServer(id: String, name: String, url: String, token: String) {
        val current = servers().firstOrNull { it.id == id } ?: return
        repository.save(current.copy(name = name, url = url, token = token))
        if (activeConfig?.id == id) {
            val wasRemote = useRemote
            disconnect(restoreLocal = false)
            if (wasRemote) connect(repository.list().first { it.id == id })
        }
        listener?.onServersChanged(servers())
    }

    fun close() {
        disconnect(restoreLocal = false)
        writeExecutor.shutdownNow()
    }

    fun deleteServer(id: String) {
        if (activeConfig?.id == id) disconnect()
        if (activeServerId == id) activeServerId = null
        repository.delete(id)
        listener?.onServersChanged(servers())
    }

    fun connect(config: RemoteServerConfig) {
        val savedDeviceId = activeDeviceId
        disconnect(restoreLocal = false)
        activeConfig = config
        activeServerId = config.id
        activeDeviceId = savedDeviceId
        persistState()
        emitState("连接中")
        socket = RemoteWebSocketClient(
            config = config,
            onAuth = { success, state ->
                emitState(state)
                if (success) {
                    currentDevices = emptyList()
                    listener?.onDevicesChanged(currentDevices)
                    activeDeviceId?.let { deviceId -> selectDevice(deviceId) }
                    if (useRemote) {
                        remoteEnabled.filterValues { it }.keys.forEach { type ->
                            latest[type]?.let { applyRemote(type, it) }
                        }
                    }
                }
            },
            onDevices = { devices ->
                currentDevices = devices
                listener?.onDevicesChanged(devices)
            },
            onData = { deviceId, protocolType, data ->
                if (deviceId != activeDeviceId) return@RemoteWebSocketClient
                val dataType = if (protocolType == "bluetooth") "ble" else protocolType
                if (dataType !in SUPPORTED_TYPES) return@RemoteWebSocketClient
                latest[dataType] = data
                if (useRemote && remoteEnabled[dataType] == true) applyRemote(dataType, data)
                listener?.onDataChanged(latest.toMap())
            },
            onState = { state -> emitState(state) },
        ).also { it.connect() }
    }

    fun reconnectPersisted() {
        if (!useRemote) return
        val config = activeServer() ?: return
        connect(config)
        activeDeviceId?.let { deviceId ->
            if (deviceId.isNotBlank()) selectDevice(deviceId)
        }
    }

    fun disconnect(restoreLocal: Boolean = true) {
        socket?.disconnect()
        socket = null
        if (restoreLocal) restoreLocalTypes()
        activeConfig = null
        activeDeviceId = null
        currentDevices = emptyList()
        latest.clear()
        emitState("未连接")
        listener?.onDevicesChanged(currentDevices)
        listener?.onDataChanged(emptyMap())
    }

    fun selectDevice(deviceId: String) {
        val old = activeDeviceId
        if (old != null && old != deviceId) {
            socket?.unsubscribe(old, PROTOCOL_TYPES)
            latest.clear()
        }
        activeDeviceId = deviceId
        socket?.subscribe(deviceId, PROTOCOL_TYPES)
        listener?.onDataChanged(latest.toMap())
    }

    fun setUseRemote(enabled: Boolean) {
        useRemote = enabled
        persistState()
        if (!enabled) {
            SUPPORTED_TYPES.forEach { remoteEnabled[it] = false }
            restoreLocalTypes()
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
        persistState()
        if (useRemote && enabled) {
            latest[type]?.let { applyRemote(type, it) }
        } else if (!enabled) {
            restoreLocalType(type)
        }
    }

    fun isUseRemote(): Boolean = useRemote
    fun isTypeEnabled(type: String): Boolean = remoteEnabled[type] == true
    fun currentDeviceId(): String? = activeDeviceId
    fun currentData(): Map<String, JSONObject> = latest.toMap()

    private fun applyRemote(type: String, data: JSONObject) {
        if (!localSnapshots.containsKey(type)) {
            localSnapshots[type] = runCatching {
                ApiClient.getEnvStatus(type).data?.optJSONObject("data")
            }.getOrNull()
        }
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

    private fun restoreLocalType(type: String) {
        val local = localSnapshots[type] ?: return
        runCatching {
            ApiClient.setEnvData(type, local)
            ApiClient.setEnvEnabled(type, true)
        }.onFailure { ZLog.w("Remote", "restore local $type failed: ${it.message}") }
    }

    private fun restoreLocalTypes() {
        localSnapshots.keys.toList().forEach(::restoreLocalType)
        localSnapshots.clear()
    }

    private fun persistState() {
        prefs.edit()
            .putBoolean("use_remote", useRemote)
            .apply {
                remoteEnabled.forEach { (type, enabled) -> putBoolean("remote_enabled_$type", enabled) }
            }
            .apply()
    }

    private fun emitState(value: String) {
        currentState = value
        listener?.onState(value)
    }
}

/** Process-wide owner so leaving the management page does not stop remote virtualization. */
object RemoteEnvironmentRuntime {
    @Volatile private var instance: RemoteEnvironmentManager? = null

    fun get(context: Context): RemoteEnvironmentManager = synchronized(this) {
        instance ?: RemoteEnvironmentManager(context.applicationContext).also { instance = it }
    }
}
