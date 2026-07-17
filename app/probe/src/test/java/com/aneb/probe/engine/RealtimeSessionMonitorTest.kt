package com.aneb.probe.engine

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RealtimeSessionMonitorTest {
    @Test
    fun `synchronous session setup failure disables and joins the loaded RTT monitor`() = runBlocking {
        var disabled = false
        var monitorExited = false
        val monitor = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                monitorExited = true
            }
        }

        try {
            runRealtimeSessionWithMonitor(
                monitorJob = monitor,
                disableMonitor = { disabled = true },
            ) {
                throw IllegalArgumentException("bad_websocket_url")
            }
            fail("session setup failure must remain observable")
        } catch (_: IllegalArgumentException) {
            // The session owner handles the failure after its child monitor has stopped.
        }

        assertTrue(disabled)
        assertTrue(monitorExited)
    }

    @Test
    fun `normal session completion also joins the loaded RTT monitor`() = runBlocking {
        var monitorExited = false
        val monitor = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                monitorExited = true
            }
        }

        val value = runRealtimeSessionWithMonitor(
            monitorJob = monitor,
            disableMonitor = {},
        ) { "wire-result" }

        assertEquals("wire-result", value)
        assertTrue(monitorExited)
    }
}
