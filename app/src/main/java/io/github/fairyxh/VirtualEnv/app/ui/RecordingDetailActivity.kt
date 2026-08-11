package io.github.fairyxh.VirtualEnv.app.ui

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.systemBarsPadding
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
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassCard
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassPill
import io.github.fairyxh.VirtualEnv.app.ui.glass.glassColors
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 录像帧详情页：按帧列出录像保存的各信息摘要（帧与帧之间带分隔符），
 * 点击任意帧切换查看该帧保存的全部原始数据（各信息 JSON），带返回按钮。
 *
 * 满足需求：已保存采集详情可查看具体哪个帧保存了哪些信息的原始数据/详细信息。
 *
 * 视图层已迁移到 Compose Liquid Glass，业务逻辑不变。
 */
class RecordingDetailActivity : ComponentActivity() {

    companion object {
        private const val TAG_SCOPE = "UI"
        const val EXTRA_ID = "recording_id"
        const val EXTRA_NAME = "recording_name"

        fun start(context: Context, id: Long, name: String) {
            context.startActivity(
                Intent(context, RecordingDetailActivity::class.java)
                    .putExtra(EXTRA_ID, id)
                    .putExtra(EXTRA_NAME, name)
            )
        }
    }

    private val executor = Executors.newSingleThreadExecutor()

    private var detailTitle by mutableStateOf("")
    private var detailStatus by mutableStateOf("")
    private var frames = mutableStateListOf<JSONObject>()
    private var firstTs = 0L
    private var showingFrame by mutableStateOf<JSONObject?>(null)

    private var recordingId = -1L
    private var recordingName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        recordingId = intent.getLongExtra(EXTRA_ID, -1L)
        recordingName = intent.getStringExtra(EXTRA_NAME) ?: ""

        detailTitle = getString(R.string.recording_detail_title) +
            (if (recordingName.isBlank()) "" else " · $recordingName")

        if (recordingId <= 0) {
            detailStatus = getString(R.string.recording_detail_empty)
        } else {
            loadFrames()
        }

        setContent {
            DetailScreen(this)
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    // ---------- Compose UI ----------

    @Composable
    private fun DetailScreen(activity: RecordingDetailActivity) {
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
                        onClick = {
                            if (showingFrame != null) {
                                showingFrame = null
                            } else {
                                activity.finish()
                            }
                        },
                        backdrop = backdrop,
                        selected = false,
                        containerColor = colors.bgTertiary.copy(alpha = 0.4f),
                        height = 36.dp
                    ) {
                        BasicText(
                            getString(if (showingFrame != null) R.string.recording_detail_back_to_list else R.string.env_detail_back),
                            Modifier.padding(horizontal = 16.dp),
                            style = TextStyle(color = colors.textPrimary, fontSize = 13.sp)
                        )
                    }
                    BasicText(
                        detailTitle,
                        Modifier.padding(start = 12.dp).weight(1f),
                        style = TextStyle(
                            color = colors.textPrimary,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp
                        )
                    )
                }

