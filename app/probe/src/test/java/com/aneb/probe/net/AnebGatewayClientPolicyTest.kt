package com.aneb.probe.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnebGatewayClientPolicyTest {
    @Test fun acceptsOnlyPathlessAttestedHttpsManagementAddressWithConfigurablePort() {
        assertEquals("https://192.168.77.1:9444", AnebGatewayClient.normalizeBase(" https://192.168.77.1:9444/ "))
        assertEquals("https://192.168.77.1", AnebGatewayClient.normalizeBase("https://192.168.77.1"))
        listOf(
            "http://192.168.77.1:9444",
            "https://192.168.77.2:9444",
            "https://10.0.0.1:9444",
            "https://example.com:9444",
            "https://8.8.8.8:9444",
            "https://user@192.168.77.1:9444",
            "https://192.168.77.1:9444/control",
            "https://192.168.77.1:9444/?token=x",
        ).forEach { value ->
            assertTrue("accepted $value", runCatching { AnebGatewayClient.normalizeBase(value) }.isFailure)
        }
    }

    @Test fun tokenMustBeFullEntropyEncodingShape() {
        assertTrue(runCatching { AnebGatewayClient("https://192.168.77.1:9444", "a".repeat(64)) }.isSuccess)
        assertTrue(runCatching { AnebGatewayClient("https://192.168.77.1:9444", "a".repeat(32)) }.isFailure)
        assertTrue(runCatching { AnebGatewayClient("https://192.168.77.1:9444", "z".repeat(64)) }.isFailure)
    }
}
