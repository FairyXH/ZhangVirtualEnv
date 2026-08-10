package io.github.fairyxh.VirtualEnv.hook

import android.os.Handler
import android.os.HandlerThread
import io.github.fairyxh.VirtualEnv.core.Backend
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.lang.reflect.Method

/**
 * WiFi 服务端 Hook Adapter（网络位置定位全局阻断）。
 *
 * 高德/百度等地图的网络定位 SDK 不经过 LocationManager，而是直接调用
 * `WifiManager.getScanResults()` / `getConnectionInfo()` 读取真实 WiFi 扫描
 * 结果与当前连接信息，发往厂商服务器换算真实坐标——这会导致 Location 层全部
 * 虚拟后地图仍拉回真实位置。
 *
 * Hook 的是 system_server 内 WiFi 服务的 Binder 实体类
 * `com.android.server.wifi.WifiServiceImpl`（位于 wifi APEX service-wifi.jar）。
 * 该类不在 system_server 的 boot classloader 中，因此通过
 * `ServiceManager.getService("wifi")` 动态发现实体实例，再取其 Class/Method。
 * Hook 服务端后对**所有 App 进程全局生效**，无需把任何第三方应用加入 scope。
 *
 * 策略（仅位置虚拟化启用时生效，fail-open）：
 * - `getScanResults`：配置了虚拟 WiFi 则返回虚拟列表；未配置则返回空列表（阻断网络定位）
 * - `getConnectionInfo`：启用时返回空 WifiInfo（SSID/BSSID 均不可用）
 */
