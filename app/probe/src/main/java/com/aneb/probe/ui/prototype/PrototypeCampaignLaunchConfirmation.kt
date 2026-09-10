package com.aneb.probe.ui.prototype

internal enum class PrototypeCampaignLaunchMode {
    QUICK,
    ACCEPTANCE,
}

internal data class PrototypeCampaignLaunchConfirmation(
    val mode: PrototypeCampaignLaunchMode,
    val modeLabel: String,
    val runCount: Int,
    val runOrder: String,
    val estimatedDuration: String,
    val evidenceNotice: String =
        "结果保存在本机，保存后可导出" +
            "未验证的五文件 ZIP 备份；它不是节点的正式报告。",
    val claimBoundary: String =
        "仅测手机到所选节点的应用层合成流，不代表真实 AI 表现、AQS、运营商评级或 SLA。",
)

internal data class PrototypeCampaignLaunchState(
    val pending: PrototypeCampaignLaunchConfirmation? = null,
) {
    fun select(mode: PrototypeCampaignLaunchMode): PrototypeCampaignLaunchState = copy(
        pending = when (mode) {
            PrototypeCampaignLaunchMode.QUICK -> PrototypeCampaignLaunchConfirmation(
                mode = mode,
                modeLabel = "Quick",
                runCount = 3,
                runOrder = "B1 → S1 → U1",
                estimatedDuration = "约 35 秒",
            )
            PrototypeCampaignLaunchMode.ACCEPTANCE -> PrototypeCampaignLaunchConfirmation(
                mode = mode,
                modeLabel = "Acceptance",
                runCount = 9,
                runOrder = "B1 → S1 → U1 → B2 → S2 → U2 → B3 → S3 → U3",
                estimatedDuration = "约 1 分 45 秒",
            )
        },
    )

    fun cancel(): PrototypeCampaignLaunchState = copy(pending = null)

    fun confirm(
        onStartQuick: () -> Unit,
        onStartAcceptance: () -> Unit,
    ): PrototypeCampaignLaunchState {
        when (pending?.mode) {
            PrototypeCampaignLaunchMode.QUICK -> onStartQuick()
            PrototypeCampaignLaunchMode.ACCEPTANCE -> onStartAcceptance()
            null -> return this
        }
        return cancel()
    }
}
