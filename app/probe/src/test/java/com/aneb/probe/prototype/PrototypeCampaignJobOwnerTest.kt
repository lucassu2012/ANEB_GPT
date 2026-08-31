package com.aneb.probe.prototype

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PrototypeCampaignJobOwnerTest {
    @Test
    fun notificationRefreshFailureDoesNotPreventStartOrCancellation() = runBlocking {
        val config = PrototypeCampaignPersistenceFixture
            .campaignConfig("campaign-notification-refresh-failure")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runnerEntered = CompletableDeferred<Unit>()
        val cancellationObserved = CompletableDeferred<Unit>()
        val sessions = mutableListOf<PrototypeCampaignSession>()
        val lease = PrototypeCampaignServiceLease()
        val generation = lease.acquire()
        lateinit var owner: PrototypeCampaignJobOwner
        val host = PrototypeCampaignServiceHost(
            beginForeground = {},
            startOwned = { owner.start(it) },
            cancelOwned = { owner.cancel() },
            publish = { session ->
                val accepted = lease.publishSession(generation, session) {
                    runPrototypeCampaignNotificationUpdate {
                        error("notification refresh failed")
                    }
                }
                if (accepted) synchronized(sessions) { sessions += session }
            },
            finishTerminal = {},
        )
        owner = PrototypeCampaignJobOwner(
            scope = scope,
            executor = PrototypeCampaignExecutor {
                runnerEntered.complete(Unit)
                try {
                    CompletableDeferred<PrototypeQuickCampaignRunner.CampaignResult>().await()
                } catch (cancelled: CancellationException) {
                    cancellationObserved.complete(Unit)
                    throw cancelled
                }
            },
            publish = host::onOwnerSession,
        )

        try {
            assertSame(PrototypeCampaignStartResult.Started, host.start(config))
            withTimeout(2_000) { runnerEntered.await() }
            assertTrue(lease.session.value is PrototypeCampaignSession.Running)

            assertTrue(host.cancel(config.campaignId))
            withTimeout(2_000) { cancellationObserved.await() }
            awaitState(sessions) { it is PrototypeCampaignSession.Cancelled }
            assertTrue(lease.session.value is PrototypeCampaignSession.Cancelled)
            assertFalse(synchronized(sessions) { sessions.any { it is PrototypeCampaignSession.Finished } })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun oneOwnerRejectsConcurrentStartPropagatesCancelAndAllowsRestartAfterTerminal() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstEntered = CompletableDeferred<Unit>()
        val firstCancellationObserved = CompletableDeferred<Unit>()
        val firstMayExit = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val secondResult = CompletableDeferred<PrototypeQuickCampaignRunner.CampaignResult>()
        val states = mutableListOf<PrototypeCampaignSession>()
        var executions = 0
        val owner = PrototypeCampaignJobOwner(
            scope = scope,
            executor = PrototypeCampaignExecutor {
                executions += 1
                if (executions == 1) {
                    firstEntered.complete(Unit)
                    try {
                        CompletableDeferred<PrototypeQuickCampaignRunner.CampaignResult>().await()
                    } catch (_: CancellationException) {
                        firstCancellationObserved.complete(Unit)
                        withContext(NonCancellable) { firstMayExit.await() }
                        emptyCampaign("campaign-1")
                    }
                } else {
                    secondEntered.complete(Unit)
                    secondResult.await()
                }
            },
            publish = { state -> synchronized(states) { states += state } },
        )
        val ticket = ownerTicket()
        val first = PrototypeCampaignConfig(ticket, "campaign-1")
        val second = PrototypeCampaignConfig(ticket, "campaign-2")

        try {
            assertTrue(owner.start(first))
            withTimeout(2_000) { firstEntered.await() }
            assertFalse(owner.start(second))
            assertEquals(1, executions)

            assertTrue(owner.cancel())
            withTimeout(2_000) { firstCancellationObserved.await() }
            val cancelling = synchronized(states) { states.last() }
            assertEquals("Cancelling", cancelling::class.simpleName)
            assertFalse(owner.start(second))
            assertFalse(owner.cancel())

            firstMayExit.complete(Unit)
            awaitState(states) { it is PrototypeCampaignSession.Cancelled }
            assertFalse(owner.cancel())

            assertTrue(owner.start(second))
            withTimeout(2_000) { secondEntered.await() }
            val expected = emptyCampaign("campaign-2")
            secondResult.complete(expected)
            val finished = awaitState(states) { it is PrototypeCampaignSession.Finished }
                as PrototypeCampaignSession.Finished
            assertEquals(expected, finished.result)
            assertEquals(2, executions)
            assertFalse(
                synchronized(states) {
                    states.any {
                        it is PrototypeCampaignSession.Finished &&
                            it.config.campaignId == "campaign-1"
                    }
                },
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun completeAndPartialResultsCallStoreOnceBeforeFinished(): Unit = runBlocking {
        val completeConfig = PrototypeCampaignPersistenceFixture.campaignConfig(
            "campaign-persist-complete",
        )
        val partialConfig = PrototypeCampaignPersistenceFixture.campaignConfig(
            "campaign-persist-partial",
        )
        val cases = listOf(
            completeConfig to PrototypeCampaignPersistenceFixture.completeQuickCampaign(completeConfig),
            partialConfig to PrototypeCampaignPersistenceFixture.partialQuickCampaign(partialConfig),
        )

        cases.forEach { (config, expectedResult) ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val states = mutableListOf<PrototypeCampaignSession>()
            val events = mutableListOf<String>()
            val saveEntered = CompletableDeferred<Unit>()
            val allowSave = CompletableDeferred<Unit>()
            val storeCalls = AtomicInteger()
            val owner = PrototypeCampaignJobOwner(
                scope = scope,
                executor = PersistingPrototypeCampaignExecutor(
                    delegate = PrototypeCampaignExecutor { received ->
                        assertEquals(config, received)
                        synchronized(events) { events += "runner" }
                        expectedResult
                    },
                    store = PrototypeCampaignResultStore { received, result ->
                        assertEquals(config, received)
                        assertEquals(expectedResult, result)
                        assertEquals(1, storeCalls.incrementAndGet())
                        synchronized(events) { events += "save-start" }
                        saveEntered.complete(Unit)
                        allowSave.await()
                        synchronized(events) { events += "save-complete" }
                    },
                    backgroundDispatcher = Dispatchers.Default,
                    publishProgress = { progress ->
                        val saving = progress as PrototypeCampaignProgress.Saving
                        assertEquals(config.campaignId, saving.campaignId)
                        synchronized(events) {
                            events += "saving:${saving.processedRuns}/${saving.totalRuns}"
                        }
                    },
                ),
                publish = { state ->
                    synchronized(states) { states += state }
                    if (state is PrototypeCampaignSession.Finished) {
                        synchronized(events) { events += "Finished" }
                    }
                },
            )
            try {
                assertTrue(owner.start(config))
                withTimeout(2_000) { saveEntered.await() }
                assertFalse(synchronized(states) { states.any { it is PrototypeCampaignSession.Finished } })
                allowSave.complete(Unit)
                val finished = awaitState(states) { it is PrototypeCampaignSession.Finished }
                    as PrototypeCampaignSession.Finished
                assertEquals(expectedResult, finished.result)
                assertEquals(1, storeCalls.get())
                assertEquals(
                    listOf(
                        "runner",
                        "saving:${expectedResult.summary.attemptedRuns}/" +
                            expectedResult.summary.plannedRuns,
                        "save-start",
                        "save-complete",
                        "Finished",
                    ),
                    synchronized(events) { events.toList() },
                )
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun persistenceFailurePublishesFailedWithoutFinished(): Unit = runBlocking {
        val config = PrototypeCampaignPersistenceFixture.campaignConfig("campaign-persist-failure")
        val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val states = mutableListOf<PrototypeCampaignSession>()
        val progress = mutableListOf<PrototypeCampaignProgress>()
        val storeCalls = AtomicInteger()
        val owner = PrototypeCampaignJobOwner(
            scope = scope,
            executor = PersistingPrototypeCampaignExecutor(
                delegate = PrototypeCampaignExecutor { result },
                store = PrototypeCampaignResultStore { _, _ ->
                    storeCalls.incrementAndGet()
                    error("persistence failed")
                },
                backgroundDispatcher = Dispatchers.Default,
                publishProgress = { update -> synchronized(progress) { progress += update } },
            ),
            publish = { state -> synchronized(states) { states += state } },
        )
        try {
            assertTrue(owner.start(config))
            val failed = awaitState(states) { it is PrototypeCampaignSession.Failed }
                as PrototypeCampaignSession.Failed
            assertEquals("persistence failed", failed.message)
            assertEquals(1, storeCalls.get())
            assertEquals(
                listOf(PrototypeCampaignProgress.Saving(config.campaignId, 3, 3)),
                synchronized(progress) { progress.toList() },
            )
            assertFalse(synchronized(states) { states.any { it is PrototypeCampaignSession.Finished } })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun runnerFailureNeverCallsPersistenceAndPublishesFailed(): Unit = runBlocking {
        val config = PrototypeCampaignPersistenceFixture.campaignConfig("campaign-runner-failure")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val states = mutableListOf<PrototypeCampaignSession>()
        val storeCalls = AtomicInteger()
        val owner = PrototypeCampaignJobOwner(
            scope = scope,
            executor = PersistingPrototypeCampaignExecutor(
                delegate = PrototypeCampaignExecutor { error("runner failed") },
                store = PrototypeCampaignResultStore { _, _ -> storeCalls.incrementAndGet() },
                backgroundDispatcher = Dispatchers.Default,
            ),
            publish = { state -> synchronized(states) { states += state } },
        )
        try {
            assertTrue(owner.start(config))
            val failed = awaitState(states) { it is PrototypeCampaignSession.Failed }
                as PrototypeCampaignSession.Failed
            assertEquals("runner failed", failed.message)
            assertEquals(0, storeCalls.get())
            assertFalse(synchronized(states) { states.any { it is PrototypeCampaignSession.Finished } })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellationBeforeResultNeverCallsPersistence(): Unit = runBlocking {
        val config = PrototypeCampaignPersistenceFixture.campaignConfig("campaign-cancel-before-result")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val states = mutableListOf<PrototypeCampaignSession>()
        val runnerEntered = CompletableDeferred<Unit>()
        val storeCalls = AtomicInteger()
        val owner = PrototypeCampaignJobOwner(
            scope = scope,
            executor = PersistingPrototypeCampaignExecutor(
                delegate = PrototypeCampaignExecutor {
                    runnerEntered.complete(Unit)
                    CompletableDeferred<PrototypeQuickCampaignRunner.CampaignResult>().await()
                },
                store = PrototypeCampaignResultStore { _, _ -> storeCalls.incrementAndGet() },
                backgroundDispatcher = Dispatchers.Default,
            ),
            publish = { state -> synchronized(states) { states += state } },
        )
        try {
            assertTrue(owner.start(config))
            withTimeout(2_000) { runnerEntered.await() }
            assertTrue(owner.cancel())
            awaitState(states) { it is PrototypeCampaignSession.Cancelled }
            assertEquals(0, storeCalls.get())
            assertFalse(synchronized(states) { states.any { it is PrototypeCampaignSession.Finished } })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellationDuringPersistenceNeverFinishesOrRetriesSave(): Unit = runBlocking {
        val config = PrototypeCampaignPersistenceFixture.campaignConfig("campaign-cancel-during-save")
        val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val states = mutableListOf<PrototypeCampaignSession>()
        val saveEntered = CompletableDeferred<Unit>()
        val saveCancellationObserved = CompletableDeferred<Unit>()
        val saveMayExit = CompletableDeferred<Unit>()
        val storeCalls = AtomicInteger()
        val owner = PrototypeCampaignJobOwner(
            scope = scope,
            executor = PersistingPrototypeCampaignExecutor(
                delegate = PrototypeCampaignExecutor { result },
                store = PrototypeCampaignResultStore { _, _ ->
                    storeCalls.incrementAndGet()
                    saveEntered.complete(Unit)
                    try {
                        CompletableDeferred<Unit>().await()
                    } catch (cancelled: CancellationException) {
                        saveCancellationObserved.complete(Unit)
                        withContext(NonCancellable) { saveMayExit.await() }
                        throw cancelled
                    }
                },
                backgroundDispatcher = Dispatchers.Default,
            ),
            publish = { state -> synchronized(states) { states += state } },
        )
        try {
            assertTrue(owner.start(config))
            withTimeout(2_000) { saveEntered.await() }
            assertTrue(owner.cancel())
            withTimeout(2_000) { saveCancellationObserved.await() }
            assertFalse(synchronized(states) { states.any { it is PrototypeCampaignSession.Finished } })
            saveMayExit.complete(Unit)
            awaitState(states) { it is PrototypeCampaignSession.Cancelled }
            assertEquals(1, storeCalls.get())
            assertFalse(synchronized(states) { states.any { it is PrototypeCampaignSession.Finished } })
        } finally {
            scope.cancel()
        }
    }

    private suspend fun awaitState(
        states: List<PrototypeCampaignSession>,
        predicate: (PrototypeCampaignSession) -> Boolean,
    ): PrototypeCampaignSession = withTimeout(2_000) {
        while (true) {
            synchronized(states) { states.lastOrNull(predicate) }?.let { return@withTimeout it }
            kotlinx.coroutines.yield()
        }
        error("unreachable")
    }

    private fun emptyCampaign(campaignId: String) = PrototypeQuickCampaignRunner.CampaignResult(
        runs = emptyList(),
        summary = PrototypeQuickCampaignRunner.CampaignSummary(
            campaignId = campaignId,
            campaignMode = "quick",
            plannedRuns = 3,
            attemptedRuns = 0,
            successfulRuns = 0,
            failedRuns = 0,
            notStartedRuns = 3,
            successRate = 0.0,
            status = PrototypeQuickCampaignRunner.CampaignStatus.PARTIAL,
            conditionSummaries = emptyList(),
        ),
    )

    private fun ownerTicket(): CompatibleNodeTicket {
        val endpoint = PrototypeNodeEndpoint.parse("http://10.0.2.2:18088")
        return CompatibleNodeTicket.fromValidatedCapability(
            endpoint = endpoint,
            rawCapabilityBody = "owner-test-capability",
            identity = PrototypeCapabilityIdentity(
                schemaVersion = "aneb-prototype-capabilities-0.1",
                productVersion = "prototype-0.1",
                protocolVersion = "prototype-stream-0.1",
                serverVersion = "owner-test-server",
                serverBinarySha256 = "0".repeat(64),
                claimScope = "application_end_to_end_to_probe_node",
                evidenceMode = "synthetic_application_impairment",
                impairmentLayer = "application",
                profileManifestSha256 = "44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc",
                workload = PrototypeCapabilityWorkloadIdentity(
                    id = "streaming_text_reference_v0.1",
                    version = "0.1",
                    contentEventCount = 120,
                ),
                conditions = listOf(
                    PrototypeCapabilityConditionIdentity(
                        "baseline_v0.1",
                        "0.1",
                        50,
                        "46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e",
                    ),
                    PrototypeCapabilityConditionIdentity(
                        "slow_v0.1",
                        "0.1",
                        125,
                        "b51b27fe8332b3fc8a97472a44312b3001ccd54364a61ed8799816c299d27062",
                    ),
                    PrototypeCapabilityConditionIdentity(
                        "unstable_v0.1",
                        "0.1",
                        65,
                        "d11dce2a877d7c3772a4552f2d922d5f96730c9a01bb829f0203c65b110a8c58",
                    ),
                ),
                evidenceSchemaVersion = "aneb-prototype-evidence-0.1",
                scorePolicyId = "rpi-0.1",
                terminalReceiptVersion = "prototype-terminal-receipt-0.1",
            ),
        )
    }
}
