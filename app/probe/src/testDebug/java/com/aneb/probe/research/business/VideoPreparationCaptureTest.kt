package com.aneb.probe.research.business

import org.junit.Assert.*
import org.junit.Test

class VideoPreparationCaptureTest {
    @Test fun `late public source is retained before stopping the caller at the original cutoff`() {
        var now = 9L
        val original = Any()
        val retained = mutableListOf<Any>()
        assertThrows(IllegalStateException::class.java) {
            VideoPreparationCapture.readSource<Any>({ now }, 10L, { now = 11L; original }, { retained += it })
        }
        assertEquals(listOf(original), retained)
    }

    @Test fun `at cutoff no new source read starts and timely original is returned unchanged`() {
        assertThrows(IllegalStateException::class.java) {
            VideoPreparationCapture.readSource({ 10L }, 10L,
                { fail("source read started at cutoff") }, { fail("absent source was retained") })
        }
        val original = Any()
        var retained: Any? = null
        assertSame(original, VideoPreparationCapture.readSource({ 9L }, 10L, { original }, { retained = it }))
        assertSame(original, retained)
    }

    @Test fun `source preparation does not request system recording consent`() {
        var systemConsentRequests = 0
        VideoPreparationCapture.requestIfRequired("SOURCE_PREPARATION") { systemConsentRequests++ }
        assertEquals(0, systemConsentRequests)
    }

    @Test fun `reference qualification keeps its mandatory system recording request`() {
        var systemConsentRequests = 0
        VideoPreparationCapture.requestIfRequired("REFERENCE_QUALIFICATION") { systemConsentRequests++ }
        assertEquals(1, systemConsentRequests)
    }

    @Test fun `unknown mode cannot request consent and system failures propagate`() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoPreparationCapture.requestIfRequired("UNKNOWN") { fail("unknown mode requested recording") }
        }
        val failure = IllegalStateException("system consent unavailable")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            VideoPreparationCapture.requestIfRequired("REFERENCE_QUALIFICATION") { throw failure }
        })
    }
}
