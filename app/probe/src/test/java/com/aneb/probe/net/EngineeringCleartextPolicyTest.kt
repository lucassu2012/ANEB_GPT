package com.aneb.probe.net

import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class EngineeringCleartextPolicyTest {
    @Test
    fun interceptorReportsDisallowedCleartextAsIOExceptionOnAsyncCall() {
        val client = OkHttpClient.Builder()
            .addInterceptor(EngineeringCleartextPolicy.interceptor(prototypePrivate = false))
            .build()
        val latch = CountDownLatch(1)
        val failure = AtomicReference<IOException?>(null)
        val request = Request.Builder().url("http://120.77.174.207:8443/api/v1/echo").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                failure.set(e)
                latch.countDown()
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                latch.countDown()
            }
        })
        assertTrue("async call must invoke onFailure instead of crashing dispatcher", latch.await(5, TimeUnit.SECONDS))
        assertNotNull(failure.get())
        assertTrue(failure.get()!!.message.orEmpty().contains("cleartext network traffic is disabled"))
    }
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
