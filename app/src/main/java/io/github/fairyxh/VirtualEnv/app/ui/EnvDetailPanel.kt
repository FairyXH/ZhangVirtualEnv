package io.github.fairyxh.VirtualEnv.app.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.app.ApiClient
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassBackdropHost
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassButton
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassCard
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassField
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassPill
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassTextDialog
import io.github.fairyxh.VirtualEnv.app.ui.glass.glassColors
import io.github.fairyxh.VirtualEnv.core.model.ApiResult
import io.github.fairyxh.VirtualEnv.util.DefaultNames
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

private const val TYPE_CELL = "cell"
private const val TYPE_WIFI = "wifi"
private const val TYPE_BLE = "ble"
private const val TYPE_SENSOR = "sensor"
private const val TYPE_GNSS = "gnss"

private fun envTitleRes(type: String): Int = when (type) {
    TYPE_CELL -> R.string.env_cell_title
    TYPE_WIFI -> R.string.env_wifi_title
    TYPE_BLE -> R.string.env_ble_title
    TYPE_SENSOR -> R.string.env_sensor_title
    TYPE_GNSS -> R.string.env_gnss_title
    else -> R.string.env_title
}

/**
 * 环境子页面（基站 / WiFi / 蓝牙 / 传感器 / GNSS 详细管理）。
 *
 * 已从独立 Activity 迁移为 EnvFragment 的子页面：由 EnvFragment 切换显示，
 * 黑底阶段继承主界面黑底；返回按钮为悬浮圆形液态玻璃按钮，滚动时永远可见。
 */
