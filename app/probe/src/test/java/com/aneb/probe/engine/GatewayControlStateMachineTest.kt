package com.aneb.probe.engine

import com.aneb.probe.net.AnebGatewayClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayControlStateMachineTest {
    @Test fun discoveryWindowOutlivesOneGatewayControlCall() {
        assertEquals(8_000L, GATEWAY_DISCOVERY_TIMEOUT_MS)
        assertTrue(GATEWAY_DISCOVERY_TIMEOUT_MS > AnebGatewayClient.CONTROL_CALL_TIMEOUT_MS)
    }

    @Test fun cancellationDiscoveryIsStatusOnlyAndNeverCreatesExperiment() {
        val calls = mutableListOf<String>()
        val found = runBlocking {
            discoverCancelledGatewayStart<String>(
                status = {
                    calls += "status"
                    null
                },
                isOwned = { it == "owned" },
            )
        }

        assertNull(found)
        assertEquals(listOf("status"), calls)
    }

    @Test fun nonCancellationFailureUsesStatusPostFinalStatusWhenRetryResponseIsLost() {
        val calls = mutableListOf<String>()
        var statusCalls = 0
        val found = runBlocking {
            reconcileFailedGatewayStart(
                status = {
                    calls += "status"
                    statusCalls += 1
                    if (statusCalls == 2) "owned" else null
                },
                retryPost = {
                    calls += "post"
                    null
                },
                isOwned = { it == "owned" },
            )
        }

        assertEquals("owned", found)
        assertEquals(listOf("status", "post", "status"), calls)
    }

    @Test fun nonCancellationFailureStopsBeforePostWhenStatusAlreadyOwnsExperiment() {
        val calls = mutableListOf<String>()
        val found = runBlocking {
            reconcileFailedGatewayStart(
                status = {
                    calls += "status"
                    "owned"
                },
                retryPost = {
                    calls += "post"
                    "owned"
                },
                isOwned = { it == "owned" },
            )
        }

        assertEquals("owned", found)
        assertEquals(listOf("status"), calls)
    }

    @Test fun explicitHttp409FailsImmediatelyWithoutStatusOrPostRetry() {
        val calls = mutableListOf<String>()
        val rejection = AnebGatewayClient.GatewayApiException(
            message = "gateway_http_409",
            submissionMayHaveSucceeded = false,
        )

        val thrown = runCatching {
            runBlocking {
                reconcileAmbiguousGatewayStart<String>(
                    startError = rejection,
                    status = {
                        calls += "status"
                        null
                    },
                    retryPost = {
                        calls += "post"
                        null
                    },
                    isOwned = { it == "owned" },
                )
            }
        }.exceptionOrNull()

        assertSame(rejection, thrown)
        assertTrue(calls.isEmpty())
    }

    @Test fun boundedStatusPollingFindsLateExperimentRegistration() {
        var attempts = 0
        var pauses = 0
        val found = runBlocking {
            pollGatewayStatusUntilFound(
                status = {
                    attempts += 1
                    if (attempts == 3) "owned" else null
                },
                accept = { it == "owned" },
                pause = { pauses += 1 },
            )
        }

        assertEquals("owned", found)
        assertEquals(3, attempts)
        assertEquals(2, pauses)
    }

    @Test fun uncertainDeleteResponseGetsTwoIdempotentRetriesThenOnlyPolls() {
        val policy = GatewayCleanupReconciliationPolicy(
            initialDeleteResponseKnown = false,
            maxRetryDeletes = 2,
        )
        assertEquals(GatewayCleanupNextAction.RETRY_DELETE, policy.afterObservation("active"))
        policy.onDeleteResult(responseKnown = false)
        assertEquals(GatewayCleanupNextAction.RETRY_DELETE, policy.afterObservation("active"))
        policy.onDeleteResult(responseKnown = false)
        assertEquals(GatewayCleanupNextAction.POLL_GET, policy.afterObservation("active"))
        assertEquals(GatewayCleanupNextAction.POLL_GET, policy.afterObservation("cleanup_failed"))
    }

    @Test fun cleanupCompletesBeforeFailureEvidenceIsFrozenForPersistence() = runBlocking {
        val events = mutableListOf<String>()
        prepareGatewayFailureEvidence(
            cleanup = { events += "cleanup" },
            freeze = { events += "freeze" },
        )
        events += "persist"

        assertEquals(listOf("cleanup", "freeze", "persist"), events)
    }

    @Test fun gatewayRequestClassificationDoesNotDependOnLoadedProfile() {
        assertTrue(isGatewayControlVariant("gateway_loss"))
        assertTrue(isGatewayControlVariant("gateway_recovery"))
        assertFalse(isGatewayControlVariant("quick"))
        assertFalse(isGatewayControlVariant("weak_recovery"))
    }

    @Test fun internalDeadlineBecomesControlFailureInsteadOfUserCancellation() {
        val error = runCatching {
            runBlocking {
                withGatewayControlTimeout(20, "activation") { delay(200) }
            }
        }.exceptionOrNull()

        assertTrue(error is GatewayControlException)
        assertEquals("gateway_activation_timeout", error?.message)
    }

    @Test fun externalCancellationRemainsCancellation() = runBlocking {
        var observed: Throwable? = null
        val job = launch {
            try {
                withGatewayControlTimeout(10_000, "activation") { delay(10_000) }
            } catch (error: Throwable) {
                observed = error
            }
        }
        yield()
        job.cancel()
        joinAll(job)

        assertTrue(observed is CancellationException)
        assertFalse(observed is GatewayControlException)
    }

    @Test fun gatewayControlFailureIsRethrownToServiceAfterEvidenceIsFrozen() {
        val original = IllegalArgumentException("untrusted detail")
        val wrapped = gatewayFailureForService(gatewayRequested = true, original)
        assertTrue(wrapped is GatewayControlException)
        assertEquals("gateway_control_failed", wrapped?.message)
        assertSame(original, wrapped?.cause)
        assertNull(gatewayFailureForService(gatewayRequested = false, original))

        val existing = GatewayControlException("gateway_cleanup_timeout")
        assertSame(existing, gatewayFailureForService(gatewayRequested = true, existing))
    }

    @Test fun firstSuccessDuringClearingIsFrozenUntilCleanupIsVerified() {
        val tracker = GatewayRecoveryTracker(activeAcknowledgedNanos = 1_000_000_000L)
        tracker.observe("active", "active", success = false, echoCompletedNanos = 1_100_000_000L)
        tracker.observe("active", "clearing", success = true, echoCompletedNanos = 1_250_000_000L)
        tracker.observe("clearing", "completed", success = true, echoCompletedNanos = 5_000_000_000L)

        assertEquals(1, tracker.outageFailureCount)
        assertTrue(tracker.hasRecoveryCandidate)
        assertNull(tracker.verifiedRecoveryTimeMs(cleanupVerified = false))
        assertEquals(250.0, tracker.verifiedRecoveryTimeMs(cleanupVerified = true)!!, 0.001)
    }

    @Test fun successWhileGatewayIsStillActiveIsBypassNotRecovery() {
        val tracker = GatewayRecoveryTracker(activeAcknowledgedNanos = 1_000_000_000L)
        tracker.observe("active", "active", success = true, echoCompletedNanos = 1_100_000_000L)

        assertTrue(tracker.bypassObserved)
        assertFalse(tracker.hasRecoveryCandidate)
        assertNull(tracker.verifiedRecoveryTimeMs(cleanupVerified = true))
    }
}
