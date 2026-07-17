package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormalResultServiceTerminalPolicyTest {
    @Test
    fun `all formal modes require their own durably committed result`() {
        val modes = listOf(
            AnebTestMode.NETWORK_BASIC,
            AnebTestMode.TOKEN_SIMULATION,
            AnebTestMode.AI_REALTIME_SIMULATION,
        )

        modes.forEach { mode ->
            assertEquals(
                ProbeRunSession.Completed(false, "run-${mode.name}", mode),
                FormalResultServiceTerminalPolicy.afterFlow(
                    autorun = false,
                    testMode = mode,
                    committedRunId = "run-${mode.name}",
                ),
            )
            assertEquals(
                ProbeRunSession.Failed(true, "测试未生成结果，请重试。", mode),
                FormalResultServiceTerminalPolicy.afterFlow(
                    autorun = true,
                    testMode = mode,
                    committedRunId = null,
                ),
            )
        }
    }

    @Test
    fun `durably committed result wins a concurrent cancel request`() {
        val terminal = FormalResultServiceTerminalPolicy.afterCancellation(
            autorun = false,
            testMode = AnebTestMode.AI_REALTIME_SIMULATION,
            committedRunId = "run-committed",
            cancelRequested = true,
        )

        assertEquals(
            ProbeRunSession.Completed(
                autorun = false,
                runId = "run-committed",
                testMode = AnebTestMode.AI_REALTIME_SIMULATION,
            ),
            terminal,
        )
    }

    @Test
    fun `normal flow completion without a durable result is failed`() {
        val terminal = FormalResultServiceTerminalPolicy.afterFlow(
            autorun = true,
            testMode = AnebTestMode.AI_REALTIME_SIMULATION,
            committedRunId = null,
        )

        assertEquals(
            ProbeRunSession.Failed(
                autorun = true,
                message = "测试未生成结果，请重试。",
                testMode = AnebTestMode.AI_REALTIME_SIMULATION,
            ),
            terminal,
        )
    }

    @Test
    fun `cancel request without a durable result is cancelled`() {
        val terminal = FormalResultServiceTerminalPolicy.afterCancellation(
            autorun = false,
            testMode = AnebTestMode.AI_REALTIME_SIMULATION,
            committedRunId = null,
            cancelRequested = true,
        )

        assertEquals(
            ProbeRunSession.Cancelled(
                autorun = false,
                testMode = AnebTestMode.AI_REALTIME_SIMULATION,
            ),
            terminal,
        )
    }

    @Test
    fun `durably recorded network cancellation remains cancelled`() {
        val terminal = FormalResultServiceTerminalPolicy.afterCancellation(
            autorun = false,
            testMode = AnebTestMode.NETWORK_BASIC,
            committedRunId = "run-cancelled",
            committedResultWasCancelled = true,
            cancelRequested = true,
        )

        assertEquals(
            ProbeRunSession.Cancelled(
                autorun = false,
                testMode = AnebTestMode.NETWORK_BASIC,
            ),
            terminal,
        )
    }

    @Test
    fun `system cancellation with a partial network record is not reported as user cancellation`() {
        val terminal = FormalResultServiceTerminalPolicy.afterCancellation(
            autorun = false,
            testMode = AnebTestMode.NETWORK_BASIC,
            committedRunId = "run-system-cancelled",
            committedResultWasCancelled = true,
            cancelRequested = false,
        )

        assertNull(terminal)
    }

    @Test
    fun `unrelated service cancellation does not manufacture a terminal state`() {
        val terminal = FormalResultServiceTerminalPolicy.afterCancellation(
            autorun = false,
            testMode = AnebTestMode.AI_REALTIME_SIMULATION,
            committedRunId = null,
            cancelRequested = false,
        )

        assertNull(terminal)
    }

    @Test
    fun `durable result also wins an exception observed after commit`() {
        val terminal = FormalResultServiceTerminalPolicy.afterFailure(
            autorun = true,
            testMode = AnebTestMode.AI_REALTIME_SIMULATION,
            committedRunId = "run-after-commit",
            message = "ignored failure",
        )

        assertEquals(
            ProbeRunSession.Completed(
                autorun = true,
                runId = "run-after-commit",
                testMode = AnebTestMode.AI_REALTIME_SIMULATION,
            ),
            terminal,
        )
    }
}
