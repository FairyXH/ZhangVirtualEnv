package io.github.fairyxh.VirtualEnv.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.app.ApiClient
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 录像帧详情页：按帧列出录像保存的各信息摘要（帧与帧之间带分隔符），
 * 点击任意帧切换查看该帧保存的全部原始数据（各信息 JSON），带返回按钮。
 *
 * 满足需求：已保存采集详情可查看具体哪个帧保存了哪些信息的原始数据/详细信息。
 */
class RecordingDetailActivity : Activity() {

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

    private lateinit var detailTitle: TextView
    private lateinit var detailStatus: TextView
    private lateinit var listScroll: ScrollView
    private lateinit var listContainer: LinearLayout
    private lateinit var frameDetailContainer: LinearLayout
    private lateinit var frameTitle: TextView
    private lateinit var frameRawText: TextView

    private var recordingId = -1L
    private var recordingName = ""
    private var frames: List<JSONObject> = emptyList()
    private var firstTs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recording_detail)

        recordingId = intent.getLongExtra(EXTRA_ID, -1L)
        recordingName = intent.getStringExtra(EXTRA_NAME) ?: ""

        detailTitle = findViewById(R.id.detailTitle)
        detailStatus = findViewById(R.id.detailStatus)
        listScroll = findViewById(R.id.listScroll)
        listContainer = findViewById(R.id.listContainer)
        frameDetailContainer = findViewById(R.id.frameDetailContainer)
        frameTitle = findViewById(R.id.frameTitle)
        frameRawText = findViewById(R.id.frameRawText)

        detailTitle.text = getString(R.string.recording_detail_title) +
            (if (recordingName.isBlank()) "" else " · $recordingName")

        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }
        findViewById<TextView>(R.id.frameBackButton).setOnClickListener { showList() }

        if (recordingId <= 0) {
            detailStatus.text = getString(R.string.recording_detail_empty)
            return
        }
        loadFrames()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun loadFrames() {
        detailStatus.text = getString(R.string.recording_detail_loading)
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
                frames = loaded
                firstTs = loaded.firstOrNull()?.optLong("timestampMs", 0L) ?: 0L
                val lastTs = loaded.lastOrNull()?.optLong("timestampMs", 0L) ?: firstTs
                detailStatus.text = getString(
                    R.string.home_saved_meta_recording,
                    formatDuration(((lastTs - firstTs).coerceAtLeast(0L)) / 1000L),
                    loaded.size
                )
                renderList()
            }
        }
    }

    private fun renderList() {
        listContainer.removeAllViews()
        if (frames.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.recording_detail_empty)
                setTextColor(getColor(R.color.text_tertiary))
                textSize = 13f
                setPadding(0, dp(24), 0, dp(24))
                gravity = Gravity.CENTER
            }
            listContainer.addView(empty)
            return
        }
        // 帧间分隔符提示
        val hint = TextView(this).apply {
            text = getString(R.string.recording_detail_click_hint)
            setTextColor(getColor(R.color.text_tertiary))
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        }
        listContainer.addView(hint)

        frames.forEachIndexed { index, frame ->
            // 帧卡片：点击查看该帧原始数据
            val card = buildFrameCard(frame, index)
            listContainer.addView(card)
            // 帧与帧之间分隔符
            if (index < frames.size - 1) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1
                    )
                    setBackgroundColor(getColor(R.color.separator))
                }
                listContainer.addView(divider)
            }
        }
    }

    /** 构建单帧卡片：帧号 / 时间偏移 / 时间 + 各信息摘要。 */
    private fun buildFrameCard(frame: JSONObject, index: Int): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = getDrawable(R.drawable.bg_field)
            isClickable = true
            isFocusable = true
        }
        val ts = frame.optLong("timestampMs", 0L)
        val offsetSec = ((ts - firstTs).coerceAtLeast(0L)) / 1000.0
        val seq = frame.optInt("seq", index + 1)

        val head = TextView(this).apply {
            text = String.format(
                "#%d  [+%06.1fs]  %s",
                seq,
                offsetSec,
                formatTime(ts)
            )
            setTextColor(getColor(R.color.accent))
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        card.addView(head)

        val data = frame.optJSONObject("data")
        val summary = buildFrameSummary(data ?: JSONObject())
        if (summary.isNotEmpty()) {
            val body = TextView(this).apply {
                text = summary
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, dp(4), 0, 0)
            }
            card.addView(body)
        }
        card.setOnClickListener { showFrameDetail(frame) }
        return card
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

    /** 切换显示单帧完整原始数据（各信息 JSON）。 */
    private fun showFrameDetail(frame: JSONObject) {
        val seq = frame.optInt("seq", 0)
        val ts = frame.optLong("timestampMs", 0L)
        val offsetSec = ((ts - firstTs).coerceAtLeast(0L)) / 1000.0
        frameTitle.text = String.format(
            getString(R.string.recording_detail_frame),
            seq,
            String.format("%06.1fs", offsetSec)
        )
        val data = frame.optJSONObject("data")
        frameRawText.text = if (data != null) data.toString(2) else getString(R.string.recording_detail_empty)
        listScroll.visibility = View.GONE
        frameDetailContainer.visibility = View.VISIBLE
    }

    private fun showList() {
        frameDetailContainer.visibility = View.GONE
        listScroll.visibility = View.VISIBLE
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
