package com.aneb.probe.engine

import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.TokenSimArrival
import com.aneb.probe.net.TokenSimPrelude
import com.aneb.probe.net.TokenSimTaskPlan
import com.aneb.probe.net.TokenSimTaskResult
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionRequirementsStrictParsingTest {
    @Test
    fun `unknown or missing keys anywhere in execution requirements fail before traffic`() = runBlocking {
        val source = quickProfileText().replace("\r\n", "\n")
        val mutations = listOf(
            source.replace(
                "\"execution_requirements\": {\n    \"contract_id\"",
                "\"execution_requirements\": {\n    \"future_requirement\": true,\n    \"contract_id\"",
            ),
            source.replace(
                "\"client_engine\": {\n      \"contract_id\"",
                "\"client_engine\": {\n      \"future_engine\": true,\n      \"contract_id\"",
            ),
            source.replace(
                "\"server_capability_receipt\": {\n      \"contract_id\"",
                "\"server_capability_receipt\": {\n      \"future_receipt\": true,\n      \"contract_id\"",
            ),
            source.replace(
                "{\n        \"primitive_id\": \"echo\"",
                "{\n        \"future_primitive\": true,\n        \"primitive_id\": \"echo\"",
            ),
            source.replaceFirst("      \"min_version\": \"1.0.0\",\n", ""),
        )

        mutations.forEach { mutated ->
            val transport = NoTrafficTransport()
            val error = runCatching {
                val profile = ProfileParser.parseSingle(mutated)
                TokenExecutionContractGate.authorize(
                    serverBase = "https://aneb.test",
                    profile = profile,
                    profileCanonicalSha256 = TokenRuntimeIntegrity.canonicalSha256(mutated),
                    transport = transport,
                )
            }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message?.startsWith("execution_requirements_keys_invalid:") == true)
            assertEquals(0, transport.serverInfoRequests)
            assertEquals(0, transport.businessRequests)
        }
    }

    private class NoTrafficTransport : TokenExecutionTransport {
        var serverInfoRequests = 0
        var businessRequests = 0

        override suspend fun fetchServerInfo(url: String): AnebClient.HttpTextResult {
            serverInfoRequests++
            return AnebClient.HttpTextResult(200, "{}", null)
        }

        override suspend fun echo(url: String): AnebClient.EchoResult {
            businessRequests++
            error("business request must not run")
        }

        override suspend fun tokenSim(
            url: String,
            plan: TokenSimTaskPlan,
            uploadChunkBytes: Int,
            uploadChunkCadenceMs: Double,
            onUploadBytes: (Long, Long) -> Unit,
            onPrelude: (TokenSimPrelude, Long) -> Unit,
            onToken: (TokenSimArrival) -> Unit,
        ): TokenSimTaskResult {
            businessRequests++
            error("business request must not run")
        }

        override suspend fun downloadThroughput(
            url: String,
            onBytes: (Int, Long) -> Unit,
        ): AnebClient.TransferResult {
            businessRequests++
            error("business request must not run")
        }
    }

    private fun quickProfileText(): String {
        val path = repositoryRoot().resolve("profiles/published/token_multimodal_quick/profile.json")
        return Files.readAllBytes(path).toString(Charsets.UTF_8)
    }

    private fun repositoryRoot(): Path {
        var cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(6) {
            if (Files.isDirectory(cursor.resolve("profiles")) && Files.isDirectory(cursor.resolve("app"))) {
                return cursor
            }
            cursor = cursor.parent ?: return@repeat
        }
        error("repository root not found from ${System.getProperty("user.dir")}")
    }
}
