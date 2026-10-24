package com.aneb.probe.research.business

import org.junit.Assert.*
import org.junit.Test

class VideoPreparationCaptureTest {
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
