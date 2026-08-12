package io.github.fairyxh.VirtualEnv.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.app.ApiClient
import io.github.fairyxh.VirtualEnv.app.collect.EnvironmentCollector
import io.github.fairyxh.VirtualEnv.app.collect.SensorStreamRecorder
import io.github.fairyxh.VirtualEnv.app.collect.StreamEnvironmentSampler
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassBackdropHost
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassButton
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassCard
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassCheckbox
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassField
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassPill
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassSegmented
import io.github.fairyxh.VirtualEnv.app.ui.glass.GlassTextDialog
import io.github.fairyxh.VirtualEnv.app.ui.glass.glassColors
import io.github.fairyxh.VirtualEnv.core.model.ApiResult
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 主页：模块状态 + 一键采集（快照/录像选项卡）+ 已保存采集（快照/录像统一）+ 采集回放。
 *
 * 视图层已迁移到 Compose Liquid Glass（GlassCard / GlassButton / GlassField / GlassToggle），
 * 全部业务逻辑（ApiClient / collector / executor / 权限 / 轮询）保持不变。
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

    // ---------- Compose 视图状态 ----------

    private var statusDotEnabled by mutableStateOf(false)
    private var statusText by mutableStateOf("")
    private var statusDetail by mutableStateOf("")
    private val featureStatusRows = mutableStateListOf<Pair<String, String>>()

    private var collectResult by mutableStateOf<String?>(null)
    private var collectName by mutableStateOf("")
    private var detailDialog by mutableStateOf<Pair<String, String>?>(null)
    private var collectRemark by mutableStateOf("")
    private var collectButtonEnabled by mutableStateOf(true)
    private var saveCollectEnabled by mutableStateOf(false)
    private var collectRecordingMode by mutableStateOf(false)

    private var recordingName by mutableStateOf("")
    private var recordingInterval by mutableStateOf("3")
    private var recordingStatus by mutableStateOf("")
    private var recordingRunning by mutableStateOf(false)

    private var playbackSelected by mutableStateOf("")
    private var playbackStatus by mutableStateOf("")
    private var playbackControlsVisible by mutableStateOf(false)
    private var playbackSpeedRowVisible by mutableStateOf(false)
    private var playbackSpeed by mutableStateOf("1.0")
    private var playbackLoop by mutableStateOf(true)

    private val savedItems = mutableStateListOf<SavedItem>()
    private var selectedItem: SavedItem? = null

    // ---------- 业务对象（逻辑不变） ----------

    private val executor = Executors.newSingleThreadExecutor()
    private var collector: EnvironmentCollector? = null
    private var lastCollectResult: JSONObject? = null

    /** 录像期间持续监听环境（位置/GNSS/BLE/传感器 + 基站/WiFi 轮询），采样线程按间隔截帧。 */
    private var streamSampler: StreamEnvironmentSampler? = null

    /** 录像期间连续采集真实传感器数据（加速度/陀螺仪/计步）并逐帧追加。 */
    private var sensorRecorder: SensorStreamRecorder? = null

    @Volatile
    private var recordingId = -1L
    private var recordingFrames = 0
    private var recordingNameBackend = ""
    private var recordingScheduler: ScheduledExecutorService? = null
    private var pendingRecordingStart = false

    /** 采样链保护：collectAll 为异步链，上一轮未完成时跳过本轮，避免并发扫描。 */
    private val samplingBusy = java.util.concurrent.atomic.AtomicBoolean(false)

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

    /** 模块各功能实时状态轮询（位置/路线/摇杆/基站/WiFi/BLE/GNSS/传感器）。 */
    private val featurePollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val featurePoll = object : Runnable {
        override fun run() {
            if (!isAdded) return
            executor.execute {
                val loc = ApiClient.getLocationStatus()
                val route = ApiClient.getRouteStatus()
                val env = ApiClient.getEnvStatus()
                val joystick = ApiClient.getJoystickStatus()
                requireActivity().runOnUiThread {
                    renderFeatureStatus(loc, route, env, joystick)
                    if (isAdded) featurePollHandler.postDelayed(this, 2000L)
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        collector = EnvironmentCollector(requireContext())
        sensorRecorder = SensorStreamRecorder(requireContext())
        streamSampler = StreamEnvironmentSampler(requireContext())

        // 输入框默认值：录像名称默认时间，采集间隔默认 3 秒，快照名称默认时间
        recordingName = io.github.fairyxh.VirtualEnv.util.DefaultNames.timeName(getString(R.string.home_recording_title))
        recordingInterval = "3"
        collectName = io.github.fairyxh.VirtualEnv.util.DefaultNames.timeName(getString(R.string.home_collect_title))
        recordingStatus = getString(R.string.home_recording_idle)
        playbackStatus = getString(R.string.home_playback_idle)
        collectRecordingMode = false

        refreshBackendStatus()
        refreshSavedItems()
        return androidx.compose.ui.platform.ComposeView(requireContext()).apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                HomeScreen(this@HomeFragment)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBackendStatus()
        refreshSavedItems()
        playbackPollHandler.removeCallbacks(playbackPoll)
        playbackPollHandler.post(playbackPoll)
        featurePollHandler.removeCallbacks(featurePoll)
        featurePollHandler.post(featurePoll)
    }

    override fun onDestroyView() {
        recordingScheduler?.shutdownNow()
        recordingScheduler = null
        sensorRecorder?.stop()
        streamSampler?.stop()
        if (recordingId > 0) {
            val id = recordingId
            recordingId = -1L
            executor.execute { ApiClient.stopRecording(id) }
        }
        playbackPollHandler.removeCallbacks(playbackPoll)
        featurePollHandler.removeCallbacks(featurePoll)
        executor.shutdown()
        super.onDestroyView()
    }

    // ---------- Compose UI ----------

    @Composable
    private fun HomeScreen(fragment: HomeFragment) {
        GlassBackdropHost(
            modifier = Modifier
                .fillMaxSize()
        ) { backdrop ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 130.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val colors = glassColors()
                BasicText(
                    getString(R.string.app_name),
                    style = TextStyle(
                        color = colors.textPrimary,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    )
                )
                BasicText(
                    getString(R.string.home_subtitle),
                    style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                )

                // 模块状态卡
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        BasicText(
                            getString(R.string.home_status_title),
                            style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        )
                        Row(
                            Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusDot(
                                enabled = statusDotEnabled,
                                modifier = Modifier.size(10.dp)
                            )
                            BasicText(
                                statusText,
                                Modifier.padding(start = 8.dp),
                                style = TextStyle(color = colors.textPrimary, fontSize = 15.sp)
                            )
                        }
                        if (statusDetail.isNotBlank()) {
                            BasicText(
                                statusDetail,
                                Modifier.padding(top = 8.dp),
                                style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                            )
                        }
                        BasicText(
                            getString(R.string.home_feature_status_title),
                            Modifier.padding(top = 16.dp),
                            style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        )
                        BasicText(
                            getString(R.string.home_feature_status_hint),
                            Modifier.padding(top = 4.dp),
                            style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                        )
                        featureStatusRows.forEach { (label, value) ->
                            Row(
                                Modifier
                                    .padding(top = 6.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BasicText(
                                    label,
                                    Modifier.weight(1f),
                                    style = TextStyle(color = colors.textSecondary, fontSize = 12.sp)
                                )
                                BasicText(
                                    value,
                                    style = TextStyle(color = colors.textPrimary, fontSize = 12.sp)
                                )
                            }
                        }
                    }
                }

                // 悬浮窗卡
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        BasicText(
                            getString(R.string.home_float_title),
                            style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        )
                        BasicText(
                            getString(R.string.home_float_desc),
                            Modifier.padding(top = 4.dp),
                            style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                        )
                        Row(
                            Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            GlassButton(
                                onClick = { fragment.openFloatWindow() },
                                backdrop = backdrop,
                                modifier = Modifier.weight(1f),
                                tint = colors.accent
                            ) {
                                BasicText(
                                    getString(R.string.float_open),
                                    style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                )
                            }
                            GlassButton(
                                onClick = {
                                    io.github.fairyxh.VirtualEnv.app.FloatControlService.stop(requireContext())
                                },
                                backdrop = backdrop,
                                modifier = Modifier.weight(1f),
                                surfaceColor = colors.bgSecondary.copy(alpha = 0.55f)
                            ) {
                                BasicText(
                                    getString(R.string.float_close_window),
                                    style = TextStyle(color = colors.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }

                // 一键采集卡
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        BasicText(
                            getString(R.string.home_collect_title),
                            style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        )
                        GlassSegmented(
                            backdrop = backdrop,
                            modifier = Modifier
                                .padding(top = 10.dp)
                                .fillMaxWidth(),
                            selectedIndex = { if (collectRecordingMode) 1 else 0 },
                            onSelect = { index ->
                                fragment.setCollectMode(index == 1)
                            },
                            count = 2
                        ) { index ->
                            BasicText(
                                if (index == 0) getString(R.string.home_collect_tab_snapshot)
                                else getString(R.string.home_collect_tab_recording),
                                style = TextStyle(
                                    color = if ((if (collectRecordingMode) 1 else 0) == index) Color.White else colors.textPrimary,
                                    fontSize = 13.sp
                                )
                            )
                        }

                        if (!collectRecordingMode) {
                            // 快照模式
                            BasicText(
                                getString(R.string.home_collect_desc),
                                Modifier.padding(top = 10.dp),
                                style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                            )
                            GlassButton(
                                onClick = { fragment.startCollect() },
                                backdrop = backdrop,
                                modifier = Modifier
                                    .padding(top = 12.dp)
                                    .fillMaxWidth(),
                                tint = colors.accent
                            ) {
                                BasicText(
                                    getString(R.string.home_collect_button),
                                    style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                )
                            }
                            collectResult?.let { result ->
                                BasicText(
                                    result,
                                    Modifier
                                        .padding(top = 12.dp)
                                        .fillMaxWidth(),
                                    style = TextStyle(
                                        color = colors.textSecondary,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                )
                            }
                            GlassField(
                                value = collectName,
                                onValueChange = { collectName = it },
                                backdrop = backdrop,
                                modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                                placeholder = getString(R.string.home_collect_name_hint)
                            )
                            GlassField(
                                value = collectRemark,
                                onValueChange = { collectRemark = it },
                                backdrop = backdrop,
                                modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                                placeholder = getString(R.string.home_collect_remark_hint)
                            )
                            GlassButton(
                                onClick = { fragment.saveCollect() },
                                backdrop = backdrop,
                                modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                                surfaceColor = colors.bgSecondary.copy(alpha = 0.55f)
                            ) {
                                BasicText(
                                    getString(R.string.home_collect_save),
                                    style = TextStyle(color = colors.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                )
                            }
                        } else {
                            // 录像模式
                            BasicText(
                                getString(R.string.home_recording_desc),
                                Modifier.padding(top = 10.dp),
                                style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                            )
                            GlassField(
                                value = recordingName,
                                onValueChange = { recordingName = it },
                                backdrop = backdrop,
                                modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                                placeholder = getString(R.string.home_recording_name_hint)
                            )
                            Row(
                                Modifier
                                    .padding(top = 8.dp)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                GlassField(
                                    value = recordingInterval,
                                    onValueChange = { recordingInterval = it },
                                    backdrop = backdrop,
                                    modifier = Modifier.weight(1f),
                                    placeholder = getString(R.string.home_recording_interval_hint)
                                )
                                GlassButton(
                                    onClick = { fragment.startRecording() },
                                    backdrop = backdrop,
                                    modifier = Modifier.weight(1f),
                                    tint = colors.accent
                                ) {
                                    BasicText(
                                        getString(R.string.home_recording_start),
                                        style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    )
                                }
                                GlassButton(
                                    onClick = { fragment.stopRecording() },
                                    backdrop = backdrop,
                                    modifier = Modifier.weight(1f),
                                    surfaceColor = colors.bgSecondary.copy(alpha = 0.55f)
                                ) {
                                    BasicText(
                                        getString(R.string.home_recording_stop),
                                        style = TextStyle(color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                            BasicText(
                                recordingStatus,
                                Modifier.padding(top = 8.dp),
                                style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                            )
                        }
                    }
                }

                // 采集回放卡
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        BasicText(
                            getString(R.string.home_playback_title),
                            style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        )
                        BasicText(
                            playbackSelected.ifBlank { getString(R.string.home_playback_none) },
                            Modifier.padding(top = 6.dp),
                            style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                        )
                        GlassButton(
                            onClick = { fragment.enableSelectedPlayback() },
                            backdrop = backdrop,
                            modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                            tint = colors.accent
                        ) {
                            BasicText(
                                if (selectedItem?.kind == "recording") getString(R.string.home_playback_start_video)
                                else getString(R.string.home_playback_enable),
                                style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            )
                        }
                        if (playbackControlsVisible) {
                            Row(
                                Modifier.padding(top = 10.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                GlassButton(
                                    onClick = {
                                        executor.execute {
                                            val result = ApiClient.pauseRecordingPlayback()
                                            requireActivity().runOnUiThread {
                                                Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    backdrop = backdrop,
                                    modifier = Modifier.weight(1f),
                                    surfaceColor = colors.bgSecondary.copy(alpha = 0.55f)
                                ) {
                                    BasicText(
                                        getString(R.string.home_playback_pause),
                                        style = TextStyle(color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    )
                                }
                                GlassButton(
                                    onClick = {
                                        executor.execute {
                                            val result = ApiClient.resumeRecordingPlayback()
                                            requireActivity().runOnUiThread {
                                                Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    backdrop = backdrop,
                                    modifier = Modifier.weight(1f),
                                    surfaceColor = colors.bgSecondary.copy(alpha = 0.55f)
                                ) {
                                    BasicText(
                                        getString(R.string.home_playback_resume),
                                        style = TextStyle(color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                            Row(
                                Modifier.padding(top = 4.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                GlassButton(
                                    onClick = {
                                        val sel = selectedItem
                                        if (sel?.kind == "recording") {
                                            executor.execute {
                                                ApiClient.stopRecordingPlayback()
                                                val result = ApiClient.playRecordings(listOf(sel.id), playbackLoop)
                                                requireActivity().runOnUiThread {
                                                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    },
                                    backdrop = backdrop,
                                    modifier = Modifier.weight(1f),
                                    surfaceColor = colors.bgSecondary.copy(alpha = 0.55f)
                                ) {
                                    BasicText(
                                        getString(R.string.home_playback_restart),
                                        style = TextStyle(color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    )
                                }
                                GlassButton(
                                    onClick = {
                                        executor.execute {
                                            val result = ApiClient.stopRecordingPlayback()
                                            requireActivity().runOnUiThread {
                                                Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    backdrop = backdrop,
                                    modifier = Modifier.weight(1f),
                                    surfaceColor = colors.bgSecondary.copy(alpha = 0.55f)
                                ) {
                                    BasicText(
                                        getString(R.string.home_playback_stop),
                                        style = TextStyle(color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                        }
                        if (playbackSpeedRowVisible) {
                            Row(
                                Modifier.padding(top = 8.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BasicText(
                                    getString(R.string.home_playback_speed),
                                    style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                                )
                                GlassField(
                                    value = playbackSpeed,
                                    onValueChange = { playbackSpeed = it },
                                    backdrop = backdrop,
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .weight(1f),
                                    placeholder = getString(R.string.home_playback_speed_hint)
                                )
                                GlassButton(
                                    onClick = { fragment.setPlaybackSpeed() },
                                    backdrop = backdrop,
                                    modifier = Modifier.padding(start = 8.dp),
                                    surfaceColor = colors.bgSecondary.copy(alpha = 0.55f)
                                ) {
                                    BasicText(
                                        getString(R.string.home_playback_speed_set),
                                        style = TextStyle(color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                            Row(
                                Modifier.padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                GlassCheckbox(
                                    checked = playbackLoop,
                                    onCheckedChange = { playbackLoop = it }
                                )
                                BasicText(
                                    getString(R.string.home_playback_loop),
                                    Modifier.padding(start = 8.dp),
                                    style = TextStyle(color = colors.textPrimary, fontSize = 13.sp)
                                )
                            }
                        }
                        BasicText(
                            playbackStatus,
                            Modifier.padding(top = 8.dp),
                            style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                        )
                    }
                }

                // 已保存采集卡
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = colors.bgSecondary.copy(alpha = 0.45f)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        BasicText(
                            getString(R.string.home_saved_title),
                            style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        )
                        if (savedItems.isEmpty()) {
                            BasicText(
                                getString(R.string.home_saved_empty),
                                Modifier.padding(top = 8.dp),
                                style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                            )
                        } else {
                            savedItems.forEach { item ->
                                SavedItemRow(
                                    item = item,
                                    selected = selectedItem?.kind == item.kind && selectedItem?.id == item.id,
                                    backdrop = backdrop,
                                    onSelect = { fragment.selectItem(item) },
                                    onDetail = { fragment.showSavedDetail(item) },
                                    onDelete = { fragment.deleteItem(item) }
                                )
                            }
                        }
                    }
                }
            }
            detailDialog?.let { (title, text) ->
                GlassTextDialog(
                    title = title,
                    text = text,
                    onDismiss = { detailDialog = null }
                )
            }
        }
    }

    @Composable
    private fun SavedItemRow(
        item: SavedItem,
        selected: Boolean,
        backdrop: com.kyant.backdrop.Backdrop,
        onSelect: () -> Unit,
        onDetail: () -> Unit,
        onDelete: () -> Unit
    ) {
        val colors = glassColors()
        // 选中态不显示蓝色：仅提高中性色亮度区分，保留玻璃质感且不干扰视觉
        GlassPill(
            onClick = onSelect,
            backdrop = backdrop,
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(),
            selected = selected,
            containerColor = if (selected) colors.bgTertiary.copy(alpha = 0.75f)
            else colors.bgTertiary.copy(alpha = 0.4f),
            height = 56.dp
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    val kindLabel = if (item.kind == "snapshot") {
                        getString(R.string.home_saved_kind_snapshot)
                    } else {
                        getString(R.string.home_saved_kind_recording)
                    }
                    BasicText(
                        "$kindLabel ${item.name}",
                        style = TextStyle(color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    )
                    if (item.remark.isNotBlank()) {
                        BasicText(
                            getString(R.string.location_point_remark_format, item.remark),
                            style = TextStyle(color = colors.textSecondary, fontSize = 11.sp)
                        )
                    }
                    BasicText(
                        item.meta,
                        style = TextStyle(color = colors.textTertiary, fontSize = 11.sp)
                    )
                }
                GlassButton(
                    onClick = onDetail,
                    backdrop = backdrop,
                    modifier = Modifier.width(64.dp),
                    isInteractive = false,
                    surfaceColor = colors.bgSecondary.copy(alpha = 0.5f)
                ) {
                    BasicText(
                        getString(R.string.home_saved_detail),
                        style = TextStyle(color = colors.textPrimary, fontSize = 12.sp)
                    )
                }
                GlassButton(
                    onClick = onDelete,
                    backdrop = backdrop,
                    modifier = Modifier.width(64.dp),
                    isInteractive = false,
                    surfaceColor = colors.danger.copy(alpha = 0.25f)
                ) {
                    BasicText(
                        getString(R.string.home_recording_delete),
                        style = TextStyle(color = colors.danger, fontSize = 12.sp)
                    )
                }
            }
        }
    }

    @Composable
    private fun StatusDot(enabled: Boolean, modifier: Modifier = Modifier) {
        val colors = glassColors()
        Box(
            modifier
                .clip(CircleShape)
                .drawBehind {
                    drawCircle(
                        color = if (enabled) colors.success else colors.textTertiary,
                        radius = size.minDimension / 2f
                    )
                    if (enabled) {
                        drawCircle(
                            color = colors.success.copy(alpha = 0.35f),
                            radius = size.minDimension * 0.85f
                        )
                    }
                }
        )
    }

    // ---------- 选项卡 ----------

    private fun setCollectMode(recording: Boolean) {
        collectRecordingMode = recording
    }

    // ---------- 状态 ----------

    private fun refreshBackendStatus() {
        executor.execute {
            val reachable = ApiClient.ping()
            val info = if (reachable) ApiClient.getSystemInfo() else null
            requireActivity().runOnUiThread {
                statusDotEnabled = reachable
                if (reachable) {
                    statusText = getString(R.string.home_status_ok)
                    val enabledText = if (ApiClient.getLocationStatus().data?.optBoolean("enabled", false) == true) {
                        getString(R.string.location_enabled)
                    } else {
                        getString(R.string.location_disabled)
                    }
//                    statusDetail = getString(
//                        R.string.home_status_detail,
//                        info?.data?.optString("phase", "1") ?: "-",
//                        enabledText
//                    )
                } else {
                    statusText = getString(R.string.home_status_offline)
                    statusDetail = getString(R.string.home_status_offline_detail)
                }
            }
        }
    }

    // ---------- 悬浮窗统一入口（主页） ----------

    private fun openFloatWindow() {
        val context = requireContext()
        if (!android.provider.Settings.canDrawOverlays(context)) {
            Toast.makeText(context, R.string.float_permission_required, Toast.LENGTH_LONG).show()
            try {
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}")
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t: Throwable) {
                startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        io.github.fairyxh.VirtualEnv.app.FloatControlService.start(context)
        Toast.makeText(context, R.string.home_float_opened, Toast.LENGTH_SHORT).show()
    }

    // ---------- 功能实时状态 ----------

    /** 主页模块状态卡下的功能实时状态：位置 / 路线 / 摇杆 / 基站 / WiFi / BLE / GNSS / 传感器。 */
    private fun renderFeatureStatus(
        loc: ApiResult,
        route: ApiResult,
        env: ApiResult,
        joystick: ApiResult
    ) {
        featureStatusRows.clear()
        if (loc.code != ApiResult.CODE_OK || env.code != ApiResult.CODE_OK) {
            featureStatusRows.add(getString(R.string.home_status_offline) to "—")
            return
        }
        val locData = loc.data
        val mode = locData?.optString("mode", "none") ?: "none"
        val singleEnabled = locData?.optBoolean("singleEnabled", false) == true
        val locText = when {
            mode == "route" -> getString(R.string.route_status_running, route.data?.optInt("points", 0) ?: 0)
            singleEnabled -> getString(R.string.location_enabled)
            else -> getString(R.string.location_disabled)
        }
        featureStatusRows.add("位置" to locText)

        val routeData = route.data
        val routeRunning = routeData?.optBoolean("running", false) == true
        val routeText = if (routeRunning) {
            if (routeData?.optBoolean("paused", false) == true) getString(R.string.float_route_paused)
            else getString(R.string.route_status_running, routeData.optInt("points", 0))
        } else {
            getString(R.string.route_status_idle)
        }
        featureStatusRows.add(getString(R.string.route_title) to routeText)

        val joyData = joystick.data
        val joyText = if (joyData?.optBoolean("enabled", false) == true) {
            getString(R.string.location_enabled)
        } else {
            getString(R.string.location_disabled)
        }
        featureStatusRows.add(getString(R.string.float_mode_joystick) to joyText)

        val envData = env.data
        listOf(
            "cell" to getString(R.string.env_cell_title),
            "wifi" to getString(R.string.env_wifi_title),
            "ble" to getString(R.string.env_ble_title),
            "gnss" to getString(R.string.env_gnss_title),
            "sensor" to getString(R.string.env_sensor_title)
        ).forEach { (key, label) ->
            val enabled = envData?.optJSONObject(key)?.optBoolean("enabled", false) == true
            featureStatusRows.add(
                label to getString(if (enabled) R.string.location_enabled else R.string.location_disabled)
            )
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
        collectButtonEnabled = false
        collectResult = getString(R.string.home_collect_running)
        Toast.makeText(requireContext(), R.string.home_collect_suspend_notice, Toast.LENGTH_LONG).show()
        executor.execute {
            ApiClient.suspendEnv()
            collector?.collectAll { result ->
                ApiClient.resumeEnv()
                lastCollectResult = result
                requireActivity().runOnUiThread {
                    collectButtonEnabled = true
                    saveCollectEnabled = true
                    collectResult = summarize(result)
                    ZLog.i(TAG_SCOPE, "collect done: ${result.length()}")
                }
            }
        }
    }

    // ---------- 保存采集（collect 包 + 轨道拆分） ----------

    private fun saveCollect() {
        val name = collectName.trim()
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), R.string.home_collect_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        val result = lastCollectResult
        if (result == null) {
            Toast.makeText(requireContext(), R.string.home_collect_none, Toast.LENGTH_SHORT).show()
            return
        }
        val remark = collectRemark.trim()
        executor.execute {
            val apiResult = ApiClient.createEnvSnapshot(name, remark, "collect", result)
            if (apiResult.code == ApiResult.CODE_OK) {
                saveCollectTracks(name, remark, result)
            }
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), apiResult.message, Toast.LENGTH_SHORT).show()
                if (apiResult.code == ApiResult.CODE_OK) {
                    resetDefaultNames()
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
        val name = recordingName.trim()
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), R.string.home_recording_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        // 流式采集：间隔支持小数秒，最低 0.1s（0.1~300）
        val interval = (recordingInterval.toDoubleOrNull() ?: 1.0).coerceIn(0.1, 300.0)
        recordingInterval = if (interval % 1.0 == 0.0) interval.toInt().toString() else interval.toString()
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
                    recordingNameBackend = name
                    recordingName = io.github.fairyxh.VirtualEnv.util.DefaultNames.timeName(
                        getString(R.string.home_recording_title)
                    )
                    // 启动流式监听（位置/GNSS/BLE/传感器 + 基站/WiFi 轮询）
                    streamSampler?.start()
                    // 连续传感器事件流（加速度/陀螺仪/计步）随录像启动
                    if (recordingId > 0) sensorRecorder?.start(recordingId)
                    Toast.makeText(
                        requireContext(),
                        R.string.home_recording_suspend_notice,
                        Toast.LENGTH_LONG
                    ).show()
                    startSamplingLoop(interval)
                    recordingRunning = true
                    recordingStatus = getString(R.string.home_recording_running, name, recordingFrames)
                } else {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startSamplingLoop(intervalSec: Double) {
        recordingScheduler?.shutdownNow()
        val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ZVE-Recorder").apply { isDaemon = true }
        }
        recordingScheduler = scheduler
        val intervalMs = (intervalSec * 1000.0).toLong().coerceAtLeast(100L)
        scheduler.scheduleWithFixedDelay(
            {
                try {
                    if (recordingId <= 0) return@scheduleWithFixedDelay
                    // 流式截帧：直接从最新快照取当前状态（不再每次重新注册监听）
                    if (!samplingBusy.compareAndSet(false, true)) return@scheduleWithFixedDelay
                    executor.execute {
                        try {
                            val id = recordingId
                            if (id > 0) {
                                val frame = streamSampler?.snapshot() ?: JSONObject()
                                val result = ApiClient.appendRecordingFrame(id, frame)
                                if (result.code == ApiResult.CODE_OK) {
                                    recordingFrames++
                                    requireActivity().runOnUiThread {
                                        if (isAdded) {
                                            recordingStatus = getString(
                                                R.string.home_recording_running,
                                                recordingNameBackend,
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
                    ZLog.w(TAG_SCOPE, "recording sample failed", t)
                }
            },
            0,
            intervalMs,
            TimeUnit.MILLISECONDS
        )
    }

    private fun stopRecording() {
        recordingScheduler?.shutdownNow()
        recordingScheduler = null
        sensorRecorder?.stop()
        streamSampler?.stop()
        val id = recordingId
        if (id <= 0) return
        recordingId = -1L
        executor.execute {
            ApiClient.resumeEnv()
            val result = ApiClient.stopRecording(id)
            if (result.code == ApiResult.CODE_OK) {
                saveRecordingAsRoute(id, recordingNameBackend)
            }
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), R.string.home_recording_stopped, Toast.LENGTH_SHORT).show()
                recordingRunning = false
                recordingStatus = getString(R.string.home_recording_idle)
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
                        val interrupted = item.optBoolean("interrupted", false)
                        savedItems.add(
                            SavedItem(
                                kind = "recording",
                                id = id,
                                name = item.optString("name", ""),
                                remark = "",
                                meta = (if (interrupted) {
                                    getString(R.string.recording_detail_interrupted_badge) + " "
                                } else {
                                    ""
                                }) + getString(
                                    R.string.home_saved_meta_recording,
                                    formatDuration(durationSec),
                                    item.optInt("frameCount", 0)
                                )
                            )
                        )
                    }
                }
            }
        }
    }

    private fun selectItem(item: SavedItem) {
        selectedItem = item
        playbackSelected = getString(R.string.home_playback_selected, kindLabel(item.kind), item.name)
        val isRecording = item.kind == "recording"
        playbackControlsVisible = isRecording
        playbackSpeedRowVisible = isRecording
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
                        playbackSelected = ""
                        playbackControlsVisible = false
                        playbackSpeedRowVisible = false
                    }
                    refreshSavedItems()
                }
            }
        }
    }

    // ---------- 采集详情 ----------

    /** 已保存采集详情：快照弹窗显示采集内容摘要；录像跳转帧详情页（帧列表+原始数据+返回）。 */
    private fun showSavedDetail(item: SavedItem) {
        if (item.kind == "recording") {
            RecordingDetailActivity.start(requireContext(), item.id, item.name)
            return
        }
        executor.execute {
            val text = buildSnapshotDetail(item.id)
            requireActivity().runOnUiThread {
                showScrollableDialog(
                    getString(R.string.home_saved_detail_title) + " · " + item.name,
                    text
                )
            }
        }
    }

    /** 可滚动详情弹窗（液态玻璃样式，录像帧数多时避免内容溢出）。 */
    private fun showScrollableDialog(title: String, text: String) {
        detailDialog = title to text
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
                "collect" -> formatCollectDetail(data) + rawDataSuffix(data)
                "cell" -> formatEnvDetail(data, "cell") + rawDataSuffix(data)
                "wifi" -> formatEnvDetail(data, "wifi") + rawDataSuffix(data)
                "gnss" -> formatEnvDetail(data, "gnss") + rawDataSuffix(data)
                else -> getString(R.string.home_saved_detail_empty)
            }
        }
        return getString(R.string.home_saved_detail_empty)
    }

    /** 快照详情末尾追加原始 JSON（需求：已保存采集详情可看到原始数据）。 */
    private fun rawDataSuffix(data: JSONObject?): String {
        if (data == null) return ""
        return "\n\n—— 原始数据 ——\n" + data.toString(2)
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
                ApiClient.playRecordings(listOf(item.id), playbackLoop)
            }
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setPlaybackSpeed() {
        val speed = playbackSpeed.toFloatOrNull() ?: return
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
            playbackStatus = getString(R.string.home_playback_idle)
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
        val loopText = if (data.optBoolean("loop", false)) " · 循环" else ""
        playbackStatus = if (paused) {
            getString(R.string.home_playback_paused, playIndex, playlistSize, frameProgress, frameCount) + syncText + loopText
        } else {
            getString(R.string.home_playback_playing, playIndex, playlistSize, frameProgress, frameCount, syncText) + loopText
        }
    }

    // ---------- 工具 ----------

    /** 保存/录制成功后重置默认名称（时间命名）。 */
    private fun resetDefaultNames() {
        collectName = io.github.fairyxh.VirtualEnv.util.DefaultNames.timeName(getString(R.string.home_collect_title))
        collectRemark = ""
        recordingName = io.github.fairyxh.VirtualEnv.util.DefaultNames.timeName(getString(R.string.home_recording_title))
    }

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
