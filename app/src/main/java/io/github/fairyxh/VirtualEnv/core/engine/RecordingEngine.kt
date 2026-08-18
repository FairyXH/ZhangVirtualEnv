package io.github.fairyxh.VirtualEnv.core.engine

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.github.fairyxh.VirtualEnv.core.Backend
import io.github.fairyxh.VirtualEnv.core.DatabaseManager
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 环境录制与回放引擎（Phase 4）。
 *
 * 录制：App 控制端按采样间隔 POST 采集帧（位置/基站/WiFi/蓝牙），
 * 本引擎写入 recording_frame 表；停止时写入时长与帧数。
 *
 * 回放：按帧时间轴推进，把当前帧的位置/基站/WiFi/蓝牙写入对应模拟引擎，
 * Hook 层随即输出虚拟环境。支持：
 * - 循环播放（loop）
 * - 多个录像顺序播放（playlist）
 * - 暂停 / 继续 / 停止
 *
 * 线程模型：HandlerThread 定时器（200ms tick），惰性推进；无 Hook 业务状态。
 */
class RecordingEngine(
    private val databaseManager: DatabaseManager,
    private val backend: Backend,
) {

    companion object {
        private const val TAG_SCOPE = "Replay"
        private const val TICK_MS = 200L

        /** 循环回放时末帧停留余量（ms），避免最后一帧与时长边界重合导致不显示。 */
        private const val FRAME_GRACE_MS = 500L
    }

    // ---------- 录制状态 ----------

    @Volatile
    private var activeRecordingId: Long = -1L
    @Volatile
    private var recordingName: String = ""
    @Volatile
    private var recordingStartWall: Long = 0L
    @Volatile
    private var recordingFirstTs: Long = 0L
    @Volatile
    private var recordingLastTs: Long = 0L
    @Volatile
    private var recordingFrameSeq: Int = 0
    @Volatile
    private var recordingFrameCount: Int = 0

    // ---------- 回放状态 ----------

    private val playbackLock = Any()
    private var playbackEnabled = false
    private var playbackPaused = false
    private var playbackLoop = false
    private var playbackSpeed = 1.0f
    private var playlist: List<Long> = emptyList()
    private var playIndex = 0
    private var frames: List<JSONObject> = emptyList()
    private var firstFrameTs = 0L
    private var durationMs = 0L
    private var playStartWall = 0L
    private var pausedElapsed = 0L
    private var lastAppliedIdx = -1
    private val tickerRunning = AtomicBoolean(false)

    /** 回放帧间平滑过渡：位置按时间插值生成中间段并叠加小随机抖动。 */
    @Volatile
    private var smoothLocation = true

    /** 每帧的时间偏移（相对首帧）与位置项（provider 结构第一个有效坐标）；loadRecording 时重建。 */
    private var frameOffsets: List<Long> = emptyList()
    private var frameLocations: List<JSONObject?> = emptyList()

    private val tickerThread = HandlerThread("ZVE-Replay").apply { start() }
    private val handler = Handler(tickerThread.looper)

    // ---------- 录制 ----------

    /** 开始一段新录像；若有未结束录像先自动结束。返回录像 id。 */
    fun startRecording(name: String, remark: String): Long {
        if (activeRecordingId > 0) stopRecording()
        // 录制基线：保存用户录制开始时的位置/路线/摇杆/环境开关与配置，
        // 回放时恢复同一环境起点（采集模式随后由帧数据逐帧覆盖）。
        backend.saveRecordingBaseState()
        val id = databaseManager.insertRecording(name, remark)
        synchronized(this) {
            activeRecordingId = id
            recordingName = name
            recordingStartWall = System.currentTimeMillis()
            recordingFirstTs = 0L
            recordingLastTs = 0L
            recordingFrameSeq = 0
            recordingFrameCount = 0
        }
        ZLog.i(TAG_SCOPE, "recording started id=$id name=$name")
        return id
    }

    /** 追加一帧；无活动录像时返回 false。 */
    fun appendFrame(data: JSONObject): Boolean {
        val id = activeRecordingId
        if (id <= 0) return false
        // 录像编程器：每帧附加完整环境状态（位置/路线/摇杆/所有环境开关与配置），
        // 回放时按时间轴自动重放这些“操作”。
        // 采集模式（suspend 中）各引擎被临时清空，帧内 envState 取录制基线，
        // 保证回放从用户录制开始时的环境起点恢复，而不是从空状态开始。
        try {
            val snapshot = if (backend.isSuspended()) {
                backend.recordingBaseSnapshotJson()
            } else {
                backend.envStateSnapshotJson()
            }
            if (snapshot != null) data.put("envState", snapshot)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "append envState snapshot failed", t)
        }
        val ts = data.optLong("timestamp", System.currentTimeMillis())
        val seq = synchronized(this) {
            if (recordingFirstTs == 0L) recordingFirstTs = ts
            recordingLastTs = ts
            recordingFrameSeq++
            recordingFrameCount++
            recordingFrameSeq
        }
        databaseManager.insertRecordingFrame(id, seq, ts, data.toString())
        return true
    }

    /** 停止录制并写入元信息。 */
    fun stopRecording(): Boolean {
        val id = activeRecordingId
        if (id <= 0) return false
        backend.clearRecordingBaseState()
        var count = 0
        var duration = 0L
        synchronized(this) {
            count = recordingFrameCount
            duration = if (recordingFirstTs > 0 && recordingLastTs >= recordingFirstTs) {
                recordingLastTs - recordingFirstTs
            } else if (recordingFirstTs > 0) {
                (System.currentTimeMillis() - recordingFirstTs).coerceAtLeast(0L)
            } else 0L
            databaseManager.updateRecordingMeta(id, duration, count)
            activeRecordingId = -1L
            recordingName = ""
            recordingFrameSeq = 0
            recordingFrameCount = 0
            recordingFirstTs = 0L
            recordingLastTs = 0L
        }
        ZLog.i(TAG_SCOPE, "recording stopped id=$id frames=$count duration=$duration")
        return true
    }

    fun isRecording(): Boolean = activeRecordingId > 0

    /**
     * 启动兜底：system_server 重启/崩溃后，把未正常 finalize 的录像按实际帧数据收尾
     * （计算时长与帧数并标记录像为中断），保证已录制内容可被查看/回放/删除。
     * 由 Backend.start() 在模块加载时调用一次。
     */
    fun recoverInterruptedRecordings(): Int {
        val ids = databaseManager.queryUnfinalizedRecordingIds()
        if (ids.isEmpty()) return 0
        var recovered = 0
        for (id in ids) {
            try {
                val range = databaseManager.recordingFrameRange(id)
                val count = range?.optInt("count", 0) ?: 0
                val duration = if (range != null) {
                    (range.optLong("lastTs") - range.optLong("firstTs")).coerceAtLeast(0L)
                } else 0L
                databaseManager.updateRecordingMeta(id, duration, count)
                databaseManager.markRecordingInterrupted(id)
                recovered++
                ZLog.w(
                    TAG_SCOPE,
                    "recording recovered (interrupted) id=$id frames=$count duration=$duration"
                )
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "recover recording id=$id failed", t)
            }
        }
        ZLog.i(TAG_SCOPE, "recovered $recovered interrupted recording(s)")
        return recovered
    }

    /** 回放帧间平滑过渡开关（默认开）。 */
    fun setSmoothLocation(enabled: Boolean) {
        smoothLocation = enabled
        ZLog.i(TAG_SCOPE, "playback smoothLocation=$enabled")
    }

    fun isSmoothLocation(): Boolean = smoothLocation

    // ---------- 录像查询 ----------

    fun listRecordings(): List<JSONObject> = databaseManager.queryRecordings()

    fun getFrames(id: Long): List<JSONObject> = databaseManager.queryRecordingFrames(id)

    /** 分页查询录像帧（按 seq 升序）。 */
    fun getFramesPaged(id: Long, offset: Int, limit: Int): List<JSONObject> =
        databaseManager.queryRecordingFrames(id, offset, limit)

    /** 录像帧范围统计（首帧/末帧时间戳与总帧数），无帧返回 null。 */
    fun getFrameRange(id: Long): JSONObject? = databaseManager.recordingFrameRange(id)

    fun deleteRecording(id: Long): Boolean = databaseManager.deleteRecording(id)

    // ---------- 回放 ----------

    /** 开始回放：ids 为录像 id 列表（顺序播放），loop 表示末尾循环。 */
    fun play(ids: List<Long>, loop: Boolean): Boolean {
        if (ids.isEmpty()) return false
        val valid = ids.distinct().filter { it > 0 }
        if (valid.isEmpty()) return false
        synchronized(playbackLock) {
            stopPlaybackLocked()
            playbackEnabled = true
            playbackPaused = false
            playbackLoop = loop
            playlist = valid
            playIndex = 0
        }
        // 回放是独立模式：保存回放前的用户环境，结束时恢复；
        // 同时应用录制基线（录制开始时的位置/路线/环境开关与配置）作为环境起点。
        backend.savePrePlaybackState()
        backend.applyRecordingBaseState()
        if (!loadRecording(valid[0])) {
            synchronized(playbackLock) { playbackEnabled = false }
            return false
        }
        startTicker()
        ZLog.i(TAG_SCOPE, "playback started ids=$valid loop=$loop")
        return true
    }

    fun pausePlayback() {
        synchronized(playbackLock) {
            if (!playbackEnabled || playbackPaused) return
            playbackPaused = true
            pausedElapsed = SystemClock.elapsedRealtime() - playStartWall
        }
        ZLog.i(TAG_SCOPE, "playback paused")
    }

    fun resumePlayback() {
        synchronized(playbackLock) {
            if (!playbackEnabled || !playbackPaused) return
            playStartWall = SystemClock.elapsedRealtime() - pausedElapsed
            playbackPaused = false
        }
        ZLog.i(TAG_SCOPE, "playback resumed")
    }

    fun stopPlayback() {
        synchronized(playbackLock) {
            stopPlaybackLocked()
        }
        stopTicker()
        // 同步启停：回放停止时恢复回放前的用户环境（位置/路线/环境开关与配置）
        backend.restoreAfterPlayback()
        ZLog.i(TAG_SCOPE, "playback stopped (env restored)")
    }

    /** 设置回放倍速（0.5x~8x）。 */
    fun setPlaybackSpeed(speed: Float) {
        synchronized(playbackLock) {
            playbackSpeed = speed.coerceIn(0.5f, 8f)
        }
        ZLog.i(TAG_SCOPE, "playback speed=${playbackSpeed}")
    }

    private fun stopPlaybackLocked() {
        playbackEnabled = false
        playbackPaused = false
        playlist = emptyList()
        frames = emptyList()
        lastAppliedIdx = -1
        frameOffsets = emptyList()
        frameLocations = emptyList()
    }

    private fun loadRecording(id: Long): Boolean {
        val loaded = databaseManager.queryRecordingFrames(id)
        if (loaded.isEmpty()) return false
        synchronized(playbackLock) {
            frames = loaded
            firstFrameTs = loaded.first().optLong("timestampMs", 0L)
            durationMs = (loaded.last().optLong("timestampMs", 0L) - firstFrameTs).coerceAtLeast(0L)
            playStartWall = SystemClock.elapsedRealtime()
            pausedElapsed = 0L
            lastAppliedIdx = -1
            // 预解析每帧位置项（平滑插值用，避免每 tick 重复解析 JSON）
            frameOffsets = loaded.map { (it.optLong("timestampMs", 0L) - firstFrameTs).coerceAtLeast(0L) }
            frameLocations = loaded.map { frame ->
                frame.optJSONObject("data")?.optJSONObject("location")?.let { loc ->
                    val keys = loc.keys()
                    while (keys.hasNext()) {
                        val item = loc.optJSONObject(keys.next()) ?: continue
                        if (!item.optDouble("latitude", Double.NaN).isNaN() &&
                            !item.optDouble("longitude", Double.NaN).isNaN()
                        ) {
                            return@map item
                        }
                    }
                    null
                }
            }
        }
        // 立即应用第一帧，使回放开始即有环境
        applyFrame(loaded.first().optJSONObject("data") ?: JSONObject())
        lastAppliedIdx = 0
        ZLog.i(TAG_SCOPE, "playback load recording id=$id frames=${loaded.size} durationMs=$durationMs smooth=$smoothLocation")
        return true
    }

    private fun startTicker() {
        if (tickerRunning.getAndSet(true)) return
        handler.post(tickRunnable)
    }

    private fun stopTicker() {
        tickerRunning.set(false)
        handler.removeCallbacks(tickRunnable)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!tickerRunning.get()) return
            try {
                tick()
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "playback tick failed", t)
            }
            if (tickerRunning.get()) handler.postDelayed(this, TICK_MS)
        }
    }

    private fun tick() {
        val enabled: Boolean
        val paused: Boolean
        var elapsed: Long
        val dur: Long
        val loop: Boolean
        synchronized(playbackLock) {
            enabled = playbackEnabled
            paused = playbackPaused
            elapsed = if (paused) {
                pausedElapsed
            } else {
                // 倍速：仅作用于推进速度（0.5x~8x）
                ((SystemClock.elapsedRealtime() - playStartWall).toDouble() * playbackSpeed).toLong()
            }
            dur = durationMs
            loop = playbackLoop
        }
        if (!enabled) return

        var idx: Int
        if (dur > 0 && elapsed >= dur) {
            if (loop) {
                // 平滑循环：末帧停留 FRAME_GRACE_MS 后再从头
                val cycleLen = dur + FRAME_GRACE_MS
                val cycleElapsed = elapsed % cycleLen
                idx = if (cycleElapsed >= dur) frames.size - 1 else findFrameIndex(cycleElapsed)
            } else {
                // 非循环：先确保停在最后一帧，再推进到下一段或结束
                idx = frames.size - 1
                if (idx != lastAppliedIdx) {
                    applyFrame(frames.getOrNull(idx)?.optJSONObject("data") ?: JSONObject())
                    lastAppliedIdx = idx
                }
                handler.post { advanceOrStop() }
                return
            }
        } else {
            idx = findFrameIndex(elapsed)
        }

        val frame = frames.getOrNull(idx) ?: return
        if (idx != lastAppliedIdx) {
            applyFrame(frame.optJSONObject("data") ?: JSONObject())
            lastAppliedIdx = idx
        }
        // 帧间平滑过渡：当前帧已应用且未到最后一帧时，按时间比例插值位置并叠加随机抖动，
        // 使定位不跳变（生成中间段）。仅当当前帧与下一帧都有有效坐标时生效。
        if (smoothLocation && !paused && idx == lastAppliedIdx && idx < frames.size - 1) {
            interpolateLocation(elapsed, idx)
        }
    }

    /** 在当前帧与下一帧之间按时间比例插值位置，附加确定性小随机抖动（约 1~2 米）。 */
    private fun interpolateLocation(elapsed: Long, idx: Int) {
        val cur = frameLocations.getOrNull(idx) ?: return
        val nxt = frameLocations.getOrNull(idx + 1) ?: return
        val t0 = frameOffsets.getOrNull(idx)?.toDouble() ?: return
        val t1 = frameOffsets.getOrNull(idx + 1)?.toDouble() ?: return
        if (t1 <= t0) return
        val t = ((elapsed - t0) / (t1 - t0)).coerceIn(0.0, 1.0)
        // 缓动：先快后慢，接近帧点时收敛到帧值，避免帧切换瞬间跳变
        val eased = t * t * (3.0 - 2.0 * t)
        val lat0 = cur.optDouble("latitude", Double.NaN)
        val lon0 = cur.optDouble("longitude", Double.NaN)
        val lat1 = nxt.optDouble("latitude", Double.NaN)
        val lon1 = nxt.optDouble("longitude", Double.NaN)
        if (lat0.isNaN() || lon0.isNaN() || lat1.isNaN() || lon1.isNaN()) return
        // 确定性伪随机抖动：基于 elapsed 相位，连续 tick 间平滑（幅度约 ±1.3e-5 度 ≈ ±1.5m）
        val jitter = jitterAt(elapsed)
        val lat = lat0 + (lat1 - lat0) * eased + jitter
        val lon = lon0 + (lon1 - lon0) * eased + jitter * 0.9
        val speed = cur.optDouble("speed", 0.0) + (nxt.optDouble("speed", 0.0) - cur.optDouble("speed", 0.0)) * eased
        try {
            backend.setLocationPoint(lat, lon, speed.toFloat(), 0f)
            backend.setLocationEnabled(true)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "interpolate location failed", t)
        }
    }

    /** 确定性平滑抖动：同一 elapsed 相位返回同一值，随相位缓变，避免每 tick 突变。 */
    private fun jitterAt(elapsed: Long): Double {
        val phase = elapsed / 200.0
        // 两个不同频率正弦叠加，幅度约 ±1.3e-5 度
        val a = Math.sin(phase * 0.73) * 0.5 + Math.sin(phase * 1.31) * 0.5
        return a * 1.3e-5
    }

    private fun findFrameIndex(elapsed: Long): Int {
        // frames 按 timestampMs 升序；二分查找最后一个 offset <= elapsed 的帧
        var lo = 0
        var hi = frames.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val offset = frames[mid].optLong("timestampMs", 0L) - firstFrameTs
            if (offset <= elapsed) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    private fun advanceOrStop() {
        val next: Long?
        synchronized(playbackLock) {
            if (playIndex < playlist.size - 1) {
                playIndex++
                next = playlist[playIndex]
            } else if (playbackLoop) {
                playIndex = 0
                next = playlist[0]
            } else {
                next = null
            }
        }
        if (next == null) {
            stopPlayback()
            return
        }
        if (!loadRecording(next)) {
            // 空录像：跳到下一个
            handler.post { advanceOrStop() }
        }
    }

    /**
     * 把一帧数据写入对应模拟引擎（Hook 层随即输出）。
     *
     * 录像编程器语义：帧内 `envState` 记录录制时刻的完整环境状态
     * （位置/路线/摇杆/所有环境开关与配置），回放时先整体应用；
     * 随后帧内采集数据（location/cell/wifi/bluetooth/gnss/sensor）
     * 覆盖为具体输出数据。两者叠加 = 在合适时间点自动操作软件功能。
     */
    private fun applyFrame(data: JSONObject) {
        try {
            // 1) 应用录制时刻的完整环境状态快照（开关、路线、位置、摇杆、配置）
            data.optJSONObject("envState")?.let { backend.applyEnvStateSnapshot(it) }

            // 2) 帧内采集数据覆盖：位置
            data.optJSONObject("location")?.let { loc ->
                val keys = loc.keys()
                while (keys.hasNext()) {
                    val provider = keys.next()
                    val item = loc.optJSONObject(provider) ?: continue
                    val lat = item.optDouble("latitude", Double.NaN)
                    val lon = item.optDouble("longitude", Double.NaN)
                    if (!lat.isNaN() && !lon.isNaN()) {
                        backend.setLocationPoint(lat, lon, item.optDouble("speed", 0.0).toFloat(), 0f)
                        backend.setLocationEnabled(true)
                        break
                    }
                }
            }
            // 3) 帧内采集数据覆盖：基站 / WiFi / 蓝牙 / GNSS / 传感器
            data.optJSONObject("cell")?.let { backend.cellEngine.update(it) }
            data.optJSONObject("wifi")?.let { backend.wifiEngine.update(it) }
            data.optJSONObject("bluetooth")?.let { backend.bleEngine.update(it) }
            data.optJSONObject("gnss")?.let { backend.gnssEngine.update(it) }
            // 传感器连续模拟/回放：帧内 sensor 数据写入引擎，进程内注入器按事件流/采样率连续输出
            data.optJSONObject("sensor")?.let { backend.sensorEngine.update(it) }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "apply frame failed", t)
        }
    }

    // ---------- 状态 ----------

    fun statusJson(): JSONObject {
        synchronized(playbackLock) {
            return JSONObject().apply {
                put("playing", playbackEnabled)
                put("paused", playbackPaused)
                put("loop", playbackLoop)
                put("playlistSize", playlist.size)
                put("playIndex", playIndex)
                put("currentRecordingId", playlist.getOrNull(playIndex) ?: -1L)
                put("frameCount", frames.size)
                put("frameProgress", lastAppliedIdx.coerceAtLeast(0))
                put("durationMs", durationMs)
                put("recording", isRecording())
                put("recordingId", activeRecordingId)
                put("smoothLocation", smoothLocation)
                // 回放同步状态：路线/位置/环境开关与配置（App 展示用）
                put("routeRunning", backend.routeEngine.isRunning())
                put("routeEnabled", backend.routeEngine.isEnabled())
                put("locationEnabled", backend.locationEngine.isEnabled())
                put("envEnabled", JSONObject().apply {
                    put("wifi", backend.wifiEngine.isEnabled())
                    put("cell", backend.cellEngine.isEnabled())
                    put("ble", backend.bleEngine.isEnabled())
                    put("gnss", backend.gnssEngine.isEnabled())
                    put("sensor", backend.sensorEngine.isEnabled())
                })
                put("currentUsing", backend.currentUsingName())
            }
        }
    }

    fun shutdown() {
        stopTicker()
        tickerThread.quitSafely()
    }
}
