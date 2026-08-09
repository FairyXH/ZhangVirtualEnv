package io.github.fairyxh.VirtualEnv.profile

import android.os.Build
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Profile 管理器。
 *
 * 根据 Build.VERSION.SDK_INT / Build.FINGERPRINT / ro.product.device 选择 Profile；
 * 加载失败时回退 default.json。
 *
 * 读取顺序：
 * 1. 模块 APK 的 assets/profiles（system_server 进程通过 ZipFile 读取，无需 App Context）
 * 2. 缓存目录 dataDir/profiles-cache.json（APK 读取失败时的回退）
 */
class ProfileManager(private val dataDir: File) {

    companion object {
        private const val TAG_SCOPE = "Profile"
        private const val ASSETS_ROOT = "assets/profiles"
        private const val DEFAULT_NAME = "default.json"
        private const val CACHE_FILE = "profiles-cache.json"

        private const val KEY_NAME = "name"
        private const val KEY_MIN_SDK = "minSdk"
        private const val KEY_MAX_SDK = "maxSdk"
        private const val KEY_DEVICE = "device"
        private const val KEY_HOOKS = "hooks"
        private const val KEY_LOCATION = "location"
    }

    @Volatile
    var current: JSONObject? = null
        private set

    /**
     * 加载并选择 Profile。
     *
     * @param moduleApkPath 模块 APK 路径（用于读取 assets/profiles/ 下的 JSON）
     */
    fun load(moduleApkPath: String) {
        val profiles = readProfiles(moduleApkPath).ifEmpty { readCache() }
        if (profiles.isEmpty()) {
            ZLog.e(TAG_SCOPE, "no profiles available")
            return
        }
        // 缓存本次读取结果，供下次 APK 不可读时回退
        writeCache(profiles)

        val selected = select(profiles) ?: profiles.firstOrNull()
        current = selected
        ZLog.i(
            TAG_SCOPE,
            "selected profile=${selected?.optString(KEY_NAME)} sdk=${Build.VERSION.SDK_INT} device=${deviceName()}"
        )
    }

    private fun readProfiles(moduleApkPath: String): List<JSONObject> {
        return try {
            val result = mutableListOf<JSONObject>()
            ZipFile(moduleApkPath).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (entry.name.startsWith(ASSETS_ROOT) && entry.name.endsWith(".json")) {
                        zip.getInputStream(entry).use { stream ->
                            val text = stream.readBytes().toString(Charsets.UTF_8)
                            result.add(JSONObject(text))
                        }
                    }
                }
            }
            result
        } catch (t: Throwable) {
            ZLog.e(TAG_SCOPE, "read profiles from apk failed", t)
            emptyList()
        }
    }

    private fun readCache(): List<JSONObject> {
        val cacheFile = File(dataDir, CACHE_FILE)
        return try {
            if (!cacheFile.exists()) return emptyList()
            val root = JSONObject(cacheFile.readText())
            val arr = root.optJSONArray("profiles") ?: return emptyList()
            (0 until arr.length()).map { JSONObject(arr.getString(it)) }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "read profiles cache failed", t)
            emptyList()
        }
    }

    private fun writeCache(profiles: List<JSONObject>) {
        return try {
            if (!dataDir.exists()) dataDir.mkdirs()
            val root = JSONObject()
            root.put("profiles", org.json.JSONArray(profiles.map { it.toString() }))
            File(dataDir, CACHE_FILE).writeText(root.toString(2))
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "write profiles cache failed", t)
        }
    }

    private fun select(profiles: List<JSONObject>): JSONObject? {
        val sdk = Build.VERSION.SDK_INT
        val device = deviceName()

        // 1. 精确设备匹配 + SDK 范围匹配
        profiles.firstOrNull { p ->
            p.optString(KEY_DEVICE) == device && sdkInRange(p, sdk)
        }?.let { return it }

        // 2. SDK 范围匹配（通配设备）
        profiles.firstOrNull { p ->
            p.optString(KEY_DEVICE) == "*" && sdkInRange(p, sdk)
        }?.let { return it }

        // 3. default.json
        profiles.firstOrNull { p ->
            p.optString(KEY_NAME, "").equals("default", ignoreCase = true)
        }?.let { return it }

        return null
    }

    private fun sdkInRange(p: JSONObject, sdk: Int): Boolean {
        val min = p.optInt(KEY_MIN_SDK, 0)
        val max = p.optInt(KEY_MAX_SDK, 99)
        return sdk in min..max
    }

    private fun deviceName(): String {
        return try {
            val props = ProcessBuilder("getprop", "ro.product.device")
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader().readLine()?.trim()
            if (props.isNullOrEmpty()) Build.DEVICE else props
        } catch (t: Throwable) {
            Build.DEVICE
        }
    }

    // ---------- Hook 配置访问 ----------

    /** 返回 location hook 配置（JSONObject），缺失时返回空对象。 */
    fun locationHookConfig(): JSONObject {
        val hooks = current?.optJSONObject(KEY_HOOKS) ?: return JSONObject()
        return hooks.optJSONObject(KEY_LOCATION) ?: JSONObject()
    }
}
