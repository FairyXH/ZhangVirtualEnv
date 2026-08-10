package io.github.fairyxh.VirtualEnv.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.fragment.app.Fragment
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.app.ApiClient
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassBackdropHost
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassCard
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassToggle
import io.github.fairyxh.VirtualEnv.app.ui.glass.glassColors
import java.util.concurrent.Executors

/**
 * 环境模拟入口页：基站 / WiFi / GNSS 等五卡片 → 子页面（EnvDetailActivity）。
 *
 * 视图层已迁移到 Compose Liquid Glass（GlassCard + GlassToggle），业务逻辑不变。
 */
class EnvFragment : Fragment() {

    companion object {
        private const val TYPE_CELL = "cell"
        private const val TYPE_WIFI = "wifi"
        private const val TYPE_BLE = "ble"
        private const val TYPE_SENSOR = "sensor"
        private const val TYPE_GNSS = "gnss"
    }

    private data class EnvItem(
        val type: String,
        val titleRes: Int,
        val switchState: Boolean,
        val summary: String
    )

    private var items by mutableStateOf(listOf<EnvItem>())
    private var updatingSwitch = false

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        refreshStatuses()
        return androidx.compose.ui.platform.ComposeView(requireContext()).apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                EnvScreen(this@EnvFragment)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refreshStatuses()
    }

    override fun onDestroyView() {
        executor.shutdown()
        super.onDestroyView()
    }

    @Composable
    private fun EnvScreen(fragment: EnvFragment) {
        GlassBackdropHost(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) { backdrop ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val colors = glassColors()
                BasicText(
                    getString(R.string.env_title),
                    style = TextStyle(
                        color = colors.textPrimary,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    )
                )
                BasicText(
                    getString(R.string.env_subtitle),
                    style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                )
                items.forEach { item ->
                    EnvCard(
                        item = item,
                        backdrop = backdrop,
                        onCardClick = {
                            EnvDetailActivity.start(requireContext(), item.type)
                        },
                        onToggle = { checked -> fragment.toggleEnv(item.type, checked) }
                    )
                }
            }
        }
    }

    @Composable
    private fun EnvCard(
        item: EnvItem,
        backdrop: com.kyant.backdrop.Backdrop,
        onCardClick: () -> Unit,
        onToggle: (Boolean) -> Unit
    ) {
        val colors = glassColors()
        GlassCard(
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth(),
            onClick = onCardClick,
            containerColor = colors.bgSecondary.copy(alpha = 0.45f)
        ) {
            Row(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    BasicText(
                        getString(item.titleRes),
                        style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    )
                    BasicText(
                        item.summary,
                        Modifier.padding(top = 2.dp),
                        style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                    )
                }
                GlassToggle(
                    selected = { item.switchState },
                    onSelect = onToggle,
                    backdrop = backdrop,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }

    /** 快捷开关：关闭时 Hook 放行真实数据（数据保留），开启时恢复。 */
    private fun toggleEnv(type: String, enabled: Boolean) {
        if (updatingSwitch) return
        executor.execute {
            val result = ApiClient.setEnvEnabled(type, enabled)
            requireActivity().runOnUiThread {
                if (result.code != io.github.fairyxh.VirtualEnv.core.model.ApiResult.CODE_OK) {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                    refreshStatuses()
                }
            }
        }
    }

    private fun refreshStatuses() {
        listOf(
            TYPE_CELL to R.string.env_cell_title,
            TYPE_WIFI to R.string.env_wifi_title,
            TYPE_BLE to R.string.env_ble_title,
            TYPE_SENSOR to R.string.env_sensor_title,
            TYPE_GNSS to R.string.env_gnss_title
        ).forEach { (type, titleRes) ->
            executor.execute {
                val result = ApiClient.getEnvStatus(type)
                requireActivity().runOnUiThread {
                    val data = result.data
                    val enabled = data != null && data.optBoolean("enabled", false)
                    val summary = configSummary(type, data)
                    val current = items
                    items = current.map { item ->
                        if (item.type == type) {
                            EnvItem(
                                type = item.type,
                                titleRes = item.titleRes,
                                switchState = enabled,
                                summary = getString(
                                    R.string.env_card_status_format,
                                    getString(if (enabled) R.string.env_status_active else R.string.env_status_inactive),
                                    summary
                                )
                            )
                        } else {
                            item
                        }
                    }
                }
            }
        }
    }

    /** 卡片显示当前使用的配置摘要（原始 data 的要点；未配置显示“未配置”）。 */
    private fun configSummary(type: String, status: org.json.JSONObject?): String {
        val data = status?.optJSONObject("data")
        val none = getString(R.string.env_card_no_config)
        if (data == null) return none
        return when (type) {
            TYPE_CELL -> {
                val arr = data.optJSONArray("entries") ?: org.json.JSONArray()
                if (arr.length() == 0) none else {
                    val e = arr.optJSONObject(0)
                    val first = e?.let {
                        "${it.optString("type", "LTE")} ${it.optInt("mcc", -1)}/${it.optInt("mnc", -1)}"
                    } ?: ""
                    getString(R.string.env_card_config_summary_cell, arr.length(), first)
                }
            }
            TYPE_WIFI -> {
                val arr = data.optJSONArray("networks") ?: org.json.JSONArray()
                if (arr.length() == 0) none else {
                    val first = arr.optJSONObject(0)?.optString("ssid", "") ?: ""
                    getString(R.string.env_card_config_summary_wifi, arr.length(), first)
                }
            }
            TYPE_BLE -> {
                val arr = data.optJSONArray("devices") ?: org.json.JSONArray()
                if (arr.length() == 0) none else {
                    val e = arr.optJSONObject(0)
                    val first = e?.let {
                        it.optString("name", "").ifEmpty { it.optString("address", "") }
                    } ?: ""
                    getString(R.string.env_card_config_summary_ble, arr.length(), first)
                }
            }
            TYPE_SENSOR -> {
                val step = data.optInt("stepFrequency", 0)
                if (step <= 0) none else getString(R.string.env_card_config_summary_sensor, step)
            }
            TYPE_GNSS -> {
                val count = data.optInt("satelliteCount", -1)
                if (count <= 0) none else {
                    getString(R.string.env_card_config_summary_gnss, count, data.optInt("usedInFix", 0))
                }
            }
            else -> none
        }
    }
}
