package io.github.fairyxh.VirtualEnv.core.gnss

import kotlin.math.*

data class Ecef(val x: Double, val y: Double, val z: Double)
data class Lla(val latitudeDeg: Double, val longitudeDeg: Double, val altitudeMeters: Double)
data class Enu(val east: Double, val north: Double, val up: Double)

object CoordinateTransform {
    private const val A = 6_378_137.0
    private const val E2 = 6.69437999014e-3
    private const val DEG = Math.PI / 180.0

    fun llaToEcef(lla: Lla): Ecef {
        val lat = lla.latitudeDeg * DEG
        val lon = lla.longitudeDeg * DEG
        val sinLat = sin(lat)
        val n = A / sqrt(1.0 - E2 * sinLat * sinLat)
        return Ecef(
            (n + lla.altitudeMeters) * cos(lat) * cos(lon),
            (n + lla.altitudeMeters) * cos(lat) * sin(lon),
            (n * (1.0 - E2) + lla.altitudeMeters) * sinLat,
        )
    }

    fun ecefToEnu(observer: Lla, delta: Ecef): Enu {
        val lat = observer.latitudeDeg * DEG
        val lon = observer.longitudeDeg * DEG
        return Enu(
            -sin(lon) * delta.x + cos(lon) * delta.y,
            -sin(lat) * cos(lon) * delta.x - sin(lat) * sin(lon) * delta.y + cos(lat) * delta.z,
            cos(lat) * cos(lon) * delta.x + cos(lat) * sin(lon) * delta.y + sin(lat) * delta.z,
        )
    }
}