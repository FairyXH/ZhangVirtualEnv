package io.github.fairyxh.VirtualEnv.hook

import io.github.fairyxh.VirtualEnv.core.EnvStateCache
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * SubscriptionInfo 全局虚拟化 Hook Adapter（system_server 进程）。
 *
 * 借鉴 VirtualRegion 的 SubscriptionInfo Hook 点（getCountryIso / getMcc /
 * getMccString / getMnc / getMncString / getCarrierName / getDisplayName /
 * getIccId / getNumber）。但 VirtualRegion 在 App 进程 hook getter；本模块按
 * 硬约束不得 Hook 第三方 App，因此改为在 system_server 的
 * SubscriptionManagerService / SubscriptionController（ISub.Stub）返回点
 * **直接反射改写 SubscriptionInfo 字段**。字段在 Binder parcel 之前被修改，
 * 任意 App 读取 getActiveSubscriptionInfoList() 都拿到虚拟 SIM 身份，全局生效。
 *
 * 兼容策略：
 * - 类名候选：com.android.server.telephony.SubscriptionManagerService（API 30+）
 *   与 com.android.server.telephony.SubscriptionController（旧版）
 * - 方法名候选：getActiveSubscriptionInfoList / getActiveSubscriptionInfoList(String)
 * - 字段名候选：mIccId / mCarrierName / mDisplayName / mCountryIso / mMcc /
 *   mMnc / mNumber / mSimSlotIndex（各 ROM 可能有 m 前缀差异，逐个尝试）
 * - 任一失败放行原始值（fail-open）
 */
