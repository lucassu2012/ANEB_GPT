package com.aneb.probe.engine

import com.aneb.probe.data.ResultEnvelopeEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class NetworkResultEnvelopeSource(
    val profile: ScenarioProfile?,
    val profileHash: String? = null,
    val profileUri: String? = null,
)

internal data class NetworkResultEnvelopeInput(
    val result: BasicSpeedResult,
    val source: NetworkResultEnvelopeSource,
    val producer: AnebResultProducerContext,
    val device: AnebResultDeviceContext,
    val network: AnebResultNetworkContext,
    val endedAtEpochMs: Long,
    val status: String,
    val radio: FormalRadioEvidence = FormalRadioEvidence.notCollected("radio_evidence_not_provided"),
)

/** Freezes network-comprehensive results without re-running measurement or scoring logic. */
internal object NetworkResultEnvelopeV2 {
    const val TEST_TYPE = "network_comprehensive"

    fun build(input: NetworkResultEnvelopeInput): ResultEnvelopeEntity {
        val result = input.result
        val profile = input.source.profile
        val parsedEvidence = parseEvidence(result.evidenceJson)
        val invalidReason = parsedEvidence["invalid_reason"].nullableString()
            ?: result.transferErrors.firstOrNull()?.takeIf { result.verdict == TokenVerdict.INVALID }
            ?: "measurement_engine_invalid_result".takeIf { result.verdict == TokenVerdict.INVALID }
        val valid = invalidReason == null
        val profileResolved = profile != null && input.source.profileHash.isSha256Digest() && input.source.profileUri != null
        val missingFields = buildList {
            add("/context/endpoint/node_id")
            add("/context/endpoint/server_version")
            if (input.radio.collectionStatus != "collected") add("/context/radio")
            if (input.network.activeTransport == null) add("/context/network/active_transport")
            if (input.network.interfaceName == null) add("/context/network/interface_name")
            if (input.network.validated == null) add("/context/network/validated")
            if (input.network.notSuspended == null) add("/context/network/not_suspended")
            if (input.network.metered == null) add("/context/network/metered")
            if (input.network.vpnActive == null) add("/context/network/vpn_active")
            if (input.network.privateDnsMode == null) add("/context/network/private_dns_mode")
            add("/context/network/bound_network_generation")
            if (!profileResolved) {
                add("/profile")
                add("/profile/runtime_artifact_hash")
            }
        }.distinct().sorted()
        val definitions = profile?.measurements?.associateBy { it.metricId }.orEmpty()
        val evidenceRefs = buildJsonArray {
            if (profileResolved) {
                add(artifactEvidenceRef(
                    uri = requireNotNull(input.source.profileUri),
                    digest = requireNotNull(input.source.profileHash),
                ))
            }
            add(buildJsonObject {
                put("ref_id", "network-raw")
                put("kind", "inline_json_pointer")
                put("uri", "#/category_payload/raw_evidence")
                put("media_type", "application/json")
                put("digest", JsonNull)
                put("record_count", rawRecordCount(parsedEvidence))
                put("redaction", "none")
                put("description", "Inline latency, transfer, UDP, impairment and recovery evidence frozen by the network engine.")
            })
            if (input.radio.collectionStatus == "collected") add(input.radio.evidenceRefJson())
        }
        val serializedAt = input.endedAtEpochMs
        val body = buildJsonObject {
            put("schema_version", TokenResultEnvelopeV2.SCHEMA_VERSION)
            put("test_type", TEST_TYPE)
            put("producer", buildJsonObject {
                put("resolution_status", "resolved")
                put("component", input.producer.component)
                put("component_version", input.producer.componentVersion)
                put("exporter_version", TokenResultEnvelopeV2.EXPORTER_VERSION)
                put("build_type", input.producer.buildType)
                put("serialized_at_epoch_ms", serializedAt)
            })
            put("completeness", buildJsonObject {
                put("status", if (missingFields.isEmpty()) "complete" else "partial")
                put("missing_fields", JsonArray(missingFields.map(::JsonPrimitive)))
                put("notes", buildJsonArray {
                    add(JsonPrimitive("Only context observed by the formal network-comprehensive engine is included; absent fields were not reconstructed."))
                    add(JsonPrimitive("Network Profiles contain executable phases directly, so a separate runtime artifact is not applicable."))
                    add(JsonPrimitive(
                        if (input.radio.collectionStatus == "collected")
                            "Public Android radio observations were sampled at 1Hz; coordinates are excluded from this shareable result."
                        else "Radio context unavailable: ${input.radio.unavailableReason}.",
                    ))
                })
            })
            put("result_semantics", buildJsonObject {
                put("metrics_recomputed_on_export", false)
                put("score_recomputed_on_export", false)
                put("confidence_recomputed_on_export", false)
                put("conclusions_recomputed_on_export", false)
                put("missing_value_encoding", "null_with_explicit_state")
                put("invalid_run_policy", "retain_raw_evidence_and_suppress_score")
            })
            put("run", buildJsonObject {
                put("run_id", result.runId)
                put("started_at_epoch_ms", result.startedAtEpochMs)
                put("ended_at_epoch_ms", input.endedAtEpochMs)
                put("duration_ms", (input.endedAtEpochMs - result.startedAtEpochMs).coerceAtLeast(0L))
                put("status", input.status)
                put("validity", if (valid) "valid" else "invalid")
                put("invalid_reason_codes", buildJsonArray { invalidReason?.let { add(JsonPrimitive(it)) } })
                put("source_record_version", "room-v19-network-envelope-v1")
            })
            put("profile", profileReference(input.source, result, profileResolved))
            put("claim", buildJsonObject {
                put("scope", profile?.claimScope?.takeIf { profileResolved } ?: result.claimScope)
                put("measurement_subject", "ANEB controlled network-comprehensive path to the selected probe node")
                put("limitations", buildJsonArray {
                    add(JsonPrimitive("Application UDP non-return is not an estimate of IP-layer packet loss when the UDP probe is unavailable."))
                    add(JsonPrimitive("Synthetic or dedicated-gateway impairments do not alter RSRP, RSRQ, SINR or base-station scheduling."))
                    add(JsonPrimitive("Quality conclusions apply only to this device, node, Profile and captured context."))
                })
            })
            put("context", buildJsonObject {
                put("endpoint", buildJsonObject {
                    put("server_base", result.serverBase)
                    put("node_id", JsonNull)
                    put("server_version", JsonNull)
                })
                put("device", deviceContext(input.device))
                put("network", networkContext(input.network))
                put("radio", input.radio.contextJson())
            })
            put("evaluation", buildJsonObject {
                put("algorithm_versions", buildJsonObject {
                    put("measurement_engine_version", "network-comprehensive-engine-v1")
                    put("metric_catalog_id", profile?.measurementCatalogId?.takeIf(String::isNotBlank) ?: "network-comprehensive-measurements-v1")
                    put("target_set_id", profile?.evaluation?.targetSetId?.takeIf(String::isNotBlank))
                    put("score_policy_id", result.scorePolicyId)
                    put("score_anchor_policy_id", result.scoreAnchorPolicyId)
                    put("conclusion_policy_id", result.conclusionPolicyId)
                    put("calculation_origin", "measurement_engine")
                    put("finalized_at_epoch_ms", serializedAt)
                })
                put("score", scoreJson(result, valid, invalidReason))
                put("group_scores", buildJsonObject {
                    result.groupScores.toSortedMap().forEach { (id, value) -> put(id, value) }
                })
                put("metrics", buildJsonObject {
                    (definitions.keys + result.metrics.keys).toSortedSet().forEach { id ->
                        put(id, metricJson(result.metrics[id], definitions[id], input.radio))
                    }
                })
                put("conclusions", buildJsonArray {
                    result.conclusions.forEachIndexed { index, text ->
                        add(buildJsonObject {
                            put("conclusion_id", "network-conclusion-${(index + 1).toString().padStart(3, '0')}")
                            put("severity", conclusionSeverity(result.verdict, index))
                            put("policy_id", result.conclusionPolicyId)
                            put("text", text)
                            put("basis", buildJsonArray { add(JsonPrimitive("evidence:network-raw")) })
                        })
                    }
                })
            })
            put("evidence", buildJsonObject {
                put("raw_evidence_retained", true)
                put("invalid_evidence_retained", true)
                put("refs", evidenceRefs)
                put("environment_events", input.radio.environmentEventsJson())
            })
            put("category_payload", buildJsonObject {
                put("evidence_contract_version", "aneb-network-evidence-v1")
                put("variant", result.variant)
                put("transfer_summary", buildJsonObject {
                    put("download_bytes", result.downloadBytes)
                    put("upload_bytes", result.uploadBytes)
                    put("download_mbps", result.downloadMbps.finiteOrNull())
                    put("upload_mbps", result.uploadMbps.finiteOrNull())
                    put("post_load_rtt_ms", result.postLoadPingMs.finiteOrNull())
                    put("transfer_errors", JsonArray(result.transferErrors.filter(String::isNotBlank).map(::JsonPrimitive)))
                })
                put("raw_evidence", rawEvidenceJson(parsedEvidence, invalidReason))
            })
        }
        val bodyJson = Json.encodeToString(JsonObject.serializer(), body)
        return ResultEnvelopeEntity(
            runId = result.runId,
            schemaVersion = TokenResultEnvelopeV2.SCHEMA_VERSION,
            testType = TEST_TYPE,
            startedAtEpochMs = result.startedAtEpochMs,
            serializedAtEpochMs = serializedAt,
            canonicalSha256 = TokenRuntimeIntegrity.canonicalSha256(bodyJson),
            bodyJson = bodyJson,
        )
    }

