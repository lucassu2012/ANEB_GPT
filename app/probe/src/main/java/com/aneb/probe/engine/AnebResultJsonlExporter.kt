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
    enum class RejectionKind {
        UNSUPPORTED_SCHEMA,
        INTEGRITY,
    }

    class VerificationException internal constructor(
        val kind: RejectionKind,
        message: String,
        cause: Throwable? = null,
    ) : IllegalArgumentException(message, cause)

    data class RejectedRecord(
        val runId: String,
        val kind: RejectionKind,
        val reason: String,
    )

    data class VerifiableSelection(
        val accepted: List<ResultEnvelopeEntity>,
        val rejected: List<RejectedRecord>,
    )

    fun export(records: List<ResultEnvelopeEntity>): String {
        require(records.map { it.runId }.distinct().size == records.size) {
            "aneb_result_export_duplicate_run_id"
        }
        val ordered = records.sortedWith(compareBy<ResultEnvelopeEntity> { it.startedAtEpochMs }.thenBy { it.runId })
        ordered.forEach(::verify)
        return ordered.joinToString(separator = "\n", postfix = if (ordered.isEmpty()) "" else "\n") { it.bodyJson }
    }

    /**
     * A legacy/corrupt record must not permanently block the user's whole archive. Each envelope
     * remains fail-closed: only independently verified records are accepted, and every rejection
     * is returned so the UI can report a transparent partial export rather than omit it silently.
     */
    fun selectVerifiable(records: List<ResultEnvelopeEntity>): VerifiableSelection {
        require(records.map { it.runId }.distinct().size == records.size) {
            "aneb_result_export_duplicate_run_id"
        }
        val accepted = mutableListOf<ResultEnvelopeEntity>()
        val rejected = mutableListOf<RejectedRecord>()
        records.sortedWith(compareBy<ResultEnvelopeEntity> { it.startedAtEpochMs }.thenBy { it.runId })
            .forEach { record ->
                try {
                    verify(record)
                    accepted += record
                } catch (error: Exception) {
                    rejected += RejectedRecord(
                        runId = record.runId,
                        kind = (error as? VerificationException)?.kind ?: RejectionKind.INTEGRITY,
                        reason = error.message ?: "aneb_result_export_unknown_integrity_failure",
                    )
                }
            }
        return VerifiableSelection(accepted = accepted, rejected = rejected)
    }

    private fun verify(record: ResultEnvelopeEntity) {
        if (record.schemaVersion !in SUPPORTED_SCHEMA_VERSIONS) {
            reject(
                RejectionKind.UNSUPPORTED_SCHEMA,
                "aneb_result_export_schema_not_supported:${record.schemaVersion}",
            )
        }
        if (TokenRuntimeIntegrity.canonicalSha256(record.bodyJson) != record.canonicalSha256) {
            reject(RejectionKind.INTEGRITY, "aneb_result_export_digest_mismatch:${record.runId}")
        }
        val body = runCatching { Json.parseToJsonElement(record.bodyJson).jsonObject }
            .getOrElse {
                reject(RejectionKind.INTEGRITY, "aneb_result_export_body_invalid:${record.runId}", it)
            }
        if (body["schema_version"]?.jsonPrimitive?.content != record.schemaVersion) {
            reject(RejectionKind.INTEGRITY, "aneb_result_export_schema_identity_mismatch:${record.runId}")
        }
        if (body["test_type"]?.jsonPrimitive?.content != record.testType) {
            reject(RejectionKind.INTEGRITY, "aneb_result_export_test_type_mismatch:${record.runId}")
        }
        if (body["run"]?.jsonObject?.get("run_id")?.jsonPrimitive?.content != record.runId) {
            reject(RejectionKind.INTEGRITY, "aneb_result_export_run_identity_mismatch:${record.runId}")
        }
    }

    private fun reject(kind: RejectionKind, message: String, cause: Throwable? = null): Nothing {
        throw VerificationException(kind, message, cause)
    }

    private val SUPPORTED_SCHEMA_VERSIONS = setOf("aneb-result-v1", TokenResultEnvelopeV2.SCHEMA_VERSION)
}
