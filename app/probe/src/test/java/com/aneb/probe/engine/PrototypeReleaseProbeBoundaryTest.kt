package com.aneb.probe.engine

import com.aneb.probe.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PrototypeReleaseProbeBoundaryTest {
    @Test
    fun `prototype release rejects the ordinary external probe entry`() {
        val externalIntent = Any()

        assertNull(
            PrototypeReleaseProbeBoundary.ordinaryExternalEntry(
                source = externalIntent,
                isPrototypeRelease = true,
            ),
        )
        assertSame(
            externalIntent,
            PrototypeReleaseProbeBoundary.ordinaryExternalEntry(
                source = externalIntent,
                isPrototypeRelease = false,
            ),
        )
    }

    @Test
    fun `prototype release does not publish ordinary formal results`() = runBlocking {
        var publishCalls = 0
        suspend fun publish(): String {
            publishCalls += 1
            return "published"
        }

        assertNull(
            PrototypeReleaseProbeBoundary.publishOrdinaryResult(
                isPrototypeRelease = true,
                publish = ::publish,
            ),
        )
        assertEquals(0, publishCalls)

        assertEquals(
            "published",
            PrototypeReleaseProbeBoundary.publishOrdinaryResult(
                isPrototypeRelease = false,
                publish = ::publish,
            ),
        )
        assertEquals(1, publishCalls)
    }

    @Test
    fun `generated variant binds the prototype release boundary`() = runBlocking {
        val externalIntent = Any()
        var publishCalls = 0

        val admittedIntent = PrototypeReleaseProbeBoundary.ordinaryExternalEntry(externalIntent)
        val publication = PrototypeReleaseProbeBoundary.publishOrdinaryResult {
            publishCalls += 1
            "published"
        }

        if (BuildConfig.PROTOTYPE_RELEASE) {
            assertNull(admittedIntent)
            assertNull(publication)
            assertEquals(0, publishCalls)
        } else {
            assertSame(externalIntent, admittedIntent)
            assertEquals("published", publication)
            assertEquals(1, publishCalls)
        }
    }
}
