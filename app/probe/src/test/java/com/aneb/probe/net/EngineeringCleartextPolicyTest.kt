package com.aneb.probe.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineeringCleartextPolicyTest {
    @Test
    fun productionPrototypeFlagRejectsCleartextExceptExactPrototypePrivateRoutes() {
        val privateRun = "http://192.168.1.20:18088/api/v1/prototype/runs".toHttpUrl()
        val privateCapability = "http://192.168.1.20:18088/api/v1/prototype/capabilities".toHttpUrl()
        val privateEvidence = "http://192.168.1.20:18088/api/v1/prototype/campaigns/evidence".toHttpUrl()

        assertFalse(EngineeringCleartextPolicy.isAllowed(privateRun, prototypeCleartextEnabled = true, prototypePrivate = false))
        assertTrue(EngineeringCleartextPolicy.isAllowed(privateRun, prototypeCleartextEnabled = true, prototypePrivate = true))
        assertTrue(EngineeringCleartextPolicy.isAllowed(privateCapability, prototypeCleartextEnabled = true, prototypePrivate = true))
        assertTrue(EngineeringCleartextPolicy.isAllowed(privateEvidence, prototypeCleartextEnabled = true, prototypePrivate = true))
        assertFalse(
            EngineeringCleartextPolicy.isAllowed(
                "http://192.168.1.20:18088/api/v1/prototype/runs?redirect=1".toHttpUrl(),
                prototypeCleartextEnabled = true,
                prototypePrivate = true,
            ),
        )
        assertFalse(
            EngineeringCleartextPolicy.isAllowed(
                "http://8.8.8.8:18088/api/v1/prototype/runs".toHttpUrl(),
                prototypeCleartextEnabled = true,
                prototypePrivate = true,
            ),
        )
        assertTrue(
            EngineeringCleartextPolicy.isAllowed(
                "https://prototype.example.com/api/v1/prototype/runs".toHttpUrl(),
                prototypeCleartextEnabled = true,
                prototypePrivate = false,
            ),
        )
        assertFalse(EngineeringCleartextPolicy.isAllowed(privateRun, prototypeCleartextEnabled = false, prototypePrivate = true))
    }
}
