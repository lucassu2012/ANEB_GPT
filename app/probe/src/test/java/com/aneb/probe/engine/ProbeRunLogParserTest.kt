package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeRunLogParserTest {
    @Test fun `run id only comes from RUN_START contract`() {
        assertEquals("019f-test", ProbeRunLogParser.runId("RUN_START run_id=019f-test mode=quick"))
        assertNull(ProbeRunLogParser.runId("SCENARIO_START run_id=wrong profile=S1"))
        assertNull(ProbeRunLogParser.runId("RUN_START mode=quick"))
    }

    @Test fun `known progress keys map without changing source line`() {
        assertEquals("正在执行网络场景", ProbeRunLogParser.progressText("SCENARIO_START profile=S1"))
        assertNull(ProbeRunLogParser.progressText("TOKEN seq=1"))
    }

    @Test fun `local persistence failure can never become a completed public session`() {
        val terminalStatus = ProbeRunLogParser.terminalStatus(
            "RUN_END run_id=run-local-failed status=local_persistence_failed",
        )

        assertEquals(
            ProbeRunSession.Failed(
                autorun = false,
                message = "无法安全保存本次结果，因此未发送。",
                testMode = AnebTestMode.TOKEN_EXPERIENCE,
            ),
            LegacyTestEngineTerminalPolicy.afterFlow(
                autorun = false,
                testMode = AnebTestMode.TOKEN_EXPERIENCE,
                runId = "run-local-failed",
                terminalStatus = terminalStatus,
            ),
        )
    }

    @Test fun `completed public session requires matching start commit and terminal identities`() {
        val startedRunId = ProbeRunLogParser.runId("RUN_START run_id=run-committed mode=quick")
        val committedRunId = ProbeRunLogParser.localCommitRunId(
            "DB_WRITE run_id=run-committed scenarios=3 env_events_pending=0 radio_samples_pending=0",
        )
        val terminalLine = "RUN_END run_id=run-committed status=completed"

        assertEquals(
            ProbeRunSession.Completed(
                autorun = true,
                runId = "run-committed",
                testMode = AnebTestMode.TOKEN_EXPERIENCE,
            ),
            LegacyTestEngineTerminalPolicy.afterFlow(
                autorun = true,
                testMode = AnebTestMode.TOKEN_EXPERIENCE,
                runId = startedRunId,
                committedRunId = committedRunId,
                terminalRunId = ProbeRunLogParser.terminalRunId(terminalLine),
                terminalStatus = ProbeRunLogParser.terminalStatus(terminalLine),
            ),
        )
    }

    @Test fun `already claimed duplicate can never become a completed public session`() {
        val runId = "run-duplicate"
        val terminalLine = "RUN_END run_id=$runId status=upload_already_claimed"

        assertTrue(
            LegacyTestEngineTerminalPolicy.afterFlow(
                autorun = false,
                testMode = AnebTestMode.TOKEN_EXPERIENCE,
                runId = runId,
                committedRunId = runId,
                terminalRunId = ProbeRunLogParser.terminalRunId(terminalLine),
                terminalStatus = ProbeRunLogParser.terminalStatus(terminalLine),
            ) is ProbeRunSession.Failed,
        )
    }
}
