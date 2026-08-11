package com.aneb.probe.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TestEngineResultSubmissionTest {
    @Test
    fun `test engine completion seam commits locally and never enters upload when consent is unknown`() =
        runBlocking {
            val events = mutableListOf<String>()
            var requestConstructionCount = 0
            var transportConstructionCount = 0

            val outcome = TestEngine.completeResultSubmission(
                coordinator = ResultSubmissionCoordinator(),
                localCommit = { reportStatus ->
                    events += "local:$reportStatus"
                },
                requestFactory = {
                    requestConstructionCount += 1
                    error("TestEngine must not construct an upload request without consent")
                },
                transportFactory = {
                    transportConstructionCount += 1
                    error("TestEngine must not construct an upload transport without consent")
                },
            )

            assertEquals(ResultSubmissionOutcome.NotConsented, outcome)
            assertEquals(listOf("local:not_consented"), events)
            assertEquals(0, requestConstructionCount)
            assertEquals(0, transportConstructionCount)
        }
}
