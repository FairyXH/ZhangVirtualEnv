package io.github.fairyxh.VirtualEnv.core.gnss

import android.location.GnssStatus
import android.location.Location
import kotlin.math.atan2
import kotlin.math.sqrt

/** Derives one coherent GNSS snapshot from the current virtual Location and timestamp. */
class GNSSSimulationEngine(
    private val catalog: List<SatelliteModel> = SatelliteCatalog.default,
) {
    fun snapshot(location: Location, timestampMs: Long = System.currentTimeMillis()): List<SatelliteState> {
        val observer = Lla(location.latitude, location.longitude, location.altitude)
        val observerEcef = CoordinateTransform.llaToEcef(observer)
        return catalog.map { model ->
            val sat = OrbitCalculator.position(model, timestampMs)
            val delta = Ecef(sat.x - observerEcef.x, sat.y - observerEcef.y, sat.z - observerEcef.z)
            val enu = CoordinateTransform.ecefToEnu(observer, delta)
            val distance = sqrt(enu.east * enu.east + enu.north * enu.north + enu.up * enu.up)
            val horizontal = sqrt(enu.east * enu.east + enu.north * enu.north)
            val elevation = Math.toDegrees(atan2(enu.up, horizontal))
            val azimuth = (Math.toDegrees(atan2(enu.east, enu.north)) + 360.0) % 360.0
            val visible = elevation >= 5.0
            val cn0 = SignalModel.cn0(elevation, distance, model, timestampMs)
            SatelliteState(
                model = model,
                azimuthDeg = azimuth.toFloat(),
                elevationDeg = elevation.toFloat(),
                distanceMeters = distance,
                visible = visible,
                cn0DbHz = cn0,
                hasEphemerisData = visible,
                hasAlmanacData = true,
                usedInFix = visible && elevation >= 15.0 && cn0 >= 25f,
            )
        }
    }

    fun toJson(location: Location, timestampMs: Long = System.currentTimeMillis()): org.json.JSONObject {
        val satellites = snapshot(location, timestampMs)
        return org.json.JSONObject().apply {
            put("timestampMs", timestampMs)
            put("satellites", org.json.JSONArray().apply {
                satellites.forEach { state ->
                    put(org.json.JSONObject().apply {
                        put("svid", state.model.svid)
                        put("constellationType", state.model.constellation)
                        put("signalType", state.model.signalType)
                        put("carrierFrequencyHz", state.model.carrierFrequencyHz)
                        put("azimuth", state.azimuthDeg)
                        put("elevation", state.elevationDeg)
                        put("distanceMeters", state.distanceMeters)
                        put("visible", state.visible)
                        put("cn0DbHz", state.cn0DbHz)
                        put("hasEphemerisData", state.hasEphemerisData)
                        put("hasAlmanacData", state.hasAlmanacData)
                        put("usedInFix", state.usedInFix)
                    })
                }
            })
            put("satelliteCount", satellites.count { it.visible })
            put("usedInFix", satellites.count { it.usedInFix })
        }
    }

    companion object {
        fun fromConstellation(name: String): Int = when (name.uppercase()) {
            "GPS" -> GnssStatus.CONSTELLATION_GPS
            "BEIDOU", "BDS" -> GnssStatus.CONSTELLATION_BEIDOU
            "GALILEO", "GAL" -> GnssStatus.CONSTELLATION_GALILEO
            "GLONASS", "GLO" -> GnssStatus.CONSTELLATION_GLONASS
            "QZSS", "QZS" -> GnssStatus.CONSTELLATION_QZSS
            else -> GnssStatus.CONSTELLATION_UNKNOWN
        }
    }
}