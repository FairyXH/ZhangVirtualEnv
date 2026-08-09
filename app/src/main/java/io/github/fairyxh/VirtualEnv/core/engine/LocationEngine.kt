package io.github.fairyxh.VirtualEnv.core.engine

import android.location.Location
import io.github.fairyxh.VirtualEnv.core.model.LocationState

/**
 * 位置引擎接口。
 *
 * 所有位置生成逻辑（单点、路线、摇杆）必须实现该接口，
 * Hook 层只调用 [currentLocation] 获取快照，不感知具体实现。
 */
interface LocationEngine {
    /** 引擎名称，用于日志与调试。 */
    val name: String

    /** 当前是否启用虚拟位置输出。 */
    fun isEnabled(): Boolean

    /** 设置启用/禁用。 */
    fun setEnabled(enabled: Boolean)

    /** 输出当前虚拟位置；未启用或尚未设置坐标时返回 null（表示放行真实数据）。 */
    fun currentLocation(): Location?

    /** 输出当前虚拟位置状态（无 Location 对象依赖，便于 App 端展示）。 */
    fun currentState(): LocationState

    /** 设置单点位置。 */
    fun setPoint(latitude: Double, longitude: Double, speed: Float, bearing: Float)
}
