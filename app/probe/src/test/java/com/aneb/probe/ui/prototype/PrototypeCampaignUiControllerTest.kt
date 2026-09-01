package com.aneb.probe.ui.prototype

import com.aneb.probe.engine.ProbeExecutionLease
import com.aneb.probe.prototype.PrototypeCampaignConfig
import com.aneb.probe.prototype.PrototypeCampaignPersistenceFixture
import com.aneb.probe.prototype.PrototypeCampaignProgress
import com.aneb.probe.prototype.PrototypeCampaignRunRef
import com.aneb.probe.prototype.PrototypeCampaignServiceHost
import com.aneb.probe.prototype.PrototypeCampaignSession
import com.aneb.probe.prototype.PrototypeCampaignStartResult
import com.aneb.probe.prototype.PrototypeQuickCampaignRunner
import com.aneb.probe.prototype.PrototypeRunLivePhase
import com.aneb.probe.prototype.PrototypeRunLiveProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

class PrototypeCampaignUiControllerTest {
    @Test
    fun acceptanceSelectionSurvivesNotificationPermissionAndStartsAcceptanceConfig() {
        val ticket = PrototypeCampaignPersistenceFixture
            .campaignConfig("campaign-ui-acceptance")
            .nodeTicket
        val launched = mutableListOf<PrototypeCampaignConfig>()
        val controller = PrototypeCampaignUiController(
            ticketForStart = { ticket },
            campaignIdFactory = { "campaign-ui-acceptance-new" },
            startCampaign = { config -> launched += config },
        )
        val input = PrototypeCampaignUiInput(
            nodeUrl = ticket.nodeBaseUrl,
            nodeCompatible = true,
            checkingNode = false,
            otherRunActive = false,
            session = PrototypeCampaignSession.Idle,
        )

        assertSame(
            PrototypeCampaignUiActionResult.RequestNotificationPermission,
            controller.requestStart(
                input = input,
                mode = PrototypeQuickCampaignRunner.CampaignMode.ACCEPTANCE,
            ),
        )
        val outcome = controller.continueStartAfterNotification(input)

        assertTrue(outcome is PrototypeCampaignUiActionResult.Started)
        val config = (outcome as PrototypeCampaignUiActionResult.Started).config
        assertEquals(
            PrototypeQuickCampaignRunner.CampaignMode.ACCEPTANCE,
            config.campaignMode,
        )
        assertSame(config, launched.single())
    }

    @Test
    fun permissionContinuationRechecksFreshTicketAndForwardsTheExactConfig() {
        val initiallyChecked = PrototypeCampaignPersistenceFixture
            .campaignConfig("campaign-ui-initial")
            .nodeTicket
        val freshAtLaunch = PrototypeCampaignPersistenceFixture
            .campaignConfig("campaign-ui-fresh")
            .nodeTicket
        var currentTicket = initiallyChecked
        var ticketLookups = 0
        val launched = mutableListOf<PrototypeCampaignConfig>()
        val controller = PrototypeCampaignUiController(
            ticketForStart = {
                ticketLookups += 1
                currentTicket
            },
            campaignIdFactory = { "campaign-ui-new" },
            startCampaign = { config: PrototypeCampaignConfig -> launched += config },
        )
        val input = PrototypeCampaignUiInput(
            nodeUrl = initiallyChecked.nodeBaseUrl,
            nodeCompatible = true,
            checkingNode = false,
            otherRunActive = false,
            session = PrototypeCampaignSession.Idle,
        )

        assertSame(
            PrototypeCampaignUiActionResult.RequestNotificationPermission,
            controller.requestStart(input),
        )
        assertEquals(1, ticketLookups)
        assertTrue(launched.isEmpty())

        currentTicket = freshAtLaunch
        val outcome = controller.continueStartAfterNotification(input)
        assertTrue(outcome is PrototypeCampaignUiActionResult.Started)
        val started = outcome as PrototypeCampaignUiActionResult.Started
        assertEquals("campaign-ui-new", started.config.campaignId)
        assertSame(freshAtLaunch, started.config.nodeTicket)
        assertSame(started.config, launched.single())
        assertEquals(2, ticketLookups)
    }

    @Test
    fun duplicateStartWhileNotificationPermissionIsPendingIsBusy() {
        val ticket = PrototypeCampaignPersistenceFixture
            .campaignConfig("campaign-ui-permission-pending")
            .nodeTicket
        var ticketLookups = 0
        val launched = mutableListOf<PrototypeCampaignConfig>()
        val controller = PrototypeCampaignUiController(
            ticketForStart = {
                ticketLookups += 1
                ticket
            },
            campaignIdFactory = { "campaign-ui-duplicate" },
            startCampaign = { config -> launched += config },
        )
        val input = PrototypeCampaignUiInput(
            nodeUrl = ticket.nodeBaseUrl,
            nodeCompatible = true,
            checkingNode = false,
            otherRunActive = false,
            session = PrototypeCampaignSession.Idle,
        )

        assertSame(
            PrototypeCampaignUiActionResult.RequestNotificationPermission,
            controller.requestStart(input),
        )
        assertSame(PrototypeCampaignUiActionResult.Busy, controller.requestStart(input))
        assertEquals(1, ticketLookups)
        assertTrue(launched.isEmpty())
    }