    private fun rawEvidenceJson(source: JsonObject, invalidReason: String?): JsonObject {
        val requiredEvidenceKeys = setOf(
            "idle_rtt_ms", "loaded_rtt_ms", "download_windows_mbps", "upload_windows_mbps",
            "app_request_attempts", "app_request_successes", "udp_packets_sent", "udp_received_seqs",
            "udp_unavailable_reason", "handshakes", "synthetic_impairment", "gateway_impairment", "recovery",
        )
        val complete = requiredEvidenceKeys.all(source::containsKey)
        return buildJsonObject {
            put("invalid_reason", invalidReason)
            put("idle_rtt_ms", source["idle_rtt_ms"] ?: emptyArray())
            put("loaded_rtt_ms", source["loaded_rtt_ms"] ?: emptyArray())
            put("download_windows_mbps", source["download_windows_mbps"] ?: emptyArray())
            put("upload_windows_mbps", source["upload_windows_mbps"] ?: emptyArray())
            put("app_request_attempts", source["app_request_attempts"] ?: JsonPrimitive(0))
            put("app_request_successes", source["app_request_successes"] ?: JsonPrimitive(0))
            put("udp_packets_sent", source["udp_packets_sent"] ?: JsonPrimitive(0))
            put("udp_received_seqs", source["udp_received_seqs"] ?: emptyArray())
            put("udp_unavailable_reason", source["udp_unavailable_reason"] ?: JsonNull)
            put("handshakes", source["handshakes"] ?: emptyArray())
            put("synthetic_impairment", source["synthetic_impairment"] ?: JsonNull)
            put("gateway_impairment", normalizeGatewayEvidence(source["gateway_impairment"]))
            put("recovery", source["recovery"] ?: JsonNull)
            if (!complete) put("engine_failure_context", source)
        }
    }

