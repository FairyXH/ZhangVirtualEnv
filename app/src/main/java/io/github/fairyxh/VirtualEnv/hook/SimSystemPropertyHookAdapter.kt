package io.github.fairyxh.VirtualEnv.hook

import io.github.fairyxh.VirtualEnv.core.EnvStateCache
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Oplus / Android 15 SIM 身份系统属性全局虚拟化 Hook Adapter（com.android.phone 进程）。
 *
 * 逆向结论（JADX + 真机 getprop 验证）：Oplus 15 上
 * `TelephonyManager.getSimOperatorName() / getSimCountryIso() / getSimOperator() /
 * getNetworkOperator() / getNetworkOperatorName()` **全部直接读系统属性**
 * （gsm.sim.operator.alpha / gsm.sim.operator.numeric / gsm.sim.operator.iso-country /
 * gsm.operator.alpha / gsm.operator.numeric / gsm.operator.iso-country，按 phoneId 逗号分隔），
 * 不走 ITelephony / IPhoneSubInfo Binder，因此 Binder 侧 Hook 无法覆盖这些字段。
 *
 * 所有属性写入最终都经过 `TelephonyProperties` 的
 * 6 个 `List<String>` setter（TelephonyManager.setSimOperatorNameForPhone 等内部调用）。
 * 在 com.android.phone 进程拦截这些 setter，把配置槽位的值替换为虚拟值后写回属性；
 * 系统属性是进程级全局，任意 App 读到的都是虚拟值，无需 Hook 第三方 App（符合作用域硬约束）。
 *
 * 类路径（JADX Android 16 实测）：Android 15 为 `android.internal.telephony.sysprop.TelephonyProperties`；
 * Android 16 迁移到 `android.sysprop.TelephonyProperties`（framework_classes2.dex 确认，
 * setter 名与 List<String> 签名完全一致）。两个候选按顺序查找，找不到即 fail-open。
 *
 * 另启动 1s 轮询：SIM 配置变化（重新保存/启用/禁用）后，即使电话栈没有新的属性写入，
 * 也主动按当前配置重写属性；禁用时放行，属性保持电话栈写入的真实值。
 */