    @Test
    fun otherAndPrototypeActiveRunsBlockStartBeforeTicketLookup() {
        val config = PrototypeCampaignPersistenceFixture
            .campaignConfig("campaign-ui-active-gate")
        val activeInputs = listOf(
            PrototypeCampaignUiInput(
                nodeUrl = config.nodeBaseUrl,
                nodeCompatible = true,
                checkingNode = false,
                otherRunActive = true,
                session = PrototypeCampaignSession.Idle,
            ),
            PrototypeCampaignUiInput(
                nodeUrl = config.nodeBaseUrl,
                nodeCompatible = true,
                checkingNode = false,
                otherRunActive = false,
                session = PrototypeCampaignSession.Running(config),
            ),
            PrototypeCampaignUiInput(
                nodeUrl = config.nodeBaseUrl,
                nodeCompatible = true,
                checkingNode = false,
                otherRunActive = false,
                session = PrototypeCampaignSession.Cancelling(config),
            ),
        )

        activeInputs.forEach { input ->
            var ticketLookups = 0
            var campaignIdCalls = 0
            val launched = mutableListOf<PrototypeCampaignConfig>()
            val controller = PrototypeCampaignUiController(
                ticketForStart = {
                    ticketLookups += 1
                    config.nodeTicket
                },
                campaignIdFactory = {
                    campaignIdCalls += 1
                    "campaign-ui-blocked"
                },
                startCampaign = { launched += it },
            )

            assertSame(PrototypeCampaignUiActionResult.Busy, controller.requestStart(input))
            assertEquals(0, ticketLookups)
            assertEquals(0, campaignIdCalls)
            assertTrue(launched.isEmpty())
        }
    }

    @Test
    fun permissionContinuationRechecksGlobalRunStateBeforeTicketLookup() {
        val config = PrototypeCampaignPersistenceFixture
            .campaignConfig("campaign-ui-permission-race")
        var ticketLookups = 0
        val launched = mutableListOf<PrototypeCampaignConfig>()
        val controller = PrototypeCampaignUiController(
            ticketForStart = {
                ticketLookups += 1
                config.nodeTicket
            },
            campaignIdFactory = { "campaign-ui-should-not-start" },
            startCampaign = { launched += it },
        )
        val idle = PrototypeCampaignUiInput(
            nodeUrl = config.nodeBaseUrl,
            nodeCompatible = true,
            checkingNode = false,
            otherRunActive = false,
            session = PrototypeCampaignSession.Idle,
        )
        assertSame(
            PrototypeCampaignUiActionResult.RequestNotificationPermission,
            controller.requestStart(idle),
        )

        val becameBusy = idle.copy(otherRunActive = true)
        assertSame(
            PrototypeCampaignUiActionResult.Busy,
            controller.continueStartAfterNotification(becameBusy),
        )
        assertEquals(1, ticketLookups)
        assertTrue(launched.isEmpty())
    }

    @Test
    fun successfulLaunchRemainsBusyBeforeServicePublishesRunning() {
        val config = PrototypeCampaignPersistenceFixture
            .campaignConfig("campaign-ui-launch-pending")
        var ticketLookups = 0
        val launched = mutableListOf<PrototypeCampaignConfig>()
        val controller = PrototypeCampaignUiController(
            ticketForStart = {
                ticketLookups += 1
                config.nodeTicket
            },
            campaignIdFactory = { "campaign-ui-pending-new" },
            startCampaign = { launched += it },
        )
        val idle = PrototypeCampaignUiInput(
            nodeUrl = config.nodeBaseUrl,
            nodeCompatible = true,
            checkingNode = false,
            otherRunActive = false,
            session = PrototypeCampaignSession.Idle,
        )

        assertSame(
            PrototypeCampaignUiActionResult.RequestNotificationPermission,
            controller.requestStart(idle),
        )
        val started = controller.continueStartAfterNotification(idle)
        assertTrue(started is PrototypeCampaignUiActionResult.Started)
        assertSame(PrototypeCampaignUiActionResult.Busy, controller.requestStart(idle))
        assertEquals(2, ticketLookups)
        assertEquals(1, launched.size)
    }

