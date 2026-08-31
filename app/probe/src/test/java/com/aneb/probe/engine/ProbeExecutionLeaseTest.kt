package com.aneb.probe.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeExecutionLeaseTest {
    @Test
    fun mainSpecialAndPrototypeRunsShareOneIdentitySafeExecutionSlot() {
        val shared = ProbeExecutionLease()

        val mainToken = shared.tryAcquire()
        assertNotNull(mainToken)
        assertNull("special run overlapped the main run", shared.tryAcquire())
        assertNull("Prototype run overlapped the main run", shared.tryAcquire())

        val foreignToken = checkNotNull(ProbeExecutionLease().tryAcquire())
        assertFalse(shared.release(foreignToken))
        assertNull("wrong token unlocked the main run", shared.tryAcquire())

        assertTrue(shared.release(checkNotNull(mainToken)))
        val specialToken = shared.tryAcquire()
        assertNotNull(specialToken)
        assertFalse(shared.release(mainToken))
        assertNull("stale main token unlocked the special run", shared.tryAcquire())

        assertTrue(shared.release(checkNotNull(specialToken)))
        val prototypeToken = shared.tryAcquire()
        assertNotNull(prototypeToken)
        assertTrue(shared.release(checkNotNull(prototypeToken)))
    }

    @Test
    fun mainSpecialAndPrototypeConcurrentContendersProduceExactlyOneOwner() {
        val shared = ProbeExecutionLease()
        val ready = CountDownLatch(3)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)
        try {
            val contenders = listOf("main", "special", "prototype").map {
                executor.submit<ProbeExecutionLease.Token?> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    shared.tryAcquire()
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()

            val winners = contenders.map { it.get(5, TimeUnit.SECONDS) }.filterNotNull()
            assertEquals(1, winners.size)
            assertNull("a fourth contender overlapped the winner", shared.tryAcquire())
            assertTrue(shared.release(winners.single()))
            val successor = checkNotNull(shared.tryAcquire())
            assertTrue(shared.release(successor))
        } finally {
            start.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun mainAndSpecialProductionHostsDefaultToTheProcessLease() {
        val currentOwner = checkNotNull(ProbeExecutionLease.process.tryAcquire())
        var foregroundStarts = 0
        var workerStarts = 0
        var rejectedStops = 0
        try {
            val main = ProbeRunServiceExecutionHost(
                beginForeground = { foregroundStarts += 1 },
                publish = {},
                finishRejected = { rejectedStops += 1 },
            )
            val special = ProbeSpecialRunServiceExecutionHost(
                beginForeground = { foregroundStarts += 1 },
                publish = {},
                finishRejected = { rejectedStops += 1 },
            )

            assertSame(
                ProbeExecutionStartResult.ProcessBusy,
                main.start(false, AnebTestMode.TOKEN_EXPERIENCE) {
                    workerStarts += 1
                    true
                },
            )
            assertSame(
                ProbeExecutionStartResult.ProcessBusy,
                special.start(SpecialRunKind.CONTINUITY) {
                    workerStarts += 1
                    true
                },
            )
            assertEquals(2, foregroundStarts)
            assertEquals(2, rejectedStops)
            assertEquals(0, workerStarts)
            assertNull(ProbeExecutionLease.process.tryAcquire())
        } finally {
            assertTrue(ProbeExecutionLease.process.release(currentOwner))
        }
    }
}
