package com.aneb.probe.ui.prototype

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.data.PrototypeSavedCampaignReference
import com.aneb.probe.prototype.PrototypeNodeState
import com.aneb.probe.ui.components.AnebGradientCard
import com.aneb.probe.ui.components.AnebPageIntro
import com.aneb.probe.ui.components.AnebTopBar
import com.aneb.probe.ui.theme.AnebPalette
import com.aneb.probe.ui.theme.AnebTheme
import kotlinx.coroutines.CancellationException

internal data class PrototypeNodeErrorPresentation(val title: String, val detail: String)

internal fun prototypeNodeErrorPresentation(
    nodeState: PrototypeNodeState?,
    errorMessage: String?,
): PrototypeNodeErrorPresentation? = when (nodeState) {
    is PrototypeNodeState.Compatible -> null
    is PrototypeNodeState.ConnectedIncompatible -> PrototypeNodeErrorPresentation(
        title = "P007_CONTRACT_MISMATCH · 已连接，但协议不兼容",
        detail = nodeState.message + "\n\n" +
            "请使用同一发布包中的 APK 和服务端，再检查连接。" +
            "未启动测试，已保存结果未改变。",
    )
    null -> errorMessage?.let { detail ->
        PrototypeNodeErrorPresentation(
            title = "P006_NODE_UNREACHABLE · 节点不可达",
            detail = detail + "\n\n" +
                "请检查节点地址、同一局域网及启动器的防火墙说明。" +
                "然后重新检查连接。未启动测试，已保存结果未改变。",
        )
    }
}

