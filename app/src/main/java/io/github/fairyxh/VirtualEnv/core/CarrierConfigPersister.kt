package io.github.fairyxh.VirtualEnv.core

import android.os.IBinder
import android.os.PersistableBundle
import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject

/**
 * Nrfr 同款 CarrierConfig 持久化固化（system_server 内直接 Binder 调用）。
 *
 * 与 Nrfr 完全相同的接口：`ICarrierConfigLoader.overrideConfig(subId, bundle, true)`
 * - `sim_country_iso_override`：两位小写国家码（空串/长度非 2 不设置）
 * - `carrier_name_override_bool` + `carrier_name_string`：运营商名称覆盖
 * - 第三个参数 `true` = 持久化：写入 CarrierConfig 持久存储，重启设备甚至
 *   禁用 KernelSU/LSPosed 后依然生效（这正是 Nrfr「禁用框架后仍是日本」的原因）。
 *
 * 我们运行在 system_server（LSPosed 模块），可直接通过
 * `TelephonyFrameworkInitializer` 拿到 `carrierConfig` Binder，不需要 Shizuku。
 * 全部用反射，fail-open：任何一步失败只记日志，不影响主流程。
 */
object CarrierConfigPersister {

    private const val TAG_SCOPE = "CarrierCfg"

    private const val KEY_SIM_COUNTRY_ISO_OVERRIDE = "sim_country_iso_override"
    private const val KEY_CARRIER_NAME_OVERRIDE_BOOL = "carrier_name_override_bool"
    private const val KEY_CARRIER_NAME_STRING = "carrier_name_string"

    /** 本模块固化过的 subId 集合（clear 时还原用）。 */
    private val overriddenSubIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /** 对单个 SIM 卡槽固化（覆盖 + 持久化）。 */
    fun applySlot(slot: JSONObject) {
        try {
            val subId = slot.optInt("subId", -1)
            if (subId < 0) return
            val countryIso = slot.optString("simCountryIso")
                .ifBlank { slot.optString("countryIso") }
                .lowercase()
            val carrierName = slot.optString("simOperatorName")
                .ifBlank { slot.optString("operatorName") }
            val bundle = PersistableBundle()
            if (countryIso.length == 2) {
                bundle.putString(KEY_SIM_COUNTRY_ISO_OVERRIDE, countryIso)
            }
            if (carrierName.isNotEmpty()) {
                bundle.putBoolean(KEY_CARRIER_NAME_OVERRIDE_BOOL, true)
                bundle.putString(KEY_CARRIER_NAME_STRING, carrierName)
            }
            if (bundle.size() == 0) return
            if (overrideConfig(subId, bundle)) {
                overriddenSubIds.add(subId)
                ZLog.i(TAG_SCOPE, "carrier config overridden subId=$subId iso=$countryIso carrier=$carrierName")
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "applySlot failed", t)
        }
    }

    /** 还原本模块固化过的全部卡槽（null bundle = 删除 override）。 */
    fun resetAll() {
        for (subId in overriddenSubIds) {
            try {
                if (overrideConfig(subId, null)) {
                    ZLog.i(TAG_SCOPE, "carrier config reset subId=$subId")
                }
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "reset subId=$subId failed", t)
            }
        }
        overriddenSubIds.clear()
    }

    /**
     * 调用 ICarrierConfigLoader.overrideConfig(subId, bundle, true)。
     *
     * @param bundle null = 清除该 subId 的 override（reset）。
     */
    private fun overrideConfig(subId: Int, bundle: PersistableBundle?): Boolean {
        return try {
            // 优先 TelephonyFrameworkInitializer（Nrfr 同款入口），失败回退 ServiceManager。
            // 注意：Oplus ROM 上 TelephonyServiceManager.carrierConfigServiceRegisterer
            // 可能被移除/混淆导致 getMethod 404，因此 ServiceManager 兜底最稳。
            val binder: IBinder = try {
                // 优先 TelephonyFrameworkInitializer（Nrfr 同款入口），
                // Oplus ROM 可能移除 carrierConfigServiceRegisterer，失败回退 ServiceManager。
                val tsm = Class.forName("android.telephony.TelephonyFrameworkInitializer")
                    .getMethod("getTelephonyServiceManager").invoke(null)
                val registerer = tsm.javaClass
                    .getMethod("carrierConfigServiceRegisterer").invoke(tsm)
                registerer.javaClass.getMethod("get").invoke(registerer) as IBinder
            } catch (t: Throwable) {
                ZLog.w(TAG_SCOPE, "TelephonyServiceManager registerer unavailable, fallback ServiceManager", t)
                Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, "carrier_config") as IBinder
            }
            val stub = Class.forName("com.android.internal.telephony.ICarrierConfigLoader\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
            val method = Class.forName("com.android.internal.telephony.ICarrierConfigLoader")
                .getMethod(
                    "overrideConfig",
                    Int::class.javaPrimitiveType,
                    PersistableBundle::class.java,
                    Boolean::class.javaPrimitiveType
                )
            method.invoke(stub, subId, bundle, true)
            true
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "ICarrierConfigLoader.overrideConfig failed subId=$subId", t)
            false
        }
    }
}