class SimSubscriptionHookAdapter(
    private val simDataProvider: () -> org.json.JSONObject?,
    private val registrar: HookRegistrar,
    private val subscriptionClasses: List<String> = DEFAULT_SUBSCRIPTION_CLASSES,
) {

    companion object {
        private const val TAG_SCOPE = "Hook"

        /** AOSP / 常见 ROM 的 ISub.Stub 实现类候选（可经 Profile sim.subscriptionClasses 覆盖）。 */
        val DEFAULT_SUBSCRIPTION_CLASSES = listOf(
            "com.android.internal.telephony.subscription.SubscriptionManagerService",
            "com.android.server.telephony.SubscriptionManagerService",
            "com.android.server.telephony.SubscriptionController",
            "com.android.internal.telephony.SubscriptionController",
        )

        private val LIST_METHOD_NAMES = listOf(
            "getActiveSubscriptionInfoList",
            "getActiveSubscriptionInfo",
            "getActiveSubscriptionInfoForSimSlotIndex",
            "getSubscriptionInfo",
            "getSubscriptionInfoForIccId",
        )
        private val INT_METHOD_NAMES = listOf(
            "getDefaultSubscriptionId",
            "getDefaultDataSubscriptionId",
            "getDefaultVoiceSubscriptionId",
            "getDefaultSmsSubscriptionId",
            "getActiveDataSubscriptionId",
        )

        // SubscriptionInfo 字段候选名（AOSP 字段 + Oplus 变体）
        private val FIELDS_STRING = mapOf(
            "mIccId" to "simSerialNumber",
            "mCarrierName" to "operatorName",
            "mDisplayName" to "operatorName",
            "mCountryIso" to "countryIso",
            "mIso" to "countryIso",
            "mNumber" to "line1Number",
        )
        private val FIELDS_INT = mapOf(
            "mMcc" to "mcc",
            "mMnc" to "mnc",
            "mSimSlotIndex" to "slotIndex",
            "mSubscriptionId" to "subId",
        )
    }

    fun install(classLoader: ClassLoader): Int {
        var hooked = 0
        val candidates = subscriptionClasses.ifEmpty { DEFAULT_SUBSCRIPTION_CLASSES }
        for (className in candidates) {
            val clazz = HookSupport.findClass(classLoader, className) ?: continue
            hooked += installOnClass(clazz)
            if (hooked > 0) ZLog.i(TAG_SCOPE, "sim subscription hooks active on $className")
        }
        return hooked
    }

    private fun installOnClass(clazz: Class<*>): Int {
        var hooked = 0

        // 返回 List<SubscriptionInfo> / SubscriptionInfo 的方法
        LIST_METHOD_NAMES.forEach { name ->
            HookSupport.findMethods(clazz, name).forEach { method ->
                val returnType = method.returnType
                val isList = returnType.name == "java.util.List"
                    || returnType.name.startsWith("java.util.List")
                    || returnType.simpleName.contains("List")
                if (!isList && returnType.simpleName != "SubscriptionInfo") return@forEach
                val ok = registrar.register(method) { chain ->
                    val original = chain.proceed()
                    try {
                        val virtual = currentSimData()
                        if (virtual == null) return@register original
                        if (isList) {
                            rewriteList(original, virtual)
                        } else {
                            rewriteOne(original, virtual)
                        }
                        original
                    } catch (t: Throwable) {
                        ZLog.w(TAG_SCOPE, "${clazz.name}.$name rewrite failed, fallback", t)
                        original
                    }
                }
                if (ok) {
                    hooked++
                    ZLog.i(TAG_SCOPE, "hooked ${clazz.name}.$name (list=$isList) rt=${returnType.name}")
                }
            }
        }

        // 默认订阅 ID 方法：优先返回配置中 enabled 槽的 subId
        INT_METHOD_NAMES.forEach { name ->
            HookSupport.findMethods(clazz, name)
                .filter { it.parameterCount == 0 }
                .forEach { method ->
                    val ok = registrar.register(method) { chain ->
                        val original = chain.proceed()
                        val subId = defaultSubId()
                        if (subId != null) {
                            ZLog.d(TAG_SCOPE, "${clazz.name}.$name -> $subId")
                            subId
                        } else {
                            original
                        }
                    }
                    if (ok) {
                        hooked++
                        ZLog.i(TAG_SCOPE, "hooked ${clazz.name}.$name")
                    }
                }
        }

        return hooked
    }

    // ---------- 改写 SubscriptionInfo ----------

    private fun rewriteList(list: Any?, data: org.json.JSONObject) {
        if (list !is List<*>) return
        list.forEach { item -> rewriteOne(item, data) }
    }

    private fun rewriteOne(item: Any?, data: org.json.JSONObject) {
        if (item == null) return
        val slot = resolveSlotFor(item, data) ?: return
        try {
            var changed = false
            FIELDS_STRING.forEach { (fieldName, key) ->
                val value = slot.optString(key, "")
                if (value.isNotEmpty() && setStringFieldSmart(item, fieldName, value)) changed = true
            }
            FIELDS_INT.forEach { (fieldName, key) ->
                val v = when (key) {
                    "mcc" -> slot.optString("mcc", "").toIntOrNull() ?: slot.optInt("mcc", -1)
                    "mnc" -> slot.optString("mnc", "").toIntOrNull() ?: slot.optInt("mnc", -1)
                    else -> slot.optInt(key, -1)
                }
                if (v >= 0 && setIntFieldSmart(item, fieldName, v)) changed = true
            }
            ZLog.d(TAG_SCOPE, "SubscriptionInfo rewritten for slot=${slot.optInt("slotIndex", -1)} changed=$changed")
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "SubscriptionInfo rewrite failed", t)
        }
    }

    /** 字段名优先；找不到时按当前真实值 + 类型启发式定位（Oplus 混淆字段名兼容）。 */
    private fun setStringFieldSmart(target: Any, name: String, value: String): Boolean {
        val f = findField(target, name)
        if (f != null) {
            setStringField(target, name, value)
            return true
        }
        // 启发式：找当前值=真实物理值 的 String 字段（名字含 iso/carrier/name/mcc/mnc/number 的优先）
        val real = realStringValue(target, name) ?: return false
        val candidates = mutableListOf<Field>()
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            clazz.declaredFields.forEach { field ->
                if (Modifier.isStatic(field.modifiers)) return@forEach
                if (field.type != String::class.java && field.type != java.lang.CharSequence::class.java) return@forEach
                val cur = try { field.isAccessible = true; field.get(target)?.toString() } catch (t: Throwable) { null }
                if (cur == real) candidates.add(field)
            }
            clazz = clazz.superclass
        }
        if (candidates.isEmpty()) return false
        // 偏好名字包含关键字的字段
        val hint = name.removePrefix("m").lowercase()
        val best = candidates.firstOrNull { it.name.lowercase().contains(hint) } ?: candidates.first()
        return try {
            best.isAccessible = true
            if (best.type == String::class.java) best.set(target, value) else best.set(target, value)
            true
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "smart set string $name failed", t)
            false
        }
    }

    private fun setIntFieldSmart(target: Any, name: String, value: Int): Boolean {
        val f = findField(target, name)
        if (f != null) {
            setIntField(target, name, value)
            return true
        }
        val real = realIntValue(target, name) ?: return false
        val candidates = mutableListOf<Field>()
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            clazz.declaredFields.forEach { field ->
                if (Modifier.isStatic(field.modifiers)) return@forEach
                if (field.type != Int::class.javaPrimitiveType && field.type != Integer::class.java) return@forEach
                val cur = try { field.isAccessible = true; field.getInt(target) } catch (t: Throwable) { Int.MIN_VALUE }
                if (cur == real) candidates.add(field)
            }
            clazz = clazz.superclass
        }
        if (candidates.isEmpty()) return false
        val hint = name.removePrefix("m").lowercase()
        val best = candidates.firstOrNull { it.name.lowercase().contains(hint) } ?: candidates.first()
        return try {
            best.isAccessible = true
            best.setInt(target, value)
            true
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "smart set int $name failed", t)
            false
        }
    }

    /** 用保留的 getter 名读真实物理值，辅助字段定位。 */
    private fun realStringValue(target: Any, name: String): String? {
        val getter = when (name) {
            "mIccId" -> "getIccId"
            "mCarrierName" -> "getCarrierName"
            "mDisplayName" -> "getDisplayName"
            "mCountryIso", "mIso" -> "getCountryIso"
            "mNumber" -> "getNumber"
            else -> null
        } ?: return null
        return try {
            val m = target.javaClass.getMethod(getter)
            m.isAccessible = true
            m.invoke(target)?.toString()
        } catch (t: Throwable) {
            null
        }
    }

    private fun realIntValue(target: Any, name: String): Int? {
        val getter = when (name) {
            "mMcc" -> "getMccString"
            "mMnc" -> "getMncString"
            "mSimSlotIndex" -> "getSimSlotIndex"
            "mSubscriptionId" -> "getSubscriptionId"
            else -> null
        } ?: return null
        return try {
            val m = target.javaClass.getMethod(getter)
            m.isAccessible = true
            when (val r = m.invoke(target)) {
                is Int -> r
                is Number -> r.toInt()
                is String -> r.toIntOrNull()
                else -> null
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** 按 SubscriptionInfo 自身 subId/slotIndex 匹配配置槽；无法匹配时用第一个启用槽。 */
    private fun resolveSlotFor(item: Any, data: org.json.JSONObject): org.json.JSONObject? {
        val slots = data.optJSONArray("slots") ?: return null
        var subId = -1
        var slotIndex = -1
        try {
            subId = getIntField(item, "mSubscriptionId") ?: getterInt(item, "getSubscriptionId") ?: -1
            slotIndex = getIntField(item, "mSimSlotIndex") ?: getterInt(item, "getSimSlotIndex") ?: -1
        } catch (t: Throwable) {
        }
        for (i in 0 until slots.length()) {
            val s = slots.optJSONObject(i) ?: continue
            if (s.optInt("subId", -1) == subId || s.optInt("slotIndex", -1) == slotIndex) return s
        }
        for (i in 0 until slots.length()) {
            val s = slots.optJSONObject(i) ?: continue
            if (s.optBoolean("enabled", true)) return s
        }
        return slots.optJSONObject(0)
    }

    private fun getterInt(target: Any, name: String): Int? {
        return try {
            val m = target.javaClass.getMethod(name)
            m.isAccessible = true
            when (val r = m.invoke(target)) {
                is Int -> r
                is Number -> r.toInt()
                else -> null
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** 当前启用 SIM 配置；未启用返回 null。 */
    private fun currentSimData(): org.json.JSONObject? = try {
        simDataProvider()
    } catch (t: Throwable) {
        null
    }

    private fun defaultSubId(): Int? {
        val data = currentSimData() ?: return null
        val slots = data.optJSONArray("slots") ?: return null
        for (i in 0 until slots.length()) {
            val s = slots.optJSONObject(i) ?: continue
            if (s.optBoolean("enabled", true)) {
                val subId = s.optInt("subId", -1)
                if (subId >= 0) return subId
            }
        }
        return null
    }

    // ---------- 反射字段工具（final 也允许通过 setAccessible 改写） ----------

    private fun setStringField(target: Any, name: String, value: String) {
        if (value.isEmpty()) return
        val f = findField(target, name) ?: return
        try {
            f.isAccessible = true
            f.set(target, value)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "set field $name failed", t)
        }
    }

    private fun setIntField(target: Any, name: String, value: Int) {
        val f = findField(target, name) ?: return
        try {
            f.isAccessible = true
            f.setInt(target, value)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "set int field $name failed", t)
        }
    }

    private fun getIntField(target: Any, name: String): Int? {
        val f = findField(target, name) ?: return null
        return try {
            f.isAccessible = true
            f.getInt(target)
        } catch (t: Throwable) {
            null
        }
    }

    private fun findField(target: Any, name: String): Field? {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            try {
                val f = clazz.getDeclaredField(name)
                // 跳过 static 字段
                if (!Modifier.isStatic(f.modifiers)) return f
            } catch (_: NoSuchFieldException) {
            }
            clazz = clazz.superclass
        }
        return null
    }
}
