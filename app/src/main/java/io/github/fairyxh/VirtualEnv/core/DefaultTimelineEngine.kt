package io.github.fairyxh.VirtualEnv.core

import io.github.fairyxh.VirtualEnv.util.ZLog
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 时间轴引擎基础实现（Phase 1）。
 *
 * 仅维护状态机与时间游标，不包含具体数据源。
 * 后续 Phase 通过数据源（路线点、录制数据）驱动 [seek] 与位置插值。
 */
class DefaultTimelineEngine : TimelineEngine {

    companion object {
        private const val TAG_SCOPE = "Core"
    }

    private val stateRef = AtomicReference(TimelineEngine.State.IDLE)
    private val currentTimeRef = AtomicLong(0L)
    private val durationRef = AtomicLong(0L)

    override fun state(): TimelineEngine.State = stateRef.get()

    override fun currentTime(): Long = currentTimeRef.get()

    override fun duration(): Long = durationRef.get()

    override fun start() {
        if (durationRef.get() <= 0) {
            ZLog.w(TAG_SCOPE, "TimelineEngine.start ignored, no data loaded")
            return
        }
        stateRef.set(TimelineEngine.State.RUNNING)
        ZLog.i(TAG_SCOPE, "TimelineEngine started at ${currentTimeRef.get()}ms")
    }

    override fun pause() {
        if (stateRef.get() != TimelineEngine.State.RUNNING) return
        stateRef.set(TimelineEngine.State.PAUSED)
        ZLog.i(TAG_SCOPE, "TimelineEngine paused at ${currentTimeRef.get()}ms")
    }

    override fun seek(time: Long) {
        val clamped = time.coerceIn(0L, durationRef.get())
        currentTimeRef.set(clamped)
        ZLog.i(TAG_SCOPE, "TimelineEngine seek -> ${clamped}ms")
    }

    override fun stop() {
        currentTimeRef.set(0L)
        stateRef.set(TimelineEngine.State.STOPPED)
        ZLog.i(TAG_SCOPE, "TimelineEngine stopped")
    }

    /** 供后续 Phase 注入数据源总时长。 */
    fun setDuration(duration: Long) {
        durationRef.set(duration)
    }
}
