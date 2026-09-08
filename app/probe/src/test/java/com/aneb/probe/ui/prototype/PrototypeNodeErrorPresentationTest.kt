package com.aneb.probe.ui.prototype

import com.aneb.probe.prototype.PrototypeNodeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrototypeNodeErrorPresentationTest {
    @Test
    fun `incompatible node presents P007 without allowing a campaign`() {
        val incompatible = PrototypeNodeState.ConnectedIncompatible(
            "http://192.168.1.20:18088", "claim scope does not match",
        )
        val presentation = prototypeNodeErrorPresentation(incompatible, null)
        assertEquals("P007_CONTRACT_MISMATCH · Connected, incompatible", presentation?.title)
        assertTrue(presentation!!.detail.startsWith("claim scope does not match"))
        assertFalse(incompatible.canStartQuick)
    }

    @Test
    fun `unreachable node presents the required P006 code`() {
        val presentation = prototypeNodeErrorPresentation(null, "Unable to reach this Prototype node.")
        assertEquals("P006_NODE_UNREACHABLE · Node unavailable", presentation?.title)
        assertTrue(presentation!!.detail.startsWith("Unable to reach this Prototype node."))
    }

    @Test
    fun `node failures explain recovery without claiming a new result`() {
        val unreachable = prototypeNodeErrorPresentation(null, "Unable to reach this Prototype node.")!!
        assertTrue(unreachable.detail.contains("Check the node URL, the shared LAN and the launcher firewall guidance."))
        assertTrue(unreachable.detail.contains("No campaign was started. Saved results are unchanged."))
        val incompatible = prototypeNodeErrorPresentation(
            PrototypeNodeState.ConnectedIncompatible("http://192.168.1.20:18088", "claim scope does not match"),
            null,
        )!!
        assertTrue(incompatible.detail.contains("Use the APK and server from the same release package, then test the connection again."))
        assertTrue(incompatible.detail.contains("No campaign was started. Saved results are unchanged."))
    }

    @Test
    fun `node failures retain their diagnostic detail without enabling a campaign`() {
        val unreachable = prototypeNodeErrorPresentation(null, "Unable to reach this Prototype node.")
        assertTrue(unreachable!!.detail.startsWith("Unable to reach this Prototype node."))
        val incompatible = PrototypeNodeState.ConnectedIncompatible(
            "http://192.168.1.20:18088", "claim scope does not match",
        )
        assertTrue(prototypeNodeErrorPresentation(incompatible, null)!!.detail.startsWith("claim scope does not match"))
        assertFalse(incompatible.canStartQuick)
        assertNull(prototypeNodeErrorPresentation(null, null))
    }
}
