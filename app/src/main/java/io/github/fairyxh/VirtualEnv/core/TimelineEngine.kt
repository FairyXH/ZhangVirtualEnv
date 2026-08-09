package io.github.fairyxh.VirtualEnv.core

/**
 * 时间轴/播放引擎接口。
 *
 * 负责路线播放、时间同步、数据回放。
 * Phase 1 提供接口与基础状态机；路线插值与回放由后续 Phase 实现。
 */
interface TimelineEngine {

    enum class State {
        IDLE,
        RUNNING,
        PAUSED,
        STOPPED
    }

    /** 当前播放状态。 */
    fun state(): State

    /** 当前播放时间（毫秒）。 */
    fun currentTime(): Long

    /** 总时长（毫秒），未加载数据时为 0。 */
    fun duration(): Long

    /** 开始播放。 */
    fun start()

    /** 暂停播放。 */
    fun pause()

    /** 跳转到指定时间（毫秒）。 */
    fun seek(time: Long)

    /** 停止播放并复位。 */
    fun stop()
}
