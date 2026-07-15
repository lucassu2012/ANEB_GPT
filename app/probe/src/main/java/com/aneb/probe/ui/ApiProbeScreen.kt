package com.aneb.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.apiprobe.LlmProvider
import com.aneb.probe.apiprobe.ProviderPreset
import com.aneb.probe.apiprobe.toLlmProvider
import com.aneb.probe.data.ApiProbeResultEntity
import com.aneb.probe.ui.components.SectionLabel
import com.aneb.probe.ui.components.SegmentedControl
import com.aneb.probe.ui.components.pressable
import com.aneb.probe.ui.theme.AnebShapes
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.AnebType

/**
 * 真实 API 探针屏（阶段 2，iOS 化）。**独立入口**：不动 TestEngine 流程；结果单独归类展示
 * （claim scope=application_end_to_end_to_llm_api，不进 AQS，不与仿真 KPI 混排）。
 *
 * iOS 材质：毛玻璃顶栏、分段控件选 provider、统一输入框配色、iOS 蓝主按钮 + soft 次按钮、
 * 结果 mono 卡（错误行发 poor 语义色）。
 *
 * E-03 缺 key 降级：无 key 时 Run 按钮禁用置灰并显示"E-03 未配置"。
 * key 输入框密文显示；key 永不回显到日志/导出（出口经 ApiKeyRedactor，单测锚定）。
 */