@Composable
fun EnvDetailPanel(
    fragment: EnvFragment,
    type: String,
    onBack: () -> Unit
) {
    val context = fragment.requireContext()

    // ---------- 表单状态 ----------
    var detailTitle by remember { mutableStateOf(fragment.getString(envTitleRes(type))) }
    var detailStatus by remember { mutableStateOf("") }
    var detailDialog by remember { mutableStateOf<JSONObject?>(null) }
    var cellType by remember { mutableStateOf("") }
    var cellMcc by remember { mutableStateOf("") }
    var cellMnc by remember { mutableStateOf("") }
    var cellTac by remember { mutableStateOf("") }
    var cellCi by remember { mutableStateOf("") }
    var cellPci by remember { mutableStateOf("") }
    var cellRsrp by remember { mutableStateOf("") }
    var wifiSsid by remember { mutableStateOf("") }
    var wifiBssid by remember { mutableStateOf("") }
    var wifiRssi by remember { mutableStateOf("") }
    var wifiFrequency by remember { mutableStateOf("") }
    var bleName by remember { mutableStateOf("") }
    var bleAddress by remember { mutableStateOf("") }
    var bleRssi by remember { mutableStateOf("") }
    var sensorStep by remember { mutableStateOf("") }
    var gnssCount by remember { mutableStateOf("") }
    var gnssUsed by remember { mutableStateOf("") }
    var gnssCn0 by remember { mutableStateOf("") }
    var saveName by remember { mutableStateOf(DefaultNames.timeName(detailTitle)) }
    var saveRemark by remember { mutableStateOf("") }

    val entries = remember { mutableStateListOf<JSONObject>() }
    val savedItems = remember { mutableStateListOf<JSONObject>() }
    var savedEmptyVisible by remember { mutableStateOf(false) }
    var activeSnapshotId by remember { mutableStateOf(-1L) }

    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    // ---------- 条目编辑 ----------
    fun readEntryForm(): JSONObject? {
        return when (type) {
            TYPE_CELL -> {
                val obj = JSONObject().apply {
                    val netType = cellType.trim()
                    put("type", if (netType.isEmpty()) "LTE" else netType)
                    put("mcc", cellMcc.toIntOrNull() ?: -1)
                    put("mnc", cellMnc.toIntOrNull() ?: -1)
                    put("tac", cellTac.toIntOrNull() ?: -1)
                    put("ci", cellCi.toLongOrNull() ?: -1L)
                    put("pci", cellPci.toIntOrNull() ?: -1)
                    put("rsrp", cellRsrp.toIntOrNull() ?: -1)
                }
                if (obj.optInt("mcc", -1) < 0) {
                    toast(fragment.getString(R.string.env_cell_mcc_required))
                    return null
                }
                obj
            }
            TYPE_WIFI -> {
                val ssid = wifiSsid.trim()
                if (ssid.isEmpty()) {
                    toast(fragment.getString(R.string.env_wifi_ssid_required))
                    return null
                }
                JSONObject().apply {
                    put("ssid", ssid)
                    put("bssid", wifiBssid.trim())
                    put("rssi", wifiRssi.toIntOrNull() ?: -60)
                    put("frequency", wifiFrequency.toIntOrNull() ?: 2412)
                }
            }
            TYPE_BLE -> {
                val address = bleAddress.trim()
                if (address.isEmpty()) {
                    toast(fragment.getString(R.string.env_ble_address_required))
                    return null
                }
                JSONObject().apply {
                    put("name", bleName.trim())
                    put("address", address)
                    put("rssi", bleRssi.toIntOrNull() ?: -70)
                }
            }
            else -> null
        }
    }

    fun clearEntryForm() {
        when (type) {
            TYPE_CELL -> {
                cellType = ""
                cellMcc = ""
                cellMnc = ""
                cellTac = ""
                cellCi = ""
                cellPci = ""
                cellRsrp = ""
            }
            TYPE_WIFI -> {
                wifiSsid = ""
                wifiBssid = ""
                wifiRssi = ""
                wifiFrequency = ""
            }
            TYPE_BLE -> {
                bleName = ""
                bleAddress = ""
                bleRssi = ""
            }
        }
    }

    fun entrySummary(entry: JSONObject): String {
        return when (type) {
            TYPE_CELL -> {
                val typeStr = entry.optString("type", "LTE")
                val mcc = entry.optInt("mcc", -1)
                val mnc = entry.optInt("mnc", -1)
                val tac = entry.optInt("tac", -1)
                val ci = entry.optLong("ci", -1L)
                fragment.getString(R.string.env_cell_entry_format, typeStr, mcc, mnc, tac, ci)
            }
            TYPE_WIFI -> {
                val ssid = entry.optString("ssid", "")
                val bssid = entry.optString("bssid", "")
                val rssi = entry.optInt("rssi", -60)
                fragment.getString(R.string.env_wifi_entry_format, ssid, bssid, rssi)
            }
            TYPE_BLE -> {
                val name = entry.optString("name", "").ifEmpty { entry.optString("address", "") }
                val address = entry.optString("address", "")
                val rssi = entry.optInt("rssi", -70)
                fragment.getString(R.string.env_ble_entry_format, name, address, rssi)
            }
            else -> entry.toString()
        }
    }

    fun addEntry() {
        val entry = readEntryForm() ?: return
        entries.add(entry)
        clearEntryForm()
    }

    // ---------- 保存 / 使用 / 删除 ----------
    fun buildConfigData(): JSONObject? {
        return when (type) {
            TYPE_CELL -> JSONObject().apply { put("entries", JSONArray(entries.toList())) }
            TYPE_WIFI -> JSONObject().apply { put("networks", JSONArray(entries.toList())) }
            TYPE_BLE -> JSONObject().apply { put("devices", JSONArray(entries.toList())) }
            TYPE_SENSOR -> {
                val step = sensorStep.toIntOrNull()
                if (step == null || step <= 0) {
                    toast(fragment.getString(R.string.env_sensor_step_hint))
                    return null
                }
                JSONObject().apply { put("stepFrequency", step) }
            }
            TYPE_GNSS -> JSONObject().apply {
                put("satelliteCount", gnssCount.toIntOrNull() ?: -1)
                put("usedInFix", gnssUsed.toIntOrNull() ?: -1)
                put("cn0", gnssCn0.toDoubleOrNull() ?: -1.0)
            }
            else -> null
        }
    }

    fun refreshStatus() {
        executor.execute {
            val result = ApiClient.getEnvStatus(type)
            fragment.requireActivity().runOnUiThread {
                val data = result.data
                val enabled = data != null && data.optBoolean("enabled", false)
                detailStatus = fragment.getString(
                    if (enabled) R.string.env_detail_active else R.string.env_detail_inactive
                )
            }
        }
    }

    fun renderSaved(result: ApiResult) {
        savedItems.clear()
        val snapshots = result.data?.optJSONArray("snapshots") ?: return
        val items = mutableListOf<JSONObject>()
        for (i in 0 until snapshots.length()) {
            val item = snapshots.optJSONObject(i) ?: continue
            if (item.optString("type", "") == type) items.add(item)
        }
        savedEmptyVisible = items.isEmpty()
        savedItems.addAll(items)
    }

    fun refreshSaved() {
        executor.execute {
            val result = ApiClient.listEnvSnapshots()
            val status = ApiClient.getEnvStatus(type)
            val activeId = status.data?.optLong("activeSnapshotId", -1L) ?: -1L
            fragment.requireActivity().runOnUiThread {
                activeSnapshotId = activeId
                renderSaved(result)
            }
        }
    }

    fun formatConfigData(data: JSONObject?): String {
        if (data == null) return fragment.getString(R.string.env_saved_detail_empty)
        val sb = StringBuilder()
        when (type) {
            TYPE_CELL -> {
                val arr = data.optJSONArray("entries") ?: JSONArray()
                sb.append("基站 ").append(arr.length()).append(" 个\n")
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    sb.append("\n#").append(i + 1).append("  ")
                        .append(e.optString("type", "LTE"))
                        .append("  MCC=").append(e.optInt("mcc", -1))
                        .append(" MNC=").append(e.optInt("mnc", -1))
                        .append(" TAC=").append(e.optInt("tac", -1))
                        .append(" CI=").append(e.optLong("ci", e.optLong("nci", -1L)))
                        .append(" PCI=").append(e.optInt("pci", -1))
                        .append(" RSRP=").append(e.optInt("rsrp", -1))
                }
            }
            TYPE_WIFI -> {
                val arr = data.optJSONArray("networks") ?: JSONArray()
                sb.append("WiFi ").append(arr.length()).append(" 个\n")
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    sb.append("\n#").append(i + 1).append("  ")
                        .append(e.optString("ssid", ""))
                        .append(" (").append(e.optString("bssid", ""))
                        .append(") RSSI=").append(e.optInt("rssi", -70))
                        .append(" Freq=").append(e.optInt("frequency", 2412))
                }
            }
            TYPE_BLE -> {
                val arr = data.optJSONArray("devices") ?: JSONArray()
                sb.append("蓝牙设备 ").append(arr.length()).append(" 个\n")
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    sb.append("\n#").append(i + 1).append("  ")
                        .append(e.optString("name", "").ifEmpty { e.optString("address", "") })
                        .append(" (").append(e.optString("address", ""))
                        .append(") RSSI=").append(e.optInt("rssi", -70))
                }
            }
            TYPE_SENSOR -> {
                sb.append("步频：").append(data.optInt("stepFrequency", 0)).append(" 步/分\n")
            }
            TYPE_GNSS -> {
                sb.append("卫星总数：").append(data.optInt("satelliteCount", -1)).append("\n")
                sb.append("参与定位：").append(data.optInt("usedInFix", -1)).append("\n")
                sb.append("平均信噪比：").append(data.optDouble("cn0", -1.0)).append(" dBHz\n")
            }
            else -> sb.append(data.toString(2))
        }
        return sb.toString()
    }

    fun saveConfig() {
        val name = saveName.trim()
        if (name.isEmpty()) {
            toast(fragment.getString(R.string.location_point_name_required))
            return
        }
        val remark = saveRemark.trim()
        val data = buildConfigData() ?: return
        executor.execute {
            val result = ApiClient.createEnvSnapshot(name, remark, type, data)
            fragment.requireActivity().runOnUiThread {
                toast(result.message)
                if (result.code == ApiResult.CODE_OK) {
                    saveName = DefaultNames.timeName(detailTitle)
                    saveRemark = ""
                    entries.clear()
                    if (type == TYPE_SENSOR) {
                        sensorStep = ""
                    }
                    refreshSaved()
                }
            }
        }
    }

    /** 一键使用 = 切换到该配置（Hook 层随即生效）。 */
    fun useConfig(item: JSONObject) {
        val id = item.optLong("id")
        executor.execute {
            val result = ApiClient.useEnvSnapshot(id)
            fragment.requireActivity().runOnUiThread {
                toast(result.message)
                refreshStatus()
                refreshSaved()
            }
        }
    }

    fun deleteConfig(id: Long) {
        executor.execute {
            val result = ApiClient.deleteEnvSnapshot(id)
            fragment.requireActivity().runOnUiThread {
                toast(result.message)
                if (result.code == ApiResult.CODE_OK) refreshSaved()
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshSaved()
        refreshStatus()
    }

    @Composable
    fun RenderEntriesLocal(backdrop: Backdrop) {
        val colors = glassColors()
        entries.forEachIndexed { index, entry ->
            Row(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    entrySummary(entry),
                    Modifier.weight(1f),
                    style = TextStyle(color = colors.textPrimary, fontSize = 12.sp)
                )
                GlassPill(
                    onClick = { entries.removeAt(index) },
                    backdrop = backdrop,
                    selected = false,
                    containerColor = colors.danger.copy(alpha = 0.25f),
                    height = 28.dp
                ) {
                    BasicText(
                        fragment.getString(R.string.env_detail_remove),
                        Modifier.padding(horizontal = 10.dp),
                        style = TextStyle(color = colors.danger, fontSize = 11.sp)
                    )
                }
            }
        }
    }

    @Composable
    fun SavedItemRowLocal(item: JSONObject, backdrop: Backdrop) {
        val colors = glassColors()
        val isActive = item.optLong("id", -1L) == activeSnapshotId
        Row(
            Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                if (isActive) {
                    fragment.getString(R.string.env_detail_in_use_badge) + " " + item.optString("name", "")
                } else {
                    item.optString("name", "")
                },
                Modifier.weight(1f),
                style = TextStyle(
                    color = if (isActive) colors.accent else colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                )
            )
            GlassPill(
                onClick = { detailDialog = item },
                backdrop = backdrop,
                modifier = Modifier.padding(end = 4.dp),
                selected = false,
                containerColor = colors.bgTertiary.copy(alpha = 0.35f),
                height = 28.dp
            ) {
                BasicText(
                    fragment.getString(R.string.env_detail_view),
                    Modifier.padding(horizontal = 10.dp),
                    style = TextStyle(color = colors.textSecondary, fontSize = 11.sp)
                )
            }
            GlassPill(
                onClick = { useConfig(item) },
                backdrop = backdrop,
                modifier = Modifier.padding(end = 4.dp),
                selected = false,
                containerColor = colors.accent.copy(alpha = 0.2f),
                height = 28.dp
            ) {
                BasicText(
                    fragment.getString(R.string.env_use),
                    Modifier.padding(horizontal = 10.dp),
                    style = TextStyle(color = colors.accent, fontSize = 11.sp)
                )
            }
            GlassPill(
                onClick = { deleteConfig(item.optLong("id")) },
                backdrop = backdrop,
                selected = false,
                containerColor = colors.danger.copy(alpha = 0.25f),
                height = 28.dp
            ) {
                BasicText(
                    fragment.getString(R.string.env_delete),
                    Modifier.padding(horizontal = 10.dp),
                    style = TextStyle(color = colors.danger, fontSize = 11.sp)
                )
            }
        }
    }

    // ---------- UI ----------
    GlassBackdropHost(
        modifier = Modifier.fillMaxSize()
    ) { backdrop ->
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 130.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val colors = glassColors()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 52.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(
                        detailTitle,
                        Modifier.weight(1f),
                        style = TextStyle(
                            color = colors.textPrimary,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp
                        )
                    )
                    GlassPill(
                        onClick = {},
                        backdrop = backdrop,
                        selected = false,
                        containerColor = colors.bgTertiary.copy(alpha = 0.4f),
                        height = 30.dp
                    ) {
                        BasicText(
                            detailStatus,
                            Modifier.padding(horizontal = 12.dp),
                            style = TextStyle(color = colors.textSecondary, fontSize = 12.sp)
                        )
                    }
                }

                when (type) {
                    TYPE_CELL -> {
                        GlassCard(
                            backdrop = backdrop,
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                BasicText(
                                    fragment.getString(R.string.env_detail_entries_title),
                                    style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                )
                                BasicText(
                                    fragment.getString(R.string.env_detail_entries_desc),
                                    Modifier.padding(top = 4.dp),
                                    style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                                )
                                EntryFormField("type", cellType, { cellType = it }, fragment.getString(R.string.env_cell_type_hint), backdrop)
                                EntryFormField("mcc", cellMcc, { cellMcc = it }, fragment.getString(R.string.env_cell_mcc_hint), backdrop)
                                EntryFormField("mnc", cellMnc, { cellMnc = it }, fragment.getString(R.string.env_cell_mnc_hint), backdrop)
                                EntryFormField("tac", cellTac, { cellTac = it }, fragment.getString(R.string.env_cell_tac_hint), backdrop)
                                EntryFormField("ci", cellCi, { cellCi = it }, fragment.getString(R.string.env_cell_ci_hint), backdrop)
                                EntryFormField("pci", cellPci, { cellPci = it }, fragment.getString(R.string.env_cell_pci_hint), backdrop)
                                EntryFormField("rsrp", cellRsrp, { cellRsrp = it }, fragment.getString(R.string.env_cell_rsrp_hint), backdrop)
                                GlassButton(
                                    onClick = { addEntry() },
                                    backdrop = backdrop,
                                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                                    surfaceColor = colors.bgTertiary.copy(alpha = 0.4f)
                                ) {
                                    BasicText(
                                        fragment.getString(R.string.env_detail_add_entry),
                                        style = TextStyle(color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    )
                                }
                                RenderEntriesLocal(backdrop)
                            }
                        }
                    }
                    TYPE_WIFI -> {
                        GlassCard(
                            backdrop = backdrop,
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                BasicText(
                                    fragment.getString(R.string.env_detail_entries_title),
                                    style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                )
                                BasicText(
                                    fragment.getString(R.string.env_detail_entries_desc),
                                    Modifier.padding(top = 4.dp),
                                    style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                                )
                                EntryFormField("ssid", wifiSsid, { wifiSsid = it }, fragment.getString(R.string.env_wifi_ssid_hint), backdrop)
                                EntryFormField("bssid", wifiBssid, { wifiBssid = it }, fragment.getString(R.string.env_wifi_bssid_hint), backdrop)
                                EntryFormField("rssi", wifiRssi, { wifiRssi = it }, fragment.getString(R.string.env_wifi_rssi_hint), backdrop)
                                EntryFormField("frequency", wifiFrequency, { wifiFrequency = it }, fragment.getString(R.string.env_wifi_frequency_hint), backdrop)
                                GlassButton(
                                    onClick = { addEntry() },
                                    backdrop = backdrop,
                                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                                    surfaceColor = colors.bgTertiary.copy(alpha = 0.4f)
                                ) {
                                    BasicText(
                                        fragment.getString(R.string.env_detail_add_entry),
                                        style = TextStyle(color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    )
                                }
                                RenderEntriesLocal(backdrop)
                            }
                        }
                    }
                    TYPE_BLE -> {
                        GlassCard(
                            backdrop = backdrop,
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                BasicText(
                                    fragment.getString(R.string.env_detail_entries_title),
                                    style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                )
                                BasicText(
                                    fragment.getString(R.string.env_detail_entries_desc),
                                    Modifier.padding(top = 4.dp),
                                    style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                                )
                                EntryFormField("name", bleName, { bleName = it }, fragment.getString(R.string.env_ble_name_hint), backdrop)
                                EntryFormField("address", bleAddress, { bleAddress = it }, fragment.getString(R.string.env_ble_address_hint), backdrop)
                                EntryFormField("rssi", bleRssi, { bleRssi = it }, fragment.getString(R.string.env_ble_rssi_hint), backdrop)
                                GlassButton(
                                    onClick = { addEntry() },
                                    backdrop = backdrop,
                                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                                    surfaceColor = colors.bgTertiary.copy(alpha = 0.4f)
                                ) {
                                    BasicText(
                                        fragment.getString(R.string.env_detail_add_entry),
                                        style = TextStyle(color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    )
                                }
                                RenderEntriesLocal(backdrop)
                            }
                        }
                    }
                    TYPE_SENSOR -> {
                        GlassCard(
                            backdrop = backdrop,
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                BasicText(
                                    fragment.getString(R.string.env_sensor_title),
                                    style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                )
                                EntryFormField("step", sensorStep, { sensorStep = it }, fragment.getString(R.string.env_sensor_step_hint), backdrop)
                            }
                        }
                    }
                    TYPE_GNSS -> {
                        GlassCard(
                            backdrop = backdrop,
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                BasicText(
                                    fragment.getString(R.string.env_gnss_title),
                                    style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                )
                                EntryFormField("count", gnssCount, { gnssCount = it }, fragment.getString(R.string.env_gnss_count_hint), backdrop)
                                EntryFormField("used", gnssUsed, { gnssUsed = it }, fragment.getString(R.string.env_gnss_used_hint), backdrop)
                                EntryFormField("cn0", gnssCn0, { gnssCn0 = it }, fragment.getString(R.string.env_gnss_cn0_hint), backdrop)
                            }
                        }
                    }
                }

                // 保存卡
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        BasicText(
                            fragment.getString(R.string.env_detail_save_title),
                            style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        )
                        GlassField(
                            value = saveName,
                            onValueChange = { saveName = it },
                            backdrop = backdrop,
                            modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                            placeholder = fragment.getString(R.string.env_detail_name_hint)
                        )
                        GlassField(
                            value = saveRemark,
                            onValueChange = { saveRemark = it },
                            backdrop = backdrop,
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                            placeholder = fragment.getString(R.string.env_detail_remark_hint)
                        )
                        GlassButton(
                            onClick = { saveConfig() },
                            backdrop = backdrop,
                            modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                            tint = colors.accent
                        ) {
                            BasicText(
                                fragment.getString(R.string.env_save),
                                style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }

                // 已保存配置卡
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        BasicText(
                            fragment.getString(R.string.env_detail_saved_title),
                            style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        )
                        if (savedEmptyVisible) {
                            BasicText(
                                fragment.getString(R.string.env_saved_empty),
                                Modifier.padding(top = 8.dp),
                                style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                            )
                        } else {
                            savedItems.forEach { item ->
                                SavedItemRowLocal(item, backdrop)
                            }
                        }
                    }
                }
            }

            // 悬浮圆形液态玻璃返回按钮：永远可见（不随内容滚动）
            GlassCircleBackButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 8.dp)
            )
        }

        detailDialog?.let { item ->
            GlassTextDialog(
                title = fragment.getString(R.string.env_detail_data_title) + " · " + item.optString("name", ""),
                text = formatConfigData(item.optJSONObject("data")),
                onDismiss = { detailDialog = null }
            )
        }
    }
}

@Composable
private fun EntryFormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    backdrop: Backdrop
) {
    val colors = glassColors()
    Column(Modifier.padding(top = 8.dp).fillMaxWidth()) {
        BasicText(
            label,
            style = TextStyle(color = colors.textSecondary, fontSize = 12.sp)
        )
        GlassField(
            value = value,
            onValueChange = onValueChange,
            backdrop = backdrop,
            modifier = Modifier.padding(top = 2.dp).fillMaxWidth(),
            placeholder = placeholder
        )
    }
}

@Composable
private fun GlassCircleBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = glassColors()
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .drawBehind {
                // 液态玻璃圆钮：半透明底 + 顶部高光 + 内阴影 + 描边
                drawCircle(colors.bgTertiary.copy(alpha = 0.45f))
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(size.width * 0.32f, size.height * 0.24f),
                        radius = size.maxDimension * 0.9f
                    ),
                    radius = size.maxDimension * 0.9f,
                    center = Offset(size.width * 0.32f, size.height * 0.24f)
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.18f),
                    style = Stroke(width = 1.dp.toPx())
                )
            },
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            "←",
            style = TextStyle(color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        )
    }
}
