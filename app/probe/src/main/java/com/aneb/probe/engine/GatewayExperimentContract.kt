package com.aneb.probe.engine

import com.aneb.probe.net.AnebGatewayClient

internal object GatewayExperimentContract {
    data class Expected(
        val runId: String,
        val profileRef: String,
        val profileFingerprint: String,
        val impairmentLayer: String,
    )

    private val experimentIdPattern = Regex("^[A-Za-z0-9._-]{1,128}$")
    private val phases = setOf("scheduled", "active", "clearing", "completed", "failed", "cleanup_failed")
    private val transitions = setOf("scheduled", "active", "clearing")

    /**
     * Minimal identity check used before the full response contract is trusted. Once this passes,
     * the experiment belongs to this run and its safe ID may be retained for fail-safe cleanup.
     */
    fun requireOwned(experiment: AnebGatewayClient.Experiment, expected: Expected) {
        require(experiment.experimentId.matches(experimentIdPattern)) { "gateway_experiment_id_invalid" }
        require(experiment.runId == expected.runId) { "gateway_run_id_mismatch" }
        require(experiment.profileRef == expected.profileRef) { "gateway_profile_ref_mismatch" }
        require(experiment.profileFingerprint == expected.profileFingerprint) { "gateway_profile_fingerprint_mismatch" }
        require(experiment.impairmentLayer == expected.impairmentLayer) { "gateway_impairment_layer_mismatch" }
        require(experiment.claimScope == "dedicated_gateway_ip_forwarding") { "gateway_claim_scope_mismatch" }
    }

    fun isOwnedBy(experiment: AnebGatewayClient.Experiment?, expected: Expected): Boolean =
        experiment != null && runCatching { requireOwned(experiment, expected) }.isSuccess

    fun validate(experiment: AnebGatewayClient.Experiment, expected: Expected) {
        requireOwned(experiment, expected)
        require(experiment.phase in phases) { "gateway_phase_invalid:${experiment.phase}" }
        require(experiment.createdAt.isNotBlank() && experiment.scheduledAt.isNotBlank() && experiment.expectedActiveAt.isNotBlank()) {
            "gateway_schedule_timestamps_missing"
        }
        if (experiment.phase == "active" || experiment.phase == "clearing") {
            require(!experiment.activeAt.isNullOrBlank() && !experiment.expectedClearAt.isNullOrBlank()) {
                "gateway_active_timestamps_missing"
            }
        }
        if (experiment.cleanupVerified) {
            require(experiment.phase in setOf("completed", "failed") && !experiment.clearedAt.isNullOrBlank()) {
                "gateway_cleanup_claim_invalid"
            }
        } else {
            require(experiment.clearedAt == null) { "gateway_unverified_cleanup_has_timestamp" }
        }
        if (experiment.phase == "cleanup_failed") {
            require(!experiment.cleanupVerified && experiment.clearedAt == null && experiment.error.isNotBlank()) {
                "gateway_cleanup_failure_evidence_invalid"
            }
        }
    }

    fun requireActive(experiment: AnebGatewayClient.Experiment, expected: Expected) {
        validate(experiment, expected)
        require(experiment.phase == "active" && experiment.error.isBlank()) { "gateway_not_active:${experiment.phase}" }
    }

    fun isTransition(experiment: AnebGatewayClient.Experiment): Boolean = experiment.phase in transitions

    fun requireCleanTerminal(experiment: AnebGatewayClient.Experiment, expected: Expected) {
        validate(experiment, expected)
        require(experiment.cleanupVerified && experiment.clearedAt != null) { "gateway_cleanup_not_confirmed" }
        require(experiment.phase in setOf("completed", "failed")) { "gateway_cleanup_failed:${experiment.phase}" }
    }

    /** Normal measurement completion requires both safe cleanup and a successful experiment. */
    fun requireSuccessfulTerminal(experiment: AnebGatewayClient.Experiment, expected: Expected) {
        requireCleanTerminal(experiment, expected)
        require(experiment.phase == "completed") { "gateway_experiment_terminal_failed:${experiment.phase}" }
        require(experiment.error.isBlank()) { "gateway_experiment_terminal_error" }
    }

    fun isBypass(success: Boolean, after: AnebGatewayClient.Experiment): Boolean = success && after.phase == "active"
}

/** Pure recovery state machine: measurement time is frozen at echo completion, never at a later control GET. */
internal class GatewayRecoveryTracker(private val activeAcknowledgedNanos: Long) {
    var outageFailureCount: Int = 0
        private set
    var bypassObserved: Boolean = false
        private set
    private var firstNonActiveSuccessNanos: Long? = null

    fun observe(beforePhase: String, afterPhase: String, success: Boolean, echoCompletedNanos: Long) {
        if (!success && beforePhase == "active") outageFailureCount += 1
        if (success && afterPhase == "active") {
            bypassObserved = true
        } else if (success && afterPhase != "active" && firstNonActiveSuccessNanos == null) {
            firstNonActiveSuccessNanos = echoCompletedNanos
        }
    }

    val hasRecoveryCandidate: Boolean
        get() = firstNonActiveSuccessNanos != null

    fun verifiedRecoveryTimeMs(cleanupVerified: Boolean): Double? =
        firstNonActiveSuccessNanos
            ?.takeIf { cleanupVerified && it >= activeAcknowledgedNanos }
            ?.let { (it - activeAcknowledgedNanos) / 1e6 }
}
