package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultNetworkTransitionTrackerTest {
    @Test
    fun `initial callback is baseline and stable capability replay emits nothing`() {
        val tracker = DefaultNetworkTransitionTracker<String>()

        assertTrue(tracker.onAvailable("android-network-101").isEmpty())
        assertTrue(tracker.onCapabilities("android-network-101", "wifi", true, true).isEmpty())
        assertTrue(tracker.onCapabilities("android-network-101", "wifi", true, true).isEmpty())
    }

    @Test
    fun `loss and recovery use per-run aliases without leaking platform handles`() {
        val tracker = DefaultNetworkTransitionTracker<String>()
        tracker.onAvailable("android-network-101")
        tracker.onCapabilities("android-network-101", "wifi", true, true)

        val loss = tracker.onLost("android-network-101")
        val available = tracker.onAvailable("android-network-207")
        val notReady = tracker.onCapabilities("android-network-207", "cellular", false, true)
        val ready = tracker.onCapabilities("android-network-207", "cellular", true, true)
        val all = loss + available + notReady + ready

        assertEquals(
            listOf(
                "default_network_lost path=path-1 transport=wifi",
                "default_network_available path=path-2",
                "default_network_ready path=path-2 transport=cellular validated=true not_suspended=true",
            ),
            all,
        )
        assertTrue(all.none { it.contains("101") || it.contains("207") || it.contains("android-network") })
    }

    @Test
    fun `direct switch and capability degradation are deduplicated and recoverable`() {
        val tracker = DefaultNetworkTransitionTracker<String>()
        tracker.onAvailable("wifi-handle")
        tracker.onCapabilities("wifi-handle", "wifi", true, true)

        val changed = tracker.onAvailable("cell-handle")
        val ready = tracker.onCapabilities("cell-handle", "cellular", true, true)
        val validationLost = tracker.onCapabilities("cell-handle", "cellular", false, true)
        val duplicate = tracker.onCapabilities("cell-handle", "cellular", false, true)
        val restored = tracker.onCapabilities("cell-handle", "cellular", true, true)

        assertEquals(
            listOf("default_network_changed from_path=path-1 from_transport=wifi to_path=path-2"),
            changed,
        )
        assertEquals(
            listOf("default_network_ready path=path-2 transport=cellular validated=true not_suspended=true"),
            ready,
        )
        assertEquals(
            listOf("default_network_validation_lost path=path-2 transport=cellular"),
            validationLost,
        )
        assertTrue(duplicate.isEmpty())
        assertEquals(
            listOf("default_network_validation_restored path=path-2 transport=cellular"),
            restored,
        )
    }

    @Test
    fun `loss of a non-default path is ignored`() {
        val tracker = DefaultNetworkTransitionTracker<String>()
        tracker.onAvailable("default")
        tracker.onCapabilities("default", "wifi", true, true)

        assertTrue(tracker.onLost("background").isEmpty())
    }
}
