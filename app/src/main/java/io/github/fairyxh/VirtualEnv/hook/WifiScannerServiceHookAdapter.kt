package io.github.fairyxh.VirtualEnv.hook

import android.os.Handler
import android.os.HandlerThread
import io.github.fairyxh.VirtualEnv.core.Backend
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.lang.reflect.Array

/**
 * Covers the independent `wifiscanner` Binder data plane used by system Wi-Fi pickers.
 *
 * `WifiServiceImpl.getScanResults()` is not authoritative for clients that register an
 * `IWifiScannerListener`; those clients receive ScanData directly from WifiScanningServiceImpl.
 */
class WifiScannerServiceHookAdapter(
    private val backend: Backend,
    private val registrar: HookRegistrar,
    private val scanResultBuilder: (JSONObject?) -> List<Any>,
) {

    companion object {
        private const val TAG_SCOPE = "Hook"
        private const val SERVICE_NAME = "wifiscanner"
        private const val RETRY_DELAY_MS = 2000L
        private const val MAX_RETRIES = 30
        private const val SCAN_DATA_CLASS = "android.net.wifi.WifiScanner\$ScanData"
        private const val SCANNER_LISTENER_PROXY = "android.net.wifi.IWifiScannerListener\$Stub\$Proxy"
        private const val SCAN_DATA_LISTENER_PROXY = "android.net.wifi.IScanDataListener\$Stub\$Proxy"
    }

    private val installThread: HandlerThread by lazy {
        HandlerThread("ZVE-WifiScannerHook").apply { start() }
    }
    private val installHandler: Handler by lazy { Handler(installThread.looper) }

    fun install() {
        installHandler.post(object : Runnable {
            var retries = 0

            override fun run() {
                val serviceClass = findServiceClass()
                if (serviceClass != null) {
                    val loader = serviceClass.classLoader ?: ClassLoader.getSystemClassLoader()
                    val hooked = hookGetSingleScanResults(serviceClass) +
                        hookScannerListenerProxy(loader) +
                        hookScanDataListenerProxy(loader)
                    ZLog.i(TAG_SCOPE, "WifiScanningService hooks installed hooked=$hooked attempt=${retries + 1}")
                    return
                }
                retries++
                if (retries < MAX_RETRIES) {
                    installHandler.postDelayed(this, RETRY_DELAY_MS)
                } else {
                    ZLog.w(TAG_SCOPE, "WifiScanningService not available after $MAX_RETRIES attempts")
                }
            }
        })
    }

    private fun findServiceClass(): Class<*>? {
        return try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, SERVICE_NAME) ?: return null
            binder.javaClass.also {
                ZLog.i(TAG_SCOPE, "wifiscanner binder class=${it.name}")
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "find WifiScanningService failed: ${t.message}")
            null
        }
    }

    private fun hookGetSingleScanResults(serviceClass: Class<*>): Int {
        val method = HookSupport.findMethods(serviceClass, "getSingleScanResults")
            .firstOrNull { it.parameterCount == 2 && List::class.java.isAssignableFrom(it.returnType) }
            ?: return 0
        val ok = registrar.register(method) { chain ->
            val original = chain.proceed()
            val data = backend.wifiEngine.currentData() ?: return@register original
            try {
                val virtual = scanResultBuilder(data)
                if (backend.isScanBlockingEnabled()) {
                    virtual
                } else {
                    buildList {
                        (original as? List<*>)?.filterNotNull()?.forEach(::add)
                        addAll(virtual)
                    }
                }
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "transform getSingleScanResults failed, fallback", t)
                original
            }
        }
        if (ok) ZLog.i(TAG_SCOPE, "hooked WifiScanningService.getSingleScanResults")
        return if (ok) 1 else 0
    }

    private fun hookScannerListenerProxy(loader: ClassLoader): Int {
        val clazz = HookSupport.findClass(loader, SCANNER_LISTENER_PROXY) ?: return 0
        var hooked = 0
        HookSupport.findMethods(clazz, "onResults")
            .firstOrNull { it.parameterCount == 1 }
            ?.let { method ->
                if (registrar.register(method) { chain ->
                        val data = backend.wifiEngine.currentData()
                            ?: return@register chain.proceed()
                        val componentType = method.parameterTypes[0].componentType
                            ?: return@register chain.proceed()
                        val replacement = try {
                            buildScanDataArray(
                                componentType,
                                chain.getArg(0),
                                scanResultBuilder(data),
                                backend.isScanBlockingEnabled(),
                            )
                        } catch (t: Throwable) {
                            ZLog.w(TAG_SCOPE, "transform WifiScanner onResults failed, fallback", t)
                            null
                        }
                        if (replacement != null) chain.proceed(arrayOf(replacement)) else chain.proceed()
                    }) {
                    hooked++
                    ZLog.i(TAG_SCOPE, "hooked IWifiScannerListener.Proxy.onResults")
                }
            }
        HookSupport.findMethods(clazz, "onFullResult")
            .firstOrNull { it.parameterCount == 1 }
            ?.let { method ->
                if (registrar.register(method) { chain ->
                        if (shouldBlockRealScan()) null else chain.proceed()
                    }) {
                    hooked++
                    ZLog.i(TAG_SCOPE, "hooked IWifiScannerListener.Proxy.onFullResult")
                }
            }
        HookSupport.findMethods(clazz, "onPnoNetworkFound")
            .firstOrNull { it.parameterCount == 1 }
            ?.let { method ->
                if (registrar.register(method) { chain ->
                        val data = backend.wifiEngine.currentData()
                            ?: return@register chain.proceed()
                        val componentType = method.parameterTypes[0].componentType
                            ?: return@register chain.proceed()
                        val replacement = try {
                            buildScanResultArray(
                                componentType,
                                chain.getArg(0),
                                scanResultBuilder(data),
                                backend.isScanBlockingEnabled(),
                            )
                        } catch (t: Throwable) {
                            ZLog.w(TAG_SCOPE, "transform WifiScanner PNO results failed, fallback", t)
                            null
                        }
                        if (replacement != null) chain.proceed(arrayOf(replacement)) else chain.proceed()
                    }) {
                    hooked++
                    ZLog.i(TAG_SCOPE, "hooked IWifiScannerListener.Proxy.onPnoNetworkFound")
                }
            }
        return hooked
    }

    private fun hookScanDataListenerProxy(loader: ClassLoader): Int {
        val clazz = HookSupport.findClass(loader, SCAN_DATA_LISTENER_PROXY) ?: return 0
        val method = HookSupport.findMethods(clazz, "onResult")
            .firstOrNull { it.parameterCount == 1 }
            ?: return 0
        val ok = registrar.register(method) { chain ->
            val data = backend.wifiEngine.currentData() ?: return@register chain.proceed()
            val replacement = try {
                buildScanData(
                    method.parameterTypes[0],
                    chain.getArg(0),
                    scanResultBuilder(data),
                    backend.isScanBlockingEnabled(),
                )
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "transform cached ScanData failed, fallback", t)
                null
            }
            if (replacement != null) chain.proceed(arrayOf(replacement)) else chain.proceed()
        }
        if (ok) ZLog.i(TAG_SCOPE, "hooked IScanDataListener.Proxy.onResult")
        return if (ok) 1 else 0
    }

    private fun buildScanDataArray(
        scanDataClass: Class<*>,
        original: Any?,
        virtual: List<Any>,
        exclusive: Boolean,
    ): Any {
        val merged = if (exclusive) virtual else extractScanResults(original) + virtual
        val scanData = newScanData(scanDataClass, merged)
        return Array.newInstance(scanDataClass, 1).also { Array.set(it, 0, scanData) }
    }

    private fun buildScanData(
        scanDataClass: Class<*>,
        original: Any?,
        virtual: List<Any>,
        exclusive: Boolean,
    ): Any {
        val originalResults = original?.let { extractResultsFromScanData(it) }.orEmpty()
        return newScanData(scanDataClass, if (exclusive) virtual else originalResults + virtual)
    }

    private fun newScanData(scanDataClass: Class<*>, results: List<Any>): Any {
        val ctor = scanDataClass.getConstructor(
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            List::class.java,
        )
        return ctor.newInstance(0, 0, 0, 0, results)
    }

    private fun extractScanResults(scanDataArray: Any?): List<Any> {
        if (scanDataArray == null || !scanDataArray.javaClass.isArray) return emptyList()
        return buildList {
            for (index in 0 until Array.getLength(scanDataArray)) {
                Array.get(scanDataArray, index)?.let { addAll(extractResultsFromScanData(it)) }
            }
        }
    }

    private fun extractResultsFromScanData(scanData: Any): List<Any> {
        val results = scanData.javaClass.getMethod("getResults").invoke(scanData) ?: return emptyList()
        if (!results.javaClass.isArray) return emptyList()
        return buildList {
            for (index in 0 until Array.getLength(results)) {
                Array.get(results, index)?.let(::add)
            }
        }
    }

    private fun buildScanResultArray(
        componentType: Class<*>,
        original: Any?,
        virtual: List<Any>,
        exclusive: Boolean,
    ): Any {
        val merged = buildList {
            if (!exclusive && original != null && original.javaClass.isArray) {
                for (index in 0 until Array.getLength(original)) {
                    Array.get(original, index)?.let(::add)
                }
            }
            addAll(virtual)
        }
        return Array.newInstance(componentType, merged.size).also { array ->
            merged.forEachIndexed { index, value -> Array.set(array, index, value) }
        }
    }

    private fun shouldBlockRealScan(): Boolean =
        backend.wifiEngine.currentData() != null && backend.isScanBlockingEnabled()
}
