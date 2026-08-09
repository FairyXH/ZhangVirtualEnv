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
    private var playlist: List<Long> = emptyList()
    private var playIndex = 0
    private var frames: List<JSONObject> = emptyList()
    private var firstFrameTs = 0L
    private var durationMs = 0L
    private var playStartWall = 0L
    private var pausedElapsed = 0L
    private var lastAppliedIdx = -1
    private val tickerRunning = AtomicBoolean(false)

    private val tickerThread = HandlerThread("ZVE-Replay").apply { start() }
    private val handler = Handler(tickerThread.looper)

    // ---------- 录制 ----------

    /** 开始一段新录像；若有未结束录像先自动结束。返回录像 id。 */
    fun startRecording(name: String, remark: String): Long {
        if (activeRecordingId > 0) stopRecording()
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

    // ---------- 录像查询 ----------

    fun listRecordings(): List<JSONObject> = databaseManager.queryRecordings()

    fun getFrames(id: Long): List<JSONObject> = databaseManager.queryRecordingFrames(id)

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
        // 回放是独立模式：先停路线，避免路线位置抢占回放输出
        backend.stopRoute()
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
        ZLog.i(TAG_SCOPE, "playback stopped")
    }

    private fun stopPlaybackLocked() {
        playbackEnabled = false
        playbackPaused = false
        playlist = emptyList()
        frames = emptyList()
        lastAppliedIdx = -1
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
        }
        // 立即应用第一帧，使回放开始即有环境
        applyFrame(loaded.first().optJSONObject("data") ?: JSONObject())
        lastAppliedIdx = 0
        ZLog.i(TAG_SCOPE, "playback load recording id=$id frames=${loaded.size} durationMs=$durationMs")
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
            elapsed = if (paused) pausedElapsed else SystemClock.elapsedRealtime() - playStartWall
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

    /** 把一帧数据写入对应模拟引擎（Hook 层随即输出）。 */
    private fun applyFrame(data: JSONObject) {
        try {
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
            data.optJSONObject("cell")?.let { backend.cellEngine.update(it) }
            data.optJSONObject("wifi")?.let { backend.wifiEngine.update(it) }
            data.optJSONObject("bluetooth")?.let { backend.bleEngine.update(it) }
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
            }
        }
    }

    fun shutdown() {
        stopTicker()
        tickerThread.quitSafely()
    }
}