    @Test
    fun onlyTheMatchingTerminalSessionUnlocksRetryWithAFreshCampaignId() {
        val ticket = PrototypeCampaignPersistenceFixture
            .campaignConfig("campaign-ui-retry-ticket")
            .nodeTicket
        val oldConfig = PrototypeCampaignConfig(ticket, "campaign-ui-old")
        val campaignIds = ArrayDeque(listOf("campaign-ui-new-1", "campaign-ui-new-2"))
        val launched = mutableListOf<PrototypeCampaignConfig>()
        val controller = PrototypeCampaignUiController(
            ticketForStart = { ticket },
            campaignIdFactory = { campaignIds.removeFirst() },
            startCampaign = { launched += it },
        )
        val idle = PrototypeCampaignUiInput(
            nodeUrl = ticket.nodeBaseUrl,
            nodeCompatible = true,
            checkingNode = false,
            otherRunActive = false,
            session = PrototypeCampaignSession.Idle,
        )

        assertSame(
            PrototypeCampaignUiActionResult.RequestNotificationPermission,
            controller.requestStart(idle),
        )
        val first = controller.continueStartAfterNotification(idle)
            as PrototypeCampaignUiActionResult.Started
        assertEquals("campaign-ui-new-1", first.config.campaignId)

        controller.observe(PrototypeCampaignSession.Failed(oldConfig, "old terminal"))
        assertSame(PrototypeCampaignUiActionResult.Busy, controller.requestStart(idle))

        controller.observe(PrototypeCampaignSession.Failed(first.config, "new terminal"))
        assertSame(
            PrototypeCampaignUiActionResult.RequestNotificationPermission,
            controller.requestStart(idle),
        )
        val second = controller.continueStartAfterNotification(idle)
            as PrototypeCampaignUiActionResult.Started
        assertEquals("campaign-ui-new-2", second.config.campaignId)
        assertEquals(listOf(first.config, second.config), launched)
    }

    @Test
    fun processLeaseRejectedFreshCampaignDoesNotRemainPermanentlyPending() {
        val oldConfig = PrototypeCampaignPersistenceFixture
            .campaignConfig("campaign-ui-old-owner")
        val executionLease = ProbeExecutionLease()
        var serviceSession: PrototypeCampaignSession = PrototypeCampaignSession.Idle
        val oldHost = PrototypeCampaignServiceHost(
            beginForeground = {},
            startOwned = { true },
            cancelOwned = { true },
            publish = { serviceSession = it },
            finishTerminal = {},
            executionLease = executionLease,
        )
        assertSame(PrototypeCampaignStartResult.Started, oldHost.start(oldConfig))
        oldHost.onOwnerSession(PrototypeCampaignSession.Running(oldConfig))
        oldHost.destroy()
        assertEquals(oldConfig, (serviceSession as PrototypeCampaignSession.Failed).config)

        var replacementStarts = 0
        lateinit var freshConfig: PrototypeCampaignConfig
        lateinit var controller: PrototypeCampaignUiController
        controller = PrototypeCampaignUiController(
            ticketForStart = { oldConfig.nodeTicket },
            campaignIdFactory = { "campaign-ui-fresh-retry" },
            startCampaign = { freshConfig = it },
        )
        fun input() = PrototypeCampaignUiInput(
            nodeUrl = oldConfig.nodeBaseUrl,
            nodeCompatible = true,
            checkingNode = false,
            otherRunActive = false,
            session = serviceSession,
        )

        try {
            controller.observe(serviceSession)
            assertSame(
                PrototypeCampaignUiActionResult.RequestNotificationPermission,
                controller.requestStart(input()),
            )
            val started = controller.continueStartAfterNotification(input())
            assertTrue(started is PrototypeCampaignUiActionResult.Started)
            val replacementHost = PrototypeCampaignServiceHost(
                beginForeground = {},
                startOwned = {
                    replacementStarts += 1
                    true
                },
                cancelOwned = { true },
                publish = { session ->
                    serviceSession = session
                    controller.observe(session)
                },
                finishTerminal = {},
                executionLease = executionLease,
            )
            when (val result = replacementHost.start(freshConfig)) {
                PrototypeCampaignStartResult.Started -> Unit
                PrototypeCampaignStartResult.HostRejected -> {
                    replacementHost.finishRejectedStart()
                }
                is PrototypeCampaignStartResult.ProcessLeaseRejected -> {
                    replacementHost.finishProcessLeaseRejected(result)
                }
            }
            assertEquals(0, replacementStarts)

            val presentation = controller.presentation(input())
            assertFalse(
                "process-lease rejection left the fresh campaign permanently pending",
                presentation.quickRunning,
            )
            assertTrue(presentation.quickAvailable)
            assertSame(
                PrototypeCampaignUiActionResult.RequestNotificationPermission,
                controller.requestStart(input()),
            )
        } finally {
            oldHost.onOwnerSession(
                PrototypeCampaignSession.Failed(oldConfig, "old owner exited"),
            )
        }
    }

    @Test
    fun cancelIsForwardedOnlyForRunning() {
        val config = PrototypeCampaignPersistenceFixture
            .campaignConfig("campaign-ui-cancel")
        var cancelCalls = 0
        val controller = PrototypeCampaignUiController(
            ticketForStart = { config.nodeTicket },
            campaignIdFactory = { "campaign-ui-unused" },
            startCampaign = {},
            cancelCampaign = { _ -> cancelCalls += 1 },
        )

        val inactive = listOf(
            PrototypeCampaignSession.Idle,
            PrototypeCampaignSession.Cancelling(config),
            PrototypeCampaignSession.Failed(config, "failed"),
            PrototypeCampaignSession.Cancelled(config),
        )
        inactive.forEach { session ->
            assertTrue(!controller.requestCancel(session))
        }
        assertEquals(0, cancelCalls)

        assertTrue(controller.requestCancel(PrototypeCampaignSession.Running(config)))
        assertEquals(1, cancelCalls)
    }

