package com.aneb.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aneb.probe.data.Exporter
import com.aneb.probe.engine.BasicSpeedResult
import com.aneb.probe.engine.LoadedResponsiveness
import com.aneb.probe.ui.theme.AnebTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Unscored directional facts. Historical Basic conclusions and scores are never reused here. */
@Composable
fun LoadedResponsivenessScreen(result: BasicSpeedResult, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportStatus by remember(result.runId) { mutableStateOf("") }
    var exporting by remember(result.runId) { mutableStateOf(false) }
    val report = remember(result.responsivenessJson) {
        result.responsivenessJson?.let { runCatching { LoadedResponsiveness.decode(it) }.getOrNull() }
    }
    val summary = remember(report) { report?.let(LoadedResponsiveness::summarize) }
    fun value(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.2f ms", it) } ?: "不可用"
    val colors = AnebTheme.colors
    CompositionLocalProvider(LocalContentColor provides colors.ink) {
    Column(Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onBack) { Text("返回") }
        Text("负载下响应性")
        Text("仅限 ANEB 应用到所选节点的应用层往返测量；不产生综合分。")
        Text("状态：${report?.status ?: result.status} · 节点 ${result.serverBase}")
        if (summary == null) {
            Text("未能读取本次原始响应性证据。不会用旧 Basic 指标补位，请保留诊断记录。")
        } else {
            Text("空闲 RTT P50：${value(summary.idleP50Ms)}")
            Text("下载期间 RTT P95：${value(summary.downloadP95Ms)}")
            Text("下载期间增量：${value(summary.downloadDeltaMs)}")
            Text("上传期间 RTT P95：${value(summary.uploadP95Ms)}")
            Text("上传期间增量：${value(summary.uploadDeltaMs)}")
            summary.stages.forEach { stage ->
                val label = when (stage.stage) { "idle" -> "空闲"; "download" -> "下载"; else -> "上传" }
                Text("$label · 有效 ${stage.validSampleCount} / 观察 ${stage.totalSampleCount}" +
                    (stage.missingReason?.let { " · 证据不足：$it" } ?: ""))
            }
        }
        Text("负载样本只采用完整位于该阶段、且探测期间记录到传输字节增加的 echo。未采用的样本和原因仍在导出中。")
        Text("上传字节表示客户端写入，不是远端接收证明；不证明线路饱和、拥塞根因或运营商质量。P95 仅描述本次有效样本，不代表总体95%保证。")
        Text("运行：${result.runId} · 方法：${report?.methodVersion ?: "不可用"}")
        Button(enabled = report != null && !exporting, modifier = Modifier.fillMaxWidth(), onClick = {
            val raw = result.responsivenessJson ?: return@Button
            exporting = true
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    Exporter.exportToDownloads(context, "aneb_loaded_${result.runId}.json", "application/json", raw)
                }
                exportStatus = if (outcome.ok) "已保存到下载/ANEB（${outcome.bytes}字节）"
                    else "导出失败，原始记录仍在本机：${outcome.error}"
                exporting = false
            }
        }) { Text(if (exporting) "正在保存…" else "导出负载响应性证据 JSON") }
        if (exportStatus.isNotEmpty()) Text(exportStatus)
    }
    }
}
