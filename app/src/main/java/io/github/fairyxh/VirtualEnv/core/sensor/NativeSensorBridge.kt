package io.github.fairyxh.VirtualEnv.core.sensor

import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Native 层传感器全局模拟桥（system_server 内使用）。
 *
 * 加载 `libzvesensor.so`（arm64）并调用 inline hook：
 * `SensorEventQueue::write`（libsensor.so）→ 重写桩 → 全局虚拟传感器事件。
 *
 * 只允许在 system_server 进程调用（[VirtualEnvEntry] 门禁）；hook 失败
 * 一律 fail-open（返回非 0 错误码，由 [SystemSensorBackend] 回退 Java 通道）。
 */
object NativeSensorBridge {

    private const val TAG_SCOPE = "NativeSensor"

    /** SystemSensorBackend 使用的 Native 通道注入模式值。 */
    const val MODE_NATIVE_GLOBAL = 100

    @Volatile
    private var loaded = false

    // ---------- 库加载 ----------

    /**
     * 加载 native 库。优先使用模块 nativeLibraryDir 解包目录，
     * 失败时从模块 APK 内解出 `lib/arm64-v8a/libzvesensor.so` 到
     * system_server 可写的 /data/system/zve/ 再加载。
     */
    fun loadLibrary(moduleApkPath: String?, nativeLibDir: String?): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true
            val direct = nativeLibDir?.let { File(it, "libzvesensor.so") }
            if (direct != null && direct.exists() && direct.length() > 0) {
                if (tryLoad(direct.absolutePath)) return true
            }
            if (!moduleApkPath.isNullOrBlank()) {
                if (tryExtractFromApk(moduleApkPath)) return true
            }
            ZLog.w(TAG_SCOPE, "loadLibrary failed: nativeLibraryDir=$nativeLibDir apk=$moduleApkPath")
            return false
        }
    }

    private fun tryLoad(path: String): Boolean {
        return try {
            System.load(path)
            loaded = true
            ZLog.i(TAG_SCOPE, "native lib loaded: $path")
            true
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "System.load failed: $path", t)
            false
        }
    }

    private fun tryExtractFromApk(apkPath: String): Boolean {
        return try {
            val destDir = File("/data/system/zve")
            if (!destDir.exists() && !destDir.mkdirs()) {
                ZLog.w(TAG_SCOPE, "mkdir /data/system/zve failed")
                return false
            }
            val dest = File(destDir, "libzvesensor.so")
            if (dest.exists()) dest.delete()
            val zip = ZipFile(apkPath)
            try {
                val entry = zip.getEntry("lib/arm64-v8a/libzvesensor.so") ?: run {
                    ZLog.w(TAG_SCOPE, "apk entry lib/arm64-v8a/libzvesensor.so not found")
                    return false
                }
                zip.getInputStream(entry).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            } finally {
                zip.close()
            }
            dest.setExecutable(true, false)
            tryLoad(dest.absolutePath)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "extract lib from apk failed", t)
            false
        }
    }

    // ---------- JNI ----------

    private external fun nativeInit(): Int
    private external fun nativeHookInstall(): Int
    private external fun nativeHookUninstall(): Int
    private external fun nativeSetConfig(
        enabled: Boolean,
        mode: Int,
        stepFrequency: Float,
        speedKmh: Float,
        amplitude: Float,
        randomNoise: Boolean,
        headingDeg: Float,
        initialStepCount: Long
    ): Int
    private external fun nativeGetStatus(): String
    private external fun nativeGetStepCount(): Long

    // ---------- 对外封装（fail-open） ----------

    /** 安装 inline hook；返回 0=成功 / 1=已安装 / 负值=失败原因。 */
    fun install(): Int {
        if (!loaded) return -100
        return try {
            nativeInit()
            nativeHookInstall()
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "nativeHookInstall failed", t)
            -101
        }
    }

    /** 卸载 inline hook（恢复原指令）；返回 0=成功 / 负值=失败。 */
    fun uninstall(): Int {
        if (!loaded) return 0
        return try {
            nativeHookUninstall()
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "nativeHookUninstall failed", t)
            -102
        }
    }

    /** 同步运动配置到 native 引擎。 */
    fun setConfig(
        enabled: Boolean,
        mode: Int,
        stepFrequency: Float,
        speedKmh: Float,
        amplitude: Float,
        randomNoise: Boolean,
        headingDeg: Float,
        initialStepCount: Long
    ) {
        if (!loaded) return
        try {
            nativeSetConfig(enabled, mode, stepFrequency, speedKmh, amplitude, randomNoise, headingDeg, initialStepCount)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "nativeSetConfig failed", t)
        }
    }

    /** native 状态 JSON（hooked/addr/eventsRewritten/stepCount/deliveryVerified）。 */
    fun status(): JSONObject {
        return try {
            if (!loaded) JSONObject().put("hooked", 0).put("lastError", -100)
            else JSONObject(nativeGetStatus())
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "nativeGetStatus failed", t)
            JSONObject().put("hooked", 0).put("lastError", -103)
        }
    }

    /** native 引擎当前步数。 */
    fun stepCount(): Long {
        return try {
            if (loaded) nativeGetStepCount() else 0L
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "nativeGetStepCount failed", t)
            0L
        }
    }

    /** 已安装且启用期间确实改写过事件（App 侧抑制 LEGACY 的依据）。 */
    fun deliveryVerified(): Boolean = status().optBoolean("deliveryVerified", false)

    /** 最近一次事件改写计数。 */
    fun eventsRewritten(): Long = status().optLong("eventsRewritten", 0L)
}
