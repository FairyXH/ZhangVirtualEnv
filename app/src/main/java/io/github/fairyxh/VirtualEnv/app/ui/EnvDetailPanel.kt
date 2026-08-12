package io.github.fairyxh.VirtualEnv.app.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
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
private const val TYPE_SIM = "sim"

private fun envTitleRes(type: String): Int = when (type) {
    TYPE_CELL -> R.string.env_cell_title
    TYPE_WIFI -> R.string.env_wifi_title
    TYPE_BLE -> R.string.env_ble_title
    TYPE_SENSOR -> R.string.env_sensor_title
    TYPE_GNSS -> R.string.env_gnss_title
    TYPE_SIM -> R.string.env_sim_title
    else -> R.string.env_title
}

/**
 * 环境子页面（基站 / WiFi / 蓝牙 / 传感器 / GNSS 详细管理）。
 *
 * 已从独立 Activity 迁移为 EnvFragment 的子页面：由 EnvFragment 切换显示，
 * 复用父级 GlassBackdropHost 的 backdrop（不再嵌套第二层 host，避免
 * 双层 systemBars padding + 双层采样层导致顶部出现异常亮条/空白）；
 * 返回按钮为悬浮圆形液态玻璃按钮，滚动时永远可见。
 */
@Composable
fun EnvDetailPanel(
    fragment: EnvFragment,
    type: String,
    backdrop: Backdrop,
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
    // SIM 卡槽编辑表单
    var simSlot by remember { mutableStateOf("0") }
    var simSubId by remember { mutableStateOf("1") }
    var simCountryIso by remember { mutableStateOf("cn") }
    var simMcc by remember { mutableStateOf("460") }
    var simMnc by remember { mutableStateOf("00") }
    var simOperatorName by remember { mutableStateOf("中国移动") }
    var simNetworkOperatorName by remember { mutableStateOf("中国移动") }
    var simSubscriberId by remember { mutableStateOf("") }
    var simSerial by remember { mutableStateOf("") }
    var simLine1 by remember { mutableStateOf("") }
    var simDeviceId by remember { mutableStateOf("") }
    var simImei by remember { mutableStateOf("") }
    var simSimState by remember { mutableStateOf("5") }
    var simPhoneType by remember { mutableStateOf("1") }
    var simSignalGsm by remember { mutableStateOf("20") }
    var simSignalLte by remember { mutableStateOf("-95") }
    var simSignalNr by remember { mutableStateOf("-105") }
    var simSignalLevel by remember { mutableStateOf("3") }
    // SIM 卡槽选择：识别出的卡槽列表 + 当前选中卡槽 + 下拉展开状态
    val simDetectedSlots = remember { mutableStateListOf<JSONObject>() }
    var simSelectedSlotIndex by remember { mutableStateOf(-1) }
    var simCountryExpanded by remember { mutableStateOf(false) }
    var simCarrierExpanded by remember { mutableStateOf(false) }
    var simCustomCountry by remember { mutableStateOf("") }
    var simCustomCarrier by remember { mutableStateOf("") }
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
            TYPE_SIM -> {
                val slot = simSlot.toIntOrNull()
                if (slot == null || slot < 0) {
                    toast(fragment.getString(R.string.env_sim_slot_hint))
                    return null
                }
                JSONObject().apply {
                    put("slotIndex", slot)
                    put("subId", simSubId.toIntOrNull() ?: (slot + 1))
                    put("enabled", true)
                    put("mcc", simMcc.trim().ifEmpty { "460" })
                    put("mnc", simMnc.trim().ifEmpty { "00" })
                    put("countryIso", simCountryIso.trim().lowercase().ifEmpty { "cn" })
                    put("simCountryIso", simCountryIso.trim().lowercase().ifEmpty { "cn" })
                    put("networkCountryIso", simCountryIso.trim().lowercase().ifEmpty { "cn" })
                    put("simOperatorName", simOperatorName.trim())
                    put("networkOperatorName", simNetworkOperatorName.trim().ifEmpty { simOperatorName.trim() })
                    put("subscriberId", simSubscriberId.trim())
                    put("simSerialNumber", simSerial.trim())
                    put("line1Number", simLine1.trim())
                    put("deviceId", simDeviceId.trim())
                    put("imei", simImei.trim())
                    put("simState", simSimState.toIntOrNull() ?: 5)
                    put("phoneType", simPhoneType.toIntOrNull() ?: 1)
                    put("signal", JSONObject().apply {
                        put("gsm", simSignalGsm.toIntOrNull() ?: 20)
                        put("lte", simSignalLte.toIntOrNull() ?: -95)
                        put("nr", simSignalNr.toIntOrNull() ?: -105)
                        put("level", simSignalLevel.toIntOrNull() ?: 3)
                    })
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
            TYPE_SIM -> {
                simSlot = (entries.size).toString()
                simSubId = (entries.size + 1).toString()
                simCountryIso = "cn"
                simMcc = "460"
                simMnc = "00"
                simOperatorName = "中国移动"
                simNetworkOperatorName = "中国移动"
                simSubscriberId = ""
                simSerial = ""
                simLine1 = ""
                simDeviceId = ""
                simImei = ""
                simSimState = "5"
                simPhoneType = "1"
                simSignalGsm = "20"
                simSignalLte = "-95"
                simSignalNr = "-105"
                simSignalLevel = "3"
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
            TYPE_SIM -> {
                val slot = entry.optInt("slotIndex", -1)
                val operator = entry.optString("simOperatorName", "").ifEmpty { entry.optString("carrier", "") }
                val mcc = entry.optString("mcc", "")
                val mnc = entry.optString("mnc", "")
                fragment.getString(R.string.env_sim_entry_format, slot, operator, mcc, mnc)
            }
            else -> entry.toString()
        }
    }

    fun addEntry() {
        val entry = readEntryForm() ?: return
        entries.add(entry)
        clearEntryForm()
    }

    /** 把选中卡槽的信息加载到编辑表单。 */
    fun loadSimSlot(slot: JSONObject) {
        simSelectedSlotIndex = slot.optInt("slotIndex", -1)
        simSlot = slot.optInt("slotIndex", 0).toString()
        simSubId = slot.optInt("subId", -1).let { if (it >= 0) it.toString() else (slot.optInt("slotIndex", 0) + 1).toString() }
        simCountryIso = slot.optString("countryIso", "cn")
        simMcc = slot.optString("mcc", "460")
        simMnc = slot.optString("mnc", "00")
        simOperatorName = slot.optString("simOperatorName", "")
        simNetworkOperatorName = slot.optString("networkOperatorName", "")
        simSubscriberId = slot.optString("subscriberId", "")
        simSerial = slot.optString("simSerialNumber", "")
        simLine1 = slot.optString("line1Number", "")
        simDeviceId = slot.optString("deviceId", "")
        simImei = slot.optString("imei", "")
        simSimState = slot.optInt("simState", 5).toString()
        simPhoneType = slot.optInt("phoneType", 1).toString()
        slot.optJSONObject("signal")?.let { sig ->
            simSignalGsm = sig.optInt("gsm", 20).toString()
            simSignalLte = sig.optInt("lte", -95).toString()
            simSignalNr = sig.optInt("nr", -105).toString()
            simSignalLevel = sig.optInt("level", 3).toString()
        }
        // 重置自定义选择状态：当前国家/运营商有预设则取消自定义
        simCustomCountry = ""
        simCustomCarrier = ""
        simCountryExpanded = false
        simCarrierExpanded = false
    }

    /** 把当前表单作为卡槽配置写入 entries（同 slotIndex 覆盖，否则追加）。 */
    fun applySimSlot() {
        val entry = readEntryForm() ?: return
        val slotIndex = entry.optInt("slotIndex", -1)
        if (slotIndex < 0) return
        val idx = entries.indexOfFirst { it.optInt("slotIndex", -1) == slotIndex }
        if (idx >= 0) entries[idx] = entry else entries.add(entry)
        // 同步识别列表里的显示名（用户修改了运营商后列表摘要即时更新）
        val detectIdx = simDetectedSlots.indexOfFirst { it.optInt("slotIndex", -1) == slotIndex }
        if (detectIdx >= 0) simDetectedSlots[detectIdx] = entry
        toast(fragment.getString(R.string.env_sim_applied, slotIndex))
    }

    /** 自动识别真实卡槽并填充下拉列表；自动选中第一个识别卡槽并加载表单。 */
    fun detectSimSlots() {
        val detected = detectRealSimSlots(fragment)
        if (detected.isEmpty()) return
        simDetectedSlots.clear()
        simDetectedSlots.addAll(detected)
        val first = detected.first()
        loadSimSlot(first)
        toast(fragment.getString(R.string.env_sim_auto_detect_done, detected.size))
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
            TYPE_SIM -> JSONObject().apply {
                put("slots", JSONArray(entries.toList()))
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
            TYPE_SIM -> {
                val arr = data.optJSONArray("slots") ?: JSONArray()
                sb.append("SIM 卡槽 ").append(arr.length()).append(" 个\n")
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    sb.append("\n#").append(i + 1).append("  卡槽 ").append(e.optInt("slotIndex", -1))
                        .append("  ").append(e.optString("simOperatorName", "").ifEmpty { e.optString("carrier", "") })
                        .append("\n    MCC=").append(e.optString("mcc", ""))
                        .append(" MNC=").append(e.optString("mnc", ""))
                        .append(" 国家=").append(e.optString("countryIso", ""))
                        .append("\n    IMSI=").append(e.optString("subscriberId", ""))
                        .append("\n    ICCID=").append(e.optString("simSerialNumber", ""))
                        .append("\n    号码=").append(e.optString("line1Number", ""))
                        .append("\n    状态=").append(e.optInt("simState", -1))
                    e.optJSONObject("signal")?.let { sig ->
                        sb.append("\n    信号 GSM=").append(sig.optInt("gsm", -1))
                            .append(" LTE=").append(sig.optInt("lte", -1))
                            .append(" NR=").append(sig.optInt("nr", -1))
                            .append(" 等级=").append(sig.optInt("level", -1))
                    }
                }
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
                                EntryFormField("cn0", gnssCn0, { cn0 -> gnssCn0 = cn0 }, fragment.getString(R.string.env_gnss_cn0_hint), backdrop)
                            }
                        }
                    }
                    TYPE_SIM -> {
                        // 第一段：选择目标卡槽（自动识别 + 下拉选择）
                        GlassCard(
                            backdrop = backdrop,
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                BasicText(
                                    fragment.getString(R.string.env_sim_slot_select_title),
                                    style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                )
                                BasicText(
                                    fragment.getString(R.string.env_sim_slot_select_desc),
                                    Modifier.padding(top = 4.dp),
                                    style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                                )
                                GlassButton(
                                    onClick = { detectSimSlots() },
                                    backdrop = backdrop,
                                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                                    surfaceColor = colors.bgTertiary.copy(alpha = 0.4f)
                                ) {
                                    BasicText(
                                        fragment.getString(R.string.env_sim_auto_detect),
                                        style = TextStyle(color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    )
                                }
                                // 卡槽下拉选择：识别结果优先，也允许手动输入 slot/subId
                                SimSlotDropdown(
                                    fragment = fragment,
                                    backdrop = backdrop,
                                    slots = simDetectedSlots,
                                    selectedSlotIndex = simSelectedSlotIndex,
                                    manualSlot = simSlot,
                                    manualSubId = simSubId,
                                    onSlotClick = { slot -> loadSimSlot(slot) },
                                    onManualSlotChange = { simSlot = it; simSelectedSlotIndex = it.toIntOrNull() ?: -1 },
                                    onManualSubIdChange = { simSubId = it }
                                )
                            }
                        }
                        // 第二段：详细参数（选择卡槽后设置）
                        GlassCard(
                            backdrop = backdrop,
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                BasicText(
                                    fragment.getString(R.string.env_sim_detail_title),
                                    style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                )
                                BasicText(
                                    if (simSelectedSlotIndex >= 0) {
                                        fragment.getString(R.string.env_sim_slot_item, simSelectedSlotIndex, simOperatorName)
                                    } else {
                                        fragment.getString(R.string.env_sim_no_slot_selected)
                                    },
                                    Modifier.padding(top = 4.dp),
                                    style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                                )
                                // 国家 / 运营商双下拉（预设 + 自定义，类似 Nrfr 交互）
                                SimCountryCarrierSelect(
                                    fragment = fragment,
                                    backdrop = backdrop,
                                    countryIso = simCountryIso,
                                    carrierName = simOperatorName,
                                    customCountry = simCustomCountry,
                                    customCarrier = simCustomCarrier,
                                    countryExpanded = simCountryExpanded,
                                    carrierExpanded = simCarrierExpanded,
                                    onCountryExpanded = { simCountryExpanded = it; if (it) simCarrierExpanded = false },
                                    onCarrierExpanded = { simCarrierExpanded = it; if (it) simCountryExpanded = false },
                                    onCountry = { iso, mcc, mnc, carrier ->
                                        simCountryIso = iso
                                        simMcc = mcc
                                        simMnc = mnc
                                        simCustomCountry = ""
                                        if (carrier.isNotBlank()) {
                                            simOperatorName = carrier
                                            simNetworkOperatorName = carrier
                                            simCustomCarrier = ""
                                        }
                                    },
                                    onCountryCustom = { value ->
                                        simCustomCountry = value
                                        simCountryIso = value.lowercase()
                                    },
                                    onCarrier = { carrier ->
                                        simOperatorName = carrier
                                        simNetworkOperatorName = carrier
                                        simCustomCarrier = ""
                                    },
                                    onCarrierCustom = { value ->
                                        simCustomCarrier = value
                                        simOperatorName = value
                                        simNetworkOperatorName = value
                                    }
                                )
                                EntryFormField(fragment.getString(R.string.env_sim_mcc_hint), simMcc, { simMcc = it }, fragment.getString(R.string.env_sim_mcc_hint), backdrop)
                                EntryFormField(fragment.getString(R.string.env_sim_mnc_hint), simMnc, { simMnc = it }, fragment.getString(R.string.env_sim_mnc_hint), backdrop)
                                EntryFormField(fragment.getString(R.string.env_sim_country_iso_hint), simCountryIso, { simCountryIso = it }, fragment.getString(R.string.env_sim_country_iso_hint), backdrop)
                                EntryFormField(fragment.getString(R.string.env_sim_operator_name_hint), simOperatorName, { simOperatorName = it }, fragment.getString(R.string.env_sim_operator_hint), backdrop)
                                EntryFormField(fragment.getString(R.string.env_sim_network_operator_name_hint), simNetworkOperatorName, { simNetworkOperatorName = it }, fragment.getString(R.string.env_sim_network_operator_name_hint), backdrop)
                                EntryFormField(fragment.getString(R.string.env_sim_subscriber_id_hint), simSubscriberId, { simSubscriberId = it }, fragment.getString(R.string.env_sim_subscriber_id_hint), backdrop)
                                EntryFormField(fragment.getString(R.string.env_sim_serial_hint), simSerial, { simSerial = it }, fragment.getString(R.string.env_sim_serial_hint), backdrop)
                                EntryFormField(fragment.getString(R.string.env_sim_line1_hint), simLine1, { simLine1 = it }, fragment.getString(R.string.env_sim_line1_hint), backdrop)
                                EntryFormField(fragment.getString(R.string.env_sim_device_id_hint), simDeviceId, { simDeviceId = it }, fragment.getString(R.string.env_sim_device_id_hint), backdrop)
                                EntryFormField(fragment.getString(R.string.env_sim_imei_hint), simImei, { simImei = it }, fragment.getString(R.string.env_sim_imei_hint), backdrop)
                                EntryFormField(fragment.getString(R.string.env_sim_sim_state_hint), simSimState, { simSimState = it }, fragment.getString(R.string.env_sim_sim_state_hint), backdrop)
                                EntryFormField(fragment.getString(R.string.env_sim_phone_type_hint), simPhoneType, { simPhoneType = it }, fragment.getString(R.string.env_sim_phone_type_hint), backdrop)
                                BasicText(
                                    fragment.getString(R.string.env_sim_signal_title),
                                    Modifier.padding(top = 10.dp),
                                    style = TextStyle(color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                )
                                EntryFormField(fragment.getString(R.string.env_sim_signal_gsm_hint), simSignalGsm, { simSignalGsm = it }, fragment.getString(R.string.env_sim_signal_gsm_hint), backdrop)
                                EntryFormField(fragment.getString(R.string.env_sim_signal_lte_hint), simSignalLte, { simSignalLte = it }, fragment.getString(R.string.env_sim_signal_lte_hint), backdrop)
                                EntryFormField(fragment.getString(R.string.env_sim_signal_nr_hint), simSignalNr, { simSignalNr = it }, fragment.getString(R.string.env_sim_signal_nr_hint), backdrop)
                                EntryFormField(fragment.getString(R.string.env_sim_signal_level_hint), simSignalLevel, { simSignalLevel = it }, fragment.getString(R.string.env_sim_signal_level_hint), backdrop)
                                GlassButton(
                                    onClick = { applySimSlot() },
                                    backdrop = backdrop,
                                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                                    surfaceColor = colors.bgTertiary.copy(alpha = 0.4f)
                                ) {
                                    BasicText(
                                        fragment.getString(R.string.env_sim_apply_slot),
                                        style = TextStyle(color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    )
                                }
                                RenderEntriesLocal(backdrop)
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

/**
 * SIM 卡槽下拉选择：显示识别出的卡槽列表，点击选择后加载表单；
 * 未识别/手动场景可手动输入 slot/subId。
 */
@Composable
private fun SimSlotDropdown(
    fragment: androidx.fragment.app.Fragment,
    backdrop: Backdrop,
    slots: List<JSONObject>,
    selectedSlotIndex: Int,
    manualSlot: String,
    manualSubId: String,
    onSlotClick: (JSONObject) -> Unit,
    onManualSlotChange: (String) -> Unit,
    onManualSubIdChange: (String) -> Unit
) {
    val colors = glassColors()
    Column(Modifier.padding(top = 8.dp).fillMaxWidth()) {
        BasicText(
            fragment.getString(R.string.env_sim_slot_hint),
            style = TextStyle(color = colors.textSecondary, fontSize = 12.sp)
        )
        // 识别结果选择区（无结果时提示手动输入）
        if (slots.isEmpty()) {
            BasicText(
                fragment.getString(R.string.env_sim_auto_detect_empty),
                Modifier.padding(top = 4.dp),
                style = TextStyle(color = colors.textSecondary, fontSize = 12.sp)
            )
        } else {
            slots.forEach { slot ->
                val slotIdx = slot.optInt("slotIndex", -1)
                val label = slot.optString("simOperatorName", "")
                    .ifEmpty { slot.optString("mcc", "") + "/" + slot.optString("mnc", "") }
                    .ifEmpty { "SIM ${slotIdx + 1}" }
                Row(
                    Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .clickable { onSlotClick(slot) }
                        .then(
                            if (slotIdx == selectedSlotIndex) {
                                Modifier.drawBehind {
                                    drawRect(colors.accent.copy(alpha = 0.14f))
                                }
                            } else Modifier
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(
                        fragment.getString(R.string.env_sim_slot_item, slotIdx, label),
                        Modifier.weight(1f),
                        style = TextStyle(
                            color = if (slotIdx == selectedSlotIndex) colors.accent else colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = if (slotIdx == selectedSlotIndex) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                }
            }
        }
        // 手动输入（未识别时使用；选择识别卡槽后自动同步）
        EntryFormField(
            fragment.getString(R.string.env_sim_slot_hint) + " (ID)",
            manualSlot,
            onManualSlotChange,
            fragment.getString(R.string.env_sim_slot_hint),
            backdrop
        )
        EntryFormField(
            fragment.getString(R.string.env_sim_sub_id_hint),
            manualSubId,
            onManualSubIdChange,
            fragment.getString(R.string.env_sim_sub_id_hint),
            backdrop
        )
    }
}

/**
 * SIM 国家/运营商双下拉（预设 + 自定义）。
 *
 * 国家来源：assets/country_templates.json（28 个国家，含 mcc/mnc/carrier/前缀）；
 * 运营商来源：assets/carrier_presets.json（按 iso 分组，借鉴 Nrfr 预设）。
 * 均带「自定义」选项，选择自定义时显示输入框。
 */
@Composable
private fun SimCountryCarrierSelect(
    fragment: androidx.fragment.app.Fragment,
    backdrop: Backdrop,
    countryIso: String,
    carrierName: String,
    customCountry: String,
    customCarrier: String,
    countryExpanded: Boolean,
    carrierExpanded: Boolean,
    onCountryExpanded: (Boolean) -> Unit,
    onCarrierExpanded: (Boolean) -> Unit,
    onCountry: (iso: String, mcc: String, mnc: String, carrier: String) -> Unit,
    onCountryCustom: (String) -> Unit,
    onCarrier: (String) -> Unit,
    onCarrierCustom: (String) -> Unit
) {
    val context = fragment.requireContext()
    val countries = remember {
        val list = mutableListOf<JSONObject>()
        try {
            val text = context.assets.open("country_templates.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) list.add(arr.optJSONObject(i) ?: continue)
        } catch (t: Throwable) {
            // assets 缺失时返回空列表
        }
        list
    }
    val carriersByIso = remember {
        val map = HashMap<String, List<String>>()
        try {
            val text = context.assets.open("carrier_presets.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val iso = obj.optString("iso", "").uppercase()
                val carriers = mutableListOf<String>()
                val ca = obj.optJSONArray("carriers")
                if (ca != null) for (j in 0 until ca.length()) carriers.add(ca.optString(j))
                if (carriers.isNotEmpty()) map[iso] = carriers
            }
        } catch (t: Throwable) {
        }
        map
    }
    val colors = glassColors()
    val customLabel = fragment.getString(R.string.env_sim_custom)
    val currentCountry = countries.firstOrNull { it.optString("iso", "").equals(countryIso, ignoreCase = true) }

    // ---------- 国家下拉 ----------
    Column(Modifier.padding(top = 8.dp).fillMaxWidth()) {
        BasicText(
            fragment.getString(R.string.env_sim_country),
            style = TextStyle(color = colors.textSecondary, fontSize = 12.sp)
        )
        GlassCard(
            backdrop = backdrop,
            modifier = Modifier.padding(top = 2.dp).fillMaxWidth(),
            cornerRadius = 12.dp,
            containerColor = colors.bgSecondary.copy(alpha = 0.45f),
            contentPadding = 0.dp
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onCountryExpanded(!countryExpanded) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    currentCountry?.let { "${it.optString("nameZh", "")} (${it.optString("iso", "")})" }
                        ?: customCountry.ifEmpty { countryIso.ifEmpty { fragment.getString(R.string.env_sim_no_slot_selected) } },
                    Modifier.weight(1f),
                    style = TextStyle(color = colors.textPrimary, fontSize = 15.sp)
                )
                BasicText(
                    if (countryExpanded) "▲" else "▼",
                    style = TextStyle(color = colors.textSecondary, fontSize = 11.sp)
                )
            }
        }
        if (countryExpanded) {
            Column(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .drawBehind { drawRect(colors.bgTertiary.copy(alpha = 0.35f)) }
                    .padding(4.dp)
            ) {
                countries.forEach { c ->
                    val iso = c.optString("iso", "")
                    OptionRow(
                        label = "${c.optString("nameZh", "")} (${iso}) · MCC ${c.optString("mcc", "")}",
                        selected = iso.equals(countryIso, ignoreCase = true),
                        colors = colors
                    ) {
                        onCountry(
                            iso.lowercase(),
                            c.optString("mcc", "460"),
                            c.optString("defaultMnc", "00"),
                            c.optString("carrier", "")
                        )
                        onCountryExpanded(false)
                    }
                }
                OptionRow(
                    label = customLabel,
                    selected = customCountry.isNotEmpty(),
                    colors = colors
                ) {
                    onCountryExpanded(false)
                }
            }
            if (customCountry.isNotEmpty()) {
                GlassField(
                    value = customCountry,
                    onValueChange = onCountryCustom,
                    backdrop = backdrop,
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
                    placeholder = fragment.getString(R.string.env_sim_country_custom_hint)
                )
            }
        }
    }

    // ---------- 运营商下拉 ----------
    Column(Modifier.padding(top = 8.dp).fillMaxWidth()) {
        BasicText(
            fragment.getString(R.string.env_sim_carrier),
            style = TextStyle(color = colors.textSecondary, fontSize = 12.sp)
        )
        GlassCard(
            backdrop = backdrop,
            modifier = Modifier.padding(top = 2.dp).fillMaxWidth(),
            cornerRadius = 12.dp,
            containerColor = colors.bgSecondary.copy(alpha = 0.45f),
            contentPadding = 0.dp
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onCarrierExpanded(!carrierExpanded) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    carrierName.ifEmpty { fragment.getString(R.string.env_sim_no_slot_selected) },
                    Modifier.weight(1f),
                    style = TextStyle(color = colors.textPrimary, fontSize = 15.sp)
                )
                BasicText(
                    if (carrierExpanded) "▲" else "▼",
                    style = TextStyle(color = colors.textSecondary, fontSize = 11.sp)
                )
            }
        }
        if (carrierExpanded) {
            val options = carriersByIso[countryIso.uppercase()] ?: emptyList()
            Column(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .drawBehind { drawRect(colors.bgTertiary.copy(alpha = 0.35f)) }
                    .padding(4.dp)
            ) {
                if (options.isEmpty()) {
                    BasicText(
                        fragment.getString(R.string.env_sim_no_slot_selected),
                        Modifier.padding(10.dp),
                        style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                    )
                } else {
                    options.forEach { carrier ->
                        OptionRow(
                            label = carrier,
                            selected = carrier == carrierName,
                            colors = colors
                        ) {
                            onCarrier(carrier)
                            onCarrierExpanded(false)
                        }
                    }
                }
                OptionRow(
                    label = customLabel,
                    selected = customCarrier.isNotEmpty(),
                    colors = colors
                ) {
                    onCarrierExpanded(false)
                }
            }
            if (customCarrier.isNotEmpty()) {
                GlassField(
                    value = customCarrier,
                    onValueChange = onCarrierCustom,
                    backdrop = backdrop,
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
                    placeholder = fragment.getString(R.string.env_sim_carrier_custom_hint)
                )
            }
        }
    }
    if (currentCountry != null) {
        BasicText(
            "IMSI 前缀 ${currentCountry.optString("imsiPrefix", "-")} · ICCID 前缀 ${currentCountry.optString("iccidPrefix", "-")} · 国际区号 +${currentCountry.optString("callingCode", "-")}",
            Modifier.padding(top = 6.dp),
            style = TextStyle(color = colors.textSecondary, fontSize = 11.sp)
        )
    }
}

/** 下拉选项行（选中高亮）。 */
@Composable
private fun OptionRow(
    label: String,
    selected: Boolean,
    colors: io.github.fairyxh.VirtualEnv.app.ui.glass.GlassColors,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .then(if (selected) Modifier.drawBehind { drawRect(colors.accent.copy(alpha = 0.16f)) } else Modifier)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            label,
            Modifier.weight(1f),
            style = TextStyle(
                color = if (selected) colors.accent else colors.textPrimary,
                fontSize = 14.sp
            )
        )
    }
}

/**
 * 自动识别真实 SIM 卡槽（借鉴 VirtualRegion B3.d.e 的链路）：
 * SubscriptionManager.getActiveSubscriptionInfoList → createForSubscriptionId →
 * getSimOperator 切分 mcc/mnc → getSimCountryIso / getSimOperatorName /
 * getNetworkOperatorName → getSimState。返回识别到的槽位列表（JSON 配置结构）。
 */
private fun detectRealSimSlots(fragment: androidx.fragment.app.Fragment): List<JSONObject> {
    val context = fragment.requireContext()
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
        Toast.makeText(context, fragment.getString(R.string.env_sim_auto_detect_perm), Toast.LENGTH_SHORT).show()
        return emptyList()
    }
    return try {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val sm = context.getSystemService(SubscriptionManager::class.java)
        if (tm == null || sm == null) {
            Toast.makeText(context, fragment.getString(R.string.env_sim_auto_detect_empty), Toast.LENGTH_SHORT).show()
            return emptyList()
        }
        val detected = mutableListOf<JSONObject>()
        val seenSlots = HashSet<Int>()
        sm.activeSubscriptionInfoList?.forEach { info ->
            if (info.simSlotIndex < 0) return@forEach
            seenSlots.add(info.simSlotIndex)
            val subTm = tm.createForSubscriptionId(info.subscriptionId)
            var mcc = ""
            var mnc = ""
            if (Build.VERSION.SDK_INT >= 29) {
                mcc = info.mccString.orEmpty()
                mnc = info.mncString.orEmpty()
            } else {
                if (info.mcc > 0) mcc = String.format(java.util.Locale.ROOT, "%03d", info.mcc)
                if (info.mnc >= 0) mnc = String.format(java.util.Locale.ROOT, "%02d", info.mnc)
            }
            val operator = try { subTm.simOperator.orEmpty() } catch (t: Throwable) { "" }
            if (mcc.isEmpty() && operator.matches(Regex("\\d{5,6}"))) {
                mcc = operator.substring(0, 3)
                mnc = operator.substring(3)
            }
            var countryIso = try { info.countryIso.orEmpty() } catch (t: Throwable) { "" }
            if (!countryIso.matches(Regex("[A-Za-z]{2}"))) {
                countryIso = try { subTm.simCountryIso.orEmpty() } catch (t: Throwable) { "" }
            }
            var carrierName = try { info.carrierName?.toString()?.trim().orEmpty() } catch (t: Throwable) { "" }
            if (carrierName.isEmpty()) {
                carrierName = try { subTm.simOperatorName.orEmpty() } catch (t: Throwable) { "" }
            }
            var displayName = try { info.displayName?.toString()?.trim().orEmpty() } catch (t: Throwable) { "" }
            if (displayName.isEmpty()) displayName = carrierName
            if (displayName.isEmpty()) displayName = "SIM ${info.simSlotIndex + 1}"
            var networkOperatorName = ""
            try { networkOperatorName = subTm.networkOperatorName.orEmpty() } catch (t: Throwable) { }
            val simState = try { tm.getSimState(info.simSlotIndex) } catch (t: Throwable) { 0 }
            detected.add(JSONObject().apply {
                put("slotIndex", info.simSlotIndex)
                put("subId", info.subscriptionId)
                put("enabled", true)
                put("mcc", mcc)
                put("mnc", mnc)
                put("countryIso", countryIso.lowercase(java.util.Locale.ROOT))
                put("simCountryIso", countryIso.lowercase(java.util.Locale.ROOT))
                put("networkCountryIso", countryIso.lowercase(java.util.Locale.ROOT))
                put("simOperatorName", displayName)
                put("networkOperatorName", networkOperatorName.ifEmpty { displayName })
                put("simState", simState)
            })
        }
        // 未出现在订阅列表但 simState 有效的槽位（eSIM / 未激活槽）
        val modemCount = try {
            if (Build.VERSION.SDK_INT >= 30) tm.activeModemCount else tm.phoneCount
        } catch (t: Throwable) {
            try { tm.phoneCount } catch (t2: Throwable) { 0 }
        }
        for (i in 0 until modemCount.coerceIn(0, 8)) {
            if (i in seenSlots) continue
            val st = try { tm.getSimState(i) } catch (t: Throwable) { 0 }
            if (st != 0 && st != 1) {
                detected.add(JSONObject().apply {
                    put("slotIndex", i)
                    put("subId", -1)
                    put("enabled", true)
                    put("mcc", "460")
                    put("mnc", "00")
                    put("countryIso", "cn")
                    put("simOperatorName", "SIM ${i + 1}")
                    put("simState", st)
                })
            }
        }
        detected.sortBy { it.optInt("slotIndex", -1) }
        detected
    } catch (t: Throwable) {
        Toast.makeText(context, fragment.getString(R.string.env_sim_auto_detect_empty), Toast.LENGTH_SHORT).show()
        emptyList()
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
