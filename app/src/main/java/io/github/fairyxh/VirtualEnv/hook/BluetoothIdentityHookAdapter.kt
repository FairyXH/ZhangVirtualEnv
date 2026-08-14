package io.github.fairyxh.VirtualEnv.hook

import io.github.fairyxh.VirtualEnv.core.Backend
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject

/**
 * 蓝牙适配器身份虚拟化（system_server 层）。
 *
 * 对应 VirtualRegion 原生 JNI Hook（BluetoothAdapter.getAddress/getName/getState/isEnabled）：
 * 我方在 system_server 的 BluetoothManagerService（IBluetoothManager 服务端）拦截，
 * 任意 App（含 GMS）调用 BluetoothAdapter 身份 API 时全局生效，无需 Hook 第三方进程。
 *
 * BLE 引擎启用（且总开关开启、未采集暂停）时返回虚拟 MAC/名称/状态；数据可配置
 * （data.adapterMac / adapterName / adapterState），缺省按虚拟位置确定性生成：
 * - MAC：locally administered unicast（02:00:00:xx:xx:xx）
 * - 名称：ZVE-BT-xxxxxx
 * - 状态：STATE_ON(12)
 */
class BluetoothIdentityHookAdapter(
    private val backend: Backend,
    private val registrar: HookRegistrar,
) {

    private val TAG_SCOPE = "Hook"

    private val bleEngine get() = backend.bleEngine

    /** 虚拟适配器身份；未启用返回 null（放行真实数据）。 */
    private fun virtualIdentity(): JSONObject? {
        if (!backend.isModuleEnabled() || backend.isSuspended() || !bleEngine.isEnabled()) return null
        val data = bleEngine.currentData() ?: return null
        val base = backend.currentLocation()
        val lat = base?.latitude ?: 24.6
        val lng = base?.longitude ?: 118.0
        val seed = ((lat * 1e5).toLong() shl 16) xor (lng * 1e5).toLong()
        val crc = java.util.zip.CRC32().apply { update(seed.toString().toByteArray()) }.value
        val defaultMac = String.format(
            "02:00:00:%02x:%02x:%02x",
            (crc shr 16) and 0xff,
            (crc shr 8) and 0xff,
            crc and 0xff
        )
        val mac = data.optString("adapterMac", "").ifBlank { defaultMac }.uppercase()
        val name = data.optString("adapterName", "").ifBlank { "ZVE-BT-${mac.takeLast(6).replace(":", "")}" }
        return JSONObject().apply {
            put("mac", mac)
            put("name", name)
            put("state", data.optInt("adapterState", 12))
        }
    }

    fun install(classLoader: ClassLoader): Int {
        val clazz = HookSupport.findClass(classLoader, "com.android.server.bluetooth.BluetoothManagerService")
        if (clazz == null) {
            ZLog.w(TAG_SCOPE, "BluetoothManagerService not found (fail-open)")
            return 0
        }
        var hooked = 0
        // BluetoothAdapter.getAddress() -> String
        HookSupport.findMethods(clazz, "getAddress")
            .firstOrNull { it.parameterCount == 0 && it.returnType == String::class.java }
            ?.let { method ->
                val ok = registrar.register(method) { chain ->
                    val v = virtualIdentity()
                    if (v != null) {
                        ZLog.d(TAG_SCOPE, "BluetoothAdapter.getAddress -> virtual ${v.optString("mac")}")
                        return@register v.optString("mac")
                    }
                    chain.proceed()
                }
                if (ok) {
                    hooked++
                    ZLog.i(TAG_SCOPE, "hooked BluetoothManagerService.getAddress")
                }
            }
        // BluetoothAdapter.getName() -> String
        HookSupport.findMethods(clazz, "getName")
            .firstOrNull { it.parameterCount == 0 && it.returnType == String::class.java }
            ?.let { method ->
                val ok = registrar.register(method) { chain ->
                    val v = virtualIdentity()
                    if (v != null) {
                        ZLog.d(TAG_SCOPE, "BluetoothAdapter.getName -> virtual ${v.optString("name")}")
                        return@register v.optString("name")
                    }
                    chain.proceed()
                }
                if (ok) {
                    hooked++
                    ZLog.i(TAG_SCOPE, "hooked BluetoothManagerService.getName")
                }
            }
        // BluetoothAdapter.getState() -> int
        HookSupport.findMethods(clazz, "getState")
            .firstOrNull { it.parameterCount == 0 && it.returnType == Int::class.javaPrimitiveType }
            ?.let { method ->
                val ok = registrar.register(method) { chain ->
                    val v = virtualIdentity()
                    if (v != null) {
                        ZLog.d(TAG_SCOPE, "BluetoothAdapter.getState -> virtual ${v.optInt("state")}")
                        return@register v.optInt("state")
                    }
                    chain.proceed()
                }
                if (ok) {
                    hooked++
                    ZLog.i(TAG_SCOPE, "hooked BluetoothManagerService.getState")
                }
            }
        // BluetoothAdapter.isEnabled() -> boolean
        HookSupport.findMethods(clazz, "isEnabled")
            .firstOrNull { it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType }
            ?.let { method ->
                val ok = registrar.register(method) { chain ->
                    if (virtualIdentity() != null) {
                        ZLog.d(TAG_SCOPE, "BluetoothAdapter.isEnabled -> true (virtual)")
                        return@register true
                    }
                    chain.proceed()
                }
                if (ok) {
                    hooked++
                    ZLog.i(TAG_SCOPE, "hooked BluetoothManagerService.isEnabled")
                }
            }
        return hooked
    }
}
