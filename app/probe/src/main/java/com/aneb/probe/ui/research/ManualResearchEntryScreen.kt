package com.aneb.probe.ui.research

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aneb.probe.research.*
import com.aneb.probe.ui.theme.AnebTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Manual historical observations, never a timer, app controller, or network measurement. */
@Composable
fun ManualResearchEntryScreen(store: ResearchRecordStore, onBack: () -> Unit, onSaved: (ResearchDocument) -> Unit) {
    val context = LocalContext.current
    val cache = remember(context) { ManualResearchDraftCache(context.getSharedPreferences("r1-manual-draft", Context.MODE_PRIVATE)) }
    val initial = remember(cache) { runCatching { cache.load() ?: ManualResearchDraft() } }
    var draft by remember(cache) { mutableStateOf(initial.getOrElse { ManualResearchDraft() }) }
    var loadError by remember(cache) { mutableStateOf(initial.exceptionOrNull()?.message) }
    var message by remember { mutableStateOf<String?>(null) }
    var activeAttempt by rememberSaveable { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var confirmSave by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun change(next: ManualResearchDraft) {
        if (busy || loadError != null) return
        try { cache.save(next); draft = next; message = null }
        catch (_: Exception) { message = "草稿暂存失败；当前页面内容仍保留，请勿离开。"; draft = next }
    }
    fun attemptChange(next: ManualResearchAttempt) {
        change(draft.copy(attempts = draft.attempts.toMutableList().apply { this[activeAttempt] = next }))
    }
    fun leaveWithDraft() {
        if (busy) return
        if (loadError != null) { onBack(); return }
        try { cache.save(draft); onBack() }
        catch (_: Exception) { message = "草稿暂存失败，尚未离开；请重试，或明确选择丢弃草稿。" }
    }
    BackHandler { leaveWithDraft() }
    val colors = AnebTheme.colors
    Column(Modifier.fillMaxSize().background(colors.background).padding(horizontal = 16.dp).imePadding()) {
        Row {
            TextButton(onClick = { leaveWithDraft() }, enabled = !busy) { Text("返回 · 保留草稿") }
            TextButton(onClick = { confirmDiscard = true }, enabled = !busy) { Text("丢弃草稿") }
        }
        Text("手工研究记录", style = MaterialTheme.typography.headlineSmall, color = colors.ink)
        Text("记录已有 App 可见观察，不是网络协议测量。不启动其它 App、不计时、不采集。勿填写密码或验证码。", color = colors.muted)
        Text("草稿自动暂存在本机，重新进入此表可继续；正式保存前不计作研究记录。", color = colors.muted, style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        (loadError ?: message)?.let { Text(it, color = colors.fair) }
        if (loadError == null) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    ManualSection("批次、App 与固定动作") {
                        ManualChoice("来源", draft.recordKind, listOf("" to "未选择 · 只留草稿", "SAMPLE" to "SAMPLE 虚构演示", "OBSERVED" to "OBSERVED 已有现场来源声明"), !busy) { change(draft.copy(recordKind = it.orEmpty(), sourceConfirmed = false)) }
                        SelectionContainer { Text(ManualResearchDraft.ACTION_TEXT, style = MaterialTheme.typography.bodySmall) }
                        Text("沿当前文本研究卡，历史动作不一致请勿伪填为此动作；三次槽位保留失败和未执行。", style = MaterialTheme.typography.bodySmall)
                        if (draft.recordKind == "OBSERVED") {
                            Row {
                                Checkbox(draft.sourceConfirmed, { change(draft.copy(sourceConfirmed = it)) }, enabled = !busy)
                                Text("我声明这是已有现场观察/未执行事实，且动作与上文一致；本工具未核实来源或媒体，不代表验收通过。")
                            }
                        }
                        ManualField("App 名称（必填）", draft.appName, !busy) { change(draft.copy(appName = it)) }
                        ManualField("历史版本（未知留空）", draft.appVersion, !busy) { change(draft.copy(appVersion = it)) }
                        ManualField("历史模型/模式（未知留空）", draft.modelMode, !busy) { change(draft.copy(modelMode = it)) }
                        ManualField("当时设备（未知留空）", draft.device, !busy) { change(draft.copy(device = it)) }
                        ManualField("观察日期时间及已知时区（不自动填现在）", draft.observedAt, !busy) { change(draft.copy(observedAt = it)) }
                        ManualField("当时网络描述（未知留空）", draft.networkDescription, !busy) { change(draft.copy(networkDescription = it)) }
                        Row {
                            Checkbox(draft.unmodifiedConditionConfirmed, { change(draft.copy(unmodifiedConditionConfirmed = it)) }, enabled = !busy)
                            Text("已知符合 C0_existing_unmodified（当时未人为改变网络）；否则保留未知，不自动推断。")
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(3) { index -> FilterChip(selected = activeAttempt == index, onClick = { activeAttempt = index }, enabled = !busy, label = { Text("第 ${index + 1} 次") }) }
                    }
                    Text("尝试 ID：manual-${draft.batchId}-${activeAttempt + 1}", color = colors.muted, style = MaterialTheme.typography.bodySmall)
                }
                val attempt = draft.attempts[activeAttempt]
                item {
                    ManualSection("是否发送与观察结局") {
                        ManualBoolean("当时是否实际发送", attempt.executed, !busy) { attemptChange(attempt.copy(executed = it)) }
                        ManualChoice("结局", attempt.status, statusLabels, !busy) { attemptChange(attempt.copy(status = it.orEmpty())) }
                        Text("已发送但缺 T0 仍为已发送；未发送用未执行。不会随切换自动清除已填事件。", style = MaterialTheme.typography.bodySmall)
                        ManualChoice("可见完成", attempt.visibleCompletion, observationLabels, !busy) { attemptChange(attempt.copy(visibleCompletion = it)) }
                        ManualChoice("指令满足（独立于完成）", attempt.instructionFollowing, observationLabels, !busy) { attemptChange(attempt.copy(instructionFollowing = it)) }
                        ManualField("缺失/来源说明（必填，未执行需写事实或演示原因）", attempt.reason, !busy) { attemptChange(attempt.copy(reason = it)) }
                        ManualField("本地现场引用及说明（OBSERVED必填；SAMPLE留空）", attempt.evidenceReference, !busy) { attemptChange(attempt.copy(evidenceReference = it)) }
                        ManualBoolean("已知证据存在？不由引用自动推定", attempt.evidenceExists, !busy) { attemptChange(attempt.copy(evidenceExists = it)) }
                        ManualField("已取得的媒体 SHA-256（否则留空；不自动计算）", attempt.evidenceHash, !busy) { attemptChange(attempt.copy(evidenceHash = it)) }
                    }
                }
                item {
                    ManualSection("已有时间轴与锚点 · 无映射不要填毫秒") {
                        ManualField("历史时钟来源", attempt.clockSource, !busy) { attemptChange(attempt.copy(clockSource = it)) }
                        ManualField("视频/时间轴标识", attempt.videoId, !busy) { attemptChange(attempt.copy(videoId = it)) }
                        ManualField("同条时钟域标识", attempt.clockDomain, !busy) { attemptChange(attempt.copy(clockDomain = it)) }
                        ManualField("已有帧→毫秒映射说明（不猜 fps）", attempt.clockMapping, !busy) { attemptChange(attempt.copy(clockMapping = it)) }
                        ManualField("当时发送锚", attempt.sendAnchor, !busy) { attemptChange(attempt.copy(sendAnchor = it)) }
                        ManualField("当时首反馈类型", attempt.feedbackAnchor, !busy) { attemptChange(attempt.copy(feedbackAnchor = it)) }
                        ManualField("最终正文区域（排除思考/状态）", attempt.bodyAnchor, !busy) { attemptChange(attempt.copy(bodyAnchor = it)) }
                    }
                }
                eventLabels.forEach { (name, label) -> item(key = "event-$activeAttempt-$name") {
                    ManualSection(label) {
                        ManualRangeFields(attempt.events[name] ?: ManualResearchRange(), !busy, true) { value ->
                            attemptChange(attempt.copy(events = attempt.events + (name to value)))
                        }
                    }
                } }
                item {
                    ManualSection("完成确认依据（不自动判断或计算）") {
                        ManualField("已有稳定窗口/退出生成的观察依据", attempt.completionBasis, !busy) { attemptChange(attempt.copy(completionBasis = it)) }
                        ManualBoolean("正文连续可见", attempt.bodyContinuouslyVisible, !busy) { attemptChange(attempt.copy(bodyContinuouslyVisible = it)) }
                        ManualBoolean("界面正常退出生成", attempt.uiExitedNormally, !busy) { attemptChange(attempt.copy(uiExitedNormally = it)) }
                        Text("最后正文只是候选，不等于完成。缺证据请选不确定；实际时长交给分析，不在这里填 TTFR/RPI。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                attempt.intervals.forEachIndexed { index, interval -> item(key = "interval-$activeAttempt-$index") {
                    ManualSection("原始观察区间 ${index + 1}") {
                        fun update(value: ManualResearchInterval) = attemptChange(attempt.copy(intervals = attempt.intervals.toMutableList().apply { this[index] = value }))
                        ManualChoice("类型", interval.kind, listOf("gap" to "观察缺口", "update_gap" to "相邻正文更新间隔", "unclosed_tail" to "未闭合尾段（不是完整停顿）"), !busy) { update(interval.copy(kind = it.orEmpty())) }
                        Text("起点（ms区间）")
                        ManualRangeFields(interval.start, !busy, false) { update(interval.copy(start = it)) }
                        Text("终点（ms区间）")
                        ManualRangeFields(interval.end, !busy, false) { update(interval.copy(end = it)) }
                        ManualBoolean("是否已恢复", interval.resumed, !busy) { update(interval.copy(resumed = it)) }
                        ManualBoolean("是否完全可见", interval.fullyVisible, !busy) { update(interval.copy(fullyVisible = it)) }
                        TextButton(onClick = { attemptChange(attempt.copy(intervals = attempt.intervals.filterIndexed { i, _ -> i != index })) }, enabled = !busy) { Text("移除此区间输入（请保留缺失说明）") }
                    }
                } }
                item {
                    Text("未添加区间只表示未录入，不表示没有停顿。最终静默不当作更新间隔。", color = colors.muted)
                    message?.let { Text(it, color = colors.fair) }
                    OutlinedButton(enabled = !busy && attempt.intervals.size < 20, onClick = { attemptChange(attempt.copy(intervals = attempt.intervals + ManualResearchInterval())) }) { Text("添加已有观察区间（最多20项）") }
                    Button(enabled = !busy, onClick = {
                        try { draft.toRecordBytes(); cache.save(draft); confirmSave = true }
                        catch (e: ResearchImportException) { message = e.message }
                        catch (_: Exception) { message = "草稿检查失败，内容保留；请检查字段或本地存储。" }
                    }) { Text("检查并正式保存三个槽位") }
                }
            }
        }
    }
    if (confirmSave) AlertDialog(
        onDismissRequest = { confirmSave = false },
        title = { Text("保存 ${draft.recordKind} 研究记录？") },
        text = { Text("只保存人工录入的已有观察。OBSERVED仍是未核验声明，不证明网络原因或测试成功；原文保存在本机，之后可重开和确认导出。") },
        confirmButton = { TextButton(onClick = {
            confirmSave = false; busy = true
            scope.launch {
                try { val saved = withContext(Dispatchers.IO) { cache.saveRecord(store) }; onSaved(saved) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = if (e is ResearchImportException) e.message else "正式保存失败，草稿仍保留，请检查本地存储后重试。" }
                finally { busy = false }
            }
        }) { Text("确认保存") } },
        dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("继续编辑") } },
    )
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false }, title = { Text("明确丢弃未完成草稿？") },
        text = { Text("只清除此手工草稿，不能撤销；不会删除已保存原文或分析。") },
        confirmButton = { TextButton(onClick = { cache.clear(); loadError = null; confirmDiscard = false; onBack() }) { Text("丢弃草稿") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("保留") } },
    )
}

