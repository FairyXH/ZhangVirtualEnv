package io.github.fairyxh.VirtualEnv.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * 录像帧详情页：按帧列出录像保存的各信息摘要，点击任意帧切换查看该帧保存的
 * 全部原始数据（各信息 JSON），带返回按钮。
 *
 * 性能策略：
 * - 列表按页加载/渲染（每页 [pageSize] 帧），帧数再多也只组合一页。
 * - 即使旧 Backend 忽略 offset/limit 返回全量帧，客户端也防御性截取本页。
 * - 单帧 JSON 在后台线程格式化，UI 用 LazyColumn 逐行虚拟化渲染，
 *   避免超大文本一次性布局导致 ANR/黑屏。
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

    /** 每页渲染帧数：控制单页组合规模，避免帧数过大导致卡顿/无法查看。 */
    private val pageSize = 20
    private val pageFrames = mutableStateListOf<JSONObject>()
    private var totalFrames by mutableStateOf(0)
    private var pageIndex by mutableStateOf(0)
    private var loadingPage by mutableStateOf(false)
    private var firstTs = 0L
    private var showingFrame by mutableStateOf<JSONObject?>(null)

    /** 当前帧原始 JSON 的格式化行（后台线程生成，避免 UI 线程卡顿）。 */
    private val detailJsonLines = mutableStateListOf<String>()
    private var detailJsonReady by mutableStateOf(false)

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
            val colors = glassColors()
            val scrollState = rememberScrollState()
            // 翻页后不回顶：scrollTo 是挂起等待布局的版本，在重组/协程取消时
            // 可能卡住渲染；翻页内容切换后滚动位置保持即可，不为此引入挂起风险
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
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
                    // 列表视图：不包单个巨型 GlassCard。大高度 backdrop（几千 px）
                    // 在 ColorOS/Oplus 上 RuntimeShader/RenderEffect 渲染会整片失败，
                    // 表现为黑屏/白屏；改为每帧独立小 GlassCard 直接排列。
                    BasicText(
                        detailStatus,
                        style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                    )
                    if (pageFrames.isEmpty()) {
                        GlassCard(
                            backdrop = backdrop,
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                        ) {
                            BasicText(
                                getString(R.string.recording_detail_empty),
                                Modifier
                                    .padding(16.dp)
                                    .fillMaxWidth(),
                                style = TextStyle(color = colors.textTertiary, fontSize = 13.sp)
                            )
                        }
                    } else {
                        BasicText(
                            getString(R.string.recording_detail_click_hint),
                            Modifier.padding(top = 4.dp),
                            style = TextStyle(color = colors.textTertiary, fontSize = 12.sp)
                        )
                        pageFrames.forEachIndexed { index, item ->
                            FrameCard(
                                frame = item,
                                index = index,
                                firstTs = firstTs,
                                backdrop = backdrop,
                                onClick = {
                                    showingFrame = item
                                    loadDetailJson(item)
                                }
                            )
                        }
                                // 帧数超过单页上限时显示翻页控件
                                if (totalFrames > pageSize) {
                                    val totalPages =
                                        ((totalFrames + pageSize - 1) / pageSize).coerceAtLeast(1)
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(top = 12.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val canPrev = pageIndex > 0 && !loadingPage
                                        val canNext = pageIndex < totalPages - 1 && !loadingPage
                                        GlassPill(
                                            onClick = { if (canPrev) loadPage(pageIndex - 1) },
                                            backdrop = backdrop,
                                            selected = false,
                                            containerColor = colors.bgTertiary.copy(
                                                alpha = if (canPrev) 0.4f else 0.12f
                                            ),
                                            height = 34.dp
                                        ) {
                                            BasicText(
                                                getString(R.string.recording_detail_prev),
                                                Modifier.padding(horizontal = 14.dp),
                                                style = TextStyle(
                                                    color = if (canPrev) colors.textPrimary else colors.textTertiary,
                                                    fontSize = 13.sp
                                                )
                                            )
                                        }
                                        BasicText(
                                            if (loadingPage) "…" else getString(
                                                R.string.recording_detail_page,
                                                pageIndex + 1,
                                                totalPages
                                            ),
                                            Modifier.padding(horizontal = 14.dp),
                                            style = TextStyle(
                                                color = colors.textSecondary,
                                                fontSize = 13.sp
                                            )
                                        )
                                        GlassPill(
                                            onClick = { if (canNext) loadPage(pageIndex + 1) },
                                            backdrop = backdrop,
                                            selected = false,
                                            containerColor = colors.bgTertiary.copy(
                                                alpha = if (canNext) 0.4f else 0.12f
                                            ),
                                            height = 34.dp
                                        ) {
                                            BasicText(
                                                getString(R.string.recording_detail_next),
                                                Modifier.padding(horizontal = 14.dp),
                                                style = TextStyle(
                                                    color = if (canNext) colors.textPrimary else colors.textTertiary,
                                                    fontSize = 13.sp
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                    } else {
                    // 单帧详情视图：JSON 逐行虚拟化渲染（固定高度，避免嵌套滚动无限测量），
                    // 帧数据再大也不会一次性布局整段文本
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
                                style = TextStyle(
                                    color = colors.accent,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            if (!detailJsonReady) {
                                BasicText(
                                    getString(R.string.recording_detail_loading),
                                    Modifier.padding(top = 10.dp),
                                    style = TextStyle(color = colors.textTertiary, fontSize = 12.sp)
                                )
                            } else if (detailJsonLines.isEmpty()) {
                                BasicText(
                                    getString(R.string.recording_detail_empty),
                                    Modifier.padding(top = 10.dp),
                                    style = TextStyle(color = colors.textTertiary, fontSize = 13.sp)
                                )
                            } else {
                                LazyColumn(
                                    Modifier
                                        .padding(top = 10.dp)
                                        .fillMaxWidth()
                                        .height(520.dp)
                                ) {
                                    items(detailJsonLines) { line ->
                                        BasicText(
                                            line,
                                            Modifier.fillMaxWidth(),
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

        // 帧卡片改用普通纯色圆角卡片：Oplus15 上大量 drawBackdrop 卡片同时渲染
        // 会整片失败（黑屏/白屏），帧列表可用性优先于玻璃质感。
        androidx.compose.foundation.layout.Box(
            Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                .background(colors.bgTertiary.copy(alpha = 0.35f))
                .clickable(onClick = onClick)
                .padding(12.dp)
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
        loadPage(0)
    }

    /** 加载指定页（0 起）：每次只取一页帧，渲染与内存都不随总帧数线性膨胀。 */
    private fun loadPage(index: Int) {
        if (loadingPage || index < 0) return
        loadingPage = true
        val offset = index * pageSize
        executor.execute {
            try {
                val result = ApiClient.getRecordingFrames(recordingId, offset, pageSize)
                runOnUiThread {
                    loadingPage = false
                    val data = result.data
                    val arr = data?.optJSONArray("frames")
                    val loaded = mutableListOf<JSONObject>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.let { loaded.add(it) }
                        }
                    }
                    // 旧 Backend 可能忽略 offset/limit 直接返回全量帧：
                    // 只要响应帧数超过单页上限，就视为全量并防御性截取本页范围，
                    // 保证 UI 永远只组合 pageSize 个卡片。
                    val paged = if (loaded.size > pageSize) {
                        val from = offset.coerceIn(0, loaded.size)
                        val to = (offset + pageSize).coerceIn(from, loaded.size)
                        loaded.subList(from, to)
                    } else loaded

                    totalFrames = data?.optInt("total", loaded.size) ?: loaded.size
                    val metaFirst = data?.optLong("firstTs", 0L) ?: 0L
                    if (metaFirst > 0) firstTs = metaFirst
                    // 旧 Backend 无元数据时用本页首帧时间戳回退，保证相对时间/时长显示正确
                    if (firstTs <= 0) {
                        firstTs = paged.firstOrNull()?.optLong("timestampMs", 0L) ?: 0L
                    }
                    // optLong 对缺失字段返回 0 而非 null，须显式判断后再回退到全量帧末帧
                    val metaLast = data?.optLong("lastTs", 0L) ?: 0L
                    val lastTs = if (metaLast > 0) metaLast
                    else loaded.lastOrNull()?.optLong("timestampMs", 0L) ?: firstTs
                    // 帧数变化导致页码越界（如录像被清空）：回到最后一页
                    val maxIndex = ((totalFrames - 1) / pageSize).coerceAtLeast(0)
                    if (paged.isEmpty() && totalFrames > 0 && index > maxIndex) {
                        loadPage(maxIndex)
                        return@runOnUiThread
                    }
                    pageIndex = index
                    pageFrames.clear()
                    pageFrames.addAll(paged)
                    detailStatus = getString(
                        R.string.home_saved_meta_recording,
                        formatDuration(((lastTs - firstTs).coerceAtLeast(0L)) / 1000L),
                        totalFrames
                    )
                }
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "load page failed: ${t.message}")
                runOnUiThread {
                    loadingPage = false
                    detailStatus = getString(R.string.recording_detail_empty)
                }
            }
        }
    }

    /** 点击帧后后台格式化该帧原始 JSON，避免 UI 线程处理大字符串。 */
    private fun loadDetailJson(frame: JSONObject) {
        detailJsonReady = false
        detailJsonLines.clear()
        executor.execute {
            try {
                val data = frame.optJSONObject("data")
                val text = if (data != null) data.toString(2)
                else getString(R.string.recording_detail_empty)
                val lines = text.split('\n')
                runOnUiThread {
                    detailJsonLines.clear()
                    detailJsonLines.addAll(lines)
                    detailJsonReady = true
                }
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "load frame json failed: ${t.message}")
                runOnUiThread {
                    detailJsonLines.clear()
                    detailJsonReady = true
                }
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