@Composable
fun PrototypeModeScreen(
    nodeUrl: String,
    nodeState: PrototypeNodeState?,
    checkingNode: Boolean,
    errorMessage: String?,
    quickRunning: Boolean,
    quickAvailable: Boolean,
    quickStatusMessage: String?,
    liveExecution: PrototypeCampaignLiveExecutionPresentation?,
    showQuickCancel: Boolean,
    quickCancelEnabled: Boolean,
    onNodeUrlChange: (String) -> Unit,
    onCheckNode: () -> Unit,
    onStartQuick: () -> Unit,
    onStartAcceptance: () -> Unit,
    onCancelQuick: () -> Unit,
    canOpenSavedCampaigns: Boolean,
    loadSavedCampaigns: suspend () -> List<PrototypeSavedCampaignReference>,
    onOpenSavedCampaign: (String) -> Unit,
    onBack: () -> Unit,
) {
    val colors = AnebTheme.colors
    var launchState by remember { mutableStateOf(PrototypeCampaignLaunchState()) }
    var showSavedCampaigns by remember { mutableStateOf(false) }
    LaunchedEffect(canOpenSavedCampaigns) {
        if (!canOpenSavedCampaigns) showSavedCampaigns = false
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AnebPalette.Dark.DeepBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        AnebTopBar(showBack = true, onBack = onBack)
        AnebPageIntro(
            eyebrow = "Prototype 0.1",
            title = "应用层合成流对比",
            subtitle = "同一轮测试对比基线 Baseline、慢速 Slow、不稳定 Unstable 三种条件。",
        )
        Spacer(Modifier.height(18.dp))
        AnebGradientCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("这项测试测什么？", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                Text(
                    "测量手机到所选节点的应用层合成流表现，不是真实 AI 应用或模型推理测试。Quick 每种条件测 1 次（共 3 次），Acceptance 每种条件测 3 次（共 9 次）。不代表 AQS、无线时延、IP 丢包、运营商评级或 SLA。",
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    color = colors.muted,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedButton(
            onClick = { showSavedCampaigns = true },
            enabled = canOpenSavedCampaigns,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("已保存的测试")
        }
        Text(
            "打开本地结果，可导出备份或重试发布证据；不会开始新测试。",
            fontSize = 11.sp,
            lineHeight = 17.sp,
            color = colors.muted,
        )
        Spacer(Modifier.height(14.dp))
        Text("节点", fontSize = 9.sp, letterSpacing = 1.2.sp, color = colors.faint)
        Spacer(Modifier.height(7.dp))
        OutlinedTextField(
            value = nodeUrl,
            onValueChange = onNodeUrlChange,
            enabled = !quickRunning && !checkingNode,
            singleLine = true,
            label = { Text("节点地址") },
            placeholder = { Text("http://192.168.1.20:18088") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.ink,
                unfocusedTextColor = colors.ink,
                focusedBorderColor = colors.brand,
                unfocusedBorderColor = colors.hairline,
                focusedLabelColor = colors.brand,
                unfocusedLabelColor = colors.muted,
                cursorColor = colors.brand,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onCheckNode,
            enabled = nodeUrl.isNotBlank() && !checkingNode && !quickRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (checkingNode) "正在检查…" else "检查连接")
        }
        Spacer(Modifier.height(12.dp))
        when (nodeState) {
            is PrototypeNodeState.Compatible -> CompatibleNodeCard(nodeState)
            else -> prototypeNodeErrorPresentation(nodeState, errorMessage)?.let { error ->
                StatusCard(title = error.title, detail = error.detail, accent = colors.poor)
            }
        }
        Spacer(Modifier.height(18.dp))
        liveExecution?.let { live ->
            PrototypeLiveExecutionCard(live)
            Spacer(Modifier.height(12.dp))
        }
        if (quickStatusMessage != null && liveExecution == null) {
            StatusCard(
                title = "本轮测试",
                detail = quickStatusMessage,
                accent = colors.brand,
            )
            Spacer(Modifier.height(12.dp))
        }
        Button(
            onClick = {
                launchState = launchState.select(PrototypeCampaignLaunchMode.QUICK)
            },
            enabled = quickAvailable && nodeState?.canStartQuick == true && !quickRunning && !checkingNode,
            colors = ButtonDefaults.buttonColors(containerColor = colors.brand, contentColor = Color(0xFF03131A)),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Text(if (quickRunning) "测试正在运行" else "确认 Quick 快速测试 · 共 3 次", fontWeight = FontWeight.Bold)
        }
        if (showQuickCancel) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onCancelQuick,
                enabled = quickCancelEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (quickCancelEnabled) "取消本轮测试" else "正在取消…")
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                launchState = launchState.select(PrototypeCampaignLaunchMode.ACCEPTANCE)
            },
            enabled = quickAvailable && nodeState?.canStartQuick == true && !quickRunning && !checkingNode,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("确认 Acceptance 验收测试 · 共 9 次")
        }
        Spacer(Modifier.height(28.dp))
    }
    if (showSavedCampaigns && canOpenSavedCampaigns) {
        SavedPrototypeCampaignsDialog(
            loadCampaigns = loadSavedCampaigns,
            onSelect = { campaignId ->
                showSavedCampaigns = false
                onOpenSavedCampaign(campaignId)
            },
            onDismiss = { showSavedCampaigns = false },
        )
    }
    launchState.pending?.let { confirmation ->
        PrototypeCampaignLaunchConfirmationDialog(
            confirmation = confirmation,
            onDismiss = { launchState = launchState.cancel() },
            onConfirm = {
                launchState = launchState.confirm(
                    onStartQuick = onStartQuick,
                    onStartAcceptance = onStartAcceptance,
                )
            },
        )
    }
}