@Composable
private fun ManualSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium); content()
    } }
}

@Composable
private fun ManualField(label: String, value: String, enabled: Boolean, limit: Int = 2000, change: (String) -> Unit) {
    OutlinedTextField(value, { if (it.length <= limit) change(it) }, enabled = enabled,
        label = { Text(label) }, supportingText = { Text("${value.length}/$limit · 留空保留未知，不填0替代") }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ManualChoice(label: String, value: String?, choices: List<Pair<String?, String>>, enabled: Boolean, change: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("$label：${choices.firstOrNull { it.first == value }?.second ?: "未选择"}")
        }
        DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            choices.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { change(key); open = false }) }
        }
    }
}

@Composable
private fun ManualBoolean(label: String, value: Boolean?, enabled: Boolean, change: (Boolean?) -> Unit) {
    ManualChoice(label, value?.toString(), listOf(null to "未知/未确认", "true" to "是", "false" to "否"), enabled) { change(it?.toBooleanStrictOrNull()) }
}

@Composable
private fun ManualRangeFields(value: ManualResearchRange, enabled: Boolean, frames: Boolean, change: (ManualResearchRange) -> Unit) {
    ManualField("毫秒下界", value.low, enabled, 64) { change(value.copy(low = it)) }
    ManualField("毫秒上界", value.high, enabled, 64) { change(value.copy(high = it)) }
    if (frames) {
        ManualField("已有帧下界（可选，无映射时毫秒留空）", value.frameLow, enabled, 32) { change(value.copy(frameLow = it)) }
        ManualField("已有帧上界（可选）", value.frameHigh, enabled, 32) { change(value.copy(frameHigh = it)) }
        ManualField("此事件缺失/不确定原因（留空沿总说明）", value.reason, enabled) { change(value.copy(reason = it)) }
    }
}

private val statusLabels = listOf("not_run" to "未执行", "completed" to "记录为完成", "failed" to "失败", "cancelled" to "已取消", "incomplete" to "未完成/截断")
private val observationLabels = listOf(null to "未选择/未发送不适用", "yes" to "是", "no" to "否", "uncertain" to "不确定")
private val eventLabels = listOf("send" to "发送 T0", "first_feedback" to "首个反馈", "first_content" to "首个正文", "last_content" to "最后正文候选", "complete_confirm" to "完成确认时刻", "observation_end" to "观察结束")
