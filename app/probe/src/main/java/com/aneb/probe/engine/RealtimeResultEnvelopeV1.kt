package com.aneb.probe.engine

import com.aneb.probe.data.ResultEnvelopeEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class RealtimeResultEnvelopeSource(
    val profile: ScenarioProfile?,
    val profileHash: String? = null,
    val runtimeArtifactHash: String? = null,
    val profileUri: String? = null,
    val runtimeArtifactUri: String? = null,
)

internal data class RealtimeResultEnvelopeInput(
    val result: RealtimeSimulationResult,
    val source: RealtimeResultEnvelopeSource,
    val producer: AnebResultProducerContext,
    val device: AnebResultDeviceContext,
    val network: AnebResultNetworkContext,
    val endedAtEpochMs: Long,
    val status: String,
    val radio: FormalRadioEvidence = FormalRadioEvidence.notCollected("radio_evidence_not_provided"),
)

/** Freezes the AI realtime engine result before its Room transaction and before UI publication. */
internal object RealtimeResultEnvelopeV1 {
    const val TEST_TYPE = "ai_realtime_simulation"

    fun build(input: RealtimeResultEnvelopeInput): ResultEnvelopeEntity {
        val result = input.result
        val score = result.score
        val profile = input.source.profile
        val invalidReason = result.evidence.invalidReason
            ?: score.capReason?.takeIf { score.verdict == TokenVerdict.INVALID }
            ?: "measurement_engine_invalid_result".takeIf { score.verdict == TokenVerdict.INVALID }
        val valid = invalidReason == null
        val serializedAt = input.endedAtEpochMs
        val profileResolved = profile != null &&
            input.source.profileHash.isSha256Digest() &&
            input.source.runtimeArtifactHash.isSha256Digest() &&
            input.source.profileUri != null &&
            input.source.runtimeArtifactUri != null
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
        val profileMeasurements = profile?.measurements?.associateBy { it.metricId }.orEmpty()
        val evidenceRefs = buildJsonArray {
            if (profileResolved) {
                add(artifactEvidenceRef(
                    refId = "profile-artifact",
                    uri = requireNotNull(input.source.profileUri),
                    digest = requireNotNull(input.source.profileHash),
                    description = "Canonical AI realtime Profile parsed for this run.",
                ))
                add(artifactEvidenceRef(
                    refId = "runtime-artifact",
                    uri = requireNotNull(input.source.runtimeArtifactUri),
                    digest = requireNotNull(input.source.runtimeArtifactHash),
                    description = "Hash-bound AI realtime runtime plan executed for this run.",
                ))
            }
            add(buildJsonObject {
                put("ref_id", "realtime-raw")
                put("kind", "inline_json_pointer")
                put("uri", "#/category_payload/raw_evidence")
                put("media_type", "application/json")
                put("digest", JsonNull)
                put("record_count", result.evidence.sessions.size)
                put("redaction", "none")
                put("description", "Inline session, turn, frame, RTT and recovery evidence frozen by the realtime engine.")
            })
            if (input.radio.collectionStatus == "collected") add(input.radio.evidenceRefJson())
        }
        val body = buildJsonObject {
            put("schema_version", TokenResultEnvelopeV1.SCHEMA_VERSION)
            put("test_type", TEST_TYPE)
            put("producer", buildJsonObject {
                put("resolution_status", "resolved")
                put("component", input.producer.component)
                put("component_version", input.producer.componentVersion)
                put("exporter_version", TokenResultEnvelopeV1.EXPORTER_VERSION)
                put("build_type", input.producer.buildType)
                put("serialized_at_epoch_ms", serializedAt)
            })
            put("completeness", buildJsonObject {
                put("status", if (missingFields.isEmpty()) "complete" else "partial")
                put("missing_fields", JsonArray(missingFields.map(::JsonPrimitive)))
                put("notes", buildJsonArray {
                    add(JsonPrimitive("Only context observed by the formal AI realtime engine is included; absent fields were not reconstructed."))
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
                put("invalid_reason_codes", buildJsonArray {
                    invalidReason?.let { add(JsonPrimitive(it)) }
                })
                put("source_record_version", "room-v19-realtime-envelope-v1")
            })
            put("profile", profileReference(input.source, result, profileResolved))
            put("claim", buildJsonObject {
                put(
                    "scope",
                    if (result.variant == "recovery") "controlled_server_disconnect_recovery_to_probe_node"
                    else "application_end_to_end_to_probe_node",
                )
                put("measurement_subject", "ANEB controlled AI realtime interaction simulation to the selected probe node")
                put("limitations", buildJsonArray {
                    add(JsonPrimitive("The workload simulates AI realtime behavior and does not call a third-party AI API."))
                    add(JsonPrimitive("Audio frames and model waiting are deterministic transport stimuli, not generated speech or model inference."))
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
                    put("measurement_engine_version", "realtime-simulation-engine-v1")
                    put("metric_catalog_id", profile?.measurementCatalogId?.takeIf(String::isNotBlank) ?: "realtime-interaction-measurements-v1")
                    put("target_set_id", profile?.evaluation?.targetSetId?.takeIf(String::isNotBlank))
                    put("score_policy_id", result.scorePolicyId)
                    put("score_anchor_policy_id", result.scoreAnchorPolicyId)
                    put("conclusion_policy_id", result.conclusionPolicyId)
                    put("calculation_origin", "measurement_engine")
                    put("finalized_at_epoch_ms", serializedAt)
                })
                put("score", scoreJson(score, valid, invalidReason))
                put("group_scores", buildJsonObject {
                    score.groupScores.toSortedMap().forEach { (id, value) -> put(id, value) }
                })
                put("metrics", buildJsonObject {
                    (profileMeasurements.keys + score.metrics.keys).toSortedSet().forEach { id ->
                        put(id, metricJson(score.metrics[id], profileMeasurements[id], input.radio))
                    }
                })
                put("conclusions", buildJsonArray {
                    score.conclusions.forEachIndexed { index, text ->
                        add(buildJsonObject {
                            put("conclusion_id", "realtime-conclusion-${(index + 1).toString().padStart(3, '0')}")
                            put("severity", conclusionSeverity(score.verdict, index))
                            put("policy_id", result.conclusionPolicyId)
                            put("text", text)
                            put("basis", buildJsonArray { add(JsonPrimitive("evidence:realtime-raw")) })
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
                put("evidence_contract_version", "aneb-realtime-run-evidence-v1")
                put("variant", result.variant)
                put("behavior_model", behaviorModelReference(result, profile, profileResolved))
                put("raw_evidence", rawEvidenceJson(result.evidence))
            })
        }
        val bodyJson = Json.encodeToString(JsonObject.serializer(), body)
        return ResultEnvelopeEntity(
            runId = result.runId,
            schemaVersion = TokenResultEnvelopeV1.SCHEMA_VERSION,
            testType = TEST_TYPE,
            startedAtEpochMs = result.startedAtEpochMs,
            serializedAtEpochMs = serializedAt,
            canonicalSha256 = TokenRuntimeIntegrity.canonicalSha256(bodyJson),
            bodyJson = bodyJson,
        )
    }

    fun rawEvidenceJson(evidence: RealtimeRunEvidence): JsonObject = buildJsonObject {
        put("invalid_reason", evidence.invalidReason)
        put("sessions", buildJsonArray {
            evidence.sessions.forEach { session ->
                add(buildJsonObject {
                    put("established", session.established)
                    put("setup_ms", session.setupMs.finiteOrNull())
                    put("handshake_ms", session.handshakeMs.finiteOrNull())
                    put("unexpected_disconnect", session.unexpectedDisconnect)
                    put("error", session.error)
                    put("rtt_samples_ms", JsonArray(session.rttSamplesMs.mapNotNull { it.finitePrimitiveOrNull() }))
                    put("loaded_rtt_samples_ms", JsonArray(session.loadedRttSamplesMs.map { it?.finitePrimitiveOrNull() ?: JsonNull }))
                    put("recovery_ms", session.recoveryMs.finiteOrNull())
                    put("reconnect_events", session.reconnectEvents)
                    put("controlled_disconnect_expected", session.controlledDisconnectExpected)
                    put("controlled_disconnect_observed", session.controlledDisconnectObserved)
                    put("recovery_stimulus_baseline_ms", session.recoveryStimulusBaselineMs.finiteOrNull())
                    put("turns", buildJsonArray {
                        session.turns.forEach { turn ->
                            add(buildJsonObject {
                                put("response_excess_ms", turn.responseExcessMs.finiteOrNull())
                                put("response_ms", turn.responseMs.finiteOrNull())
                                put("expected_frames", turn.expectedFrames)
                                put("unique_frames", turn.uniqueFrames)
                                put("on_time_frames", turn.onTimeFrames)
                                put("stall_frames", turn.stallFrames)
                                put("conceal_frames", turn.concealFrames)
                                put("arrival_variation_ms", JsonArray(turn.arrivalVariationMs.mapNotNull { it.finitePrimitiveOrNull() }))
                                put("barge_response_ms", turn.bargeResponseMs.finiteOrNull())
                                put("max_missing_run_frames", turn.maxMissingRunFrames)
                                put("uplink_goodput_kbps", turn.uplinkGoodputKbps.finiteOrNull())
                                put("downlink_goodput_kbps", turn.downlinkGoodputKbps.finiteOrNull())
                                put("unplanned_overlap", turn.unplannedOverlap)
                                put("interrupted", turn.interrupted)
                                put("success", turn.success)
                            })
                        }
                    })
                })
            }
        })
    }

    private fun profileReference(
        source: RealtimeResultEnvelopeSource,
        result: RealtimeSimulationResult,
        resolved: Boolean,
    ): JsonObject = buildJsonObject {
        put("resolution_status", if (resolved) "resolved" else "unavailable")
        put("contract_version", source.profile?.contractVersion.takeIf { resolved })
        put("profile_id", source.profile?.profileId ?: result.profileId)
        put("profile_version", source.profile?.version.takeIf { resolved })
        put("variant", result.variant)
        put("profile_fingerprint", digestJson(source.profileHash, resolved))
        put("profile_evidence_ref_id", if (resolved) "profile-artifact" else null)
        put("runtime_artifact_status", if (resolved) "resolved" else "unavailable")
        put("runtime_artifact_hash", digestJson(source.runtimeArtifactHash, resolved))
        put("runtime_artifact_evidence_ref_id", if (resolved) "runtime-artifact" else null)
        put("source_uri", source.profileUri.takeIf { resolved })
    }

    private fun scoreJson(score: RealtimeScoreResult, valid: Boolean, invalidReason: String?): JsonObject {
        val state = when {
            !valid -> "suppressed_invalid"
            score.totalScore == null -> "not_computable"
            else -> "computed"
        }
        return buildJsonObject {
            put("state", state)
            put("value", score.totalScore)
            put("grade", score.grade)
            put("verdict", if (valid) score.verdict.name.lowercase() else "invalid")
            put("confidence", if (valid) score.confidence.name.lowercase() else "invalid")
            put("confidence_basis", buildJsonObject {
                put("method_id", score.confidenceMethodId)
                put("coverage_ratio", score.coverageRatio)
                put("minimum_sample_satisfied", score.minimumSampleSatisfied)
            })
            put("cap_reason", score.capReason)
            put(
                "not_computable_reason",
                if (state == "computed") null
                else score.notComputableReason
                    ?: invalidReason?.let { "invalid_run:$it" }
                    ?: "measurement_engine_did_not_compute_total_score",
            )
        }
    }

    private fun metricJson(
        metric: RealtimeMetricEvidence?,
        definition: ProfileMeasurement?,
        radio: FormalRadioEvidence,
    ): JsonObject {
        checkNotNull(definition) { "realtime_result_metric_definition_missing:${metric?.metricId ?: "unknown"}" }
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
            put("components", buildJsonObject {
                metric?.componentValues.orEmpty().toSortedMap().forEach { (name, value) -> put(name, value) }
            })
            put("source_evidence_ref_ids", buildJsonArray {
                add(JsonPrimitive(if (radioSeriesObserved) "radio-context" else "realtime-raw"))
            })
            put(
                "invalid_reason",
                if (observed) null
                else if (metric == null) "measurement_not_emitted_by_current_engine" else "measurement_unavailable",
            )
        }
    }

    private fun behaviorModelReference(
        result: RealtimeSimulationResult,
        profile: ScenarioProfile?,
        profileResolved: Boolean,
    ): JsonObject {
        val resolved = profileResolved && result.behaviorModelHash.isSha256Digest()
        return buildJsonObject {
            put("resolution_status", if (resolved) "resolved" else "unavailable")
            put("model_id", result.behaviorModelId.takeIf { resolved })
            put("model_version", result.behaviorModelVersion.takeIf { resolved })
            put("model_hash", digestJson(result.behaviorModelHash, resolved))
            put("calibration_status", if (resolved) result.calibrationStatus else "unknown")
            put("source_kind", profile?.business?.modelSourceKind.takeIf { resolved })
        }
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
        put("values", buildJsonObject {
            target.values.toSortedMap().forEach { (key, value) -> put(key, value) }
        })
        put("policy_id", target.policyId)
        put("required_compliance_ratio", target.requiredComplianceRatio.finiteOrNull())
        put("provenance", target.provenance)
    }

    private fun artifactEvidenceRef(refId: String, uri: String, digest: String, description: String): JsonObject =
        buildJsonObject {
            put("ref_id", refId)
            put("kind", "content_addressed_artifact")
            put("uri", uri)
            put("media_type", "application/json")
            put("digest", digestJson(digest, true))
            put("record_count", 1)
            put("redaction", "none")
            put("description", description)
        }

    private fun digestJson(value: String?, enabled: Boolean): kotlinx.serialization.json.JsonElement =
        if (enabled && value != null) buildJsonObject {
            put("algorithm", "sha256")
            put("canonicalization", "canonical-json-v1")
            put("value", value)
        } else JsonNull

    private fun String?.isSha256Digest(): Boolean = this?.matches(Regex("^sha256:[0-9a-f]{64}$")) == true

    private fun Double?.finiteOrNull(): kotlinx.serialization.json.JsonElement =
        this?.takeIf(Double::isFinite)?.let(::JsonPrimitive) ?: JsonNull

    private fun Double.finitePrimitiveOrNull(): JsonPrimitive? = takeIf(Double::isFinite)?.let(::JsonPrimitive)

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
