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
import io.github.fairyxh.VirtualEnv.app.collect.SensorStreamRecorder
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

    /** 录像期间连续采集真实传感器数据（加速度/陀螺仪/计步）并逐帧追加。 */
    private var sensorRecorder: SensorStreamRecorder? = null

    private var collectRecordingMode = false

    @Volatile
    private var recordingId = -1L
    private var recordingFrames = 0
    private var recordingName = ""
    private var recordingScheduler: ScheduledExecutorService? = null
    private var pendingRecordingStart = false

    /** 采样链保护：collectAll 为异步链，上一轮未完成时跳过本轮，避免并发扫描。 */
    private val samplingBusy = java.util.concurrent.atomic.AtomicBoolean(false)

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
        sensorRecorder = SensorStreamRecorder(requireContext())

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
        sensorRecorder?.stop()
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

        // 蓝牙轨道：合并附近设备 + 已配对设备，保存为 type=ble 配置，
        // 使“蓝牙模拟”子页面能看到并同步本次采集结果
        val btData = collect.optJSONObject("bluetooth")
        val btDevices = btData?.optJSONArray("devices") ?: org.json.JSONArray()
        val btBonded = btData?.optJSONArray("bonded") ?: org.json.JSONArray()
        if (btDevices.length() > 0 || btBonded.length() > 0) {
            val merged = org.json.JSONArray()
            val seen = HashSet<String>()
            fun appendEntry(src: org.json.JSONObject) {
                val address = src.optString("address", "").uppercase()
                if (address.isBlank() || !seen.add(address)) return
                merged.put(JSONObject().apply {
                    put("name", src.optString("name", ""))
                    put("address", address)
                    put("rssi", src.optInt("rssi", -70))
                    if (src.has("txPower")) put("txPower", src.optInt("txPower", 0))
                    if (src.has("manufacturerData")) put("manufacturerData", src.optString("manufacturerData", ""))
                    if (src.has("serviceUuids")) put("serviceUuids", src.optJSONArray("serviceUuids"))
                })
            }
            for (i in 0 until btBonded.length()) appendEntry(btBonded.optJSONObject(i) ?: continue)
            for (i in 0 until btDevices.length()) appendEntry(btDevices.optJSONObject(i) ?: continue)
            if (merged.length() > 0) {
                ApiClient.createEnvSnapshot(
                    name, sourceRemark, "ble",
                    JSONObject().apply { put("devices", merged) }
                )
                ZLog.i(TAG_SCOPE, "ble track saved ${merged.length()} devices")
            }
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
            // 采集真实环境前先临时停用虚拟环境；必须在后台线程（主线程禁止网络）
            if (result.code == ApiResult.CODE_OK) {
                ApiClient.suspendEnv()
            }
            requireActivity().runOnUiThread {
                if (result.code == ApiResult.CODE_OK) {
                    recordingId = result.data?.optLong("id", -1L) ?: -1L
                    recordingFrames = 0
                    recordingName = name
                    recordingNameInput.text.clear()
                    // 连续传感器采集（加速度/陀螺仪/计步）随录像启动
                    if (recordingId > 0) sensorRecorder?.start(recordingId)
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
                    // collectAll 是异步链（WiFi/GNSS/BLE 回调），上一轮未完成时跳过
                    if (!samplingBusy.compareAndSet(false, true)) return@scheduleWithFixedDelay
                    collector?.collectAll { frame ->
                        try {
                            // collectAll 回调运行在主线程，HTTP 追加必须切到后台线程
                            executor.execute {
                                try {
                                    val id = recordingId
                                    if (id > 0) {
                                        val result = ApiClient.appendRecordingFrame(id, frame)
                                        if (result.code == ApiResult.CODE_OK) {
                                            recordingFrames++
                                            requireActivity().runOnUiThread {
                                                if (isAdded) {
                                                    recordingStatus.text = getString(
                                                        R.string.home_recording_running,
                                                        recordingName,
                                                        recordingFrames
                                                    )
                                                }
                                            }
                                        } else {
                                            ZLog.w(TAG_SCOPE, "append frame rejected: ${result.message}")
                                        }
                                    }
                                } catch (t: Throwable) {
                                    ZLog.w(TAG_SCOPE, "append frame failed", t)
                                } finally {
                                    samplingBusy.set(false)
                                }
                            }
                        } catch (t: Throwable) {
                            samplingBusy.set(false)
                            ZLog.w(TAG_SCOPE, "append frame dispatch failed", t)
                        }
                    }
                } catch (t: Throwable) {
                    samplingBusy.set(false)
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
        sensorRecorder?.stop()
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
                // 帧数据 location 为 provider 键结构：{gps: {latitude, longitude, ...}}
                val loc = frame.optJSONObject("data")?.optJSONObject("location") ?: continue
                val keys = loc.keys()
                while (keys.hasNext()) {
                    val item = loc.optJSONObject(keys.next()) ?: continue
                    val lat = item.optDouble("latitude", Double.NaN)
                    val lon = item.optDouble("longitude", Double.NaN)
                    if (!lat.isNaN() && !lon.isNaN()) {
                        points.add(com.amap.api.maps.model.LatLng(lat, lon))
                        break
                    }
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
            row.findViewById<Button>(R.id.detailButton).setOnClickListener {
                showSavedDetail(item)
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

    // ---------- 采集详情 ----------

    /** 已保存采集详情弹窗：快照显示采集内容摘要，录像按时间轴显示所有帧。 */
    private fun showSavedDetail(item: SavedItem) {
        executor.execute {
            val text = if (item.kind == "snapshot") {
                buildSnapshotDetail(item.id)
            } else {
                buildRecordingDetail(item.id)
            }
            requireActivity().runOnUiThread {
                showScrollableDialog(
                    getString(R.string.home_saved_detail_title) + " · " + item.name,
                    text
                )
            }
        }
    }

    /** 可滚动详情弹窗（录像帧数多时避免内容溢出）。 */
    private fun showScrollableDialog(title: String, text: String) {
        val scroll = android.widget.ScrollView(requireContext()).apply {
            isFillViewport = true
        }
        val tv = TextView(requireContext()).apply {
            setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_primary))
            textSize = 12f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setText(text)
        }
        scroll.addView(tv)
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildSnapshotDetail(id: Long): String {
        val arr = ApiClient.listEnvSnapshots().data?.optJSONArray("snapshots")
            ?: return getString(R.string.home_saved_detail_empty)
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optLong("id", -1L) != id) continue
            val data = item.optJSONObject("data")
            return when (item.optString("type", "")) {
                "collect" -> formatCollectDetail(data)
                "cell" -> formatEnvDetail(data, "cell")
                "wifi" -> formatEnvDetail(data, "wifi")
                "gnss" -> formatEnvDetail(data, "gnss")
                else -> getString(R.string.home_saved_detail_empty)
            }
        }
        return getString(R.string.home_saved_detail_empty)
    }

    private fun formatCollectDetail(data: JSONObject?): String {
        if (data == null) return getString(R.string.home_saved_detail_empty)
        val sb = StringBuilder()
        val loc = data.optJSONObject("location")
        if (loc != null && loc.length() > 0) {
            val first = loc.keys().next()
            val item = loc.optJSONObject(first)
            sb.append("位置：").append(item?.optString("latitude")).append(", ")
                .append(item?.optString("longitude")).append("\n")
        }
        sb.append("基站：").append(data.optJSONObject("cell")?.optJSONArray("cells")?.length() ?: 0)
            .append(" 个\n")
        sb.append("WiFi：").append(data.optJSONObject("wifi")?.optJSONArray("networks")?.length() ?: 0)
            .append(" 个\n")
        val bt = data.optJSONObject("bluetooth")
        sb.append("蓝牙：已配对 ").append(bt?.optJSONArray("bonded")?.length() ?: 0)
            .append(" · 附近 ").append(bt?.optJSONArray("devices")?.length() ?: 0).append("\n")
        val gnss = data.optJSONObject("gnss")
        sb.append("GNSS：").append(gnss?.optInt("satelliteCount", 0) ?: 0).append(" 颗卫星")
        return sb.toString()
    }

    private fun formatEnvDetail(data: JSONObject?, type: String): String {
        if (data == null) return getString(R.string.home_saved_detail_empty)
        val sb = StringBuilder()
        when (type) {
            "cell" -> {
                val arr = data.optJSONArray("entries") ?: org.json.JSONArray()
                sb.append("基站 ").append(arr.length()).append(" 个")
                for (i in 0 until arr.length().coerceAtMost(8)) {
                    val e = arr.optJSONObject(i) ?: continue
                    sb.append("\n#").append(i + 1).append("  ")
                        .append(e.optString("type", "LTE"))
                        .append(" MCC=").append(e.optInt("mcc", -1))
                        .append(" MNC=").append(e.optInt("mnc", -1))
                        .append(" TAC=").append(e.optInt("tac", -1))
                        .append(" CI=").append(e.optLong("ci", -1L))
                }
            }
            "wifi" -> {
                val arr = data.optJSONArray("networks") ?: org.json.JSONArray()
                sb.append("WiFi ").append(arr.length()).append(" 个")
                for (i in 0 until arr.length().coerceAtMost(8)) {
                    val e = arr.optJSONObject(i) ?: continue
                    sb.append("\n#").append(i + 1).append("  ")
                        .append(e.optString("ssid", ""))
                        .append(" (").append(e.optString("bssid", ""))
                        .append(") RSSI=").append(e.optInt("rssi", -70))
                }
            }
            "gnss" -> {
                sb.append("卫星总数：").append(data.optInt("satelliteCount", -1)).append("\n")
                sb.append("参与定位：").append(data.optInt("usedInFix", -1)).append("\n")
                sb.append("平均信噪比：").append(data.optDouble("cn0", -1.0)).append(" dBHz")
            }
            else -> sb.append(data.toString(2))
        }
        return sb.toString()
    }

    /** 录像详情：按时间轴逐帧展示（seq、时间偏移、位置/基站/WiFi/蓝牙/GNSS/传感器摘要）。 */
    private fun buildRecordingDetail(id: Long): String {
        val result = ApiClient.getRecordingFrames(id)
        val frames = result.data?.optJSONArray("frames")
            ?: return getString(R.string.home_saved_detail_empty)
        val sb = StringBuilder()
        sb.append("帧数：").append(frames.length()).append("\n")
        val firstTs = frames.optJSONObject(0)?.optLong("timestampMs", 0L) ?: 0L
        var firstLoc = ""
        var lastLoc = ""
        for (i in 0 until frames.length()) {
            val frame = frames.optJSONObject(i) ?: continue
            val ts = frame.optLong("timestampMs", 0L)
            val offsetSec = ((ts - firstTs).coerceAtLeast(0L)) / 1000.0
            val data = frame.optJSONObject("data") ?: continue

            var locText = ""
            data.optJSONObject("location")?.let { loc ->
                val keys = loc.keys()
                while (keys.hasNext()) {
                    val item = loc.optJSONObject(keys.next()) ?: continue
                    val lat = item.optDouble("latitude", Double.NaN)
                    val lon = item.optDouble("longitude", Double.NaN)
                    if (!lat.isNaN() && !lon.isNaN()) {
                        val text = String.format("%.6f, %.6f", lat, lon)
                        if (firstLoc.isEmpty()) firstLoc = text
                        lastLoc = text
                        locText = text
                        break
                    }
                }
            }
            val cellN = data.optJSONObject("cell")?.optJSONArray("cells")?.length() ?: 0
            val wifiN = data.optJSONObject("wifi")?.optJSONArray("networks")?.length() ?: 0
            val btN = (data.optJSONObject("bluetooth")?.optJSONArray("devices")?.length() ?: 0) +
                (data.optJSONObject("bluetooth")?.optJSONArray("bonded")?.length() ?: 0)
            val gnssN = data.optJSONObject("gnss")?.optInt("satelliteCount", 0) ?: 0
            val sensor = data.optJSONObject("sensor")
            var sensorText = ""
            if (sensor != null) {
                val parts = mutableListOf<String>()
                sensor.optJSONArray("accelerometer")?.let { arr ->
                    if (arr.length() >= 3) {
                        parts.add(String.format("acc=%.2f,%.2f,%.2f", arr.optDouble(0), arr.optDouble(1), arr.optDouble(2)))
                    }
                }
                sensor.optJSONArray("gyroscope")?.let { arr ->
                    if (arr.length() >= 3) {
                        parts.add(String.format("gyr=%.3f,%.3f,%.3f", arr.optDouble(0), arr.optDouble(1), arr.optDouble(2)))
                    }
                }
                if (sensor.has("stepCounter")) parts.add("步=" + sensor.optLong("stepCounter", 0L))
                sensorText = if (parts.isEmpty()) "" else parts.joinToString(" ")
            }

            sb.append(String.format("#%d [+%06.1fs] ", frame.optInt("seq", i + 1), offsetSec))
            if (locText.isNotEmpty()) sb.append("位置:").append(locText).append(" ")
            if (cellN > 0) sb.append("基站:").append(cellN).append(" ")
            if (wifiN > 0) sb.append("WiFi:").append(wifiN).append(" ")
            if (btN > 0) sb.append("蓝牙:").append(btN).append(" ")
            if (gnssN > 0) sb.append("GNSS:").append(gnssN).append(" ")
            if (sensorText.isNotEmpty()) sb.append("传感器[").append(sensorText).append("]")
            val content = sb.toString()
            if (content.endsWith("] ") || content.endsWith(" ")) {
                sb.setLength(sb.length - 1)
            }
            sb.append("\n")
        }
        if (firstLoc.isNotEmpty()) sb.append("起点：").append(firstLoc).append("\n")
        if (lastLoc.isNotEmpty()) sb.append("终点：").append(lastLoc).append("\n")
        return sb.toString()
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
        val routeRunning = data.optBoolean("routeRunning", false)
        val locationEnabled = data.optBoolean("locationEnabled", false)
        val env = data.optJSONObject("envEnabled")
        val envParts = mutableListOf<String>()
        env?.let {
            if (it.optBoolean("wifi", false)) envParts.add("WiFi")
            if (it.optBoolean("cell", false)) envParts.add("基站")
            if (it.optBoolean("ble", false)) envParts.add("BLE")
            if (it.optBoolean("gnss", false)) envParts.add("GNSS")
            if (it.optBoolean("sensor", false)) envParts.add("传感器")
        }
        val syncParts = mutableListOf<String>()
        if (routeRunning) syncParts.add("路线运行中")
        if (locationEnabled) syncParts.add("虚拟定位")
        if (envParts.isNotEmpty()) syncParts.add("环境:" + envParts.joinToString("/"))
        val syncText = if (syncParts.isEmpty()) "" else " · " + syncParts.joinToString(" ")
        playbackStatus.text = if (paused) {
            getString(R.string.home_playback_paused, playIndex, playlistSize, frameProgress, frameCount) + syncText
        } else {
            getString(R.string.home_playback_playing, playIndex, playlistSize, frameProgress, frameCount, syncText)
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
