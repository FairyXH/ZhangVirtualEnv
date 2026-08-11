package io.github.fairyxh.VirtualEnv.app.ui.glass

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Liquid Glass 主题：延续项目现有 Apple 风格调色板（values/values-night），
 * 并按 backdrop 库的玻璃语义拆分为容器/高光/阴影/强调色。
 */
@Immutable
data class GlassColors(
    val bgPrimary: Color,
    val bgSecondary: Color,
    val bgTertiary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val separator: Color,
    val accent: Color,
    val accentPress: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    // 玻璃层颜色（供 onDrawSurface / 轨道 / 覆盖层使用）
    val glassFill: Color,
    val glassHighlight: Color,
    val glassBorder: Color,
    val glassShadow: Color,
    val tabIconNormal: Color,
    val tabIconActive: Color,
    val overlayScrim: Color
)

private val LightGlassColors = GlassColors(
    bgPrimary = Color(0xFFF2F2F7),
    bgSecondary = Color(0xFFFFFFFF),
    bgTertiary = Color(0xFFE5E5EA),
    textPrimary = Color(0xFF1D1D1F),
    textSecondary = Color(0xFF86868B),
    textTertiary = Color(0xFFAEAEB2),
    separator = Color(0x1C000000),
    accent = Color(0xFF0071E3),
    accentPress = Color(0xFF0A5DC2),
    success = Color(0xFF34C759),
    warning = Color(0xFFFF9500),
    danger = Color(0xFFFF3B30),
    glassFill = Color(0xCCF2F2F7),
    glassHighlight = Color(0x66FFFFFF),
    glassBorder = Color(0x40FFFFFF),
    glassShadow = Color(0x14000000),
    tabIconNormal = Color(0xFF8E8E93),
    tabIconActive = Color(0xFF0071E3),
    overlayScrim = Color(0x59000000)
)

private val DarkGlassColors = GlassColors(
    bgPrimary = Color(0xFF000000),
    bgSecondary = Color(0xFF1C1C1E),
    bgTertiary = Color(0xFF2C2C2E),
    textPrimary = Color(0xFFF5F5F7),
    textSecondary = Color(0xFF98989D),
    textTertiary = Color(0xFF636366),
    separator = Color(0x29FFFFFF),
    accent = Color(0xFF0A84FF),
    accentPress = Color(0xFF409CFF),
    success = Color(0xFF30D158),
    warning = Color(0xFFFF9F0A),
    danger = Color(0xFFFF453A),
    glassFill = Color(0xCC1C1C1E),
    glassHighlight = Color(0x2EFFFFFF),
    glassBorder = Color(0x33FFFFFF),
    glassShadow = Color(0x40000000),
    tabIconNormal = Color(0xFF7C7C80),
    tabIconActive = Color(0xFF0A84FF),
    overlayScrim = Color(0x80000000)
)

val LocalGlassColors = staticCompositionLocalOf { LightGlassColors }

@Composable
fun glassColors(): GlassColors {
    // 跟随系统主题：浅色模式用浅色玻璃配色，深色模式用深色配色。
    // （此前黑底阶段强制深色，导致 App 无论系统主题都像黑暗模式）
    return if (isSystemInDarkTheme()) DarkGlassColors else LightGlassColors
}
