package com.aneb.probe.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutorunIntentGateTest {
    @Test
    fun `first creation reads and immediately removes autorun`() {
        var extra = true
        var reads = 0
        var removes = 0

        val requested = consumeAutorunOnce(
            isFirstCreation = true,
            enabled = true,
            readAutorun = {
                reads++
                extra
            },
            removeAutorun = {
                removes++
                extra = false
            },
        )

        assertTrue(requested)
        assertFalse(extra)
        assertEquals(1, reads)
        assertEquals(1, removes)
    }

    @Test
    fun `recreation neither reads nor removes autorun`() {
        var reads = 0
        var removes = 0

        val requested = consumeAutorunOnce(
            isFirstCreation = false,
            enabled = true,
            readAutorun = {
                reads++
                true
            },
            removeAutorun = { removes++ },
        )

        assertFalse(requested)
        assertEquals(0, reads)
        assertEquals(0, removes)
    }

    @Test
    fun `consumed autorun cannot trigger a second time`() {
        var extra = true
        fun consume() = consumeAutorunOnce(
            isFirstCreation = true,
            enabled = true,
            readAutorun = { extra },
            removeAutorun = { extra = false },
        )

        assertTrue(consume())
        assertFalse(consume())
    }

    @Test
    fun `disabled release automation scrubs without reading or running`() {
        var reads = 0
        var removes = 0

        val requested = consumeAutorunOnce(
            isFirstCreation = true,
            enabled = false,
            readAutorun = {
                reads++
                true
            },
            removeAutorun = { removes++ },
        )

        assertFalse(requested)
        assertEquals(0, reads)
        assertEquals(1, removes)
    }
}
