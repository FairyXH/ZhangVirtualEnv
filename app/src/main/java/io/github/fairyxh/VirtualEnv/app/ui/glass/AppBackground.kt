package io.github.fairyxh.VirtualEnv.app.ui.glass

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.Executors
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowInsetsCompat
import io.github.fairyxh.VirtualEnv.util.ZLog

/**
 * 系统栏 insets 全局缓存。
 *
 * AndroidComposeView 默认把窗口 insets 当作 contentPadding 应用到 Compose 根，
 * ColorOS 曲面屏的 left inset=56px 会让整页内容从 x=56 开始，左侧露出黑/白边。
 * GlassBackdropHost 在组合时消费窗口 insets（contentPadding 归零），并把
 * insets 保存到这里，内容层用它手动避让状态栏/导航栏。
 */
object AppInsets {
    var systemBars by mutableStateOf(WindowInsets(0, 0, 0, 0))
        private set
    var navigationBars by mutableStateOf(WindowInsets(0, 0, 0, 0))
        private set

    fun consume(insets: WindowInsetsCompat) {
        val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val nb = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        systemBars = WindowInsets(sb.left, sb.top, sb.right, sb.bottom)
        navigationBars = WindowInsets(nb.left, nb.top, nb.right, nb.bottom)
    }

    /**
     * 在指定 View 上消费窗口 insets：AndroidComposeView 默认把 insets 当作
     * contentPadding（ColorOS 曲面安全区 left=56px 会让整页从 x=56 开始），
     * 消费后 Compose 内容真正全屏；insets 由 [consume] 缓存供手动避让。
     * 调用时机必须在 View attach 之后（setContentView 后 requestApplyInsets）。
     */
    fun attachConsume(view: android.view.View) {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            consume(insets)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        view.requestApplyInsets()
    }
}

/**
 * App 背景状态：是否用桌面壁纸作为所有页面的背景画布。
 *
 * ColorOS 的 FLAG_SHOW_WALLPAPER 会在曲面屏左缘画 56px 黑色遮罩，无法显示
 * 完整壁纸；因此优先读取壁纸位图由 GlassBackdropHost 全屏绘制（无黑边、
 * 可加轻微磨砂）。ColorOS 读取壁纸位图需要 READ_MEDIA_IMAGES 权限，
 * 未授权时退回 FLAG_SHOW_WALLPAPER 兜底。
 */
object AppBackground {

    private const val PREFS = "app_background"
    private const val KEY_USE_WALLPAPER = "use_wallpaper"

    var useWallpaper by mutableStateOf(false)
        private set
    var wallpaperBitmap by mutableStateOf<ImageBitmap?>(null)
        private set

    /** 壁纸 Drawable（fastDrawable 优先）：避免 toBitmap 触发 ColorOS 权限检查。 */
    var wallpaperDrawable by mutableStateOf<android.graphics.drawable.Drawable?>(null)
        private set

    private val loader = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ZVE-WallpaperLoader").apply { isDaemon = true }
    }

    /** 仅同步读取开关；壁纸 Drawable/Bitmap 在后台线程加载。 */
    fun load(context: Context) {
        val app = context.applicationContext
        useWallpaper = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_USE_WALLPAPER, false)
        if (useWallpaper) loader.execute { refreshWallpaper(app) }
    }

    fun setUseWallpaper(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_USE_WALLPAPER, enabled)
            .apply()
        useWallpaper = enabled
        if (enabled) loader.execute { refreshWallpaper(app) }
    }

    /** 权限授予或开关开启后重新读取壁纸。 */
    @android.annotation.SuppressLint("MissingPermission")
    fun refreshWallpaper(context: Context) {
        val app = context.applicationContext
        // 先拿 Drawable：fastDrawable（API 31+ 系统快速壁纸）不触发
        // READ_EXTERNAL_STORAGE 权限检查；toBitmap() 在 ColorOS 上会被拒绝，
        // 因此由 GlassBackdropHost 直接用 drawDrawable 绘制。
        val drawable = try {
            val wm = WallpaperManager.getInstance(app)
            val d = if (Build.VERSION.SDK_INT >= 31) {
                try {
                    wm.fastDrawable
                } catch (e: Throwable) {
                    ZLog.w("ZVirtualEnv", "fastDrawable failed: $e")
                    null
                }
            } else {
                null
            } ?: wm.drawable
            ZLog.i("ZVirtualEnv", "wallpaper drawable=${d?.javaClass?.simpleName}")
            d
        } catch (e: Throwable) {
            ZLog.e("ZVirtualEnv", "wallpaper load failed: $e")
            null
        }
        wallpaperDrawable = drawable
        wallpaperBitmap = try {
            val bmp = drawable?.toBitmap()
            ZLog.i("ZVirtualEnv", "wallpaper bitmap=${bmp?.width}x${bmp?.height}")
            bmp?.asImageBitmap()
        } catch (e: Throwable) {
            // ColorOS: toBitmap 需要 READ_EXTERNAL_STORAGE（targetSdk 36 不可授予），
            // 仅作为优化路径，失败时退回 Drawable 直绘
            ZLog.w("ZVirtualEnv", "wallpaper toBitmap failed: $e")
            null
        }
    }
}