    @Test
    fun savingACompletedResultDoesNotOfferOrAcceptCancellation() {
        val config = PrototypeCampaignPersistenceFixture
            .campaignConfig("campaign-ui-saving-not-cancellable")
        val running = PrototypeCampaignSession.Running(config)
        val saving = PrototypeCampaignProgress.Saving(
            campaignId = config.campaignId,
            processedRuns = 3,
            totalRuns = 3,
        )
        var cancelCalls = 0
        val controller = PrototypeCampaignUiController(
            ticketForStart = { config.nodeTicket },
            campaignIdFactory = { "unused" },
            startCampaign = {},
            cancelCampaign = { _ -> cancelCalls += 1 },
        )

        val presentation = controller.presentation(
            PrototypeCampaignUiInput(
                nodeUrl = config.nodeBaseUrl,
                nodeCompatible = true,
                checkingNode = false,
                otherRunActive = false,
                session = running,
                progress = saving,
            ),
        )

        assertFalse(presentation.showCancel)
        assertFalse(presentation.cancelEnabled)
        assertEquals("Saving local result… · 3/3 processed", presentation.statusMessage)
        assertFalse(controller.requestCancel(running, saving))
        assertEquals(0, cancelCalls)
    }

    @Test
    fun duplicateCancelForTheSameRunningCampaignIsIgnored() {
        val config = PrototypeCampaignPersistenceFixture
            .campaignConfig("campaign-ui-cancel-once")
        var cancelCalls = 0
        val controller = PrototypeCampaignUiController(
            ticketForStart = { config.nodeTicket },
            campaignIdFactory = { "unused" },
            startCampaign = {},
            cancelCampaign = { _ -> cancelCalls += 1 },
        )
        val running = PrototypeCampaignSession.Running(config)

        assertTrue(controller.requestCancel(running))
        val cancellingPresentation = controller.presentation(
            PrototypeCampaignUiInput(
                nodeUrl = config.nodeBaseUrl,
                nodeCompatible = true,
                checkingNode = false,
                otherRunActive = false,
                session = running,
            ),
        )
        assertTrue(!cancellingPresentation.cancelEnabled)
        assertEquals("Cancelling Quick campaign…", cancellingPresentation.statusMessage)
        assertTrue(!controller.requestCancel(running))
        assertEquals(1, cancelCalls)
    }

    @Test
    fun replacementRunningCampaignOwnsCancelDespiteLatePreviousTerminal() {
        val first = PrototypeCampaignPersistenceFixture
            .campaignConfig("campaign-ui-cancel-owner-a")
        val replacement = first.copy(campaignId = "campaign-ui-cancel-owner-b")
        val cancelledCampaignIds = mutableListOf<String>()
        val controller = PrototypeCampaignUiController(
            ticketForStart = { first.nodeTicket },
            campaignIdFactory = { "unused" },
            startCampaign = {},
            cancelCampaign = cancelledCampaignIds::add,
        )

        assertTrue(controller.requestCancel(PrototypeCampaignSession.Running(first)))
        controller.observe(PrototypeCampaignSession.Running(replacement))
        assertTrue(controller.requestCancel(PrototypeCampaignSession.Running(replacement)))
        assertEquals(listOf(first.campaignId, replacement.campaignId), cancelledCampaignIds)

        controller.observe(PrototypeCampaignSession.Cancelled(first))
        assertFalse(controller.requestCancel(PrototypeCampaignSession.Running(replacement)))
        controller.observe(PrototypeCampaignSession.Cancelling(replacement))
        assertFalse(controller.requestCancel(PrototypeCampaignSession.Cancelling(replacement)))
        controller.observe(PrototypeCampaignSession.Cancelled(replacement))
        assertFalse(controller.requestCancel(PrototypeCampaignSession.Cancelled(replacement)))
        assertEquals(listOf(first.campaignId, replacement.campaignId), cancelledCampaignIds)
    }

    @Test
    fun authoritativeActiveSessionClearsItsMatchingStartPending() {
        val config = PrototypeCampaignPersistenceFixture
            .campaignConfig("campaign-ui-start-handoff")
        val activeSessions = listOf<PrototypeCampaignSession>(
            PrototypeCampaignSession.Running(config),
            PrototypeCampaignSession.Cancelling(config),
        )
        activeSessions.forEach { activeSession ->
            val controller = PrototypeCampaignUiController(
                ticketForStart = { config.nodeTicket },
                campaignIdFactory = { config.campaignId },
                startCampaign = {},
            )
            val idle = PrototypeCampaignUiInput(
                nodeUrl = config.nodeBaseUrl,
                nodeCompatible = true,
                checkingNode = false,
                otherRunActive = false,
                session = PrototypeCampaignSession.Idle,
            )
            assertSame(
                PrototypeCampaignUiActionResult.RequestNotificationPermission,
                controller.requestStart(idle),
            )
            assertTrue(controller.continueStartAfterNotification(idle) is PrototypeCampaignUiActionResult.Started)
            assertTrue(controller.presentation(idle).quickRunning)

            controller.observe(activeSession)

            assertFalse(controller.presentation(idle).quickRunning)
        }
    }

