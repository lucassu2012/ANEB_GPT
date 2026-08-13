package com.aneb.probe.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnebClientRequestBuildFailureTest {
    @Test
    fun `fetchProfiles contains malformed URL failure without retaining raw input`() = runBlocking {
        val rawInput = "MALFORMED_URL_SENTINEL_B2"

        val result = AnebClient().fetchProfiles(rawInput)

        assertNull(result.httpCode)
        assertNull(result.body)
        assertEquals("REQUEST_BUILD_FAILED transport=LOCAL", result.error)
        assertFalse(result.toString().contains(rawInput))
        assertFalse(result.toString().contains("Exception"))
        assertFalse(result.toString().contains("java."))
        Unit
    }
}
