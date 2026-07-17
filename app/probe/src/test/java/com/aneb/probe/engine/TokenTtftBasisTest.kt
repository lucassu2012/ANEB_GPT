package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenTtftBasisTest {
    @Test fun monotonicBasisSeparatesServerProcessingFromEndToEndTtft() {
        val basis = deriveTokenTtftBasis(
            firstArrivalNanos = 2_340_000_000,
            mappedUploadReceiveEndNanos = 2_000_000_000,
            firstCorrectedServerUs = 1_300_000,
            serverUploadReceiveEndUs = 1_000_000,
        )

        assertEquals(300.0, basis.serverProcessingMs!!, 0.0)
        assertEquals(340.0, basis.ttftMs!!, 0.0)
    }

    @Test fun negativeOrUnmappedIntervalsRemainMissing() {
        val negative = deriveTokenTtftBasis(
            firstArrivalNanos = 1,
            mappedUploadReceiveEndNanos = 2,
            firstCorrectedServerUs = 9,
            serverUploadReceiveEndUs = 10,
        )
        assertNull(negative.serverProcessingMs)
        assertNull(negative.ttftMs)

        val missing = deriveTokenTtftBasis(null, null, null, null)
        assertNull(missing.serverProcessingMs)
        assertNull(missing.ttftMs)
    }
}
