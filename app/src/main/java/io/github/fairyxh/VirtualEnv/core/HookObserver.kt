package io.github.fairyxh.VirtualEnv.core

import android.location.Location
import io.github.fairyxh.VirtualEnv.util.CellInfoRead
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hook 层真实数据观测器（system_server 进程）。
 *
 * 采集真实环境时（App 调用 /api/env/suspend 后），各 Hook 点会先拦截到未虚拟化的
 * 真实数据；本对象把这些数据按类型记录为快照，App 通过
 * /api/debug/observe/snapshot 读取并合并进采集结果，用于“Hook 层真实数据检验”，
 * 而不是只信普通 App 视角的读取结果。
 *
 * 仅在 [begin] 后记录（默认关闭，避免常驻开销）。观测点：
 * - LocationHookAdapter：LocationProviderManager.onReportLocation / GnssLocationProvider.onReportLocation
 *   / LocationManagerService.getLastLocation 的真实位置（虚拟替换前）
 * - GnssDataBlockHookAdapter：GnssLocationProvider.onReportSvStatus 的真实卫星状态
 * - WifiServiceHookAdapter：WifiServiceImpl.getScanResults 的真实扫描列表（替换前）
 * - CellObserveHookAdapter：TelephonyRegistry.notifyCellInfoForSubscriber 的真实小区列表；
 *   采集挂起期间 snapshot 还会由 Backend 直连 phone Binder 实时拉取真实基站兜底
 */
object HookObserver {

    private const val TAG_SCOPE = "Observe"

    @Volatile
    private var active = false

    private val lock = Any()

    /** 最近一条真实位置（扁平字段 + provider）。 */
    private var location: JSONObject? = null

    /** 最近真实基站列表（CellInfoRead.cellToJson 摘要）。 */
    private var cells: JSONArray? = null

    /** 最近真实 WiFi 扫描列表。 */
    private var wifi: JSONArray? = null

    /** 最近真实 GNSS 状态。 */
    private var gnss: JSONObject? = null

    /** 最近真实 NMEA 句子（最多保留 3 条，按时间倒序）。 */
    private var nmea: JSONArray? = null

    /** 是否处于观测状态。 */
    fun isActive(): Boolean = active

    /** 开启新一轮观测：清空旧快照并开始记录。 */
    fun begin() {
        synchronized(lock) {
            location = null
            cells = null
            wifi = null
            gnss = null
            nmea = null
            active = true
        }
        ZLog.i(TAG_SCOPE, "hook observation started")
    }

    /** 结束观测（保留最后一次快照供读取）。 */
    fun end() {
        active = false
        ZLog.i(TAG_SCOPE, "hook observation ended")
    }

    fun recordLocation(location: Location) {
        if (!active) return
        recordLocation(location.provider, location.latitude, location.longitude, location.accuracy, location.time)
    }

    fun recordLocation(provider: String?, latitude: Double, longitude: Double, accuracy: Float, time: Long) {
        if (!active) return
        synchronized(lock) {
            this.location = JSONObject().apply {
                put("provider", provider ?: "?")
                put("latitude", latitude)
                put("longitude", longitude)
                put("accuracy", accuracy)
                put("time", time)
            }
        }
    }

