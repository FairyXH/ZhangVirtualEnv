package io.github.fairyxh.VirtualEnv.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.app.ApiClient
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
 */
class EnvDetailActivity : Activity() {

    companion object {
        private const val TAG_SCOPE = "UI"
        const val EXTRA_TYPE = "env_type"
        const val TYPE_CELL = "cell"
        const val TYPE_WIFI = "wifi"
        const val TYPE_GNSS = "gnss"

        fun start(context: Context, type: String) {
            context.startActivity(
                Intent(context, EnvDetailActivity::class.java)
                    .putExtra(EXTRA_TYPE, type)
            )
        }
    }

    private lateinit var type: String
    private lateinit var detailTitle: TextView
    private lateinit var detailStatus: TextView
    private lateinit var entryList: LinearLayout
    private lateinit var cellFields: View
    private lateinit var wifiFields: View
    private lateinit var gnssFields: View
    private lateinit var addEntryButton: Button
    private lateinit var saveNameInput: EditText
    private lateinit var saveRemarkInput: EditText
    private lateinit var savedEmpty: TextView
    private lateinit var savedList: LinearLayout

    /** 当前组合条目（cell/wifi 多条目；gnss 为单配置）。 */
    private val entries = mutableListOf<JSONObject>()

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_env_detail)
        type = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_CELL

        detailTitle = findViewById(R.id.detailTitle)
        detailStatus = findViewById(R.id.detailStatus)
        entryList = findViewById(R.id.entryList)
        cellFields = findViewById(R.id.cellFields)
        wifiFields = findViewById(R.id.wifiFields)
        gnssFields = findViewById(R.id.gnssFields)
        addEntryButton = findViewById(R.id.addEntryButton)
        saveNameInput = findViewById(R.id.saveNameInput)
        saveRemarkInput = findViewById(R.id.saveRemarkInput)
        savedEmpty = findViewById(R.id.savedEmpty)
        savedList = findViewById(R.id.savedList)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        detailTitle.text = when (type) {
            TYPE_CELL -> getString(R.string.env_cell_title)
            TYPE_WIFI -> getString(R.string.env_wifi_title)
            TYPE_GNSS -> getString(R.string.env_gnss_title)
            else -> type
        }

        when (type) {
            TYPE_CELL -> cellFields.visibility = View.VISIBLE
            TYPE_WIFI -> wifiFields.visibility = View.VISIBLE
            TYPE_GNSS -> {
                gnssFields.visibility = View.VISIBLE
                // GNSS 为单配置：表单即配置，无“添加条目”
                addEntryButton.visibility = View.GONE
                findViewById<View>(R.id.entriesDesc).visibility = View.GONE
                findViewById<View>(R.id.entryList).visibility = View.GONE
            }
        }

        addEntryButton.setOnClickListener { addEntry() }
        findViewById<Button>(R.id.saveButton).setOnClickListener { saveConfig() }

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

    // ---------- 条目编辑 ----------

    private fun addEntry() {
        val entry = readEntryForm() ?: return
        entries.add(entry)
        clearEntryForm()
        renderEntries()
    }

    private fun readEntryForm(): JSONObject? {
        return when (type) {
            TYPE_CELL -> {
                val obj = JSONObject().apply {
                    val netType = findViewById<EditText>(R.id.cellTypeInput).text.toString().trim()
                    put("type", if (netType.isEmpty()) "LTE" else netType)
                    put("mcc", findViewById<EditText>(R.id.cellMccInput).text.toString().toIntOrNull() ?: -1)
                    put("mnc", findViewById<EditText>(R.id.cellMncInput).text.toString().toIntOrNull() ?: -1)
                    put("tac", findViewById<EditText>(R.id.cellTacInput).text.toString().toIntOrNull() ?: -1)
                    put("ci", findViewById<EditText>(R.id.cellCiInput).text.toString().toLongOrNull() ?: -1L)
                    put("pci", findViewById<EditText>(R.id.cellPciInput).text.toString().toIntOrNull() ?: -1)
                    put("rsrp", findViewById<EditText>(R.id.cellRsrpInput).text.toString().toIntOrNull() ?: -1)
                }
                if (obj.optInt("mcc", -1) < 0) {
                    Toast.makeText(this, R.string.env_cell_mcc_required, Toast.LENGTH_SHORT).show()
                    return null
                }
                obj
            }
            TYPE_WIFI -> {
                val ssid = findViewById<EditText>(R.id.wifiSsidInput).text.toString().trim()
                if (ssid.isEmpty()) {
                    Toast.makeText(this, R.string.env_wifi_ssid_required, Toast.LENGTH_SHORT).show()
                    return null
                }
                JSONObject().apply {
                    put("ssid", ssid)
                    put("bssid", findViewById<EditText>(R.id.wifiBssidInput).text.toString().trim())
                    put("rssi", findViewById<EditText>(R.id.wifiRssiInput).text.toString().toIntOrNull() ?: -60)
                    put("frequency", findViewById<EditText>(R.id.wifiFrequencyInput).text.toString().toIntOrNull() ?: 2412)
                }
            }
            else -> null
        }
    }

    private fun clearEntryForm() {
        if (type == TYPE_CELL) {
            listOf(R.id.cellTypeInput, R.id.cellMccInput, R.id.cellMncInput, R.id.cellTacInput,
                R.id.cellCiInput, R.id.cellPciInput, R.id.cellRsrpInput)
                .forEach { findViewById<EditText>(it).text.clear() }
        } else if (type == TYPE_WIFI) {
            listOf(R.id.wifiSsidInput, R.id.wifiBssidInput, R.id.wifiRssiInput, R.id.wifiFrequencyInput)
                .forEach { findViewById<EditText>(it).text.clear() }
        }
    }

    private fun renderEntries() {
        entryList.removeAllViews()
        entries.forEachIndexed { index, entry ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = android.view.Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(6), 0, dp(6))

            val summary = TextView(this)
            summary.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            summary.text = entrySummary(entry)
            summary.setTextColor(getColor(R.color.text_primary))
            summary.setTextSize(12f)
            row.addView(summary)

            val del = TextView(this)
            del.text = getString(R.string.env_detail_remove)
            del.setBackgroundResource(R.drawable.bg_pill_secondary)
            del.setTextColor(getColor(R.color.text_secondary))
            del.setTextSize(11f)
            del.setPadding(dp(8), dp(4), dp(8), dp(4))
            del.setOnClickListener {
                entries.removeAt(index)
                renderEntries()
            }
            row.addView(del)
            entryList.addView(row)
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
            else -> entry.toString()
        }
    }

    // ---------- 保存 / 使用 / 删除 ----------

    private fun saveConfig() {
        val name = saveNameInput.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.location_point_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        val remark = saveRemarkInput.text.toString().trim()
        val data = buildConfigData() ?: return
        executor.execute {
            val result = ApiClient.createEnvSnapshot(name, remark, type, data)
            runOnUiThread {
                Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                if (result.code == ApiResult.CODE_OK) {
                    saveNameInput.text.clear()
                    saveRemarkInput.text.clear()
                    entries.clear()
                    renderEntries()
                    refreshSaved()
                }
            }
        }
    }

    private fun buildConfigData(): JSONObject? {
        return when (type) {
            TYPE_CELL -> JSONObject().apply { put("entries", JSONArray(entries.toList())) }
            TYPE_WIFI -> JSONObject().apply { put("networks", JSONArray(entries.toList())) }
            TYPE_GNSS -> JSONObject().apply {
                put("satelliteCount", findViewById<EditText>(R.id.gnssCountInput).text.toString().toIntOrNull() ?: -1)
                put("usedInFix", findViewById<EditText>(R.id.gnssUsedInput).text.toString().toIntOrNull() ?: -1)
                put("cn0", findViewById<EditText>(R.id.gnssCn0Input).text.toString().toDoubleOrNull() ?: -1.0)
            }
            else -> null
        }
    }

    private fun refreshSaved() {
        executor.execute {
            val result = ApiClient.listEnvSnapshots()
            runOnUiThread { renderSaved(result) }
        }
    }

    private fun renderSaved(result: ApiResult) {
        savedList.removeAllViews()
        val snapshots = result.data?.optJSONArray("snapshots") ?: return
        val items = mutableListOf<JSONObject>()
        for (i in 0 until snapshots.length()) {
            val item = snapshots.optJSONObject(i) ?: continue
            if (item.optString("type", "") == type) items.add(item)
        }
        savedEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        items.forEach { item ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = android.view.Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(6), 0, dp(6))

            val name = TextView(this)
            name.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            name.text = item.optString("name", "")
            name.setTextColor(getColor(R.color.text_primary))
            name.setTextSize(13f)
            row.addView(name)

            val use = TextView(this)
            use.text = getString(R.string.env_use)
            use.setBackgroundResource(R.drawable.bg_pill)
            use.setTextColor(getColor(R.color.text_primary))
            use.setTextSize(11f)
            use.setPadding(dp(8), dp(4), dp(8), dp(4))
            use.setOnClickListener { useConfig(item) }
            row.addView(use)

            val del = TextView(this)
            del.text = getString(R.string.env_delete)
            del.setBackgroundResource(R.drawable.bg_pill_secondary)
            del.setTextColor(getColor(R.color.text_secondary))
            del.setTextSize(11f)
            del.setPadding(dp(8), dp(4), dp(8), dp(4))
            del.setOnClickListener { deleteConfig(item.optLong("id")) }
            row.addView(del)

            savedList.addView(row)
        }
    }

    /** 一键使用 = 切换到该配置（Hook 层随即生效）。 */
    private fun useConfig(item: JSONObject) {
        val id = item.optLong("id")
        executor.execute {
            val result = ApiClient.useEnvSnapshot(id)
            runOnUiThread {
                Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                refreshStatus()
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
                detailStatus.text = getString(
                    if (enabled) R.string.env_detail_active else R.string.env_detail_inactive
                )
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
