package com.aneb.probe.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import com.aneb.probe.ui.theme.AnebTheme

internal data class RadioPermissionState(
    val phoneStateGranted: Boolean,
    val coarseLocationGranted: Boolean,
    val fineLocationGranted: Boolean,
) {
    val hasFullRadioEvidence: Boolean
        get() = phoneStateGranted && fineLocationGranted

    val deniedSummary: String
        get() = when {
            !phoneStateGranted && !fineLocationGranted -> "电话与精确位置权限均未完整授予"
            !phoneStateGranted -> "电话权限未授予"
            coarseLocationGranted && !fineLocationGranted -> "当前仅授予了大致位置"
            !fineLocationGranted -> "精确位置权限未授予"
            else -> "无线层权限已完整授予"
        }
}

internal enum class RadioPermissionPurpose { START_TEST, DRIVE_TEST }

internal enum class RadioPermissionStage { RATIONALE, DENIED }

internal data class RadioPermissionPrompt(
    val purpose: RadioPermissionPurpose,
    val stage: RadioPermissionStage,
    val state: RadioPermissionState,
)

/** 首装/拒绝后的无线层权限说明；AQS 主测量可在无权限时继续，路测则必须精确位置。 */
@Composable
internal fun RadioPermissionDialog(
    prompt: RadioPermissionPrompt,
    onRequest: () -> Unit,
    onContinueLimited: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AnebTheme.colors
    val isTest = prompt.purpose == RadioPermissionPurpose.START_TEST
    val title = when (prompt.stage) {
        RadioPermissionStage.RATIONALE -> if (isTest) "允许无线层归因？" else "路测需要精确位置"
        RadioPermissionStage.DENIED -> "无线层权限未完整授予"
    }
    val body = when {
        prompt.stage == RadioPermissionStage.DENIED && isTest ->
            "${prompt.state.deniedSummary}。你仍可完成 AQS 测试，但运营商、小区、信号与制式只会显示为证据不足。"
        prompt.stage == RadioPermissionStage.DENIED ->
            "${prompt.state.deniedSummary}。路测坐标不会启动；请在系统设置中授予电话与精确位置权限。"
        isTest ->
            "ANEB 使用电话与精确位置权限读取当前数据卡、小区和信号。不会读取通话、联系人或 IMSI；拒绝后仍可用低置信无线归因继续测试。"
        else ->
            "路测会以 1Hz 记录坐标到本机 Room，并仅随本地导出使用；坐标不会进入 /results 上报体。"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = colors.ink) },
        text = { Text(body, color = colors.muted, fontSize = 13.sp) },
        confirmButton = {
            TextButton(onClick = if (prompt.stage == RadioPermissionStage.RATIONALE) onRequest else onOpenSettings) {
                Text(if (prompt.stage == RadioPermissionStage.RATIONALE) "授权" else "系统设置")
            }
        },
        dismissButton = {
            Row {
                if (isTest) {
                    TextButton(onClick = onContinueLimited) { Text("低置信继续") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
        containerColor = colors.surface,
        titleContentColor = colors.ink,
        textContentColor = colors.muted,
    )
}
