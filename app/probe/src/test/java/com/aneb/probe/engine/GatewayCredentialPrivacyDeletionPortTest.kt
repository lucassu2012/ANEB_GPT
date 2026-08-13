package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayCredentialPrivacyDeletionPortTest {
    @Test
    fun `clears vault owned credential references with an exact count`() {
        GatewayCredentialVault.clear()
        val first = GatewayCredentialVault.put("a".repeat(64))
        val second = GatewayCredentialVault.put("b".repeat(64))

        val result = GatewayCredentialPrivacyDeletionPort().clearOwnedReferences()

        assertEquals(GatewayCredentialPrivacyDeletionResult.Cleared(referenceCount = 2), result)
        assertNull(GatewayCredentialVault.take(first))
        assertNull(GatewayCredentialVault.take(second))
    }
}
