package io.github.fairyxh.VirtualEnv.app.ui.glass

import android.app.WallpaperManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/**
 * App 背景状态：是否用桌面壁纸作为所有页面的背景画布。
 * Compose state 全局可见，GlassBackdropHost 读取后自动重组。
 */
object AppBackground {

    private const val PREFS = "app_background"
    private const val KEY_USE_WALLPAPER = "use_wallpaper"

    var useWallpaper by mutableStateOf(false)
        private set
    var wallpaperBitmap by mutableStateOf<ImageBitmap?>(null)
        private set

    fun load(context: Context) {
        val app = context.applicationContext
        useWallpaper = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_USE_WALLPAPER, false)
        if (useWallpaper) refreshWallpaper(app)
    }

    fun setUseWallpaper(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_USE_WALLPAPER, enabled)
            .apply()
        useWallpaper = enabled
        if (enabled) refreshWallpaper(app)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun refreshWallpaper(context: Context) {
        // READ_WALLPAPER_INTERNAL 是系统级权限：普通应用读取壁纸无需申请，lint 误报
        wallpaperBitmap = try {
            WallpaperManager.getInstance(context).drawable?.toBitmap()?.asImageBitmap()
        } catch (_: Throwable) {
            null
        }
    }
}
