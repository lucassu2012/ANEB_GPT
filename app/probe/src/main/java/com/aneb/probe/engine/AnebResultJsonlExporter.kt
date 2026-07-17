package com.aneb.probe.engine

import com.aneb.probe.data.ResultEnvelopeEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Deterministic JSONL wrapper over already-finalized result envelopes.
 *
 * It deliberately does not parse-and-reserialize the body, join Profiles, fill missing context,
 * or rerun a scorer. Identity and the canonical digest are verified before exact stored JSON is
 * emitted. UI/file sharing can call this core later without gaining authority to change results.
 */
internal object AnebResultJsonlExporter {
    fun export(records: List<ResultEnvelopeEntity>): String {
        require(records.map { it.runId }.distinct().size == records.size) {
            "aneb_result_export_duplicate_run_id"
        }
        val ordered = records.sortedWith(compareBy<ResultEnvelopeEntity> { it.startedAtEpochMs }.thenBy { it.runId })
        ordered.forEach(::verify)
        return ordered.joinToString(separator = "\n", postfix = if (ordered.isEmpty()) "" else "\n") { it.bodyJson }
    }

    private fun verify(record: ResultEnvelopeEntity) {
        require(record.schemaVersion == TokenResultEnvelopeV1.SCHEMA_VERSION) {
            "aneb_result_export_schema_not_supported:${record.schemaVersion}"
        }
        require(TokenRuntimeIntegrity.canonicalSha256(record.bodyJson) == record.canonicalSha256) {
            "aneb_result_export_digest_mismatch:${record.runId}"
        }
        val body = runCatching { Json.parseToJsonElement(record.bodyJson).jsonObject }
            .getOrElse { throw IllegalArgumentException("aneb_result_export_body_invalid:${record.runId}", it) }
        require(body["schema_version"]?.jsonPrimitive?.content == record.schemaVersion) {
            "aneb_result_export_schema_identity_mismatch:${record.runId}"
        }
        require(body["test_type"]?.jsonPrimitive?.content == record.testType) {
            "aneb_result_export_test_type_mismatch:${record.runId}"
        }
        require(body["run"]?.jsonObject?.get("run_id")?.jsonPrimitive?.content == record.runId) {
            "aneb_result_export_run_identity_mismatch:${record.runId}"
        }
    }
}
