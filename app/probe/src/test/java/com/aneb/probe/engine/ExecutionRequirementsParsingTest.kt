package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExecutionRequirementsParsingTest {
    @Test
    fun `profile parser reads optional machine readable execution requirements`() {
        val profile = ProfileParser.parseSingle(profileJson(includeRequirements = true))

        val requirements = requireNotNull(profile.executionRequirements)
        assertEquals("aneb-execution-requirements", requirements.contractId)
        assertEquals("1.0.0", requirements.contractVersion)
        assertEquals("aneb-token-simulation-engine", requirements.clientEngine.contractId)
        assertEquals("2.0.0", requirements.serverCapabilityReceipt.maxVersionExclusive)
        assertEquals(listOf("echo", "token_sim", "download"), requirements.requiredPrimitives.map { it.primitiveId })
    }

    @Test
    fun `legacy profile without execution requirements remains parseable`() {
        val profile = ProfileParser.parseSingle(profileJson(includeRequirements = false))

        assertNull(profile.executionRequirements)
    }

    private fun profileJson(includeRequirements: Boolean): String {
        val requirements = if (!includeRequirements) "" else """
            ,"execution_requirements":{
              "contract_id":"aneb-execution-requirements",
              "contract_version":"1.0.0",
              "client_engine":{"contract_id":"aneb-token-simulation-engine","min_version":"1.0.0","max_version_exclusive":"2.0.0"},
              "server_capability_receipt":{"contract_id":"aneb-server-capability-receipt","min_version":"1.0.0","max_version_exclusive":"2.0.0"},
              "required_primitives":[
                {"primitive_id":"echo","wire_contract_id":"aneb-echo-v1"},
                {"primitive_id":"token_sim","wire_contract_id":"aneb-token-task-v1"},
                {"primitive_id":"download","wire_contract_id":"aneb-download-v1"}
              ]
            }
        """.trimIndent()
        return """
            {
              "profile_id":"token_multimodal_quick",
              "version":"1.2.0",
              "contract_version":"aneb-profile-v2",
              "mode_id":"token_simulation"$requirements
            }
        """.trimIndent()
    }
}
