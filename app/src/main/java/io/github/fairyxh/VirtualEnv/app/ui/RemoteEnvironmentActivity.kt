package io.github.fairyxh.VirtualEnv.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.app.remote.RemoteDevice
import io.github.fairyxh.VirtualEnv.app.remote.RemoteEnvironmentManager
import io.github.fairyxh.VirtualEnv.app.remote.RemoteServerConfig
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassBackdropHost
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassCard
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassPill
import io.github.fairyxh.VirtualEnv.app.ui.glass.glassColors
import org.json.JSONObject

class RemoteEnvironmentActivity : ComponentActivity(), RemoteEnvironmentManager.Listener {
    private lateinit var manager: RemoteEnvironmentManager
    private var servers by mutableStateOf(emptyList<RemoteServerConfig>())
    private var devices by mutableStateOf(emptyList<RemoteDevice>())
    private var data by mutableStateOf(emptyMap<String, JSONObject>())
    private var state by mutableStateOf("未连接")
    private var useRemote by mutableStateOf(false)
    private var name by mutableStateOf("")
    private var url by mutableStateOf("")
    private var token by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = RemoteEnvironmentManager(this).also {
            it.listener = this
            servers = it.servers()
        }
        setContent { Screen() }
    }

    override fun onDestroy() {
        manager.disconnect()
        super.onDestroy()
    }

    @Composable
    private fun Screen() {
        GlassBackdropHost(Modifier.fillMaxSize()) { backdrop ->
            val colors = glassColors()
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BasicText(getString(R.string.remote_env_title), style = TextStyle(colors.textPrimary, 32.sp, FontWeight.Bold))
                BasicText(if (useRemote) getString(R.string.remote_env_on) else getString(R.string.remote_env_off), style = TextStyle(colors.textSecondary, 13.sp))
                GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth(), containerColor = colors.bgSecondary.copy(alpha = .45f)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        BasicText(getString(R.string.remote_env_use), style = TextStyle(colors.textPrimary, 16.sp, FontWeight.Medium))
                        GlassPill(onClick = { useRemote = !useRemote; manager.setUseRemote(useRemote) }, backdrop = backdrop, selected = useRemote) {
                            BasicText(if (useRemote) "ON" else "OFF", Modifier.padding(horizontal = 14.dp), style = TextStyle(colors.textPrimary, 12.sp))
                        }
                    }
                }
                BasicText(getString(R.string.remote_env_servers), style = TextStyle(colors.textPrimary, 20.sp, FontWeight.SemiBold))
                servers.forEach { server ->
                    GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth(), onClick = { manager.connect(server) }, containerColor = colors.bgSecondary.copy(alpha = .4f)) {
                        Column(Modifier.padding(14.dp)) {
                            BasicText(server.name, style = TextStyle(colors.textPrimary, 16.sp, FontWeight.Medium))
                            BasicText("${server.url} · ${if (server.id == servers.firstOrNull()?.id) state else server.lastStatus}", style = TextStyle(colors.textSecondary, 12.sp))
                        }
                    }
                }
                RemoteField(getString(R.string.remote_env_name_hint), name) { name = it }
                RemoteField(getString(R.string.remote_env_url_hint), url) { url = it }
                RemoteField(getString(R.string.remote_env_token_hint), token) { token = it }
                GlassPill(onClick = { if (name.isNotBlank() && url.isNotBlank() && token.isNotBlank()) { manager.saveServer(name, url, token); name = ""; url = ""; token = "" } }, backdrop = backdrop, selected = false) {
                    BasicText(getString(R.string.remote_env_save), Modifier.padding(horizontal = 18.dp), style = TextStyle(colors.textPrimary, 13.sp))
                }
                BasicText(getString(R.string.remote_env_devices), style = TextStyle(colors.textPrimary, 20.sp, FontWeight.SemiBold))
                devices.forEach { device ->
                    GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth(), containerColor = colors.bgSecondary.copy(alpha = .4f)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                BasicText(device.name.ifBlank { device.deviceId }, style = TextStyle(colors.textPrimary, 16.sp, FontWeight.Medium))
                                BasicText("${device.deviceType} · ${device.capabilities.joinToString(" / ")} · ${if (device.online) "在线" else "离线"}", style = TextStyle(colors.textSecondary, 12.sp))
                            }
                            GlassPill(onClick = { manager.selectDevice(device.deviceId) }, backdrop = backdrop, selected = manager.currentDeviceId() == device.deviceId) {
                                BasicText(if (manager.currentDeviceId() == device.deviceId) getString(R.string.remote_env_selected) else getString(R.string.remote_env_select), Modifier.padding(horizontal = 10.dp), style = TextStyle(colors.textPrimary, 11.sp))
                            }
                        }
                    }
                }
                BasicText(getString(R.string.remote_env_current_data), style = TextStyle(colors.textPrimary, 20.sp, FontWeight.SemiBold))
                listOf("ble" to R.string.remote_env_ble, "wifi" to R.string.remote_env_wifi, "cell" to R.string.remote_env_cell).forEach { (type, title) ->
                    val item = data[type]
                    BasicText("${getString(title)}: ${item?.let { summarize(type, it) } ?: getString(R.string.remote_env_empty)}", style = TextStyle(colors.textSecondary, 13.sp))
                }
            }
        }
    }

    @Composable
    private fun RemoteField(hint: String, value: String, onValue: (String) -> Unit) {
        val colors = glassColors()
        BasicTextField(value, onValue, Modifier.fillMaxWidth().padding(vertical = 8.dp), textStyle = TextStyle(colors.textPrimary, 14.sp), decorationBox = { inner ->
            if (value.isEmpty()) BasicText(hint, style = TextStyle(colors.textSecondary, 14.sp))
            inner()
        })
    }

    private fun summarize(type: String, json: JSONObject): String = when (type) {
        "ble" -> "发现 ${json.optJSONArray("devices")?.length() ?: 0} 个设备"
        "wifi" -> "发现 ${json.optJSONArray("networks")?.length() ?: 0} 个 AP"
        "cell" -> "发现 ${json.optJSONArray("entries")?.length() ?: 0} 个基站"
        else -> ""
    }

    override fun onState(value: String) { runOnUiThread { state = value } }
    override fun onServersChanged(value: List<RemoteServerConfig>) { runOnUiThread { servers = value } }
    override fun onDevicesChanged(value: List<RemoteDevice>) { runOnUiThread { devices = value } }
    override fun onDataChanged(value: Map<String, JSONObject>) { runOnUiThread { data = value } }
}
