package io.github.fairyxh.VirtualEnv.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 输入框默认值生成：默认以当前时间命名（如「录像 2026-08-10 14:30」）。
 *
 * 需求：所有输入框都要有默认值，不要出现“请输入录像名称”等空提示；
 * 位置模拟、路线模拟的名称默认取高德选点地址，没有地址时使用日期。
 */
object DefaultNames {

    @Volatile
    private var lastPoiTitle: String? = null

    /** 记录最近一次高德选点/搜索的地址（位置、路线页共用）。 */
    fun rememberPoi(title: String) {
        if (title.isNotBlank()) lastPoiTitle = title
    }

    fun lastPoi(): String? = lastPoiTitle

    /** 以时间为默认名；有高德地址时优先用地址。 */
    fun locationOrRoute(prefix: String): String {
        val poi = lastPoi()
        return if (!poi.isNullOrBlank()) poi else timeName(prefix)
    }

    /** 纯时间默认名，如「录像 2026-08-10 14:30」。 */
    fun timeName(prefix: String): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return "$prefix ${fmt.format(Date())}"
    }
}