                val frame = showingFrame
                if (frame == null) {
                    // 列表视图
                    GlassCard(
                        backdrop = backdrop,
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            BasicText(
                                detailStatus,
                                style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                            )
                            if (frames.isEmpty()) {
                                BasicText(
                                    getString(R.string.recording_detail_empty),
                                    Modifier
                                        .padding(top = 16.dp, bottom = 16.dp)
                                        .fillMaxWidth(),
                                    style = TextStyle(color = colors.textTertiary, fontSize = 13.sp)
                                )
                            } else {
                                BasicText(
                                    getString(R.string.recording_detail_click_hint),
                                    Modifier.padding(top = 10.dp),
                                    style = TextStyle(color = colors.textTertiary, fontSize = 12.sp)
                                )
                                frames.forEachIndexed { index, item ->
                                    FrameCard(
                                        frame = item,
                                        index = index,
                                        firstTs = firstTs,
                                        backdrop = backdrop,
                                        onClick = { showingFrame = item }
                                    )
                                    if (index < frames.size - 1) {
                                        Row(
                                            Modifier
                                                .padding(horizontal = 8.dp)
                                                .fillMaxWidth(),
                                        ) {
                                            BasicText(
                                                "",
                                                Modifier
                                                    .weight(1f)
                                                    .padding(top = 0.dp),
                                                style = TextStyle(fontSize = 1.sp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // 单帧详情视图
                    GlassCard(
                        backdrop = backdrop,
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            val seq = frame.optInt("seq", 0)
                            val ts = frame.optLong("timestampMs", 0L)
                            val offsetSec = ((ts - firstTs).coerceAtLeast(0L)) / 1000.0
                            BasicText(
                                getString(
                                    R.string.recording_detail_frame,
                                    seq,
                                    String.format("%06.1fs", offsetSec)
                                ),
                                style = TextStyle(color = colors.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            )
                            val data = frame.optJSONObject("data")
                            BasicText(
                                if (data != null) data.toString(2) else getString(R.string.recording_detail_empty),
                                Modifier.padding(top = 10.dp).fillMaxWidth(),
                                style = TextStyle(
                                    color = colors.textSecondary,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun FrameCard(
        frame: JSONObject,
        index: Int,
        firstTs: Long,
        backdrop: com.kyant.backdrop.Backdrop,
        onClick: () -> Unit
    ) {
        val colors = glassColors()
        val ts = frame.optLong("timestampMs", 0L)
        val offsetSec = ((ts - firstTs).coerceAtLeast(0L)) / 1000.0
        val seq = frame.optInt("seq", index + 1)

        GlassCard(
            backdrop = backdrop,
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .clickable(onClick = onClick),
            containerColor = colors.bgTertiary.copy(alpha = 0.3f),
            cornerRadius = 14.dp,
            contentPadding = 12.dp
        ) {
            Column {
                BasicText(
                    String.format(
                        "#%d  [+%06.1fs]  %s",
                        seq,
                        offsetSec,
                        formatTime(ts)
                    ),
                    style = TextStyle(color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                )
                val data = frame.optJSONObject("data")
                val summary = buildFrameSummary(data ?: JSONObject())
                if (summary.isNotEmpty()) {
                    BasicText(
                        summary,
                        Modifier.padding(top = 4.dp),
                        style = TextStyle(color = colors.textSecondary, fontSize = 12.sp)
                    )
                }
            }
        }
    }

    // ---------- 数据 ----------

    private fun loadFrames() {
        detailStatus = getString(R.string.recording_detail_loading)
        executor.execute {
            val result = ApiClient.getRecordingFrames(recordingId)
            val arr = result.data?.optJSONArray("frames")
            val loaded = mutableListOf<JSONObject>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { loaded.add(it) }
                }
            }
            runOnUiThread {
                frames.clear()
                frames.addAll(loaded)
                firstTs = loaded.firstOrNull()?.optLong("timestampMs", 0L) ?: 0L
                val lastTs = loaded.lastOrNull()?.optLong("timestampMs", 0L) ?: firstTs
                detailStatus = getString(
                    R.string.home_saved_meta_recording,
                    formatDuration(((lastTs - firstTs).coerceAtLeast(0L)) / 1000L),
                    loaded.size
                )
            }
        }
    }

    /** 帧摘要：位置 / 基站 / WiFi / 蓝牙 / GNSS / 传感器 数量与要点。 */
    private fun buildFrameSummary(data: JSONObject): String {
        val sb = StringBuilder()
        data.optJSONObject("location")?.let { loc ->
            val keys = loc.keys()
            while (keys.hasNext()) {
                val item = loc.optJSONObject(keys.next()) ?: continue
                val lat = item.optDouble("latitude", Double.NaN)
                val lon = item.optDouble("longitude", Double.NaN)
                if (!lat.isNaN() && !lon.isNaN()) {
                    sb.append("位置: ").append(String.format("%.6f, %.6f", lat, lon)).append("\n")
                    break
                }
            }
        }
        val cellN = data.optJSONObject("cell")?.optJSONArray("cells")?.length() ?: 0
        if (cellN > 0) sb.append("基站: ").append(cellN).append(" 个\n")
        val wifiN = data.optJSONObject("wifi")?.optJSONArray("networks")?.length() ?: 0
        if (wifiN > 0) sb.append("WiFi: ").append(wifiN).append(" 个\n")
        val bt = data.optJSONObject("bluetooth")
        val btN = (bt?.optJSONArray("devices")?.length() ?: 0) +
            (bt?.optJSONArray("bonded")?.length() ?: 0)
        if (btN > 0) sb.append("蓝牙: ").append(btN).append(" 个\n")
        val gnssN = data.optJSONObject("gnss")?.optInt("satelliteCount", 0) ?: 0
        if (gnssN > 0) sb.append("GNSS: ").append(gnssN).append(" 颗\n")
        val sensor = data.optJSONObject("sensor")
        if (sensor != null) {
            val parts = mutableListOf<String>()
            sensor.optJSONArray("accelerometer")?.let {
                if (it.length() >= 3) parts.add(
                    String.format("acc=%.2f,%.2f,%.2f", it.optDouble(0), it.optDouble(1), it.optDouble(2))
                )
            }
            sensor.optJSONArray("gyroscope")?.let {
                if (it.length() >= 3) parts.add(
                    String.format("gyr=%.3f,%.3f,%.3f", it.optDouble(0), it.optDouble(1), it.optDouble(2))
                )
            }
            if (sensor.has("stepCounter")) parts.add("步=" + sensor.optLong("stepCounter", 0L))
            if (parts.isNotEmpty()) sb.append("传感器: ").append(parts.joinToString(" ")).append("\n")
        }
        return sb.toString().trimEnd('\n')
    }

    private fun formatTime(millis: Long): String {
        if (millis <= 0) return ""
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(millis))
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0s"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }
}
