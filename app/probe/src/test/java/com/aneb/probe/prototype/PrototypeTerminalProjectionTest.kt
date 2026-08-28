package com.aneb.probe.prototype

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class PrototypeTerminalProjectionTest {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    @Test
    fun sharedFixtureProjectsExact15PlusExact9ToCanonicalExact24() {
        val fixture = readSharedFixture()
        val serverDetails = fixture.getValue("server_done_details").jsonObject
        val androidAdditions = fixture.getValue("android_additions").jsonObject
        val expectedCanonical = fixture.getValue("canonical_terminal_event_details").jsonObject

        assertEquals(15, serverDetails.size)
        assertEquals(9, androidAdditions.size)
        assertEquals(24, expectedCanonical.size)

        val actual = PrototypeTerminalProjection.project(
            serverDetails = serverDetails,
            androidAdditions = androidAdditions,
        )

        assertEquals(expectedCanonical, actual)
        assertTrue(actual.keys.containsAll(serverDetails.keys))
        assertTrue(actual.keys.containsAll(androidAdditions.keys))
    }

    @Test
    fun legacyPrivateExact19IsRejected() {
        val fixture = readSharedFixture()
        val serverDetails = buildJsonObject {
            fixture.getValue("server_done_details").jsonObject.forEach { (key, value) ->
                put(key, value)
            }
            put("receipt_version", JsonPrimitive("prototype-wire-terminal-receipt-0.1"))
            put("canonical_receipt_version", JsonPrimitive("prototype-terminal-receipt-0.1"))
            put("workload_id", JsonPrimitive("streaming_text_reference_v0.1"))
            put("workload_version", JsonPrimitive("0.1"))
        }

        try {
            val actual = PrototypeTerminalProjection.project(
                serverDetails = serverDetails,
                androidAdditions = fixture.getValue("android_additions").jsonObject,
            )
            org.junit.Assert.fail(
                "legacy exact19 was accepted; projection produced ${actual.size} keys",
            )
        } catch (_: IllegalArgumentException) {
            // The production gate rejects the private legacy wire shape before canonicalization.
        }
    }

    @Test
    fun sameCountRenamedServerKeyIsRejected() {
        val fixture = readSharedFixture()
        val original = fixture.getValue("server_done_details").jsonObject
        val mutated = buildJsonObject {
            original.forEach { (key, value) ->
                if (key != "protocol_version") put(key, value)
            }
            put("protocolVersion", original.getValue("protocol_version"))
        }

        assertEquals(15, mutated.size)
        assertTrue("protocol_version" !in mutated)
        assertTrue("protocolVersion" in mutated)

        try {
            val actual = PrototypeTerminalProjection.project(
                serverDetails = mutated,
                androidAdditions = fixture.getValue("android_additions").jsonObject,
            )
            org.junit.Assert.fail(
                "same-count renamed server key was accepted; projection produced ${actual.size} keys",
            )
        } catch (_: IllegalArgumentException) {
            // The production gate rejects aliases even when the server shape count remains 15.
        }
    }

    @Test
    fun sameCountRenamedAndroidKeyIsRejected() {
        val fixture = readSharedFixture()
        val serverDetails = fixture.getValue("server_done_details").jsonObject
        val original = fixture.getValue("android_additions").jsonObject
        val mutated = buildJsonObject {
            original.forEach { (key, value) ->
                if (key != "clock_source") put(key, value)
            }
            put("clockSource", original.getValue("clock_source"))
        }

        assertEquals(9, mutated.size)
        assertTrue(serverDetails.keys.intersect(mutated.keys).isEmpty())
        assertEquals(24, serverDetails.keys.union(mutated.keys).size)

        try {
            val actual = PrototypeTerminalProjection.project(
                serverDetails = serverDetails,
                androidAdditions = mutated,
            )
            org.junit.Assert.fail(
                "same-count renamed Android key was accepted; projection produced ${actual.size} keys",
            )
        } catch (_: IllegalArgumentException) {
            // The production gate rejects Android aliases without adding a second schema source.
        }
    }

    @Test
    fun changedAndroidReceiptVersionIsRejected() {
        val fixture = readSharedFixture()
        val serverDetails = fixture.getValue("server_done_details").jsonObject
        val original = fixture.getValue("android_additions").jsonObject
        val mutated = buildJsonObject {
            original.forEach { (key, value) ->
                if (key == "receipt_version") {
                    put(key, JsonPrimitive("prototype-terminal-receipt-0.2"))
                } else {
                    put(key, value)
                }
            }
        }

        assertEquals(original.keys, mutated.keys)
        assertEquals(serverDetails.keys.intersect(original.keys), serverDetails.keys.intersect(mutated.keys))
        original.forEach { (key, value) ->
            if (key != "receipt_version") assertEquals(value, mutated.getValue(key))
        }
        assertEquals(JsonPrimitive("prototype-terminal-receipt-0.2"), mutated.getValue("receipt_version"))

        try {
            val actual = PrototypeTerminalProjection.project(
                serverDetails = serverDetails,
                androidAdditions = mutated,
            )
            org.junit.Assert.fail(
                "changed receipt_version was accepted; projection produced ${actual.size} keys",
            )
        } catch (_: IllegalArgumentException) {
            // The production gate enforces the canonical receipt_version value.
        }
    }

    @Test
    fun changedAndroidEventsExpectedIsRejected() {
        val fixture = readSharedFixture()
        val serverDetails = fixture.getValue("server_done_details").jsonObject
        val original = fixture.getValue("android_additions").jsonObject
        val mutated = buildJsonObject {
            original.forEach { (key, value) ->
                if (key == "events_expected") {
                    put(key, JsonPrimitive(119))
                } else {
                    put(key, value)
                }
            }
        }

        assertEquals(original.keys, mutated.keys)
        assertEquals(serverDetails.keys.intersect(original.keys), serverDetails.keys.intersect(mutated.keys))
        assertEquals(original.getValue("receipt_version"), mutated.getValue("receipt_version"))
        original.forEach { (key, value) ->
            if (key != "events_expected") assertEquals(value, mutated.getValue(key))
        }
        assertEquals(JsonPrimitive(119), mutated.getValue("events_expected"))

        try {
            val actual = PrototypeTerminalProjection.project(
                serverDetails = serverDetails,
                androidAdditions = mutated,
            )
            org.junit.Assert.fail(
                "changed events_expected was accepted; projection produced ${actual.size} keys",
            )
        } catch (_: IllegalArgumentException) {
            // The production gate enforces the canonical events_expected value.
        }
    }

    @Test
    fun changedAndroidEventsReceivedIsRejected() {
        val fixture = readSharedFixture()
        val serverDetails = fixture.getValue("server_done_details").jsonObject
        val original = fixture.getValue("android_additions").jsonObject
        val mutated = buildJsonObject {
            original.forEach { (key, value) ->
                if (key == "events_received") {
                    put(key, JsonPrimitive(119))
                } else {
                    put(key, value)
                }
            }
        }

        assertEquals(original.keys, mutated.keys)
        assertEquals(serverDetails.keys.intersect(original.keys), serverDetails.keys.intersect(mutated.keys))
        assertEquals(original.getValue("receipt_version"), mutated.getValue("receipt_version"))
        assertEquals(original.getValue("events_expected"), mutated.getValue("events_expected"))
        original.forEach { (key, value) ->
            if (key != "events_received") assertEquals(value, mutated.getValue(key))
        }
        assertEquals(JsonPrimitive(119), mutated.getValue("events_received"))

        try {
            val actual = PrototypeTerminalProjection.project(
                serverDetails = serverDetails,
                androidAdditions = mutated,
            )
            org.junit.Assert.fail(
                "changed events_received was accepted; projection produced ${actual.size} keys",
            )
        } catch (_: IllegalArgumentException) {
            // The production gate enforces the canonical events_received value.
        }
    }

    @Test
    fun nonCanonicalClockSourceIsRejected() {
        val fixture = readSharedFixture()
        val serverDetails = fixture.getValue("server_done_details").jsonObject
        val original = fixture.getValue("android_additions").jsonObject
        val mutated = buildJsonObject {
            original.forEach { (key, value) ->
                if (key == "clock_source") {
                    put(key, JsonPrimitive("android.elapsedRealtimeNanos"))
                } else {
                    put(key, value)
                }
            }
        }

        assertEquals(original.keys, mutated.keys)
        assertEquals(serverDetails.keys.intersect(original.keys), serverDetails.keys.intersect(mutated.keys))
        assertEquals(original.getValue("receipt_version"), mutated.getValue("receipt_version"))
        assertEquals(original.getValue("events_expected"), mutated.getValue("events_expected"))
        assertEquals(original.getValue("events_received"), mutated.getValue("events_received"))
        original.forEach { (key, value) ->
            if (key != "clock_source") assertEquals(value, mutated.getValue(key))
        }
        assertEquals(JsonPrimitive("android.elapsedRealtimeNanos"), mutated.getValue("clock_source"))

        try {
            val actual = PrototypeTerminalProjection.project(
                serverDetails = serverDetails,
                androidAdditions = mutated,
            )
            org.junit.Assert.fail(
                "noncanonical clock_source accepted; projection produced ${actual.size} keys",
            )
        } catch (_: IllegalArgumentException) {
            // The production gate enforces the canonical clock_source value.
        }
    }

    @Test
    fun nonCanonicalClockUnitIsRejected() {
        val fixture = readSharedFixture()
        val serverDetails = fixture.getValue("server_done_details").jsonObject
        val original = fixture.getValue("android_additions").jsonObject
        val mutated = buildJsonObject {
            original.forEach { (key, value) ->
                if (key == "clock_unit") {
                    put(key, JsonPrimitive("ms"))
                } else {
                    put(key, value)
                }
            }
        }

        assertEquals(original.keys, mutated.keys)
        assertEquals(serverDetails.keys.intersect(original.keys), serverDetails.keys.intersect(mutated.keys))
        assertEquals(original.getValue("receipt_version"), mutated.getValue("receipt_version"))
        assertEquals(original.getValue("events_expected"), mutated.getValue("events_expected"))
        assertEquals(original.getValue("events_received"), mutated.getValue("events_received"))
        assertEquals(original.getValue("clock_source"), mutated.getValue("clock_source"))
        original.forEach { (key, value) ->
            if (key != "clock_unit") assertEquals(value, mutated.getValue(key))
        }
        assertEquals(JsonPrimitive("ms"), mutated.getValue("clock_unit"))

        try {
            val actual = PrototypeTerminalProjection.project(
                serverDetails = serverDetails,
                androidAdditions = mutated,
            )
            org.junit.Assert.fail(
                "noncanonical clock_unit accepted; projection produced ${actual.size} keys",
            )
        } catch (_: IllegalArgumentException) {
            // The production gate enforces the canonical clock_unit value.
        }
    }

    @Test
    fun nonCanonicalClockEpochIsRejected() {
        val fixture = readSharedFixture()
        val serverDetails = fixture.getValue("server_done_details").jsonObject
        val original = fixture.getValue("android_additions").jsonObject
        val mutated = buildJsonObject {
            original.forEach { (key, value) ->
                if (key == "clock_epoch") {
                    put(key, JsonPrimitive("boot"))
                } else {
                    put(key, value)
                }
            }
        }

        assertEquals(original.keys, mutated.keys)
        assertEquals(serverDetails.keys.intersect(original.keys), serverDetails.keys.intersect(mutated.keys))
        assertEquals(original.getValue("receipt_version"), mutated.getValue("receipt_version"))
        assertEquals(original.getValue("events_expected"), mutated.getValue("events_expected"))
        assertEquals(original.getValue("events_received"), mutated.getValue("events_received"))
        assertEquals(original.getValue("clock_source"), mutated.getValue("clock_source"))
        assertEquals(original.getValue("clock_unit"), mutated.getValue("clock_unit"))
        assertEquals(original.getValue("clock_domain_id"), mutated.getValue("clock_domain_id"))
        assertEquals(original.getValue("t0_monotonic_ns"), mutated.getValue("t0_monotonic_ns"))
        assertEquals(original.getValue("client_monotonic_ns"), mutated.getValue("client_monotonic_ns"))
        original.forEach { (key, value) ->
            if (key != "clock_epoch") assertEquals(value, mutated.getValue(key))
        }
        assertEquals(JsonPrimitive("boot"), mutated.getValue("clock_epoch"))

        try {
            val actual = PrototypeTerminalProjection.project(
                serverDetails = serverDetails,
                androidAdditions = mutated,
            )
            org.junit.Assert.fail(
                "noncanonical clock_epoch accepted; projection produced ${actual.size} keys",
            )
        } catch (_: IllegalArgumentException) {
            // The production gate enforces the canonical clock_epoch value.
        }
    }

    @Test
    fun invalidT0MonotonicValuesAreRejected() {
        val fixture = readSharedFixture()
        val serverDetails = fixture.getValue("server_done_details").jsonObject
        val original = fixture.getValue("android_additions").jsonObject
        val invalidCases = listOf(
            "negative" to JsonPrimitive(-1),
            "string" to JsonPrimitive("1000000000"),
        )
        val accepted = mutableListOf<String>()

        invalidCases.forEach { (label, invalidValue) ->
            val mutated = buildJsonObject {
                original.forEach { (key, value) ->
                    if (key == "t0_monotonic_ns") {
                        put(key, invalidValue)
                    } else {
                        put(key, value)
                    }
                }
            }

            assertEquals(original.keys, mutated.keys)
            original.forEach { (key, value) ->
                if (key != "t0_monotonic_ns") assertEquals(value, mutated.getValue(key))
            }
            assertEquals(invalidValue, mutated.getValue("t0_monotonic_ns"))

            try {
                val actual = PrototypeTerminalProjection.project(
                    serverDetails = serverDetails,
                    androidAdditions = mutated,
                )
                accepted += "$label case was accepted; projection produced ${actual.size} keys"
            } catch (_: IllegalArgumentException) {
                // The production gate rejects each invalid t0_monotonic_ns domain case.
            }
        }

        if (accepted.isNotEmpty()) {
            org.junit.Assert.fail(
                accepted.joinToString(separator = "; ") { "invalid t0_monotonic_ns $it" },
            )
        }
    }

    @Test
    fun zeroT0MonotonicValueIsAccepted() {
        val fixture = readSharedFixture()
        val serverDetails = fixture.getValue("server_done_details").jsonObject
        val original = fixture.getValue("android_additions").jsonObject
        val mutated = buildJsonObject {
            original.forEach { (key, value) ->
                if (key == "t0_monotonic_ns") {
                    put(key, JsonPrimitive(0))
                } else {
                    put(key, value)
                }
            }
        }

        assertEquals(original.keys, mutated.keys)
        original.forEach { (key, value) ->
            if (key != "t0_monotonic_ns") assertEquals(value, mutated.getValue(key))
        }
        assertEquals(JsonPrimitive(0), mutated.getValue("t0_monotonic_ns"))

        val actual = PrototypeTerminalProjection.project(
            serverDetails = serverDetails,
            androidAdditions = mutated,
        )
        assertEquals(JsonPrimitive(0), actual.getValue("t0_monotonic_ns"))
    }

    @Test
    fun invalidClientMonotonicValuesAreRejected() {
        val fixture = readSharedFixture()
        val serverDetails = fixture.getValue("server_done_details").jsonObject
        val original = fixture.getValue("android_additions").jsonObject
        val invalidCases = listOf(
            "negative" to JsonPrimitive(-1),
            "string" to JsonPrimitive("1000000001"),
        )
        val accepted = mutableListOf<String>()

        invalidCases.forEach { (label, invalidValue) ->
            val mutated = buildJsonObject {
                original.forEach { (key, value) ->
                    if (key == "client_monotonic_ns") {
                        put(key, invalidValue)
                    } else {
                        put(key, value)
                    }
                }
            }

            assertEquals(original.keys, mutated.keys)
            original.forEach { (key, value) ->
                if (key != "client_monotonic_ns") assertEquals(value, mutated.getValue(key))
            }
            assertEquals(invalidValue, mutated.getValue("client_monotonic_ns"))

            try {
                val actual = PrototypeTerminalProjection.project(
                    serverDetails = serverDetails,
                    androidAdditions = mutated,
                )
                accepted += "$label case was accepted; projection produced ${actual.size} keys"
            } catch (_: IllegalArgumentException) {
                // The production gate rejects each invalid client_monotonic_ns domain case.
            }
        }

        if (accepted.isNotEmpty()) {
            org.junit.Assert.fail(
                accepted.joinToString(separator = "; ") { "invalid client_monotonic_ns $it" },
            )
        }
    }

    private fun readSharedFixture(): JsonObject {
        val candidates = listOf(
            Path.of("server/testdata/prototype_option_a_terminal_projection.json"),
            Path.of("../../server/testdata/prototype_option_a_terminal_projection.json"),
        )
        val path = candidates.firstOrNull { Files.isRegularFile(it) }
            ?: error("shared fixture not found: ${candidates.joinToString()}")
        return json.parseToJsonElement(Files.readAllBytes(path).toString(Charsets.UTF_8)).jsonObject
    }
}
