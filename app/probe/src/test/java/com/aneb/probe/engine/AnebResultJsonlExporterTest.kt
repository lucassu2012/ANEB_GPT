package com.aneb.probe.engine

import com.aneb.probe.data.ResultEnvelopeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AnebResultJsonlExporterTest {
    @Test fun verifiedBodyReturnsExactStoredBodyAndRejectsAnyIdentityDrift() {
        val hostile = "RAW_ENVELOPE_IDENTITY_SENTINEL"
        val valid = record(
            runId = "00000000-0000-7000-8000-000000000001",
            startedAt = 1_000L,
            schemaVersion = "aneb-result-v2",
        )
        var transportCalls = 0
        fun verifyThenTransport(record: ResultEnvelopeEntity): String {
            val body = AnebResultJsonlExporter.verifiedBody(record)
            transportCalls += 1
            return body
        }

        assertEquals(valid.bodyJson, verifyThenTransport(valid))
        assertEquals(1, transportCalls)

        val invalidRecords = listOf(
            valid.copy(canonicalSha256 = "sha256:" + "0".repeat(64)) to
                "aneb_result_export_digest_mismatch",
            valid.copy(schemaVersion = "aneb-result-v1") to
                "aneb_result_export_schema_identity_mismatch",
            valid.copy(schemaVersion = hostile) to
                "aneb_result_export_schema_not_supported",
            valid.copy(testType = hostile) to
                "aneb_result_export_test_type_mismatch",
            valid.copy(runId = hostile) to
                "aneb_result_export_run_identity_mismatch",
            valid.copy(bodyJson = hostile) to
                "aneb_result_export_body_invalid",
        )
        invalidRecords.forEach { (invalid, expectedCode) ->
            val callsBefore = transportCalls
            val error = assertThrows(AnebResultJsonlExporter.VerificationException::class.java) {
                verifyThenTransport(invalid)
            }
            assertEquals(expectedCode, error.message)
            assertEquals(null, error.cause)
            assertEquals(0, error.suppressed.size)
            assertEquals(0, error.toString().windowed(hostile.length).count { it == hostile })
            assertEquals(callsBefore, transportCalls)
        }
    }

    @Test fun sortsAndEmitsExactStoredBodies() {
        val later = record(
            "00000000-0000-7000-8000-000000000002",
            2_000L,
            "network_comprehensive",
        )
        val earlier = record(
            "00000000-0000-7000-8000-000000000001",
            1_000L,
            "token_simulation",
        )

        assertEquals(
            earlier.bodyJson + "\n" + later.bodyJson + "\n",
            AnebResultJsonlExporter.export(listOf(later, earlier)),
        )
    }

    @Test fun rejectsDigestDriftAndDuplicateRunIds() {
        val record = record("00000000-0000-7000-8000-000000000001", 1_000L)
        assertThrows(IllegalArgumentException::class.java) {
            AnebResultJsonlExporter.export(
                listOf(record.copy(bodyJson = record.bodyJson.replace("token_simulation", "network_comprehensive"))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnebResultJsonlExporter.export(listOf(record, record))
        }
    }

    @Test fun emptyHistoryProducesNoPhantomLine() {
        assertEquals("", AnebResultJsonlExporter.export(emptyList()))
    }

    @Test fun bulkSelectionRejectsCorruptHistoryWithoutBlockingValidRecords() {
        val valid = record("00000000-0000-7000-8000-000000000001", 1_000L)
        val corrupt = record(
            "00000000-0000-7000-8000-000000000002",
            2_000L,
            "network_comprehensive",
        ).copy(canonicalSha256 = "sha256:" + "0".repeat(64))

        val selection = AnebResultJsonlExporter.selectVerifiable(listOf(corrupt, valid))

        assertEquals(listOf(valid), selection.accepted)
        assertEquals(listOf(corrupt.runId), selection.rejected.map { it.runId })
        assertEquals(
            listOf(AnebResultJsonlExporter.RejectionKind.INTEGRITY),
            selection.rejected.map { it.kind },
        )
        assertEquals(valid.bodyJson + "\n", AnebResultJsonlExporter.export(selection.accepted))
    }

    @Test fun supportsPublishedV1AndCurrentV2WithoutRewritingEitherBody() {
        val v1 = record("00000000-0000-7000-8000-000000000001", 1_000L, schemaVersion = "aneb-result-v1")
        val v2 = record("00000000-0000-7000-8000-000000000002", 2_000L, schemaVersion = "aneb-result-v2")

        assertEquals(v1.bodyJson + "\n" + v2.bodyJson + "\n", AnebResultJsonlExporter.export(listOf(v2, v1)))
    }

    @Test fun reportsUnsupportedSchemaSeparatelyFromIntegrityFailure() {
        val unsupported = record(
            "00000000-0000-7000-8000-000000000001",
            1_000L,
            schemaVersion = "aneb-result-v3",
        )

        val selection = AnebResultJsonlExporter.selectVerifiable(listOf(unsupported))

        assertEquals(emptyList<ResultEnvelopeEntity>(), selection.accepted)
        assertEquals(AnebResultJsonlExporter.RejectionKind.UNSUPPORTED_SCHEMA, selection.rejected.single().kind)
        assertEquals(
            "aneb_result_export_schema_not_supported",
            selection.rejected.single().reason,
        )
    }

    private fun record(
        runId: String,
        startedAt: Long,
        testType: String = "token_simulation",
        schemaVersion: String = "aneb-result-v1",
    ): ResultEnvelopeEntity {
        val body = """{"schema_version":"$schemaVersion","test_type":"$testType","run":{"run_id":"$runId"}}"""
        return ResultEnvelopeEntity(
            runId = runId,
            schemaVersion = schemaVersion,
            testType = testType,
            startedAtEpochMs = startedAt,
            serializedAtEpochMs = startedAt + 1,
            canonicalSha256 = TokenRuntimeIntegrity.canonicalSha256(body),
            bodyJson = body,
        )
    }
}