class WifiServiceHookAdapter(
    private val backend: Backend,
    private val registrar: HookRegistrar,
) {

    companion object {
        private const val TAG_SCOPE = "Hook"
        private const val PARCELED_LIST_SLICE = "android.content.pm.ParceledListSlice"
        private const val WIFI_SERVICE_NAME = "wifi"
        private const val RETRY_DELAY_MS = 2000L
        private const val MAX_RETRIES = 30
    }

    /** 位置虚拟化启用（单点或路线任一开启；采集暂停时强制放行）。 */
    private fun virtualLocationEnabled(): Boolean =
        !backend.isSuspended() &&
            (backend.locationEngine.isEnabled() || backend.routeEngine.isRunning())

    private val installThread: HandlerThread by lazy {
        HandlerThread("ZVE-WifiHook").apply { start() }
    }

    private val installHandler: Handler by lazy { Handler(installThread.looper) }

    /**
     * WiFi 服务在 system_server 启动早期尚未注册，延迟轮询 ServiceManager，
     * 拿到实体后安装 Hook；超时后放弃（fail-open）。
     */
    fun install(classLoader: ClassLoader) {
        installHandler.post(object : Runnable {
            var retries = 0
            override fun run() {
                val clazz = findServiceImplClass()
                if (clazz != null) {
                    hookGetScanResults(clazz)
                    hookGetConnectionInfo(clazz)
                    ZLog.i(TAG_SCOPE, "WifiServiceImpl hooks installed (attempt ${retries + 1})")
                    return
                }
                retries++
                if (retries < MAX_RETRIES) {
                    installHandler.postDelayed(this, RETRY_DELAY_MS)
                } else {
                    ZLog.w(TAG_SCOPE, "WifiServiceImpl not available after $MAX_RETRIES attempts, skip")
                }
            }
        })
    }

    /** 通过 ServiceManager 动态发现 WifiServiceImpl 实体类。 */
    private fun findServiceImplClass(): Class<*>? {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, WIFI_SERVICE_NAME) as? android.os.IBinder
            if (binder == null) {
                ZLog.w(TAG_SCOPE, "ServiceManager.getService(wifi) returned null")
                return null
            }
            val clazz = binder.javaClass
            ZLog.i(TAG_SCOPE, "wifi binder class=${clazz.name} interfaces=${clazz.interfaces.joinToString { it.name }}")
            if (clazz.name.contains("WifiServiceImpl")) clazz else null
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "find WifiServiceImpl failed: ${t.message}")
            null
        }
    }

    // ---------- getScanResults(String, String): ParceledListSlice ----------

    private fun hookGetScanResults(clazz: Class<*>) {
        val method = findMethod(clazz, "getScanResults", 2) ?: return
        val ok = registrar.register(method) { chain ->
            // after：先走原始逻辑（含权限校验），再决定是否替换返回值
            val original = chain.proceed()
            try {
                val virtual = backend.wifiEngine.currentData()
                if (virtual != null) {
                    // WiFi 模拟开关打开：直接覆盖真实扫描结果（空配置也返回空列表）
                    val list = buildVirtualScanResults(virtual)
                    val slice = newParceledListSlice(method.returnType, list)
                    ZLog.d(TAG_SCOPE, "WifiService.getScanResults -> virtual ${list.size} networks")
                    slice
                } else if (!virtualLocationEnabled()) {
                    original
                } else {
                    // 虚拟定位开启但未配置虚拟 WiFi：阻断网络定位数据源
                    val slice = newParceledListSlice(method.returnType, emptyList<Any>())
                    ZLog.d(TAG_SCOPE, "WifiService.getScanResults -> empty (virtual location)")
                    slice
                }
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "WifiService.getScanResults virtual failed, fallback", t)
                original
            }
        }
        if (ok) ZLog.i(TAG_SCOPE, "hooked WifiServiceImpl.getScanResults")
    }

    /**
     * 构造虚拟扫描结果列表；未配置虚拟 WiFi 时返回空列表（阻断网络定位数据源）。
     * 返回元素类型为 android.net.wifi.ScanResult。
     */
    private fun buildVirtualScanResults(data: JSONObject?): List<Any> {
        if (data == null) return emptyList()
        val networks = data.optJSONArray("networks") ?: return emptyList()
        val resultClass = try {
            Class.forName("android.net.wifi.ScanResult")
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "ScanResult class not found", t)
            return emptyList()
        }
        val ctor = try {
            resultClass.getDeclaredConstructor().also { it.isAccessible = true }
        } catch (t: Throwable) {
            resultClass.getConstructor()
        }
        val result = mutableListOf<Any>()
        for (i in 0 until networks.length()) {
            val n = networks.optJSONObject(i) ?: continue
            try {
                val scan = ctor.newInstance()
                setField(scan, "SSID", n.optString("ssid", ""))
                setField(scan, "BSSID", n.optString("bssid", ""))
                setField(scan, "level", n.optInt("rssi", -70))
                setField(scan, "frequency", n.optInt("frequency", 2412))
                setField(scan, "capabilities", n.optString("capabilities", "[WPA2-PSK-CCMP]"))
                result.add(scan)
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "build virtual wifi result failed", t)
            }
        }
        return result
    }

    /** 用 hook 方法自身的返回类型 Class 反射构造 ParceledListSlice（避免 classloader 不一致）。 */
    private fun newParceledListSlice(returnType: Class<*>, list: List<Any>): Any {
        val ctor = try {
            returnType.getConstructor(List::class.java)
        } catch (t: Throwable) {
            // 部分版本构造器为 ParceledListSlice(List, boolean)
            return returnType.getConstructor(List::class.java, Boolean::class.java)
                .newInstance(list, false)
        }
        return ctor.newInstance(list)
    }

    // ---------- getConnectionInfo(String, String): WifiInfo ----------

    private fun hookGetConnectionInfo(clazz: Class<*>) {
        val method = findMethod(clazz, "getConnectionInfo", 2) ?: return
        val ok = registrar.register(method) { chain ->
            val original = chain.proceed()
            try {
                val virtual = backend.wifiEngine.currentData()
                if (virtual != null) {
                    // WiFi 模拟开关打开：直接覆盖当前连接信息
                    val info = buildVirtualWifiInfo(method.returnType, virtual)
                    ZLog.d(TAG_SCOPE, "WifiService.getConnectionInfo -> virtual")
                    info
                } else if (!virtualLocationEnabled()) {
                    original
                } else {
                    // 虚拟定位开启但未配置虚拟 WiFi：返回空 WifiInfo，阻断网络定位
                    val info = newEmptyWifiInfo(method.returnType)
                    ZLog.d(TAG_SCOPE, "WifiService.getConnectionInfo -> empty (virtual location)")
                    info
                }
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "WifiService.getConnectionInfo virtual failed, fallback", t)
                original
            }
        }
        if (ok) ZLog.i(TAG_SCOPE, "hooked WifiServiceImpl.getConnectionInfo")
    }

    /** 用 hook 方法自身的返回类型 Class 构造空 WifiInfo（避免 classloader 不一致）。 */
    private fun newEmptyWifiInfo(returnType: Class<*>): Any {
        val ctor = try {
            returnType.getDeclaredConstructor().also { it.isAccessible = true }
        } catch (t: Throwable) {
            returnType.getConstructor()
        }
        return ctor.newInstance()
    }

    private fun buildVirtualWifiInfo(returnType: Class<*>, data: JSONObject): Any {
        val info = newEmptyWifiInfo(returnType)
        val networks = data.optJSONArray("networks")
        val first = networks?.optJSONObject(0)
        if (first != null) {
            setField(info, "mSSID", first.optString("ssid", ""))
            setField(info, "mBSSID", first.optString("bssid", ""))
            setField(info, "mRssi", first.optInt("rssi", -70))
            setField(info, "mFrequency", first.optInt("frequency", 2412))
        }
        return info
    }

    private fun findMethod(clazz: Class<*>, name: String, paramCount: Int): Method? {
        return HookSupport.findMethods(clazz, name)
            .firstOrNull { it.parameterCount == paramCount }
            ?.also { ZLog.i(TAG_SCOPE, "resolve $name -> ${it.toGenericString()}") }
            ?: ZLog.w(TAG_SCOPE, "$name not found in ${clazz.name}").let { null }
    }

    private fun setField(target: Any, fieldName: String, value: Any) {
        try {
            target.javaClass.getField(fieldName).set(target, value)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "set field $fieldName failed", t)
        }
    }
}
