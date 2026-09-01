package com.aneb.probe.prototype

import com.aneb.probe.engine.ProbeExecutionLease
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PrototypeCampaignServiceTest {
    @Test
    fun processWideMainOrSpecialOwnerBlocksPrototypeBeforeItsExecutorStarts() {
        val currentOwner = checkNotNull(ProbeExecutionLease.process.tryAcquire())
        val config = PrototypeCampaignConfig(serviceTicket(), "campaign-process-global-busy")
        val sessions = mutableListOf<PrototypeCampaignSession>()
        var ownerStarts = 0
        var stops = 0
        val host = PrototypeCampaignServiceHost(
            beginForeground = {},
            startOwned = {
                ownerStarts += 1
                true
            },
            cancelOwned = { true },
            publish = sessions::add,
            finishTerminal = { stops += 1 },
            executionLease = ProbeExecutionLease.process,
        )

        try {
            val result = host.start(config)
            assertTrue(
                "Prototype executor started while another probe Service owned the process slot",
                result is PrototypeCampaignStartResult.ProcessLeaseRejected,
            )
            assertEquals(0, ownerStarts)
            assertTrue(
                host.finishProcessLeaseRejected(
                    result as PrototypeCampaignStartResult.ProcessLeaseRejected,
                ),
            )
            assertEquals(1, stops)
            assertEquals(config, (sessions.single() as PrototypeCampaignSession.Failed).config)
            assertNull(ProbeExecutionLease.process.tryAcquire())
        } finally {
            assertTrue(ProbeExecutionLease.process.release(currentOwner))
        }
    }

    @Test
    fun acceptedTicketStartEntersForegroundBeforeOwnerAndDoesNotStopBeforeTerminal() {
        val config = PrototypeCampaignConfig(
            nodeTicket = serviceTicket(),
            campaignId = "campaign-service-1",
            campaignMode = PrototypeQuickCampaignRunner.CampaignMode.ACCEPTANCE,
        )
        val result = emptyCampaign(config.campaignId)
        val effects = mutableListOf<String>()
        val sessions = mutableListOf<PrototypeCampaignSession>()
        val beginForeground: () -> Unit = { effects += "foreground" }
        val startOwned: (PrototypeCampaignConfig) -> Boolean = { received ->
            assertSame(config, received)
            effects += "owner-start"
            true
        }
        val cancelOwned: () -> Boolean = { false }
        val publish: (PrototypeCampaignSession) -> Unit = sessions::add
        val finishTerminal: () -> Unit = { effects += "stop" }
        val host = PrototypeCampaignServiceHost(
            beginForeground = beginForeground,
            startOwned = startOwned,
            cancelOwned = cancelOwned,
            publish = publish,
            finishTerminal = finishTerminal,
        )

        assertSame(PrototypeCampaignStartResult.Started, host.start(config))
        assertSame(PrototypeCampaignStartResult.HostRejected, host.start(config))
        host.onOwnerSession(PrototypeCampaignSession.Running(config))

        assertEquals(listOf("foreground", "owner-start"), effects)
        assertEquals(listOf(PrototypeCampaignSession.Running(config)), sessions)

        val finished = PrototypeCampaignSession.Finished(config, result)
        host.onOwnerSession(finished)
        host.onOwnerSession(finished)

        assertEquals(listOf("foreground", "owner-start", "stop"), effects)
        assertEquals(finished, sessions.last())
    }

    @Test
    fun ticketHandoffIsSingleUseAndRejectsMissingDamagedOrMismatchedIdentity() {
        var token = 0
        val registry = PrototypeCampaignServiceHandoffRegistry { "handoff-${++token}" }
        val config = PrototypeCampaignConfig(
            nodeTicket = serviceTicket(),
            campaignId = "campaign-service-1",
            campaignMode = PrototypeQuickCampaignRunner.CampaignMode.ACCEPTANCE,
        )

        val valid = registry.register(config)
        assertSame(config, registry.consume(valid))
        assertNull(registry.consume(valid))

        val missing = registry.register(config)
        assertNull(registry.consume(missing.copy(token = "missing")))
        assertTrue(registry.revoke(missing.token))

        val invalidHandoffs: List<(PrototypeCampaignServiceHandoff) -> PrototypeCampaignServiceHandoff> =
            listOf(
                { it.copy(campaignId = "campaign-forged") },
                { it.copy(nodeBaseUrl = "http://10.0.2.3:18088") },
                { it.copy(runUrl = "http://10.0.2.3:18088/api/v1/prototype/runs") },
                { it.copy(capabilityUrl = "http://10.0.2.3:18088/api/v1/prototype/capabilities") },
                { it.copy(campaignMode = PrototypeQuickCampaignRunner.CampaignMode.QUICK) },
            )
        invalidHandoffs.forEach { mutate ->
            val handoff = registry.register(config)
            assertNull(registry.consume(mutate(handoff)))
            assertNull(registry.consume(handoff))
        }
    }

    @Test
    fun androidShellIsDeclaredAsPrivateDataSyncForegroundService() {
        assertEquals(
            "com.aneb.probe.prototype.PrototypeCampaignService",
            PrototypeCampaignService::class.java.name,
        )
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(Files.newInputStream(mainManifest()))
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val service = (0 until document.getElementsByTagName("service").length)
            .map { document.getElementsByTagName("service").item(it) }
            .first { node ->
                node.attributes.getNamedItemNS(androidNamespace, "name")?.nodeValue ==
                    ".prototype.PrototypeCampaignService"
            }

        assertEquals("false", service.attributes.getNamedItemNS(androidNamespace, "exported").nodeValue)
        assertEquals(
            "dataSync",
            service.attributes.getNamedItemNS(androidNamespace, "foregroundServiceType").nodeValue,
        )
        assertEquals("false", service.attributes.getNamedItemNS(androidNamespace, "stopWithTask").nodeValue)
    }

    @Test
    fun cancellingKeepsTheOwnedSlotAndForegroundUntilTheUnderlyingRunExits() {
        val first = PrototypeCampaignConfig(serviceTicket(), "campaign-service-1")
        val second = PrototypeCampaignConfig(serviceTicket(), "campaign-service-2")
        val sessions = mutableListOf<PrototypeCampaignSession>()
        var starts = 0
        var cancels = 0
        var stops = 0
        val host = PrototypeCampaignServiceHost(
            beginForeground = {},
            startOwned = {
                starts += 1
                true
            },
            cancelOwned = {
                cancels += 1
                cancels == 1
            },
            publish = sessions::add,
            finishTerminal = { stops += 1 },
        )

        assertSame(PrototypeCampaignStartResult.Started, host.start(first))
        host.onOwnerSession(PrototypeCampaignSession.Running(first))
        assertTrue(host.cancel(first.campaignId))
        host.onOwnerSession(PrototypeCampaignSession.Cancelling(first))

        assertSame(PrototypeCampaignStartResult.HostRejected, host.start(second))
        assertFalse(host.cancel(first.campaignId))
        assertEquals(1, starts)
        assertEquals(0, stops)

        host.onOwnerSession(PrototypeCampaignSession.Cancelled(first))

        assertEquals(1, stops)
        assertEquals(
            listOf("Running", "Cancelling", "Cancelled"),
            sessions.map { it::class.simpleName },
        )
    }

    @Test
    fun staleCampaignCancelCannotCancelOrReleaseTheReplacementOwner() {
        val executionLease = ProbeExecutionLease()
        val first = PrototypeCampaignConfig(serviceTicket(), "campaign-cancel-owner-a")
        val replacement = first.copy(campaignId = "campaign-cancel-owner-b")
        val firstHost = PrototypeCampaignServiceHost(
            beginForeground = {},
            startOwned = { true },
            cancelOwned = { true },
            publish = {},
            finishTerminal = {},
            executionLease = executionLease,
        )
        assertSame(PrototypeCampaignStartResult.Started, firstHost.start(first))
        firstHost.onOwnerSession(PrototypeCampaignSession.Running(first))
        firstHost.onOwnerSession(PrototypeCampaignSession.Cancelled(first))

        var cancelCalls = 0
        var stops = 0
        lateinit var replacementHost: PrototypeCampaignServiceHost
        replacementHost = PrototypeCampaignServiceHost(
            beginForeground = {},
            startOwned = { true },
            cancelOwned = {
                cancelCalls += 1
                replacementHost.onOwnerSession(PrototypeCampaignSession.Cancelling(replacement))
                true
            },
            publish = {},
            finishTerminal = { stops += 1 },
            executionLease = executionLease,
        )
        assertSame(PrototypeCampaignStartResult.Started, replacementHost.start(replacement))
        replacementHost.onOwnerSession(PrototypeCampaignSession.Running(replacement))

        listOf(null, "", "campaign-missing", first.campaignId).forEach { staleId ->
            assertFalse(replacementHost.cancel(staleId))
            assertFalse(replacementHost.finishRejectedStart())
        }
        assertEquals(0, cancelCalls)
        assertEquals(0, stops)
        assertNull(executionLease.tryAcquire())

        assertTrue(replacementHost.cancel(replacement.campaignId))
        assertFalse(replacementHost.cancel(replacement.campaignId))
        replacementHost.onOwnerSession(PrototypeCampaignSession.Cancelling(replacement))
        assertFalse(replacementHost.cancel(replacement.campaignId))
        assertEquals(1, cancelCalls)
        assertEquals(0, stops)
        assertNull(executionLease.tryAcquire())

        replacementHost.onOwnerSession(PrototypeCampaignSession.Cancelled(replacement))
        assertFalse(replacementHost.cancel(replacement.campaignId))
        assertEquals(1, cancelCalls)
        assertEquals(1, stops)
        val successor = checkNotNull(executionLease.tryAcquire())
        assertTrue(executionLease.release(successor))
    }

    @Test
    fun failedCancelDispatchCanRetryWithoutReleasingTheOwnedCampaign() {
        val config = PrototypeCampaignConfig(serviceTicket(), "campaign-cancel-retry")
        val executionLease = ProbeExecutionLease()
        var attempts = 0
        lateinit var host: PrototypeCampaignServiceHost
        host = PrototypeCampaignServiceHost(
            beginForeground = {},
            startOwned = { true },
            cancelOwned = {
                attempts += 1
                when (attempts) {
                    1 -> false
                    2 -> throw IllegalStateException("cancel dispatch failed")
                    else -> {
                        host.onOwnerSession(PrototypeCampaignSession.Cancelling(config))
                        true
                    }
                }
            },
            publish = {},
            finishTerminal = {},
            executionLease = executionLease,
        )
        assertSame(PrototypeCampaignStartResult.Started, host.start(config))
        host.onOwnerSession(PrototypeCampaignSession.Running(config))

        assertFalse(host.cancel(config.campaignId))
        assertNull(executionLease.tryAcquire())
        val thrown = runCatching { host.cancel(config.campaignId) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertEquals("cancel dispatch failed", thrown?.message)
        assertNull(executionLease.tryAcquire())
        assertTrue(host.cancel(config.campaignId))
        assertFalse(host.cancel(config.campaignId))
        assertEquals(3, attempts)
        assertNull(executionLease.tryAcquire())

        host.onOwnerSession(PrototypeCampaignSession.Cancelled(config))
        val successor = checkNotNull(executionLease.tryAcquire())
        assertTrue(executionLease.release(successor))
    }

    @Test
    fun invalidOrUnknownCommandsFinishIdleServiceOnceButNeverStopAnActiveRun() {
        var idleStops = 0
        val idleSessions = mutableListOf<PrototypeCampaignSession>()
        val idleHost = PrototypeCampaignServiceHost(
            beginForeground = {},
            startOwned = { true },
            cancelOwned = { false },
            publish = idleSessions::add,
            finishTerminal = { idleStops += 1 },
        )

        assertTrue(idleHost.finishIfIdle())
        assertFalse(idleHost.finishIfIdle())
        assertEquals(1, idleStops)
        assertTrue(idleSessions.isEmpty())

        val config = PrototypeCampaignConfig(serviceTicket(), "campaign-service-active")
        var activeStops = 0
        val activeHost = PrototypeCampaignServiceHost(
            beginForeground = {},
            startOwned = { true },
            cancelOwned = { false },
            publish = {},
            finishTerminal = { activeStops += 1 },
        )
        assertSame(PrototypeCampaignStartResult.Started, activeHost.start(config))
        activeHost.onOwnerSession(PrototypeCampaignSession.Running(config))

        assertFalse(activeHost.finishIfIdle())
        assertEquals(0, activeStops)
    }

    @Test
    fun systemDestroyPublishesFailureNotUserCancellationAndIgnoresLateOwnerTerminal() {
        val config = PrototypeCampaignConfig(serviceTicket(), "campaign-service-destroyed")
        val sessions = mutableListOf<PrototypeCampaignSession>()
        var stops = 0
        val host = PrototypeCampaignServiceHost(
            beginForeground = {},
            startOwned = { true },
            cancelOwned = { true },
            publish = sessions::add,
            finishTerminal = { stops += 1 },
        )
        assertSame(PrototypeCampaignStartResult.Started, host.start(config))
        host.onOwnerSession(PrototypeCampaignSession.Running(config))

        host.destroy()
        host.onOwnerSession(PrototypeCampaignSession.Cancelled(config))
        host.onOwnerSession(PrototypeCampaignSession.Finished(config, emptyCampaign(config.campaignId)))

        assertEquals(listOf("Running", "Failed"), sessions.map { it::class.simpleName })
        assertEquals(
            "prototype campaign service was destroyed",
            (sessions.last() as PrototypeCampaignSession.Failed).message,
        )
        assertFalse(host.cancel(config.campaignId))
        assertSame(PrototypeCampaignStartResult.HostRejected, host.start(config))
        assertEquals(0, stops)
    }

    @Test
    fun newerServiceLeaseMakesEveryOldInstanceCallbackStale() {
        val lease = PrototypeCampaignServiceLease()
        val oldGeneration = lease.acquire()
        assertTrue(lease.isCurrent(oldGeneration))

        val newGeneration = lease.acquire()
        assertFalse(lease.isCurrent(oldGeneration))
        assertTrue(lease.isCurrent(newGeneration))

        lease.release(oldGeneration)
        assertTrue(lease.isCurrent(newGeneration))
        lease.release(newGeneration)
        assertFalse(lease.isCurrent(newGeneration))
    }

    @Test
    fun replacementGenerationRejectsStaleSessionProgressAndReleaseWithoutDamagingCurrent() {
        val lease = PrototypeCampaignServiceLease()
        val first = PrototypeCampaignConfig(serviceTicket(), "campaign-generation-a")
        val replacement = PrototypeCampaignConfig(serviceTicket(), "campaign-generation-b")
        val firstGeneration = lease.acquire()
        assertTrue(
            lease.publishSession(
                firstGeneration,
                PrototypeCampaignSession.Running(first),
            ),
        )
        assertTrue(
            lease.publishProgress(
                firstGeneration,
                PrototypeCampaignProgress.Running(
                    campaignId = first.campaignId,
                    currentRunRef = PrototypeCampaignRunRef(1, "run-a-1", "baseline_v0.1"),
                    processedRuns = 0,
                    totalRuns = 3,
                ),
            ),
        )

        val replacementGeneration = lease.acquire()
        val replacementSession = PrototypeCampaignSession.Running(replacement)
        val replacementProgress = PrototypeCampaignProgress.Running(
            campaignId = replacement.campaignId,
            currentRunRef = PrototypeCampaignRunRef(1, "run-b-1", "baseline_v0.1"),
            processedRuns = 0,
            totalRuns = 3,
        )
        assertTrue(lease.publishSession(replacementGeneration, replacementSession))
        assertTrue(lease.publishProgress(replacementGeneration, replacementProgress))

        assertFalse(
            lease.publishSession(
                firstGeneration,
                PrototypeCampaignSession.Cancelled(first),
            ),
        )
        assertFalse(
            lease.publishProgress(
                firstGeneration,
                PrototypeCampaignProgress.Saving(first.campaignId, 1, 3),
                onPublished = { error("stale progress notification side effect ran") },
            ),
        )
        lease.release(firstGeneration)

        assertEquals(replacementSession, lease.session.value)
        assertEquals(replacementProgress, lease.progress.value)
        assertTrue(lease.isCurrent(replacementGeneration))
    }

    @Test
    fun stateFlowRetainsLatestLivePhaseAcrossCollectorsAndAdvancesToSaving() {
        val config = PrototypeCampaignConfig(serviceTicket(), "campaign-live-state-flow")
        val lease = PrototypeCampaignServiceLease()
        val generation = lease.acquire()
        val observedFlow = lease.progress
        assertTrue(lease.publishSession(generation, PrototypeCampaignSession.Running(config)))
        val base = PrototypeCampaignProgress.Running(
            campaignId = config.campaignId,
            currentRunRef = PrototypeCampaignRunRef(1, "run-live-state-01", "baseline_v0.1"),
            processedRuns = 0,
            totalRuns = 3,
        )

        assertTrue(lease.publishProgress(generation, base))
        assertTrue(
            lease.publishProgress(
                generation,
                base.copy(
                    live = PrototypeRunLiveProgress(
                        phase = PrototypeRunLivePhase.STREAMING,
                        validatedEventCount = 42,
                        ttftMs = 250.0,
                        eventRateEps = 20.0,
                        stallObserved = true,
                    ),
                ),
            ),
        )
        val finalizing = base.copy(
            live = PrototypeRunLiveProgress(
                phase = PrototypeRunLivePhase.FINALIZING,
                validatedEventCount = 120,
                ttftMs = 250.0,
                eventRateEps = 20.0,
                stallObserved = true,
            ),
        )
        assertTrue(lease.publishProgress(generation, finalizing))

        assertSame(observedFlow, lease.progress)
        assertEquals(finalizing, observedFlow.value)
        val saving = PrototypeCampaignProgress.Saving(config.campaignId, 3, 3)
        assertTrue(lease.publishProgress(generation, saving))
        assertEquals(saving, observedFlow.value)
    }

    @Test
    fun progressIsCampaignBoundAndCancellingOrTerminalSessionsClearAndCloseIt() {
        val config = PrototypeCampaignConfig(serviceTicket(), "campaign-progress-owner")
        val other = config.copy(campaignId = "campaign-progress-other")
        val lease = PrototypeCampaignServiceLease()
        val generation = lease.acquire()
        val progress = PrototypeCampaignProgress.Running(
            campaignId = config.campaignId,
            currentRunRef = PrototypeCampaignRunRef(1, "run-progress-owner-1", "baseline_v0.1"),
            processedRuns = 0,
            totalRuns = 3,
        )

        assertTrue(lease.publishSession(generation, PrototypeCampaignSession.Running(config)))
        assertFalse(lease.publishProgress(generation, progress.copy(campaignId = other.campaignId)))
        assertNull(lease.progress.value)
        assertTrue(lease.publishProgress(generation, progress))
        assertFalse(
            lease.publishSession(
                generation,
                PrototypeCampaignSession.Cancelled(other),
            ),
        )
        assertEquals(progress, lease.progress.value)

        assertTrue(lease.publishSession(generation, PrototypeCampaignSession.Cancelling(config)))
        assertNull(lease.progress.value)
        assertFalse(
            lease.publishProgress(
                generation,
                PrototypeCampaignProgress.Saving(config.campaignId, 2, 3),
            ),
        )
        assertNull(lease.progress.value)

        listOf<PrototypeCampaignSession>(
            PrototypeCampaignSession.Finished(config, emptyCampaign(config.campaignId)),
            PrototypeCampaignSession.Failed(config, "persistence failed"),
            PrototypeCampaignSession.Cancelled(config),
        ).forEach { terminal ->
            val terminalLease = PrototypeCampaignServiceLease()
            val terminalGeneration = terminalLease.acquire()
            assertTrue(
                terminalLease.publishSession(
                    terminalGeneration,
                    PrototypeCampaignSession.Running(config),
                ),
            )
            assertTrue(terminalLease.publishProgress(terminalGeneration, progress))
            assertTrue(terminalLease.publishSession(terminalGeneration, terminal))
            assertNull(terminalLease.progress.value)
            assertFalse(terminalLease.publishProgress(terminalGeneration, progress))
        }

        val rejectedLease = PrototypeCampaignServiceLease()
        val rejectedGeneration = rejectedLease.acquire()
        assertTrue(
            rejectedLease.publishSession(
                rejectedGeneration,
                PrototypeCampaignSession.Failed(other, "process slot is still finishing"),
            ),
        )
        assertEquals(other, (rejectedLease.session.value as PrototypeCampaignSession.Failed).config)
    }

    @Test
    fun everyOwnedTerminalSessionStopsForegroundAndSelfExactlyOnce() {
        val config = PrototypeCampaignConfig(serviceTicket(), "campaign-service-terminal")
        val terminals = listOf<PrototypeCampaignSession>(
            PrototypeCampaignSession.Finished(config, emptyCampaign(config.campaignId)),
            PrototypeCampaignSession.Failed(config, "terminal failure"),
            PrototypeCampaignSession.Cancelled(config),
        )

        terminals.forEach { terminal ->
            var stops = 0
            val sessions = mutableListOf<PrototypeCampaignSession>()
            val executionLease = ProbeExecutionLease()
            val host = PrototypeCampaignServiceHost(
                beginForeground = {},
                startOwned = { true },
                cancelOwned = { false },
                publish = sessions::add,
                finishTerminal = { stops += 1 },
                executionLease = executionLease,
            )
            assertSame(PrototypeCampaignStartResult.Started, host.start(config))
            host.onOwnerSession(PrototypeCampaignSession.Running(config))

            host.onOwnerSession(terminal)
            host.onOwnerSession(terminal)

            assertEquals(1, stops)
            assertEquals(terminal, sessions.last())
            assertSame(PrototypeCampaignStartResult.HostRejected, host.start(config))
            val nextToken = checkNotNull(executionLease.tryAcquire())
            assertTrue(executionLease.release(nextToken))
        }
    }

    @Test
    fun rejectedOrThrowingOwnerStartImmediatelyReleasesTheProcessExecutionLease() {
        val config = PrototypeCampaignConfig(serviceTicket(), "campaign-rejected-start")
        val executionLease = ProbeExecutionLease()
        val rejectedHost = PrototypeCampaignServiceHost(
            beginForeground = {},
            startOwned = { false },
            cancelOwned = { false },
            publish = {},
            finishTerminal = {},
            executionLease = executionLease,
        )
        assertSame(PrototypeCampaignStartResult.HostRejected, rejectedHost.start(config))

        val throwingHost = PrototypeCampaignServiceHost(
            beginForeground = {},
            startOwned = { error("owner start failed") },
            cancelOwned = { false },
            publish = {},
            finishTerminal = {},
            executionLease = executionLease,
        )
        val failure = runCatching { throwingHost.start(config) }.exceptionOrNull()
        assertEquals("owner start failed", failure?.message)

        var starts = 0
        val acceptedHost = PrototypeCampaignServiceHost(
            beginForeground = {},
            startOwned = {
                starts += 1
                true
            },
            cancelOwned = { false },
            publish = {},
            finishTerminal = {},
            executionLease = executionLease,
        )
        assertSame(PrototypeCampaignStartResult.Started, acceptedHost.start(config))
        assertEquals(1, starts)
    }

    @Test
    fun processExecutionLeaseRejectsWrongAndDuplicateReleaseTokens() {
        val lease = ProbeExecutionLease()
        val owned = checkNotNull(lease.tryAcquire())
        assertNull(lease.tryAcquire())

        val foreign = checkNotNull(ProbeExecutionLease().tryAcquire())
        assertFalse(lease.release(foreign))
        assertNull(lease.tryAcquire())
        assertTrue(lease.release(owned))
        assertFalse(lease.release(owned))

        val replacement = checkNotNull(lease.tryAcquire())
        assertFalse(lease.release(owned))
        assertNull(lease.tryAcquire())
        assertTrue(lease.release(replacement))
    }

    @Test
    fun lateStartAfterTerminalIsRejectedAndStopsTheLatestCommandWithoutLeakingHandoff() {
        val config = PrototypeCampaignConfig(serviceTicket(), "campaign-late-start")
        val registry = PrototypeCampaignServiceHandoffRegistry { "late-start-handoff" }
        var stops = 0
        val host = PrototypeCampaignServiceHost(
            beginForeground = {},
            startOwned = { true },
            cancelOwned = { true },
            publish = {},
            finishTerminal = { stops += 1 },
        )

        assertSame(PrototypeCampaignStartResult.Started, host.start(config))
        host.onOwnerSession(PrototypeCampaignSession.Finished(config, emptyCampaign(config.campaignId)))
        assertEquals(1, stops)

        val handoff = registry.register(config)
        val consumed = registry.consume(handoff)
        assertSame(config, consumed)
        assertSame(
            PrototypeCampaignStartResult.HostRejected,
            host.start(checkNotNull(consumed)),
        )
        assertTrue(host.finishRejectedStart())
        assertEquals(2, stops)
        assertNull(registry.consume(handoff))
    }

    @Test
    fun destroyedInstanceKeepsTheProcessExecutionLeaseUntilItsOldOwnerActuallyTerminates() {
        val config = PrototypeCampaignConfig(serviceTicket(), "campaign-process-lease")
        val executionLease = ProbeExecutionLease()
        var oldStarts = 0
        var oldStops = 0
        val oldSessions = mutableListOf<PrototypeCampaignSession>()
        val oldHost = PrototypeCampaignServiceHost(
            beginForeground = {},
            startOwned = {
                oldStarts += 1
                true
            },
            cancelOwned = { true },
            publish = oldSessions::add,
            finishTerminal = { oldStops += 1 },
            executionLease = executionLease,
        )
        assertSame(PrototypeCampaignStartResult.Started, oldHost.start(config))
        oldHost.onOwnerSession(PrototypeCampaignSession.Running(config))
        oldHost.destroy()

        var replacementStarts = 0
        var replacementStops = 0
        val replacementSessions = mutableListOf<PrototypeCampaignSession>()
        val replacementHost = PrototypeCampaignServiceHost(
            beginForeground = {},
            startOwned = {
                replacementStarts += 1
                true
            },
            cancelOwned = { true },
            publish = replacementSessions::add,
            finishTerminal = { replacementStops += 1 },
            executionLease = executionLease,
        )
        val replacementConfig = config.copy(campaignId = "campaign-replacement")
        val rejection = replacementHost.start(replacementConfig)
        assertTrue(rejection is PrototypeCampaignStartResult.ProcessLeaseRejected)
        assertEquals(0, replacementStarts)
        assertTrue(
            replacementHost.finishProcessLeaseRejected(
                rejection as PrototypeCampaignStartResult.ProcessLeaseRejected,
            ),
        )
        assertEquals(1, replacementStops)
        val replacementFailed = replacementSessions.single() as PrototypeCampaignSession.Failed
        assertEquals(replacementConfig, replacementFailed.config)
        assertEquals(
            "The previous Quick campaign is still finishing. Please try again shortly.",
            replacementFailed.message,
        )
        assertNull(executionLease.tryAcquire())

        oldHost.onOwnerSession(PrototypeCampaignSession.Failed(config, "old owner finally exited"))
        assertEquals(1, oldStarts)
        assertEquals(0, oldStops)
        assertTrue(oldSessions.last() is PrototypeCampaignSession.Failed)

        var successorStarts = 0
        val successorHost = PrototypeCampaignServiceHost(
            beginForeground = {},
            startOwned = {
                successorStarts += 1
                true
            },
            cancelOwned = { true },
            publish = {},
            finishTerminal = {},
            executionLease = executionLease,
        )
        assertSame(
            PrototypeCampaignStartResult.Started,
            successorHost.start(config.copy(campaignId = "campaign-successor")),
        )
        assertEquals(1, successorStarts)
    }

    @Test
    fun serviceSourceKeepsTheOwnershipChainAndImmutableCancelInsideThisService() {
        val source = Files.readAllBytes(productionSource()).toString(UTF_8)
        val uiSources = uiSources()

        assertTrue(source.contains("class PrototypeCampaignService : Service()"))
        assertTrue(source.contains("private val serviceScope = CoroutineScope("))
        assertEquals(1, Regex("PrototypeCampaignJobOwner\\(").findAll(source).count())
        assertTrue(source.contains("AnebClientPrototypeRawPostTransport(AnebClient())"))
        assertTrue(source.contains("ticketTransport.forTicket(config.nodeTicket)"))
        assertTrue(source.contains("PrototypeRunStreamAdapter("))
        assertTrue(source.contains("PrototypeQuickCampaignRunner("))
        assertTrue(source.contains("publishProgress = publishProgress"))
        assertTrue(source.contains("AnebDatabase.get(applicationContext)"))
        assertTrue(source.contains("PrototypeCampaignRoomRepository("))
        assertTrue(source.contains("PersistingPrototypeCampaignExecutor("))
        assertTrue(source.contains("PrototypeCampaignResultStore { config, result ->"))
        assertTrue(source.contains("repository.save(config, result)"))
        assertTrue(source.contains("backgroundDispatcher = Dispatchers.IO"))
        assertTrue(
            source.indexOf("PrototypeQuickCampaignRunner(") <
                source.indexOf("repository.save(config, result)"),
        )
        assertTrue(source.indexOf("startForeground(") < source.indexOf("PrototypeCampaignJobOwner("))
        assertTrue(source.contains("ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC"))
        assertTrue(source.contains("return START_NOT_STICKY"))
        assertFalse(source.contains("Manifest.permission.POST_NOTIFICATIONS"))
        assertTrue(source.contains("ACTION_START -> startCampaign(intent)"))
        val cancelBranch = source
            .substringAfter("ACTION_CANCEL -> {")
            .substringBefore("else -> host.finishRejectedStart()")
        assertTrue(cancelBranch.contains("PrototypeCampaignCancelIntent.campaignIdOrNull(intent)"))
        assertTrue(cancelBranch.contains("host.cancel(campaignId)"))
        assertTrue(cancelBranch.contains("host.finishRejectedStart()"))
        assertTrue(source.contains("else -> host.finishRejectedStart()"))
        assertTrue(source.contains("PendingIntent.getService("))
        assertTrue(source.contains("Intent(context, PrototypeCampaignService::class.java)"))
        assertTrue(source.contains(".setAction(ACTION_CANCEL)"))
        assertTrue(source.contains("PendingIntent.FLAG_ONE_SHOT"))
        assertTrue(source.contains("PendingIntent.FLAG_IMMUTABLE"))
        assertFalse(source.contains("PendingIntent.FLAG_UPDATE_CURRENT"))
        assertFalse(source.contains("PendingIntent.getActivity("))
        assertTrue(source.contains("buildPrototypeCampaignNotification(this, campaignId = null)"))
        assertTrue(
            Regex(
                "is PrototypeCampaignSession\\.Running\\s*->\\s*" +
                    "updateNotificationSafely\\(session\\.config\\.campaignId\\)",
            ).containsMatchIn(source),
        )
        assertTrue(
            Regex(
                "is PrototypeCampaignSession\\.Cancelling\\s*->\\s*" +
                    "updateNotificationSafely\\(campaignId = null\\)",
            ).containsMatchIn(source),
        )
        assertTrue(
            Regex(
                "if \\(progress is PrototypeCampaignProgress\\.Saving\\) \\{\\s*" +
                    "updateNotificationSafely\\(campaignId = null\\)",
            ).containsMatchIn(source),
        )
        val safeNotificationBody = source
            .substringAfter("internal fun runPrototypeCampaignNotificationUpdate(")
            .substringBefore("internal data class PrototypeCampaignServiceHandoff")
        assertTrue(safeNotificationBody.contains("try {"))
        assertTrue(safeNotificationBody.contains("catch (_: Exception)"))
        assertTrue(
            source.substringAfter("private fun updateNotificationSafely(")
                .substringBefore("override fun onDestroy()")
                .contains("runPrototypeCampaignNotificationUpdate"),
        )
        assertTrue(source.contains("context.startService(PrototypeCampaignCancelIntent.create(context, campaignId))"))
        assertTrue(source.contains("private const val CHANNEL_ID = \"prototype_campaign\""))
        assertTrue(source.contains("private const val NOTIFICATION_ID = 4103"))
        assertTrue(source.indexOf("handoffRegistry::consume") < source.indexOf("host.start(config)"))
        assertTrue(source.contains("PrototypeCampaignStartResult.HostRejected -> host.finishRejectedStart()"))
        assertTrue(source.contains("host.finishProcessLeaseRejected(result)"))
        assertTrue(source.indexOf("host.destroy()") < source.indexOf("serviceScope.cancel("))
        assertEquals(0, Regex("serviceLease\\.isCurrent\\(generation\\)").findAll(source).count())
        assertTrue(source.contains("serviceLease.publishSession(generation, session)"))
        assertTrue(source.contains("serviceLease.publishProgress(generation, progress)"))
        assertTrue(source.contains("serviceLease.runIfCurrent(generation)"))
        assertTrue(source.contains("internal val session = serviceLease.session"))
        assertTrue(source.contains("internal val progress = serviceLease.progress"))
        assertTrue(source.contains("private val executionLease = ProbeExecutionLease.process"))
        assertTrue(source.contains("executionLease = executionLease"))
        assertTrue(source.contains("private var foregroundRemoved = false"))
        assertTrue(source.contains("if (!foregroundRemoved)"))
        assertEquals(1, Regex("stopForeground\\(STOP_FOREGROUND_REMOVE\\)").findAll(source).count())
        assertTrue(source.contains("stopSelfResult(latestStartId)"))
        val onDestroyBody = source
            .substringAfter("override fun onDestroy()")
            .substringBefore("private fun Intent.handoffOrNull")
        assertFalse(onDestroyBody.contains("executionLease.release"))
        assertFalse(onDestroyBody.contains("AnebDatabase"))
        assertFalse(onDestroyBody.contains(".close("))

        listOf(
            "PrototypeCampaignJobOwner(",
            "PrototypeQuickCampaignRunner(",
            "PrototypeRunStreamAdapter(",
            ".forTicket(",
        ).forEach { forbidden -> assertFalse(uiSources.contains(forbidden)) }
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

    private fun serviceTicket(): CompatibleNodeTicket {
        val endpoint = PrototypeNodeEndpoint.parse("http://10.0.2.2:18088")
        return CompatibleNodeTicket.fromValidatedCapability(
            endpoint = endpoint,
            rawCapabilityBody = "service-test-capability",
            identity = PrototypeCapabilityIdentity(
                schemaVersion = "aneb-prototype-capabilities-0.1",
                productVersion = "prototype-0.1",
                protocolVersion = "prototype-stream-0.1",
                serverVersion = "service-test-server",
                serverBinarySha256 = "0".repeat(64),
                claimScope = "application_end_to_end_to_probe_node",
                evidenceMode = "synthetic_application_impairment",
                impairmentLayer = "application",
                profileManifestSha256 =
                    "44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc",
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

    private fun mainManifest(): Path = listOf(
        Path.of("app/probe/src/main/AndroidManifest.xml"),
        Path.of("src/main/AndroidManifest.xml"),
        Path.of("../../app/probe/src/main/AndroidManifest.xml"),
    ).firstOrNull(Files::isRegularFile)
        ?: error("AndroidManifest.xml fixture was not found")

    private fun productionSource(): Path = sourcePath(
        "prototype/PrototypeCampaignService.kt",
    )

    private fun uiSources(): String {
        val root = mainJavaSourceRoot().resolve("ui")
        val combined = StringBuilder()
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .forEach { path -> combined.append(Files.readAllBytes(path).toString(UTF_8)) }
        }
        return combined.toString()
    }

    private fun mainJavaSourceRoot(): Path = listOf(
        Path.of("app/probe/src/main/java/com/aneb/probe"),
        Path.of("src/main/java/com/aneb/probe"),
        Path.of("../../app/probe/src/main/java/com/aneb/probe"),
    ).firstOrNull(Files::isDirectory)
        ?: error("main Java source root was not found")

    private fun sourcePath(relative: String): Path = listOf(
        mainJavaSourceRoot().resolve(relative),
    ).firstOrNull(Files::isRegularFile)
        ?: error("source fixture was not found: $relative")
}
