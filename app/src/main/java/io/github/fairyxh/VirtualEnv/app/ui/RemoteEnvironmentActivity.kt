package io.github.fairyxh.VirtualEnv.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.app.remote.RemoteDevice
import io.github.fairyxh.VirtualEnv.app.remote.RemoteEnvironmentManager
import io.github.fairyxh.VirtualEnv.app.remote.RemoteEnvironmentRuntime
import io.github.fairyxh.VirtualEnv.app.remote.RemoteServerConfig
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassBackdropHost
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassCard
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassField
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassPill
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassToggle
import io.github.fairyxh.VirtualEnv.app.ui.glass.glassColors
import org.json.JSONArray
import org.json.JSONObject

class RemoteEnvironmentActivity : ComponentActivity(), RemoteEnvironmentManager.Listener {
    private lateinit var manager: RemoteEnvironmentManager
    private var servers by mutableStateOf(emptyList<RemoteServerConfig>())
    private var devices by mutableStateOf(emptyList<RemoteDevice>())
    private var data by mutableStateOf(emptyMap<String, JSONObject>())
    private var state by mutableStateOf("未连接")
    private var useRemote by mutableStateOf(false)
    private var editingId by mutableStateOf<String?>(null)
    private var name by mutableStateOf("")
    private var url by mutableStateOf("")
    private var token by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = RemoteEnvironmentRuntime.get(this).also {
            it.listener = this
            useRemote = it.isUseRemote()
            clearEditor()
            it.refreshListener()
        }
        setContent { Screen() }
    }

    override fun onResume() {
        super.onResume()
        if (::manager.isInitialized) {
            manager.listener = this
            useRemote = manager.isUseRemote()
            manager.refreshListener()
        }
    }

    override fun onDestroy() {
        if (::manager.isInitialized) manager.listener = null
        super.onDestroy()
    }

    @Composable
    private fun Screen() {
        GlassBackdropHost(Modifier.fillMaxSize()) { backdrop ->
            val colors = glassColors()
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                BasicText(getString(R.string.remote_env_title), style = TextStyle(colors.textPrimary, 32.sp, FontWeight.Bold))
                BasicText(state, style = TextStyle(colors.textSecondary, 13.sp))

                GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth(), containerColor = colors.bgSecondary.copy(alpha = .45f), contentPadding = 16.dp) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            BasicText(getString(R.string.remote_env_use), style = TextStyle(colors.textPrimary, 16.sp, FontWeight.Medium))
                            BasicText(if (useRemote) getString(R.string.remote_env_on) else getString(R.string.remote_env_off), style = TextStyle(colors.textSecondary, 12.sp))
                        }
                        GlassToggle(selected = { useRemote }, onSelect = {
                            useRemote = it
                            manager.setUseRemote(it)
                        }, backdrop = backdrop)
                    }
                }

                GlassSection(title = getString(R.string.remote_env_servers), backdrop = backdrop) {
                    servers.forEach { server -> ServerCard(server, backdrop) }
                    GlassField(name, { name = it }, backdrop, placeholder = getString(R.string.remote_env_name_hint))
                    GlassField(url, { url = it }, backdrop, placeholder = getString(R.string.remote_env_url_hint))
                    GlassField(token, { token = it }, backdrop, placeholder = getString(R.string.remote_env_token_hint))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassPill(onClick = { saveServer() }, backdrop = backdrop, selected = false) {
                            BasicText(if (editingId == null) getString(R.string.remote_env_save) else "更新", Modifier.padding(horizontal = 14.dp), style = TextStyle(colors.textPrimary, 12.sp))
                        }
                        if (editingId != null) {
                            GlassPill(onClick = { clearEditor() }, backdrop = backdrop, selected = false) {
                                BasicText("取消", Modifier.padding(horizontal = 14.dp), style = TextStyle(colors.textSecondary, 12.sp))
                            }
                        }
                    }
                }

                GlassSection(title = getString(R.string.remote_env_devices), backdrop = backdrop) {
                    if (devices.isEmpty()) BasicText(getString(R.string.remote_env_empty), style = TextStyle(colors.textSecondary, 13.sp))
                    devices.forEach { device -> DeviceCard(device, backdrop) }
                }

                GlassSection(title = getString(R.string.remote_env_current_data), backdrop = backdrop) {
                    DataCard("ble", getString(R.string.remote_env_ble), backdrop)
                    DataCard("wifi", getString(R.string.remote_env_wifi), backdrop)
                    DataCard("cell", getString(R.string.remote_env_cell), backdrop)
                }
            }
        }
    }

    @Composable
    private fun GlassSection(title: String, backdrop: com.kyant.backdrop.Backdrop, content: @Composable () -> Unit) {
        val colors = glassColors()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(title, style = TextStyle(colors.textPrimary, 20.sp, FontWeight.SemiBold))
            GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth(), containerColor = colors.bgSecondary.copy(alpha = .28f), contentPadding = 12.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    content()
                }
            }
        }
    }

    @Composable
    private fun ServerCard(server: RemoteServerConfig, backdrop: com.kyant.backdrop.Backdrop) {
        val colors = glassColors()
        GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth(), containerColor = colors.bgTertiary.copy(alpha = .38f), contentPadding = 12.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        BasicText(server.name, style = TextStyle(colors.textPrimary, 16.sp, FontWeight.Medium))
                        BasicText(server.url, style = TextStyle(colors.textSecondary, 12.sp))
                        BasicText(if (server.id == manager.activeServer()?.id) state else server.lastStatus, style = TextStyle(colors.textSecondary, 12.sp))
                    }
                    GlassPill(onClick = { manager.connect(server) }, backdrop = backdrop, selected = server.id == manager.activeServer()?.id) {
                        BasicText(if (server.id == manager.activeServer()?.id) "已连接" else getString(R.string.remote_env_connect), Modifier.padding(horizontal = 10.dp), style = TextStyle(colors.textPrimary, 11.sp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPill(onClick = { loadServer(server) }, backdrop = backdrop, selected = false) { BasicText("编辑", Modifier.padding(horizontal = 10.dp), style = TextStyle(colors.textSecondary, 11.sp)) }
                    GlassPill(onClick = { manager.deleteServer(server.id) }, backdrop = backdrop, selected = false, containerColor = colors.danger.copy(alpha = .2f)) { BasicText("删除", Modifier.padding(horizontal = 10.dp), style = TextStyle(colors.danger, 11.sp)) }
                }
            }
        }
    }

    @Composable
    private fun DeviceCard(device: RemoteDevice, backdrop: com.kyant.backdrop.Backdrop) {
        val colors = glassColors()
        val selected = manager.currentDeviceId() == device.deviceId
        GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth(), containerColor = if (selected) colors.accent.copy(alpha = .16f) else colors.bgTertiary.copy(alpha = .35f), contentPadding = 12.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    BasicText(device.name.ifBlank { device.deviceId }, style = TextStyle(colors.textPrimary, 16.sp, FontWeight.Medium))
                    BasicText(device.deviceId, style = TextStyle(colors.textSecondary, 11.sp))
                    BasicText("${device.deviceType} · ${device.capabilities.joinToString(" / ")} · ${if (device.online) "在线" else "离线"}", style = TextStyle(colors.textSecondary, 12.sp))
                    BasicText("最后数据：${device.lastData ?: 0}", style = TextStyle(colors.textTertiary, 11.sp))
                }
                GlassPill(onClick = { manager.selectDevice(device.deviceId) }, backdrop = backdrop, selected = selected) {
                    BasicText(if (selected) getString(R.string.remote_env_selected) else getString(R.string.remote_env_select), Modifier.padding(horizontal = 10.dp), style = TextStyle(colors.textPrimary, 11.sp))
                }
            }
        }
    }

    @Composable
    private fun DataCard(type: String, title: String, backdrop: com.kyant.backdrop.Backdrop) {
        val colors = glassColors()
        val item = data[type]
        val enabled = manager.isTypeEnabled(type)
        GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth(), containerColor = colors.bgTertiary.copy(alpha = .35f), contentPadding = 12.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        BasicText(title, style = TextStyle(colors.textPrimary, 16.sp, FontWeight.Medium))
                        BasicText(summarize(type, item), style = TextStyle(colors.textSecondary, 12.sp))
                    }
                    GlassToggle(selected = { enabled }, onSelect = { manager.setTypeEnabled(type, it) }, backdrop = backdrop)
                }
                if (item != null) GuiData(type, item)
            }
        }
    }

    @Composable
    private fun GuiData(type: String, item: JSONObject) {
        val colors = glassColors()
        val lines = when (type) {
            "ble" -> arrayLines(item.optJSONArray("devices"), "name", "address", "rssi")
            "wifi" -> arrayLines(item.optJSONArray("networks"), "ssid", "bssid", "rssi", "frequency")
            "cell" -> arrayLines(item.optJSONArray("entries"), "type", "mcc", "mnc", "tac", "ci", "pci", "rsrp")
            else -> emptyList()
        }
        if (lines.isEmpty()) BasicText(getString(R.string.remote_env_empty), style = TextStyle(colors.textTertiary, 12.sp))
        lines.take(8).forEach { line -> BasicText(line, style = TextStyle(colors.textSecondary, 12.sp)) }
    }

    private fun arrayLines(array: JSONArray?, vararg keys: String): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            keys.mapNotNull { key -> if (item.has(key)) "$key=${item.opt(key)}" else null }.joinToString(" · ")
        }
    }

    private fun summarize(type: String, json: JSONObject?): String {
        if (json == null) return getString(R.string.remote_env_empty)
        return when (type) {
            "ble" -> "发现 ${json.optJSONArray("devices")?.length() ?: 0} 个设备"
            "wifi" -> "发现 ${json.optJSONArray("networks")?.length() ?: 0} 个 AP"
            "cell" -> "发现 ${json.optJSONArray("entries")?.length() ?: 0} 个基站"
            else -> getString(R.string.remote_env_empty)
        }
    }

    private fun loadServer(server: RemoteServerConfig) {
        editingId = server.id
        name = server.name
        url = server.url
        token = server.token
    }

    private fun clearEditor() {
        editingId = null
        name = ""
        url = ""
        token = ""
    }

    private fun saveServer() {
        if (name.isBlank() || url.isBlank() || token.isBlank()) return
        val id = editingId
        if (id == null) manager.saveServer(name, url, token) else manager.editServer(id, name, url, token)
        clearEditor()
    }

    override fun onState(value: String) { runOnUiThread { state = value } }
    override fun onServersChanged(value: List<RemoteServerConfig>) { runOnUiThread { servers = value } }
    override fun onDevicesChanged(value: List<RemoteDevice>) { runOnUiThread { devices = value } }
    override fun onDataChanged(value: Map<String, JSONObject>) { runOnUiThread { data = value } }
}
