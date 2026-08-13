package com.aneb.probe.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeOwnedCollectorCleanupTest {
    @Test
    fun `owned collector cleanup waits for cancelled collector terminal`() = runBlocking {
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                cleanupEntered.complete(Unit)
                withContext(NonCancellable) { releaseCleanup.await() }
            }
        }

        val cleanup = async(start = CoroutineStart.UNDISPATCHED) {
            cancelOwnedCollectorsAndJoin(collector)
        }
        withTimeout(1_000) { cleanupEntered.await() }
        assertFalse(cleanup.isCompleted)

        releaseCleanup.complete(Unit)
        cleanup.await()
        assertTrue(collector.isCompleted)
    }
}
