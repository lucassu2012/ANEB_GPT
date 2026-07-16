package com.aneb.probe.net

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class SseBoundaryScannerObserverTest {
    @Test
    fun `完整 event 边界只回调到达戳且不改变原始流`() {
        val arrivals = mutableListOf<Long>()
        val scanner = SseBoundaryScanner(arrivals::add)
        val chunk = Buffer().writeUtf8(
            "event: token\ndata: {\"seq\":0}\n\n" +
                "event: token\ndata: {\"seq\":1}\n\n",
        )
        val bytes = chunk.size
        scanner.onRead(chunk, bytes, arrivalNanos = 123_456L)
        val raw = scanner.finish(eofNanos = 200_000L)

        assertEquals(listOf(123_456L, 123_456L), arrivals)
        assertEquals(2, raw.events.size)
        assertEquals(bytes, raw.totalBytes)
        assertEquals(false, raw.truncatedTail)
    }
}