class SimSystemPropertyHookAdapter(
    private val cache: EnvStateCache,
    private val registrar: HookRegistrar,
) {

    companion object {
        private const val TAG_SCOPE = "Hook"

        /** Android 15 / Android 16 的 TelephonyProperties 候选类（先命中先安装）。 */
        private val TELEPHONY_PROPERTIES_CLASSES = listOf(
            "android.sysprop.TelephonyProperties",
            "android.internal.telephony.sysprop.TelephonyProperties",
        )

        /** sysprop setter 方法名 → 系统属性 key。 */
        private val SETTER_KEYS = mapOf(
            "icc_operator_numeric" to "gsm.sim.operator.numeric",
            "icc_operator_alpha" to "gsm.sim.operator.alpha",
            "icc_operator_iso_country" to "gsm.sim.operator.iso-country",
            "operator_numeric" to "gsm.operator.numeric",
            "operator_alpha" to "gsm.operator.alpha",
            "operator_iso_country" to "gsm.operator.iso-country",
        )
    }

    private val pollerStarted = AtomicBoolean(false)

    fun install(classLoader: ClassLoader): Int {
        var clazz: Class<*>? = null
        var usedClassName = ""
        for (candidate in TELEPHONY_PROPERTIES_CLASSES) {
            val found = HookSupport.findClass(classLoader, candidate)
            if (found != null) {
                clazz = found
                usedClassName = candidate
                break
            }
            ZLog.i(TAG_SCOPE, "sim property class candidate not found: $candidate")
        }
        val target = clazz ?: return 0
        ZLog.i(TAG_SCOPE, "sim property class resolved: $usedClassName [Android16-compatible candidates=${TELEPHONY_PROPERTIES_CLASSES}]")
        var hooked = 0
        SETTER_KEYS.forEach { (name, key) ->
            HookSupport.findMethods(target, name)
                .filter { it.parameterCount == 1 && it.parameterTypes[0] == List::class.java }
                .forEach { method ->
                    val ok = registrar.register(method) { chain ->
                        try {
                            val data = currentSimData()
                            if (data == null) {
                                chain.proceed()
                            } else {
                                val args = chain.javaClass.getMethod("getArgs").invoke(chain) as? List<*>
                                val original = args?.getOrNull(0) as? List<*>
                                val virtualized = virtualizeList(key, original, data)
                                if (virtualized != null) {
                                    SysProps.set(key, formatList(virtualized))
                                    null // 已用虚拟值写回，不再执行原 setter
                                } else {
                                    chain.proceed()
                                }
                            }
                        } catch (t: Throwable) {
                            ZLog.w(TAG_SCOPE, "sim property setter $name hook failed, fallback", t)
                            chain.proceed()
                        }
                    }
                    if (ok) {
                        hooked++
                        ZLog.i(TAG_SCOPE, "hooked $usedClassName.$name -> $key")
                    }
                }
        }
        if (hooked > 0) {
            startPoller()
            ZLog.i(TAG_SCOPE, "sim system-property hooks active on $usedClassName hooked=$hooked")
        }
        return hooked
    }

    // ---------- 属性写入拦截 ----------

    /** 返回虚拟化后的列表；无需改写时返回 null。 */
    private fun virtualizeList(key: String, original: List<*>?, data: JSONObject): List<String>? {
        val slots = data.optJSONArray("slots") ?: return null
        if (original == null || slots.length() == 0) return null
        var changed = false
        val out = ArrayList<String>(original.size)
        for (i in original.indices) {
            val real = original[i]?.toString() ?: ""
            val slot = findSlotForIndex(slots, i)
            val virtual = slot?.let { virtualValue(key, it) } ?: ""
            if (virtual.isNotEmpty() && virtual != real) {
                out.add(virtual)
                changed = true
            } else {
                out.add(real)
            }
        }
        return if (changed) out else null
    }

    /** 按 phoneId（列表下标）匹配配置槽；单槽配置且无 slotIndex 时按 0 处理。 */
    private fun findSlotForIndex(slots: org.json.JSONArray, index: Int): JSONObject? {
        for (i in 0 until slots.length()) {
            val s = slots.optJSONObject(i) ?: continue
            if (s.optInt("slotIndex", -1) == index) return s
        }
        if (index == 0) {
            var enabledCount = 0
            var first: JSONObject? = null
            for (i in 0 until slots.length()) {
                val s = slots.optJSONObject(i) ?: continue
                if (s.optBoolean("enabled", true)) {
                    enabledCount++
                    if (first == null) first = s
                }
            }
            if (enabledCount == 1 && first?.has("slotIndex") == false) return first
        }
        return null
    }

    private fun virtualValue(key: String, slot: JSONObject): String {
        val mcc = slot.optString("mcc", "")
        val mnc = slot.optString("mnc", "")
        val numeric = if (mcc.isNotBlank() && mnc.isNotBlank()) mcc + mnc else ""
        return when (key) {
            "gsm.sim.operator.numeric", "gsm.operator.numeric" -> numeric
            "gsm.sim.operator.alpha" ->
                slot.optString("simOperatorName").ifBlank { slot.optString("operatorName") }
            "gsm.operator.alpha" ->
                slot.optString("networkOperatorName")
                    .ifBlank { slot.optString("simOperatorName") }
                    .ifBlank { slot.optString("operatorName") }
            "gsm.sim.operator.iso-country" ->
                slot.optString("simCountryIso").ifBlank { slot.optString("countryIso") }
            "gsm.operator.iso-country" ->
                slot.optString("networkCountryIso").ifBlank { slot.optString("countryIso") }
            else -> ""
        }
    }

    // ---------- 配置变化轮询 ----------

    private fun startPoller() {
        if (!pollerStarted.compareAndSet(false, true)) return
        Thread {
            while (true) {
                try {
                    // 每 1s 按当前配置修正属性：电话栈（SIM 加载/网络注册）可能把属性重写回真实值，
                    // 因此不能只在配置指纹变化时应用；applyCurrent 内部无差异时不会写。
                    currentSimData()?.let { applyCurrent(it) }
                } catch (t: Throwable) {
                    ZLog.w(TAG_SCOPE, "sim property poll failed", t)
                }
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.apply {
            isDaemon = true
            name = "ZVE-SimProp"
            start()
        }
    }

    /** 配置变化后主动重写 6 个属性（保留未配置槽位的真实值）。 */
    private fun applyCurrent(data: JSONObject) {
        SETTER_KEYS.values.forEach { key ->
            try {
                val current = SysProps.get(key)
                val virtualized = virtualizeList(key, parseList(current), data) ?: return@forEach
                SysProps.set(key, formatList(virtualized))
                ZLog.d(TAG_SCOPE, "sim prop applied $key=${SysProps.get(key)}")
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "sim prop apply $key failed", t)
            }
        }
    }

    private fun currentSimData(): JSONObject? = try {
        cache.currentSim()
    } catch (t: Throwable) {
        null
    }

    // ---------- CSV 工具（与 sysprop formatList/tryParseList 语义一致） ----------

    private fun parseList(str: String): List<String> {
        if (str.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var i = 0
        while (i < str.length) {
            val sb = StringBuilder()
            while (i < str.length && str[i] != ',') {
                if (str[i] == '\\') i++
                if (i == str.length) break
                sb.append(str[i])
                i++
            }
            out.add(sb.toString())
            if (i == str.length) break
            i++
        }
        return out
    }

    private fun formatList(list: List<String>): String = list.joinToString(",") { escape(it) }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace(",", "\\,")

    /** android.os.SystemProperties 为隐藏 API，统一反射访问（fail-open）。 */
    private object SysProps {
        private val cls: Class<*>? = try {
            Class.forName("android.os.SystemProperties")
        } catch (t: Throwable) {
            null
        }
        private val getMethod: java.lang.reflect.Method? = try {
            cls?.getMethod("get", String::class.java)
        } catch (t: Throwable) {
            null
        }
        private val setMethod: java.lang.reflect.Method? = try {
            cls?.getMethod("set", String::class.java, String::class.java)
        } catch (t: Throwable) {
            null
        }

        fun get(key: String): String = try {
            (getMethod?.invoke(null, key) as? String) ?: ""
        } catch (t: Throwable) {
            ""
        }

        fun set(key: String, value: String) {
            try {
                setMethod?.invoke(null, key, value)
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "SystemProperties.set $key failed", t)
            }
        }
    }
}
