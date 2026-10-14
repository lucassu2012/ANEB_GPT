package com.aneb.probe.research.engineering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PublicUiIdleTimeoutScopeTest {
    @Test fun temporarilyUsesZeroAndRestoresExactOriginalAfterBodyReturn() {
        // Only the external Configurator state is substituted, not a Core/Native business object.
        var timeoutMillis = 8_321L
        val scope = PublicUiIdleTimeoutScope({ timeoutMillis }, { timeoutMillis = it })
        val bodyResult = Any()

        val returned = scope.run {
            assertEquals("the actual body must observe temporary idle timeout zero", 0L, timeoutMillis)
            bodyResult
        }

        assertSame(bodyResult, returned)
        assertEquals(8_321L, timeoutMillis)
    }

    @Test fun restoresExactOriginalAndPreservesBodyFailure() {
        var timeoutMillis = 4_217L
        val scope = PublicUiIdleTimeoutScope({ timeoutMillis }, { timeoutMillis = it })
        val primary = IllegalStateException("body rejected")

        val observed = runCatching {
            scope.run<Unit> {
                assertEquals(0L, timeoutMillis)
                throw primary
            }
        }.exceptionOrNull()

        assertSame(primary, observed)
        assertEquals(4_217L, timeoutMillis)
    }

    @Test fun preservesBodyFailureWhenOriginalTimeoutRestoreAlsoFails() {
        var timeoutMillis = 9_671L
        val restoration = IllegalStateException("external Configurator restore failed")
        val scope = PublicUiIdleTimeoutScope({ timeoutMillis }, { requested ->
            if (requested == 9_671L && timeoutMillis == 0L) throw restoration
            timeoutMillis = requested
        })
        val primary = IllegalStateException("original body rejection")

        val observed = runCatching {
            scope.run<Unit> {
                assertEquals(0L, timeoutMillis)
                throw primary
            }
        }.exceptionOrNull()

        assertSame("the original body failure must survive restore failure", primary, observed)
        assertSame(restoration, observed?.suppressed?.singleOrNull())
        assertEquals(0L, timeoutMillis) // Failed external restoration is not a restored/closed success.
    }
}
