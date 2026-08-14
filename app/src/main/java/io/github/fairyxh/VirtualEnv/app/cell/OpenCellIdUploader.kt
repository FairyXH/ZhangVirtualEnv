package io.github.fairyxh.VirtualEnv.app.cell

import android.content.Context
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenCellID 本地上传队列。
 *
 * - 测量数据先落盘（App 私有 filesDir/opencellid_queue.json），网络失败不丢。
 * - 仅在用户开启贡献且配置了 API Key 时上传。
 * - 上传成功才从队列移除；失败保留，下次继续重试。
 * - 严格只接受真实测量数据（由 [CellMeasurementCollector] 保证）。
 */
class OpenCellIdUploader(private val context: Context) {

    companion object {
        private const val TAG_SCOPE = "OpenCellId"
        private const val FILE_NAME = "opencellid_queue.json"
        private const val MAX_QUEUE = 500
    }

    private val uploading = AtomicBoolean(false)

    /** 入队测量数据（幂等去重由调用方完成，这里只做文件追加）。 */
    fun enqueue(measurements: List<JSONObject>): Int {
        if (measurements.isEmpty()) return 0
        synchronized(this) {
            val queue = loadQueue()
            measurements.forEach { m ->
                val fingerprint = queueFingerprint(m)
                if (queue.none { queueFingerprint(it) == fingerprint }) {
                    queue.add(m)
                }
            }
            while (queue.size > MAX_QUEUE) {
                queue.removeAt(0)
            }
            saveQueue(queue)
            ZLog.i(TAG_SCOPE, "queue enqueue total=${queue.size} added=${measurements.size}")
            return queue.size
        }
    }

    fun pendingCount(): Int = synchronized(this) { loadQueue().size }

    /** 上传待发送队列；成功移除已传项。 */
    fun uploadPending(): UploadResult {
        if (!uploading.compareAndSet(false, true)) {
            return UploadResult(false, "上传进行中")
        }
        try {
            val apiKey = OpenCellIdSettings.getApiKey(context)
            if (apiKey.isNullOrBlank()) {
                return UploadResult(false, "未配置 OpenCellID API Key")
            }
            if (!OpenCellIdSettings.isContributeEnabled(context)) {
                return UploadResult(false, "未开启数据贡献")
            }
            val queue = synchronized(this) { loadQueue() }
            if (queue.isEmpty()) {
                return UploadResult(false, "队列为空")
            }
            val result = OpenCellIdApi.uploadMeasurements(apiKey, queue)
            return if (result.isSuccess) {
                synchronized(this) {
                    // 上传成功：整批移除（OpenCellID 批量接口一次性处理）
                    saveQueue(emptyList())
                }
                UploadResult(true, "已上传 ${queue.size} 条测量")
            } else {
                val err = result.exceptionOrNull()
                UploadResult(false, "上传失败：${err?.message ?: "未知错误"}")
            }
        } finally {
            uploading.set(false)
        }
    }

    /** 清空队列。 */
    fun clear() {
        synchronized(this) {
            saveQueue(emptyList())
        }
    }

    private fun queueFingerprint(m: JSONObject): String {
        return buildString {
            append(m.optInt("mcc", -1))
            append('|').append(m.optInt("mnc", -1))
            append('|').append(m.optLong("cellid", -1L))
            append('|').append(m.optLong("measured_at", 0L))
        }
    }

    private fun queueFile(): File = File(context.filesDir, FILE_NAME)

    private fun loadQueue(): MutableList<JSONObject> {
        return try {
            val file = queueFile()
            if (!file.exists()) return mutableListOf()
            val arr = JSONArray(file.readText())
            val list = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { list.add(it) }
            }
            list
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "queue load failed: ${t.message}")
            mutableListOf()
        }
    }

    private fun saveQueue(queue: List<JSONObject>) {
        try {
            queueFile().writeText(JSONArray(queue).toString())
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "queue save failed: ${t.message}")
        }
    }

    data class UploadResult(val success: Boolean, val message: String)
}