    private fun normalizeGatewayEvidence(element: JsonElement?): JsonElement {
        if (element == null || element == JsonNull) return JsonNull
        val gateway = runCatching { element.jsonObject }.getOrNull() ?: return JsonNull
        val rawFingerprint = gateway["profile_fingerprint"].nullableString() ?: return JsonNull
        val normalized = when {
            rawFingerprint.matches(Regex("^[0-9a-f]{64}$")) -> "sha256:$rawFingerprint"
            rawFingerprint.isSha256Digest() -> rawFingerprint
            else -> return JsonNull
        }
        return JsonObject(gateway.toMutableMap().apply {
            put("profile_fingerprint", buildJsonObject {
                put("algorithm", "sha256")
                put("canonicalization", "go-struct-json-v1")
                put("value", normalized)
            })
        })
    }

    private fun scoreJson(result: BasicSpeedResult, valid: Boolean, invalidReason: String?): JsonObject {
        val state = when {
            !valid -> "suppressed_invalid"
            result.totalScore == null -> "not_computable"
            else -> "computed"
        }
        return buildJsonObject {
            put("state", state)
            put("value", result.totalScore.finiteOrNull())
            put("grade", result.grade)
            put("verdict", if (valid) result.verdict.name.lowercase() else "invalid")
            put("confidence", if (valid) result.confidence.name.lowercase() else "invalid")
            put("confidence_basis", buildJsonObject {
                put("method_id", result.confidenceMethodId)
                put("coverage_ratio", result.coverageRatio.finiteOrNull())
                put("minimum_sample_satisfied", result.minimumSampleSatisfied)
            })
            put("cap_reason", JsonNull)
            put(
                "not_computable_reason",
                if (state == "computed") null
                else result.notComputableReason
                    ?: invalidReason?.let { "invalid_run:$it" }
                    ?: "measurement_engine_did_not_compute_total_score",
            )
        }
    }

