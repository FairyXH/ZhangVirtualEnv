package io.github.fairyxh.VirtualEnv.app.location

import android.content.Context
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.ServiceSettings
import com.amap.api.services.geocoder.GeocodeResult
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeQuery
import com.amap.api.services.geocoder.RegeocodeResult
import io.github.fairyxh.VirtualEnv.app.AmapPrivacyManager
import io.github.fairyxh.VirtualEnv.util.ZLog

/** 高德逆地理编码，用于把采集坐标转换为可读名称。 */
object AmapAddressHelper {
    private const val TAG_SCOPE = "AmapAddress"
    private const val PREFS = "amap_config"
    private const val KEY_AMAP_KEY = "amap_key"

    fun reverseGeocode(
        context: Context,
        latitude: Double,
        longitude: Double,
        onResult: (String?) -> Unit,
    ) {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0 ||
            (latitude == 0.0 && longitude == 0.0)
        ) {
            onResult(null)
            return
        }
        val app = context.applicationContext
        try {
            AmapPrivacyManager.applyPrivacyIfAgreed(app)
            val key = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_AMAP_KEY, "").orEmpty()
            if (key.isBlank()) {
                onResult(null)
                return
            }
            ServiceSettings.getInstance().setApiKey(key)
            val search = GeocodeSearch(app)
            search.setOnGeocodeSearchListener(object : GeocodeSearch.OnGeocodeSearchListener {
                override fun onRegeocodeSearched(result: RegeocodeResult?, code: Int) {
                    val address = if (code == 1000) {
                        result?.regeocodeAddress?.formatAddress?.trim()?.takeIf(String::isNotEmpty)
                    } else {
                        null
                    }
                    if (address == null) ZLog.w(TAG_SCOPE, "reverse geocode failed code=$code")
                    onResult(address)
                }

                override fun onGeocodeSearched(result: GeocodeResult?, code: Int) = Unit
            })
            val query = RegeocodeQuery(
                LatLonPoint(latitude, longitude),
                200f,
                GeocodeSearch.GPS,
            ).apply { extensions = GeocodeSearch.EXTENSIONS_BASE }
            search.getFromLocationAsyn(query)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "reverse geocode start failed", t)
            onResult(null)
        }
    }
}
