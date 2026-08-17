package io.github.fairyxh.VirtualEnv.core.gnss

import android.location.GnssStatus

/** Compact, legal-SVID catalog shared by all GNSS calculations. */
object SatelliteCatalog {
    private data class Family(val constellation: Int, val prefix: String, val freq: Float, val radius: Double, val period: Double)

    private val families = listOf(
        Family(GnssStatus.CONSTELLATION_GPS, "GPS", 1_575_420_000f, 26_560_000.0, 43_080.0),
        Family(GnssStatus.CONSTELLATION_BEIDOU, "BDS", 1_561_098_000f, 27_906_000.0, 50_400.0),
        Family(GnssStatus.CONSTELLATION_GALILEO, "GAL", 1_575_420_000f, 29_600_000.0, 50_700.0),
        Family(GnssStatus.CONSTELLATION_GLONASS, "GLO", 1_602_000_000f, 25_510_000.0, 40_500.0),
        Family(GnssStatus.CONSTELLATION_QZSS, "QZS", 1_575_420_000f, 42_164_000.0, 86_164.0),
    )

    val default: List<SatelliteModel> = families.flatMapIndexed { familyIndex, family ->
        (0 until 5).map { index ->
            SatelliteModel(
                satelliteId = "${family.prefix}-${index + 1}",
                constellation = family.constellation,
                svid = if (family.constellation == GnssStatus.CONSTELLATION_GLONASS) index + 1 else index + 1,
                signalType = when (family.constellation) {
                    GnssStatus.CONSTELLATION_BEIDOU -> "B1I"
                    GnssStatus.CONSTELLATION_GLONASS -> "G1"
                    GnssStatus.CONSTELLATION_GALILEO -> "E1"
                    else -> "L1"
                },
                carrierFrequencyHz = family.freq,
                orbitRadiusMeters = family.radius,
                inclinationDeg = 54.0 + familyIndex * 2.5,
                rightAscensionDeg = (familyIndex * 71 + index * 37) % 360.0,
                phaseDeg = (familyIndex * 43 + index * 67) % 360.0,
                periodSeconds = family.period,
            )
        }
    }
}