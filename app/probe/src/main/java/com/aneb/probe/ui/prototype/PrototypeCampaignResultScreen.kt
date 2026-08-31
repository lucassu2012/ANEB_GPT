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
}

internal data class PrototypeCampaignResultActionPresentation(
    val actionsEnabled: Boolean,
    val message: String?,
)

internal fun prototypeCampaignResultActionPresentation(
    state: PrototypeCampaignResultActionState,
): PrototypeCampaignResultActionPresentation = PrototypeCampaignResultActionPresentation(
    actionsEnabled = state != PrototypeCampaignResultActionState.Exporting &&
        state != PrototypeCampaignResultActionState.PreparingShare,
    message = when (state) {
        PrototypeCampaignResultActionState.Idle -> null
        PrototypeCampaignResultActionState.Exporting -> "Exporting…"
        PrototypeCampaignResultActionState.PreparingShare -> "Preparing share…"
        PrototypeCampaignResultActionState.Saved -> "Saved to Downloads/ANEB"
        PrototypeCampaignResultActionState.ShareOpened -> "Share sheet opened"
        PrototypeCampaignResultActionState.ShareUnavailable -> "Saved, but share is unavailable"
        PrototypeCampaignResultActionState.Failed -> "Export failed"
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
                title = "Loading campaign result",
                detail = "Validating the locally persisted campaign graph…",
            )
            is PrototypeCampaignResultLoadState.Unavailable -> ResultState(
                title = "Campaign result unavailable",
                detail = "The local campaign result is missing or failed validation.",
            )
            is PrototypeCampaignResultLoadState.Ready -> ReadyResult(
                presentation = loadState.presentation,
                actionPresentation = requireNotNull(actionPresentation),
                onExport = onExport,
                onShare = onShare,
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
    actionPresentation: PrototypeCampaignResultActionPresentation,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = AnebTheme.colors
    AnebPageIntro(
        eyebrow = "Prototype 0.1",
        title = "Campaign result",
        subtitle = "${presentation.status} · ${presentation.campaignMode}",
    )
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
                Count("Attempted", presentation.attemptedRuns)
                Count("Successful", presentation.successfulRuns)
                Count("Failed", presentation.failedRuns)
                Count("Not started", presentation.notStartedRuns)
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
    Text("CONDITIONS", color = colors.faint, fontSize = 9.sp, letterSpacing = 1.2.sp)
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
    Button(
        onClick = onExport,
        enabled = actionPresentation.actionsEnabled,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Text("Export ZIP", fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onShare,
        enabled = actionPresentation.actionsEnabled,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Text("Share ZIP", fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onBack,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Text("Back", fontWeight = FontWeight.SemiBold)
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(condition.title, color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("Confidence · ${condition.confidence}", color = colors.muted, fontSize = 10.sp)
            }
            MetricRow("TTFT", condition.ttft)
            MetricRow("Completion", condition.completion)
            MetricRow("Event rate", condition.eventRate)
            MetricRow("Stall count", condition.stallCount)
            MetricRow("Stall duration", condition.stallDuration)
            MetricRow("Success rate", condition.successRate)
            Spacer(Modifier.height(2.dp))
            Text(rpiLabel, color = colors.faint, fontSize = 9.sp, lineHeight = 13.sp)
            Text(condition.rpi, color = colors.brand, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            condition.metricNullReason?.let { reason ->
                Text("Metric null reason · $reason", color = colors.muted, fontSize = 10.sp)
            }
            if (condition.rpiNullReasons.isNotEmpty()) {
                Text(
                    "RPI null reason · ${condition.rpiNullReasons.joinToString(", ")}",
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
        Text(label, color = colors.faint, fontSize = 10.sp)
        Text(value, color = colors.ink, fontSize = 11.sp)
    }
}
