package io.github.fairyxh.VirtualEnv.app.collect

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import io.github.fairyxh.VirtualEnv.app.ApiClient
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 传感器实时事件流录制器（控制端）。
 *
 * 与旧“按采样间隔帧”不同：本录制器记录每个传感器事件的**相对时间戳**，
 * 形成连续事件流（events[]）。录像帧的 sensor 键携带自录制开始以来的
 * 完整事件列表；回放端 [io.github.fairyxh.VirtualEnv.hook.StepSensorInjector]
 * 按事件时间轴完整重放，而非按固定周期跳变。
 *
 * 线程模型：传感器回调在主线程，仅追加内存列表（CopyOnWriteArrayList）；
 * flush 在专用单线程 executor，把完整事件流与最新状态 POST 到后端。
 */
@SuppressLint("MissingPermission")
class SensorStreamRecorder(private val context: Context) {

    companion object {
        private const val TAG_SCOPE = "Collect"
        /** 事件记录最小间隔（ms）：加速度/陀螺仪 SENSOR_DELAY_GAME 事件频率较高，节流到 50ms 已可完整还原波形。 */
        private const val MIN_EVENT_DELTA_MS = 50L
        /** 事件流帧 flush 周期（ms）。 */
        private const val FLUSH_INTERVAL_MS = 500L
    }

    private data class SensorEventRecord(
        val t: Long, // 相对录制开始时间（ms）
        val accel: FloatArray? = null,
        val gyro: FloatArray? = null,
        val step: Long = -1L,
    )

    private data class SensorValues(
        val accel: FloatArray? = null,
        val gyro: FloatArray? = null,
        val step: Long = -1L,
    )

    private val events = CopyOnWriteArrayList<SensorEventRecord>()
    private val latest = AtomicReference(SensorValues())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recordingId = -1L
    private var recordingStartWall = 0L
    private var lastEventT = Long.MIN_VALUE
    private var scheduler: ScheduledExecutorService? = null
    private var listener: SensorEventListener? = null
    private var sensors: List<Sensor> = emptyList()

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val nowT = if (recordingStartWall > 0) {
                (System.currentTimeMillis() - recordingStartWall).coerceAtLeast(0L)
            } else 0L
            val type = event.sensor?.type ?: return
            // 节流：同类型事件间隔小于 MIN_EVENT_DELTA_MS 时只更新 latest，不追加事件
            if (nowT - lastEventT >= MIN_EVENT_DELTA_MS) {
                val s = latest.get()
                val accel = if (type == Sensor.TYPE_ACCELEROMETER) event.values.copyOf() else s.accel
                val gyro = if (type == Sensor.TYPE_GYROSCOPE) event.values.copyOf() else s.gyro
                val step = if (type == Sensor.TYPE_STEP_COUNTER) event.values[0].toLong() else s.step
                val record = SensorEventRecord(nowT, accel, gyro, step)
                latest.set(SensorValues(accel, gyro, step))
                events.add(record)
                lastEventT = nowT
            } else {
                val s = latest.get()
                val updated = when (type) {
                    Sensor.TYPE_ACCELEROMETER -> s.copy(accel = event.values.copyOf())
                    Sensor.TYPE_GYROSCOPE -> s.copy(gyro = event.values.copyOf())
                    Sensor.TYPE_STEP_COUNTER -> s.copy(step = event.values[0].toLong())
                    else -> s
                }
                latest.set(updated)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** 开始实时事件流录制并周期 flush 到指定录像。 */
    fun start(recordingId: Long) {
        stop()
        if (recordingId <= 0) return
        this.recordingId = recordingId
        this.recordingStartWall = System.currentTimeMillis()
        this.lastEventT = Long.MIN_VALUE
        events.clear()
        latest.set(SensorValues())

        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val step = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        sensors = listOfNotNull(accel, gyro, step)
        if (sensors.isEmpty()) {
            ZLog.w(TAG_SCOPE, "no sensors available for event stream recording")
            return
        }
        listener = sensorListener
        sensors.forEach { sensor ->
            val delay = if (sensor.type == Sensor.TYPE_STEP_COUNTER) {
                SensorManager.SENSOR_DELAY_NORMAL
            } else {
                SensorManager.SENSOR_DELAY_GAME
            }
            try {
                sm.registerListener(sensorListener, sensor, delay, mainHandler)
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "register sensor ${sensor.type} failed", t)
            }
        }

        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ZVE-SensorRecorder").apply { isDaemon = true }
        }
        scheduler = exec
        exec.scheduleWithFixedDelay(
            {
                try {
                    val id = this.recordingId
                    if (id <= 0) return@scheduleWithFixedDelay
                    val frame = buildFrame()
                    if (frame == null) return@scheduleWithFixedDelay
                    ApiClient.appendRecordingFrame(id, frame)
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "sensor event frame append failed", t)
                }
            },
            FLUSH_INTERVAL_MS,
            FLUSH_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
        ZLog.i(TAG_SCOPE, "sensor event stream recorder started id=$recordingId")
    }

    /** 停止录制并注销传感器监听。 */
    fun stop() {
        val id = recordingId
        recordingId = -1L
        recordingStartWall = 0L
        scheduler?.shutdownNow()
        scheduler = null
        listener?.let {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            try {
                sm.unregisterListener(it)
            } catch (_: Throwable) {
            }
        }
        listener = null
        sensors = emptyList()
        if (id > 0) ZLog.i(TAG_SCOPE, "sensor event stream recorder stopped id=$id events=${events.size}")
    }

    /**
     * 构造事件流帧：
     * ```
     * { "sensor": { "events": [{t, accelerometer?, gyroscope?, stepCounter?}...],
     *               "accelerometer": 最新, "gyroscope": 最新, "stepCounter": 最新,
     *               "accuracy": 3, "sampleRateMs": 50 } }
     * ```
     * events 为自录制开始以来的完整事件列表（跨帧累积），回放端按 t 完整重放。
     */
    private fun buildFrame(): JSONObject? {
        if (events.isEmpty()) return null
        val sensor = JSONObject().apply {
            val arr = JSONArray()
            events.forEach { e ->
                val item = JSONObject().apply { put("t", e.t) }
                e.accel?.let { if (it.size >= 3) item.put("accelerometer", JSONArray().apply { put(it[0]); put(it[1]); put(it[2]) }) }
                e.gyro?.let { if (it.size >= 3) item.put("gyroscope", JSONArray().apply { put(it[0]); put(it[1]); put(it[2]) }) }
                if (e.step >= 0) item.put("stepCounter", e.step)
                arr.put(item)
            }
            put("events", arr)
            val v = latest.get()
            v.accel?.let {
                if (it.size >= 3) put("accelerometer", JSONArray().apply { put(it[0]); put(it[1]); put(it[2]) })
            }
            v.gyro?.let {
                if (it.size >= 3) put("gyroscope", JSONArray().apply { put(it[0]); put(it[1]); put(it[2]) })
            }
            if (v.step >= 0) put("stepCounter", v.step)
            put("accuracy", 3)
            put("sampleRateMs", MIN_EVENT_DELTA_MS)
        }
        return JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("sensor", sensor)
        }
    }
}
