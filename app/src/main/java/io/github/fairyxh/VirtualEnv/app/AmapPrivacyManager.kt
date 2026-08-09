package io.github.fairyxh.VirtualEnv.app

import android.content.Context
import android.content.SharedPreferences
import com.amap.api.maps.MapsInitializer
import io.github.fairyxh.VirtualEnv.util.ZLog

/**
 * 高德地图隐私合规管理器。
 *
 * 依据《个人信息保护法》，高德 SDK 8.1.0 起必须在调用任何 SDK 接口前
 * 先调用 updatePrivacyShow / updatePrivacyAgree，否则 MapView 白屏、
 * OfflineMapManager 等初始化抛异常。
 *
 * 流程：设置页勾选同意 → 持久化 → 地图初始化前检查并调用合规接口。
 */
object AmapPrivacyManager {

    private const val TAG_SCOPE = "AmapPrivacy"
    private const val PREFS = "amap_config"
    private const val KEY_PRIVACY_AGREED = "privacy_agreed"

    /** 用户是否已同意隐私政策。 */
    fun isAgreed(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_PRIVACY_AGREED, false)
    }

    /** 设置页保存用户同意状态。 */
    fun setAgreed(context: Context, agreed: Boolean) {
        prefs(context).edit().putBoolean(KEY_PRIVACY_AGREED, agreed).apply()
        ZLog.i(TAG_SCOPE, "privacy agreed=$agreed")
    }

    /**
     * 地图初始化前调用隐私合规接口。
     *
     * 必须在任何高德 SDK 接口之前执行；失败时抛异常由调用方捕获
     * （MapView / OfflineMapManager / 搜索等初始化均需 try/catch）。
     */
    fun applyPrivacyIfAgreed(context: Context) {
        if (!isAgreed(context)) {
            ZLog.w(TAG_SCOPE, "privacy not agreed, SDK calls blocked")
            throw IllegalStateException("高德隐私政策未同意，请在设置中阅读并同意")
        }
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
        ZLog.i(TAG_SCOPE, "updatePrivacyShow/Agree called")
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