    private fun metricJson(
        metric: NetworkMetricEvidence?,
        definition: ProfileMeasurement?,
        radio: FormalRadioEvidence,
    ): JsonObject {
        checkNotNull(definition) { "network_result_metric_definition_missing:${metric?.metricId ?: "unknown"}" }
        val radioSeriesObserved = definition.domain == "radio_covariate" &&
            definition.aggregation == "time_series" && radio.collectionStatus == "collected"
        val observed = metric?.value?.isFinite() == true || radioSeriesObserved
        return buildJsonObject {
            put("label", definition.label)
            put("domain", normalizeDomain(definition.domain))
            put("unit", definition.unit)
            put("measurement_level", definition.measurementLevel)
            put("state", if (observed) "observed" else "missing")
            put("value", metric?.value.finiteOrNull())
            put("compliance_ratio", metric?.complianceRatio.finiteOrNull())
            put("sample_count", if (radioSeriesObserved) radio.samples.size else metric?.sampleCount ?: 0)
            put("minimum_sample_count", metric?.minimumSampleCount ?: definition.minimumSampleCount)
            put("source_event_ids", JsonArray(definition.sourceEventIds.map(::JsonPrimitive)))
            put("direction", definition.direction)
            put("required_for_score", definition.requiredForScore)
            put("quality_target", definition.qualityTarget?.let(::qualityTargetJson) ?: JsonNull)
            put("score", metric?.score.finiteOrNull())
            put("formula_id", definition.formulaId)
            put("aggregation", definition.aggregation)
            put("components", buildJsonObject { })
            put("source_evidence_ref_ids", buildJsonArray {
                add(JsonPrimitive(if (radioSeriesObserved) "radio-context" else "network-raw"))
            })
            put("invalid_reason", if (observed) null else if (metric == null) "measurement_not_emitted_by_current_engine" else "measurement_unavailable")
        }
    }

    private fun profileReference(
        source: NetworkResultEnvelopeSource,
        result: BasicSpeedResult,
        resolved: Boolean,
    ): JsonObject = buildJsonObject {
        put("resolution_status", if (resolved) "resolved" else "unavailable")
        put("contract_version", source.profile?.contractVersion.takeIf { resolved })
        put("profile_id", source.profile?.profileId ?: result.profileId)
        put("profile_version", source.profile?.version.takeIf { resolved })
        put("variant", result.variant)
        put("profile_fingerprint", digestJson(source.profileHash, resolved, "canonical-json-v1"))
        put("profile_evidence_ref_id", if (resolved) "profile-artifact" else null)
        put("runtime_artifact_status", if (resolved) "not_applicable" else "unavailable")
        put("runtime_artifact_hash", JsonNull)
        put("runtime_artifact_evidence_ref_id", JsonNull)
        put("source_uri", source.profileUri.takeIf { resolved })
    }

