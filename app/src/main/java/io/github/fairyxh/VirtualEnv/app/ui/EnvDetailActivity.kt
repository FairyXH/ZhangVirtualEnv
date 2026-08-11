package io.github.fairyxh.VirtualEnv.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 环境模拟子页面：基站 / WiFi / GNSS 详细管理。
 *
 * - cell：支持多个基站条目（保存为 data.entries[]，Hook 层 getAllCellInfo 返回多个虚拟基站）
 * - wifi：支持多个 WiFi 条目（保存为 data.networks[]）
 * - gnss：详细设置（卫星总数 / 使用中 / 平均 CN0）
 *
 * 已保存配置列表即“多配置切换”：点使用加载到对应引擎。
 *
 * 视图层已迁移到 Compose Liquid Glass，业务逻辑不变。
 */
class EnvDetailActivity : ComponentActivity() {

    companion object {
        private const val TAG_SCOPE = "UI"
        const val EXTRA_TYPE = "env_type"
        const val TYPE_CELL = "cell"
        const val TYPE_WIFI = "wifi"
        const val TYPE_BLE = "ble"
        const val TYPE_SENSOR = "sensor"
        const val TYPE_GNSS = "gnss"

        fun start(context: Context, type: String) {
            context.startActivity(
                Intent(context, EnvDetailActivity::class.java)
                    .putExtra(EXTRA_TYPE, type)
            )
        }
    }

    private lateinit var type: String

    // ---------- Compose 视图状态 ----------

    private var detailTitle by mutableStateOf("")
    private var detailStatus by mutableStateOf("")
    private var detailDialog by mutableStateOf<JSONObject?>(null)
    // cell 表单
    private var cellType by mutableStateOf("")
    private var cellMcc by mutableStateOf("")
    private var cellMnc by mutableStateOf("")
    private var cellTac by mutableStateOf("")
    private var cellCi by mutableStateOf("")
    private var cellPci by mutableStateOf("")
    private var cellRsrp by mutableStateOf("")

    // wifi 表单
    private var wifiSsid by mutableStateOf("")
    private var wifiBssid by mutableStateOf("")
    private var wifiRssi by mutableStateOf("")
    private var wifiFrequency by mutableStateOf("")

    // ble 表单
    private var bleName by mutableStateOf("")
    private var bleAddress by mutableStateOf("")
    private var bleRssi by mutableStateOf("")

    // sensor / gnss 表单
    private var sensorStep by mutableStateOf("")
    private var gnssCount by mutableStateOf("")
    private var gnssUsed by mutableStateOf("")
    private var gnssCn0 by mutableStateOf("")

    private var saveName by mutableStateOf("")
    private var saveRemark by mutableStateOf("")

    /** 当前组合条目（cell/wifi/ble 多条目；gnss/sensor 为单配置）。 */
    private val entries = mutableStateListOf<JSONObject>()
    private val savedItems = mutableStateListOf<JSONObject>()
    private var savedEmptyVisible by mutableStateOf(false)

    /** 当前正在使用的配置 id（-1 表示无）。 */
    private var activeSnapshotId = -1L

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        type = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_CELL

        detailTitle = when (type) {
            TYPE_CELL -> getString(R.string.env_cell_title)
            TYPE_WIFI -> getString(R.string.env_wifi_title)
            TYPE_BLE -> getString(R.string.env_ble_title)
            TYPE_SENSOR -> getString(R.string.env_sensor_title)
            TYPE_GNSS -> getString(R.string.env_gnss_title)
            else -> type
        }

        // 输入框默认值：配置名称默认时间
        saveName = io.github.fairyxh.VirtualEnv.util.DefaultNames.timeName(detailTitle)

        setContent {
            DetailScreen(this)
        }
        refreshSaved()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshSaved()
        refreshStatus()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    // ---------- Compose UI ----------

