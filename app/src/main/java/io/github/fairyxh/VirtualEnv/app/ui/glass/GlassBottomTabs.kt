package io.github.fairyxh.VirtualEnv.app.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/**
 * Liquid Glass 底栏（移植自 AndroidLiquidGlass LiquidBottomTabs，Apache-2.0）。
 *
 * 由三层玻璃构成：
 * - 外层胶囊：Backdrop blur + 透镜折射 + Fresnel 边缘高光 + 内部柔和阴影（常驻）
 * - 中间选中态胶囊：独立浮起的液态玻璃球，随滑块位置移动，带顶部镜面高光弧、
 *   边缘描边、蓝色内发光与内阴影；按压/选中时折射增强、轻微放大浮起
 * - 内层图标层：tint 强调色 + 选中态蓝色外发光，拖拽时整体跟随
 *
 * 页面内容的实时模糊由 LiquidGlassBarBlur（RenderNode + AGSL）提供，
 * 本组件只负责玻璃材质本身（表面/高光/厚度/动态光照）。
 */
@Composable
fun GlassBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val isLightTheme = !isSystemInDarkTheme()
    val colors = glassColors()
    val accentColor =
        if (isLightTheme) Color(0xFF0088FF)
        else Color(0xFF0091FF)
    // 底栏表面：接近透明的玻璃基底，质感由后面的实时模糊 + 顶部镜面渐变/边缘高光承载
    val containerColor =
        if (isLightTheme) Color(0xFFFAFAFA).copy(0.10f)
        else Color(0xFF121212).copy(0.14f)

    val tabsBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabsCount
        }

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var currentIndex by remember(selectedTabIndex) {
            mutableIntStateOf(selectedTabIndex())
        }
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabIndex().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(
                            0f,
                            spring(1f, 300f, 0.5f)
                        )
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            )
        }
        LaunchedEffect(selectedTabIndex) {
            snapshotFlow { selectedTabIndex() }
                .collectLatest { index ->
                    currentIndex = index
                }
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    dampedDragAnimation.animateToValue(index.toFloat())
                    onTabSelected(index)
                }
        }

        // 选中浮起度：滑块贴近任意 tab 中心时最强，拖动过渡时自然“融化”
        val selectedIdle by remember {
            derivedStateOf {
                val v = dampedDragAnimation.value
                val nearest = v.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                (1f - abs(v - nearest)).coerceIn(0f, 1f)
            }
        }

        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, offset ->
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset,
                        size.height / 2f
                    )
                }
            )
        }

        Row(
            Modifier
                .graphicsLayer {
                    translationX = panelOffset
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(8f.dp.toPx())
                        lens(24f.dp.toPx(), 24f.dp.toPx())
                    },
                    highlight = {
                        // 常驻 Fresnel 边缘高光：玻璃边缘一圈连续亮边
                        Highlight.Default.copy(alpha = 0.5f)
                    },
                    innerShadow = {
                        // 内部柔和阴影：玻璃厚度
                        InnerShadow(
                            radius = 14f.dp,
                            offset = DpOffset(0f.dp, 6f.dp),
                            color = colors.glassShadow.copy(alpha = 0.6f)
                        )
                    },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = {
                        drawRect(containerColor)
                        // 顶部镜面渐变：沿胶囊曲面连续衰减，替代“磨平”的纯 alpha 面
                        drawRect(
                            Brush.verticalGradient(
                                0f to colors.glassHighlight.copy(alpha = 0.10f),
                                0.30f to Color.Transparent,
                                1f to Color.Transparent
                            )
                        )
                    }
                )
                .then(interactiveHighlight.modifier)
                .height(64f.dp)
                .fillMaxWidth()
                .padding(4f.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )

        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.2f, dampedDragAnimation.pressProgress) *
                    lerp(1f, 1.07f, selectedIdle)
            }
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer {
                        translationX = panelOffset
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            vibrancy()
                            blur(8f.dp.toPx())
                            lens(
                                24f.dp.toPx() * progress,
                                24f.dp.toPx() * progress
                            )
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Default.copy(alpha = progress)
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(56f.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4f.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        Box(
            Modifier
                .align(Alignment.CenterStart)
                .zIndex(2f)
                .padding(horizontal = 4f.dp)
                .graphicsLayer {
                    translationX =
                        if (isLtr) dampedDragAnimation.value * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                }
                .clickable(
                    interactionSource = null,
                    indication = null,
                    role = Role.Tab,
                    // 滑块覆盖当前 tab：点击时直接切到滑块所在 tab，
                    // 避免滑块抢走点击导致"主页点不动"
                    onClick = {
                        val index = currentIndex.fastCoerceIn(0, tabsCount - 1)
                        onTabSelected(index)
                    }
                )
                .then(interactiveHighlight.gestureModifier)
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        blur(5f.dp.toPx())
                        lens(
                            10f.dp.toPx() * progress,
                            14f.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Default.copy(alpha = 0.35f + 0.65f * max(selectedIdle, progress))
                    },
                    shadow = {
                        val progress = dampedDragAnimation.pressProgress
                        // 选中态自带浮起阴影（不只在按压时出现），形成空间层级
                        Shadow(
                            radius = 14f.dp,
                            color = Color.Black.copy(alpha = 0.35f),
                            alpha = 0.35f * selectedIdle + 0.65f * progress
                        )
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 12f.dp * lerp(0.4f, 1f, max(selectedIdle, progress)),
                            offset = DpOffset(0f.dp, 4f.dp),
                            color = Color.Black.copy(alpha = 0.16f),
                            alpha = lerp(0.4f, 1f, max(selectedIdle, progress))
                        )
                    },
                    layerBlock = {
                        // 选中浮起：在按压缩放基础上叠加轻微放大（液态膨胀）
                        scaleX = dampedDragAnimation.scaleX * lerp(1f, 1.06f, selectedIdle)
                        scaleY = dampedDragAnimation.scaleY * lerp(1f, 1.06f, selectedIdle)
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        val emphasis = max(selectedIdle, progress)

                        // 1) 完整胶囊底色：Oplus 上 blur 采样可能只覆盖下半部分，
                        //    这层保证玻璃球上半部分也完整可见；选中时更亮
                        drawRect(
                            if (isLightTheme) Color.White.copy(alpha = 0.20f + 0.10f * emphasis)
                            else Color.Black.copy(alpha = 0.24f + 0.12f * emphasis)
                        )

                        // 2) 顶部液态高光弧：光源在胶囊上方，光沿曲面连续衰减，
                        //    修复“上方边缘被切平/磨平”的直线裁剪感
                        val arcCenter = Offset(size.width / 2f, -size.height * 0.30f)
                        val arcRadius = size.maxDimension * 1.2f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    if (isLightTheme)
                                        Color.White.copy(alpha = 0.42f * emphasis + 0.12f)
                                    else
                                        Color.White.copy(alpha = 0.28f * emphasis + 0.08f),
                                    if (isLightTheme)
                                        Color.White.copy(alpha = 0.14f * emphasis)
                                    else
                                        Color.White.copy(alpha = 0.08f * emphasis),
                                    Color.Transparent
                                ),
                                center = arcCenter,
                                radius = arcRadius
                            ),
                            radius = arcRadius,
                            center = arcCenter
                        )

                        // 3) 底部内阴影：玻璃厚度
                        val shadowCenter = Offset(size.width / 2f, size.height * 1.25f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.16f * emphasis + 0.04f),
                                    Color.Transparent
                                ),
                                center = shadowCenter,
                                radius = size.width * 0.55f
                            ),
                            radius = size.width * 0.55f,
                            center = shadowCenter
                        )

                        // 4) 蓝色内发光：选中态内部光感
                        val glowCenter = Offset(size.width / 2f, size.height / 2f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    accentColor.copy(alpha = 0.10f + 0.12f * emphasis),
                                    accentColor.copy(alpha = 0.03f * emphasis),
                                    Color.Transparent
                                ),
                                center = glowCenter,
                                radius = size.width * 0.5f
                            ),
                            radius = size.width * 0.5f,
                            center = glowCenter
                        )

                        // 5) 边缘镜面描边：沿胶囊轮廓的高光边，顶部最亮
                        val rimRadius = size.minDimension / 2f
                        val rimPath = Path().apply {
                            addRoundRect(
                                RoundRect(
                                    Rect(0f, 0f, size.width, size.height),
                                    CornerRadius(rimRadius, rimRadius)
                                )
                            )
                        }
                        drawPath(
                            path = rimPath,
                            brush = Brush.verticalGradient(
                                0f to Color.White.copy(alpha = 0.5f * emphasis + 0.12f),
                                0.55f to Color.White.copy(alpha = 0.18f * emphasis + 0.05f),
                                1f to Color.White.copy(alpha = 0.06f * emphasis + 0.02f)
                            ),
                            style = Stroke(width = 2f.dp.toPx())
                        )

                        // 6) 按压过渡压暗（保留原逻辑）
                        drawRect(
                            if (isLightTheme) Color.Black.copy(0.1f)
                            else Color.White.copy(0.1f),
                            alpha = 1f - progress
                        )
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                    }
                )
                .height(56f.dp)
                .fillMaxWidth(1f / tabsCount)
        )
    }
}

private val LocalLiquidBottomTabScale =
    staticCompositionLocalOf { { 1f } }

@Composable
fun RowScope.GlassBottomTab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalLiquidBottomTabScale.current
    Column(
        modifier
            .clip(Capsule())
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val scale = scale()
                scaleX = scale
                scaleY = scale
            },
        verticalArrangement = Arrangement.spacedBy(2f.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}