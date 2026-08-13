package com.aneb.probe.apiprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ApiProbePersistenceContractTest {
    @Test
    fun classifiesOnlyClosedSafePersistenceOutcomes() {
        assertEquals(
            setOf(
                "HTTP_ACCEPTED",
                "HTTP_REJECTED",
                "TRANSPORT_FAILED",
                "ADAPTER_FAILED",
                "LEGACY_UNCLASSIFIED",
            ),
            ApiProbePersistenceContract.ResultCode.entries.map { it.wireValue }.toSet(),
        )
        assertEquals(
            setOf("HTTP", "IO", "LOCAL", "NONE"),
            ApiProbePersistenceContract.TransportClass.entries.map { it.wireValue }.toSet(),
        )
        assertEquals(
            setOf("COMPLETED", "TRUNCATED_TAIL", "FAILED", "LEGACY_UNCLASSIFIED"),
            ApiProbePersistenceContract.CompletionClass.entries.map { it.wireValue }.toSet(),
        )
        assertEquals(
            setOf("NONE"),
            ApiProbePersistenceContract.DigestScope.entries.map { it.wireValue }.toSet(),
        )

        val matrix = listOf(
            Case(
                outcome = http(statusCode = 200),
                resultCode = "HTTP_ACCEPTED",
                transportClass = "HTTP",
                completionClass = "COMPLETED",
            ),
            Case(
                outcome = http(statusCode = 200, streamTailTruncated = true),
                resultCode = "HTTP_ACCEPTED",
                transportClass = "HTTP",
                completionClass = "TRUNCATED_TAIL",
            ),
            Case(
                outcome = http(
                    statusCode = 200,
                    streamTailTruncated = true,
                    protocolErrorPresent = true,
                ),
                resultCode = "ADAPTER_FAILED",
                transportClass = "HTTP",
                completionClass = "FAILED",
            ),
            Case(
                outcome = http(statusCode = 400),
                resultCode = "HTTP_REJECTED",
                transportClass = "HTTP",
                completionClass = "FAILED",
            ),
            Case(
                outcome = failed(ApiProbePersistenceContract.Failure.IO),
                resultCode = "TRANSPORT_FAILED",
                transportClass = "IO",
                completionClass = "FAILED",
            ),
            Case(
                outcome = failed(ApiProbePersistenceContract.Failure.LOCAL),
                resultCode = "ADAPTER_FAILED",
                transportClass = "LOCAL",
                completionClass = "FAILED",
            ),
        )

        matrix.forEach { case ->
            val classified = ApiProbePersistenceContract.classify(case.outcome)
            assertEquals(case.resultCode, classified.resultCode.wireValue)
            assertEquals(case.transportClass, classified.transportClass.wireValue)
            assertEquals(case.completionClass, classified.completionClass.wireValue)
            assertEquals(0L, classified.errorBodyByteCount)
            assertNull(classified.errorBodySha256)
            assertEquals("NONE", classified.errorBodyDigestScope.wireValue)
            assertFalse(classified.errorBodyTruncated)

            classified.wireStrings().forEach { wire ->
                assertFalse(wire.contains(FREE_TEXT_SENTINEL))
            }
        }
    }

    private fun http(
        statusCode: Int,
        streamTailTruncated: Boolean = false,
        protocolErrorPresent: Boolean = false,
    ) = ApiProbePersistenceContract.Outcome.Http(
        statusCode = statusCode,
        streamTailTruncated = streamTailTruncated,
        protocolErrorPresent = protocolErrorPresent,
        untrustedDetail = FREE_TEXT_SENTINEL,
    )

    private fun failed(failure: ApiProbePersistenceContract.Failure) =
        ApiProbePersistenceContract.Outcome.Failed(
            failure = failure,
            untrustedDetail = FREE_TEXT_SENTINEL,
        )

    private fun ApiProbePersistenceContract.Classification.wireStrings(): List<String> = listOf(
        resultCode.wireValue,
        transportClass.wireValue,
        completionClass.wireValue,
        errorBodyDigestScope.wireValue,
    )

    private data class Case(
        val outcome: ApiProbePersistenceContract.Outcome,
        val resultCode: String,
        val transportClass: String,
        val completionClass: String,
    )

    private companion object {
        const val FREE_TEXT_SENTINEL =
            "RAW_FREE_TEXT_SENTINEL_email@example.invalid_https://private.invalid/token"
    }
}
