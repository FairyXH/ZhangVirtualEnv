package io.github.fairyxh.VirtualEnv.app.remote

import android.content.Context
import android.content.SharedPreferences
import io.github.fairyxh.VirtualEnv.app.ApiClient
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Control-app-side remote manager. Transport and arbitration are kept separate from Hook code.
 * Remote data is adapted to existing Backend EnvStateEngine schemas through ApiClient.
 */
class RemoteEnvironmentManager(context: Context) {
    companion object {
        val SUPPORTED_TYPES = linkedSetOf("ble", "wifi", "cell", "gps", "gnss", "sensor")
        private val PROTOCOL_TYPES = SUPPORTED_TYPES.map { if (it == "ble") "bluetooth" else it }.toSet()
    }

    private val repository = RemoteServerRepository(context.applicationContext)
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("remote_environment", Context.MODE_PRIVATE)
    private val stateLock = Any()
    private val writeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "ZVE-RemoteState").apply { isDaemon = true } }
    private val applyExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ZVE-RemoteApply").apply { isDaemon = true }
    }
    private val applyWorkers = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "ZVE-RemoteApplyWorker").apply { isDaemon = true }
    }
    private val pendingRemote = mutableMapOf<String, JSONObject>()
    private val applyingTypes = mutableSetOf<String>()
    private val heartbeatExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ZVE-RemoteHeartbeat").apply { isDaemon = true }
    }
    private var heartbeatTask: ScheduledFuture<*>? = null
    private var freshnessTask: ScheduledFuture<*>? = null
    private var lastForcedRefreshAt = 0L
    private val lastObservedDeviceDataAt = mutableMapOf<String, Long>()
    private var socket: RemoteWebSocketClient? = null
    private var activeConfig: RemoteServerConfig? = null
    private var currentState = "未连接"
    private var currentDevices = emptyList<RemoteDevice>()
    private var moduleEnabled = true
    private val clientDeviceId: String = prefs.getString("consumer_device_id", null)
        ?: ("android-consumer-" + UUID.randomUUID().toString()).also {
            prefs.edit().putString("consumer_device_id", it).apply()
        }
    private var lastHeartbeatAt = 0L
    private var lastDataAt = 0L
    private val latest = mutableMapOf<String, JSONObject>()
    private val remoteEnabled = mutableMapOf<String, Boolean>()
    private var activeDeviceId: String?
        get() = prefs.getString("active_device_id", null)
        set(value) { prefs.edit().putString("active_device_id", value).apply() }
    private var lastSelectedDeviceId: String?
        get() = prefs.getString("last_selected_device_id", null)
        set(value) { prefs.edit().putString("last_selected_device_id", value).apply() }
    private var pendingCapabilityDefaultsDeviceId: String? = null
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
        applyExecutor.scheduleWithFixedDelay({ drainPendingRemote() }, 0L, 200L, TimeUnit.MILLISECONDS)
        freshnessTask = applyExecutor.scheduleWithFixedDelay({ refreshSelectedDeviceIfStale() }, 2L, 2L, TimeUnit.SECONDS)
    }

    var listener: Listener? = null

    interface Listener {
        fun onState(state: String)
        fun onServersChanged(servers: List<RemoteServerConfig>)
        fun onDevicesChanged(devices: List<RemoteDevice>)
        fun onDeviceSelected(deviceId: String?)
        fun onDataChanged(data: Map<String, JSONObject>)
        fun onConfigurationChanged(useRemote: Boolean, typeEnabled: Map<String, Boolean>)
    }

    fun servers(): List<RemoteServerConfig> = repository.list()

    fun currentState(): String = currentState
    fun currentDevices(): List<RemoteDevice> = currentDevices
    fun isServerConnected(serverId: String): Boolean =
        activeConfig?.id == serverId && socket?.isOpen() == true
    fun lastHeartbeatAt(): Long = lastHeartbeatAt
    fun lastDataAt(): Long = lastDataAt

    /** Replays the process-wide remote session to a newly bound UI listener. */
    fun refreshListener() {
        listener?.let { currentListener ->
            currentListener.onServersChanged(servers())
            currentListener.onState(currentState)
            currentListener.onDevicesChanged(currentDevices)
            currentListener.onDeviceSelected(activeDeviceId)
            currentListener.onDataChanged(latest.toMap())
            currentListener.onConfigurationChanged(useRemote, remoteEnabled.toMap())
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
        freshnessTask?.cancel(false)
        freshnessTask = null
        applyExecutor.shutdownNow()
        applyWorkers.shutdownNow()
        heartbeatExecutor.shutdownNow()
    }

    fun deleteServer(id: String) {
        if (activeConfig?.id == id) disconnect()
        if (activeServerId == id) activeServerId = null
        repository.delete(id)
        listener?.onServersChanged(servers())
    }

    private var reconnecting = false

    fun connect(config: RemoteServerConfig) {
        if (activeConfig?.id == config.id && socket?.isOpen() == true) {
            socket?.restoreSubscription()
            emitState(currentState)
            return
        }
        val savedDeviceId = activeDeviceId ?: lastSelectedDeviceId
        disconnect(restoreLocal = false)
        activeConfig = config
        activeServerId = config.id
        activeDeviceId = savedDeviceId
        pendingCapabilityDefaultsDeviceId = savedDeviceId
        persistState()
        publishRemoteSimulationMode()
        emitState("连接中")
        heartbeatTask?.cancel(false)
        heartbeatTask = heartbeatExecutor.scheduleAtFixedRate({
            socket?.sendPing()
        }, 0L, 5L, TimeUnit.SECONDS)
        socket = RemoteWebSocketClient(
            config = config,
            clientDeviceId = clientDeviceId,
            onAuth = { success, state ->
                emitState(state)
                if (success) {
                    lastHeartbeatAt = System.currentTimeMillis()
                    currentDevices = emptyList()
                    listener?.onDevicesChanged(currentDevices)
                    activeDeviceId?.let { deviceId -> selectDevice(deviceId) }
                    if (useRemote) {
                        remoteEnabled.filterValues { it }.keys.forEach { type ->
                            latest[type]?.let { data -> synchronized(stateLock) { pendingRemote[type] = JSONObject(data.toString()) } }
                        }
                    }
                }
            },
            onDevices = { devices ->
                currentDevices = devices
                devices.forEach { device ->
                    val previous = lastObservedDeviceDataAt[device.deviceId] ?: 0L
                    if (device.lastData != null && device.lastData > previous) {
                        lastObservedDeviceDataAt[device.deviceId] = device.lastData
                    }
                }
                if (activeDeviceId != null &&
                    pendingCapabilityDefaultsDeviceId != activeDeviceId &&
                    devices.none { it.deviceId == activeDeviceId }
                ) {
                    activeDeviceId = null
                    listener?.onDeviceSelected(null)
                }
                pendingCapabilityDefaultsDeviceId?.let { pendingId ->
                    devices.firstOrNull { it.deviceId == pendingId }?.let { device ->
                        applyDeviceCapabilityDefaults(device)
                        pendingCapabilityDefaultsDeviceId = null
                    }
                }
                listener?.onDevicesChanged(devices)
            },
            onData = { deviceId, protocolType, data ->
                if (deviceId != activeDeviceId) return@RemoteWebSocketClient
                val dataType = if (protocolType == "bluetooth") "ble" else protocolType
                if (dataType !in SUPPORTED_TYPES) return@RemoteWebSocketClient
                latest[dataType] = data
                synchronized(stateLock) { pendingRemote[dataType] = JSONObject(data.toString()) }
                lastDataAt = System.currentTimeMillis()
                listener?.onDataChanged(latest.toMap())

            },
            onState = { state -> emitState(state) },
        ).also { it.connect() }
    }

    fun reconnectPersisted() {
        if (!useRemote) return
        if (activeConfig?.id == activeServer()?.id && socket?.isActive() == true) {
            socket?.restoreSubscription()
            return
        }
        val config = activeServer() ?: return
        connect(config)
    }

    fun disconnect(restoreLocal: Boolean = true) {
        socket?.disconnect()
        socket = null
        heartbeatTask?.cancel(false)
        heartbeatTask = null
        synchronized(stateLock) { pendingRemote.clear() }
        val snapshots = synchronized(stateLock) {
            localSnapshots.toMap().also { localSnapshots.clear() }
        }
        if (restoreLocal && snapshots.isNotEmpty()) {
            writeExecutor.execute { restoreLocalTypes(snapshots) }
        }
        if (useRemote) prepareRemoteTypesWithoutData()
        activeConfig = null
        activeDeviceId = null
        publishRemoteSimulationMode()
        lastObservedDeviceDataAt.clear()
        currentDevices = emptyList()
        latest.clear()
        emitState("未连接")
        listener?.onDevicesChanged(currentDevices)
        listener?.onDeviceSelected(null)
        listener?.onDataChanged(emptyMap())
        notifyConfigurationChanged()
    }

    fun selectDevice(deviceId: String) {
        selectDevice(deviceId, applyCapabilityDefaults = true)
    }

    private fun selectDevice(deviceId: String, applyCapabilityDefaults: Boolean) {
        val old = activeDeviceId
        if (old != null && old != deviceId) {
            socket?.unsubscribe(old, PROTOCOL_TYPES)
            latest.clear()
        }
        lastForcedRefreshAt = 0L
        activeDeviceId = deviceId
        lastSelectedDeviceId = deviceId
        if (applyCapabilityDefaults) {
            currentDevices.firstOrNull { it.deviceId == deviceId }?.let(::applyDeviceCapabilityDefaults)
                ?: run { pendingCapabilityDefaultsDeviceId = deviceId }
        }
        socket?.subscribe(deviceId, PROTOCOL_TYPES)
        publishRemoteSimulationMode()
        listener?.onDeviceSelected(deviceId)
        listener?.onDataChanged(latest.toMap())
    }

    /** Replays the selected device's cached/latest frames without changing selection state. */
    fun forceRefreshSelectedDevice() {
        if (activeDeviceId == null || socket?.isOpen() != true) return
        socket?.refreshSubscription()
    }

    fun setUseRemote(enabled: Boolean) {
        useRemote = enabled
        persistState()
        if (!enabled) {
            SUPPORTED_TYPES.forEach { remoteEnabled[it] = false }
            persistState()
            disconnect()
            publishRemoteSimulationMode()
            listener?.onState("本地环境模拟正常工作")
            return
        }
        val selectedId = activeDeviceId ?: lastSelectedDeviceId
        if (selectedId != null) {
            activeDeviceId = selectedId
            currentDevices.firstOrNull { it.deviceId == selectedId }?.let(::applyDeviceCapabilityDefaults)
                ?: run {
                    SUPPORTED_TYPES.forEach { remoteEnabled[it] = false }
                    pendingCapabilityDefaultsDeviceId = selectedId
                    persistState()
                }
        } else {
            SUPPORTED_TYPES.forEach { remoteEnabled[it] = false }
            persistState()
        }
        publishRemoteSimulationMode()
        listener?.onState("远程环境模拟启用")
        remoteEnabled.filterValues { it }.keys.forEach { type ->
            latest[type]?.let { data -> synchronized(stateLock) { pendingRemote[type] = JSONObject(data.toString()) } }
        }
        if (socket?.isOpen() != true) {
            prepareRemoteTypesWithoutData()
        }
        notifyConfigurationChanged()
    }

    private fun publishRemoteSimulationMode() {
        runCatching {
            ApiClient.setRemoteSimulation(
                useRemote && !activeDeviceId.isNullOrBlank() && socket?.isOpen() == true,
                activeDeviceId ?: "",
                activeServerId ?: "",
            )
        }.onFailure { ZLog.w("Remote", "publish remote mode failed: ${it.message}") }
    }

    fun setTypeEnabled(type: String, enabled: Boolean) {
        require(type in SUPPORTED_TYPES)
        remoteEnabled[type] = enabled
        persistState()
        if (useRemote && enabled) {
            latest[type]?.let { data -> synchronized(stateLock) { pendingRemote[type] = JSONObject(data.toString()) } }
        } else if (!enabled) {
            val local = synchronized(stateLock) {
                pendingRemote.remove(type)
                localSnapshots.remove(type)
            }
            writeExecutor.execute { restoreLocalType(type, local) }
        }
        notifyConfigurationChanged()
    }

    fun setModuleEnabled(enabled: Boolean) {
        moduleEnabled = enabled
        if (enabled && useRemote) {
            synchronized(stateLock) {
                latest.forEach { (type, data) -> pendingRemote[type] = JSONObject(data.toString()) }
            }
        }
    }

    fun refreshModuleEnabled() {
        writeExecutor.execute {
            val enabled = ApiClient.getModuleStatus(suppressTransportFailure = true)
                .data?.optBoolean("enabled", true) ?: true
            setModuleEnabled(enabled)
        }
    }

    fun isUseRemote(): Boolean = useRemote
    fun isTypeEnabled(type: String): Boolean = remoteEnabled[type] == true
    fun typeEnabledSnapshot(): Map<String, Boolean> = remoteEnabled.toMap()
    fun currentDeviceId(): String? = activeDeviceId
    fun currentData(): Map<String, JSONObject> = latest.toMap()

    private fun drainPendingRemote() {
        if (!useRemote || !moduleEnabled) return
        val pending = synchronized(stateLock) {
            pendingRemote.toMap().also { pendingRemote.clear() }
        }
        pending.forEach { (type, data) ->
            if (remoteEnabled[type] != true) return@forEach
            synchronized(stateLock) {
                if (!applyingTypes.add(type)) {
                    pendingRemote[type] = data
                    return@forEach
                }
            }
            applyWorkers.execute {
                try {
                    applyRemote(type, data)
                } catch (t: Throwable) {
                    synchronized(stateLock) { pendingRemote[type] = data }
                    ZLog.w("Remote", "queued remote apply retry type=$type", t)
                } finally {
                    synchronized(stateLock) { applyingTypes.remove(type) }
                }
            }
        }
    }

    private fun refreshSelectedDeviceIfStale() {
        if (!useRemote || !moduleEnabled || activeDeviceId == null || socket?.isOpen() != true) return
        val now = System.currentTimeMillis()
        val selectedId = activeDeviceId ?: return
        val device = currentDevices.firstOrNull { it.deviceId == selectedId }
        val observedAt = lastObservedDeviceDataAt[selectedId] ?: 0L
        val sourceDataAt = maxOf(device?.lastData ?: 0L, observedAt)
        val staleTypes = SUPPORTED_TYPES.filter { type ->
            val item = latest[type]
            val itemAt = item?.optLong("_timestamp", 0L) ?: 0L
            val protocolType = if (type == "ble") "bluetooth" else type
            val serverTypeAt = device?.lastDataByType?.get(protocolType)
                ?: device?.lastDataByType?.get(type) ?: 0L
            item == null || itemAt <= 0L || now - itemAt > 10_000L ||
                (maxOf(sourceDataAt, serverTypeAt) > itemAt && maxOf(sourceDataAt, serverTypeAt) - itemAt > 1_000L)
        }
        if (staleTypes.isNotEmpty() && now - lastForcedRefreshAt > 10_000L) {
            lastForcedRefreshAt = now
            ZLog.i(
                "Remote",
                "selected device data stale; auto-select device=$selectedId types=${staleTypes.joinToString(",")} " +
                    "sourceAt=$sourceDataAt"
            )
            // Use the exact same manager path as tapping the selected device button.
            selectDevice(selectedId, applyCapabilityDefaults = false)
        }
    }

    private fun applyDeviceCapabilityDefaults(device: RemoteDevice) {
        val supported = device.capabilities.mapTo(mutableSetOf()) { capability ->
            when (capability.lowercase()) {
                "bluetooth", "bluetooth_data", "ble" -> "ble"
                "cellular" -> "cell"
                else -> capability.lowercase()
            }
        }
        SUPPORTED_TYPES.forEach { type -> remoteEnabled[type] = type in supported }
        persistState()
        notifyConfigurationChanged()
    }

    private fun prepareRemoteTypesWithoutData() {
        writeExecutor.execute {
            SUPPORTED_TYPES.filter { remoteEnabled[it] == true }.forEach { type ->
                val empty = when (type) {
                    "ble" -> JSONObject().put("devices", JSONArray())
                    "wifi" -> JSONObject().put("networks", JSONArray())
                    "cell" -> JSONObject().put("entries", JSONArray())
                    else -> JSONObject()
                }
                runCatching {
                    val result = ApiClient.setEnvData(type, empty, suppressTransportFailure = true)
                    if (result.code == io.github.fairyxh.VirtualEnv.core.model.ApiResult.CODE_OK) {
                        ApiClient.setEnvEnabled(type, true, suppressTransportFailure = true)
                    }
                }.onFailure { ZLog.w("Remote", "clear disconnected remote type failed: $type", it) }
            }
        }
    }

    private fun applyRemote(type: String, data: JSONObject) {
        if (!moduleEnabled) {
            ZLog.i("Remote", "module master switch is off; defer remote apply type=$type")
            return
        }
        if (type == "gps") {
            data.put("_gnssSourceEnabled", remoteEnabled["gnss"] == true)
        }
        synchronized(stateLock) {
            if (!localSnapshots.containsKey(type)) {
                localSnapshots[type] = runCatching {
                    if (type == "gps") {
                        ApiClient.getLocationStatus(suppressTransportFailure = true).data
                    } else {
                        ApiClient.getEnvStatus(type, suppressTransportFailure = true)
                            .data?.optJSONObject("data")
                    }
                }.getOrNull()
            }
        }
        if (type == "gps") {
            val applied = ApiClient.applyRemoteGps(data)
            if (applied.code != io.github.fairyxh.VirtualEnv.core.model.ApiResult.CODE_OK) {
                throw IllegalStateException("apply remote gps failed: ${applied.message}")
            }
            return
        }
        val envType = type
        val normalized = when (type) {
            "ble" -> JSONObject(data.toString()).apply {
                if (!has("devices")) put("devices", JSONArray())
            }
            "wifi" -> JSONObject(data.toString()).apply {
                if (!has("networks")) put("networks", JSONArray())
            }
            "cell" -> JSONObject(data.toString()).apply {
                if (!has("entries")) put("entries", JSONArray())
            }
            "gnss" -> JSONObject(data.toString()).apply {
                put("_remoteSource", true)
                if (!has("satellites")) put("satellites", JSONArray())
            }
            "sensor" -> JSONObject(data.toString()).apply {
                if (!has("samples") && !has("readings")) put("samples", JSONArray())
            }
            else -> JSONObject(data.toString())
        }
        try {
            if (type == "gps") {
                data.put("_gnssSourceEnabled", remoteEnabled["gnss"] == true)
            }
            val result = ApiClient.setEnvData(envType, normalized, suppressTransportFailure = true)
            if (result.code != io.github.fairyxh.VirtualEnv.core.model.ApiResult.CODE_OK) {
                throw IllegalStateException("apply remote $type failed: ${result.message}")
            }
            ApiClient.setEnvEnabled(envType, true, suppressTransportFailure = true)
        } catch (t: Throwable) {
            ZLog.w("Remote", "apply remote $type exception: ${t.message}")
            throw t
        }
    }

    private fun restoreLocalType(type: String, local: JSONObject?) {
        local ?: return
        runCatching {
            if (type == "gps") {
                val set = ApiClient.setLocation(
                    local.optDouble("latitude", 0.0),
                    local.optDouble("longitude", 0.0),
                    local.optDouble("speed", 0.0).toFloat(),
                    local.optDouble("bearing", 0.0).toFloat(),
                    suppressTransportFailure = true,
                )
                if (set.code == io.github.fairyxh.VirtualEnv.core.model.ApiResult.CODE_OK) {
                    ApiClient.setLocationEnabled(
                        local.optBoolean("enabled", false),
                        suppressTransportFailure = true,
                    )
                }
            } else {
                ApiClient.setEnvData(type, local, suppressTransportFailure = true)
                ApiClient.setEnvEnabled(type, true, suppressTransportFailure = true)
            }
        }.onFailure { ZLog.w("Remote", "restore local $type failed: ${it.message}") }
    }

    private fun restoreLocalTypes(snapshots: Map<String, JSONObject?>) {
        snapshots.forEach(::restoreLocalType)
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
        if (value == "已连接 · 心跳正常") {
            lastHeartbeatAt = System.currentTimeMillis()
        }
        currentState = value
        listener?.onState(value)
    }

    private fun notifyConfigurationChanged() {
        listener?.onConfigurationChanged(useRemote, remoteEnabled.toMap())
    }
}

/** Process-wide owner so leaving the management page does not stop remote virtualization. */
object RemoteEnvironmentRuntime {
    @Volatile private var instance: RemoteEnvironmentManager? = null

    fun get(context: Context): RemoteEnvironmentManager = synchronized(this) {
        instance ?: RemoteEnvironmentManager(context.applicationContext).also { instance = it }
    }
}
