package com.aneb.probe.engine

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeSpecialRunServiceTest {
    @Test
    fun processBusySpecialRunStartsNoWorkerAndStopsOnlyItsForegroundShell() {
        val shared = ProbeExecutionLease()
        val currentOwner = checkNotNull(shared.tryAcquire())
        val sessions = mutableListOf<SpecialRunSession>()
        var foregroundStarts = 0
        var rejectedStops = 0
        var workerStarts = 0
        val host = ProbeSpecialRunServiceExecutionHost(
            beginForeground = { _ -> foregroundStarts += 1 },
            publish = sessions::add,
            finishRejected = { rejectedStops += 1 },
            executionLease = shared,
        )

        val result = host.start(SpecialRunKind.CONTINUITY) {
            workerStarts += 1
            true
        }

        assertSame(ProbeExecutionStartResult.ProcessBusy, result)
        assertEquals(1, foregroundStarts)
        assertEquals(0, workerStarts)
        assertEquals(1, rejectedStops)
        val failed = sessions.single() as SpecialRunSession.Failed
        assertEquals(SpecialRunKind.CONTINUITY, failed.kind)
        assertEquals("另一项测试仍在结束处理中，请稍后重试。", failed.message)
        assertNull("busy special run released the current owner", shared.tryAcquire())
        assertTrue(shared.release(currentOwner))
    }

    @Test
    fun sameHostDuplicateCannotReplaceOrReleaseOwnedSpecialRun() {
        val shared = ProbeExecutionLease()
        val sessions = mutableListOf<SpecialRunSession>()
        var foregroundStarts = 0
        var rejectedStops = 0
        var workerStarts = 0
        var ownedToken: ProbeExecutionLease.Token? = null
        val host = ProbeSpecialRunServiceExecutionHost(
            beginForeground = { _ -> foregroundStarts += 1 },
            publish = sessions::add,
            finishRejected = { rejectedStops += 1 },
            executionLease = shared,
        )

        assertTrue(
            host.start(SpecialRunKind.CONTINUITY) { token ->
                ownedToken = token
                workerStarts += 1
                sessions += SpecialRunSession.Running(SpecialRunKind.CONTINUITY)
                true
            } is ProbeExecutionStartResult.Started,
        )
        assertSame(
            ProbeExecutionStartResult.AlreadyActive,
            host.start(SpecialRunKind.PROTOCOL_AB) {
                workerStarts += 1
                true
            },
        )
        assertEquals(1, foregroundStarts)
        assertEquals(1, workerStarts)
        assertEquals(0, rejectedStops)
        assertEquals(
            listOf(SpecialRunSession.Running(SpecialRunKind.CONTINUITY)),
            sessions,
        )
        assertNull(shared.tryAcquire())
        assertTrue(host.finish(checkNotNull(ownedToken)))
    }

    @Test
    fun specialServiceReleasesOnlyFromItsOwnedWorkerFinally() {
        val source = Files.readAllBytes(productionSource()).toString(UTF_8)
        val ownedWorker = source.substringAfter("job = scope.launch {")
            .substringBefore("\n            true")
        val finallyMarker = "                } finally {"
        assertTrue(ownedWorker.contains(finallyMarker))
        val cleanup = ownedWorker.substringAfter(finallyMarker)
        assertEquals(1, Regex("executionHost\\.finish\\(").findAll(source).count())
        val cancelBody = source.substringAfter("private fun cancelRun()")
            .substringBefore("private fun failBeforeRun")
        val destroyBody = source.substringAfter("override fun onDestroy()")
            .substringBefore("companion object")
        assertTrue(!cancelBody.contains("executionHost.finish"))
        assertTrue(!destroyBody.contains("executionHost.finish"))
        val markers = listOf(
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
    }

    private fun productionSource(): Path = listOf(
        Path.of("app/probe/src/main/java/com/aneb/probe/engine/ProbeSpecialRunService.kt"),
        Path.of("src/main/java/com/aneb/probe/engine/ProbeSpecialRunService.kt"),
        Path.of("../../app/probe/src/main/java/com/aneb/probe/engine/ProbeSpecialRunService.kt"),
    ).firstOrNull(Files::isRegularFile)
        ?: error("ProbeSpecialRunService.kt fixture was not found")
}
