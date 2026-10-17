package com.aneb.probe.ui.research

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManualResearchMediaPlaybackOwnerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun newSelectionTakesOverAndStoppedRequestsCannotRevivePlayback() {
        // TEST ONLY: file handles exercise selection ownership, not media decoding.
        val firstFile = temporary.newFile("test-only-r1.mp4")
        val secondFile = temporary.newFile("test-only-r2.mp4")
        val owner = ManualResearchMediaPlaybackOwner()
        val first = owner.request("TEST-ONLY-SOURCE", "R1")
        assertTrue(owner.activate(first, firstFile, foreground = true))
        assertEquals(firstFile, owner.selection?.file)

        val second = owner.request("TEST-ONLY-SOURCE", "R2")
        assertNull("A new explicit request revokes the old playback before IO returns", owner.selection)
        assertFalse("The earlier result cannot retake playback", owner.activate(first, firstFile, foreground = true))
        assertTrue(owner.activate(second, secondFile, foreground = true))
        owner.release(first)
        assertEquals("Disposing an old card cannot stop the new owner", secondFile, owner.selection?.file)

        val pending = owner.request("TEST-ONLY-SOURCE", "R1")
        owner.stop()
        assertFalse("A late IO result after stop cannot activate", owner.activate(pending, firstFile, foreground = true))
        assertNull(owner.selection)

        val background = owner.request("TEST-ONLY-SOURCE", "R2")
        assertFalse("Background results cannot publish a player", owner.activate(background, secondFile, foreground = false))
        assertNull(owner.selection)
    }
}
