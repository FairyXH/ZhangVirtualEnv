package io.github.fairyxh.VirtualEnv.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.app.ApiClient
import io.github.fairyxh.VirtualEnv.app.collect.EnvironmentCollector
import io.github.fairyxh.VirtualEnv.core.model.ApiResult
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 主页：模块状态 + 一键采集（快照）+ 持续记录（录像）+ 录像回放。
 *
 * - 快照模式：一键采集当前环境并保存为 env_snapshot（type=collect）。
 * - 持续记录模式：按采样间隔采集位置/基站/WiFi/蓝牙帧，逐帧写入 Backend 录像。
 * - 回放：勾选多个录像顺序播放，支持循环 / 暂停 / 继续 / 停止。
 */
class HomeFragment : Fragment() {

    companion object {
        private const val TAG_SCOPE = "UI"
        private const val REQ_PERMISSIONS = 1001

        private val REQUIRED_PERMISSIONS: Array<String>
            get() {
                val base = mutableListOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.READ_PHONE_STATE,
                )
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    base.add(Manifest.permission.BLUETOOTH_SCAN)
                    base.add(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    base.add(Manifest.permission.BLUETOOTH)
                    base.add(Manifest.permission.BLUETOOTH_ADMIN)
                }
                return base.toTypedArray()
            }
    }

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var collectButton: Button
    private lateinit var collectResult: TextView
    private lateinit var collectNameInput: android.widget.EditText
    private lateinit var collectRemarkInput: android.widget.EditText
    private lateinit var saveCollectButton: Button
    private lateinit var savedCollectEmpty: TextView
    private lateinit var savedCollectList: android.widget.LinearLayout

    // 持续记录（录像）
    private lateinit var recordingNameInput: android.widget.EditText
    private lateinit var recordingIntervalInput: android.widget.EditText
    private lateinit var recordingStartButton: Button
    private lateinit var recordingStopButton: Button
    private lateinit var recordingStatus: TextView

    // 录像回放
    private lateinit var playbackLoopCheck: CheckBox
    private lateinit var playbackPlayButton: Button
    private lateinit var playbackPauseButton: Button
    private lateinit var playbackStopButton: Button
    private lateinit var playbackStatus: TextView
    private lateinit var recordingsEmpty: TextView
    private lateinit var recordingList: android.widget.LinearLayout

    private val executor = Executors.newSingleThreadExecutor()
    private var collector: EnvironmentCollector? = null
    private var lastCollectResult: JSONObject? = null

    private var recordingId = -1L
    private var recordingFrames = 0
    private var recordingName = ""
    private var recordingScheduler: ScheduledExecutorService? = null
    private var pendingRecordingStart = false
    private val selectedRecordingIds = LinkedHashSet<Long>()
    private val playbackPollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val playbackPoll = object : Runnable {
        override fun run() {
            if (!isAdded) return
            executor.execute {
                val result = ApiClient.getRecordingStatus()
                requireActivity().runOnUiThread {
                    renderPlaybackStatus(result)
                    if (isAdded) playbackPollHandler.postDelayed(this, 1000L)
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_home, container, false)
        statusDot = root.findViewById(R.id.statusDot)
        statusText = root.findViewById(R.id.statusText)
        statusDetail = root.findViewById(R.id.statusDetail)
        collectButton = root.findViewById(R.id.collectButton)
        collectResult = root.findViewById(R.id.collectResult)
        collectNameInput = root.findViewById(R.id.collectNameInput)
        collectRemarkInput = root.findViewById(R.id.collectRemarkInput)
        saveCollectButton = root.findViewById(R.id.saveCollectButton)
        savedCollectEmpty = root.findViewById(R.id.savedCollectEmpty)
        savedCollectList = root.findViewById(R.id.savedCollectList)
        collector = EnvironmentCollector(requireContext())

        recordingNameInput = root.findViewById(R.id.recordingNameInput)
        recordingIntervalInput = root.findViewById(R.id.recordingIntervalInput)
        recordingStartButton = root.findViewById(R.id.recordingStartButton)
        recordingStopButton = root.findViewById(R.id.recordingStopButton)
        recordingStatus = root.findViewById(R.id.recordingStatus)

        playbackLoopCheck = root.findViewById(R.id.playbackLoopCheck)
        playbackPlayButton = root.findViewById(R.id.playbackPlayButton)
        playbackPauseButton = root.findViewById(R.id.playbackPauseButton)
        playbackStopButton = root.findViewById(R.id.playbackStopButton)
        playbackStatus = root.findViewById(R.id.playbackStatus)
        recordingsEmpty = root.findViewById(R.id.recordingsEmpty)
        recordingList = root.findViewById(R.id.recordingList)

        collectButton.setOnClickListener { startCollect() }
        saveCollectButton.setOnClickListener { saveCollect() }
        recordingStartButton.setOnClickListener { startRecording() }
        recordingStopButton.setOnClickListener { stopRecording() }
        playbackPlayButton.setOnClickListener { playSelectedRecordings() }
        playbackPauseButton.setOnClickListener {
            executor.execute {
                val result = ApiClient.pauseRecordingPlayback()
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
        playbackStopButton.setOnClickListener {
            executor.execute {
                val result = ApiClient.stopRecordingPlayback()
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }

        recordingStatus.text = getString(R.string.home_recording_idle)
        playbackStatus.text = getString(R.string.home_playback_idle)
        refreshBackendStatus()
        refreshSavedCollects()
        refreshRecordings()
        return root
    }

    override fun onResume() {
        super.onResume()
        refreshBackendStatus()
        refreshSavedCollects()
        refreshRecordings()
        playbackPollHandler.removeCallbacks(playbackPoll)
        playbackPollHandler.post(playbackPoll)
    }

    private fun refreshBackendStatus() {
        executor.execute {
            val reachable = ApiClient.ping()
            val info = if (reachable) ApiClient.getSystemInfo() else null
            requireActivity().runOnUiThread {
                statusDot.isEnabled = reachable
                if (reachable) {
                    statusText.text = getString(R.string.home_status_ok)
                    val enabledText = if (ApiClient.getLocationStatus().data?.optBoolean("enabled", false) == true) {
                        getString(R.string.location_enabled)
                    } else {
                        getString(R.string.location_disabled)
                    }
                    statusDetail.text = getString(
                        R.string.home_status_detail,
                        info?.data?.optString("phase", "1") ?: "-",
                        enabledText
                    )
                } else {
                    statusText.text = getString(R.string.home_status_offline)
                    statusDetail.text = getString(R.string.home_status_offline_detail)
                }
            }
        }
    }

    private fun hasPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestMissingPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            if (pendingRecordingStart) {
                pendingRecordingStart = false
                doStartRecording()
            } else {
                doCollect()
            }
        }
    }

    // ---------- 快照采集 ----------

    private fun startCollect() {
        if (!hasPermissions()) {
            pendingRecordingStart = false
            requestMissingPermissions()
            return
        }
        doCollect()
    }

    private fun doCollect() {
        collectButton.isEnabled = false
        collectResult.text = getString(R.string.home_collect_running)
        collectResult.visibility = View.VISIBLE
        executor.execute {
            collector?.collectAll { result ->
                lastCollectResult = result
                requireActivity().runOnUiThread {
                    collectButton.isEnabled = true
                    collectResult.text = summarize(result)
                    collectResult.visibility = View.VISIBLE
                    ZLog.i(TAG_SCOPE, "collect done: ${result.length()}")
                }
            }
        }
    }

    // ---------- 已保存采集 ----------

    private fun saveCollect() {
        val name = collectNameInput.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), R.string.home_collect_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        val result = lastCollectResult
        if (result == null) {
            Toast.makeText(requireContext(), R.string.home_collect_none, Toast.LENGTH_SHORT).show()
            return
        }
        val remark = collectRemarkInput.text.toString().trim()
        executor.execute {
            val apiResult = ApiClient.createEnvSnapshot(name, remark, "collect", result)
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), apiResult.message, Toast.LENGTH_SHORT).show()
                if (apiResult.code == ApiResult.CODE_OK) {
                    collectNameInput.text.clear()
                    collectRemarkInput.text.clear()
                    refreshSavedCollects()
                }
            }
        }
    }

    private fun refreshSavedCollects() {
        executor.execute {
            val result = ApiClient.listEnvSnapshots()
            requireActivity().runOnUiThread {
                renderSavedCollects(result)
            }
        }
    }

    private fun renderSavedCollects(result: ApiResult) {
        savedCollectList.removeAllViews()
        val snapshots = result.data?.optJSONArray("snapshots") ?: return
        val collects = mutableListOf<JSONObject>()
        for (i in 0 until snapshots.length()) {
            val item = snapshots.optJSONObject(i) ?: continue
            if (item.optString("type", "") == "collect") collects.add(item)
        }
        savedCollectEmpty.visibility = if (collects.isEmpty()) View.VISIBLE else View.GONE
        if (collects.isEmpty()) return

        collects.forEach { item ->
            val row = layoutInflater.inflate(R.layout.item_saved_collect, savedCollectList, false)
            row.findViewById<TextView>(R.id.collectName).text = item.optString("name", "")
            val remark = item.optString("remark", "")
            val remarkView = row.findViewById<TextView>(R.id.collectRemark)
            if (remark.isBlank()) {
                remarkView.visibility = View.GONE
            } else {
                remarkView.text = getString(R.string.location_point_remark_format, remark)
            }
            row.findViewById<TextView>(R.id.collectMeta).text = formatTime(item.optLong("createTime", 0L))
            row.findViewById<Button>(R.id.useButton).setOnClickListener {
                useCollect(item)
            }
            row.findViewById<Button>(R.id.deleteButton).setOnClickListener {
                deleteCollect(item.optLong("id"))
            }
            savedCollectList.addView(row)
        }
    }

    /** 一键使用采集包：整体加载到 WiFi / 基站 / BLE 模拟引擎。 */
    private fun useCollect(item: JSONObject) {
        val id = item.optLong("id")
        val name = item.optString("name", "")
        executor.execute {
            val result = ApiClient.useEnvSnapshot(id)
            requireActivity().runOnUiThread {
                if (result.code == ApiResult.CODE_OK) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.home_collect_applied, name),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun deleteCollect(id: Long) {
        executor.execute {
            val result = ApiClient.deleteEnvSnapshot(id)
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                if (result.code == ApiResult.CODE_OK) {
                    refreshSavedCollects()
                }
            }
        }
    }

    // ---------- 持续记录（录像） ----------

    private fun startRecording() {
        if (!hasPermissions()) {
            pendingRecordingStart = true
            requestMissingPermissions()
            return
        }
        doStartRecording()
    }

    private fun doStartRecording() {
        if (recordingId > 0) {
            Toast.makeText(requireContext(), R.string.home_recording_running, Toast.LENGTH_SHORT).show()
            return
        }
        val name = recordingNameInput.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), R.string.home_recording_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        val interval = (recordingIntervalInput.text.toString().toIntOrNull() ?: 5).coerceIn(2, 300)
        recordingIntervalInput.setText(interval.toString())
        executor.execute {
            val result = ApiClient.startRecording(name, "")
            requireActivity().runOnUiThread {
                if (result.code == ApiResult.CODE_OK) {
                    recordingId = result.data?.optLong("id", -1L) ?: -1L
                    recordingFrames = 0
                    recordingName = name
                    recordingNameInput.text.clear()
                    Toast.makeText(requireContext(), R.string.home_recording_started, Toast.LENGTH_SHORT).show()
                    startSamplingLoop(interval)
                    recordingStatus.text = getString(R.string.home_recording_running, name, recordingFrames)
                } else {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startSamplingLoop(intervalSec: Int) {
        recordingScheduler?.shutdownNow()
        val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ZVE-Recorder").apply { isDaemon = true }
        }
        recordingScheduler = scheduler
        scheduler.scheduleWithFixedDelay(
            {
                try {
                    if (recordingId <= 0) return@scheduleWithFixedDelay
                    collector?.collectAll { frame ->
                        try {
                            val result = ApiClient.appendRecordingFrame(recordingId, frame)
                            if (result.code == ApiResult.CODE_OK) {
                                recordingFrames++
                                requireActivity().runOnUiThread {
                                    recordingStatus.text = getString(
                                        R.string.home_recording_running,
                                        recordingName,
                                        recordingFrames
                                    )
                                }
                            }
                        } catch (t: Throwable) {
                            ZLog.w(TAG_SCOPE, "append frame failed", t)
                        }
                    }
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "recording sample failed", t)
                }
            },
            0,
            intervalSec.toLong(),
            TimeUnit.SECONDS
        )
    }

    private fun stopRecording() {
        recordingScheduler?.shutdownNow()
        recordingScheduler = null
        val id = recordingId
        if (id <= 0) return
        recordingId = -1L
        executor.execute {
            val result = ApiClient.stopRecording(id)
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), R.string.home_recording_stopped, Toast.LENGTH_SHORT).show()
                recordingStatus.text = getString(R.string.home_recording_idle)
                if (result.code == ApiResult.CODE_OK) {
                    refreshRecordings()
                }
            }
        }
    }

    // ---------- 录像回放 ----------

    private fun refreshRecordings() {
        executor.execute {
            val result = ApiClient.listRecordings()
            requireActivity().runOnUiThread {
                renderRecordings(result)
            }
        }
    }

    private fun renderRecordings(result: ApiResult) {
        recordingList.removeAllViews()
        val recordings = result.data?.optJSONArray("recordings") ?: return
        val count = recordings.length()
        recordingsEmpty.visibility = if (count == 0) View.VISIBLE else View.GONE
        if (count == 0) return

        for (i in 0 until count) {
            val item = recordings.optJSONObject(i) ?: continue
            val id = item.optLong("id", -1L)
            val row = layoutInflater.inflate(R.layout.item_saved_recording, recordingList, false)
            row.findViewById<TextView>(R.id.recordingName).text = item.optString("name", "")
            val durationSec = item.optLong("durationMs", 0L) / 1000L
            row.findViewById<TextView>(R.id.recordingMeta).text = getString(
                R.string.home_recording_meta,
                formatDuration(durationSec),
                item.optInt("frameCount", 0)
            )
            val check = row.findViewById<CheckBox>(R.id.recordingCheck)
            check.isChecked = selectedRecordingIds.contains(id)
            check.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    selectedRecordingIds.add(id)
                } else {
                    selectedRecordingIds.remove(id)
                }
            }
            row.findViewById<Button>(R.id.recordingPlayButton).setOnClickListener {
                playRecordings(listOf(id), playbackLoopCheck.isChecked)
            }
            row.findViewById<Button>(R.id.recordingDeleteButton).setOnClickListener {
                deleteRecording(id)
            }
            recordingList.addView(row)
        }
    }

    private fun playSelectedRecordings() {
        val ids = selectedRecordingIds.toList()
        if (ids.isEmpty()) {
            Toast.makeText(requireContext(), R.string.home_playback_none_selected, Toast.LENGTH_SHORT).show()
            return
        }
        playRecordings(ids, playbackLoopCheck.isChecked)
    }

    private fun playRecordings(ids: List<Long>, loop: Boolean) {
        executor.execute {
            val result = ApiClient.playRecordings(ids, loop)
            requireActivity().runOnUiThread {
                if (result.code == ApiResult.CODE_OK) {
                    Toast.makeText(requireContext(), R.string.home_playback_started, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun deleteRecording(id: Long) {
        executor.execute {
            val result = ApiClient.deleteRecording(id)
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                if (result.code == ApiResult.CODE_OK) {
                    selectedRecordingIds.remove(id)
                    refreshRecordings()
                }
            }
        }
    }

    private fun renderPlaybackStatus(result: ApiResult) {
        val data = result.data ?: return
        val playing = data.optBoolean("playing", false)
        val paused = data.optBoolean("paused", false)
        if (!playing) {
            playbackStatus.text = getString(R.string.home_playback_idle)
            return
        }
        val playIndex = data.optInt("playIndex", 0) + 1
        val playlistSize = data.optInt("playlistSize", 1).coerceAtLeast(1)
        val frameProgress = data.optInt("frameProgress", 0)
        val frameCount = data.optInt("frameCount", 0)
        val loopText = if (data.optBoolean("loop", false)) " · 循环" else ""
        playbackStatus.text = if (paused) {
            getString(R.string.home_playback_paused, playIndex, playlistSize, frameProgress, frameCount)
        } else {
            getString(R.string.home_playback_playing, playIndex, playlistSize, frameProgress, frameCount, loopText)
        }
    }

    // ---------- 工具 ----------

    private fun formatTime(millis: Long): String {
        if (millis <= 0) return ""
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
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

    private fun summarize(result: JSONObject): String {
        val loc = result.optJSONObject("location")
        val cell = result.optJSONObject("cell")
        val wifi = result.optJSONObject("wifi")
        val bt = result.optJSONObject("bluetooth")
        val gnss = result.optJSONObject("gnss")

        val sb = StringBuilder()
        sb.append("位置: ")
        if (loc != null && loc.length() > 0) {
            val first = loc.keys().next()
            val item = loc.optJSONObject(first)
            sb.append(item?.optString("latitude")).append(", ").append(item?.optString("longitude"))
        } else {
            sb.append("无")
        }
        sb.append("\n")
        sb.append("基站: ").append(cell?.optJSONArray("cells")?.length() ?: 0).append(" 个\n")
        sb.append("WiFi: ").append(wifi?.optJSONArray("networks")?.length() ?: 0).append(" 个\n")
        val bondedCount = bt?.optJSONArray("bonded")?.length() ?: 0
        val nearbyCount = bt?.optJSONArray("devices")?.length() ?: 0
        sb.append("蓝牙: 已配对 ").append(bondedCount).append(" 个 · 附近 ").append(nearbyCount).append(" 个\n")
        val gnssCount = gnss?.optInt("satelliteCount", 0) ?: 0
        sb.append("GNSS: ").append(gnss?.optString("available", "false")).append(
            if (gnssCount > 0) {
                " ($gnssCount 颗卫星)"
            } else {
                ""
            }
        )
        return sb.toString()
    }

    override fun onDestroyView() {
        // 离开主页时结束录制采样（避免无主录制）
        recordingScheduler?.shutdownNow()
        recordingScheduler = null
        if (recordingId > 0) {
            val id = recordingId
            recordingId = -1L
            executor.execute { ApiClient.stopRecording(id) }
        }
        playbackPollHandler.removeCallbacks(playbackPoll)
        executor.shutdown()
        super.onDestroyView()
    }
}
