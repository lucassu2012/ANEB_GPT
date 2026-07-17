package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayCredentialVaultTest {
    @Test fun handlesAreOneTimeAndCannotSwapConcurrentTokens() {
        GatewayCredentialVault.clear()
        val first = "a".repeat(64)
        val second = "b".repeat(64)
        val firstHandle = GatewayCredentialVault.put(first, 1)
        val secondHandle = GatewayCredentialVault.put(second, 2)
        assertEquals(second, GatewayCredentialVault.take(secondHandle, 3))
        assertEquals(first, GatewayCredentialVault.take(firstHandle, 4))
        assertNull(GatewayCredentialVault.take(firstHandle, 5))
    }

    @Test fun staleAndExplicitlyDiscardedCredentialsDisappear() {
        GatewayCredentialVault.clear()
        val stale = GatewayCredentialVault.put("c".repeat(64), 1)
        assertNull(GatewayCredentialVault.take(stale, 60_000_000_002L))
        val discarded = GatewayCredentialVault.put("d".repeat(64), 10)
        GatewayCredentialVault.discard(discarded)
        assertNull(GatewayCredentialVault.take(discarded, 11))
    }
}
