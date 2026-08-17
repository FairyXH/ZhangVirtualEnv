package io.github.fairyxh.VirtualEnv.core.gnss

import kotlin.math.*

object SignalModel {
    fun cn0(elevationDeg: Double, distanceMeters: Double, model: SatelliteModel, timestampMs: Long): Float {
        val elevationGain = when {
            elevationDeg >= 60.0 -> 12.0
            elevationDeg <= 10.0 -> -8.0
            else -> (elevationDeg - 10.0) * 20.0 / 50.0 - 8.0
        }
        val distanceLoss = 3.0 * ln((distanceMeters / model.orbitRadiusMeters).coerceAtLeast(0.5)) / ln(2.0)
        // Deterministic, slowly changing noise: no per-refresh random SVID/count/geometry.
        val phase = timestampMs / 1000.0 / 19.0 + model.phaseDeg / 57.0
        val smoothNoise = 1.5 * sin(phase)
        return (31.0 + elevationGain - distanceLoss + smoothNoise).coerceIn(18.0, 52.0).toFloat()
    }
}