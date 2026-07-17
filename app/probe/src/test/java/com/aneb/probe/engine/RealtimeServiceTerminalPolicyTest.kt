package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RealtimeServiceTerminalPolicyTest {
    @Test
    fun `durably committed result wins a concurrent cancel request`() {
        val terminal = RealtimeServiceTerminalPolicy.afterCancellation(
            autorun = false,
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
        val terminal = RealtimeServiceTerminalPolicy.afterFlow(
            autorun = true,
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
        val terminal = RealtimeServiceTerminalPolicy.afterCancellation(
            autorun = false,
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
    fun `unrelated service cancellation does not manufacture a terminal state`() {
        val terminal = RealtimeServiceTerminalPolicy.afterCancellation(
            autorun = false,
            committedRunId = null,
            cancelRequested = false,
        )

        assertNull(terminal)
    }

    @Test
    fun `durable result also wins an exception observed after commit`() {
        val terminal = RealtimeServiceTerminalPolicy.afterFailure(
            autorun = true,
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
