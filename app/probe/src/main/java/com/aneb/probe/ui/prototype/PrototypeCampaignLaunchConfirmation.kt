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
        "Results are stored locally in Room. After a result is saved, you can export " +
            "an unverified ZIP on this device.",
    val claimBoundary: String =
        "Synthetic app-layer measurement only — not AQS, an operator rating or an SLA.",
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
                estimatedDuration = "About 35 seconds",
            )
            PrototypeCampaignLaunchMode.ACCEPTANCE -> PrototypeCampaignLaunchConfirmation(
                mode = mode,
                modeLabel = "Acceptance",
                runCount = 9,
                runOrder = "B1 → S1 → U1 → B2 → S2 → U2 → B3 → S3 → U3",
                estimatedDuration = "About 1 minute 45 seconds",
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
