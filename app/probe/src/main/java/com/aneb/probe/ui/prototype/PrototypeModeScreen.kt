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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.prototype.PrototypeNodeState
import com.aneb.probe.ui.components.AnebGradientCard
import com.aneb.probe.ui.components.AnebPageIntro
import com.aneb.probe.ui.components.AnebTopBar
import com.aneb.probe.ui.theme.AnebPalette
import com.aneb.probe.ui.theme.AnebTheme

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
    onBack: () -> Unit,
) {
    val colors = AnebTheme.colors
    var launchState by remember { mutableStateOf(PrototypeCampaignLaunchState()) }
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
            title = "Synthetic streaming comparison",
            subtitle = "Compare Baseline, Slow and Unstable conditions inside one controlled campaign.",
        )
        Spacer(Modifier.height(18.dp))
        AnebGradientCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Synthetic application-layer test", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                Text(
                    "This mode measures app-to-node streaming behavior. It is not AQS, radio latency, packet loss, an operator rating or an SLA.",
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    color = colors.muted,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("NODE", fontSize = 9.sp, letterSpacing = 1.2.sp, color = colors.faint)
        Spacer(Modifier.height(7.dp))
        OutlinedTextField(
            value = nodeUrl,
            onValueChange = onNodeUrlChange,
            enabled = !quickRunning && !checkingNode,
            singleLine = true,
            label = { Text("Node base URL") },
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
            Text(if (checkingNode) "Checking…" else "Test connection")
        }
        Spacer(Modifier.height(12.dp))
        when (nodeState) {
            is PrototypeNodeState.Compatible -> CompatibleNodeCard(nodeState)
            is PrototypeNodeState.ConnectedIncompatible -> StatusCard(
                title = "Connected, incompatible",
                detail = nodeState.message,
                accent = colors.poor,
            )
            null -> errorMessage?.let { detail ->
                StatusCard(title = "Node unavailable", detail = detail, accent = colors.poor)
            }
        }
        Spacer(Modifier.height(18.dp))
        liveExecution?.let { live ->
            PrototypeLiveExecutionCard(live)
            Spacer(Modifier.height(12.dp))
        }
        if (quickStatusMessage != null && liveExecution == null) {
            StatusCard(
                title = "Campaign",
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
            Text(if (quickRunning) "Campaign is running" else "Review Quick · 3 runs", fontWeight = FontWeight.Bold)
        }
        if (showQuickCancel) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onCancelQuick,
                enabled = quickCancelEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (quickCancelEnabled) "Cancel campaign" else "Cancelling…")
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
            Text("Review Acceptance · 9 runs")
        }
        Spacer(Modifier.height(28.dp))
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
private fun PrototypeLiveExecutionCard(
    live: PrototypeCampaignLiveExecutionPresentation,
) {
    val colors = AnebTheme.colors
    AnebGradientCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("LIVE EXECUTION", fontSize = 9.sp, letterSpacing = 1.2.sp, color = colors.faint)
            Text(
                live.currentRunLabel,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = colors.ink,
            )
            LiveExecutionDetail("Overall", live.completedRunsLabel)
            LiveExecutionDetail("Phase", live.phaseLabel)
            live.ttftLabel?.let { value -> LiveExecutionDetail("TTFT", value) }
            live.eventRateLabel?.let { value -> LiveExecutionDetail("Rate", value) }
            if (live.stallDetected) {
                Text(
                    "Stall detected · provisional until the saved result is finalized",
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
                "Confirm ${confirmation.modeLabel} campaign",
                color = colors.ink,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ConfirmationDetail("Mode", confirmation.modeLabel)
                ConfirmationDetail("Runs", confirmation.runCount.toString())
                ConfirmationDetail("Estimated time", confirmation.estimatedDuration)
                Text("FIXED ORDER", fontSize = 9.sp, letterSpacing = 1.2.sp, color = colors.faint)
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
                Text("Start campaign", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
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
                Text("Compatible", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.good)
                Text(capability.serverVersion, fontSize = 10.sp, color = colors.muted)
            }
            DetailLine("Workload", capability.workloadVersion)
            DetailLine("Profile", capability.profileManifestSha256.take(12) + "…")
            DetailLine("Conditions", capability.conditions.joinToString(" · "))
            DetailLine("Evidence", capability.evidenceSchemaVersion)
            DetailLine("Score policy", capability.scorePolicyId)
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
