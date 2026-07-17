package com.aneb.probe.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshingSessionResourceTest {
    private data class Lease(val id: Int, var usable: Boolean = true)
    private data class Client(val leaseId: Int?)

    @Test
    fun `healthy fixed lease is reused across sessions`() = runBlocking {
        val first = Lease(1)
        var acquireCount = 0
        val provider = provider(first) { acquireCount += 1; Lease(2) }

        val one = provider.forSession()
        val two = provider.forSession()

        assertSame(one.resource, two.resource)
        assertEquals(1, two.generation)
        assertFalse(two.refreshed)
        assertEquals(0, acquireCount)
    }

    @Test
    fun `lost fixed lease is reacquired before next session`() = runBlocking {
        val released = mutableListOf<Int>()
        val first = Lease(1)
        val provider = provider(first, released) { Lease(2) }
        assertEquals(1, provider.forSession().resource.leaseId)

        first.usable = false
        val recovered = provider.forSession()

        assertEquals(2, recovered.resource.leaseId)
        assertEquals(2, recovered.generation)
        assertTrue(recovered.refreshed)
        assertEquals(listOf(1), released)
    }

    @Test
    fun `failed reacquire does not reuse dead client`() {
        val released = mutableListOf<Int>()
        val first = Lease(1, usable = false)
        val provider = provider(first, released) { error("same_transport_unavailable") }

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { provider.forSession() }
        }

        assertEquals("same_transport_unavailable", failure.message)
        assertEquals(listOf(1), released)
    }

    @Test
    fun `retry after failed reacquire still obtains a fresh lease`() = runBlocking {
        var attempts = 0
        val first = Lease(1, usable = false)
        val provider = provider(first) {
            attempts++
            if (attempts == 1) error("temporarily_unavailable")
            Lease(2)
        }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { provider.forSession() }
        }
        val recovered = provider.forSession()

        assertEquals(2, recovered.resource.leaseId)
        assertTrue(recovered.refreshed)
        assertEquals(2, attempts)
    }

    @Test
    fun `auto mode never creates a synthetic binding`() = runBlocking {
        var acquireCount = 0
        val provider = RefreshingSessionResource<Lease, Client>(
            initialLease = null,
            refreshEnabled = false,
            isUsable = { it.usable },
            acquire = { acquireCount += 1; Lease(1) },
            release = {},
            create = { Client(it?.id) },
        )

        val resolution = provider.forSession()

        assertEquals(null, resolution.resource.leaseId)
        assertEquals(0, resolution.generation)
        assertFalse(resolution.refreshed)
        assertEquals(0, acquireCount)
    }

    @Test
    fun `close releases current lease exactly once`() {
        val released = mutableListOf<Int>()
        val provider = provider(Lease(1), released) { Lease(2) }

        provider.close()
        provider.close()

        assertEquals(listOf(1), released)
    }

    private fun provider(
        initial: Lease,
        released: MutableList<Int> = mutableListOf(),
        acquire: suspend () -> Lease,
    ) = RefreshingSessionResource(
        initialLease = initial,
        refreshEnabled = true,
        isUsable = { it.usable },
        acquire = acquire,
        release = { released += it.id },
        create = { Client(it?.id) },
    )
}
