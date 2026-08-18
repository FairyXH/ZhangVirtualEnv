package io.github.fairyxh.VirtualEnv.hook

import io.github.fairyxh.VirtualEnv.util.ZLog
import java.lang.reflect.Method

/**
 * Android 17 TelephonyRegistry published-state adapter (system_server).
 *
 * Android 17 Xiaomi completes the RIL request / serial / Radio HAL lifecycle before publishing
 * ServiceState and SignalStrength through TelephonyRegistry. Updating those published objects in
 * place keeps the registry cache and callbacks consistent without bypassing RIL cleanup.
 */
class TelephonyRegistryStateHookAdapter(
    private val simDataProvider: () -> org.json.JSONObject?,
    private val registrar: HookRegistrar,
) {
    companion object {
        private const val TAG_SCOPE = "Hook"
        private const val CLASS_NAME = "com.android.server.TelephonyRegistry"
        private const val IN_SERVICE = 0
    }

    fun install(classLoader: ClassLoader): Int {
        val registry = HookSupport.findClass(classLoader, CLASS_NAME) ?: return 0
        val hooked = hookSignalStrength(registry) + hookServiceState(registry)
        if (hooked > 0) ZLog.i(TAG_SCOPE, "TelephonyRegistry published-state hooks active count=$hooked")
        return hooked
    }

    private fun hookSignalStrength(registry: Class<*>): Int {
        var hooked = 0
        HookSupport.findMethods(registry, "notifySignalStrengthForPhoneId")
            .filter { it.parameterCount == 3 && it.parameterTypes[2].name == "android.telephony.SignalStrength" }
            .forEach { method ->
                val ok = registrar.register(method) { chain ->
                    try {
                        val published = chain.getArg(2)
                        val replacement = VirtualSignalFactory.build(simDataProvider())
                        if (published != null && replacement != null && copySignalStrength(replacement, published)) {
                            ZLog.d(TAG_SCOPE, "TelephonyRegistry.notifySignalStrengthForPhoneId -> virtual")
                        }
                    } catch (t: Throwable) {
                        ZLog.w(TAG_SCOPE, "TelephonyRegistry signal adaptation failed, fallback", t)
                    }
                    chain.proceed()
                    null
                }
                if (ok) {
                    hooked++
                    ZLog.i(TAG_SCOPE, "hooked $CLASS_NAME.${method.name}(${method.parameterCount})")
                }
            }
        return hooked
    }

    private fun hookServiceState(registry: Class<*>): Int {
        var hooked = 0
        HookSupport.findMethods(registry, "notifyServiceStateForPhoneId")
            .filter { it.parameterCount == 3 && it.parameterTypes[2].name == "android.telephony.ServiceState" }
            .forEach { method ->
                val ok = registrar.register(method) { chain ->
                    try {
                        val state = chain.getArg(2)
                        val sim = simDataProvider()
                        if (state != null && sim != null && applyVirtualServiceState(state, sim)) {
                            ZLog.d(TAG_SCOPE, "TelephonyRegistry.notifyServiceStateForPhoneId -> virtual in-service")
                        }
                    } catch (t: Throwable) {
                        ZLog.w(TAG_SCOPE, "TelephonyRegistry service-state adaptation failed, fallback", t)
                    }
                    chain.proceed()
                    null
                }
                if (ok) {
                    hooked++
                    ZLog.i(TAG_SCOPE, "hooked $CLASS_NAME.${method.name}(${method.parameterCount})")
                }
            }
        return hooked
    }

    private fun copySignalStrength(source: Any, target: Any): Boolean {
        var copied = false
        var type: Class<*>? = source.javaClass
        while (type != null) {
            for (field in type.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                val targetField = findField(target.javaClass, field.name) ?: continue
                if (!targetField.type.isAssignableFrom(field.type)) continue
                try {
                    field.isAccessible = true
                    targetField.isAccessible = true
                    targetField.set(target, field.get(source))
                    copied = true
                } catch (_: Throwable) {
                }
            }
            type = type.superclass
        }
        return copied
    }

    private fun applyVirtualServiceState(state: Any, sim: org.json.JSONObject): Boolean {
        var changed = false
        changed = invokeIntSetter(state, "setState", IN_SERVICE) || changed
        changed = invokeIntSetter(state, "setVoiceRegState", IN_SERVICE) || changed
        changed = invokeIntSetter(state, "setDataRegState", IN_SERVICE) || changed

        val dataType = sim.optInt("dataNetworkType", -1)
        if (dataType >= 0) changed = invokeIntSetter(state, "setDataNetworkType", dataType) || changed
        val voiceType = sim.optInt("voiceNetworkType", -1)
        if (voiceType >= 0) changed = invokeIntSetter(state, "setVoiceNetworkType", voiceType) || changed

        val mcc = sim.optString("mcc")
        val mnc = sim.optString("mnc")
        val numeric = if (mcc.isNotBlank() && mnc.isNotBlank()) mcc + mnc else ""
        val longName = sim.optString("networkOperatorName")
            .ifBlank { sim.optString("simOperatorName") }
            .ifBlank { sim.optString("operatorName") }
        if (longName.isNotBlank() || numeric.isNotBlank()) {
            changed = invokeOperatorSetter(state, longName, longName, numeric) || changed
        }
        return changed
    }

    private fun invokeIntSetter(target: Any, name: String, value: Int): Boolean {
        val method = findMethod(target.javaClass, name, Int::class.javaPrimitiveType) ?: return false
        return try {
            method.invoke(target, value)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun invokeOperatorSetter(target: Any, longName: String, shortName: String, numeric: String): Boolean {
        val method = findMethod(target.javaClass, "setOperatorName", String::class.java, String::class.java, String::class.java)
            ?: return false
        return try {
            method.invoke(target, longName, shortName, numeric)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun findMethod(type: Class<*>, name: String, vararg parameterTypes: Class<*>?): Method? {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, *parameterTypes).also { it.isAccessible = true }
            } catch (_: Throwable) {
                current = current.superclass
            }
        }
        return null
    }

    private fun findField(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name)
            } catch (_: Throwable) {
                current = current.superclass
            }
        }
        return null
    }
}