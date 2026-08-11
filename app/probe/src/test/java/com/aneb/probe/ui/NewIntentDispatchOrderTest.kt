package com.aneb.probe.ui

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NewIntentDispatchOrderTest {
    @Test
    fun `release scrubs the same launch before framework dispatch`() {
        val launch = hostileLaunch()
        val events = mutableListOf<String>()
        var scrubCount = 0
        var frameworkCount = 0
        var frameworkLaunch: MutableLaunchProbe? = null
        var frameworkObservedExtras: Map<String, String>? = null

        dispatchReleaseNewIntent(
            launch = launch,
            scrubExternalLaunch = { observed: MutableLaunchProbe ->
                assertSame(launch, observed)
                scrubCount += 1
                observed.extras.clear()
                events += "SCRUB"
            },
            dispatchToFramework = { observed: MutableLaunchProbe ->
                frameworkCount += 1
                frameworkLaunch = observed
                frameworkObservedExtras = LinkedHashMap(observed.extras)
                events += "SUPER_DISPATCH"
            },
        )

        assertEquals(listOf("SCRUB", "SUPER_DISPATCH"), events)
        assertEquals(1, scrubCount)
        assertEquals(1, frameworkCount)
        assertEquals(emptyMap<String, String>(), frameworkObservedExtras)
        assertSame(launch, frameworkLaunch)
    }

    @Test
    fun `debug dispatches the original launch without scrubbing`() {
        val launch = hostileLaunch()
        val originalExtras = LinkedHashMap(launch.extras)
        val originalBytes = extrasBytes(originalExtras)
        val events = mutableListOf<String>()
        var frameworkCount = 0
        var frameworkLaunch: MutableLaunchProbe? = null
        var frameworkObservedExtras: Map<String, String>? = null

        dispatchDebugNewIntent(
            launch = launch,
            dispatchToFramework = { observed: MutableLaunchProbe ->
                frameworkCount += 1
                frameworkLaunch = observed
                frameworkObservedExtras = LinkedHashMap(observed.extras)
                events += "SUPER_DISPATCH_WITH_ORIGINAL"
            },
        )

        assertEquals(listOf("SUPER_DISPATCH_WITH_ORIGINAL"), events)
        assertEquals(1, frameworkCount)
        assertSame(launch, frameworkLaunch)
        assertEquals(originalExtras, frameworkObservedExtras)
        assertArrayEquals(originalBytes, extrasBytes(frameworkObservedExtras.orEmpty()))
        assertEquals(originalExtras, launch.extras)
    }

    private fun hostileLaunch() = MutableLaunchProbe(
        extras = linkedMapOf(
            "server" to "https://hostile.example:9443",
            "autorun" to "true",
            "future_unknown" to "future-hostile-value",
        ),
    )

    private fun extrasBytes(extras: Map<String, String>): ByteArray = extras.entries
        .joinToString(separator = "\u0000") { (key, value) -> "$key\u0000$value" }
        .toByteArray(StandardCharsets.UTF_8)

    private data class MutableLaunchProbe(
        val extras: LinkedHashMap<String, String>,
    )
}
