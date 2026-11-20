package com.aneb.probe.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadedNodePolicyTest {
    @Test fun debugLocalRoutesDoNotOpenLegacyOrReleaseCleartext() {
        val base = "http://10.10.191.194:18089"
        listOf("/api/v1/echo", "/api/v1/profiles", "/api/v1/download?bytes=1024&chunk_kb=16", "/api/v1/upload?run=abc-123").forEach {
            val url = "$base$it".toHttpUrl()
            assertTrue(LoadedNodePolicy.isAllowed(url, debug = true))
            assertFalse(LoadedNodePolicy.isAllowed(url, debug = false))
            assertFalse(EngineeringCleartextPolicy.isAllowed(url, prototypeCleartextEnabled = false, prototypePrivate = false))
        }
        listOf("http://8.8.8.8/api/v1/echo", "$base/other", "$base/api/v1/echo?redirect=1",
            "$base/api/v1/download?bytes=0&chunk_kb=16", "$base/api/v1/upload?run=a&run=b").forEach {
            assertFalse(LoadedNodePolicy.isAllowed(it.toHttpUrl(), debug = true))
        }
        assertTrue(LoadedNodePolicy.isAllowed("https://node.example/api/v1/echo".toHttpUrl(), debug = false))
    }
}
