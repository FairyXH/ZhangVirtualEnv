package io.github.fairyxh.VirtualEnv.app.ui.glass

import kotlinx.coroutines.android.awaitFrame

/**
 * 等待下一帧（Android 实现，移植自 AndroidLiquidGlass catalog utils，Apache-2.0）。
 */
suspend fun awaitFrameCompat() {
    awaitFrame()
}
