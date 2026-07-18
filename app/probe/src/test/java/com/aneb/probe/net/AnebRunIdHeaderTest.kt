package com.aneb.probe.net

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnebRunIdHeaderTest {
    @Test
    fun `run audit headers preserve exact fixed values and default absent`() {
        val withoutRunId = Request.Builder()
            .url("https://aneb.test/api/v1/serverinfo")
            .withAnebRunId(null, AnebAuditRole.REACHABILITY)
            .build()
        val runId = "019f731f-602a-72b3-abeb-85afa315e0f0"
        val businessRequest = Request.Builder()
            .url("https://aneb.test/api/v1/echo")
            .withAnebRunId(runId)
            .build()
        val capabilityRequest = Request.Builder()
            .url("https://aneb.test/api/v1/serverinfo")
            .withAnebRunId(runId, AnebAuditRole.CAPABILITY)
            .build()

        assertNull(withoutRunId.header(ANEB_RUN_ID_HEADER))
        assertNull(withoutRunId.header(ANEB_AUDIT_ROLE_HEADER))
        assertEquals(runId, businessRequest.header(ANEB_RUN_ID_HEADER))
        assertNull(businessRequest.header(ANEB_AUDIT_ROLE_HEADER))
        assertEquals(runId, capabilityRequest.header(ANEB_RUN_ID_HEADER))
        assertEquals("capability", capabilityRequest.header(ANEB_AUDIT_ROLE_HEADER))
    }
}
