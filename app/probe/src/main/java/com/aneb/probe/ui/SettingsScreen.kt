package com.aneb.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.engine.TestEngine
import com.aneb.probe.ui.components.GlassChrome
import com.aneb.probe.ui.components.SectionLabel
import com.aneb.probe.ui.components.SegmentedControl
import com.aneb.probe.ui.components.pressable
import com.aneb.probe.ui.theme.AnebElevation
import com.aneb.probe.ui.theme.AnebShapes
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.AnebType

/**
 * 设置页（设计稿 §设置，iOS 化）：服务器（bare-IP 默认 / sslip.io / 自定义）、模式（快测/取证）、
 * 传输（自动/WiFi/蜂窝）、Kimi/LLM API 探针入口、路测开关（危险项二次确认）、debug 注入提示。
 *
 * iOS 材质：毛玻璃顶栏（[GlassHeader]）、inset-grouped 分组卡（#1C1C1E 连续圆角 + hairline 分隔）、
 * 分段控件胶囊、iOS 蓝主色、绿色系统开关、按压缩放（[pressable]，减弱动效自动降级）。
 *
 * 纯 UI 层：状态由 MainActivity 提升（撑过配置变更、与 autorun/测量语义正交）。
 */
@Composable
fun SettingsScreen(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    mode: TestEngine.Mode,
    onModeChange: (TestEngine.Mode) -> Unit,
    transport: TestEngine.TransportMode,
    onTransportChange: (TestEngine.TransportMode) -> Unit,
    driveTest: Boolean,
    onDriveTestChange: (Boolean) -> Unit,
    injectActive: String?,
    onOpenApiProbe: () -> Unit,
    onOpenReachBoard: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = AnebTheme.colors
    var confirmDrive by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))
        GlassHeader("设置", onBack)

        // ---- 服务器 ----
        SectionLabel("测量服务器")
        val presets = listOf(
            ServerPreset("bare-IP（默认）", "https://120.79.148.0:8443"),
            ServerPreset("sslip.io（公网 TLS）", "https://120-79-148-0.sslip.io:8443"),
        )
        GroupedCard {
            presets.forEachIndexed { i, p ->
                if (i > 0) HairlineDivider()
                OptionRow(
                    title = p.label,
                    subtitle = p.url,
                    selected = serverUrl == p.url,
                    onClick = { onServerUrlChange(p.url) },
                )
            }
        }
        OutlinedTextField(
            value = serverUrl,
            onValueChange = onServerUrlChange,
            label = { Text("自定义服务器地址") },
            singleLine = true,
            shape = AnebShapes.button,
            colors = iosTextFieldColors(),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )

        // ---- 模式 ----
        SectionLabel("测量模式")
        SegmentedControl(
            options = listOf(TestEngine.Mode.QUICK, TestEngine.Mode.FORENSIC),
            selected = mode,
            onSelect = onModeChange,
            label = { if (it == TestEngine.Mode.QUICK) "快测（约 90s）" else "取证（多遍拉丁方）" },
            modifier = Modifier.fillMaxWidth(),
        )

        // ---- 传输 ----
        SectionLabel("传输通道")
        SegmentedControl(
            options = listOf(
                TestEngine.TransportMode.AUTO,
                TestEngine.TransportMode.WIFI,
                TestEngine.TransportMode.CELLULAR,
            ),
            selected = transport,
            onSelect = onTransportChange,
            label = {
                when (it) {
                    TestEngine.TransportMode.AUTO -> "自动"
                    TestEngine.TransportMode.WIFI -> "WiFi"
                    TestEngine.TransportMode.CELLULAR -> "蜂窝"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // ---- 对照/探针入口（API 探针 + 可达性看板；后者已从顶级 tab 降为此二级入口）----
        SectionLabel("对照：真实 LLM 探针")
        GroupedCard {
            OptionRow(
                title = "Kimi / OpenAI 兼容 API 探针",
                subtitle = "API key 走 Android Keystore 加密存储 · 独立口径不进 AQS",
                selected = false,
                showChevron = true,
                onClick = onOpenApiProbe,
            )
            HairlineDivider()
            OptionRow(
                title = "AI 可达性看板",
                subtitle = "无 key 连接层探测（TLS 握手）· best-effort 不进 AQS",
                selected = false,
                showChevron = true,
                onClick = onOpenReachBoard,
            )
        }

        // ---- 路测开关（隐私边界，危险项二次确认）----
        SectionLabel("GPS 路测")
        GroupedCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("记录位置轨迹（1Hz）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                    Text(
                        "坐标仅存本机、绝不上报服务器（§9.1）",
                        fontSize = 12.sp,
                        color = if (driveTest) colors.poor else colors.muted,
                    )
                }
                Switch(
                    checked = driveTest,
                    onCheckedChange = { on -> if (on) confirmDrive = true else onDriveTestChange(false) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = colors.excellent,
                        uncheckedTrackColor = colors.surfaceMuted,
                        uncheckedBorderColor = colors.hairline,
                    ),
                )
            }
        }

        if (injectActive != null) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AnebShapes.tile)
                    .background(colors.poorSoft)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "调试注入生效：$injectActive（本次 run 非取证证据）",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.poor,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(ResultFormat.CLAIM_SCOPE_TEXT, fontSize = 11.sp, color = colors.faint)
        Spacer(Modifier.height(24.dp))
    }

    if (confirmDrive) {
        AlertDialog(
            onDismissRequest = { confirmDrive = false },
            title = { Text("开启位置轨迹记录？", fontWeight = FontWeight.SemiBold, color = colors.ink) },
            text = {
                Text(
                    "将以 1Hz 记录 GPS 坐标用于路测标注。坐标仅保存在本机、绝不上报服务器（§9.1）。" +
                        "确认开启？",
                    fontSize = 13.sp,
                    color = colors.muted,
                )
            },
            containerColor = colors.surface,
            shape = AnebShapes.card,
            confirmButton = {
                TextButton(onClick = { onDriveTestChange(true); confirmDrive = false }) {
                    Text("开启", color = colors.brand, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDrive = false }) {
                    Text("取消", color = colors.muted)
                }
            },
        )
    }
}

