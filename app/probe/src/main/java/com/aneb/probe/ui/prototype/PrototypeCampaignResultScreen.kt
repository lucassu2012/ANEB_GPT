package com.aneb.probe.ui.prototype

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.ui.components.AnebGradientCard
import com.aneb.probe.ui.components.AnebPageIntro
import com.aneb.probe.ui.components.AnebTopBar
import com.aneb.probe.ui.theme.AnebPalette
import com.aneb.probe.ui.theme.AnebTheme

internal enum class PrototypeCampaignResultActionState {
    Idle,
    Exporting,
    PreparingShare,
    Saved,
    ShareOpened,
    ShareUnavailable,
    Failed,
    Publishing,
    Published,
    PublicationFailed,
}

internal data class PrototypeCampaignResultActionPresentation(
    val actionsEnabled: Boolean,
    val message: String?,
)

internal fun prototypeCampaignResultActionPresentation(
    state: PrototypeCampaignResultActionState,
): PrototypeCampaignResultActionPresentation = PrototypeCampaignResultActionPresentation(
    actionsEnabled = state != PrototypeCampaignResultActionState.Exporting &&
        state != PrototypeCampaignResultActionState.PreparingShare &&
        state != PrototypeCampaignResultActionState.Publishing,
    message = when (state) {
        PrototypeCampaignResultActionState.Idle -> null
        PrototypeCampaignResultActionState.Exporting -> "正在导出…"
        PrototypeCampaignResultActionState.PreparingShare -> "正在准备分享…"
        PrototypeCampaignResultActionState.Saved -> "已保存到 Downloads/ANEB"
        PrototypeCampaignResultActionState.ShareOpened -> "已打开分享面板"
        PrototypeCampaignResultActionState.ShareUnavailable -> "已保存，但暂时无法分享"
        PrototypeCampaignResultActionState.Failed -> "导出失败"
        PrototypeCampaignResultActionState.Publishing -> "正在重试发布证据…"
        PrototypeCampaignResultActionState.Published -> "原节点已确认收到并发布证据；不表示测试成功。"
        PrototypeCampaignResultActionState.PublicationFailed ->
            "P018 · 发布失败，本地证据已保留。恢复原节点后重试。"
    },
)

internal fun prototypeCampaignResultActions(
    loadState: PrototypeCampaignResultLoadState,
    actionState: PrototypeCampaignResultActionState,
): PrototypeCampaignResultActionPresentation? = when (loadState) {
    is PrototypeCampaignResultLoadState.Loading,
    is PrototypeCampaignResultLoadState.Unavailable,
    -> null
    is PrototypeCampaignResultLoadState.Ready ->
        prototypeCampaignResultActionPresentation(actionState)
}

