package io.github.fairyxh.VirtualEnv.hook

import io.github.fairyxh.VirtualEnv.core.EnvStateCache
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * SIM 卡身份/信号全局虚拟化 Hook Adapter（com.android.phone 进程 Binder 服务端）。
 *
 * 借鉴 VirtualRegion 的 SIM Hook 点清单（TelephonyManager 的 getSimOperator /
 * getSimOperatorName / getSimCountryIso / getSimSerialNumber / getSubscriberId /
 * getLine1Number / getDeviceId / getImei / getMeid / getNetworkOperator /
 * getNetworkOperatorName / getNetworkCountryIso / getSimState / getPhoneType /
 * getPhoneCount，以及 SignalStrength 信号强度），但**不在第三方 App 进程注入**：
 * 全部在 com.android.phone 进程的 Binder 服务端实现类上 Hook，任意 App 通过
 * ITelephony / IPhoneSubInfo Binder 调用时都会被替换，全局生效。
 *
 * 目标类（AOSP / Oplus 15，JADX 确认）：
 * - com.android.phone.PhoneInterfaceManager（ITelephony.Stub）
 * - com.android.phone.PhoneSubInfoController（IPhoneSubInfo.Stub）
 *
 * 兼容策略：所有方法按“方法名 + 返回类型 + 参数个数”反射查找，找不到即跳过；
 * 方法回调异常时放行原始值（fail-open）。
 */