@Composable
private fun SavedPrototypeCampaignsDialog(
    loadCampaigns: suspend () -> List<PrototypeSavedCampaignReference>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AnebTheme.colors
    var campaigns by remember { mutableStateOf<List<PrototypeSavedCampaignReference>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        campaigns = null
        loadFailed = false
        try {
            campaigns = loadCampaigns()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF121728),
        title = { Text("已保存的测试", color = colors.ink) },
        text = {
            when {
                loadFailed -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("无法读取已保存结果，数据未改变。", color = colors.muted)
                    TextButton(onClick = { reload += 1 }) { Text("重试") }
                }
                campaigns == null -> Text("正在加载已保存结果…", color = colors.muted)
                campaigns.orEmpty().isEmpty() -> Text("此设备尚无已保存测试。", color = colors.muted)
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(campaigns.orEmpty(), key = { it.campaignId }) { campaign ->
                        OutlinedButton(
                            onClick = { onSelect(campaign.campaignId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(campaign.campaignId, fontSize = 12.sp, color = colors.ink)
                                Text("原节点：${campaign.nodeBaseUrl}", fontSize = 11.sp, color = colors.muted)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun PrototypeLiveExecutionCard(
    live: PrototypeCampaignLiveExecutionPresentation,
) {
    val colors = AnebTheme.colors
    AnebGradientCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("实时进度", fontSize = 9.sp, letterSpacing = 1.2.sp, color = colors.faint)
            Text(
                live.currentRunLabel,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = colors.ink,
            )
            LiveExecutionDetail("总进度", live.completedRunsLabel)
            LiveExecutionDetail("当前阶段", live.phaseLabel)
            live.ttftLabel?.let { value -> LiveExecutionDetail("首事件等待 TTFT", value) }
            live.eventRateLabel?.let { value -> LiveExecutionDetail("事件速率", value) }
            if (live.stallDetected) {
                Text(
                    "检测到停顿 Stall · 当前为临时值，以保存后的结果为准",
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.poor,
                )
            }
        }
    }
}

@Composable
private fun LiveExecutionDetail(label: String, value: String) {
    val colors = AnebTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 10.sp, color = colors.faint)
        Text(value, fontSize = 10.sp, color = colors.ink)
    }
}

@Composable
private fun PrototypeCampaignLaunchConfirmationDialog(
    confirmation: PrototypeCampaignLaunchConfirmation,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = AnebTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF121728),
        title = {
            Text(
                "确认 ${confirmation.modeLabel} 测试",
                color = colors.ink,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ConfirmationDetail("模式", confirmation.modeLabel)
                ConfirmationDetail("次数", confirmation.runCount.toString())
                ConfirmationDetail("预计耗时", confirmation.estimatedDuration)
                Text("固定顺序（B 基线 / S 慢速 / U 不稳定）", fontSize = 9.sp, letterSpacing = 1.2.sp, color = colors.faint)
                Text(
                    confirmation.runOrder,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    color = colors.ink,
                )
                Text(
                    confirmation.evidenceNotice,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    color = colors.muted,
                )
                Text(
                    confirmation.claimBoundary,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    color = colors.muted,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.brand,
                    contentColor = Color(0xFF03131A),
                ),
            ) {
                Text("开始测试", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun ConfirmationDetail(label: String, value: String) {
    val colors = AnebTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 10.sp, color = colors.faint)
        Text(value, fontSize = 10.sp, color = colors.ink)
    }
}

@Composable
private fun CompatibleNodeCard(state: PrototypeNodeState.Compatible) {
    val capability = state.capability
    val colors = AnebTheme.colors
    AnebGradientCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("兼容", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.good)
                Text(capability.serverVersion, fontSize = 10.sp, color = colors.muted)
            }
            DetailLine("负载", capability.workloadVersion)
            DetailLine("配置", capability.profileManifestSha256.take(12) + "…")
            DetailLine("测试条件", capability.conditions.joinToString(" · "))
            DetailLine("证据", capability.evidenceSchemaVersion)
            DetailLine("评分规则", capability.scorePolicyId)
        }
    }
}

@Composable
private fun StatusCard(title: String, detail: String, accent: Color) {
    val colors = AnebTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xA3121728), RoundedCornerShape(16.dp))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent)
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(detail, fontSize = 11.sp, lineHeight = 17.sp, color = colors.muted)
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    val colors = AnebTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 10.sp, color = colors.faint)
        Text(value, fontSize = 10.sp, color = colors.ink)
    }
}