    @Composable
    private fun DetailScreen(activity: EnvDetailActivity) {
        GlassBackdropHost(
            modifier = Modifier
                .fillMaxSize()
        ) { backdrop ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val colors = glassColors()
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassPill(
                        onClick = { activity.finish() },
                        backdrop = backdrop,
                        selected = false,
                        containerColor = colors.bgTertiary.copy(alpha = 0.4f),
                        height = 36.dp
                    ) {
                        BasicText(
                            getString(R.string.env_detail_back),
                            Modifier.padding(horizontal = 16.dp),
                            style = TextStyle(color = colors.textPrimary, fontSize = 13.sp)
                        )
                    }
                    BasicText(
                        detailTitle,
                        Modifier.padding(start = 12.dp).weight(1f),
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
                                    getString(R.string.env_detail_entries_title),
                                    style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                )
                                BasicText(
                                    getString(R.string.env_detail_entries_desc),
                                    Modifier.padding(top = 4.dp),
                                    style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                                )
                                EntryFormField("type", cellType, { cellType = it }, getString(R.string.env_cell_type_hint), backdrop)
                                EntryFormField("mcc", cellMcc, { cellMcc = it }, getString(R.string.env_cell_mcc_hint), backdrop)
                                EntryFormField("mnc", cellMnc, { cellMnc = it }, getString(R.string.env_cell_mnc_hint), backdrop)
                                EntryFormField("tac", cellTac, { cellTac = it }, getString(R.string.env_cell_tac_hint), backdrop)
                                EntryFormField("ci", cellCi, { cellCi = it }, getString(R.string.env_cell_ci_hint), backdrop)
                                EntryFormField("pci", cellPci, { cellPci = it }, getString(R.string.env_cell_pci_hint), backdrop)
                                EntryFormField("rsrp", cellRsrp, { cellRsrp = it }, getString(R.string.env_cell_rsrp_hint), backdrop)
                                GlassButton(
                                    onClick = { activity.addEntry() },
                                    backdrop = backdrop,
                                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                                    surfaceColor = colors.bgTertiary.copy(alpha = 0.4f)
                                ) {
                                    BasicText(
                                        getString(R.string.env_detail_add_entry),
                                        style = TextStyle(color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    )
                                }
                                RenderEntries(backdrop)
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
                                    getString(R.string.env_detail_entries_title),
                                    style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                )
                                BasicText(
                                    getString(R.string.env_detail_entries_desc),
                                    Modifier.padding(top = 4.dp),
                                    style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                                )
                                EntryFormField("ssid", wifiSsid, { wifiSsid = it }, getString(R.string.env_wifi_ssid_hint), backdrop)
                                EntryFormField("bssid", wifiBssid, { wifiBssid = it }, getString(R.string.env_wifi_bssid_hint), backdrop)
                                EntryFormField("rssi", wifiRssi, { wifiRssi = it }, getString(R.string.env_wifi_rssi_hint), backdrop)
                                EntryFormField("frequency", wifiFrequency, { wifiFrequency = it }, getString(R.string.env_wifi_frequency_hint), backdrop)
                                GlassButton(
                                    onClick = { activity.addEntry() },
                                    backdrop = backdrop,
                                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                                    surfaceColor = colors.bgTertiary.copy(alpha = 0.4f)
                                ) {
                                    BasicText(
                                        getString(R.string.env_detail_add_entry),
                                        style = TextStyle(color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    )
                                }
                                RenderEntries(backdrop)
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
                                    getString(R.string.env_detail_entries_title),
                                    style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                )
                                BasicText(
                                    getString(R.string.env_detail_entries_desc),
                                    Modifier.padding(top = 4.dp),
                                    style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                                )
                                EntryFormField("name", bleName, { bleName = it }, getString(R.string.env_ble_name_hint), backdrop)
                                EntryFormField("address", bleAddress, { bleAddress = it }, getString(R.string.env_ble_address_hint), backdrop)
                                EntryFormField("rssi", bleRssi, { bleRssi = it }, getString(R.string.env_ble_rssi_hint), backdrop)
                                GlassButton(
                                    onClick = { activity.addEntry() },
                                    backdrop = backdrop,
                                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                                    surfaceColor = colors.bgTertiary.copy(alpha = 0.4f)
                                ) {
                                    BasicText(
                                        getString(R.string.env_detail_add_entry),
                                        style = TextStyle(color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    )
                                }
                                RenderEntries(backdrop)
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
                                    getString(R.string.env_sensor_title),
                                    style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                )
                                EntryFormField("step", sensorStep, { sensorStep = it }, getString(R.string.env_sensor_step_hint), backdrop)
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
                                    getString(R.string.env_gnss_title),
                                    style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                )
                                EntryFormField("count", gnssCount, { gnssCount = it }, getString(R.string.env_gnss_count_hint), backdrop)
                                EntryFormField("used", gnssUsed, { gnssUsed = it }, getString(R.string.env_gnss_used_hint), backdrop)
                                EntryFormField("cn0", gnssCn0, { gnssCn0 = it }, getString(R.string.env_gnss_cn0_hint), backdrop)
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
                            getString(R.string.env_detail_save_title),
                            style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        )
                        GlassField(
                            value = saveName,
                            onValueChange = { saveName = it },
                            backdrop = backdrop,
                            modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                            placeholder = getString(R.string.env_detail_name_hint)
                        )
                        GlassField(
                            value = saveRemark,
                            onValueChange = { saveRemark = it },
                            backdrop = backdrop,
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                            placeholder = getString(R.string.env_detail_remark_hint)
                        )
                        GlassButton(
                            onClick = { activity.saveConfig() },
                            backdrop = backdrop,
                            modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                            tint = colors.accent
                        ) {
                            BasicText(
                                getString(R.string.env_save),
                                style = TextStyle(color = androidx.compose.ui.graphics.Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
                            getString(R.string.env_detail_saved_title),
                            style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        )
                        if (savedEmptyVisible) {
                            BasicText(
                                getString(R.string.env_saved_empty),
                                Modifier.padding(top = 8.dp),
                                style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                            )
                        } else {
                            savedItems.forEach { item ->
                                SavedItemRow(item, backdrop, activity)
                            }
                        }
                    }
                }
            }
            detailDialog?.let { item ->
                GlassTextDialog(
                    title = getString(R.string.env_detail_data_title) + " · " + item.optString("name", ""),
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
        backdrop: com.kyant.backdrop.Backdrop
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
    private fun RenderEntries(backdrop: com.kyant.backdrop.Backdrop) {
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
                    onClick = {
                        entries.removeAt(index)
                    },
                    backdrop = backdrop,
                    selected = false,
                    containerColor = colors.danger.copy(alpha = 0.25f),
                    height = 28.dp
                ) {
                    BasicText(
                        getString(R.string.env_detail_remove),
                        Modifier.padding(horizontal = 10.dp),
                        style = TextStyle(color = colors.danger, fontSize = 11.sp)
                    )
                }
            }
        }
    }

    @Composable
    private fun SavedItemRow(
        item: JSONObject,
        backdrop: com.kyant.backdrop.Backdrop,
        activity: EnvDetailActivity
    ) {
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
                    getString(R.string.env_detail_in_use_badge) + " " + item.optString("name", "")
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
                onClick = { activity.showDetailDialog(item) },
                backdrop = backdrop,
                modifier = Modifier.padding(end = 4.dp),
                selected = false,
                containerColor = colors.bgTertiary.copy(alpha = 0.35f),
                height = 28.dp
            ) {
                BasicText(
                    getString(R.string.env_detail_view),
                    Modifier.padding(horizontal = 10.dp),
                    style = TextStyle(color = colors.textSecondary, fontSize = 11.sp)
                )
            }
            GlassPill(
                onClick = { activity.useConfig(item) },
                backdrop = backdrop,
                modifier = Modifier.padding(end = 4.dp),
                selected = false,
                containerColor = colors.accent.copy(alpha = 0.2f),
                height = 28.dp
            ) {
                BasicText(
                    getString(R.string.env_use),
                    Modifier.padding(horizontal = 10.dp),
                    style = TextStyle(color = colors.accent, fontSize = 11.sp)
                )
            }
            GlassPill(
                onClick = { activity.deleteConfig(item.optLong("id")) },
                backdrop = backdrop,
                selected = false,
                containerColor = colors.danger.copy(alpha = 0.25f),
                height = 28.dp
            ) {
                BasicText(
                    getString(R.string.env_delete),
                    Modifier.padding(horizontal = 10.dp),
                    style = TextStyle(color = colors.danger, fontSize = 11.sp)
                )
            }
        }
    }

    // ---------- 条目编辑 ----------

    private fun addEntry() {
        val entry = readEntryForm() ?: return
        entries.add(entry)
        clearEntryForm()
    }

    private fun readEntryForm(): JSONObject? {
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
                    Toast.makeText(this, R.string.env_cell_mcc_required, Toast.LENGTH_SHORT).show()
                    return null
                }
                obj
            }
            TYPE_WIFI -> {
                val ssid = wifiSsid.trim()
                if (ssid.isEmpty()) {
                    Toast.makeText(this, R.string.env_wifi_ssid_required, Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this, R.string.env_ble_address_required, Toast.LENGTH_SHORT).show()
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

    private fun clearEntryForm() {
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

    private fun entrySummary(entry: JSONObject): String {
        return when (type) {
            TYPE_CELL -> {
                val typeStr = entry.optString("type", "LTE")
                val mcc = entry.optInt("mcc", -1)
                val mnc = entry.optInt("mnc", -1)
                val tac = entry.optInt("tac", -1)
                val ci = entry.optLong("ci", -1L)
                getString(R.string.env_cell_entry_format, typeStr, mcc, mnc, tac, ci)
            }
            TYPE_WIFI -> {
                val ssid = entry.optString("ssid", "")
                val bssid = entry.optString("bssid", "")
                val rssi = entry.optInt("rssi", -60)
                getString(R.string.env_wifi_entry_format, ssid, bssid, rssi)
            }
            TYPE_BLE -> {
                val name = entry.optString("name", "").ifEmpty { entry.optString("address", "") }
                val address = entry.optString("address", "")
                val rssi = entry.optInt("rssi", -70)
                getString(R.string.env_ble_entry_format, name, address, rssi)
            }
            else -> entry.toString()
        }
    }

    // ---------- 保存 / 使用 / 删除 ----------

    private fun saveConfig() {
        val name = saveName.trim()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.location_point_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        val remark = saveRemark.trim()
        val data = buildConfigData() ?: return
        executor.execute {
            val result = ApiClient.createEnvSnapshot(name, remark, type, data)
            runOnUiThread {
                Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                if (result.code == ApiResult.CODE_OK) {
                    saveName = io.github.fairyxh.VirtualEnv.util.DefaultNames.timeName(detailTitle)
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

    private fun buildConfigData(): JSONObject? {
        return when (type) {
            TYPE_CELL -> JSONObject().apply { put("entries", JSONArray(entries.toList())) }
            TYPE_WIFI -> JSONObject().apply { put("networks", JSONArray(entries.toList())) }
            TYPE_BLE -> JSONObject().apply { put("devices", JSONArray(entries.toList())) }
            TYPE_SENSOR -> {
                val step = sensorStep.toIntOrNull()
                if (step == null || step <= 0) {
                    Toast.makeText(this, R.string.env_sensor_step_hint, Toast.LENGTH_SHORT).show()
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

    private fun refreshSaved() {
        executor.execute {
            val result = ApiClient.listEnvSnapshots()
            val status = ApiClient.getEnvStatus(type)
            val activeId = status.data?.optLong("activeSnapshotId", -1L) ?: -1L
            runOnUiThread {
                activeSnapshotId = activeId
                renderSaved(result)
            }
        }
    }

    private fun renderSaved(result: ApiResult) {
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

    /** 配置详情弹窗：展示保存的完整数据（液态玻璃样式）。 */
    private fun showDetailDialog(item: JSONObject) {
        detailDialog = item
    }

    private fun formatConfigData(data: JSONObject?): String {
        if (data == null) return getString(R.string.env_saved_detail_empty)
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

    /** 一键使用 = 切换到该配置（Hook 层随即生效）。 */
    private fun useConfig(item: JSONObject) {
        val id = item.optLong("id")
        executor.execute {
            val result = ApiClient.useEnvSnapshot(id)
            runOnUiThread {
                Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                refreshStatus()
                refreshSaved()
            }
        }
    }

    private fun deleteConfig(id: Long) {
        executor.execute {
            val result = ApiClient.deleteEnvSnapshot(id)
            runOnUiThread {
                Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                if (result.code == ApiResult.CODE_OK) refreshSaved()
            }
        }
    }

    private fun refreshStatus() {
        executor.execute {
            val result = ApiClient.getEnvStatus(type)
            runOnUiThread {
                val data = result.data
                val enabled = data != null && data.optBoolean("enabled", false)
                detailStatus = getString(
                    if (enabled) R.string.env_detail_active else R.string.env_detail_inactive
                )
            }
        }
    }
}
