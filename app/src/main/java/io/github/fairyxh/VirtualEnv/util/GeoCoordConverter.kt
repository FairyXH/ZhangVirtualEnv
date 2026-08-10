package io.github.fairyxh.VirtualEnv.util

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 高德/GCJ-02 与 WGS-84 坐标互转。
 *
 * 背景：高德地图（以及国内大部分地图 SDK）使用 GCJ-02（火星坐标），而 Android 系统
 * Location API / 虚拟定位 Hook 输出的是 WGS-84。若把地图上直接点选/搜索得到的
 * GCJ-02 坐标当作 WGS-84 注入系统，会出现约 100~600 米的偏移。
 *
 * 约定：
 * - 模块所有持久化数据（地点/路线/录像帧）与注入系统的坐标统一为 **WGS-84**。
 * - 仅在高德地图显示层使用 [wgs84ToGcj02] 把 WGS-84 坐标转换回 GCJ-02 绘制。
 * - 地图点击/POI 搜索/高德定位返回的是 GCJ-02，先经 [gcj02ToWgs84] 再进入业务层。
 */
object GeoCoordConverter {

    private const val PI = Math.PI
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    /** 中国境外（粗略边界外）不做偏移转换，直接原样返回。 */
    private fun outOfChina(lat: Double, lon: Double): Boolean {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    /** GCJ-02 相对 WGS-84 的偏移量（正向偏移，通常用于 WGS-84 → GCJ-02）。 */
    private fun offset(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return 0.0 to 0.0
        val dLat = transformLat(lon - 105.0, lat - 35.0)
        val dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val dLatReal = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        val dLonReal = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return dLatReal to dLonReal
    }

    /** WGS-84 → GCJ-02（火星坐标）。 */
    fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon
        val (dLat, dLon) = offset(lat, lon)
        return (lat + dLat) to (lon + dLon)
    }

    /** WGS-84 → GCJ-02，LatLng 便捷版。 */
    fun wgs84ToGcj02(latLng: com.amap.api.maps.model.LatLng): com.amap.api.maps.model.LatLng {
        val (lat, lon) = wgs84ToGcj02(latLng.latitude, latLng.longitude)
        return com.amap.api.maps.model.LatLng(lat, lon)
    }

    /** GCJ-02 → WGS-84（逆偏移，采用标准近似解算，误差 < 1 米）。 */
    fun gcj02ToWgs84(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon
        // 先在 GCJ 位置计算偏移，再反向减除（标准工程做法）
        val (dLat, dLon) = offset(lat, lon)
        return (lat - dLat) to (lon - dLon)
    }

    /** GCJ-02 → WGS-84，LatLng 便捷版（供地图点击/搜索/高德定位回调使用）。 */
    fun gcj02ToWgs84(latLng: com.amap.api.maps.model.LatLng): com.amap.api.maps.model.LatLng {
        val (lat, lon) = gcj02ToWgs84(latLng.latitude, latLng.longitude)
        return com.amap.api.maps.model.LatLng(lat, lon)
    }
}