    @Test
    fun synchronousTerminalDuringLaunchDoesNotRestoreStaleStartPending() {
        val config = PrototypeCampaignPersistenceFixture
            .campaignConfig("campaign-ui-sync-terminal")
        lateinit var controller: PrototypeCampaignUiController
        controller = PrototypeCampaignUiController(
            ticketForStart = { config.nodeTicket },
            campaignIdFactory = { config.campaignId },
            startCampaign = { started ->
                controller.observe(PrototypeCampaignSession.Failed(started, "synchronous terminal"))
            },
        )
        val idle = PrototypeCampaignUiInput(
            nodeUrl = config.nodeBaseUrl,
            nodeCompatible = true,
            checkingNode = false,
            otherRunActive = false,
            session = PrototypeCampaignSession.Idle,
        )
        assertSame(
            PrototypeCampaignUiActionResult.RequestNotificationPermission,
            controller.requestStart(idle),
        )

        assertTrue(
            controller.continueStartAfterNotification(idle) is PrototypeCampaignUiActionResult.Started,
        )
        assertFalse(controller.presentation(idle).quickRunning)
    }

    @Test
    fun sessionStatesExposeHonestStatusCancelAndRetry(): Unit = runBlocking {
        val config = PrototypeCampaignPersistenceFixture
            .campaignConfig("campaign-ui-presentation")
        val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
        val controller = PrototypeCampaignUiController(
            ticketForStart = { config.nodeTicket },
            campaignIdFactory = { "campaign-ui-unused" },
            startCampaign = {},
        )
        fun presentation(session: PrototypeCampaignSession) = controller.presentation(
            PrototypeCampaignUiInput(
                nodeUrl = config.nodeBaseUrl,
                nodeCompatible = true,
                checkingNode = false,
                otherRunActive = false,
                session = session,
            ),
        )

        with(presentation(PrototypeCampaignSession.Running(config))) {
            assertTrue(quickRunning)
            assertTrue(!quickAvailable)
            assertTrue(showCancel)
            assertTrue(cancelEnabled)
            assertEquals("Quick campaign is running.", statusMessage)
        }
        with(presentation(PrototypeCampaignSession.Cancelling(config))) {
            assertTrue(quickRunning)
            assertTrue(!quickAvailable)
            assertTrue(showCancel)
            assertTrue(!cancelEnabled)
            assertEquals("Cancelling Quick campaign…", statusMessage)
        }
        with(presentation(PrototypeCampaignSession.Finished(config, result))) {
            assertTrue(!quickRunning)
            assertTrue(quickAvailable)
            assertTrue(!showCancel)
            assertEquals("Quick campaign finished.", statusMessage)
        }
        with(presentation(PrototypeCampaignSession.Failed(config, "node unavailable"))) {
            assertTrue(!quickRunning)
            assertTrue(quickAvailable)
            assertTrue(!showCancel)
            assertEquals("Quick campaign failed: node unavailable", statusMessage)
        }
        with(presentation(PrototypeCampaignSession.Cancelled(config))) {
            assertTrue(!quickRunning)
            assertTrue(quickAvailable)
            assertTrue(!showCancel)
            assertEquals("Quick campaign cancelled · partial evidence saved.", statusMessage)
        }
    }

