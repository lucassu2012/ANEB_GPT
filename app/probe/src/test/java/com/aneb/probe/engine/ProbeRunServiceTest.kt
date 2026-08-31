package com.aneb.probe.engine

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeRunServiceTest {
    @Test
    fun processBusyMainRunStartsNoWorkerAndStopsOnlyItsForegroundShell() {
        val shared = ProbeExecutionLease()
        val currentOwner = checkNotNull(shared.tryAcquire())
        val sessions = mutableListOf<ProbeRunSession>()
        var foregroundStarts = 0
        var rejectedStops = 0
        var workerStarts = 0
        val host = ProbeRunServiceExecutionHost(
            beginForeground = { foregroundStarts += 1 },
            publish = sessions::add,
            finishRejected = { rejectedStops += 1 },
            executionLease = shared,
        )

        val result = host.start(
            autorun = false,
            testMode = AnebTestMode.TOKEN_EXPERIENCE,
            startOwned = {
                workerStarts += 1
                true
            },
        )

        assertSame(ProbeExecutionStartResult.ProcessBusy, result)
        assertEquals(1, foregroundStarts)
        assertEquals(0, workerStarts)
        assertEquals(1, rejectedStops)
        val failed = sessions.single() as ProbeRunSession.Failed
        assertEquals(false, failed.autorun)
        assertEquals(AnebTestMode.TOKEN_EXPERIENCE, failed.testMode)
        assertEquals("另一项测试仍在结束处理中，请稍后重试。", failed.message)
        assertNull("busy main run released the current owner", shared.tryAcquire())
        assertTrue(shared.release(currentOwner))
    }

    @Test
    fun sameHostDuplicateAndWrongTokenCannotReplaceOrReleaseOwnedMainRun() {
        val shared = ProbeExecutionLease()
        val sessions = mutableListOf<ProbeRunSession>()
        var foregroundStarts = 0
        var rejectedStops = 0
        var workerStarts = 0
        var ownedToken: ProbeExecutionLease.Token? = null
        val host = ProbeRunServiceExecutionHost(
            beginForeground = { foregroundStarts += 1 },
            publish = sessions::add,
            finishRejected = { rejectedStops += 1 },
            executionLease = shared,
        )

        val started = host.start(false, AnebTestMode.TOKEN_EXPERIENCE) { token ->
            ownedToken = token
            workerStarts += 1
            sessions += ProbeRunSession.Running(false, testMode = AnebTestMode.TOKEN_EXPERIENCE)
            true
        }
        assertTrue(started is ProbeExecutionStartResult.Started)
        assertSame(
            ProbeExecutionStartResult.AlreadyActive,
            host.start(false, AnebTestMode.NETWORK_BASIC) {
                workerStarts += 1
                true
            },
        )
        assertEquals(1, foregroundStarts)
        assertEquals(1, workerStarts)
        assertEquals(0, rejectedStops)
        assertEquals(
            listOf(ProbeRunSession.Running(false, testMode = AnebTestMode.TOKEN_EXPERIENCE)),
            sessions,
        )

        val foreignToken = checkNotNull(ProbeExecutionLease().tryAcquire())
        assertTrue(!host.finish(foreignToken))
        assertNull(shared.tryAcquire())
        assertTrue(host.finish(checkNotNull(ownedToken)))
        val successor = checkNotNull(shared.tryAcquire())
        assertTrue(shared.release(successor))
    }

    @Test
    fun mainServiceReleasesOnlyAfterAuxiliaryCollectorsFinish() {
        val source = Files.readAllBytes(productionSource()).toString(UTF_8)
        val ownedWorker = source.substringAfter("runJob = serviceScope.launch {")
            .substringBefore("\n            true")
        val finallyMarker = "                } finally {"
        assertTrue(ownedWorker.contains(finallyMarker))
        val cleanup = ownedWorker.substringAfter(finallyMarker)
        assertEquals(1, Regex("executionHost\\.finish\\(").findAll(source).count())
        val markers = listOf(
            "withContext(NonCancellable)",
            "telemetryJob?.cancelAndJoin()",
            "resultJob?.cancelAndJoin()",
            "stopForeground(STOP_FOREGROUND_REMOVE)",
            "stopSelf()",
            "executionHost.finish(executionToken)",
        )
        markers.forEach { marker -> assertTrue("missing cleanup marker: $marker", cleanup.contains(marker)) }
        markers.zipWithNext().forEach { (before, after) ->
            assertTrue(
                "$before must precede $after",
                cleanup.indexOf(before) < cleanup.indexOf(after),
            )
        }
        val cancelBody = source.substringAfter("private fun cancelRun()")
            .substringBefore("private fun failBeforeRun")
        val destroyBody = source.substringAfter("override fun onDestroy()")
            .substringBefore("companion object")
        assertTrue(!cancelBody.contains("executionHost.finish"))
        assertTrue(!destroyBody.contains("executionHost.finish"))
    }

    private fun productionSource(): Path = listOf(
        Path.of("app/probe/src/main/java/com/aneb/probe/engine/ProbeRunService.kt"),
        Path.of("src/main/java/com/aneb/probe/engine/ProbeRunService.kt"),
        Path.of("../../app/probe/src/main/java/com/aneb/probe/engine/ProbeRunService.kt"),
    ).firstOrNull(Files::isRegularFile)
        ?: error("ProbeRunService.kt fixture was not found")
}
