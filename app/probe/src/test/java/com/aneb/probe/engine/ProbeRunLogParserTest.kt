package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