    @Test
    fun matchingRunningCampaignShowsTypedProgressWithoutLeakingIntoOtherSessions(): Unit =
        runBlocking {
        val config = PrototypeCampaignPersistenceFixture
            .campaignConfig("campaign-ui-progress")
        val otherConfig = config.copy(campaignId = "campaign-ui-progress-other")
        val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
        val controller = PrototypeCampaignUiController(
            ticketForStart = { config.nodeTicket },
            campaignIdFactory = { "campaign-ui-unused" },
            startCampaign = {},
        )
        fun status(
            session: PrototypeCampaignSession,
            progress: PrototypeCampaignProgress?,
        ): String? = controller.presentation(
            PrototypeCampaignUiInput(
                nodeUrl = config.nodeBaseUrl,
                nodeCompatible = true,
                checkingNode = false,
                otherRunActive = false,
                session = session,
                progress = progress,
            ),
        ).statusMessage
        val running = PrototypeCampaignSession.Running(config)

        assertEquals(
            "Running Baseline · 0/3 processed",
            status(
                running,
                PrototypeCampaignProgress.Running(
                    campaignId = config.campaignId,
                    currentRunRef = PrototypeCampaignRunRef(
                        runIndex = 1,
                        runId = "run-ui-progress-01",
                        conditionId = "baseline_v0.1",
                    ),
                    processedRuns = 0,
                    totalRuns = 3,
                ),
            ),
        )
        assertEquals(
            "Preparing Slow · 1/3 processed",
            status(
                running,
                PrototypeCampaignProgress.Cooldown(
                    campaignId = config.campaignId,
                    nextRunRef = PrototypeCampaignRunRef(
                        runIndex = 2,
                        runId = "run-ui-progress-02",
                        conditionId = "slow_v0.1",
                    ),
                    processedRuns = 1,
                    totalRuns = 3,
                ),
            ),
        )
        assertEquals(
            "Running Slow · 1/3 processed",
            status(
                running,
                PrototypeCampaignProgress.Running(
                    campaignId = config.campaignId,
                    currentRunRef = PrototypeCampaignRunRef(
                        runIndex = 2,
                        runId = "run-ui-progress-02",
                        conditionId = "slow_v0.1",
                    ),
                    processedRuns = 1,
                    totalRuns = 3,
                ),
            ),
        )
        assertEquals(
            "Running Unstable · 2/3 processed",
            status(
                running,
                PrototypeCampaignProgress.Running(
                    campaignId = config.campaignId,
                    currentRunRef = PrototypeCampaignRunRef(
                        runIndex = 3,
                        runId = "run-ui-progress-03",
                        conditionId = "unstable_v0.1",
                    ),
                    processedRuns = 2,
                    totalRuns = 3,
                ),
            ),
        )
        assertEquals(
            "Preparing Unstable · 2/3 processed",
            status(
                running,
                PrototypeCampaignProgress.Cooldown(
                    campaignId = config.campaignId,
                    nextRunRef = PrototypeCampaignRunRef(
                        runIndex = 3,
                        runId = "run-ui-progress-03",
                        conditionId = "unstable_v0.1",
                    ),
                    processedRuns = 2,
                    totalRuns = 3,
                ),
            ),
        )
        val saving = PrototypeCampaignProgress.Saving(
            campaignId = config.campaignId,
            processedRuns = 2,
            totalRuns = 3,
        )
        assertEquals("Saving local result… · 2/3 processed", status(running, saving))

        assertEquals(
            "Quick campaign is running.",
            status(
                running,
                saving.copy(campaignId = otherConfig.campaignId),
            ),
        )
        assertEquals(
            "Quick campaign is running.",
            status(
                running,
                PrototypeCampaignProgress.Running(
                    campaignId = config.campaignId,
                    currentRunRef = PrototypeCampaignRunRef(1, "run-unknown", "unknown"),
                    processedRuns = 0,
                    totalRuns = 3,
                ),
            ),
        )
        assertEquals(
            "Cancelling Quick campaign…",
            status(PrototypeCampaignSession.Cancelling(config), saving),
        )
        assertEquals(
            "Quick campaign finished.",
            status(PrototypeCampaignSession.Finished(config, result), saving),
        )
        assertEquals(
            "Quick campaign failed: node unavailable",
            status(PrototypeCampaignSession.Failed(config, "node unavailable"), saving),
        )
        assertEquals(
            "Quick campaign cancelled · partial evidence saved.",
            status(PrototypeCampaignSession.Cancelled(config), saving),
        )
        assertTrue(controller.requestCancel(running))
        assertEquals("Cancelling Quick campaign…", status(running, saving))
        }

    @Test
    fun acceptanceProgressShowsConditionOccurrenceAndSevenOfNine() {
        val config = PrototypeCampaignPersistenceFixture
            .campaignConfig("campaign-ui-acceptance-progress")
            .copy(campaignMode = PrototypeQuickCampaignRunner.CampaignMode.ACCEPTANCE)
        val controller = PrototypeCampaignUiController(
            ticketForStart = { config.nodeTicket },
            campaignIdFactory = { "unused" },
            startCampaign = {},
        )
        val presentation = controller.presentation(
            PrototypeCampaignUiInput(
                nodeUrl = config.nodeBaseUrl,
                nodeCompatible = true,
                checkingNode = false,
                otherRunActive = false,
                session = PrototypeCampaignSession.Running(config),
                progress = PrototypeCampaignProgress.Running(
                    campaignId = config.campaignId,
                    currentRunRef = PrototypeCampaignRunRef(
                        runIndex = 8,
                        runId = "run-ui-acceptance-08",
                        conditionId = "slow_v0.1",
                    ),
                    processedRuns = 7,
                    totalRuns = 9,
                ),
            ),
        )

        assertEquals(
            "Running Slow — run 3 of 3 · 7/9 processed",
            presentation.statusMessage,
        )
    }

