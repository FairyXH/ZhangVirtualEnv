package io.github.fairyxh.VirtualEnv.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
 * 主页：模块状态 + 一键采集（快照/录像选项卡）+ 已保存采集（快照/录像统一）+ 采集回放。
 *
 * - 快照：一键采集当前环境 → 保存为 collect 包并拆轨道（基站/WiFi/GNSS/位置）。
 * - 录像：按采样间隔持续采集 → 保存录像，并生成路线轨道。
 * - 回放：选择已保存采集，快照一次性启用（自动同步位置/基站/WiFi/GNSS），
 *   录像支持开始 / 暂停 / 继续 / 重新开始 / 倍速。
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

    private data class SavedItem(
        val kind: String, // "snapshot" / "recording"
        val id: Long,
        val name: String,
        val remark: String,
        val meta: String
    )

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var collectButton: Button
    private lateinit var collectResult: TextView
    private lateinit var collectNameInput: android.widget.EditText
    private lateinit var collectRemarkInput: android.widget.EditText
    private lateinit var saveCollectButton: Button
    private lateinit var collectTabSnapshot: TextView
    private lateinit var collectTabRecording: TextView
    private lateinit var snapshotPanel: View
    private lateinit var recordingPanel: View
    private lateinit var savedCollectEmpty: TextView
    private lateinit var savedCollectList: android.widget.LinearLayout

    private lateinit var recordingNameInput: android.widget.EditText
    private lateinit var recordingIntervalInput: android.widget.EditText
    private lateinit var recordingStartButton: Button
    private lateinit var recordingStopButton: Button
    private lateinit var recordingStatus: TextView

    private lateinit var playbackSelected: TextView
    private lateinit var playbackEnableButton: Button
    private lateinit var playbackControls: View
    private lateinit var playbackSpeedRow: View
    private lateinit var playbackPauseButton: Button
    private lateinit var playbackResumeButton: Button
    private lateinit var playbackRestartButton: Button
    private lateinit var playbackStopButton: Button
    private lateinit var playbackSpeedInput: android.widget.EditText
    private lateinit var playbackSpeedButton: Button
    private lateinit var playbackStatus: TextView

    private val executor = Executors.newSingleThreadExecutor()
    private var collector: EnvironmentCollector? = null
    private var lastCollectResult: JSONObject? = null

    private var collectRecordingMode = false

    private var recordingId = -1L
    private var recordingFrames = 0
    private var recordingName = ""
    private var recordingScheduler: ScheduledExecutorService? = null
    private var pendingRecordingStart = false

    /** 统一已保存采集（快照 + 录像）。 */
    private val savedItems = mutableListOf<SavedItem>()
    private var selectedItem: SavedItem? = null

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
        collectTabSnapshot = root.findViewById(R.id.collectTabSnapshot)
        collectTabRecording = root.findViewById(R.id.collectTabRecording)
        snapshotPanel = root.findViewById(R.id.snapshotPanel)
        recordingPanel = root.findViewById(R.id.recordingPanel)
        savedCollectEmpty = root.findViewById(R.id.savedCollectEmpty)
        savedCollectList = root.findViewById(R.id.savedCollectList)
        collector = EnvironmentCollector(requireContext())

        recordingNameInput = root.findViewById(R.id.recordingNameInput)
        recordingIntervalInput = root.findViewById(R.id.recordingIntervalInput)
        recordingStartButton = root.findViewById(R.id.recordingStartButton)
        recordingStopButton = root.findViewById(R.id.recordingStopButton)
        recordingStatus = root.findViewById(R.id.recordingStatus)

        playbackSelected = root.findViewById(R.id.playbackSelected)
        playbackEnableButton = root.findViewById(R.id.playbackEnableButton)
        playbackControls = root.findViewById(R.id.playbackControls)
        playbackSpeedRow = root.findViewById(R.id.playbackSpeedRow)
        playbackPauseButton = root.findViewById(R.id.playbackPauseButton)
        playbackResumeButton = root.findViewById(R.id.playbackResumeButton)
        playbackRestartButton = root.findViewById(R.id.playbackRestartButton)
        playbackStopButton = root.findViewById(R.id.playbackStopButton)
        playbackSpeedInput = root.findViewById(R.id.playbackSpeedInput)
        playbackSpeedButton = root.findViewById(R.id.playbackSpeedButton)
        playbackStatus = root.findViewById(R.id.playbackStatus)

        collectButton.setOnClickListener { startCollect() }
        saveCollectButton.setOnClickListener { saveCollect() }
        recordingStartButton.setOnClickListener { startRecording() }
        recordingStopButton.setOnClickListener { stopRecording() }

        collectTabSnapshot.setOnClickListener { setCollectMode(false) }
        collectTabRecording.setOnClickListener { setCollectMode(true) }

        playbackEnableButton.setOnClickListener { enableSelectedPlayback() }
        playbackPauseButton.setOnClickListener {
            executor.execute {
                val result = ApiClient.pauseRecordingPlayback()
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
        playbackResumeButton.setOnClickListener {
            executor.execute {
                val result = ApiClient.resumeRecordingPlayback()
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
        playbackRestartButton.setOnClickListener {
            val sel = selectedItem
            if (sel?.kind == "recording") {
                executor.execute {
                    ApiClient.stopRecordingPlayback()
                    val result = ApiClient.playRecordings(listOf(sel.id), false)
                    requireActivity().runOnUiThread {
                        Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                    }
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
        playbackSpeedButton.setOnClickListener { setPlaybackSpeed() }

        recordingStatus.text = getString(R.string.home_recording_idle)
        playbackStatus.text = getString(R.string.home_playback_idle)
        setCollectMode(false)
        refreshBackendStatus()
        refreshSavedItems()
        return root
    }

    override fun onResume() {
        super.onResume()
        refreshBackendStatus()
        refreshSavedItems()
        playbackPollHandler.removeCallbacks(playbackPoll)
        playbackPollHandler.post(playbackPoll)
    }

    override fun onDestroyView() {
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

    // ---------- 选项卡 ----------

    private fun setCollectMode(recording: Boolean) {
        collectRecordingMode = recording
        snapshotPanel.visibility = if (recording) View.GONE else View.VISIBLE
        recordingPanel.visibility = if (recording) View.VISIBLE else View.GONE
        collectTabSnapshot.setBackgroundResource(
            if (recording) R.drawable.bg_pill_secondary else R.drawable.bg_pill
        )
        collectTabRecording.setBackgroundResource(
            if (recording) R.drawable.bg_pill else R.drawable.bg_pill_secondary
        )
    }

    // ---------- 状态 ----------

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

    // ---------- 权限 ----------

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
        Toast.makeText(requireContext(), R.string.home_collect_suspend_notice, Toast.LENGTH_LONG).show()
        executor.execute {
            ApiClient.suspendEnv()
            collector?.collectAll { result ->
                ApiClient.resumeEnv()
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

    // ---------- 保存采集（collect 包 + 轨道拆分） ----------

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
            if (apiResult.code == ApiResult.CODE_OK) {
                saveCollectTracks(name, remark, result)
            }
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), apiResult.message, Toast.LENGTH_SHORT).show()
                if (apiResult.code == ApiResult.CODE_OK) {
                    collectNameInput.text.clear()
                    collectRemarkInput.text.clear()
                    refreshSavedItems()
                }
            }
        }
    }

    /**
     * 采集拆分轨道：
     * - 基站 / WiFi / GNSS → 三个同名称备注的 env_snapshot（备注追加来源标记）
     * - 单次采集位置 → 位置模拟（已保存地点）
     */
    private fun saveCollectTracks(name: String, remark: String, collect: JSONObject) {
        val tag = io.github.fairyxh.VirtualEnv.core.Backend.TRACK_SOURCE_TAG
        val sourceRemark = if (remark.isBlank()) tag else "$remark $tag"

        val cellData = collect.optJSONObject("cell")
        val cells = cellData?.optJSONArray("cells") ?: org.json.JSONArray()
        if (cells.length() > 0) {
            val entries = org.json.JSONArray()
            for (i in 0 until cells.length()) {
                val src = cells.optJSONObject(i) ?: continue
                val e = JSONObject().apply {
                    put("type", src.optString("type", "LTE"))
                    put("mcc", src.optInt("mcc", -1))
                    put("mnc", src.optInt("mnc", -1))
                    put("tac", if (src.has("tac")) src.optInt("tac", -1) else src.optInt("lac", -1))
                    put("ci", if (src.has("ci")) src.optLong("ci", -1L) else src.optLong("cid", -1L))
                    put("pci", src.optInt("pci", -1))
                }
                entries.put(e)
            }
            if (entries.length() > 0) {
                ApiClient.createEnvSnapshot(
                    name, sourceRemark, "cell",
                    JSONObject().apply { put("entries", entries) }
                )
            }
        }

        val wifiData = collect.optJSONObject("wifi")
        val networks = wifiData?.optJSONArray("networks") ?: org.json.JSONArray()
        if (networks.length() > 0) {
            ApiClient.createEnvSnapshot(
                name, sourceRemark, "wifi",
                JSONObject().apply { put("networks", networks) }
            )
        }

        val gnssData = collect.optJSONObject("gnss")
        if (gnssData != null) {
            ApiClient.createEnvSnapshot(
                name, sourceRemark, "gnss",
                JSONObject().apply {
                    put("satelliteCount", gnssData.optInt("satelliteCount", -1))
                    put("usedInFix", gnssData.optInt("usedInFix", -1))
                    put("cn0", gnssData.optDouble("cn0", -1.0))
                }
            )
        }

        val loc = collect.optJSONObject("location")
        val lat = loc?.optDouble("latitude", Double.NaN) ?: Double.NaN
        val lon = loc?.optDouble("longitude", Double.NaN) ?: Double.NaN
        if (!lat.isNaN() && !lon.isNaN()) {
            ApiClient.createLocationPoint(name, sourceRemark, lat, lon)
        }
        ZLog.i(TAG_SCOPE, "collect tracks saved for name=$name")
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
                    ApiClient.suspendEnv()
                    Toast.makeText(
                        requireContext(),
                        R.string.home_recording_suspend_notice,
                        Toast.LENGTH_LONG
                    ).show()
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
            ApiClient.resumeEnv()
            val result = ApiClient.stopRecording(id)
            if (result.code == ApiResult.CODE_OK) {
                saveRecordingAsRoute(id, recordingName)
            }
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), R.string.home_recording_stopped, Toast.LENGTH_SHORT).show()
                recordingStatus.text = getString(R.string.home_recording_idle)
                if (result.code == ApiResult.CODE_OK) {
                    refreshSavedItems()
                }
            }
        }
    }

    /** 录像帧坐标序列 → 路线模拟轨道。 */
    private fun saveRecordingAsRoute(recordingId: Long, name: String) {
        try {
            val framesResult = ApiClient.getRecordingFrames(recordingId)
            val frames = framesResult.data?.optJSONArray("frames") ?: return
            val points = mutableListOf<com.amap.api.maps.model.LatLng>()
            for (i in 0 until frames.length()) {
                val frame = frames.optJSONObject(i) ?: continue
                val loc = frame.optJSONObject("location") ?: continue
                val lat = loc.optDouble("latitude", Double.NaN)
                val lon = loc.optDouble("longitude", Double.NaN)
                if (!lat.isNaN() && !lon.isNaN()) {
                    points.add(com.amap.api.maps.model.LatLng(lat, lon))
                }
            }
            if (points.size >= 2) {
                val tag = io.github.fairyxh.VirtualEnv.core.Backend.TRACK_SOURCE_ROUTE_TAG
                val routeName = if (name.isBlank()) "录像路线$tag" else "$name$tag"
                ApiClient.createRoute(routeName, points)
                ZLog.i(TAG_SCOPE, "recording->route saved ${points.size} points")
            } else {
                ZLog.w(TAG_SCOPE, "recording->route skipped, points<2: ${points.size}")
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "saveRecordingAsRoute failed", t)
        }
    }

    // ---------- 已保存采集（快照 + 录像统一列表） ----------

    private fun refreshSavedItems() {
        executor.execute {
            val snapshots = ApiClient.listEnvSnapshots().data?.optJSONArray("snapshots")
            val recordings = ApiClient.listRecordings().data?.optJSONArray("recordings")
            requireActivity().runOnUiThread {
                savedItems.clear()
                snapshots?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        if (item.optString("type", "") != "collect") continue
                        savedItems.add(
                            SavedItem(
                                kind = "snapshot",
                                id = item.optLong("id", -1L),
                                name = item.optString("name", ""),
                                remark = item.optString("remark", ""),
                                meta = getString(
                                    R.string.home_saved_meta_snapshot,
                                    formatTime(item.optLong("createTime", 0L))
                                )
                            )
                        )
                    }
                }
                recordings?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val id = item.optLong("id", -1L)
                        val durationSec = item.optLong("durationMs", 0L) / 1000L
                        savedItems.add(
                            SavedItem(
                                kind = "recording",
                                id = id,
                                name = item.optString("name", ""),
                                remark = "",
                                meta = getString(
                                    R.string.home_saved_meta_recording,
                                    formatDuration(durationSec),
                                    item.optInt("frameCount", 0)
                                )
                            )
                        )
                    }
                }
                renderSavedItems()
            }
        }
    }

    private fun renderSavedItems() {
        savedCollectList.removeAllViews()
        savedCollectEmpty.visibility = if (savedItems.isEmpty()) View.VISIBLE else View.GONE
        savedItems.forEach { item ->
            val row = layoutInflater.inflate(R.layout.item_saved_collect, savedCollectList, false)
            val kindLabel = if (item.kind == "snapshot") {
                getString(R.string.home_saved_kind_snapshot)
            } else {
                getString(R.string.home_saved_kind_recording)
            }
            row.findViewById<TextView>(R.id.collectName).text = "$kindLabel ${item.name}"
            val remarkView = row.findViewById<TextView>(R.id.collectRemark)
            if (item.remark.isBlank()) {
                remarkView.visibility = View.GONE
            } else {
                remarkView.text = getString(R.string.location_point_remark_format, item.remark)
            }
            row.findViewById<TextView>(R.id.collectMeta).text = item.meta
            val useBtn = row.findViewById<Button>(R.id.useButton)
            useBtn.text = getString(R.string.home_saved_select)
            useBtn.setOnClickListener {
                selectItem(item)
            }
            row.findViewById<Button>(R.id.deleteButton).setOnClickListener {
                deleteItem(item)
            }
            // 选中高亮
            row.setBackgroundResource(
                if (selectedItem?.kind == item.kind && selectedItem?.id == item.id) {
                    R.drawable.bg_pill
                } else {
                    R.drawable.bg_field
                }
            )
            savedCollectList.addView(row)
        }
    }

    private fun selectItem(item: SavedItem) {
        selectedItem = item
        playbackSelected.text = getString(R.string.home_playback_selected, kindLabel(item.kind), item.name)
        val isRecording = item.kind == "recording"
        playbackControls.visibility = if (isRecording) View.VISIBLE else View.GONE
        playbackSpeedRow.visibility = if (isRecording) View.VISIBLE else View.GONE
        playbackEnableButton.text = getString(
            if (isRecording) R.string.home_playback_start_video else R.string.home_playback_enable
        )
        renderSavedItems()
    }

    private fun kindLabel(kind: String): String {
        return if (kind == "snapshot") {
            getString(R.string.home_saved_kind_snapshot)
        } else {
            getString(R.string.home_saved_kind_recording)
        }
    }

    private fun deleteItem(item: SavedItem) {
        executor.execute {
            val result = if (item.kind == "snapshot") {
                ApiClient.deleteEnvSnapshot(item.id)
            } else {
                ApiClient.deleteRecording(item.id)
            }
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                if (result.code == ApiResult.CODE_OK) {
                    if (selectedItem?.kind == item.kind && selectedItem?.id == item.id) {
                        selectedItem = null
                        playbackSelected.text = getString(R.string.home_playback_none)
                        playbackControls.visibility = View.GONE
                        playbackSpeedRow.visibility = View.GONE
                    }
                    refreshSavedItems()
                }
            }
        }
    }

    // ---------- 采集回放 ----------

    /** 启用采集回放：快照一次性启用（后端自动同步位置/基站/WiFi/GNSS），录像开始回放。 */
    private fun enableSelectedPlayback() {
        val item = selectedItem ?: run {
            Toast.makeText(requireContext(), R.string.home_playback_none_selected, Toast.LENGTH_SHORT).show()
            return
        }
        executor.execute {
            val result = if (item.kind == "snapshot") {
                ApiClient.useEnvSnapshot(item.id)
            } else {
                ApiClient.playRecordings(listOf(item.id), false)
            }
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setPlaybackSpeed() {
        val speed = playbackSpeedInput.text.toString().toFloatOrNull() ?: return
        executor.execute {
            val result = ApiClient.setRecordingSpeed(speed)
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
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
        playbackStatus.text = if (paused) {
            getString(R.string.home_playback_paused, playIndex, playlistSize, frameProgress, frameCount)
        } else {
            getString(R.string.home_playback_playing, playIndex, playlistSize, frameProgress, frameCount, "")
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
}