    private fun artifactEvidenceRef(uri: String, digest: String): JsonObject = buildJsonObject {
        put("ref_id", "profile-artifact")
        put("kind", "content_addressed_artifact")
        put("uri", uri)
        put("media_type", "application/json")
        put("digest", digestJson(digest, true, "canonical-json-v1"))
        put("record_count", 1)
        put("redaction", "none")
        put("description", "Canonical network-comprehensive Profile parsed for this run.")
    }

    private fun deviceContext(device: AnebResultDeviceContext): JsonObject = buildJsonObject {
        put("availability", "observed")
        put("manufacturer", device.manufacturer)
        put("model", device.model)
        put("os_name", "Android")
        put("os_release", device.osRelease)
        put("api_level", device.apiLevel)
        put("app_package", device.appPackage)
        put("app_version_name", device.appVersionName)
        put("app_version_code", device.appVersionCode)
    }

    private fun networkContext(network: AnebResultNetworkContext): JsonObject = buildJsonObject {
        put("availability", "observed")
        put("requested_transport", network.requestedTransport)
        put("active_transport", network.activeTransport)
        put("capabilities", JsonArray(network.capabilities.distinct().sorted().map(::JsonPrimitive)))
        put("interface_name", network.interfaceName)
        put("validated", network.validated)
        put("not_suspended", network.notSuspended)
        put("metered", network.metered)
        put("vpn_active", network.vpnActive)
        put("private_dns_mode", network.privateDnsMode)
        put("bound_network_generation", JsonNull)
        put("evidence_ref_ids", buildJsonArray { })
    }

    private fun qualityTargetJson(target: ProfileQualityTarget): JsonObject = buildJsonObject {
        put("operator", target.operator)
        put("value", target.value.finiteOrNull())
        put("values", buildJsonObject { target.values.toSortedMap().forEach { (key, value) -> put(key, value) } })
        put("policy_id", target.policyId)
        put("required_compliance_ratio", target.requiredComplianceRatio.finiteOrNull())
        put("provenance", target.provenance)
    }

    private fun parseEvidence(text: String): JsonObject = runCatching {
        Json.parseToJsonElement(text).jsonObject
    }.getOrElse { buildJsonObject { put("invalid_reason", "stored_network_evidence_json_invalid") } }

    private fun rawRecordCount(evidence: JsonObject): Int = listOf(
        "idle_rtt_ms", "loaded_rtt_ms", "download_windows_mbps", "upload_windows_mbps", "handshakes",
    ).sumOf { key -> (evidence[key] as? JsonArray)?.size ?: 0 }

    private fun emptyArray() = JsonArray(emptyList())

    private fun JsonElement?.nullableString(): String? = (this as? JsonPrimitive)
        ?.takeUnless { it == JsonNull || !it.isString }
        ?.content

    private fun digestJson(
        value: String?,
        enabled: Boolean,
        canonicalization: String,
    ): JsonElement = if (enabled && value != null) buildJsonObject {
        put("algorithm", "sha256")
        put("canonicalization", canonicalization)
        put("value", value)
    } else JsonNull

    private fun String?.isSha256Digest(): Boolean = this?.matches(Regex("^sha256:[0-9a-f]{64}$")) == true

    private fun Double?.finiteOrNull(): JsonElement = this?.takeIf(Double::isFinite)?.let(::JsonPrimitive) ?: JsonNull

    private fun normalizeDomain(domain: String): String = when (domain) {
        "business" -> "business"
        "network" -> "network"
        "radio" -> "radio"
        else -> "diagnostic"
    }

    private fun conclusionSeverity(verdict: TokenVerdict, index: Int): String = when {
        index > 0 -> "recommendation"
        verdict == TokenVerdict.FAIL || verdict == TokenVerdict.INVALID -> "failure"
        verdict == TokenVerdict.INCONCLUSIVE -> "warning"
        else -> "info"
    }
}