    @Test
    fun matchingLiveExecutionPresentsValidatedMetricsAndStallWithoutLeakingCampaigns() {
        val config = PrototypeCampaignPersistenceFixture
            .campaignConfig("campaign-ui-live-progress")
            .copy(campaignMode = PrototypeQuickCampaignRunner.CampaignMode.ACCEPTANCE)
        val controller = PrototypeCampaignUiController(
            ticketForStart = { config.nodeTicket },
            campaignIdFactory = { "unused" },
            startCampaign = {},
        )
        fun presentation(progress: PrototypeCampaignProgress) = controller.presentation(
            PrototypeCampaignUiInput(
                nodeUrl = config.nodeBaseUrl,
                nodeCompatible = true,
                checkingNode = false,
                otherRunActive = false,
                session = PrototypeCampaignSession.Running(config),
                progress = progress,
            ),
        )
        val progress = PrototypeCampaignProgress.Running(
            campaignId = config.campaignId,
            currentRunRef = PrototypeCampaignRunRef(
                runIndex = 8,
                runId = "run-ui-live-08",
                conditionId = "slow_v0.1",
            ),
            processedRuns = 7,
            totalRuns = 9,
            live = PrototypeRunLiveProgress(
                phase = PrototypeRunLivePhase.STREAMING,
                validatedEventCount = 42,
                ttftMs = 912.345,
                eventRateEps = 7.891,
                stallObserved = true,
            ),
        )

        with(checkNotNull(presentation(progress).liveExecution)) {
            assertEquals("Slow — run 3 of 3", currentRunLabel)
            assertEquals("7 / 9 completed", completedRunsLabel)
            assertEquals("Streaming", phaseLabel)
            assertEquals("912.3 ms", ttftLabel)
            assertEquals("7.9 events/s", eventRateLabel)
            assertTrue(stallDetected)
        }
        assertNull(
            presentation(progress.copy(campaignId = "campaign-ui-live-other")).liveExecution,
        )
    }

    @Test
    fun liveExecutionMapsActionablePhasesAndStopsWhenCancellationIsRequested() {
        val config = PrototypeCampaignPersistenceFixture
            .campaignConfig("campaign-ui-live-phases")
        val controller = PrototypeCampaignUiController(
            ticketForStart = { config.nodeTicket },
            campaignIdFactory = { "unused" },
            startCampaign = {},
            cancelCampaign = {},
        )
        val runningSession = PrototypeCampaignSession.Running(config)
        fun live(progress: PrototypeCampaignProgress) = controller.presentation(
            PrototypeCampaignUiInput(
                nodeUrl = config.nodeBaseUrl,
                nodeCompatible = true,
                checkingNode = false,
                otherRunActive = false,
                session = runningSession,
                progress = progress,
            ),
        ).liveExecution
        fun running(phase: PrototypeRunLivePhase) = PrototypeCampaignProgress.Running(
            campaignId = config.campaignId,
            currentRunRef = PrototypeCampaignRunRef(1, "run-ui-live-01", "baseline_v0.1"),
            processedRuns = 0,
            totalRuns = 3,
            live = PrototypeRunLiveProgress(phase = phase),
        )

        with(checkNotNull(live(running(PrototypeRunLivePhase.CONNECTING)))) {
            assertEquals("Baseline — run 1 of 1", currentRunLabel)
            assertEquals("0 / 3 completed", completedRunsLabel)
            assertEquals("Connecting", phaseLabel)
            assertNull(ttftLabel)
            assertNull(eventRateLabel)
            assertFalse(stallDetected)
        }
        assertEquals(
            "Waiting for first event",
            live(running(PrototypeRunLivePhase.WAITING_FOR_FIRST_EVENT))?.phaseLabel,
        )
        assertEquals(
            "Finalizing",
            live(running(PrototypeRunLivePhase.FINALIZING))?.phaseLabel,
        )
        assertEquals(
            "Preparing next run",
            live(
                PrototypeCampaignProgress.Cooldown(
                    campaignId = config.campaignId,
                    nextRunRef = PrototypeCampaignRunRef(2, "run-ui-live-02", "slow_v0.1"),
                    processedRuns = 1,
                    totalRuns = 3,
                ),
            )?.phaseLabel,
        )
        assertEquals(
            "Saving",
            live(PrototypeCampaignProgress.Saving(config.campaignId, 3, 3))?.phaseLabel,
        )

        assertTrue(controller.requestCancel(runningSession))
        assertNull(live(running(PrototypeRunLivePhase.STREAMING)))
    }

    @Test
    fun synchronousLaunchFailureIsVisibleAndDoesNotBlockRetry() {
        val ticket = PrototypeCampaignPersistenceFixture
            .campaignConfig("campaign-ui-launch-failure")
            .nodeTicket
        var launchAttempts = 0
        val controller = PrototypeCampaignUiController(
            ticketForStart = { ticket },
            campaignIdFactory = { "campaign-ui-launch-attempt-$launchAttempts" },
            startCampaign = {
                launchAttempts += 1
                if (launchAttempts == 1) error("foreground start rejected")
            },
        )
        val idle = PrototypeCampaignUiInput(
            nodeUrl = ticket.nodeBaseUrl,
            nodeCompatible = true,
            checkingNode = false,
            otherRunActive = false,
            session = PrototypeCampaignSession.Idle,
        )

        assertSame(
            PrototypeCampaignUiActionResult.RequestNotificationPermission,
            controller.requestStart(idle),
        )
        val failed = controller.continueStartAfterNotification(idle)
        assertTrue(failed is PrototypeCampaignUiActionResult.LaunchFailed)
        assertEquals(
            "foreground start rejected",
            (failed as PrototypeCampaignUiActionResult.LaunchFailed).message,
        )

        assertSame(
            PrototypeCampaignUiActionResult.RequestNotificationPermission,
            controller.requestStart(idle),
        )
        assertTrue(
            controller.continueStartAfterNotification(idle) is
                PrototypeCampaignUiActionResult.Started,
        )
        assertEquals(2, launchAttempts)
    }

