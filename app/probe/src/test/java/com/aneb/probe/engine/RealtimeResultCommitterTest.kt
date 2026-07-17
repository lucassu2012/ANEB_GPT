package com.aneb.probe.engine

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class RealtimeResultCommitterTest {
    @Test
    fun `failed durable commit never publishes a realtime result`() = runBlocking {
        var published: RealtimeSimulationResult? = null
        val committer = DurableResultCommitter(
            store = DurableResultStore<RealtimeSimulationResult> { throw IOException("disk_full") },
            publish = { published = it },
        )

        try {
            committer.commit(result())
            fail("commit should fail when Room rejects the result")
        } catch (_: IOException) {
            // The storage failure remains observable to the run owner.
        }

        assertNull(published)
    }

    @Test
    fun `cancellation observed before final commit stores and publishes nothing`() = runBlocking {
        var stored = false
        var published: RealtimeSimulationResult? = null
        val committer = DurableResultCommitter(
            store = DurableResultStore<RealtimeSimulationResult> { stored = true },
            publish = { published = it },
        )

        val job = launch {
            currentCoroutineContext().cancel(CancellationException("cancel_before_commit"))
            committer.commit(result())
        }
        job.join()

        assertFalse(stored)
        assertNull(published)
    }

    @Test
    fun `cancellation during final commit cannot split durable store from publication`() = runBlocking {
        val committed = result()
        val storeEntered = CompletableDeferred<Unit>()
        val allowStoreToFinish = CompletableDeferred<Unit>()
        var stored = false
        var published: RealtimeSimulationResult? = null
        val committer = DurableResultCommitter(
            store = DurableResultStore<RealtimeSimulationResult> {
                storeEntered.complete(Unit)
                allowStoreToFinish.await()
                stored = true
            },
            publish = { published = it },
        )

        val job = launch { committer.commit(committed) }
        storeEntered.await()
        job.cancel(CancellationException("cancel_during_commit"))
        allowStoreToFinish.complete(Unit)
        job.join()

        assertEquals(true, stored)
        assertEquals(committed, published)
    }

    @Test
    fun `store cancellation remains cancellation and never publishes`() = runBlocking {
        var published: RealtimeSimulationResult? = null
        val committer = DurableResultCommitter(
            store = DurableResultStore<RealtimeSimulationResult> { throw CancellationException("store_cancelled") },
            publish = { published = it },
        )

        try {
            committer.commit(result())
            fail("store cancellation must remain observable")
        } catch (_: CancellationException) {
            // Expected: cancellation is not converted into a persistence failure.
        }

        assertNull(published)
    }

    private fun result(): RealtimeSimulationResult {
        val evidence = RealtimeRunEvidence("quick", emptyList(), "fixture")
        return RealtimeSimulationResult(
            runId = "run-commit-fixture",
            startedAtEpochMs = 1L,
            serverBase = "https://probe.example",
            profileId = "ai_realtime_voice_quick",
            profileVersion = "1.0.0",
            behaviorModelId = "fixture",
            behaviorModelVersion = "1.0.0",
            behaviorModelHash = "fixture-hash",
            calibrationStatus = "hypothesis",
            variant = "quick",
            scorePolicyId = "realtime-interaction-score-v1",
            scoreAnchorPolicyId = "compliance-anchors-v1",
            conclusionPolicyId = "realtime-interaction-conclusions-v1",
            score = RealtimeSimulationScorer.score(evidence),
            evidence = evidence,
        )
    }
}
