package com.aneb.probe.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.apiprobe.LlmProvider
import com.aneb.probe.apiprobe.ProviderPreset
import com.aneb.probe.apiprobe.toLlmProvider
import com.aneb.probe.data.ApiProbeResultEntity
import com.aneb.probe.ui.components.AnebGradientCard
import com.aneb.probe.ui.components.AnebPageIntro
import com.aneb.probe.ui.components.AnebSectionTitle
import com.aneb.probe.ui.components.AnebSparkline
import com.aneb.probe.ui.components.AnebTopBar
import com.aneb.probe.ui.components.SegmentedControl
import com.aneb.probe.ui.components.pressable
import com.aneb.probe.ui.theme.AnebShapes
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.AnebType
import java.util.Locale
import kotlin.math.roundToInt

/** API 探针：复刻 `probe.html` 的仪表和端点卡，数据只取真实探针结果。 */
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
    showBack: Boolean = true,
) {
    val colors = AnebTheme.colors
    var configOpen by rememberSaveable { mutableStateOf(false) }
    val success = results.count { it.error == null }
    val anomalies = results.count { it.error != null }
    val latencyValues = results.mapNotNull { it.ttftMs }
    val avgLatency = latencyValues.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
    val latest = results.firstOrNull()

    Column(Modifier.fillMaxSize().background(colors.background).padding(horizontal = 16.dp)) {
        AnebTopBar(
            showBack = showBack,
            onBack = onBack,
            showMenu = true,
            onMenu = { configOpen = !configOpen },
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            AnebPageIntro(
                eyebrow = "ENDPOINT MONITOR",
                title = "API 探针",
                modifier = Modifier.weight(1f),
            )
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.excellent.copy(alpha = 0.05f))
                    .border(1.dp, colors.excellent.copy(alpha = 0.18f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(colors.excellent))
                Text("实时", fontSize = 9.sp, color = colors.excellent, modifier = Modifier.padding(start = 5.dp))
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = if (showBack) 18.dp else 76.dp),
        ) {
            item {
                ProbeSummary(
                    running = running,
                    hasKey = hasStoredKey,
                    success = success,
                    total = results.size,
                    avgLatency = avgLatency,
                    anomalies = anomalies,
                    onRun = {
                        if (hasStoredKey) onRun() else configOpen = true
                    },
                )
            }

            if (configOpen) {
                item {
                    ProbeConfiguration(
                        provider = provider,
                        onProviderChange = onProviderChange,
                        baseUrl = baseUrl,
                        onBaseUrlChange = onBaseUrlChange,
                        model = model,
                        onModelChange = onModelChange,
                        keyInput = keyInput,
                        onKeyInputChange = onKeyInputChange,
                        hasStoredKey = hasStoredKey,
                        keyStoreEncrypted = keyStoreEncrypted,
                        onSaveConfig = onSaveConfig,
                        onClearKey = onClearKey,
                        running = running,
                        presets = presets,
                        selectedPresetId = selectedPresetId,
                        onSelectPreset = onSelectPreset,
                        onOpenReachBoard = onOpenReachBoard,
                    )
                }
            }

            item {
                AnebSectionTitle(
                    text = "服务状态",
                    action = if (results.isEmpty()) "连接层看板" else "导出",
                    onAction = if (results.isEmpty()) onOpenReachBoard else onExport,
                    modifier = Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp, bottom = 7.dp),
                )
            }

            if (latest == null) {
                item { EmptyProbeCard(provider = provider, baseUrl = baseUrl, hasKey = hasStoredKey) }
            } else {
                items(count = results.size.coerceAtMost(8), key = { results[it] }) { index ->
                    EndpointResultCard(results[index], modifier = Modifier.padding(bottom = 7.dp))
                }
            }

            if (logs.isNotEmpty()) {
                item { AnebSectionTitle("运行日志", Modifier.padding(top = 14.dp, start = 4.dp, bottom = 6.dp)) }
                items(count = logs.size.coerceAtMost(12), key = { 1_000_000 + it }) { index ->
                    Text(logs[index], fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = colors.faint)
                }
            }
            exportStatus?.let { status ->
                item { Text(status, fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = colors.faint, modifier = Modifier.padding(top = 6.dp)) }
            }
        }
    }
}

