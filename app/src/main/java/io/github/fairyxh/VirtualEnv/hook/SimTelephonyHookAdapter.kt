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
 * 目标类（Oplus 15 / Android 15，JADX 确认）：
 * - com.android.phone.PhoneInterfaceManager（ITelephony.Stub，TeleService.apk）
 * - com.android.internal.telephony.PhoneSubInfoController（IPhoneSubInfo.Stub，
 *   telephony-common.jar；Oplus 15 把实现移到 framework 侧，而非 AOSP 的
 *   com.android.phone.PhoneSubInfoController）
 *
 * Android 15 方法名带 ForSubscriber 后缀（getSubscriberIdForSubscriber /
 * getIccSerialNumberForSubscriber / getLine1NumberForSubscriber 等），
 * 兼容策略：旧名 + ForSubscriber 新名都尝试，按“方法名 + 返回类型”反射查找，
 * 找不到即跳过；方法回调异常时放行原始值（fail-open）。
 */
class SimTelephonyHookAdapter(
    private val cache: EnvStateCache,
    private val registrar: HookRegistrar,
    private val phoneInterfaceClasses: List<String> = DEFAULT_PHONE_INTERFACE_CLASSES,
    private val phoneSubInfoClasses: List<String> = DEFAULT_PHONE_SUB_INFO_CLASSES,
    private val phoneClasses: List<String> = DEFAULT_PHONE_CLASSES,
) {

    companion object {
        private const val TAG_SCOPE = "Hook"

        private const val CLASS_PHONE_INTERFACE = "com.android.phone.PhoneInterfaceManager"
        private const val CLASS_PHONE_SUB_INFO = "com.android.internal.telephony.PhoneSubInfoController"

        val DEFAULT_PHONE_INTERFACE_CLASSES = listOf(
            "com.android.phone.PhoneInterfaceManager",
            "com.android.phone.PhoneInterfaceManager\$Stub",
        )

        /** Oplus 15 的 IPhoneSubInfo.Stub 实现位于 telephony-common.jar。 */
        val DEFAULT_PHONE_SUB_INFO_CLASSES = listOf(
            "com.android.internal.telephony.PhoneSubInfoController",
            "com.android.phone.PhoneSubInfoController",
        )

        /** SIM 数据最终来源（Phone 对象，VirtualRegion 的 com_android_internal_telephony_Phone_* 对应层）。 */
        val DEFAULT_PHONE_CLASSES = listOf(
            "com.android.internal.telephony.GsmCdmaPhone",
            "com.android.internal.telephony.Phone",
        )

        /** 字符串返回型 SIM 身份方法（Binder 服务端方法名，含 Android 15 ForSubscriber/WithFeature 变体）。 */
        private val STRING_METHODS = listOf(
            "getSimOperator",
            "getSimOperatorForSubscriber",
            "getSimOperatorWithFeature",
            "getSimOperatorName",
            "getSimOperatorNameForSubscriber",
            "getSimOperatorNameWithFeature",
            "getSimCountryIso",
            "getSimCountryIsoForSubscriber",
            "getSimCountryIsoWithFeature",
            "getSimSerialNumber",
            "getSubscriberId",
            "getSubscriberIdForSubscriber",
            "getSubscriberIdWithFeature",
            "getIccSerialNumber",
            "getIccSerialNumberForSubscriber",
            "getIccSerialNumberWithFeature",
            "getLine1Number",
            "getLine1NumberForSubscriber",
            "getLine1NumberWithFeature",
            "getDeviceId",
            "getDeviceIdForSubscriber",
            "getDeviceIdWithFeature",
            "getImei",
            "getImeiForSubscriber",
            "getImeiWithFeature",
            "getMeid",
            "getMeidForSubscriber",
            "getMeidWithFeature",
            "getNetworkOperator",
            "getNetworkOperatorForSubscriber",
            "getNetworkOperatorWithFeature",
            "getNetworkOperatorName",
            "getNetworkOperatorNameForSubscriber",
            "getNetworkOperatorNameWithFeature",
            "getNetworkCountryIso",
            "getNetworkCountryIsoForSubscriber",
            "getNetworkCountryIsoWithFeature",
            "getMsisdn",
            "getMsisdnForSubscriber",
            "getMsisdnWithFeature",
            "getVoiceMailNumber",
            "getVoiceMailNumberForSubscriber",
            "getVoiceMailNumberWithFeature",
        )

        /** 整型返回型方法（含 ForSubscriber/WithFeature 变体）。 */
        private val INT_METHODS = listOf(
            "getSimState",
            "getSimStateForSubscriber",
            "getSimStateWithFeature",
            "getPhoneType",
            "getPhoneTypeForSubscriber",
            "getPhoneTypeWithFeature",
            "getPhoneCount",
            "getDataNetworkType",
            "getDataNetworkTypeForSubscriber",
            "getDataNetworkTypeWithFeature",
            "getVoiceNetworkType",
            "getVoiceNetworkTypeForSubscriber",
            "getVoiceNetworkTypeWithFeature",
        )
    }

    fun install(classLoader: ClassLoader): Int {
        var hooked = 0
        val interfaces = phoneInterfaceClasses.ifEmpty { DEFAULT_PHONE_INTERFACE_CLASSES }
        for (className in interfaces) {
            val clazz = HookSupport.findClass(classLoader, className) ?: continue
            hooked += hookPhoneInterfaceManager(clazz)
            if (hooked > 0) ZLog.i(TAG_SCOPE, "sim hooks active on $className")
        }
        val subInfo = phoneSubInfoClasses.ifEmpty { DEFAULT_PHONE_SUB_INFO_CLASSES }
        for (className in subInfo) {
            val clazz = HookSupport.findClass(classLoader, className) ?: continue
            hooked += hookPhoneSubInfoController(clazz)
            if (hooked > 0) ZLog.i(TAG_SCOPE, "sim sub-info hooks active on $className")
        }
        // Phone 对象层：getSimOperator/getSimCountryIso 等最终数据源
        val phones = phoneClasses.ifEmpty { DEFAULT_PHONE_CLASSES }
        for (className in phones) {
            val clazz = HookSupport.findClass(classLoader, className) ?: continue
            hooked += hookPhoneObject(clazz)
            if (hooked > 0) ZLog.i(TAG_SCOPE, "sim phone-object hooks active on $className")
        }
        return hooked
    }

    // ---------- PhoneInterfaceManager（ITelephony.Stub） ----------

    private fun hookPhoneInterfaceManager(clazz: Class<*>): Int {
        var hooked = 0

        // 字符串身份方法：getSimOperator 等（1~4 参，含 callingPackage / featureId / subId 变体）
        STRING_METHODS.forEach { name ->
            HookSupport.findMethods(clazz, name)
                .filter { it.returnType == String::class.java }
                .forEach { method ->
                    val ok = registrar.register(method) { chain ->
                        // 先解析虚拟值：命中直接返回，避免 proceed() 触发的权限拒绝
                        val virtual = resolveString(name, chain, null)
                        if (virtual != null) {
                            ZLog.d(TAG_SCOPE, "PhoneInterfaceManager.$name -> virtual")
                            virtual
                        } else {
                            chain.proceed()
                        }
                    }
                    if (ok) {
                        hooked++
                        ZLog.i(TAG_SCOPE, "hooked PhoneInterfaceManager.$name(${method.parameterCount} params)")
                    }
                }
        }

        // 整型身份/状态方法：getSimState / getPhoneType / getPhoneCount
        INT_METHODS.forEach { name -> hooked += hookIntMethods(clazz, name) }

        // 信号状态：getSignalStrength() 返回 SignalStrength
        HookSupport.findMethods(clazz, "getSignalStrength")
            .filter { it.parameterCount in 0..3 }
            .forEach { method ->
                val ok = registrar.register(method) { chain ->
                    val virtual = VirtualSignalFactory.build(currentSimData())
                    if (virtual != null) {
                        ZLog.d(TAG_SCOPE, "PhoneInterfaceManager.getSignalStrength -> virtual")
                        virtual
                    } else {
                        chain.proceed()
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

    private fun hookPhoneSubInfoController(clazz: Class<*>): Int {
        var hooked = 0
        STRING_METHODS.forEach { name ->
            HookSupport.findMethods(clazz, name)
                .filter { it.returnType == String::class.java }
                .forEach { method ->
                    val ok = registrar.register(method) { chain ->
                        val virtual = resolveString(name, chain, null)
                        if (virtual != null) {
                            ZLog.d(TAG_SCOPE, "PhoneSubInfoController.$name -> virtual")
                            virtual
                        } else {
                            chain.proceed()
                        }
                    }
                    if (ok) {
                        hooked++
                        ZLog.i(TAG_SCOPE, "hooked PhoneSubInfoController.$name(${method.parameterCount} params)")
                    }
                }
        }
        // 信号强度（IPhoneSubInfo 也有 getSignalStrength 变体）
        HookSupport.findMethods(clazz, "getSignalStrength")
            .filter { it.parameterCount in 0..4 }
            .forEach { method ->
                val ok = registrar.register(method) { chain ->
                    val virtual = VirtualSignalFactory.build(currentSimData())
                    if (virtual != null) {
                        ZLog.d(TAG_SCOPE, "PhoneSubInfoController.getSignalStrength -> virtual")
                        virtual
                    } else {
                        chain.proceed()
                    }
                }
                if (ok) {
                    hooked++
                    ZLog.i(TAG_SCOPE, "hooked PhoneSubInfoController.getSignalStrength(${method.parameterCount} params)")
                }
            }
        return hooked
    }

    // ---------- Phone 对象（SIM 数据最终来源） ----------

    private fun hookPhoneObject(clazz: Class<*>): Int {
        var hooked = 0
        // 字符串身份方法（Phone 层多为 0 参：getSimOperator() 等）
        STRING_METHODS.forEach { name ->
            HookSupport.findMethods(clazz, name)
                .filter { it.returnType == String::class.java && it.parameterCount <= 1 }
                .forEach { method ->
                    val ok = registrar.register(method) { chain ->
                        val virtual = resolveString(name, chain, null)
                        if (virtual != null) {
                            ZLog.d(TAG_SCOPE, "Phone.$name -> virtual")
                            virtual
                        } else {
                            chain.proceed()
                        }
                    }
                    if (ok) {
                        hooked++
                        ZLog.i(TAG_SCOPE, "hooked Phone.$name(${method.parameterCount} params)")
                    }
                }
        }
        // 整型方法
        INT_METHODS.forEach { name -> hooked += hookPhoneIntMethods(clazz, name) }
        // 信号强度
        HookSupport.findMethods(clazz, "getSignalStrength")
            .filter { it.parameterCount <= 1 }
            .forEach { method ->
                val ok = registrar.register(method) { chain ->
                    val virtual = VirtualSignalFactory.build(currentSimData())
                    if (virtual != null) {
                        ZLog.d(TAG_SCOPE, "Phone.getSignalStrength -> virtual")
                        virtual
                    } else {
                        chain.proceed()
                    }
                }
                if (ok) {
                    hooked++
                    ZLog.i(TAG_SCOPE, "hooked Phone.getSignalStrength(${method.parameterCount} params)")
                }
            }
        return hooked
    }

    private fun hookPhoneIntMethods(clazz: Class<*>, name: String): Int {
        var hooked = 0
        HookSupport.findMethods(clazz, name)
            .filter { (it.returnType == Int::class.javaPrimitiveType || it.returnType == Integer::class.java) && it.parameterCount <= 1 }
            .forEach { method ->
                val ok = registrar.register(method) { chain ->
                    val virtual = resolveInt(name, chain, null)
                    if (virtual != null) {
                        ZLog.d(TAG_SCOPE, "Phone.$name -> virtual")
                        virtual
                    } else {
                        chain.proceed()
                    }
                }
                if (ok) {
                    hooked++
                    ZLog.i(TAG_SCOPE, "hooked Phone.$name(${method.parameterCount} params)")
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
                    val virtual = resolveInt(name, chain, null)
                    if (virtual != null) {
                        ZLog.d(TAG_SCOPE, "PhoneInterfaceManager.$name -> virtual")
                        virtual
                    } else {
                        chain.proceed()
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
            // Android 15 ForSubscriber/WithFeature 后缀与旧名统一映射到同一字段
            val base = name.removeSuffix("ForSubscriber").removeSuffix("WithFeature")
            val value = when (base) {
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
            val base = name.removeSuffix("ForSubscriber").removeSuffix("WithFeature")
            when (base) {
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
