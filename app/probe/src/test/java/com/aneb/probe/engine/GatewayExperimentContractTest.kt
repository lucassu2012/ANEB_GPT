package com.aneb.probe.engine

import com.aneb.probe.net.AnebGatewayClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayExperimentContractTest {
    private val expected = GatewayExperimentContract.Expected(
        runId = "run-1",
        profileRef = "ip_loss_latency@1.0.0",
        profileFingerprint = "a".repeat(64),
        impairmentLayer = "ip_forwarding",
    )

    @Test fun activeAndVerifiedCleanupAreAccepted() {
        GatewayExperimentContract.requireActive(experiment(), expected)
        val terminal = experiment().copy(
            phase = "completed",
            clearedAt = "2026-07-17T10:00:03Z",
            cleanupVerified = true,
            stopReason = "stop_requested",
        )
        GatewayExperimentContract.requireCleanTerminal(terminal, expected)
        GatewayExperimentContract.requireSuccessfulTerminal(terminal, expected)
    }

    @Test fun failedExperimentWithVerifiedCleanupIsStillSafeToRelease() {
        val terminal = experiment().copy(
            phase = "failed",
            clearedAt = "2026-07-17T10:00:03Z",
            cleanupVerified = true,
            error = "apply failed before activation",
        )
        GatewayExperimentContract.requireCleanTerminal(terminal, expected)
        assertEquals(
            "gateway_experiment_terminal_failed:failed",
            runCatching { GatewayExperimentContract.requireSuccessfulTerminal(terminal, expected) }.exceptionOrNull()?.message,
        )
    }

    @Test fun completedTerminalWithErrorIsCleanButNotMeasurementSuccess() {
        val terminal = experiment().copy(
            phase = "completed",
            clearedAt = "2026-07-17T10:00:03Z",
            cleanupVerified = true,
            error = "audit write failed",
        )
        GatewayExperimentContract.requireCleanTerminal(terminal, expected)
        assertEquals(
            "gateway_experiment_terminal_error",
            runCatching { GatewayExperimentContract.requireSuccessfulTerminal(terminal, expected) }.exceptionOrNull()?.message,
        )
    }

    @Test fun ownershipCanBeRegisteredBeforeUntrustedScheduleFieldsAreAccepted() {
        val malformedOwned = experiment().copy(createdAt = "")
        GatewayExperimentContract.requireOwned(malformedOwned, expected)
        assertTrue(GatewayExperimentContract.isOwnedBy(malformedOwned, expected))
        assertEquals(
            "gateway_schedule_timestamps_missing",
            runCatching { GatewayExperimentContract.validate(malformedOwned, expected) }.exceptionOrNull()?.message,
        )
        assertFalse(GatewayExperimentContract.isOwnedBy(malformedOwned.copy(runId = "other"), expected))
    }

    @Test fun everyIdentityMismatchFailsClosed() {
        val cases = listOf(
            experiment().copy(experimentId = "bad/id") to "gateway_experiment_id_invalid",
            experiment().copy(runId = "other") to "gateway_run_id_mismatch",
            experiment().copy(profileRef = "other@1") to "gateway_profile_ref_mismatch",
            experiment().copy(profileFingerprint = "b".repeat(64)) to "gateway_profile_fingerprint_mismatch",
            experiment().copy(impairmentLayer = "application_http") to "gateway_impairment_layer_mismatch",
            experiment().copy(claimScope = "other") to "gateway_claim_scope_mismatch",
        )
        cases.forEach { (value, message) ->
            val error = runCatching { GatewayExperimentContract.validate(value, expected) }.exceptionOrNull()
            assertEquals(message, error?.message)
        }
    }

    @Test fun cleanupFailureCannotMasqueradeAsCleared() {
        val failed = experiment().copy(
            phase = "cleanup_failed",
            activeAt = null,
            expectedClearAt = null,
            error = "qdisc remains",
        )
        GatewayExperimentContract.validate(failed, expected)
        val forged = failed.copy(clearedAt = "2026-07-17T10:00:03Z")
        assertEquals(
            "gateway_unverified_cleanup_has_timestamp",
            runCatching { GatewayExperimentContract.validate(forged, expected) }.exceptionOrNull()?.message,
        )
    }

    @Test fun onlySuccessWhileGatewayStillActiveIsBypass() {
        assertTrue(GatewayExperimentContract.isBypass(true, experiment()))
        assertFalse(GatewayExperimentContract.isBypass(false, experiment()))
        assertFalse(
            GatewayExperimentContract.isBypass(
                true,
                experiment().copy(phase = "completed", clearedAt = "done", cleanupVerified = true),
            ),
        )
    }

    private fun experiment() = AnebGatewayClient.Experiment(
        experimentId = "experiment-1",
        runId = expected.runId,
        profileRef = expected.profileRef,
        profileFingerprint = expected.profileFingerprint,
        phase = "active",
        claimScope = "dedicated_gateway_ip_forwarding",
        impairmentLayer = expected.impairmentLayer,
        createdAt = "2026-07-17T10:00:00Z",
        scheduledAt = "2026-07-17T10:00:00Z",
        expectedActiveAt = "2026-07-17T10:00:00.5Z",
        activeAt = "2026-07-17T10:00:00.5Z",
        expectedClearAt = "2026-07-17T10:01:00.5Z",
        cleanupVerified = false,
    )
}
