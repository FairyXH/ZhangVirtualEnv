package io.github.fairyxh.VirtualEnv.app

import android.content.Context
import io.github.fairyxh.VirtualEnv.app.collect.SensorStreamRecorder
import io.github.fairyxh.VirtualEnv.app.collect.StreamEnvironmentSampler
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 进程级录制采集协调器；UI 和前台服务共享，页面销毁不影响采样。 */
object RecordingCaptureManager {
    private const val TAG = "Collect"
    private val lock = Any()
    private var sampler: StreamEnvironmentSampler? = null
    private var sensorRecorder: SensorStreamRecorder? = null
    private var scheduler: ScheduledExecutorService? = null
    private var future: ScheduledFuture<*>? = null
    private var recordingId = -1L
    private val busy = AtomicBoolean(false)

    fun start(context: Context, id: Long, intervalSec: Double) {
        if (id <= 0) return
        synchronized(lock) {
            if (recordingId == id && future != null) return
            stopLocked()
            recordingId = id
            sampler = StreamEnvironmentSampler(context.applicationContext).also { it.start() }
            sensorRecorder = SensorStreamRecorder(context.applicationContext).also { it.start(id) }
            val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ZVE-AppRecorder").apply { isDaemon = true } }
            scheduler = exec
            val delay = (intervalSec * 1000.0).toLong().coerceIn(100L, 300_000L)
            future = exec.scheduleWithFixedDelay({ appendFrame() }, 0L, delay, TimeUnit.MILLISECONDS)
            ZLog.i(TAG, "app capture started id=$id intervalMs=$delay")
        }
    }

    fun stop(id: Long = -1L) {
        synchronized(lock) {
            if (id > 0 && recordingId != id) return
            stopLocked()
        }
    }

    private fun stopLocked() {
        future?.cancel(false); future = null
        scheduler?.shutdownNow(); scheduler = null
        sensorRecorder?.stop(); sensorRecorder = null
        sampler?.stop(); sampler = null
        recordingId = -1L
    }

    private fun appendFrame() {
        if (!busy.compareAndSet(false, true)) return
        try {
            val id = synchronized(lock) { recordingId }
            if (id <= 0) return
            val frame = sampler?.snapshot() ?: JSONObject()
            ApiClient.appendRecordingFrame(id, frame)
        } catch (t: Throwable) {
            ZLog.w(TAG, "app capture frame failed", t)
        } finally {
            busy.set(false)
        }
    }
}