private data class ServerPreset(val label: String, val url: String)

@Composable
private fun OptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    showChevron: Boolean = false,
) {
    val colors = AnebTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(Modifier.pressable(onClick = onClick))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
            Text(subtitle, fontSize = 11.5.sp, color = colors.muted)
        }
        when {
            selected -> Text("✓", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.brand)
            showChevron -> Text("›", fontSize = 18.sp, color = colors.faint)
        }
    }
}

// ==================================================================
// 共享 iOS 基元（本包 4 屏复用；仅 ui 层，不碰测量语义）
// ==================================================================

/**
 * 毛玻璃顶栏（[GlassChrome] 材质浮层 + hairline 连续圆角）。左返回、标题（iOS Title 字阶），
 * 可选右侧 [trailing]（右对齐）。各二级屏统一取用，读作 iOS 导航栏。
 */
@Composable
internal fun GlassHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = AnebTheme.colors
    GlassChrome(
        modifier = modifier
            .fillMaxWidth()
            .clip(AnebShapes.card)
            .border(1.dp, colors.hairline, AnebShapes.card),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onBack)
            Spacer(Modifier.width(10.dp))
            Text(title, style = AnebType.Title, fontSize = 19.sp, color = colors.ink)
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/** iOS inset-grouped 分组卡：#1C1C1E 连续圆角 + hairline 描边 + 细阴影；行由调用点 + [HairlineDivider] 组装。 */
@Composable
internal fun GroupedCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AnebTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(AnebElevation.level1, AnebShapes.card, clip = false)
            .clip(AnebShapes.card)
            .background(colors.surface)
            .border(1.dp, colors.hairline, AnebShapes.card),
        content = content,
    )
}

/** 分组卡内行间 hairline 分隔（iOS list separator，左侧留白对齐文本）。 */
@Composable
internal fun HairlineDivider() {
    val colors = AnebTheme.colors
    HorizontalDivider(color = colors.hairline, modifier = Modifier.padding(start = 14.dp))
}

/** iOS 蓝实心主按钮（按压缩放）。禁用态灰底弱字。 */
@Composable
internal fun IosFilledButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = AnebTheme.colors
    Box(
        modifier = modifier
            .clip(AnebShapes.button)
            .background(if (enabled) colors.brand else colors.surfaceMuted)
            .then(if (enabled) Modifier.pressable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) Color.White else colors.faint,
        )
    }
}

/** iOS soft 次按钮：柔和底 + hairline 描边，文字取品牌色或传入 [tint]（如危险项 poor）。 */
@Composable
internal fun IosSoftButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    val colors = AnebTheme.colors
    val content = tint ?: colors.brand2
    Box(
        modifier = modifier
            .clip(AnebShapes.button)
            .background(colors.surfaceMuted)
            .border(1.dp, colors.hairline, AnebShapes.button)
            .then(if (enabled) Modifier.pressable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) content else colors.faint,
        )
    }
}

/** 统一的 iOS 输入框配色：品牌焦点边、hairline 常态边、surface 容器、品牌光标。 */
@Composable
internal fun iosTextFieldColors(): TextFieldColors {
    val colors = AnebTheme.colors
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.brand,
        unfocusedBorderColor = colors.hairline,
        disabledBorderColor = colors.hairline,
        focusedContainerColor = colors.surface,
        unfocusedContainerColor = colors.surface,
        disabledContainerColor = colors.surface,
        cursorColor = colors.brand,
        focusedLabelColor = colors.brand,
        unfocusedLabelColor = colors.muted,
        focusedTextColor = colors.ink,
        unfocusedTextColor = colors.ink,
    )
}

@Composable
internal fun BackButton(onBack: () -> Unit) {
    val colors = AnebTheme.colors
    Text(
        text = "‹ 返回",
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = colors.brand2,
        modifier = Modifier
            .clip(AnebShapes.pill)
            .background(colors.surfaceMuted)
            .border(1.dp, colors.hairline, AnebShapes.pill)
            .then(Modifier.pressable(onClick = onBack))
            .padding(horizontal = 13.dp, vertical = 7.dp),
    )
}
