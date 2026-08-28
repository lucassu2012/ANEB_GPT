package com.aneb.probe.prototype

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull

/**
 * First Prototype 0.1 consumer seam for the Option A projection.
 *
 * The public API accepts already-decoded JSON sections compatible with the #15
 * SSE done envelope. This layer locks the exact server and Android keysets,
 * canonical receipt/count and clock source/unit/epoch values, and the local
 * non-string, non-negative long domains for t0/client monotonic scalars.
 * Server values, clock-domain identity, and outer identity/chronology belong to
 * the integration layer; this projector performs no fixture loading, inference,
 * or renaming.
 */
object PrototypeTerminalProjection {
    private val serverExact15Keys = setOf(
        "protocol_version",
        "campaign_id",
        "run_id",
        "campaign_mode",
        "run_index",
        "condition_id",
        "condition_version",
        "profile_id",
        "profile_version",
        "profile_manifest_sha256",
        "schedule_hash",
        "nominal_interval_ms",
        "planned_event_count",
        "emitted_event_count",
        "terminal_status",
    )

    private val androidExact9Keys = setOf(
        "receipt_version",
        "events_expected",
        "events_received",
        "clock_domain_id",
        "clock_source",
        "clock_unit",
        "clock_epoch",
        "t0_monotonic_ns",
        "client_monotonic_ns",
    )

    private val canonicalReceiptVersion = JsonPrimitive("prototype-terminal-receipt-0.1")

    private val canonicalClockSource = JsonPrimitive("android.os.SystemClock.elapsedRealtimeNanos")

    private val canonicalClockUnit = JsonPrimitive("ns")

    private val canonicalClockEpoch = JsonPrimitive("device_boot")

    fun project(
        serverDetails: JsonObject,
        androidAdditions: JsonObject,
    ): JsonObject {
        require(serverDetails.keys == serverExact15Keys) {
            "server done details keys do not match the exact15 wire shape"
        }
        require(androidAdditions.keys == androidExact9Keys) {
            "android additions keys do not match the exact9 enrichment shape"
        }
        require(androidAdditions["receipt_version"] == canonicalReceiptVersion) {
            "android receipt_version does not match the canonical value"
        }
        require(androidAdditions["events_expected"] == JsonPrimitive(120)) {
            "android events_expected does not match the canonical value"
        }
        require(androidAdditions["events_received"] == JsonPrimitive(120)) {
            "android events_received does not match the canonical value"
        }
        require(androidAdditions["clock_source"] == canonicalClockSource) {
            "android clock_source does not match the canonical value"
        }
        require(androidAdditions["clock_unit"] == canonicalClockUnit) {
            "android clock_unit does not match the canonical value"
        }
        require(androidAdditions["clock_epoch"] == canonicalClockEpoch) {
            "android clock_epoch does not match the canonical value"
        }
        val t0MonotonicPrimitive = androidAdditions["t0_monotonic_ns"] as? JsonPrimitive
        require(t0MonotonicPrimitive != null) {
            "android t0_monotonic_ns must be a JsonPrimitive"
        }
        require(!t0MonotonicPrimitive.isString) {
            "android t0_monotonic_ns must be a non-string JsonPrimitive"
        }
        val t0MonotonicNs = t0MonotonicPrimitive.longOrNull
        require(t0MonotonicNs != null) {
            "android t0_monotonic_ns must be a JSON integer"
        }
        require(t0MonotonicNs >= 0L) {
            "android t0_monotonic_ns must be non-negative"
        }
        val clientMonotonicPrimitive = androidAdditions["client_monotonic_ns"] as? JsonPrimitive
        require(clientMonotonicPrimitive != null) {
            "android client_monotonic_ns must be a JsonPrimitive"
        }
        require(!clientMonotonicPrimitive.isString) {
            "android client_monotonic_ns must be a non-string JsonPrimitive"
        }
        val clientMonotonicNs = clientMonotonicPrimitive.longOrNull
        require(clientMonotonicNs != null) {
            "android client_monotonic_ns must be a JSON integer"
        }
        require(clientMonotonicNs >= 0L) {
            "android client_monotonic_ns must be non-negative"
        }
        return buildJsonObject {
            serverDetails.forEach { (key, value) -> put(key, value) }
            androidAdditions.forEach { (key, value) -> put(key, value) }
        }
    }
}
