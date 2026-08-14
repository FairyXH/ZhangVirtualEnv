package io.github.fairyxh.VirtualEnv.app.ui.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.github.fairyxh.VirtualEnv.R

/**
 * 液态玻璃详情弹窗：替代原生 AlertDialog。
 * 注意：不要在 Dialog 里用 GlassBackdropHost —— 它的背景层会 fillMaxSize 铺满
 * 整个 Dialog 窗口，形成“非常大的矩形白底”。这里只创建局部 backdrop 层
 * （透明，仅限卡片区域），玻璃感来自卡片自身的透镜/高光/内阴影。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GlassTextDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        val backdrop = rememberLayerBackdrop()

        // 毛玻璃弹窗 + 严格居中：窗口铺满全屏，卡片在 Compose 内 align(Center)，
        // 保证四边到屏幕边缘距离相等（不依赖窗口 gravity/wrap 计算）
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(dialogWindow) {
            val w = dialogWindow ?: return@LaunchedEffect
            w.setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT
            )
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                w.setBackgroundBlurRadius(40)
            }
        }
        DisposableEffect(dialogWindow) {
            onDispose {
                val w = dialogWindow ?: return@onDispose
                w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    w.setBackgroundBlurRadius(0)
                }
            }
        }

        // 窗口铺满全屏后，Compose Dialog 内容区仍默认避开系统栏
        // （内容高度 = 屏幕 − 状态栏 − 导航栏），align(Center) 只相对内容区居中。
        // 用系统栏 inset 把卡片中心补偿回屏幕中心，保证四边到屏幕边缘距离相等。
        val density = LocalDensity.current
        val sysBars = WindowInsets.systemBars.asPaddingValues()
        val centerShift = with(density) {
            (sysBars.calculateTopPadding() + sysBars.calculateBottomPadding()) / 2
        }
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .offset(y = centerShift)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                // 透明 backdrop 采样层：只占卡片区域，不绘制任何底色
                Box(
                    Modifier
                        .fillMaxSize()
                        .layerBackdrop(backdrop)
                )
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 22.dp,
                    containerColor = glassColors().bgSecondary.copy(alpha = 0.62f),
                    contentPadding = 0.dp
                ) {
                    GlassDialogContent(title, text, backdrop, onDismiss)
                }
            }
        }
    }
}

/**
 * WiFi 已保存列表弹窗（分页，每页 [PAGE_SIZE] 条）。
 * 点击条目回调 onSelect(ssid)。
 */
@Composable
fun GlassWifiPickerDialog(
    title: String,
    items: List<org.json.JSONObject>,
    onSelect: (org.json.JSONObject) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = glassColors()
    var page by remember { mutableStateOf(0) }
    val totalPages = (items.size + 14) / 15
    if (page >= totalPages && totalPages > 0) page = totalPages - 1
    val pageItems = items.drop(page * 15).take(15)

    Dialog(onDismissRequest = onDismiss) {
        val backdrop = rememberLayerBackdrop()
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(dialogWindow) {
            val w = dialogWindow ?: return@LaunchedEffect
            w.setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT
            )
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                w.setBackgroundBlurRadius(40)
            }
        }
        DisposableEffect(dialogWindow) {
            onDispose {
                val w = dialogWindow ?: return@onDispose
                w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                if (android.os.Build.VERSION.SDK_INT >= 31) w.setBackgroundBlurRadius(0)
            }
        }
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp),
                    cornerRadius = 22.dp,
                    containerColor = colors.bgSecondary.copy(alpha = 0.62f),
                    contentPadding = 0.dp
                ) {
                    Column(Modifier.padding(16.dp)) {
                        BasicText(
                            title,
                            style = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        )
                        if (items.isEmpty()) {
                            BasicText(
                                "未读取到系统已保存 WiFi（无权限时模块会用 Root 读取）",
                                Modifier.padding(top = 12.dp),
                                style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                            )
                        } else {
                            Column(
                                Modifier
                                    .padding(top = 8.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                pageItems.forEach { item ->
                                    val ssid = item.optString("ssid", "")
                                    val bssid = item.optString("bssid", "")
                                    val rssi = item.optInt("rssi", Int.MIN_VALUE)
                                    val security = item.optString("security", "")
                                    val line = buildString {
                                        append(ssid)
                                        if (bssid.isNotBlank()) append("  ").append(bssid)
                                        if (rssi != Int.MIN_VALUE) append("  ").append(rssi).append("dBm")
                                        if (security.isNotBlank()) append("  ").append(security)
                                    }
                                    GlassPill(
                                        onClick = { onSelect(item) },
                                        backdrop = backdrop,
                                        modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                                        selected = false,
                                        containerColor = colors.bgTertiary.copy(alpha = 0.3f),
                                        height = 38.dp
                                    ) {
                                        BasicText(
                                            line,
                                            Modifier.padding(horizontal = 12.dp),
                                            style = TextStyle(color = colors.textPrimary, fontSize = 13.sp)
                                        )
                                    }
                                }
                            }
                            Row(
                                Modifier.padding(top = 10.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                GlassPill(
                                    onClick = { if (page > 0) page-- },
                                    backdrop = backdrop,
                                    selected = false,
                                    containerColor = colors.bgTertiary.copy(alpha = 0.3f),
                                    height = 34.dp
                                ) {
                                    BasicText(
                                        "上一页",
                                        Modifier.padding(horizontal = 12.dp),
                                        style = TextStyle(color = colors.textPrimary, fontSize = 13.sp)
                                    )
                                }
                                BasicText(
                                    "第 ${page + 1}/$totalPages 页 · 共 ${items.size} 个",
                                    style = TextStyle(color = colors.textSecondary, fontSize = 12.sp)
                                )
                                GlassPill(
                                    onClick = { if (page < totalPages - 1) page++ },
                                    backdrop = backdrop,
                                    selected = false,
                                    containerColor = colors.bgTertiary.copy(alpha = 0.3f),
                                    height = 34.dp
                                ) {
                                    BasicText(
                                        "下一页",
                                        Modifier.padding(horizontal = 12.dp),
                                        style = TextStyle(color = colors.textPrimary, fontSize = 13.sp)
                                    )
                                }
                            }
                        }
                        GlassPill(
                            onClick = onDismiss,
                            backdrop = backdrop,
                            modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                            selected = false,
                            containerColor = colors.bgTertiary.copy(alpha = 0.3f),
                            height = 36.dp
                        ) {
                            BasicText(
                                "关闭",
                                Modifier.padding(horizontal = 12.dp),
                                style = TextStyle(color = colors.textSecondary, fontSize = 13.sp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassDialogContent(
    title: String,
    text: String,
    backdrop: Backdrop,
    onDismiss: () -> Unit
) {
    val colors = glassColors()
    Column(Modifier.padding(20.dp)) {
        BasicText(
            title,
            style = TextStyle(
                color = colors.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
        BasicText(
            text,
            Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            style = TextStyle(
                color = colors.textSecondary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
        )
        GlassButton(
            onClick = onDismiss,
            backdrop = backdrop,
            modifier = Modifier
                .padding(top = 16.dp)
                .fillMaxWidth(),
            surfaceColor = colors.bgTertiary.copy(alpha = 0.4f)
        ) {
            BasicText(
                androidx.compose.ui.res.stringResource(R.string.dialog_close),
                Modifier.padding(vertical = 2.dp),
                style = TextStyle(
                    color = colors.accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}