class SimTelephonyHookAdapter(
    private val cache: EnvStateCache,
    private val registrar: HookRegistrar,
) {

    companion object {
        private const val TAG_SCOPE = "Hook"

        private const val CLASS_PHONE_INTERFACE = "com.android.phone.PhoneInterfaceManager"
        private const val CLASS_PHONE_SUB_INFO = "com.android.phone.PhoneSubInfoController"

        /** 字符串返回型 SIM 身份方法（Binder 服务端方法名）。 */
        private val STRING_METHODS = listOf(
            "getSimOperator",
            "getSimOperatorName",
            "getSimCountryIso",
            "getSimSerialNumber",
            "getSubscriberId",
            "getIccSerialNumber",
            "getLine1Number",
            "getDeviceId",
            "getImei",
            "getMeid",
            "getNetworkOperator",
            "getNetworkOperatorName",
            "getNetworkCountryIso",
            "getMsisdn",
            "getVoiceMailNumber",
        )
    }

    fun install(classLoader: ClassLoader): Int {
        var hooked = 0
        hooked += hookPhoneInterfaceManager(classLoader)
        hooked += hookPhoneSubInfoController(classLoader)
        return hooked
    }

    // ---------- PhoneInterfaceManager（ITelephony.Stub） ----------

    private fun hookPhoneInterfaceManager(classLoader: ClassLoader): Int {
        val clazz = HookSupport.findClass(classLoader, CLASS_PHONE_INTERFACE) ?: return 0
        var hooked = 0

        // 字符串身份方法：getSimOperator 等（1~4 参，含 callingPackage / featureId / subId 变体）
        STRING_METHODS.forEach { name ->
            HookSupport.findMethods(clazz, name)
                .filter { it.returnType == String::class.java }
                .forEach { method ->
                    val ok = registrar.register(method) { chain ->
                        val original = chain.proceed()
                        val virtual = resolveString(name, chain, original)
                        if (virtual != null) {
                            ZLog.d(TAG_SCOPE, "PhoneInterfaceManager.$name -> virtual")
                            virtual
                        } else {
                            original
                        }
                    }
                    if (ok) {
                        hooked++
                        ZLog.i(TAG_SCOPE, "hooked PhoneInterfaceManager.$name(${method.parameterCount} params)")
                    }
                }
        }

        // 整型身份/状态方法：getSimState / getPhoneType / getPhoneCount
        hooked += hookIntMethods(clazz, "getSimState")
        hooked += hookIntMethods(clazz, "getPhoneType")
        hooked += hookIntMethods(clazz, "getPhoneCount")
        hooked += hookIntMethods(clazz, "getDataNetworkType")
        hooked += hookIntMethods(clazz, "getVoiceNetworkType")

        // 信号状态：getSignalStrength() 返回 SignalStrength
        HookSupport.findMethods(clazz, "getSignalStrength")
            .filter { it.parameterCount in 0..3 }
            .forEach { method ->
                val ok = registrar.register(method) { chain ->
                    val original = chain.proceed()
                    val virtual = VirtualSignalFactory.build(currentSimData())
                    if (virtual != null) {
                        ZLog.d(TAG_SCOPE, "PhoneInterfaceManager.getSignalStrength -> virtual")
                        virtual
                    } else {
                        original
                    }
                }
                if (ok) {
                    hooked++
                    ZLog.i(TAG_SCOPE, "hooked PhoneInterfaceManager.getSignalStrength(${method.parameterCount} params)")
                }
            }

        return hooked
    }

    // ---------- PhoneSubInfoController（IPhoneSubInfo.Stub） ----------

    private fun hookPhoneSubInfoController(classLoader: ClassLoader): Int {
        val clazz = HookSupport.findClass(classLoader, CLASS_PHONE_SUB_INFO) ?: return 0
        var hooked = 0
        STRING_METHODS.forEach { name ->
            HookSupport.findMethods(clazz, name)
                .filter { it.returnType == String::class.java }
                .forEach { method ->
                    val ok = registrar.register(method) { chain ->
                        val original = chain.proceed()
                        val virtual = resolveString(name, chain, original)
                        if (virtual != null) {
                            ZLog.d(TAG_SCOPE, "PhoneSubInfoController.$name -> virtual")
                            virtual
                        } else {
                            original
                        }
                    }
                    if (ok) {
                        hooked++
                        ZLog.i(TAG_SCOPE, "hooked PhoneSubInfoController.$name(${method.parameterCount} params)")
                    }
                }
        }
        return hooked
    }

    // ---------- 整型方法 ----------

    private fun hookIntMethods(clazz: Class<*>, name: String): Int {
        var hooked = 0
        HookSupport.findMethods(clazz, name)
            .filter { it.returnType == Int::class.javaPrimitiveType || it.returnType == Integer::class.java }
            .forEach { method ->
                val ok = registrar.register(method) { chain ->
                    val original = chain.proceed()
                    val virtual = resolveInt(name, chain, original)
                    if (virtual != null) {
                        ZLog.d(TAG_SCOPE, "PhoneInterfaceManager.$name -> virtual")
                        virtual
                    } else {
                        original
                    }
                }
                if (ok) {
                    hooked++
                    ZLog.i(TAG_SCOPE, "hooked $name(${method.parameterCount} params)")
                }
            }
        return hooked
    }

    // ---------- 解析 ----------

    /** 当前启用 SIM 的 data JSON（含 slots）；未启用返回 null。 */
    private fun currentSimData(): JSONObject? = cache.currentSim()

    /** 按 slotIndex 匹配的 SIM 槽配置；参数里没有索引时使用默认（第一个启用）槽。 */
    private fun resolveSlot(chain: Any): JSONObject? {
        val data = currentSimData() ?: return null
        val slots = data.optJSONArray("slots") ?: return null
        if (slots.length() == 0) return null
        val idx = findIntArg(chain)
        if (idx != null) {
            for (i in 0 until slots.length()) {
                val slot = slots.optJSONObject(i) ?: continue
                if (slot.optInt("slotIndex", -1) == idx || slot.optInt("subId", -1) == idx) {
                    return slot
                }
            }
        }
        // 默认：第一个启用槽
        for (i in 0 until slots.length()) {
            val slot = slots.optJSONObject(i) ?: continue
            if (slot.optBoolean("enabled", true)) return slot
        }
        return slots.optJSONObject(0)
    }

    private fun resolveString(name: String, chain: Any, original: Any?): String? {
        return try {
            val slot = resolveSlot(chain) ?: return null
            val value = when (name) {
                "getSimOperator" -> {
                    val mcc = slot.optString("mcc", "")
                    val mnc = slot.optString("mnc", "")
                    if (mcc.isBlank()) null else mcc + mnc
                }
                "getSimOperatorName" -> slot.optString("simOperatorName").ifBlank { slot.optString("operatorName") }.ifEmpty { null }
                "getSimCountryIso" -> slot.optString("simCountryIso").ifBlank { slot.optString("countryIso") }.ifEmpty { null }
                "getSimSerialNumber", "getIccSerialNumber" -> slot.optString("simSerialNumber").ifBlank { slot.optString("iccid") }.ifEmpty { null }
                "getSubscriberId" -> slot.optString("subscriberId").ifBlank { slot.optString("imsi") }.ifEmpty { null }
                "getLine1Number", "getMsisdn" -> slot.optString("line1Number").ifBlank { slot.optString("msisdn") }.ifEmpty { null }
                "getDeviceId" -> slot.optString("deviceId").ifEmpty { null }
                "getImei" -> slot.optString("imei").ifEmpty { null }
                "getMeid" -> slot.optString("meid").ifEmpty { null }
                "getNetworkOperator" -> {
                    val mcc = slot.optString("mcc", "")
                    val mnc = slot.optString("mnc", "")
                    if (mcc.isBlank()) null else mcc + mnc
                }
                "getNetworkOperatorName" -> slot.optString("networkOperatorName").ifBlank { slot.optString("operatorName") }.ifEmpty { null }
                "getNetworkCountryIso" -> slot.optString("networkCountryIso").ifBlank { slot.optString("countryIso") }.ifEmpty { null }
                "getVoiceMailNumber" -> slot.optString("voiceMailNumber").ifEmpty { null }
                else -> null
            }
            // 显式返回空串（配置了但空）会覆盖真实值；null 表示未配置放行
            if (value != null && value.isNotEmpty()) value else null
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "resolveString $name failed, fallback", t)
            null
        }
    }

    private fun resolveInt(name: String, chain: Any, original: Any?): Int? {
        return try {
            val slot = resolveSlot(chain) ?: return null
            when (name) {
                "getSimState" -> {
                    val v = slot.optInt("simState", -1)
                    if (v >= 0) v else null
                }
                "getPhoneType" -> {
                    val v = slot.optInt("phoneType", -1)
                    if (v >= 0) v else null
                }
                "getPhoneCount" -> {
                    val v = slot.optInt("phoneCount", -1)
                    if (v > 0) v else null
                }
                "getDataNetworkType" -> {
                    val v = slot.optInt("dataNetworkType", -1)
                    if (v >= 0) v else null
                }
                "getVoiceNetworkType" -> {
                    val v = slot.optInt("voiceNetworkType", -1)
                    if (v >= 0) v else null
                }
                else -> null
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "resolveInt $name failed, fallback", t)
            null
        }
    }

    /** 在参数里找第一个 int / Integer 参数（subId 或 slotIndex）。 */
    private fun findIntArg(chain: Any): Int? {
        return try {
            val args = chain.javaClass.getMethod("getArgs").invoke(chain) as? List<*> ?: return null
            for (arg in args) {
                if (arg is Int) {
                    if (arg >= 0) return arg
                } else if (arg is Number) {
                    val v = arg.toInt()
                    if (v >= 0) return v
                }
            }
            null
        } catch (t: Throwable) {
            null
        }
    }
}