@Composable
fun ApiProbeScreen(
    provider: LlmProvider,
    onProviderChange: (LlmProvider) -> Unit,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    keyInput: String,
    onKeyInputChange: (String) -> Unit,
    hasStoredKey: Boolean,
    keyStoreEncrypted: Boolean,
    onSaveConfig: () -> Unit,
    onClearKey: () -> Unit,
    running: Boolean,
    onRun: () -> Unit,
    logs: SnapshotStateList<String>,
    results: List<ApiProbeResultEntity>,
    exportStatus: String?,
    onExport: () -> Unit,
    presets: List<ProviderPreset>,
    selectedPresetId: String?,
    onSelectPreset: (ProviderPreset) -> Unit,
    onOpenReachBoard: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = AnebTheme.colors
    Column(modifier = Modifier.fillMaxSize().background(colors.background).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(8.dp))
        GlassHeader("API 探针", onBack)

        // ---- 可达性看板入口（免 key）----
        Spacer(Modifier.height(12.dp))
        IosSoftButton(
            "① 全家可达性看板（免 key）",
            onOpenReachBoard,
            Modifier.fillMaxWidth(),
            enabled = !running,
        )

        // ---- 预置接入 ----
        SectionLabel("预置接入（选中自动填 provider / base / model）")
        val orderedPresets = remember(presets) { presets.sortedByDescending { it.verified } }
        // 高亮/详情以"当前请求参数是否仍与该预置完全一致"为准（provider+base+model 全等才算选中）；
        // 手改 base/model/provider 后即不再高亮，避免误导。selectedPresetId 仅记录"点选了哪个"意图。
        val activePreset = remember(provider, baseUrl, model, orderedPresets) {
            orderedPresets.firstOrNull {
                provider == it.toLlmProvider() && baseUrl == it.baseUrl && model == it.defaultModel
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            orderedPresets.forEach { preset ->
                PresetChip(
                    preset = preset,
                    selected = preset.id == activePreset?.id,
                    enabled = !running,
                    onClick = { onSelectPreset(preset) },
                )
            }
        }
        activePreset?.let { PresetDetail(it) }

        // ---- 提供方 ----
        SectionLabel("提供方")
        SegmentedControl(
            options = listOf(LlmProvider.ANTHROPIC, LlmProvider.OPENAI_COMPAT),
            selected = provider,
            onSelect = { if (!running) onProviderChange(it) },
            label = {
                when (it) {
                    LlmProvider.ANTHROPIC -> "Anthropic"
                    LlmProvider.OPENAI_COMPAT -> "OpenAI 兼容 (Kimi)"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // ---- 连接配置 ----
        SectionLabel("连接配置")
        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseUrlChange,
            label = { Text("Base URL") },
            singleLine = true,
            enabled = !running,
            shape = AnebShapes.button,
            colors = iosTextFieldColors(),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        OutlinedTextField(
            value = model,
            onValueChange = onModelChange,
            label = { Text("Model") },
            singleLine = true,
            enabled = !running,
            shape = AnebShapes.button,
            colors = iosTextFieldColors(),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        OutlinedTextField(
            value = keyInput,
            onValueChange = onKeyInputChange,
            label = { Text(if (hasStoredKey) "API key（已保存，输入可覆盖）" else "API key") },
            singleLine = true,
            enabled = !running,
            visualTransformation = PasswordVisualTransformation(),
            shape = AnebShapes.button,
            colors = iosTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!keyStoreEncrypted) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AnebShapes.tile)
                    .background(colors.poorSoft)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text(
                    "警告：Keystore 不可用，key 以应用私有明文 prefs 存储（沙箱内，见 ApiKeyStore 取舍）",
                    color = colors.poor,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // ---- 操作 ----
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IosSoftButton("保存配置", onSaveConfig, Modifier.weight(1f), enabled = !running)
            IosFilledButton(
                label = when {
                    running -> "运行中…"
                    !hasStoredKey -> "E-03 未配置"
                    else -> "运行探针"
                },
                onClick = onRun,
                modifier = Modifier.weight(1f),
                enabled = !running && hasStoredKey,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IosSoftButton("清除 key", onClearKey, Modifier.weight(1f), enabled = !running && hasStoredKey, tint = colors.poor)
            IosSoftButton("导出（独立 JSON）", onExport, Modifier.weight(1f), enabled = results.isNotEmpty())
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "对照列口径：application_end_to_end_to_llm_api（含用户网络路径/代理/模型推理），" +
                "不进 AQS、不与仿真节点 KPI 混排",
            fontSize = 11.sp,
            color = colors.faint,
        )
        exportStatus?.let {
            Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = colors.faint, modifier = Modifier.padding(top = 4.dp))
        }

        SectionLabel("探针结果 / 日志")
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(count = results.size, key = { it }) { i ->
                ResultCard(results[i])
            }
            items(count = logs.size, key = { 1_000_000 + it }) { index ->
                Text(
                    text = logs[index],
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = colors.muted,
                )
            }
        }
    }
}

@Composable
private fun ResultCard(r: ApiProbeResultEntity) {
    val colors = AnebTheme.colors
    fun f(v: Double?) = if (v == null) "null" else "%.1f".format(v)
    val isError = r.error != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AnebShapes.tile)
            .background(colors.surface)
            .border(1.dp, colors.hairline, AnebShapes.tile)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(AnebShapes.pill)
                    .background(if (isError) colors.poor else colors.excellent),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                "${r.provider} / ${r.model}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "http=${r.httpCode ?: "-"}",
                style = AnebType.StatValue,
                fontSize = 11.sp,
                color = if (isError) colors.poor else colors.muted,
            )
        }
        Text(
            "ttft=${f(r.ttftMs)}ms  itl_p50=${f(r.itlMedianMs)}  itl_p95=${f(r.itlP95Ms)}\n" +
                "tokens=${r.tokenEventCount}  total=${f(r.totalMs)}ms  proxy=${r.proxyDetected}",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = colors.muted,
            modifier = Modifier.padding(top = 4.dp),
        )
        r.error?.let {
            Text("err=$it", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = colors.poor, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * 预置 chip（胶囊）：✅ verified / ⚠️ needsConfirm 前缀标记，选中态品牌高亮描边。
 * 横向滚动行内取用；点击回调 [onClick] 触发自动填充 provider/base/model（由上层处理）。
 * key 处理逐字不变——预置只填非敏感的 base/model，key 仍走加密存储、绝不入日志。
 */
@Composable
private fun PresetChip(
    preset: ProviderPreset,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = AnebTheme.colors
    val marker = if (preset.verified) "✅ " else "⚠️ "
    val bg = if (selected) colors.brand.copy(alpha = 0.16f) else colors.surfaceMuted
    val borderColor = if (selected) colors.brand else colors.hairline
    val textColor = when {
        !enabled -> colors.faint
        selected -> colors.brand2
        else -> colors.ink
    }
    Box(
        modifier = Modifier
            .clip(AnebShapes.pill)
            .background(bg)
            .border(1.dp, borderColor, AnebShapes.pill)
            .pressable(onClick = onClick, enabled = enabled)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            marker + preset.displayName,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = textColor,
            maxLines = 1,
        )
    }
}

/**
 * 选中预置的说明卡：freeTierNote + keyConsole + caveat（有则显）；verified=false 时追加
 * ⚠️ 警示条（fairSoft 底 / fair 字）"以官方文档为准"。仅展示，不改测量口径。
 */
@Composable
private fun PresetDetail(preset: ProviderPreset) {
    val colors = AnebTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(AnebShapes.tile)
            .background(colors.surface)
            .border(1.dp, colors.hairline, AnebShapes.tile)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            preset.displayName,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.ink,
        )
        PresetInfoLine("免费额度", preset.freeTierNote)
        PresetInfoLine("Key 申请", preset.keyConsole)
        preset.caveat?.let { PresetInfoLine("注意", it) }
        if (!preset.verified) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AnebShapes.tile)
                    .background(colors.fairSoft)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text(
                    "⚠️ 端点/协议未经官方核实，请以官方文档为准后再使用",
                    color = colors.fair,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** 说明行：faint 标签 + muted 值（值随宽度换行）。 */
@Composable
private fun PresetInfoLine(label: String, value: String) {
    val colors = AnebTheme.colors
    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            "$label：",
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            color = colors.faint,
        )
        Text(
            value,
            fontSize = 11.5.sp,
            color = colors.muted,
            modifier = Modifier.weight(1f),
        )
    }
}