@Composable
private fun ProbeSummary(
    running: Boolean,
    hasKey: Boolean,
    success: Int,
    total: Int,
    avgLatency: Int?,
    anomalies: Int,
    onRun: () -> Unit,
) {
    val colors = AnebTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().height(139.dp).padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(122.dp).pressable(onClick = onRun, enabled = !running),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.matchParentSize()) {
                val stroke = 1.4.dp.toPx()
                drawCircle(colors.brand.copy(alpha = 0.18f), radius = size.minDimension / 2f - stroke, style = Stroke(stroke))
                if (running) {
                    drawArc(
                        Brush.sweepGradient(listOf(Color.Transparent, colors.brand, colors.brand2, Color.Transparent), center),
                        startAngle = -90f,
                        sweepAngle = 300f,
                        useCenter = false,
                        style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round),
                    )
                } else if (total > 0) {
                    drawArc(
                        colors.excellent,
                        startAngle = -90f,
                        sweepAngle = 360f * (success.toFloat() / total.coerceAtLeast(1)),
                        useCenter = false,
                        style = Stroke(2.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (running) "…" else "GO", fontSize = 27.sp, fontWeight = FontWeight(560), color = colors.ink)
                Text(
                    when { running -> "探测中"; !hasKey -> "先配置 key"; else -> "开始探测" },
                    fontSize = 10.sp,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            ProbeKpi(if (total == 0) "—" else "$success/$total", "成功记录")
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline).padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                ProbeKpi(avgLatency?.toString() ?: "—", "首字响应 ms", Modifier.weight(1f))
                ProbeKpi(anomalies.toString(), "异常", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ProbeKpi(value: String, label: String, modifier: Modifier = Modifier) {
    val colors = AnebTheme.colors
    Column(modifier) {
        Text(value, style = AnebType.StatValue, fontSize = 20.sp, fontWeight = FontWeight(520), color = colors.ink)
        Text(label, fontSize = 10.sp, color = colors.muted, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun ProbeConfiguration(
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
    presets: List<ProviderPreset>,
    selectedPresetId: String?,
    onSelectPreset: (ProviderPreset) -> Unit,
    onOpenReachBoard: () -> Unit,
) {
    val colors = AnebTheme.colors
    AnebGradientCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("探针配置", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.ink)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                presets.sortedByDescending { it.verified }.forEach { preset ->
                    val selected = preset.id == selectedPresetId ||
                        (provider == preset.toLlmProvider() && baseUrl == preset.baseUrl && model == preset.defaultModel)
                    Text(
                        (if (preset.verified) "✓ " else "△ ") + preset.displayName,
                        fontSize = 10.sp,
                        color = if (selected) colors.brand else colors.muted,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (selected) colors.brand.copy(alpha = 0.10f) else Color.Transparent)
                            .border(1.dp, if (selected) colors.brand.copy(alpha = 0.24f) else colors.hairline, CircleShape)
                            .pressable(onClick = { onSelectPreset(preset) }, enabled = !running)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }
            SegmentedControl(
                options = listOf(LlmProvider.ANTHROPIC, LlmProvider.OPENAI_COMPAT),
                selected = provider,
                onSelect = { if (!running) onProviderChange(it) },
                label = { if (it == LlmProvider.ANTHROPIC) "Anthropic" else "OpenAI 兼容" },
                modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
            )
            ProbeTextField(baseUrl, onBaseUrlChange, "Base URL", !running)
            ProbeTextField(model, onModelChange, "Model", !running)
            OutlinedTextField(
                value = keyInput,
                onValueChange = onKeyInputChange,
                label = {
                    Text(
                        when {
                            !keyStoreEncrypted -> "API key（安全存储不可用）"
                            hasStoredKey -> "API key（已保存；输入可覆盖）"
                            else -> "API key"
                        },
                    )
                },
                singleLine = true,
                enabled = !running && keyStoreEncrypted,
                visualTransformation = PasswordVisualTransformation(),
                shape = AnebShapes.button,
                colors = iosTextFieldColors(),
                modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
            )
            if (!keyStoreEncrypted) {
                Text("Keystore 当前不可用；ANEB 已禁止保存 API key，可继续使用免 key 可达性探测。", fontSize = 9.sp, color = colors.poor, modifier = Modifier.padding(top = 5.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                IosFilledButton("保存配置", onSaveConfig, Modifier.weight(1f), enabled = !running)
                IosSoftButton("清除 key", onClearKey, Modifier.weight(1f), enabled = hasStoredKey && !running, tint = colors.poor)
            }
            IosSoftButton("连接层可达性看板（免 key）", onOpenReachBoard, Modifier.fillMaxWidth().padding(top = 7.dp), enabled = !running)
        }
    }
}

@Composable
private fun ProbeTextField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        shape = AnebShapes.button,
        colors = iosTextFieldColors(),
        modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
    )
}

@Composable
private fun EmptyProbeCard(provider: LlmProvider, baseUrl: String, hasKey: Boolean) {
    val colors = AnebTheme.colors
    AnebGradientCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).border(1.dp, colors.muted.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
                Text(provider.id.take(2).uppercase(), fontSize = 8.sp, color = colors.muted)
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(provider.id, fontSize = 12.sp, color = colors.ink)
                Text(baseUrl, fontSize = 9.sp, color = colors.muted, maxLines = 1)
            }
            Text(if (hasKey) "待探测" else "未配置", fontSize = 10.sp, color = if (hasKey) colors.fair else colors.muted)
        }
    }
}

@Composable
private fun EndpointResultCard(r: ApiProbeResultEntity, modifier: Modifier = Modifier) {
    val colors = AnebTheme.colors
    val error = r.error != null
    val accent = if (error) colors.poor else colors.excellent
    AnebGradientCard(modifier.fillMaxWidth(), radius = 14.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).border(1.dp, accent.copy(alpha = 0.45f), CircleShape), contentAlignment = Alignment.Center) {
                Text(r.provider.take(2).uppercase(), fontSize = 8.sp, fontWeight = FontWeight.Bold, color = accent)
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text("${r.provider} · ${r.model}", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.ink, maxLines = 1)
                Text("HTTP ${r.httpCode ?: "—"} · ${r.totalMs?.let { "${it.roundToInt()} ms" } ?: "耗时缺失"}", fontSize = 9.sp, color = colors.muted)
            }
            val samples = listOfNotNull(r.ttftMs, r.itlMedianMs, r.itlP95Ms).map { (1f - (it / 2_000.0).coerceIn(0.0, 1.0)).toFloat() }
            AnebSparkline(samples, accent, Modifier.width(56.dp).height(24.dp))
            Column(Modifier.width(48.dp), horizontalAlignment = Alignment.End) {
                Text(r.ttftMs?.let { "${it.roundToInt()} ms" } ?: "—", fontSize = 10.sp, color = accent)
                Text(if (error) "异常" else "正常", fontSize = 9.sp, color = accent)
            }
        }
    }
}
