package io.github.fairyxh.VirtualEnv.core.gnss

import kotlin.math.*

object OrbitCalculator {
    private const val DEG = Math.PI / 180.0

    /** Circular Kepler approximation. Phase is continuous because time is the only moving input. */
    fun position(model: SatelliteModel, timestampMs: Long): Ecef {
        val phase = model.phaseDeg * DEG + (timestampMs / 1000.0 % model.periodSeconds) * (2.0 * Math.PI / model.periodSeconds)
        val inclination = model.inclinationDeg * DEG
        val asc = model.rightAscensionDeg * DEG
        val xOrb = model.orbitRadiusMeters * cos(phase)
        val yOrb = model.orbitRadiusMeters * sin(phase) * cos(inclination)
        val z = model.orbitRadiusMeters * sin(phase) * sin(inclination)
        return Ecef(
            xOrb * cos(asc) - yOrb * sin(asc),
            xOrb * sin(asc) + yOrb * cos(asc),
            z,
        )
    }
}