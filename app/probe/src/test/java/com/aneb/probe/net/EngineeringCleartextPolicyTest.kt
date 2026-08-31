package com.aneb.probe.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineeringCleartextPolicyTest {
    @Test
    fun engineeringBuildRejectsCleartextExceptExactPrototypePrivateRoutes() {
        val privateRun = "http://192.168.1.20:18088/api/v1/prototype/runs".toHttpUrl()
        val privateCapability = "http://192.168.1.20:18088/api/v1/prototype/capabilities".toHttpUrl()

        assertFalse(EngineeringCleartextPolicy.isAllowed(privateRun, engineering = true, prototypePrivate = false))
        assertTrue(EngineeringCleartextPolicy.isAllowed(privateRun, engineering = true, prototypePrivate = true))
        assertTrue(EngineeringCleartextPolicy.isAllowed(privateCapability, engineering = true, prototypePrivate = true))
        assertFalse(
            EngineeringCleartextPolicy.isAllowed(
                "http://192.168.1.20:18088/api/v1/prototype/runs?redirect=1".toHttpUrl(),
                engineering = true,
                prototypePrivate = true,
            ),
        )
        assertFalse(
            EngineeringCleartextPolicy.isAllowed(
                "http://8.8.8.8:18088/api/v1/prototype/runs".toHttpUrl(),
                engineering = true,
                prototypePrivate = true,
            ),
        )
        assertTrue(
            EngineeringCleartextPolicy.isAllowed(
                "https://prototype.example.com/api/v1/prototype/runs".toHttpUrl(),
                engineering = true,
                prototypePrivate = false,
            ),
        )
        assertTrue(EngineeringCleartextPolicy.isAllowed(privateRun, engineering = false, prototypePrivate = false))
    }
}