@Composable
internal fun PrototypeCampaignResultScreen(
    loadState: PrototypeCampaignResultLoadState,
    onBack: () -> Unit,
    actionState: PrototypeCampaignResultActionState = PrototypeCampaignResultActionState.Idle,
    onExport: () -> Unit = {},
    onShare: () -> Unit = {},
    onRetryPublication: () -> Unit = {},
) {
    val actionPresentation = prototypeCampaignResultActions(loadState, actionState)
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AnebPalette.Dark.DeepBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        AnebTopBar(showBack = true, onBack = onBack)
        when (loadState) {
            is PrototypeCampaignResultLoadState.Loading -> ResultState(
                title = "正在加载测试结果",
                detail = "正在校验本地保存的测试及证据…",
            )
            is PrototypeCampaignResultLoadState.Unavailable -> ResultState(
                title = "测试结果不可用",
                detail = "本地测试结果不存在或未通过校验。",
            )
            is PrototypeCampaignResultLoadState.Ready -> ReadyResult(
                presentation = loadState.presentation,
                publicationWarning = loadState.publicationWarning,
                actionPresentation = requireNotNull(actionPresentation),
                onExport = onExport,
                onShare = onShare,
                onRetryPublication = onRetryPublication,
                onBack = onBack,
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ResultState(title: String, detail: String) {
    val colors = AnebTheme.colors
    AnebPageIntro(eyebrow = "Prototype 0.1", title = title, subtitle = detail)
    Spacer(Modifier.height(18.dp))
    AnebGradientCard(Modifier.fillMaxWidth()) {
        Text(
            detail,
            color = colors.muted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun ReadyResult(
    presentation: PrototypeCampaignResultPresentation,
    publicationWarning: String?,
    actionPresentation: PrototypeCampaignResultActionPresentation,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onRetryPublication: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = AnebTheme.colors
    AnebPageIntro(
        eyebrow = "Prototype 0.1",
        title = "测试结果",
        subtitle = "${presentation.status} · ${presentation.campaignMode}",
    )
    publicationWarning?.let {
        Spacer(Modifier.height(12.dp))
        AnebGradientCard(Modifier.fillMaxWidth()) {
            Text(
                "P018 · 证据发布失败，本地测试结果仍已保存" +
                    "，可导出备份。",
                color = colors.brand,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
    Spacer(Modifier.height(18.dp))
    AnebGradientCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text(
                presentation.evidenceBadge,
                color = colors.brand,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                presentation.integrity,
                color = colors.brand,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Count("已尝试", presentation.attemptedRuns)
                Count("成功", presentation.successfulRuns)
                Count("失败", presentation.failedRuns)
                Count("未开始", presentation.notStartedRuns)
            }
        }
    }
    presentation.blockingError?.let { error ->
        Spacer(Modifier.height(12.dp))
        AnebGradientCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    error.code,
                    color = colors.brand,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    error.title,
                    color = colors.ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(error.cause, color = colors.ink, fontSize = 11.sp, lineHeight = 17.sp)
                Text(error.detail, color = colors.muted, fontSize = 10.sp, lineHeight = 15.sp)
                Text(
                    error.action,
                    color = colors.brand,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    AnebGradientCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                presentation.confidenceExplanation,
                color = colors.ink,
                fontSize = 11.sp,
                lineHeight = 17.sp,
            )
            Text(
                presentation.disclosure,
                color = colors.muted,
                fontSize = 11.sp,
                lineHeight = 17.sp,
            )
        }
    }
    Spacer(Modifier.height(18.dp))
    Text("各条件结果", color = colors.faint, fontSize = 9.sp, letterSpacing = 1.2.sp)
    Spacer(Modifier.height(8.dp))
    presentation.conditions.forEach { condition ->
        ConditionCard(condition, presentation.rpiLabel)
        Spacer(Modifier.height(12.dp))
    }
    Spacer(Modifier.height(6.dp))
    actionPresentation.message?.let { message ->
        Text(
            message,
            color = colors.muted,
            fontSize = 11.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(10.dp))
    }
    Text(
        "恢复本轮测试的原节点：${presentation.publicationNodeUrl}\n" +
            "重试仅发布已保存证据，不会重新测量。" +
            "导出和分享仅提供未验证的五文件设备备份，不是节点的正式报告。",
        color = colors.muted,
        fontSize = 11.sp,
        lineHeight = 17.sp,
    )
    Spacer(Modifier.height(10.dp))
    Button(
        onClick = onRetryPublication,
        enabled = actionPresentation.actionsEnabled,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Text("重试发布证据", fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = onExport,
        enabled = actionPresentation.actionsEnabled,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Text("导出 ZIP", fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onShare,
        enabled = actionPresentation.actionsEnabled,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Text("分享 ZIP", fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onBack,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Text("返回", fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Count(label: String, value: String) {
    val colors = AnebTheme.colors
    Column {
        Text(value, color = colors.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(label, color = colors.faint, fontSize = 9.sp)
    }
}

@Composable
private fun ConditionCard(
    condition: PrototypeConditionResultPresentation,
    rpiLabel: String,
) {
    val colors = AnebTheme.colors
    AnebGradientCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(condition.title, color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("证据完整度 Confidence · ${condition.confidence}", color = colors.muted, fontSize = 10.sp)
            }
            MetricRow("首事件等待 TTFT", condition.ttft)
            MetricRow("完成耗时 Completion", condition.completion)
            MetricRow("事件速率 Event rate", condition.eventRate)
            MetricRow("停顿次数 Stall", condition.stallCount)
            MetricRow("停顿时长 Stall", condition.stallDuration)
            MetricRow("成功率 Success rate", condition.successRate)
            Spacer(Modifier.height(2.dp))
            Text(rpiLabel, color = colors.faint, fontSize = 9.sp, lineHeight = 13.sp)
            Text(condition.rpi, color = colors.brand, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            condition.metricNullReason?.let { reason ->
                Text("指标缺失原因 · $reason", color = colors.muted, fontSize = 10.sp)
            }
            if (condition.rpiNullReasons.isNotEmpty()) {
                Text(
                    "RPI 缺失原因 · ${condition.rpiNullReasons.joinToString(", ")}",
                    color = colors.muted,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    val colors = AnebTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), color = colors.faint, fontSize = 11.sp)
        Text(value, color = colors.ink, fontSize = 11.sp)
    }
}
