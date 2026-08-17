package io.github.fairyxh.VirtualEnv.core.gnss

/** Static orbital description. Values are deterministic test-model parameters, not live ephemeris. */
data class SatelliteModel(
    val satelliteId: String,
    val constellation: Int,
    val svid: Int,
    val signalType: String,
    val carrierFrequencyHz: Float,
    val orbitRadiusMeters: Double,
    val inclinationDeg: Double,
    val rightAscensionDeg: Double,
    val phaseDeg: Double,
    val periodSeconds: Double,
)

data class SatelliteState(
    val model: SatelliteModel,
    val azimuthDeg: Float,
    val elevationDeg: Float,
    val distanceMeters: Double,
    val visible: Boolean,
    val cn0DbHz: Float,
    val hasEphemerisData: Boolean,
    val hasAlmanacData: Boolean,
    val usedInFix: Boolean,
)