    @Test
    fun incompatibleCheckingAndStaleTicketStatesDoNotEnterTheStartFlow() {
        val config = PrototypeCampaignPersistenceFixture
            .campaignConfig("campaign-ui-admission")
        val baseInput = PrototypeCampaignUiInput(
            nodeUrl = config.nodeBaseUrl,
            nodeCompatible = true,
            checkingNode = false,
            otherRunActive = false,
            session = PrototypeCampaignSession.Idle,
        )

        var incompatibleLookups = 0
        val incompatible = PrototypeCampaignUiController(
            ticketForStart = {
                incompatibleLookups += 1
                config.nodeTicket
            },
            campaignIdFactory = { "unused" },
            startCampaign = {},
        )
        assertSame(
            PrototypeCampaignUiActionResult.FreshNodeCheckRequired,
            incompatible.requestStart(baseInput.copy(nodeCompatible = false)),
        )
        assertEquals(0, incompatibleLookups)

        var checkingLookups = 0
        val checking = PrototypeCampaignUiController(
            ticketForStart = {
                checkingLookups += 1
                config.nodeTicket
            },
            campaignIdFactory = { "unused" },
            startCampaign = {},
        )
        assertSame(
            PrototypeCampaignUiActionResult.Busy,
            checking.requestStart(baseInput.copy(checkingNode = true)),
        )
        assertEquals(0, checkingLookups)

        var staleLookups = 0
        val stale = PrototypeCampaignUiController(
            ticketForStart = {
                staleLookups += 1
                null
            },
            campaignIdFactory = { "unused" },
            startCampaign = {},
        )
        assertSame(
            PrototypeCampaignUiActionResult.FreshNodeCheckRequired,
            stale.requestStart(baseInput),
        )
        assertEquals(1, staleLookups)
    }

    @Test
    fun activityAndPrototypeScreenUseTheControllerAndServiceSession() {
        val activity = source("ui/MainActivity.kt")
        val screen = source("ui/prototype/PrototypeModeScreen.kt")

        assertTrue(activity.contains("PrototypeCampaignService.session.collectAsStateWithLifecycle()"))
        assertTrue(activity.contains("PrototypeCampaignService.progress.collectAsStateWithLifecycle()"))
        val presentationRememberKeys = activity
            .substringAfter("val prototypePresentation = remember(")
            .substringBefore(") {")
        assertTrue(presentationRememberKeys.contains("prototypeCampaignSession"))
        assertTrue(presentationRememberKeys.contains("prototypeCampaignProgress"))
        assertTrue(activity.contains("progress = prototypeCampaignProgress"))
        assertTrue(activity.contains("PrototypeCampaignUiController("))
        assertTrue(activity.contains("ticketForStart = prototypeNodeController::ticketForStart"))
        assertTrue(activity.contains("campaignIdFactory = { UUID.randomUUID().toString() }"))
        assertTrue(activity.contains("PrototypeCampaignService.start(applicationContext, config)"))
        assertTrue(activity.contains("PrototypeCampaignService.cancel(applicationContext, campaignId)"))
        assertTrue(activity.contains("requestRunNotificationPermission"))
        assertTrue(activity.contains("continueStartAfterNotification"))
        assertTrue(activity.contains("nonPrototypeRunning || prototypePresentation.quickRunning"))
        assertFalse(activity.contains("quickRunning = false"))
        assertFalse(activity.contains("quickAvailable = false"))
        assertFalse(activity.contains("onStartQuick = {}"))
        assertTrue(activity.contains("onStartAcceptance ="))

        assertTrue(screen.contains("quickStatusMessage: String?"))
        assertTrue(screen.contains("showQuickCancel: Boolean"))
        assertTrue(screen.contains("quickCancelEnabled: Boolean"))
        assertTrue(screen.contains("onCancelQuick: () -> Unit"))
        assertTrue(screen.contains("onStartAcceptance: () -> Unit"))
        assertFalse(screen.contains("available in G2-C"))
        assertTrue(screen.contains("enabled = quickCancelEnabled"))
    }

    private fun source(relativePath: String): String {
        val path = listOf(
            Path.of("app/probe/src/main/java/com/aneb/probe/$relativePath"),
            Path.of("src/main/java/com/aneb/probe/$relativePath"),
            Path.of("../../app/probe/src/main/java/com/aneb/probe/$relativePath"),
        ).firstOrNull(Files::isRegularFile)
            ?: error("source fixture was not found: $relativePath")
        return Files.readAllBytes(path).toString(UTF_8)
    }
}
