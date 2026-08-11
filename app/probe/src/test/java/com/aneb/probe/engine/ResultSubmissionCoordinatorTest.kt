package com.aneb.probe.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ResultSubmissionCoordinatorTest {
    @Test
    fun `unknown consent commits the local result and constructs no upload boundary`() = runBlocking {
        val events = mutableListOf<String>()
        var requestConstructionCount = 0
        var transportConstructionCount = 0

        val outcome = ResultSubmissionCoordinator().submit(
            localCommit = { reportStatus ->
                events += "local:$reportStatus"
            },
            requestFactory = {
                requestConstructionCount += 1
                error("upload request must not be constructed without consent")
            },
            transportFactory = {
                transportConstructionCount += 1
                error("upload transport must not be constructed without consent")
            },
        )

        assertEquals(ResultSubmissionOutcome.NotConsented, outcome)
        assertEquals("not_consented", outcome.reportStatus)
        assertEquals(listOf("local:not_consented"), events)
        assertEquals(0, requestConstructionCount)
        assertEquals(0, transportConstructionCount)
    }

    @Test
    fun `local commit failure returns a stable failure and constructs no upload boundary`() = runBlocking {
        var requestConstructionCount = 0
        var transportConstructionCount = 0

        val outcome = ResultSubmissionCoordinator().submit(
            localCommit = {
                error("local commit failed")
            },
            requestFactory = {
                requestConstructionCount += 1
                error("upload request must not be constructed after a local commit failure")
            },
            transportFactory = {
                transportConstructionCount += 1
                error("upload transport must not be constructed after a local commit failure")
            },
        )

        assertEquals(ResultSubmissionOutcome.LocalPersistenceFailed, outcome)
        assertEquals("local_persistence_failed", outcome.reportStatus)
        assertEquals(0, requestConstructionCount)
        assertEquals(0, transportConstructionCount)
    }
}
