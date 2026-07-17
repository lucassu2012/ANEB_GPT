package com.aneb.probe.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressPolicyTest {
    @Test
    fun `https node root is normalized and accepted`() {
        val result = ServerAddressPolicy.validate("  https://node.example:8443/  ", allowCleartext = false)

        assertTrue(result.isValid)
        assertEquals("https://node.example:8443", result.normalized)
        assertNull(result.message)
    }

    @Test
    fun `release rejects cleartext while debug can admit it`() {
        assertFalse(ServerAddressPolicy.validate("http://10.0.2.2:8080", allowCleartext = false).isValid)
        assertTrue(ServerAddressPolicy.validate("http://10.0.2.2:8080", allowCleartext = true).isValid)
    }

    @Test
    fun `missing scheme host and unsupported scheme fail closed`() {
        assertFalse(ServerAddressPolicy.validate("node.example:8443", false).isValid)
        assertFalse(ServerAddressPolicy.validate("https:///api", false).isValid)
        assertFalse(ServerAddressPolicy.validate("ftp://node.example", false).isValid)
    }

    @Test
    fun `credentials query fragment and api path are rejected`() {
        assertFalse(ServerAddressPolicy.validate("https://user:secret@node.example", false).isValid)
        assertFalse(ServerAddressPolicy.validate("https://node.example?mode=test", false).isValid)
        assertFalse(ServerAddressPolicy.validate("https://node.example#status", false).isValid)
        assertFalse(ServerAddressPolicy.validate("https://node.example/api/v1", false).isValid)
        assertFalse(ServerAddressPolicy.validate("https://node.example:65536", false).isValid)
    }

    @Test
    fun `manual readiness prioritizes offline state and explains settings recovery`() {
        assertTrue(
            ManualRunReadiness.blocker(false, "bad", false)!!.contains("没有可用网络"),
        )
        assertTrue(
            ManualRunReadiness.blocker(true, "bad", false)!!.contains("设置 > 高级"),
        )
        assertNull(
            ManualRunReadiness.blocker(true, ProbeSettings.DEFAULT_SERVER_URL, false),
        )
    }
}