    /** 从 LocationResult 提取第一条真实位置（LocationProviderManager.onReportLocation 参数）。 */
    fun recordLocationResult(result: Any?) {
        if (!active || result == null) return
        try {
            val m = result.javaClass.getMethod("asList")
            val list = m.invoke(result) as? List<*> ?: return
            val loc = list.firstOrNull { it is Location } as? Location ?: return
            recordLocation(loc)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "record location result failed: ${t.message}")
        }
    }

    fun recordGnss(satelliteCount: Int, usedInFix: Int) {
        if (!active) return
        synchronized(lock) {
            gnss = JSONObject().apply {
                put("satelliteCount", satelliteCount)
                put("usedInFix", usedInFix)
            }
        }
    }

    /** 从真实 GnssStatus 提取卫星摘要（GnssLocationProvider.onReportSvStatus 参数）。 */
    fun recordGnssStatus(status: Any?) {
        if (!active || status == null) return
        try {
            val cls = status.javaClass
            val count = cls.getMethod("getSatelliteCount").invoke(status) as? Int ?: return
            val used = (0 until count).count { i ->
                (cls.getMethod("usedInFix", Int::class.javaPrimitiveType).invoke(status, i) as? Boolean) ?: false
            }
            recordGnss(count, used)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "record gnss status failed: ${t.message}")
        }
    }

    fun recordWifi(networks: List<*>) {
        if (!active) return
        val arr = JSONArray()
        networks.forEach { n ->
            if (n == null) return@forEach
            try {
                val cls = n.javaClass
                fun str(name: String): String = try {
                    cls.getMethod("get$name").invoke(n) as? String ?: ""
                } catch (_: Throwable) {
                    ""
                }
                fun intVal(name: String): Int = try {
                    (cls.getMethod("get$name").invoke(n) as? Int) ?: -1
                } catch (_: Throwable) {
                    -1
                }
                arr.put(JSONObject().apply {
                    put("ssid", str("SSID"))
                    put("bssid", str("BSSID"))
                    put("rssi", intVal("Level"))
                    put("frequency", intVal("Frequency"))
                })
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "record wifi item failed: ${t.message}")
            }
        }
        if (arr.length() == 0) return
        synchronized(lock) { wifi = arr }
    }

    /** 从 ParceledListSlice 提取扫描列表（WifiServiceImpl.getScanResults 原始返回值）。 */
    fun recordWifiSlice(slice: Any?) {
        if (!active || slice == null) return
        try {
            val m = slice.javaClass.getMethod("getList")
            val list = m.invoke(slice) as? List<*> ?: return
            recordWifi(list)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "record wifi slice failed: ${t.message}")
        }
    }

    /** 记录真实 NMEA 句子（GnssLocationProvider.onReportNmea 参数）。 */
    fun recordNmea(sentence: String?) {
        if (!active || sentence.isNullOrBlank()) return
        synchronized(lock) {
            val arr = nmea ?: JSONArray()
            val item = JSONObject().apply {
                put("time", System.currentTimeMillis())
                put("sentence", sentence.trim().take(120))
            }
            // 倒序：最新在前，最多 3 条
            val next = JSONArray().put(item)
            for (i in 0 until minOf(arr.length(), 2)) next.put(arr.opt(i))
            nmea = next
        }
    }

    fun recordCell(cells: List<*>) {
        if (!active) return
        val arr = JSONArray()
        cells.forEach { c ->
            if (c == null) return@forEach
            try {
                arr.put(CellInfoRead.cellToJson(c))
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "record cell item failed: ${t.message}")
            }
        }
        if (arr.length() == 0) return
        synchronized(lock) { this.cells = arr }
    }

    fun recordCellList(cells: Any?) {
        if (!active || cells !is List<*>) return
        recordCell(cells)
    }

    /** 当前观测快照（含最近真实数据与观测状态）。 */
    fun snapshotJson(): JSONObject {
        return synchronized(lock) {
            JSONObject().apply {
                put("active", active)
                put("recordedAt", System.currentTimeMillis())
                location?.let { put("location", it) }
                cells?.let { put("cells", it) }
                wifi?.let { put("wifi", it) }
                gnss?.let { put("gnss", it) }
                nmea?.let { put("nmea", it) }
            }
        }
    }

    /** Build a recording frame from the system-server observation plane. */
    fun recordingFrameJson(): JSONObject {
        val observed = snapshotJson()
        val observedLocation = observed.optJSONObject("location")
        val normalizedLocation = JSONObject()
        if (observedLocation != null) {
            val provider = observedLocation.optString("provider", "gps").ifBlank { "gps" }
            normalizedLocation.put(provider, JSONObject(observedLocation.toString()))
            observedLocation.optDouble("latitude", Double.NaN).takeUnless { it.isNaN() }?.let {
                normalizedLocation.put("latitude", it)
            }
            observedLocation.optDouble("longitude", Double.NaN).takeUnless { it.isNaN() }?.let {
                normalizedLocation.put("longitude", it)
            }
        }
        return JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("location", normalizedLocation)
            put("cell", JSONObject().apply {
                put("cells", observed.optJSONArray("cells") ?: JSONArray())
            })
            put("wifi", JSONObject().apply {
                put("networks", observed.optJSONArray("wifi") ?: JSONArray())
            })
            put("bluetooth", JSONObject())
            put("gnss", observed.optJSONObject("gnss") ?: JSONObject())
            put("sensor", JSONObject())
            put("hookObserve", observed)
        }
    }

    /** 是否有任一真实数据。 */
    fun hasAny(): Boolean = synchronized(lock) {
        location != null || (cells?.length() ?: 0) > 0 || (wifi?.length() ?: 0) > 0 || gnss != null || (nmea?.length() ?: 0) > 0
    }

    /**
     * 采集挂起期间实时拉取真实基站（兜底通道）：
     * system_server 直连 phone Binder ITelephony.getAllCellInfo，phone 进程的
     * PhoneInterfaceManagerHookAdapter 在虚拟化关闭（挂起）时放行原始真实数据。
     */
    fun pullRealCellsFromPhone(): List<*> {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "phone") as? android.os.IBinder
                ?: return emptyList<Any>()
            val itelephonyClass = Class.forName("com.android.internal.telephony.ITelephony")
            val stub = itelephonyClass.declaredClasses.firstOrNull { it.simpleName == "Stub" }
                ?: return emptyList<Any>()
            val it = stub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
                ?: return emptyList<Any>()
            val cells = try {
                val m = it.javaClass.getMethod("getAllCellInfo", String::class.java, String::class.java)
                m.invoke(it, "android", "") as? List<*>
            } catch (_: NoSuchMethodException) {
                val m = it.javaClass.getMethod("getAllCellInfo")
                m.invoke(it) as? List<*>
            } ?: return emptyList<Any>()
            if (active) recordCell(cells)
            cells
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "pull real cells from phone failed: ${t.message}")
            emptyList<Any>()
        }
    }
}
