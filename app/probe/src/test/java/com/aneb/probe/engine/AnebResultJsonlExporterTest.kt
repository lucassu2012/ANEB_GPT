package com.aneb.probe.engine

import com.aneb.probe.data.ResultEnvelopeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AnebResultJsonlExporterTest {
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
        assertEquals(valid.bodyJson + "\n", AnebResultJsonlExporter.export(selection.accepted))
    }

    private fun record(
        runId: String,
        startedAt: Long,
        testType: String = "token_simulation",
    ): ResultEnvelopeEntity {
        val body = """{"schema_version":"aneb-result-v1","test_type":"$testType","run":{"run_id":"$runId"}}"""
        return ResultEnvelopeEntity(
            runId = runId,
            schemaVersion = "aneb-result-v1",
            testType = testType,
            startedAtEpochMs = startedAt,
            serializedAtEpochMs = startedAt + 1,
            canonicalSha256 = TokenRuntimeIntegrity.canonicalSha256(body),
            bodyJson = body,
        )
    }
}
