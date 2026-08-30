package com.aneb.probe.prototype

import com.aneb.probe.net.MonotonicNanosClock
import com.aneb.probe.net.RawSseEvent
import com.aneb.probe.net.RawSseStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

private const val TERMINAL_COMPLETION_ERROR =
    "prototype SSE terminal receipt must report complete 120-event delivery"
private const val TERMINAL_IDENTITY_ERROR =
    "prototype SSE terminal receipt identity must match the run"
private const val REQUEST_RUN_IDENTITY_ERROR =
    "prototype SSE run identity must match the outgoing request"
private const val CONDITION_IDENTITY_ERROR =
    "prototype SSE condition identity must match the outgoing request"

class PrototypeRunStreamAdapterTest {
    @Test
    fun runStartedPayloadEventTypeMustMatchSseEvent() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        blocks[0] = blocks[0].replace(
            "\"event_type\":\"run_started\"",
            "\"event_type\":\"content_event\"",
        )
        val transport = FakeRawPostTransport(rawStreamOf(blocks))

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("run_started payload event_type mismatch was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE run_started payload event_type must match the SSE event",
                error.message,
            )
        }
    }

    @Test
    fun outgoingRequestConditionIdentityMustMatchEveryStreamEvent() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val baseline = officialProducerCases.first()
        val requestBody = officialRunRequestBody(baseline)
        val blocks = producerShapedBlocks(doneFrame, baseline).map { block ->
            block.replace(
                "\"condition_id\":\"${baseline.conditionId}\"",
                "\"condition_id\":\"slow_v0.1\"",
            )
        }
        assertEquals(122, blocks.size)
        assertTrue(blocks[0].contains("\"condition_id\":\"slow_v0.1\""))
        assertTrue(blocks.drop(1).dropLast(1).all { block ->
            block.contains("\"condition_id\":\"slow_v0.1\"")
        })
        assertTrue(blocks.last().contains("\"condition_id\":\"slow_v0.1\""))

        try {
            PrototypeRunStreamAdapter(
                FakeRawPostTransport(rawStreamOf(blocks)),
            ).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = requestBody,
            )
            org.junit.Assert.fail("condition identity mismatch was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(CONDITION_IDENTITY_ERROR, error.message)
        }
    }

    @Test
    fun conditionIdentityRejectsPlainDuplicatesAtEveryClaimedLayer() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val baseline = officialProducerCases.first()
        val canonicalRequest = officialRunRequestBody(baseline)
        val canonicalBlocks = producerShapedBlocks(doneFrame, baseline)
        val forgedCondition = "slow_v0.1"
        val canonicalConditionMember = "\"condition_id\":\"${baseline.conditionId}\""
        data class DuplicateCase(
            val label: String,
            val requestBody: String,
            val blocks: List<String>,
        )

        data class DuplicateVariant(
            val label: String,
            val firstKey: String,
            val firstValue: String,
            val secondKey: String,
            val secondValue: String,
        )

        val variants = listOf(
            DuplicateVariant(
                "plain forged then plain canonical",
                "condition_id",
                forgedCondition,
                "condition_id",
                baseline.conditionId,
            ),
            DuplicateVariant(
                "plain canonical then plain forged",
                "condition_id",
                baseline.conditionId,
                "condition_id",
                forgedCondition,
            ),
            DuplicateVariant(
                "escaped forged then plain canonical",
                "condition_\\u0069d",
                forgedCondition,
                "condition_id",
                baseline.conditionId,
            ),
            DuplicateVariant(
                "plain canonical then escaped forged",
                "condition_id",
                baseline.conditionId,
                "condition_\\u0069d",
                forgedCondition,
            ),
        )
        val cases = mutableListOf<DuplicateCase>()
        variants.forEach { variant ->
            val firstMember = "\"" + variant.firstKey + "\":\"" + variant.firstValue + "\""
            val secondMember = "\"" + variant.secondKey + "\":\"" + variant.secondValue + "\""
            val duplicateConditionMember = "$firstMember,$secondMember"
            cases += DuplicateCase(
                "outgoing request root / " + variant.label,
                canonicalRequest.replaceFirst(canonicalConditionMember, duplicateConditionMember),
                canonicalBlocks,
            )
            cases += DuplicateCase(
                "run_started root / " + variant.label,
                canonicalRequest,
                canonicalBlocks.toMutableList().also { blocks ->
                    blocks[0] = blocks[0].replaceFirst(
                        canonicalConditionMember,
                        duplicateConditionMember,
                    )
                },
            )
            cases += DuplicateCase(
                "middle content root / " + variant.label,
                canonicalRequest,
                canonicalBlocks.toMutableList().also { blocks ->
                    val middleContentIndex = 1 + 59
                    blocks[middleContentIndex] = blocks[middleContentIndex].replaceFirst(
                        canonicalConditionMember,
                        duplicateConditionMember,
                    )
                },
            )
            cases += DuplicateCase(
                "terminal root / " + variant.label,
                canonicalRequest,
                canonicalBlocks.toMutableList().also { blocks ->
                    blocks[blocks.lastIndex] = blocks[blocks.lastIndex].replaceFirst(
                        canonicalConditionMember,
                        duplicateConditionMember,
                    )
                },
            )
            cases += DuplicateCase(
                "terminal details / " + variant.label,
                canonicalRequest,
                canonicalBlocks.toMutableList().also { blocks ->
                    blocks[blocks.lastIndex] = replaceSecondOccurrence(
                        blocks[blocks.lastIndex],
                        canonicalConditionMember,
                        duplicateConditionMember,
                    )
                },
            )
        }
        assertEquals(20, cases.size)
        assertTrue(cases.all { it.blocks.size == 122 })

        val accepted = mutableListOf<String>()
        val wrongErrors = mutableListOf<String>()
        cases.forEach { case ->
            try {
                PrototypeRunStreamAdapter(
                    FakeRawPostTransport(rawStreamOf(case.blocks)),
                ).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = case.requestBody,
                )
                accepted += case.label
            } catch (error: IllegalArgumentException) {
                if (error.message != CONDITION_IDENTITY_ERROR) {
                    wrongErrors += "${case.label}: ${error.message}"
                }
            }
        }

        assertTrue(
            "condition duplicate cases accepted=$accepted wrongErrors=$wrongErrors",
            accepted.isEmpty() && wrongErrors.isEmpty(),
        )
    }

    @Test
    fun conditionIdentityRejectsOfficialContextSurfaceDrift() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")

        data class SurfaceCase(
            val label: String,
            val requestBody: String,
            val blocks: List<String>,
        )

        val surfaces = listOf(
            "request root",
            "run_started root",
            "content seq1",
            "content seq60",
            "content seq120",
            "terminal root",
            "terminal details",
        )
        val cases = mutableListOf<SurfaceCase>()
        officialProducerCases.forEachIndexed { producerIndex, producer ->
            val alternate = officialProducerCases[(producerIndex + 1) % officialProducerCases.size]
            val canonicalRequest = officialRunRequestBody(producer)
            val canonicalBlocks = producerShapedBlocks(doneFrame, producer)
            val canonicalMember = "\"condition_id\":\"" + producer.conditionId + "\""
            val alternateMember = "\"condition_id\":\"" + alternate.conditionId + "\""
            surfaces.forEach { surface ->
                if (surface == "request root") {
                    cases += SurfaceCase(
                        producer.label + " / " + surface,
                        canonicalRequest.replaceFirst(canonicalMember, alternateMember),
                        canonicalBlocks,
                    )
                } else {
                    val blocks = canonicalBlocks.toMutableList()
                    val blockIndex = when (surface) {
                        "run_started root" -> 0
                        "content seq1" -> 1
                        "content seq60" -> 60
                        "content seq120" -> 120
                        "terminal root", "terminal details" -> blocks.lastIndex
                        else -> error("unsupported condition surface: $surface")
                    }
                    blocks[blockIndex] = if (surface == "terminal details") {
                        replaceSecondOccurrence(
                            blocks[blockIndex],
                            canonicalMember,
                            alternateMember,
                        )
                    } else {
                        blocks[blockIndex].replaceFirst(canonicalMember, alternateMember)
                    }
                    cases += SurfaceCase(
                        producer.label + " / " + surface,
                        canonicalRequest,
                        blocks,
                    )
                }
            }
        }
        assertEquals(21, cases.size)
        assertTrue(cases.all { it.blocks.size == 122 })

        val accepted = mutableListOf<String>()
        val wrongErrors = mutableListOf<String>()
        cases.forEach { case ->
            try {
                PrototypeRunStreamAdapter(
                    FakeRawPostTransport(rawStreamOf(case.blocks)),
                ).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = case.requestBody,
                )
                accepted += case.label
            } catch (error: IllegalArgumentException) {
                if (error.message != CONDITION_IDENTITY_ERROR) {
                    wrongErrors += case.label + ": " + error.message
                }
            }
        }

        assertTrue(
            "official condition surface cases accepted=$accepted wrongErrors=$wrongErrors",
            accepted.isEmpty() && wrongErrors.isEmpty(),
        )
    }

    @Test
    fun acceptanceRunIndexesFourThroughNineUseAlternateEndpointAndConditionIdentity() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val producers = acceptanceProducerCases()
        val endpoint = "http://192.168.50.9:19009/api/v1/prototype/runs?mode=acceptance"
        val surfaces = listOf(
            "request root",
            "run_started root",
            "content seq60",
            "terminal root",
            "terminal details",
        )
        val accepted = mutableListOf<String>()
        val wrongErrors = mutableListOf<String>()
        var canonicalPasses = 0
        var driftCases = 0

        producers.forEachIndexed { producerIndex, producer ->
            val alternate = producers[(producerIndex + 1) % producers.size]
            val canonicalRequest = officialRunRequestBody(producer)
            val canonicalBlocks = producerShapedBlocks(doneFrame, producer)
            val canonicalTransport = FakeRawPostTransport(rawStreamOf(canonicalBlocks))
            val canonicalResult = PrototypeRunStreamAdapter(canonicalTransport).run(
                endpoint = endpoint,
                requestBody = canonicalRequest,
            )
            assertNotNull(canonicalResult.decodedTerminal)
            assertTrue(canonicalRequest.contains("\"campaign_mode\":\"acceptance\""))
            assertTrue(canonicalRequest.contains("\"run_index\":${producer.runIndex}"))
            val terminalDetails = canonicalResult.decodedTerminal.envelope
                .getValue("details") as JsonObject
            assertEquals(
                "acceptance",
                terminalDetails.getValue("campaign_mode").jsonPrimitive.content,
            )
            assertEquals(
                producer.runIndex.toString(),
                terminalDetails.getValue("run_index").jsonPrimitive.content,
            )
            assertEquals(endpoint, canonicalTransport.postedUrl)
            assertEquals(canonicalRequest, canonicalTransport.postedBody)
            canonicalPasses += 1

            surfaces.forEach { surface ->
                val (requestBody, blocks) = conditionSurfaceVariant(
                    surface = surface,
                    canonicalRequest = canonicalRequest,
                    canonicalBlocks = canonicalBlocks,
                    canonicalCondition = producer.conditionId,
                    replacementCondition = alternate.conditionId,
                )
                driftCases += 1
                val transport = FakeRawPostTransport(rawStreamOf(blocks))
                val label = "${producer.label} run_index=${producer.runIndex} / $surface"
                try {
                    PrototypeRunStreamAdapter(transport).run(endpoint, requestBody)
                    accepted += label
                } catch (error: IllegalArgumentException) {
                    if (error.message != CONDITION_IDENTITY_ERROR) {
                        wrongErrors += "$label: ${error.message}"
                    }
                }
                assertEquals(endpoint, transport.postedUrl)
                assertEquals(requestBody, transport.postedBody)
            }
        }

        assertEquals((4..9).toList(), producers.map(OfficialProducerCase::runIndex))
        assertEquals(6, canonicalPasses)
        assertEquals(30, driftCases)
        assertTrue(
            "acceptance condition cases accepted=$accepted wrongErrors=$wrongErrors",
            accepted.isEmpty() && wrongErrors.isEmpty(),
        )
    }

    @Test
    fun acceptanceNineAlternateEndpointRejectsConditionDriftAtEveryContentPosition() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val producer = acceptanceProducerCases().last()
        val alternateCondition = acceptanceProducerCases().first().conditionId
        val endpoint = "http://192.168.50.9:19009/api/v1/prototype/runs?mode=acceptance"
        val canonicalRequest = officialRunRequestBody(producer)
        val canonicalBlocks = producerShapedBlocks(doneFrame, producer)
        val canonicalMember = "\"condition_id\":\"${producer.conditionId}\""
        val accepted = mutableListOf<Int>()
        val wrongErrors = mutableListOf<String>()
        var cases = 0

        (1..120).forEach { sequence ->
            val blocks = canonicalBlocks.toMutableList()
            val original = blocks[sequence]
            blocks[sequence] = original.replaceFirst(
                canonicalMember,
                "\"condition_id\":\"$alternateCondition\"",
            )
            require(blocks[sequence] != original) {
                "content seq=$sequence condition mutation did not apply"
            }
            cases += 1
            val transport = FakeRawPostTransport(rawStreamOf(blocks))
            try {
                PrototypeRunStreamAdapter(transport).run(endpoint, canonicalRequest)
                accepted += sequence
            } catch (error: IllegalArgumentException) {
                if (error.message != CONDITION_IDENTITY_ERROR) {
                    wrongErrors += "content seq=$sequence: ${error.message}"
                }
            }
            assertEquals(endpoint, transport.postedUrl)
            assertEquals(canonicalRequest, transport.postedBody)
        }

        assertEquals(120, cases)
        assertTrue(
            "content condition positions accepted=$accepted wrongErrors=$wrongErrors",
            accepted.isEmpty() && wrongErrors.isEmpty(),
        )
    }

    @Test
    fun acceptanceNineAlternateEndpointRejectsConditionDuplicatesAtBoundaryContentPositions() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val producer = acceptanceProducerCases().last()
        val endpoint = "http://192.168.50.9:19009/api/v1/prototype/runs?mode=acceptance"
        val canonicalRequest = officialRunRequestBody(producer)
        val canonicalBlocks = producerShapedBlocks(doneFrame, producer)
        val canonicalConditionMember = "\"condition_id\":\"${producer.conditionId}\""
        val forgedCondition = acceptanceProducerCases().first().conditionId
        data class DuplicateVariant(
            val label: String,
            val firstKey: String,
            val firstValue: String,
            val secondKey: String,
            val secondValue: String,
        )
        val variants = listOf(
            DuplicateVariant(
                "plain forged then plain canonical",
                "condition_id",
                forgedCondition,
                "condition_id",
                producer.conditionId,
            ),
            DuplicateVariant(
                "plain canonical then plain forged",
                "condition_id",
                producer.conditionId,
                "condition_id",
                forgedCondition,
            ),
            DuplicateVariant(
                "escaped forged then plain canonical",
                "condition_\\u0069d",
                forgedCondition,
                "condition_id",
                producer.conditionId,
            ),
            DuplicateVariant(
                "plain canonical then escaped forged",
                "condition_id",
                producer.conditionId,
                "condition_\\u0069d",
                forgedCondition,
            ),
        )
        val accepted = mutableListOf<String>()
        val wrongErrors = mutableListOf<String>()
        var cases = 0

        listOf(1, 60, 120).forEach { sequence ->
            variants.forEach { variant ->
                val firstMember = "\"${variant.firstKey}\":\"${variant.firstValue}\""
                val secondMember = "\"${variant.secondKey}\":\"${variant.secondValue}\""
                val blocks = canonicalBlocks.toMutableList()
                val original = blocks[sequence]
                blocks[sequence] = original.replaceFirst(
                    canonicalConditionMember,
                    "$firstMember,$secondMember",
                )
                require(blocks[sequence] != original) {
                    "content seq=$sequence ${variant.label} condition mutation did not apply"
                }
                val label = "content seq=$sequence / ${variant.label}"
                cases += 1
                val transport = FakeRawPostTransport(rawStreamOf(blocks))
                try {
                    PrototypeRunStreamAdapter(transport).run(endpoint, canonicalRequest)
                    accepted += label
                } catch (error: IllegalArgumentException) {
                    if (error.message != CONDITION_IDENTITY_ERROR) {
                        wrongErrors += "$label: ${error.message}"
                    }
                }
                assertEquals(endpoint, transport.postedUrl)
                assertEquals(canonicalRequest, transport.postedBody)
            }
        }

        assertEquals(12, cases)
        assertTrue(
            "boundary content condition duplicate cases accepted=$accepted wrongErrors=$wrongErrors",
            accepted.isEmpty() && wrongErrors.isEmpty(),
        )
    }

    @Test
    fun conditionIdentityKeepsUnconditionalFullSurfaceFrame() {
        // [FRAME] Maintenance-only: this freezes the bounded, unconditional all-content dataflow.
        // The product claim remains the behavior tests above; equivalent refactors may require review.
        val source = readProductionSource()
        val runStart = source.indexOf("suspend fun run(")
        val runEnd = source.indexOf("\n    private companion object", runStart)
        require(runStart >= 0 && runEnd > runStart) { "adapter run source was not found" }
        val runBody = source.substring(runStart, runEnd)
        val expectedGate = """
            |        require(requestIdentity == expectedIdentity) { REQUEST_RUN_IDENTITY_ERROR }
            |        try {
            |            val outgoingConditionId = requestConditionId(requestBody)
            |            val runStartedConditionId = conditionIdFromRawEvent(rawEvents.first())
            |            val contentConditionIds = contentDataPayloads.map(::conditionIdFromPayload)
            |            requireNoDuplicateTerminalConditionKeys(terminalDataPayload)
            |            val terminalConditionId = conditionIdFromEnvelope(decodedTerminal.envelope)
            |            val terminalDetailsConditionId = (decodedTerminal.envelope["details"] as? JsonObject)
            |                ?.let(::conditionIdFromEnvelope)
            |            require(
            |                outgoingConditionId != null &&
            |                    runStartedConditionId == outgoingConditionId &&
            |                    contentConditionIds.all { it == outgoingConditionId } &&
            |                    terminalConditionId == outgoingConditionId &&
            |                    terminalDetailsConditionId == outgoingConditionId,
            |            ) {
            |                CONDITION_IDENTITY_ERROR
            |            }
            |        } catch (error: DuplicateConditionIdentityKeyException) {
            |            throw IllegalArgumentException(CONDITION_IDENTITY_ERROR, error)
            |        }
        """.trimMargin()
        assertEquals(1, runBody.split(expectedGate).size - 1)
        val conditionGateIndex = runBody.indexOf(expectedGate)
        val resultReturnIndex = runBody.indexOf("        return PrototypeRunStreamResult(")
        assertTrue(conditionGateIndex >= 0 && resultReturnIndex > conditionGateIndex)

        fun compact(value: String): String = value.replace(Regex("\\s+"), "")
        val helperStart = source.indexOf("private fun conditionIdFromPayload(dataPayload: String)")
        val helperEnd = source.indexOf(
            "\n        private fun requestConditionId",
            helperStart,
        )
        require(helperStart >= 0 && helperEnd > helperStart) {
            "condition payload helper was not found"
        }
        val expectedHelper = """
            private fun conditionIdFromPayload(dataPayload: String): String? {
                probeJson.decodeFromString(ConditionRootDuplicateKeyProbe, dataPayload)
                val envelope = try {
                    contentJson.parseToJsonElement(dataPayload)
                } catch (_: Exception) {
                    return null
                }
                return (envelope as? JsonObject)?.let(::conditionIdFromEnvelope)
            }
        """.trimIndent()
        assertEquals(
            compact(expectedHelper),
            compact(source.substring(helperStart, helperEnd)),
        )
    }

    @Test
    fun conditionIdentityRejectsInvalidRepresentationsAtEveryClaimedSurface() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val producer = officialProducerCases.first()
        val canonicalRequest = officialRunRequestBody(producer)
        val canonicalBlocks = producerShapedBlocks(doneFrame, producer)
        val canonicalMember = "\"condition_id\":\"" + producer.conditionId + "\""

        data class InvalidRepresentation(
            val label: String,
            val value: String?,
        )

        data class SurfaceCase(
            val label: String,
            val requestBody: String,
            val blocks: List<String>,
        )

        val invalidRepresentations = listOf(
            InvalidRepresentation("missing", null),
            InvalidRepresentation("null", "null"),
            InvalidRepresentation("boolean", "true"),
            InvalidRepresentation("number", "1"),
            InvalidRepresentation("object", "{}"),
            InvalidRepresentation("array", "[]"),
        )
        val surfaces = listOf(
            "request root",
            "run_started root",
            "content seq60",
            "terminal root",
            "terminal details",
        )
        fun replaceCondition(source: String, value: String?): String {
            if (value == null) {
                val withoutTrailingComma = source.replace(canonicalMember + ",", "")
                if (withoutTrailingComma != source) return withoutTrailingComma
                val withoutLeadingComma = source.replace("," + canonicalMember, "")
                require(withoutLeadingComma != source) {
                    "condition member missing while constructing missing representation"
                }
                return withoutLeadingComma
            }
            return source.replaceFirst(
                canonicalMember,
                "\"condition_id\":" + value,
            )
        }

        val cases = mutableListOf<SurfaceCase>()
        invalidRepresentations.forEach { representation ->
            surfaces.forEach { surface ->
                if (surface == "request root") {
                    cases += SurfaceCase(
                        "request root / " + representation.label,
                        replaceCondition(canonicalRequest, representation.value),
                        canonicalBlocks,
                    )
                } else {
                    val blocks = canonicalBlocks.toMutableList()
                    val blockIndex = when (surface) {
                        "run_started root" -> 0
                        "content seq60" -> 60
                        "terminal root", "terminal details" -> blocks.lastIndex
                        else -> error("unsupported condition surface: " + surface)
                    }
                    blocks[blockIndex] = if (surface == "terminal details") {
                        val terminal = blocks[blockIndex]
                        if (representation.value == null) {
                            val first = terminal.indexOf(canonicalMember)
                            val second = terminal.indexOf(
                                canonicalMember,
                                first + canonicalMember.length,
                            )
                            require(first >= 0 && second >= 0)
                            require(terminal.startsWith(canonicalMember + ",", second))
                            terminal.removeRange(
                                second,
                                second + canonicalMember.length + 1,
                            )
                        } else {
                            replaceSecondOccurrence(
                                terminal,
                                canonicalMember,
                                "\"condition_id\":" + representation.value,
                            )
                        }
                    } else if (surface == "terminal root" && representation.value == null) {
                        val terminal = blocks[blockIndex]
                        val first = terminal.indexOf(canonicalMember)
                        require(first >= 0)
                        require(terminal.startsWith(canonicalMember + ",", first))
                        terminal.removeRange(
                            first,
                            first + canonicalMember.length + 1,
                        )
                    } else {
                        replaceCondition(blocks[blockIndex], representation.value)
                    }
                    cases += SurfaceCase(
                        surface + " / " + representation.label,
                        canonicalRequest,
                        blocks,
                    )
                }
            }
        }
        assertEquals(30, cases.size)
        assertTrue(cases.all { it.blocks.size == 122 })

        val accepted = mutableListOf<String>()
        val wrongErrors = mutableListOf<String>()
        cases.forEach { case ->
            try {
                PrototypeRunStreamAdapter(
                    FakeRawPostTransport(rawStreamOf(case.blocks)),
                ).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = case.requestBody,
                )
                accepted += case.label
            } catch (error: IllegalArgumentException) {
                if (error.message != CONDITION_IDENTITY_ERROR) {
                    wrongErrors += case.label + ": " + error.message
                }
            }
        }

        assertTrue(
            "invalid condition representations accepted=$accepted wrongErrors=$wrongErrors",
            accepted.isEmpty() && wrongErrors.isEmpty(),
        )
    }

    @Test
    fun conditionIdentityRejectsDecodedStringVariantsAtEveryClaimedSurface() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val producer = officialProducerCases.first()
        val canonicalRequest = officialRunRequestBody(producer)
        val canonicalBlocks = producerShapedBlocks(doneFrame, producer)
        val canonicalMember = "\"condition_id\":\"" + producer.conditionId + "\""

        data class StringVariant(
            val label: String,
            val decoded: String,
        )

        data class SurfaceCase(
            val label: String,
            val requestBody: String,
            val blocks: List<String>,
        )

        fun jsonStringLiteral(decoded: String): String = buildString {
            append('"')
            decoded.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\t' -> append("\\t")
                    '\n' -> append("\\n")
                    '\u000C' -> append("\\f")
                    '\r' -> append("\\r")
                    else -> if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
            append('"')
        }

        val stringVariants = listOf(
            StringVariant("empty", ""),
            StringVariant("upper/case", producer.conditionId.uppercase()),
            StringVariant("leading space", " " + producer.conditionId),
            StringVariant("trailing space", producer.conditionId + " "),
            StringVariant("TAB", producer.conditionId + "\t"),
            StringVariant("LF", producer.conditionId + "\n"),
            StringVariant("CR", producer.conditionId + "\r"),
            StringVariant("NUL", producer.conditionId + "\u0000"),
            StringVariant(
                "NFKC-fullwidth-to-canonical",
                "\uFF42\uFF41\uFF53\uFF45\uFF4C\uFF49\uFF4E\uFF45\uFF3F\uFF56\uFF10\uFF0E\uFF11",
            ),
            StringVariant("prefix", "x" + producer.conditionId),
            StringVariant("suffix", producer.conditionId + "-x"),
        )
        val surfaces = listOf(
            "request",
            "run_started",
            "content seq60",
            "terminal root",
            "terminal details",
        )
        val cases = mutableListOf<SurfaceCase>()
        stringVariants.forEach { variant ->
            val replacement = "\"condition_id\":" + jsonStringLiteral(variant.decoded)
            surfaces.forEach { surface ->
                if (surface == "request") {
                    cases += SurfaceCase(
                        "request / " + variant.label,
                        canonicalRequest.replaceFirst(canonicalMember, replacement),
                        canonicalBlocks,
                    )
                } else {
                    val blocks = canonicalBlocks.toMutableList()
                    val blockIndex = when (surface) {
                        "run_started" -> 0
                        "content seq60" -> 60
                        "terminal root", "terminal details" -> blocks.lastIndex
                        else -> error("unsupported condition surface: " + surface)
                    }
                    blocks[blockIndex] = if (surface == "terminal details") {
                        replaceSecondOccurrence(
                            blocks[blockIndex],
                            canonicalMember,
                            replacement,
                        )
                    } else {
                        blocks[blockIndex].replaceFirst(canonicalMember, replacement)
                    }
                    cases += SurfaceCase(
                        surface + " / " + variant.label,
                        canonicalRequest,
                        blocks,
                    )
                }
            }
        }
        assertEquals(55, cases.size)
        assertTrue(cases.all { it.blocks.size == 122 })

        val accepted = mutableListOf<String>()
        val wrongErrors = mutableListOf<String>()
        cases.forEach { case ->
            try {
                PrototypeRunStreamAdapter(
                    FakeRawPostTransport(rawStreamOf(case.blocks)),
                ).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = case.requestBody,
                )
                accepted += case.label
            } catch (error: IllegalArgumentException) {
                if (error.message != CONDITION_IDENTITY_ERROR) {
                    wrongErrors += case.label + ": " + error.message
                }
            }
        }

        assertTrue(
            "decoded string variants accepted=$accepted wrongErrors=$wrongErrors",
            accepted.isEmpty() && wrongErrors.isEmpty(),
        )
    }

    @Test
    fun conditionIdentityAcceptsEquivalentRepresentationsAtEveryClaimedSurface() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val producer = officialProducerCases.first()
        val canonicalRequest = officialRunRequestBody(producer)
        val canonicalBlocks = producerShapedBlocks(doneFrame, producer)
        val canonicalMember = "\"condition_id\":\"" + producer.conditionId + "\""

        data class Representation(
            val label: String,
        )

        data class SurfaceCase(
            val surface: String,
            val label: String,
            val requestBody: String,
            val blocks: List<String>,
        )

        fun splitObjectMembers(objectJson: String): MutableList<String> {
            require(objectJson.startsWith("{") && objectJson.endsWith("}"))
            val body = objectJson.substring(1, objectJson.length - 1)
            if (body.isEmpty()) return mutableListOf()
            val members = mutableListOf<String>()
            var start = 0
            var depth = 0
            var inString = false
            var escaped = false
            body.forEachIndexed { index, character ->
                if (inString) {
                    if (escaped) {
                        escaped = false
                    } else if (character == '\\') {
                        escaped = true
                    } else if (character == '"') {
                        inString = false
                    }
                } else {
                    when (character) {
                        '"' -> inString = true
                        '{', '[' -> depth++
                        '}', ']' -> depth--
                        ',' -> if (depth == 0) {
                            members += body.substring(start, index)
                            start = index + 1
                        }
                    }
                }
            }
            require(!inString && !escaped && depth == 0)
            members += body.substring(start)
            return members
        }

        fun reorderedWithUnknown(objectJson: String, unknownKey: String): String {
            val members = splitObjectMembers(objectJson)
            val conditionIndex = members.indexOfFirst {
                it.trimStart().startsWith("\"condition_id\":")
            }
            require(conditionIndex >= 0)
            val conditionMember = members.removeAt(conditionIndex)
            members.add(0, conditionMember)
            members += "\"$unknownKey\":true"
            return "{" + members.joinToString(",") + "}"
        }

        fun reorderedBlockWithUnknown(block: String, nestedDetails: Boolean): String {
            val prefix = block.substringBefore("data: ") + "data: "
            val payload = block.substringAfter("data: ")
            if (!nestedDetails) {
                return prefix + reorderedWithUnknown(payload, "step_g_unknown")
            }
            val members = splitObjectMembers(payload)
            val detailsIndex = members.indexOfFirst {
                it.trimStart().startsWith("\"details\":")
            }
            require(detailsIndex >= 0)
            val detailsMember = members[detailsIndex]
            val colon = detailsMember.indexOf(':')
            require(colon >= 0)
            val detailsJson = detailsMember.substring(colon + 1).trim()
            members[detailsIndex] =
                detailsMember.substring(0, colon + 1) +
                    reorderedWithUnknown(detailsJson, "step_g_nested_unknown")
            return prefix + "{" + members.joinToString(",") + "}"
        }

        val representations = listOf(
            Representation("escaped semantic key"),
            Representation("unicode-escaped value"),
            Representation("reordered with unknown"),
        )
        val surfaces = listOf(
            "request",
            "run_started",
            "content seq60",
            "terminal root",
            "terminal details",
        )
        val cases = mutableListOf<SurfaceCase>()
        representations.forEach { representation ->
            surfaces.forEach { surface ->
                when (representation.label) {
                    "escaped semantic key" -> {
                        val replacement = "\"condition_\\u0069d\":\"${producer.conditionId}\""
                        if (surface == "request") {
                            cases += SurfaceCase(
                                surface,
                                surface + " / " + representation.label,
                                canonicalRequest.replaceFirst(canonicalMember, replacement),
                                canonicalBlocks,
                            )
                        } else {
                            val blocks = canonicalBlocks.toMutableList()
                            val blockIndex = when (surface) {
                                "run_started" -> 0
                                "content seq60" -> 60
                                "terminal root", "terminal details" -> blocks.lastIndex
                                else -> error("unsupported condition surface: " + surface)
                            }
                            blocks[blockIndex] = if (surface == "terminal details") {
                                replaceSecondOccurrence(
                                    blocks[blockIndex],
                                    canonicalMember,
                                    replacement,
                                )
                            } else {
                                blocks[blockIndex].replaceFirst(canonicalMember, replacement)
                            }
                            cases += SurfaceCase(
                                surface,
                                surface + " / " + representation.label,
                                canonicalRequest,
                                blocks,
                            )
                        }
                    }

                    "unicode-escaped value" -> {
                        val replacement =
                            "\"condition_id\":\"" +
                                unicodeEscapedJsonValue(producer.conditionId) +
                                "\""
                        if (surface == "request") {
                            cases += SurfaceCase(
                                surface,
                                surface + " / " + representation.label,
                                canonicalRequest.replaceFirst(canonicalMember, replacement),
                                canonicalBlocks,
                            )
                        } else {
                            val blocks = canonicalBlocks.toMutableList()
                            val blockIndex = when (surface) {
                                "run_started" -> 0
                                "content seq60" -> 60
                                "terminal root", "terminal details" -> blocks.lastIndex
                                else -> error("unsupported condition surface: " + surface)
                            }
                            blocks[blockIndex] = if (surface == "terminal details") {
                                replaceSecondOccurrence(
                                    blocks[blockIndex],
                                    canonicalMember,
                                    replacement,
                                )
                            } else {
                                blocks[blockIndex].replaceFirst(canonicalMember, replacement)
                            }
                            cases += SurfaceCase(
                                surface,
                                surface + " / " + representation.label,
                                canonicalRequest,
                                blocks,
                            )
                        }
                    }

                    "reordered with unknown" -> {
                        if (surface == "request") {
                            cases += SurfaceCase(
                                surface,
                                surface + " / " + representation.label,
                                reorderedWithUnknown(canonicalRequest, "step_g_unknown"),
                                canonicalBlocks,
                            )
                        } else {
                            val blocks = canonicalBlocks.toMutableList()
                            val blockIndex = when (surface) {
                                "run_started" -> 0
                                "content seq60" -> 60
                                "terminal root", "terminal details" -> blocks.lastIndex
                                else -> error("unsupported condition surface: " + surface)
                            }
                            blocks[blockIndex] = reorderedBlockWithUnknown(
                                blocks[blockIndex],
                                nestedDetails = surface == "terminal details",
                            )
                            cases += SurfaceCase(
                                surface,
                                surface + " / " + representation.label,
                                canonicalRequest,
                                blocks,
                            )
                        }
                    }

                    else -> error("unsupported representation: " + representation.label)
                }
            }
        }
        assertEquals(15, cases.size)
        assertTrue(cases.all { it.blocks.size == 122 })

        cases.forEach { case ->
            val transport = FakeRawPostTransport(rawStreamOf(case.blocks))
            val result = PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = case.requestBody,
            )
            assertNotNull(result.decodedTerminal)
            if (case.surface == "request") {
                assertEquals(case.requestBody, transport.postedBody)
            }
        }
    }

    @Test
    fun conditionIdentityDoesNotStealUpstreamErrors() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val producer = officialProducerCases.first()
        val canonicalConditionMember = "\"condition_id\":\"${producer.conditionId}\""
        val conditionDriftMember = "\"condition_id\":\"slow_v0.1\""
        val endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs"
        val canonicalRequest = officialRunRequestBody(producer)

        data class PrecedenceCase(
            val label: String,
            val requestBody: String,
            val stream: RawSseStream,
            val expectedMessage: String,
        )

        fun addConditionDrift(blocks: List<String>): MutableList<String> =
            blocks.toMutableList().also { mutated ->
                val original = mutated[0]
                mutated[0] = original.replaceFirst(canonicalConditionMember, conditionDriftMember)
                require(mutated[0] != original) { "condition drift mutation did not apply" }
            }

        val cases = mutableListOf<PrecedenceCase>()

        val topologyBlocks = addConditionDrift(producerShapedBlocks(doneFrame, producer))
        topologyBlocks.removeAt(60)
        cases += PrecedenceCase(
            label = "outer topology",
            requestBody = canonicalRequest,
            stream = rawStreamOf(topologyBlocks),
            expectedMessage =
                "prototype SSE stream must contain run_started, 120 content events, and final done",
        )

        val sequenceBlocks = addConditionDrift(producerShapedBlocks(doneFrame, producer))
        val originalSequence = sequenceBlocks[60]
        sequenceBlocks[60] = originalSequence.replace("\"seq\":60,", "\"seq\":61,")
        require(sequenceBlocks[60] != originalSequence)
        cases += PrecedenceCase(
            label = "content sequence",
            requestBody = canonicalRequest,
            stream = rawStreamOf(sequenceBlocks),
            expectedMessage = "prototype SSE content events must have exact seq 1 through 120",
        )

        val contentIdentityBlocks = addConditionDrift(producerShapedBlocks(doneFrame, producer))
        val originalContentIdentity = contentIdentityBlocks[60]
        contentIdentityBlocks[60] = originalContentIdentity.replace(
            "\"campaign_id\":\"${producer.campaignId}\"",
            "\"campaign_id\":\"campaign-content-forged\"",
        )
        require(contentIdentityBlocks[60] != originalContentIdentity)
        cases += PrecedenceCase(
            label = "content campaign/run identity",
            requestBody = canonicalRequest,
            stream = rawStreamOf(contentIdentityBlocks),
            expectedMessage = "prototype SSE content event identity must match the run",
        )

        val chronologyBlocks = addConditionDrift(producerShapedBlocks(doneFrame, producer))
        val chronologyArrivals = chronologyBlocks.indices
            .map { (it + 1) * 1_000L }
            .toMutableList()
            .also { arrivals -> arrivals[42] = arrivals[41] - 1L }
        cases += PrecedenceCase(
            label = "arrival chronology",
            requestBody = canonicalRequest,
            stream = rawStreamWithArrivals(chronologyBlocks, chronologyArrivals),
            expectedMessage =
                "prototype SSE content arrival timestamps must be non-negative and nondecreasing",
        )

        val terminalIdentityBlocks = addConditionDrift(producerShapedBlocks(doneFrame, producer))
        val originalTerminalIdentity = terminalIdentityBlocks.last()
        terminalIdentityBlocks[terminalIdentityBlocks.lastIndex] = originalTerminalIdentity.replaceFirst(
            "\"campaign_id\":\"${producer.campaignId}\"",
            "\"campaign_id\":\"campaign-terminal-forged\"",
        )
        require(terminalIdentityBlocks.last() != originalTerminalIdentity)
        cases += PrecedenceCase(
            label = "terminal campaign/run identity",
            requestBody = canonicalRequest,
            stream = rawStreamOf(terminalIdentityBlocks),
            expectedMessage = TERMINAL_IDENTITY_ERROR,
        )

        val completionBlocks = addConditionDrift(producerShapedBlocks(doneFrame, producer))
        val originalCompletion = completionBlocks.last()
        completionBlocks[completionBlocks.lastIndex] = originalCompletion.replace(
            "\"terminal_status\":\"complete\"",
            "\"terminal_status\":\"failed\"",
        )
        require(completionBlocks.last() != originalCompletion)
        cases += PrecedenceCase(
            label = "terminal completion facts",
            requestBody = canonicalRequest,
            stream = rawStreamOf(completionBlocks),
            expectedMessage = TERMINAL_COMPLETION_ERROR,
        )

        val requestProducer = producer.copy(
            campaignId = "campaign-request-forged",
            runId = "run-request-forged",
        )
        cases += PrecedenceCase(
            label = "outgoing request campaign/run identity",
            requestBody = officialRunRequestBody(requestProducer),
            stream = rawStreamOf(addConditionDrift(producerShapedBlocks(doneFrame, producer))),
            expectedMessage = REQUEST_RUN_IDENTITY_ERROR,
        )

        assertEquals(7, cases.size)
        cases.forEach { case ->
            try {
                PrototypeRunStreamAdapter(FakeRawPostTransport(case.stream)).run(
                    endpoint = endpoint,
                    requestBody = case.requestBody,
                )
                org.junit.Assert.fail("${case.label} error was hidden by condition validation")
            } catch (error: IllegalArgumentException) {
                assertEquals(case.expectedMessage, error.message)
            }
        }
    }

    @Test
    fun outgoingRequestIdentityMustMatchEveryStreamEvent() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val requestProducer = officialProducerCases.first().copy(
            campaignId = "campaign-request-a",
            runId = "run-request-a",
        )
        val requestBody = officialRunRequestBody(requestProducer)
        val endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs"
        val streamCases = listOf(
            "campaign only" to requestProducer.copy(campaignId = "campaign-stream-b"),
            "run only" to requestProducer.copy(runId = "run-stream-b"),
            "campaign and run" to requestProducer.copy(
                campaignId = "campaign-stream-b",
                runId = "run-stream-b",
            ),
        )
        val accepted = mutableListOf<String>()

        streamCases.forEach { (label, streamProducer) ->
            val transport = FakeRawPostTransport(
                rawStreamOf(producerShapedBlocks(doneFrame, streamProducer)),
            )
            try {
                PrototypeRunStreamAdapter(transport).run(
                    endpoint = endpoint,
                    requestBody = requestBody,
                )
                accepted += label
            } catch (error: IllegalArgumentException) {
                assertEquals(REQUEST_RUN_IDENTITY_ERROR, error.message)
            }
            assertEquals(endpoint, transport.postedUrl)
            assertEquals(requestBody, transport.postedBody)
        }

        assertTrue(
            "outgoing request identity variants were accepted: $accepted",
            accepted.isEmpty(),
        )
    }

    @Test
    fun matchingOfficialRequestIdentityIsAccepted() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val producer = officialProducerCases.first().copy(
            campaignId = "campaign-request-a",
            runId = "run-request-a",
        )
        val requestBody = officialRunRequestBody(producer)
        val transport = FakeRawPostTransport(
            rawStreamOf(producerShapedBlocks(doneFrame, producer)),
        )

        val result = PrototypeRunStreamAdapter(transport).run(
            endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
            requestBody = requestBody,
        )

        assertNotNull(result.decodedTerminal)
        assertEquals(requestBody, transport.postedBody)
    }

    @Test
    fun outgoingRequestIdentityRejectsSemanticDuplicateKeys() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val canonical = officialProducerCases.first().copy(
            campaignId = "campaign-request-a",
            runId = "run-request-a",
        )
        val forged = canonical.copy(
            campaignId = "campaign-request-b",
            runId = "run-request-b",
        )
        val campaignForged = canonical.copy(campaignId = forged.campaignId)
        val runForged = canonical.copy(runId = forged.runId)
        class DuplicateCase(
            val label: String,
            val field: String,
            val canonicalValue: String,
            val firstKey: String,
            val firstValue: String,
            val secondKey: String,
            val secondValue: String,
            val streamProducer: OfficialProducerCase,
        )
        val campaignEscaped = "campaign_\\u0069d"
        val runEscaped = "run_\\u0069d"
        val cases = listOf(
            DuplicateCase(
                "campaign canonical/canonical",
                "campaign_id",
                canonical.campaignId,
                "campaign_id",
                canonical.campaignId,
                "campaign_id",
                canonical.campaignId,
                canonical,
            ),
            DuplicateCase(
                "campaign canonical/forged",
                "campaign_id",
                canonical.campaignId,
                "campaign_id",
                canonical.campaignId,
                "campaign_id",
                forged.campaignId,
                campaignForged,
            ),
            DuplicateCase(
                "campaign forged/canonical",
                "campaign_id",
                canonical.campaignId,
                "campaign_id",
                forged.campaignId,
                "campaign_id",
                canonical.campaignId,
                canonical,
            ),
            DuplicateCase(
                "campaign plain/escaped",
                "campaign_id",
                canonical.campaignId,
                "campaign_id",
                canonical.campaignId,
                campaignEscaped,
                forged.campaignId,
                campaignForged,
            ),
            DuplicateCase(
                "campaign escaped/plain",
                "campaign_id",
                canonical.campaignId,
                campaignEscaped,
                forged.campaignId,
                "campaign_id",
                canonical.campaignId,
                canonical,
            ),
            DuplicateCase(
                "run canonical/canonical",
                "run_id",
                canonical.runId,
                "run_id",
                canonical.runId,
                "run_id",
                canonical.runId,
                canonical,
            ),
            DuplicateCase(
                "run canonical/forged",
                "run_id",
                canonical.runId,
                "run_id",
                canonical.runId,
                "run_id",
                forged.runId,
                runForged,
            ),
            DuplicateCase(
                "run forged/canonical",
                "run_id",
                canonical.runId,
                "run_id",
                forged.runId,
                "run_id",
                canonical.runId,
                canonical,
            ),
            DuplicateCase(
                "run plain/escaped",
                "run_id",
                canonical.runId,
                "run_id",
                canonical.runId,
                runEscaped,
                forged.runId,
                runForged,
            ),
            DuplicateCase(
                "run escaped/plain",
                "run_id",
                canonical.runId,
                runEscaped,
                forged.runId,
                "run_id",
                canonical.runId,
                canonical,
            ),
        )
        val accepted = mutableListOf<String>()

        cases.forEach { case ->
            val requestBody = requestBodyWithDuplicateIdentity(
                producer = canonical,
                field = case.field,
                canonicalValue = case.canonicalValue,
                firstKey = case.firstKey,
                firstValue = case.firstValue,
                secondKey = case.secondKey,
                secondValue = case.secondValue,
            )
            try {
                PrototypeRunStreamAdapter(
                    FakeRawPostTransport(
                        rawStreamOf(producerShapedBlocks(doneFrame, case.streamProducer)),
                    ),
                ).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = requestBody,
                )
                accepted += case.label
            } catch (error: IllegalArgumentException) {
                assertEquals(REQUEST_RUN_IDENTITY_ERROR, error.message)
            }
        }

        assertTrue(
            "request identity duplicate cases were accepted: $accepted",
            accepted.isEmpty(),
        )
    }

    @Test
    fun outgoingRequestIdentityAcceptsOfficialRepresentations() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val representations: List<Pair<String, (OfficialProducerCase) -> String>> = listOf(
            "canonical" to { producer -> officialRunRequestBody(producer) },
            "reordered whitespace" to { producer ->
                officialRunRequestBodyWithRepresentation(producer, reordered = true)
            },
            "escaped identity values" to { producer ->
                officialRunRequestBodyWithRepresentation(producer, escapedIdentityValues = true)
            },
        )
        val accepted = mutableListOf<String>()

        officialProducerCases.forEach { producer ->
            representations.forEach { (label, bodyFactory) ->
                val requestBody = bodyFactory(producer)
                val transport = FakeRawPostTransport(
                    rawStreamOf(producerShapedBlocks(doneFrame, producer)),
                )
                try {
                    val result = PrototypeRunStreamAdapter(transport).run(
                        endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                        requestBody = requestBody,
                    )
                    assertNotNull(result.decodedTerminal)
                    assertEquals(requestBody, transport.postedBody)
                    accepted += "${producer.label}/$label"
                } catch (error: Throwable) {
                    org.junit.Assert.fail(
                        "${producer.label}/$label official representation was rejected: ${error.message}",
                    )
                }
            }
        }

        assertEquals(
            "official request representation cases were not all accepted: $accepted",
            9,
            accepted.size,
        )
    }

    @Test
    fun outgoingRequestIdentityRejectsValidDistinctIds() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val baseline = officialProducerCases[0]
        val slow = officialProducerCases[1]
        val unstable = officialProducerCases[2]
        val cases = listOf(
            "baseline campaign case-only" to Pair(
                baseline,
                baseline.copy(campaignId = baseline.campaignId.replace("official", "Official")),
            ),
            "baseline run case-only" to Pair(
                baseline,
                baseline.copy(runId = baseline.runId.replace("official", "Official")),
            ),
            "baseline campaign formal forged" to Pair(
                baseline,
                baseline.copy(campaignId = "${baseline.campaignId}-forged"),
            ),
            "slow campaign suffix" to Pair(
                slow,
                slow.copy(campaignId = "${slow.campaignId}-suffix"),
            ),
            "slow run suffix" to Pair(
                slow,
                slow.copy(runId = "${slow.runId}-suffix"),
            ),
            "unstable campaign prefix" to Pair(
                unstable,
                unstable.copy(campaignId = "prefix-${unstable.campaignId}"),
            ),
            "unstable run prefix" to Pair(
                unstable,
                unstable.copy(runId = "prefix-${unstable.runId}"),
            ),
        )
        val accepted = mutableListOf<String>()
        val rejected = mutableListOf<String>()

        cases.forEach { (label, producers) ->
            val requestBody = officialRunRequestBody(producers.first)
            val transport = FakeRawPostTransport(
                rawStreamOf(producerShapedBlocks(doneFrame, producers.second)),
            )
            try {
                PrototypeRunStreamAdapter(transport).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = requestBody,
                )
                accepted += label
            } catch (error: IllegalArgumentException) {
                assertEquals(REQUEST_RUN_IDENTITY_ERROR, error.message)
                rejected += label
            }
        }

        assertTrue(
            "valid distinct request identity cases were accepted: $accepted",
            accepted.isEmpty(),
        )
        assertEquals(7, rejected.size)
    }

    @Test
    fun outgoingRequestIdentityRejectsRequestSideDistinctIds() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val baseline = officialProducerCases[0]
        val slow = officialProducerCases[1]
        val unstable = officialProducerCases[2]
        val cases = listOf(
            "baseline campaign case-only" to Pair(
                baseline.copy(campaignId = baseline.campaignId.replace("official", "Official")),
                baseline,
            ),
            "baseline run case-only" to Pair(
                baseline.copy(runId = baseline.runId.replace("official", "Official")),
                baseline,
            ),
            "slow campaign prefix" to Pair(
                slow.copy(campaignId = "prefix-${slow.campaignId}"),
                slow,
            ),
            "slow run suffix" to Pair(
                slow.copy(runId = "${slow.runId}-suffix"),
                slow,
            ),
            "unstable campaign suffix" to Pair(
                unstable.copy(campaignId = "${unstable.campaignId}-suffix"),
                unstable,
            ),
            "unstable run prefix" to Pair(
                unstable.copy(runId = "prefix-${unstable.runId}"),
                unstable,
            ),
            "unstable campaign and run" to Pair(
                unstable.copy(
                    campaignId = "${unstable.campaignId}-suffix",
                    runId = "prefix-${unstable.runId}",
                ),
                unstable,
            ),
        )
        val accepted = mutableListOf<String>()
        val rejected = mutableListOf<String>()

        cases.forEach { (label, producers) ->
            val requestBody = officialRunRequestBody(producers.first)
            val transport = FakeRawPostTransport(
                rawStreamOf(producerShapedBlocks(doneFrame, producers.second)),
            )
            try {
                PrototypeRunStreamAdapter(transport).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = requestBody,
                )
                accepted += label
            } catch (error: IllegalArgumentException) {
                assertEquals(REQUEST_RUN_IDENTITY_ERROR, error.message)
                rejected += label
            }
        }

        assertTrue(
            "request-side distinct identity cases were accepted: $accepted",
            accepted.isEmpty(),
        )
        assertEquals(7, rejected.size)
    }

    @Test
    fun outgoingRequestIdentityRejectsParserBoundaryFallbacks() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val cases = buildList {
            add("malformed left-brace" to "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"")
            listOf(
                "null" to "null",
                "string" to "\"request\"",
                "number" to "1",
                "bool" to "true",
                "array" to "[]",
            ).forEach { (label, body) -> add("root $label" to body) }
            listOf(
                "missing" to null,
                "null" to "null",
                "number" to "1",
                "bool" to "true",
                "array" to "[]",
                "object" to "{}",
                "empty" to "\"\"",
            ).forEach { (label, value) ->
                add("campaign $label" to requestBodyWithIdentityValue("campaign_id", value))
                add("run $label" to requestBodyWithIdentityValue("run_id", value))
            }
        }
        val accepted = mutableListOf<String>()
        val rejected = mutableListOf<String>()

        cases.forEach { (label, requestBody) ->
            try {
                PrototypeRunStreamAdapter(
                    FakeRawPostTransport(
                        rawStreamOf(canonicalBlocks(doneFrame, contentCount = 120)),
                    ),
                ).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = requestBody,
                )
                accepted += label
            } catch (error: IllegalArgumentException) {
                assertEquals(REQUEST_RUN_IDENTITY_ERROR, error.message)
                rejected += label
            }
        }

        assertTrue(
            "request parser boundary cases were accepted: $accepted",
            accepted.isEmpty(),
        )
        assertEquals(20, rejected.size)
    }

    @Test
    fun outgoingRequestIdentityRejectsDecodedWhitespaceAndNormalizationVariants() = runBlocking {
        // Adapter-internal strict vectors only: the formal Go entrance normally rejects these
        // request spellings. They prove that this consumer does not trim, coalesce, or normalize.
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val cases = listOf(
            "campaign leading space" to requestBodyWithIdentityValue(
                "campaign_id",
                "\" campaign-1\"",
            ),
            "campaign trailing space" to requestBodyWithIdentityValue(
                "campaign_id",
                "\"campaign-1 \"",
            ),
            "campaign TAB" to requestBodyWithIdentityValue(
                "campaign_id",
                "\"campaign-1\\t\"",
            ),
            "campaign LF" to requestBodyWithIdentityValue(
                "campaign_id",
                "\"campaign-1\\n\"",
            ),
            "campaign CR" to requestBodyWithIdentityValue(
                "campaign_id",
                "\"campaign-1\\r\"",
            ),
            "campaign NUL" to requestBodyWithIdentityValue(
                "campaign_id",
                "\"campaign-1\\u0000\"",
            ),
            "campaign NFKC" to requestBodyWithIdentityValue(
                "campaign_id",
                "\"\\uFF43ampaign-1\"",
            ),
            "run leading space" to requestBodyWithIdentityValue(
                "run_id",
                "\" run-1\"",
            ),
            "run trailing space" to requestBodyWithIdentityValue(
                "run_id",
                "\"run-1 \"",
            ),
            "run TAB" to requestBodyWithIdentityValue(
                "run_id",
                "\"run-1\\t\"",
            ),
            "run LF" to requestBodyWithIdentityValue(
                "run_id",
                "\"run-1\\n\"",
            ),
            "run CR" to requestBodyWithIdentityValue(
                "run_id",
                "\"run-1\\r\"",
            ),
            "run NUL" to requestBodyWithIdentityValue(
                "run_id",
                "\"run-1\\u0000\"",
            ),
            "run NFKC" to requestBodyWithIdentityValue(
                "run_id",
                "\"\\uFF52un-1\"",
            ),
        )
        val accepted = mutableListOf<String>()
        val rejected = mutableListOf<String>()

        cases.forEach { (label, requestBody) ->
            try {
                PrototypeRunStreamAdapter(
                    FakeRawPostTransport(
                        rawStreamOf(canonicalBlocks(doneFrame, contentCount = 120)),
                    ),
                ).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = requestBody,
                )
                accepted += label
            } catch (error: IllegalArgumentException) {
                assertEquals(REQUEST_RUN_IDENTITY_ERROR, error.message)
                rejected += label
            }
        }

        assertTrue(
            "request strict vectors were accepted: $accepted",
            accepted.isEmpty(),
        )
        assertEquals(cases.size, rejected.size)
    }

    @Test
    fun requestIdentityGateDoesNotStealTerminalErrors() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val streamProducer = officialProducerCases.first().copy(
            campaignId = "campaign-stream-precedence",
            runId = "run-stream-precedence",
        )
        val requestProducer = streamProducer.copy(
            campaignId = "campaign-request-precedence",
            runId = "run-request-precedence",
        )
        val requestBody = officialRunRequestBody(requestProducer)
        val cases = listOf(
            "terminal identity" to (TERMINAL_IDENTITY_ERROR to { frame: String ->
                frame.replace(
                    "\"${streamProducer.campaignId}\"",
                    "\"forged-terminal\"",
                )
            }),
            "terminal completion" to (TERMINAL_COMPLETION_ERROR to { frame: String ->
                replaceTerminalDetailValue(
                    frame,
                    field = "terminal_status",
                    canonicalValue = "\"complete\"",
                    replacementValue = "\"failed\"",
                )
            }),
        )

        cases.forEach { (label, expectedAndMutate) ->
            val blocks = producerShapedBlocks(doneFrame, streamProducer).toMutableList()
            blocks[blocks.lastIndex] = expectedAndMutate.second(blocks.last())
            try {
                PrototypeRunStreamAdapter(
                    FakeRawPostTransport(rawStreamOf(blocks)),
                ).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = requestBody,
                )
                org.junit.Assert.fail("$label error was accepted")
            } catch (error: IllegalArgumentException) {
                assertEquals(expectedAndMutate.first, error.message)
            }
        }
    }

    @Test
    fun requestIdentitySourceInvariantKeepsFinalUnconditionalGate() {
        val source = readProductionSource()
        val runStart = source.indexOf("suspend fun run(")
        val runEnd = source.indexOf("\n    private companion object", runStart)
        require(runStart >= 0 && runEnd > runStart) { "adapter run source was not found" }
        val runBody = source.substring(runStart, runEnd)
        val completionIndex = runBody.indexOf(
            "requireTerminalCompletionFacts(decodedTerminal.envelope)",
        )
        val requestAssignment = Regex(
            "(?m)^        val requestIdentity = requestIdentity\\(requestBody\\)$",
        ).findAll(runBody).toList()
        require(completionIndex >= 0) { "completion gate source was not found" }
        assertEquals(1, requestAssignment.size)
        val requestIndex = requestAssignment.single().range.first
        val requestGate = "require(requestIdentity == expectedIdentity) { REQUEST_RUN_IDENTITY_ERROR }"
        val requestGateIndex = runBody.indexOf(requestGate)
        val returnIndex = runBody.indexOf("return PrototypeRunStreamResult(", requestIndex)
        assertTrue(requestIndex > completionIndex)
        assertTrue(requestGateIndex > requestIndex)
        assertTrue(returnIndex > requestGateIndex)
        val requestStatements = """
            |        requireTerminalCompletionFacts(decodedTerminal.envelope)
            |        val requestIdentity = requestIdentity(requestBody)
            |        require(requestIdentity == expectedIdentity) { REQUEST_RUN_IDENTITY_ERROR }
        """.trimMargin()
        assertTrue(
            "completion/request/gate statements drifted from the frozen top-level chain",
            runBody.contains(requestStatements),
        )
        assertEquals(
            1,
            Regex("(?m)^        return\\b").findAll(runBody).count(),
        )
        val requestChain = runBody.substring(
            completionIndex,
            requestGateIndex + requestGate.length,
        )
        assertTrue(!requestChain.contains("if ("))
        assertTrue(!requestChain.contains("when"))
        assertTrue(!Regex("\\blet\\b").containsMatchIn(requestChain))
        assertTrue(!requestChain.contains("takeUnless"))
        assertTrue(!requestChain.contains("runCatching"))
        assertTrue(!requestChain.contains("endpoint"))
        assertTrue(!requestChain.contains("condition"))

        val helperStart = source.indexOf("private fun requestIdentity(requestBody: String)")
        val helperEnd = source.indexOf(
            "\n        private fun requireTerminalCompletionFacts",
            helperStart,
        )
        require(helperStart >= 0 && helperEnd > helperStart) { "request identity helper was not found" }
        val helper = source.substring(helperStart, helperEnd)
        val probeIndex = helper.indexOf(
            "probeJson.decodeFromString(ContentIdentityDuplicateKeyProbe, requestBody)",
        )
        val parseIndex = helper.indexOf("contentJson.parseToJsonElement(requestBody)")
        val identityIndex = helper.indexOf("(envelope as? JsonObject)?.let(::identityFromEnvelope)")
        assertTrue(probeIndex >= 0)
        assertTrue(parseIndex > probeIndex)
        assertTrue(identityIndex > parseIndex)
        assertTrue(!helper.contains("trim"))
        assertTrue(!helper.contains("ignoreCase"))
        assertTrue(!helper.contains("lowercase"))
        assertTrue(!helper.contains("uppercase"))
        assertTrue(!helper.contains("?: expectedIdentity"))
    }

    @Test
    fun requestIdentityAndExtractorKeepStrictFieldFrame() {
        val source = readProductionSource()
        fun compact(value: String): String = value.replace(Regex("\\s+"), "")

        val requestStart = source.indexOf("private fun requestIdentity(requestBody: String)")
        val requestEnd = source.indexOf(
            "\n        private fun requireTerminalCompletionFacts",
            requestStart,
        )
        require(requestStart >= 0 && requestEnd > requestStart) {
            "request identity helper was not found"
        }
        val expectedRequest = """
            private fun requestIdentity(requestBody: String): ContentRunIdentity? {
                val envelope = try {
                    probeJson.decodeFromString(ContentIdentityDuplicateKeyProbe, requestBody)
                    contentJson.parseToJsonElement(requestBody)
                } catch (_: Exception) {
                    return null
                }
                return (envelope as? JsonObject)?.let(::identityFromEnvelope)
            }
        """.trimIndent()
        assertEquals(
            compact(expectedRequest),
            compact(source.substring(requestStart, requestEnd)),
        )

        val identityStart = source.indexOf(
            "private fun identityFromEnvelope(envelope: JsonObject)",
        )
        val identityEnd = Regex("(?m)^        private fun ")
            .find(source, identityStart + 1)
            ?.range
            ?.first
            ?: -1
        require(identityStart >= 0 && identityEnd > identityStart) {
            "identity extractor was not found"
        }
        val expectedIdentity = """
            private fun identityFromEnvelope(envelope: JsonObject): ContentRunIdentity? {
                val campaignId = (envelope["campaign_id"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                val runId = (envelope["run_id"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                return if (campaignId != null && runId != null) {
                    ContentRunIdentity(campaignId, runId)
                } else {
                    null
                }
            }
        """.trimIndent()
        assertEquals(
            compact(expectedIdentity),
            compact(source.substring(identityStart, identityEnd)),
        )
    }

    @Test
    fun requestIdentityProvenanceAndContentRunIdentityRemainUniqueFrames() {
        // [FRAME] Maintenance-only whitelist: binds provenance and value-object shape; it adds no
        // product claim and intentionally rejects equivalent refactors outside this exact frame.
        val source = readProductionSource()
        fun compact(value: String): String = value.replace(Regex("\\s+"), "")

        assertEquals(
            1,
            Regex("(?m)\\bfun\\s+requestIdentity\\s*\\(").findAll(source).count(),
        )
        assertEquals(
            1,
            Regex("(?m)^\\s*private\\s+fun\\s+requestIdentity\\(requestBody: String\\)")
                .findAll(source)
                .count(),
        )
        assertEquals(
            3,
            Regex("(?m)\\brequestIdentity\\(requestBody\\)").findAll(source).count(),
        )
        assertEquals(
            1,
            Regex("(?m)^        val observationIdentity = requestIdentity\\(requestBody\\)$")
                .findAll(source)
                .count(),
        )
        assertEquals(
            1,
            Regex("(?m)^        val requestIdentity = requestIdentity\\(requestBody\\)$")
                .findAll(source)
                .count(),
        )
        assertEquals(
            1,
            Regex(
                "(?m)^            require\\(requestIdentity\\(requestBody\\) == expectedIdentity\\) " +
                    "\\{ REQUEST_RUN_IDENTITY_ERROR \\}$",
            ).findAll(source).count(),
        )

        assertEquals(
            1,
            Regex("(?m)\\bdata\\s+class\\s+ContentRunIdentity\\s*\\(")
                .findAll(source)
                .count(),
        )
        val identityDataStart = source.indexOf("private data class ContentRunIdentity(")
        val identityDataEnd = source.indexOf(
            "\n\nprivate data class ValidatedContentPayload",
            identityDataStart,
        )
        require(identityDataStart >= 0 && identityDataEnd > identityDataStart) {
            "content run identity declaration was not found"
        }
        val expectedDataClass = """
            private data class ContentRunIdentity(
                val campaignId: String,
                val runId: String,
            )
        """.trimIndent()
        assertEquals(
            compact(expectedDataClass),
            compact(source.substring(identityDataStart, identityDataEnd)),
        )

        assertEquals(
            1,
            Regex("(?m)\\bfun\\s+identityFromEnvelope\\s*\\(")
                .findAll(source)
                .count(),
        )
        assertTrue(
            source.contains(
                "require(identityFromEnvelope(envelope) == expectedIdentity) " +
                    "{ CONTENT_IDENTITY_ERROR }",
            ),
        )
        assertTrue(
            source.contains("identityFromEnvelope(decodedTerminal.envelope) == expectedIdentity"),
        )
        assertTrue(
            source.contains("return (envelope as? JsonObject)?.let(::identityFromEnvelope)"),
        )

        val runStart = source.indexOf("suspend fun run(")
        val runEnd = source.indexOf("\n    private companion object", runStart)
        require(runStart >= 0 && runEnd > runStart) { "adapter run source was not found" }
        val runBody = source.substring(runStart, runEnd)
        assertEquals(
            0,
            Regex("(?m)^\\s*(?:private\\s+)?fun\\s+(?:requestIdentity|identityFromEnvelope)\\s*\\(")
                .findAll(runBody)
                .count(),
        )
    }

    @Test
    fun requestIdentityErrorHasOnlyTheFrozenCompleteAndInterruptedConsumers() {
        // [FRAME] Maintenance-only whitelist: binds this stable error's provenance after the
        // complete-stream and interrupted-prefix validations; it adds no product claim.
        val source = readProductionSource()
        val stableMessage = "prototype SSE run identity must match the outgoing request"

        assertEquals(
            3,
            Regex("(?m)\\bREQUEST_RUN_IDENTITY_ERROR\\b").findAll(source).count(),
        )
        assertEquals(
            1,
            Regex(
                "(?m)^private const val REQUEST_RUN_IDENTITY_ERROR =$",
            ).findAll(source).count(),
        )
        assertEquals(
            1,
            Regex(Regex.escape(stableMessage)).findAll(source).count(),
        )
        assertEquals(
            1,
            Regex(
                "(?m)^        require\\(requestIdentity == expectedIdentity\\) \\{ REQUEST_RUN_IDENTITY_ERROR \\}$",
            ).findAll(source).count(),
        )
        assertEquals(
            1,
            Regex(
                "(?m)^            require\\(requestIdentity\\(requestBody\\) == expectedIdentity\\) " +
                    "\\{ REQUEST_RUN_IDENTITY_ERROR \\}$",
            ).findAll(source).count(),
        )

        val runStart = source.indexOf("suspend fun run(")
        val runEnd = source.indexOf("\n    private companion object", runStart)
        require(runStart >= 0 && runEnd > runStart) { "adapter run source was not found" }
        val runBody = source.substring(runStart, runEnd)
        val completionIndex = runBody.indexOf(
            "requireTerminalCompletionFacts(decodedTerminal.envelope)",
        )
        val requestAssignmentIndex = runBody.indexOf(
            "val requestIdentity = requestIdentity(requestBody)",
        )
        val requestGateIndex = runBody.indexOf(
            "require(requestIdentity == expectedIdentity) { REQUEST_RUN_IDENTITY_ERROR }",
        )
        val returnIndex = runBody.indexOf("return PrototypeRunStreamResult(")
        assertTrue(completionIndex >= 0)
        assertTrue(requestAssignmentIndex > completionIndex)
        assertTrue(requestGateIndex > requestAssignmentIndex)
        assertTrue(returnIndex > requestGateIndex)
    }

    @Test
    fun runStartedPayloadAcceptsReorderedAndEscapedEventTypeRepresentations() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val payloads = listOf(
            "reordered keys" to
                "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\",\"condition_id\":\"baseline_v0.1\",\"event_type\":\"run_started\"}",
            "escaped key" to
                "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\",\"condition_id\":\"baseline_v0.1\",\"\\u0065vent_type\":\"run_started\"}",
            "escaped value" to
                "{\"event_type\":\"run_\\u0073tarted\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\",\"condition_id\":\"baseline_v0.1\"}",
        )

        payloads.forEach { (label, payload) ->
            val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
            blocks[0] = "event: run_started\ndata: $payload"
            try {
                val result = PrototypeRunStreamAdapter(
                    FakeRawPostTransport(rawStreamOf(blocks)),
                ).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = canonicalRequestBody(),
                )
                assertNotNull(result.decodedTerminal)
            } catch (error: Throwable) {
                org.junit.Assert.fail("$label representation was rejected: ${error.message}")
            }
        }
    }

    @Test
    fun runStartedPayloadEventTypeDoesNotStealIdentityError() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val cases = listOf(
            "missing campaign_id" to
                "{\"event_type\":\"content_event\",\"run_id\":\"run-1\"}",
            "null campaign_id" to
                "{\"event_type\":\"content_event\",\"campaign_id\":null,\"run_id\":\"run-1\"}",
            "non-string run_id" to
                "{\"event_type\":\"content_event\",\"campaign_id\":\"campaign-1\",\"run_id\":1}",
            "duplicate campaign_id" to
                "{\"event_type\":\"content_event\",\"campaign_id\":\"forged\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            "malformed JSON" to
                "{\"event_type\":\"content_event\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"",
        )

        cases.forEach { (label, payload) ->
            val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
            blocks[0] = runStartedBlock(payload)
            try {
                PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
                )
                org.junit.Assert.fail("$label with event_type drift was accepted")
            } catch (error: IllegalArgumentException) {
                assertEquals(
                    "prototype SSE content event identity must match the run",
                    error.message,
                )
            }
        }
    }

    @Test
    fun runStartedPayloadEventTypePrecedesDownstreamErrors() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val cases = listOf("content sequence", "arrival chronology", "terminal identity")

        cases.forEach { label ->
            val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
            blocks[0] = blocks[0].replace(
                "\"event_type\":\"run_started\"",
                "\"event_type\":\"content_event\"",
            )
            val arrivals = if (label == "arrival chronology") {
                blocks.indices.map { (it + 1) * 1_000L }.toMutableList().also {
                    it[42] = it[41] - 1L
                }
            } else {
                null
            }
            when (label) {
                "content sequence" -> blocks[1] = serverContentBlock(2)
                "terminal identity" -> blocks[blocks.lastIndex] =
                    blocks[blocks.lastIndex].replace("\"campaign-1\"", "\"forged-terminal\"")
            }
            val rawStream = arrivals?.let { rawStreamWithArrivals(blocks, it) } ?: rawStreamOf(blocks)
            try {
                PrototypeRunStreamAdapter(FakeRawPostTransport(rawStream)).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
                )
                org.junit.Assert.fail("$label error was hidden by acceptance")
            } catch (error: IllegalArgumentException) {
                assertEquals(
                    "prototype SSE run_started payload event_type must match the SSE event",
                    error.message,
                )
            }
        }
    }

    @Test
    fun runStartedPayloadEventTypeRejectsDuplicateWrongAndNormalizedValues() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val cases = listOf(
            "literal duplicate wrong then canonical" to
                "{\"event_type\":\"content_event\",\"event_type\":\"run_started\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            "literal duplicate canonical then wrong" to
                "{\"event_type\":\"run_started\",\"event_type\":\"content_event\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            "escaped duplicate wrong then canonical" to
                "{\"event_type\":\"content_event\",\"\\u0065vent_type\":\"run_started\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            "escaped duplicate canonical then wrong" to
                "{\"\\u0065vent_type\":\"run_started\",\"event_type\":\"content_event\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            "number" to
                "{\"event_type\":1,\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            "null" to
                "{\"event_type\":null,\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            "array" to
                "{\"event_type\":[],\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            "object" to
                "{\"event_type\":{},\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            "case drift" to
                "{\"event_type\":\"RUN_STARTED\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            "space drift" to
                "{\"event_type\":\" run_started \",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            "NFKC drift" to
                "{\"event_type\":\"\\uFF52un_started\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
        )

        cases.forEach { (label, payload) ->
            val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
            blocks[0] = runStartedBlock(payload)
            try {
                PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
                )
                org.junit.Assert.fail("$label event_type was accepted")
            } catch (error: IllegalArgumentException) {
                assertEquals(
                    "prototype SSE run_started payload event_type must match the SSE event",
                    error.message,
                )
            }
        }
    }

    @Test
    fun runStartedPayloadRequiresEventTypeAndRejectsBoundaryWrongStrings() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val cases = listOf(
            "missing" to producerRunStartedPayload(eventTypeMembers = emptyList()),
            "empty" to producerRunStartedPayload(
                eventTypeMembers = listOf("\"event_type\":\"\""),
            ),
            "prefix" to producerRunStartedPayload(
                eventTypeMembers = listOf("\"event_type\":\"xrun_started\""),
            ),
            "suffix" to producerRunStartedPayload(
                eventTypeMembers = listOf("\"event_type\":\"run_startedx\""),
            ),
            "NUL" to producerRunStartedPayload(
                eventTypeMembers = listOf("\"event_type\":\"run_started\\u0000\""),
            ),
            "TAB" to producerRunStartedPayload(
                eventTypeMembers = listOf("\"event_type\":\"run_started\\t\""),
            ),
            "LF" to producerRunStartedPayload(
                eventTypeMembers = listOf("\"event_type\":\"run_started\\n\""),
            ),
            "CR" to producerRunStartedPayload(
                eventTypeMembers = listOf("\"event_type\":\"run_started\\r\""),
            ),
            "bool" to producerRunStartedPayload(
                eventTypeMembers = listOf("\"event_type\":true"),
            ),
        )

        cases.forEach { (label, payload) ->
            val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
            blocks[0] = runStartedBlock(payload)
            try {
                PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
                )
                org.junit.Assert.fail("$label run_started event_type was accepted")
            } catch (error: IllegalArgumentException) {
                assertEquals(
                    "prototype SSE run_started payload event_type must match the SSE event",
                    error.message,
                )
            }
        }
    }

    @Test
    fun runStartedPayloadAcceptsProducerEnvelopeInAnyKeyOrder() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val payloads = listOf(
            "canonical" to producerRunStartedPayload(),
            "reordered" to producerRunStartedPayload(reordered = true),
        )

        payloads.forEach { (label, payload) ->
            val producer = officialProducerCases.first()
            val blocks = canonicalBlocksForIdentity(
                doneFrame = doneFrame,
                contentCount = 120,
                campaignId = producer.campaignId,
                runId = producer.runId,
            ).toMutableList()
            blocks[0] = runStartedBlock(payload)
            try {
                val result = PrototypeRunStreamAdapter(
                    FakeRawPostTransport(rawStreamOf(blocks)),
                ).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = officialRunRequestBody(producer),
                )
                assertNotNull(result.decodedTerminal)
            } catch (error: Throwable) {
                org.junit.Assert.fail("$label producer-shaped run_started was rejected: ${error.message}")
            }
        }
    }

    @Test
    fun runStartedPayloadRejectsSameValueEventTypeDuplicates() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val cases = listOf(
            "plain/plain" to producerRunStartedPayload(
                eventTypeMembers = listOf(
                    "\"event_type\":\"run_started\"",
                    "\"event_type\":\"run_started\"",
                ),
            ),
            "plain/escaped" to producerRunStartedPayload(
                eventTypeMembers = listOf(
                    "\"event_type\":\"run_started\"",
                    "\"\\u0065vent_type\":\"run_started\"",
                ),
            ),
        )

        cases.forEach { (label, payload) ->
            val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
            blocks[0] = runStartedBlock(payload)
            try {
                PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
                )
                org.junit.Assert.fail("$label event_type duplicate was accepted")
            } catch (error: IllegalArgumentException) {
                assertEquals(
                    "prototype SSE run_started payload event_type must match the SSE event",
                    error.message,
                )
            }
        }
    }

    @Test
    fun runStartedSseEventLinePrecedesBadPayloadEventType() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        blocks[0] = "event: forged\ndata: " + producerRunStartedPayload(
            eventTypeMembers = listOf("\"event_type\":\"content_event\""),
        )

        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("topology drift hid behind payload event_type")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE stream must contain run_started, 120 content events, and final done",
                error.message,
            )
        }
    }

    @Test
    fun producerShapedRunStartedEventTypeControlsAcrossOfficialConditions() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        officialProducerCases.forEach { producer ->
            listOf(
                true to "run_started",
                false to "content_event",
            ).forEach { (accepted, eventType) ->
                val payload = producerRunStartedPayload(
                    eventTypeMembers = listOf("\"event_type\":\"$eventType\""),
                    producer = producer,
                )
                val blocks = producerShapedBlocks(doneFrame, producer).toMutableList()
                blocks[0] = runStartedBlock(payload)
                val endpoint = if (producer.label == "slow") {
                    "http://127.0.0.1:19001/api/v1/prototype/runs?condition=slow_v0.1"
                } else {
                    "http://127.0.0.1:18088/api/v1/prototype/runs"
                }
                val requestBody = officialRunRequestBody(producer)
                val transport = FakeRawPostTransport(rawStreamOf(blocks))

                try {
                    val result = PrototypeRunStreamAdapter(transport).run(endpoint, requestBody)
                    if (!accepted) {
                        org.junit.Assert.fail(
                            "${producer.label} event_type=$eventType was accepted",
                        )
                    }
                    assertNotNull(result.decodedTerminal)
                    if (producer.label == "slow") {
                        assertEquals(endpoint, transport.postedUrl)
                        assertEquals(requestBody, transport.postedBody)
                    }
                } catch (error: IllegalArgumentException) {
                    if (accepted) {
                        org.junit.Assert.fail(
                            "${producer.label} canonical producer event_type was rejected: ${error.message}",
                        )
                    }
                    assertEquals(
                        "prototype SSE run_started payload event_type must match the SSE event",
                        error.message,
                    )
                }
            }
        }
    }

    @Test
    fun postTransportPreservesProvidedRawEventsAndDecodesSharedTerminalFixture() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        // Content sequence and content run identity are claimed in this atom;
        // request/terminal cross-binding and payload remain NONCLAIMS.
        val blocks = canonicalBlocks(doneFrame, contentCount = 120)
        val streamText = blocks.joinToString(separator = "\n\n", postfix = "\n\n")
        val arrivals = blocks.indices.map { (it + 1) * 1_000L }
        val rawStream = RawSseStream(
            events = blocks.mapIndexed { index, block ->
                RawSseEvent(
                    bytes = block.toByteArray(Charsets.UTF_8),
                    arrivalNanos = arrivals[index],
                    sameReadBatch = false,
                )
            },
            readCount = blocks.size,
            totalBytes = streamText.toByteArray(Charsets.UTF_8).size.toLong(),
            truncatedTail = false,
            eofNanos = 4_000L,
        )
        val endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs"
        val requestBody = canonicalRequestBody()
        val transport = FakeRawPostTransport(rawStream)

        val result = PrototypeRunStreamAdapter(transport).run(endpoint, requestBody)

        assertEquals(endpoint, transport.postedUrl)
        assertEquals(requestBody, transport.postedBody)
        assertEquals(1, transport.callCount)
        assertSame(rawStream.events, result.rawEvents)
        assertEquals(arrivals, result.rawEvents.map(RawSseEvent::arrivalNanos))
        assertEquals(
            blocks,
            result.rawEvents.map { it.bytes.toString(Charsets.UTF_8) },
        )
        assertNotNull(result.decodedTerminal)
        assertEquals("done", result.decodedTerminal.eventName)
        assertEquals(
            "terminal_event",
            result.decodedTerminal.envelope.getValue("event_type").jsonPrimitive.content,
        )
        assertTrue(result.rawEvents.last().bytes.contentEquals(blocks.last().toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun missingContentTopologyFailsClosedWithStableMessage() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val rawStream = rawStreamOf(canonicalBlocks(doneFrame, contentCount = 119))
        val transport = FakeRawPostTransport(rawStream)

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = canonicalRequestBody(),
            )
            org.junit.Assert.fail("119 content events were accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE stream must contain run_started, 120 content events, and final done",
                error.message,
            )
        }
    }

    @Test
    fun contentSequenceMustBeExactOneThrough120() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val sequenceValues = listOf(1, 1) + (3..120).toList()
        val blocks = buildList {
            add(
                "event: run_started\ndata: " +
                    "{\"event_type\":\"run_started\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            sequenceValues.forEach { seq -> add(serverContentBlock(seq)) }
            add(doneFrame.removeSuffix("\n\n"))
        }
        assertEquals(122, blocks.size)
        val transport = FakeRawPostTransport(rawStreamOf(blocks))

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("duplicate content sequence was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content events must have exact seq 1 through 120",
                error.message,
            )
        }
    }

    @Test
    fun contentRunIdentityMismatchFailsClosedWithStableMessage() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        blocks[0] =
            "event: run_started\ndata: " +
                "{\"event_type\":\"run_started\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}"
        val mismatchedContentIndex = 1 + 41
        blocks[mismatchedContentIndex] = blocks[mismatchedContentIndex].replace(
            "\"campaign_id\":\"campaign-1\"",
            "\"campaign_id\":\"campaign-mismatch\"",
        )
        val transport = FakeRawPostTransport(rawStreamOf(blocks))

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("content campaign identity mismatch was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content event identity must match the run",
                error.message,
            )
        }
    }

    @Test
    fun terminalReceiptIdentityMismatchFailsClosedWithStableMessage() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList().also {
            it[it.lastIndex] = doneFrame.removeSuffix("\n\n")
        }
        val transport = FakeRawPostTransport(rawStreamOf(blocks))

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("cross-run terminal receipt identity was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE terminal receipt identity must match the run",
                error.message,
            )
        }
    }

    @Test
    fun terminalCompletionFactsMustReportComplete120() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val variants = listOf(
            "terminal status" to
                ("\"terminal_status\":\"complete\"" to
                    "\"terminal_status\":\"failed\""),
            "planned event count" to
                ("\"planned_event_count\":120" to
                    "\"planned_event_count\":119"),
            "emitted event count" to
                ("\"emitted_event_count\":120" to
                    "\"emitted_event_count\":119"),
        )
        val accepted = mutableListOf<String>()

        variants.forEach { (label, replacement) ->
            val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
            val original = blocks[blocks.lastIndex]
            blocks[blocks.lastIndex] = original.replace(replacement.first, replacement.second)
            require(blocks[blocks.lastIndex] != original) { "$label fixture mutation did not apply" }

            try {
                PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
                )
                accepted += label
            } catch (error: IllegalArgumentException) {
                assertEquals(
                    "prototype SSE terminal receipt must report complete 120-event delivery",
                    error.message,
                )
            }
        }

        assertTrue(
            "terminal completion fact variants were accepted: $accepted",
            accepted.isEmpty(),
        )
    }

    @Test
    fun terminalCompletionStatusRequiresExactString() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val invalidCases = listOf(
            "missing" to null,
            "null" to "null",
            "number" to "1",
            "boolean" to "true",
            "array" to "[]",
            "object" to "{}",
            "empty" to "\"\"",
            "case" to "\"Complete\"",
            "leading space" to "\" complete\"",
            "trailing space" to "\"complete \"",
            "tab" to "\"com\\tplete\"",
            "line feed" to "\"com\\nplete\"",
            "carriage return" to "\"com\\rplete\"",
            "NUL" to "\"com\\u0000plete\"",
            "NFKC" to "\"\\uFF43omplete\"",
            "prefix" to "\"xcomplete\"",
            "suffix" to "\"completex\"",
        )
        invalidCases.forEach { (label, replacement) ->
            val blocks = completionBlocks(doneFrame) { frame ->
                if (replacement == null) {
                    removeTerminalDetailValue(frame, "terminal_status", "\"complete\"")
                } else {
                    replaceTerminalDetailValue(
                        frame,
                        "terminal_status",
                        "\"complete\"",
                        replacement,
                    )
                }
            }
            assertTerminalCompletionRejected(label, blocks)
        }

        val escapedStatus = completionBlocks(doneFrame) { frame ->
            replaceTerminalDetailValue(
                frame,
                "terminal_status",
                "\"complete\"",
                "\"com\\u0070lete\"",
            )
        }
        assertTerminalCompletionAccepted("escaped status", escapedStatus)
    }

    @Test
    fun terminalCompletionCountsRequireExactJsonInteger120() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val fields = listOf(
            "planned_event_count",
            "emitted_event_count",
        )
        val invalidValues = listOf(
            "null" to "null",
            "boolean" to "true",
            "string" to "\"120\"",
            "119" to "119",
            "121" to "121",
            "float" to "120.0",
            "exponent" to "1.2e2",
            "array" to "[]",
            "object" to "{}",
        )
        fields.forEach { field ->
            assertTerminalCompletionRejected(
                "$field missing",
                completionBlocks(doneFrame) { frame ->
                    removeTerminalDetailValue(frame, field, "120")
                },
            )
            invalidValues.forEach { (label, replacement) ->
                assertTerminalCompletionRejected(
                    "$field $label",
                    completionBlocks(doneFrame) { frame ->
                        replaceTerminalDetailValue(frame, field, "120", replacement)
                    },
                )
            }
        }

        val coordinatedMismatch = completionBlocks(doneFrame) { frame ->
            replaceTerminalDetailValue(
                replaceTerminalDetailValue(frame, "planned_event_count", "120", "119"),
                "emitted_event_count",
                "120",
                "119",
            )
        }
        assertTerminalCompletionRejected("coordinated 119/119", coordinatedMismatch)

        val surroundingWhitespace = completionBlocks(doneFrame) { frame ->
            frame
                .replace(
                    "\"planned_event_count\":120",
                    "\"planned_event_count\" : \t120",
                )
                .replace(
                    "\"emitted_event_count\":120",
                    "\"emitted_event_count\" : \t120",
                )
        }
        assertTerminalCompletionAccepted("numeric surrounding whitespace", surroundingWhitespace)
    }

    @Test
    fun terminalCompletionLiteralDuplicatesAreRejectedForEveryField() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val fields = listOf(
            "planned_event_count" to ("120" to "119"),
            "emitted_event_count" to ("120" to "119"),
            "terminal_status" to ("\"complete\"" to "\"failed\""),
        )
        fields.forEach { (field, values) ->
            listOf(
                "canonical/canonical" to (values.first to values.first),
                "canonical/bad" to (values.first to values.second),
                "bad/canonical" to (values.second to values.first),
            ).forEach { (label, order) ->
                assertTerminalCompletionRejected(
                    "$field $label",
                    completionBlocks(doneFrame) { frame ->
                        duplicateTerminalDetailValues(frame, field, order.first, order.second)
                    },
                )
            }
        }
    }

    @Test
    fun terminalCompletionSemanticDuplicatesRejectBothKeyOrdersAndAllowSingleEscapes() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val fields = listOf(
            "planned_event_count" to ("\\u0070lanned_event_count" to "120"),
            "emitted_event_count" to ("\\u0065mitted_event_count" to "120"),
            "terminal_status" to ("terminal_\\u0073tatus" to "\"complete\""),
        )
        fields.forEach { (field, escaped) ->
            listOf(false, true).forEach { escapedFirst ->
                assertTerminalCompletionRejected(
                    "$field plain/escaped order=$escapedFirst",
                    completionBlocks(doneFrame) { frame ->
                        duplicateTerminalDetailKeys(
                            frame,
                            field,
                            escaped.first,
                            escaped.second,
                            escapedFirst,
                        )
                    },
                )
            }
            assertTerminalCompletionAccepted(
                "$field single escaped key",
                completionBlocks(doneFrame) { frame ->
                    replaceTerminalDetailKey(frame, field, escaped.first, escaped.second)
                },
            )
        }
    }

    @Test
    fun terminalCompletionPreservesUnknownOrderAndExistingErrorPrecedence() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val reorderedWithUnknown = completionBlocks(doneFrame) { frame ->
            frame
                .replace(
                    "\"planned_event_count\":120,\"emitted_event_count\":120,\"terminal_status\":\"complete\"",
                    "\"terminal_status\":\"complete\",\"emitted_event_count\":120,\"planned_event_count\":120",
                )
                .replace(
                    "\"terminal_status\":\"complete\"",
                    "\"terminal_status\":\"complete\",\"unknown_nested\":1",
                )
                .replaceFirst(
                    "{",
                    "{\"unknown_root\":1,",
                )
        }
        assertTerminalCompletionAccepted("reordered fields and unknown keys", reorderedWithUnknown)

        val rootNamedExtras = completionBlocks(doneFrame) { frame ->
            frame.replaceFirst(
                "{",
                "{\"terminal_status\":\"failed\",\"planned_event_count\":119,\"emitted_event_count\":119,",
            )
        }
        assertTerminalCompletionAccepted("root-layer completion names", rootNamedExtras)

        val nestedCompletionMissing = completionBlocks(doneFrame) { frame ->
            var mutated = frame
            mutated = removeTerminalDetailValue(mutated, "planned_event_count", "120")
            mutated = removeTerminalDetailValue(mutated, "emitted_event_count", "120")
            mutated = removeTerminalDetailValue(mutated, "terminal_status", "\"complete\"")
            mutated.replaceFirst(
                "{",
                "{\"terminal_status\":\"complete\",\"planned_event_count\":120,\"emitted_event_count\":120,",
            )
        }
        assertTerminalCompletionRejected("root-only completion facts", nestedCompletionMissing)

        val chronologyAndCompletion = completionBlocks(doneFrame) { frame ->
            replaceTerminalDetailValue(frame, "terminal_status", "\"complete\"", "\"failed\"")
        }
        val chronologyArrivals = chronologyAndCompletion.indices.map { (it + 1) * 1_000L }
            .toMutableList()
            .also { it[42] = it[41] - 1L }
        assertTerminalCompletionRejected(
            "chronology before completion",
            chronologyAndCompletion,
            chronologyArrivals,
            "prototype SSE content arrival timestamps must be non-negative and nondecreasing",
        )

        val identityAndCompletion = completionBlocks(doneFrame) { frame ->
            replaceTerminalDetailValue(
                frame
                    .replace("\"campaign-1\"", "\"forged-terminal\"")
                    .replace("\"run-1\"", "\"forged-terminal-run\""),
                "terminal_status",
                "\"complete\"",
                "\"failed\"",
            )
        }
        assertTerminalCompletionRejected(
            "identity before completion",
            identityAndCompletion,
            expectedMessage = "prototype SSE terminal receipt identity must match the run",
        )

        val rootDetailsDuplicate = completionBlocks(doneFrame) { frame ->
            duplicateRootDetails(frame, escapedCanonicalLast = false)
        }
        assertTerminalCompletionRejected(
            "duplicate root details before completion",
            rootDetailsDuplicate,
            expectedMessage = "prototype SSE terminal receipt identity must match the run",
        )
    }

    @Test
    fun terminalReceiptIdentityRequiresBothLayersToMatchRun() = runBlocking {
        val doneFrame = doneFrameForRun(readFixture("prototype_option_a_done_frame.sse"))
        val campaignKey = "\"campaign_id\":\"campaign-1\""
        val runKey = "\"run_id\":\"run-1\""
        val forgedCampaign = "\"campaign_id\":\"forged-campaign\""
        val forgedRun = "\"run_id\":\"forged-run\""
        val variants = listOf(
            "outer-only" to doneFrame
                .replaceFirst(campaignKey, forgedCampaign)
                .replaceFirst(runKey, forgedRun),
            "outer-campaign-only" to doneFrame.replaceFirst(campaignKey, forgedCampaign),
            "outer-run-only" to doneFrame.replaceFirst(runKey, forgedRun),
            "details-only" to replaceSecondOccurrence(
                replaceSecondOccurrence(doneFrame, campaignKey, forgedCampaign),
                runKey,
                forgedRun,
            ),
            "details-campaign-only" to replaceSecondOccurrence(doneFrame, campaignKey, forgedCampaign),
            "details-run-only" to replaceSecondOccurrence(doneFrame, runKey, forgedRun),
            "outer-and-details" to doneFrame
                .replace(campaignKey, forgedCampaign)
                .replace(runKey, forgedRun),
            "outer-and-details-campaign-only" to doneFrame.replace(campaignKey, forgedCampaign),
            "outer-and-details-run-only" to doneFrame.replace(runKey, forgedRun),
        )

        variants.forEach { (label, forgedDoneFrame) ->
            val transport = FakeRawPostTransport(
                rawStreamOf(canonicalBlocks(forgedDoneFrame, contentCount = 120)),
            )
            try {
                PrototypeRunStreamAdapter(transport).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
                )
                org.junit.Assert.fail("$label terminal identity was accepted")
            } catch (error: IllegalArgumentException) {
                assertEquals(
                    "prototype SSE terminal receipt identity must match the run",
                    error.message,
                )
            }
        }
    }

    @Test
    fun terminalReceiptDuplicateIdentityMemberFailsClosedWithStableMessage() = runBlocking {
        val doneFrame = doneFrameForRun(readFixture("prototype_option_a_done_frame.sse"))
        val campaignKey = "\"campaign_id\":\"campaign-1\""
        val runKey = "\"run_id\":\"run-1\""
        val duplicateVariants = listOf(
            "outer campaign" to doneFrame.replaceFirst(
                campaignKey,
                "\"campaign_id\":\"forged-campaign\",\"\\u0063ampaign_id\":\"campaign-1\"",
            ),
            "outer run" to doneFrame.replaceFirst(
                runKey,
                "\"run_id\":\"forged-run\",\"\\u0072un_id\":\"run-1\"",
            ),
            "details campaign" to replaceSecondOccurrence(
                doneFrame,
                campaignKey,
                "\"campaign_id\":\"forged-campaign\",\"\\u0063ampaign_id\":\"campaign-1\"",
            ),
            "details run" to replaceSecondOccurrence(
                doneFrame,
                runKey,
                "\"run_id\":\"forged-run\",\"\\u0072un_id\":\"run-1\"",
            ),
        )

        duplicateVariants.forEach { (label, duplicateDoneFrame) ->
            val transport = FakeRawPostTransport(
                rawStreamOf(canonicalBlocks(duplicateDoneFrame, contentCount = 120)),
            )
            try {
                PrototypeRunStreamAdapter(transport).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
                )
                org.junit.Assert.fail("$label duplicate terminal identity member was accepted")
            } catch (error: IllegalArgumentException) {
                assertEquals(
                    "prototype SSE terminal receipt identity must match the run",
                    error.message,
                )
            }
        }
    }

    @Test
    fun terminalReceiptDuplicateDetailsMemberFailsClosedWithStableMessage() = runBlocking {
        val doneFrame = doneFrameForRun(readFixture("prototype_option_a_done_frame.sse"))
        val forgedDetails = "{\"campaign_id\":\"forged-campaign\",\"run_id\":\"forged-run\"}"
        listOf(
            "literal" to (false to forgedDetails),
            "escaped" to (true to forgedDetails),
            "scalar-first" to (false to "7"),
        ).forEach { (keyKind, variant) ->
            val escapedCanonicalLast = variant.first
            val duplicateDoneFrame = duplicateRootDetails(
                doneFrame,
                escapedCanonicalLast = escapedCanonicalLast,
                firstDetails = variant.second,
            )
            val transport = FakeRawPostTransport(
                rawStreamOf(canonicalBlocks(duplicateDoneFrame, contentCount = 120)),
            )
            try {
                PrototypeRunStreamAdapter(transport).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
                )
                org.junit.Assert.fail(
                    "duplicate " + keyKind + " terminal details member was accepted",
                )
            } catch (error: IllegalArgumentException) {
                assertEquals(
                    "prototype SSE terminal receipt identity must match the run",
                    error.message,
                )
            }
        }
    }

    @Test
    fun terminalReceiptIdentityRejectsMissingNullAndNumberAtEitherLayer() = runBlocking {
        val doneFrame = doneFrameForRun(readFixture("prototype_option_a_done_frame.sse"))
        val campaignKey = "\"campaign_id\":\"campaign-1\""
        val runKey = "\"run_id\":\"run-1\""
        val campaignField = ",$campaignKey"
        val runField = ",$runKey"
        val variants = listOf(
            "outer campaign missing" to removeFirstOccurrence(doneFrame, campaignField),
            "outer campaign null" to doneFrame.replaceFirst(
                campaignKey,
                "\"campaign_id\":null",
            ),
            "outer campaign number" to doneFrame.replaceFirst(
                campaignKey,
                "\"campaign_id\":7",
            ),
            "outer run missing" to removeFirstOccurrence(doneFrame, runField),
            "outer run null" to doneFrame.replaceFirst(runKey, "\"run_id\":null"),
            "outer run number" to doneFrame.replaceFirst(runKey, "\"run_id\":7"),
            "details campaign missing" to removeSecondOccurrence(doneFrame, campaignField),
            "details campaign null" to replaceSecondOccurrence(
                doneFrame,
                campaignKey,
                "\"campaign_id\":null",
            ),
            "details campaign number" to replaceSecondOccurrence(
                doneFrame,
                campaignKey,
                "\"campaign_id\":7",
            ),
            "details run missing" to removeSecondOccurrence(doneFrame, runField),
            "details run null" to replaceSecondOccurrence(
                doneFrame,
                runKey,
                "\"run_id\":null",
            ),
            "details run number" to replaceSecondOccurrence(
                doneFrame,
                runKey,
                "\"run_id\":7",
            ),
        )

        variants.forEach { (label, invalidDoneFrame) ->
            val transport = FakeRawPostTransport(
                rawStreamOf(canonicalBlocks(invalidDoneFrame, contentCount = 120)),
            )
            try {
                PrototypeRunStreamAdapter(transport).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
                )
                org.junit.Assert.fail("$label terminal identity was accepted")
            } catch (error: IllegalArgumentException) {
                assertEquals(
                    "prototype SSE terminal receipt identity must match the run",
                    error.message,
                )
            }
        }
    }

    @Test
    fun terminalReceiptIdentityRejectsSpaceCaseAndNfkcNormalizationAtEitherLayer() = runBlocking {
        val doneFrame = doneFrameForRun(readFixture("prototype_option_a_done_frame.sse"))
        val campaignKey = "\"campaign_id\":\"campaign-1\""
        val runKey = "\"run_id\":\"run-1\""
        val variants = listOf(
            "outer campaign space" to doneFrame.replaceFirst(
                campaignKey,
                "\"campaign_id\":\"campaign-1 " + "\"",
            ),
            "outer campaign case" to doneFrame.replaceFirst(
                campaignKey,
                "\"campaign_id\":\"CAMPAIGN-1\"",
            ),
            "outer campaign nfkc" to doneFrame.replaceFirst(
                campaignKey,
                "\"campaign_id\":\"\\uFF43ampaign-1\"",
            ),
            "outer run space" to doneFrame.replaceFirst(
                runKey,
                "\"run_id\":\" run-1\"",
            ),
            "outer run case" to doneFrame.replaceFirst(
                runKey,
                "\"run_id\":\"RUN-1\"",
            ),
            "outer run nfkc" to doneFrame.replaceFirst(
                runKey,
                "\"run_id\":\"run-\\uFF11\"",
            ),
            "details campaign space" to replaceSecondOccurrence(
                doneFrame,
                campaignKey,
                "\"campaign_id\":\"campaign-1 " + "\"",
            ),
            "details campaign case" to replaceSecondOccurrence(
                doneFrame,
                campaignKey,
                "\"campaign_id\":\"CAMPAIGN-1\"",
            ),
            "details campaign nfkc" to replaceSecondOccurrence(
                doneFrame,
                campaignKey,
                "\"campaign_id\":\"\\uFF43ampaign-1\"",
            ),
            "details run space" to replaceSecondOccurrence(
                doneFrame,
                runKey,
                "\"run_id\":\"run-1 " + "\"",
            ),
            "details run case" to replaceSecondOccurrence(
                doneFrame,
                runKey,
                "\"run_id\":\"RUN-1\"",
            ),
            "details run nfkc" to replaceSecondOccurrence(
                doneFrame,
                runKey,
                "\"run_id\":\"run-\\uFF11\"",
            ),
        )

        variants.forEach { (label, invalidDoneFrame) ->
            val transport = FakeRawPostTransport(
                rawStreamOf(canonicalBlocks(invalidDoneFrame, contentCount = 120)),
            )
            try {
                PrototypeRunStreamAdapter(transport).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
                )
                org.junit.Assert.fail("$label terminal identity was accepted")
            } catch (error: IllegalArgumentException) {
                assertEquals(
                    "prototype SSE terminal receipt identity must match the run",
                    error.message,
                )
            }
        }
    }

    @Test
    fun terminalReceiptIdentityAcceptsEscapedKeysAndReorderedMembers() = runBlocking {
        val doneFrame = doneFrameForRun(readFixture("prototype_option_a_done_frame.sse"))
        val campaignKey = "\"campaign_id\":\"campaign-1\""
        val runKey = "\"run_id\":\"run-1\""
        val escapedCampaignDoneFrame = doneFrame.replaceFirst(
            campaignKey,
            "\"\\u0063ampaign_id\":\"campaign-1\"",
        )
        val reorderedDoneFrame = doneFrame.replaceFirst(
            "$campaignKey,\"run_id\":\"run-1\"",
            "\"run_id\":\"run-1\",$campaignKey",
        )
        val escapedKeysBothLayers = doneFrame
            .replace("\"campaign_id\"", "\"\\u0063ampaign_id\"")
            .replace("\"run_id\"", "\"\\u0072un_id\"")
        val reorderedKeysBothLayers = doneFrame
            .replaceFirst(
                "$campaignKey,\"run_id\":\"run-1\"",
                "\"run_id\":\"run-1\",$campaignKey",
            )
            .replaceFirst(
                "$campaignKey,\"run_id\":\"run-1\"",
                "\"run_id\":\"run-1\",$campaignKey",
            )
        val equivalentEscapedValues = doneFrame
            .replace("\"campaign-1\"", "\"campaign-\\u0031\"")
            .replace("\"run-1\"", "\"run-\\u0031\"")
        val detailsBeforeOuterIdentity = run {
            val frame = doneFrame.removeSuffix("\n\n")
            val payload = frame.substringAfter("data: ")
            val detailsMarker = ",\"details\":"
            val detailsIndex = payload.indexOf(detailsMarker)
            require(detailsIndex >= 0)
            val rootMembers = payload.substring(1, detailsIndex)
            val detailsMember = payload.substring(detailsIndex + 1, payload.length - 1)
            val nestedStart = detailsMember.indexOf('{')
            val nestedBody = detailsMember.substring(nestedStart + 1, detailsMember.length - 1)
            val nestedIdentity = "$campaignKey,$runKey,"
            val nestedWithoutIdentity = nestedBody.replace(nestedIdentity, "")
            val reorderedDetailsMember = detailsMember.substring(0, nestedStart + 1) +
                nestedWithoutIdentity + ",$campaignKey,$runKey}"
            val rootWithoutIdentity = rootMembers
                .replace("$campaignKey,", "")
                .replace("$runKey,", "")
            val reorderedPayload = "{$reorderedDetailsMember,$rootWithoutIdentity,$campaignKey,$runKey}"
            frame.substringBefore("data: ") + "data: " + reorderedPayload
        }
        val escapedRootDetailsOnly = doneFrame.replaceFirst(
            ",\"details\":",
            ",\"\\u0064etails\":",
        )

        listOf(
            escapedCampaignDoneFrame,
            reorderedDoneFrame,
            escapedKeysBothLayers,
            reorderedKeysBothLayers,
            equivalentEscapedValues,
            detailsBeforeOuterIdentity,
            escapedRootDetailsOnly,
        ).forEach { equivalentDoneFrame ->
            PrototypeRunStreamAdapter(FakeRawPostTransport(
                rawStreamOf(canonicalBlocks(equivalentDoneFrame, contentCount = 120)),
            )).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = canonicalRequestBody(),
            )
        }
    }

    @Test
    fun terminalIdentityErrorsFollowArrivalChronology() = runBlocking {
        val doneFrame = doneFrameForRun(readFixture("prototype_option_a_done_frame.sse"))
            .replaceFirst(
                "\"campaign_id\":\"campaign-1\"",
                "\"campaign_id\":\"forged-campaign\"",
            )
        val blocks = canonicalBlocks(doneFrame, contentCount = 120)
        val arrivals = blocks.indices.map { (it + 1) * 1_000L }.toMutableList()
        arrivals[42] = arrivals[41] - 1L

        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(
                rawStreamWithArrivals(blocks, arrivals),
            )).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("terminal identity was checked before chronology")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content arrival timestamps must be non-negative and nondecreasing",
                error.message,
            )
        }
    }

    @Test
    fun terminalDuplicateDetailsErrorsFollowArrivalChronology() = runBlocking {
        val doneFrame = duplicateRootDetails(
            doneFrameForRun(readFixture("prototype_option_a_done_frame.sse")),
            escapedCanonicalLast = false,
        )
        val blocks = canonicalBlocks(doneFrame, contentCount = 120)
        val arrivals = blocks.indices.map { (it + 1) * 1_000L }.toMutableList()
        arrivals[42] = arrivals[41] - 1L

        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(
                rawStreamWithArrivals(blocks, arrivals),
            )).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("terminal duplicate details was checked before chronology")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content arrival timestamps must be non-negative and nondecreasing",
                error.message,
            )
        }
    }

    @Test
    fun runStartedMissingIdentityCannotAuthorizeContentRun() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120).mapIndexed { index, block ->
            if (index == 0) {
                "event: run_started\ndata: {\"event_type\":\"run_started\"}"
            } else if (index in 1..120) {
                block
                    .replace("\"campaign_id\":\"campaign-1\"", "\"campaign_id\":\"forged-campaign\"")
                    .replace("\"run_id\":\"run-1\"", "\"run_id\":\"forged-run\"")
            } else {
                block
            }
        }
        val transport = FakeRawPostTransport(rawStreamOf(blocks))

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("content identity was accepted without run_started authority")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content event identity must match the run",
                error.message,
            )
        }
    }

    @Test
    fun duplicateCampaignIdentityMemberFailsClosedWithStableMessage() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        val original = blocks[1]
        blocks[1] = original.replace(
            "\"campaign_id\":\"campaign-1\",\"run_id\"",
            "\"campaign_id\":\"forged-campaign\",\"\\u0063ampaign_id\":\"campaign-1\",\"run_id\"",
        )
        require(blocks[1] != original)

        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("duplicate campaign_id member was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content event identity must match the run",
                error.message,
            )
        }
    }

    @Test
    fun duplicateRunIdentityMemberFailsClosedWithStableMessage() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        val original = blocks[1]
        blocks[1] = original.replace(
            "\"run_id\":\"run-1\",\"condition_id\"",
            "\"run_id\":\"forged-run\",\"\\u0072un_id\":\"run-1\",\"condition_id\"",
        )
        require(blocks[1] != original)

        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("duplicate run_id member was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content event identity must match the run",
                error.message,
            )
        }
    }

    @Test
    fun runStartedIdentityRejectsPartialOrNonStringPair() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val cases = listOf(
            "missing campaign_id" to "{\"event_type\":\"run_started\",\"run_id\":\"run-1\"}",
            "missing run_id" to "{\"event_type\":\"run_started\",\"campaign_id\":\"campaign-1\"}",
            "numeric campaign_id" to "{\"event_type\":\"run_started\",\"campaign_id\":1,\"run_id\":\"run-1\"}",
            "null run_id" to "{\"event_type\":\"run_started\",\"campaign_id\":\"campaign-1\",\"run_id\":null}",
        )
        val accepted = mutableListOf<String>()
        val wrongMessages = mutableListOf<String>()
        cases.forEach { (name, payload) ->
            val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
            blocks[0] = "event: run_started\ndata: $payload"
            try {
                PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = canonicalRequestBody(),
                )
                accepted += name
            } catch (error: IllegalArgumentException) {
                if (error.message != "prototype SSE content event identity must match the run") {
                    wrongMessages += "$name -> ${error.message}"
                }
            }
        }
        assertTrue(
            "accepted=$accepted; wrongMessages=$wrongMessages",
            accepted.isEmpty() && wrongMessages.isEmpty(),
        )
    }

    @Test
    fun contentIdentityRejectsMissingOrNonStringPair() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val canonical = serverContentBlock(1)
        val cases = listOf(
            "missing campaign_id" to canonical.replace(
                "\"campaign_id\":\"campaign-1\",",
                "",
            ),
            "missing run_id" to canonical.replace(
                "\"run_id\":\"run-1\",",
                "",
            ),
            "numeric campaign_id" to canonical.replace(
                "\"campaign_id\":\"campaign-1\"",
                "\"campaign_id\":1",
            ),
            "null run_id" to canonical.replace(
                "\"run_id\":\"run-1\"",
                "\"run_id\":null",
            ),
        )
        val accepted = mutableListOf<String>()
        val wrongMessages = mutableListOf<String>()
        cases.forEach { (name, block) ->
            val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
            blocks[1] = block
            try {
                PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
                )
                accepted += name
            } catch (error: IllegalArgumentException) {
                if (error.message != "prototype SSE content event identity must match the run") {
                    wrongMessages += "$name -> ${error.message}"
                }
            }
        }
        assertTrue(
            "accepted=$accepted; wrongMessages=$wrongMessages",
            accepted.isEmpty() && wrongMessages.isEmpty(),
        )
    }

    @Test
    fun contentArrivalTimestampRegressionFailsClosedWithStableMessage() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120)
        val arrivals = blocks.indices.map { (it + 1) * 1_000L }.toMutableList()
        arrivals[42] = arrivals[41] - 1L

        try {
            PrototypeRunStreamAdapter(
                FakeRawPostTransport(rawStreamWithArrivals(blocks, arrivals)),
            ).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("content arrival timestamp regression was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content arrival timestamps must be non-negative and nondecreasing",
                error.message,
            )
        }
    }

    @Test
    fun contentArrivalTimestampAllowsZeroAndEqualAdjacentValues() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120)
        val arrivals = blocks.indices.map { (it + 1) * 1_000L }.toMutableList()
        arrivals[1] = 0L
        arrivals[2] = 0L

        val result = PrototypeRunStreamAdapter(
            FakeRawPostTransport(rawStreamWithArrivals(blocks, arrivals)),
        ).run(
            endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
            requestBody = canonicalRequestBody(),
        )

        assertEquals(0L, result.rawEvents[1].arrivalNanos)
        assertEquals(0L, result.rawEvents[2].arrivalNanos)
    }

    @Test
    fun negativeContentArrivalTimestampFailsWithStableMessage() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120)
        val arrivals = blocks.indices.map { (it + 1) * 1_000L }.toMutableList()
        arrivals[42] = -1L

        val message = runCatching {
            PrototypeRunStreamAdapter(
                FakeRawPostTransport(rawStreamWithArrivals(blocks, arrivals)),
            ).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = canonicalRequestBody(),
            )
        }.exceptionOrNull()?.message

        assertEquals(
            "prototype SSE content arrival timestamps must be non-negative and nondecreasing",
            message,
        )
    }

    @Test
    fun contentSemanticErrorsPrecedeArrivalChronology() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val baseArrivals = canonicalBlocks(doneFrame, contentCount = 120)
            .indices
            .map { (it + 1) * 1_000L }
        val sequenceBlocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        sequenceBlocks[1] = serverContentBlock(2)
        val sequenceArrivals = baseArrivals.toMutableList().also {
            it[42] = it[41] - 1L
        }
        val sequenceMessage = runCatching {
            PrototypeRunStreamAdapter(
                FakeRawPostTransport(rawStreamWithArrivals(sequenceBlocks, sequenceArrivals)),
            ).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
        }.exceptionOrNull()?.message
        assertEquals(
            "prototype SSE content events must have exact seq 1 through 120",
            sequenceMessage,
        )

        val identityBlocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        identityBlocks[1] = serverContentBlock(1).replace(
            "\"campaign_id\":\"campaign-1\"",
            "\"campaign_id\":\"campaign-mismatch\"",
        )
        val identityArrivals = baseArrivals.toMutableList().also {
            it[42] = it[41] - 1L
        }
        val identityMessage = runCatching {
            PrototypeRunStreamAdapter(
                FakeRawPostTransport(rawStreamWithArrivals(identityBlocks, identityArrivals)),
            ).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
        }.exceptionOrNull()?.message
        assertEquals(
            "prototype SSE content event identity must match the run",
            identityMessage,
        )
    }

    @Test
    fun sequenceDuplicatePrecedesIdentityDuplicateWhenIdentityKeyAppearsFirst() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        blocks[1] =
            "event: content_event\ndata: " +
                "{\"campaign_id\":\"forged-campaign\",\"campaign_id\":\"campaign-1\",\"" +
                "run_id\":\"run-1\",\"event_type\":\"content_event\",\"details\":{" +
                "seq\":999,\"seq\":1}}"

        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("identity duplicate ahead of sequence duplicate was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content events must have exact seq 1 through 120",
                error.message,
            )
        }
    }

    @Test
    fun sequenceDuplicatePrecedesIdentityDuplicateWhenSequenceKeyAppearsFirst() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        blocks[1] =
            "event: content_event\ndata: " +
                "{\"run_id\":\"run-1\",\"event_type\":\"content_event\",\"details\":{" +
                "seq\":999,\"seq\":1},\"campaign_id\":\"forged-campaign\",\"" +
                "campaign_id\":\"campaign-1\"}"

        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("sequence duplicate was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content events must have exact seq 1 through 120",
                error.message,
            )
        }
    }

    @Test
    fun deeplyNestedContentPayloadFailsWithStableSequenceMessage() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        val nestedSeq = buildString {
            repeat(4_000) { append('[') }
            append('1')
            repeat(4_000) { append(']') }
        }
        blocks[1] = serverContentBlock(1).replace(
            "\"seq\":1,",
            "\"seq\":$nestedSeq,",
        )
        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("deeply nested content payload was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content events must have exact seq 1 through 120",
                error.message,
            )
        }
    }

    @Test
    fun contentEventWithThirdDataLineFailsClosed() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        val contentIndex = 1 + 4
        blocks[contentIndex] = blocks[contentIndex] +
            "\ndata: {\"event_type\":\"content_event\",\"details\":{\"seq\":999}}"
        val transport = FakeRawPostTransport(rawStreamOf(blocks))

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("content event with a third data line was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content events must have exact seq 1 through 120",
                error.message,
            )
        }
    }

    @Test
    fun contentSequenceRejectsNonCanonicalIntegerLexemes() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val acceptedTokens = mutableListOf<String>()

        listOf("1.0", "1e0").forEach { token ->
            val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
            val firstContent = blocks[1]
            blocks[1] = firstContent.replace(
                "\"seq\":1,\"planned_offset_ms\"",
                "\"seq\":$token,\"planned_offset_ms\"",
            )
            require(blocks[1] != firstContent)

            try {
                PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = canonicalRequestBody(),
                )
                acceptedTokens += token
            } catch (error: IllegalArgumentException) {
                assertEquals(
                    "prototype SSE content events must have exact seq 1 through 120",
                    error.message,
                )
            }
        }

        assertTrue(
            "non-canonical seq tokens were accepted: $acceptedTokens",
            acceptedTokens.isEmpty(),
        )
    }

    @Test
    fun contentSequenceMustRespectReceivedOrder() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        val content40Index = 40
        val content41Index = 41
        val content40 = blocks[content40Index]
        blocks[content40Index] = blocks[content41Index]
        blocks[content41Index] = content40
        val transport = FakeRawPostTransport(rawStreamOf(blocks))

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("out-of-order content sequence was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content events must have exact seq 1 through 120",
                error.message,
            )
        }
    }

    @Test
    fun topologyPrecedesContentSequenceValidation() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        blocks[1] = blocks[1].replaceFirst("event: content_event", "event: forged")
        blocks[2] = blocks[2].replace(
            "\"seq\":2,\"planned_offset_ms\"",
            "\"seq\":1,\"planned_offset_ms\"",
        )
        val transport = FakeRawPostTransport(rawStreamOf(blocks))

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("invalid topology was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE stream must contain run_started, 120 content events, and final done",
                error.message,
            )
        }
    }

    @Test
    fun contentDataShapeAndTypesFailClosedWithStableSequenceMessage() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val cases = listOf(
            "empty payload" to "",
            "root null" to "null",
            "root array" to "[]",
            "event_type missing" to "{\"details\":{\"seq\":1}}",
            "event_type null" to "{\"event_type\":null,\"details\":{\"seq\":1}}",
            "event_type array" to "{\"event_type\":[],\"details\":{\"seq\":1}}",
            "details missing" to "{\"event_type\":\"content_event\"}",
            "details null" to "{\"event_type\":\"content_event\",\"details\":null}",
            "details array" to "{\"event_type\":\"content_event\",\"details\":[]}",
            "seq missing" to "{\"event_type\":\"content_event\",\"details\":{}}",
            "seq null" to "{\"event_type\":\"content_event\",\"details\":{\"seq\":null}}",
            "seq string" to "{\"event_type\":\"content_event\",\"details\":{\"seq\":\"1\"}}",
            "seq bool" to "{\"event_type\":\"content_event\",\"details\":{\"seq\":true}}",
            "seq array" to "{\"event_type\":\"content_event\",\"details\":{\"seq\":[1]}}",
        )
        val accepted = mutableListOf<String>()
        val wrongMessages = mutableListOf<String>()
        val unexpectedErrors = mutableListOf<String>()
        val stableMessage = "prototype SSE content events must have exact seq 1 through 120"

        cases.forEach { (name, data) ->
            val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
            blocks[1] = "event: content_event\ndata: ${contentPayloadWithIdentity(data)}"
            try {
                PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
                )
                accepted += name
            } catch (error: IllegalArgumentException) {
                if (error.message != stableMessage) {
                    wrongMessages += "$name -> ${error.message}"
                }
            } catch (error: Throwable) {
                unexpectedErrors += "$name -> ${error::class.simpleName}: ${error.message}"
            }
        }

        assertTrue(
            "accepted=$accepted; wrongMessages=$wrongMessages; unexpected=$unexpectedErrors",
            accepted.isEmpty() && wrongMessages.isEmpty() && unexpectedErrors.isEmpty(),
        )
    }

    @Test
    fun duplicateSeqMemberFailsClosedWithStableSequenceMessage() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val stableMessage = "prototype SSE content events must have exact seq 1 through 120"
        val acceptedRed = mutableListOf<String>()
        val wrongMessages = mutableListOf<String>()
        val duplicateBlocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        duplicateBlocks[1] = duplicateBlocks[1].replace(
            "\"seq\":1,\"planned_offset_ms\"",
            "\"seq\":999,\"seq\":1,\"planned_offset_ms\"",
        )
        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(duplicateBlocks))).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            acceptedRed += "literal duplicate seq"
        } catch (error: IllegalArgumentException) {
            if (error.message != stableMessage) {
                wrongMessages += "literal duplicate seq -> ${error.message}"
            }
        }

        val duplicateDetailsBlocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        duplicateDetailsBlocks[1] =
            "event: content_event\ndata: " +
                contentPayloadWithIdentity(
                    "{\"event_type\":\"content_event\",\"details\":{\"seq\":999}," +
                        "\"details\":{\"seq\":1}}",
                )
        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(duplicateDetailsBlocks))).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            acceptedRed += "duplicate details"
        } catch (error: IllegalArgumentException) {
            if (error.message != stableMessage) {
                wrongMessages += "duplicate details -> ${error.message}"
            }
        }

        val escapedSeqBlocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        escapedSeqBlocks[1] =
            "event: content_event\ndata: " +
                contentPayloadWithIdentity(
                    "{\"event_type\":\"content_event\",\"details\":{\"seq\":999," +
                        "\"\\u0073eq\":1}}",
                )
        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(escapedSeqBlocks))).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            acceptedRed += "escaped duplicate seq"
        } catch (error: IllegalArgumentException) {
            if (error.message != stableMessage) {
                wrongMessages += "escaped duplicate seq -> ${error.message}"
            }
        }

        val reverseControlBlocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        reverseControlBlocks[1] = reverseControlBlocks[1].replace(
            "\"seq\":1,\"planned_offset_ms\"",
            "\"seq\":1,\"seq\":999,\"planned_offset_ms\"",
        )
        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(reverseControlBlocks))).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("reverse duplicate seq control was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(stableMessage, error.message)
        }

        assertTrue(
            "acceptedRed=$acceptedRed; wrongMessages=$wrongMessages",
            acceptedRed.isEmpty() && wrongMessages.isEmpty(),
        )
    }

    @Test
    fun duplicateKeyBoundaryPreservesDistinctAndEscapedKeys() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val stableMessage = "prototype SSE content events must have exact seq 1 through 120"
        val invalidBlocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        invalidBlocks[1] =
            "event: content_event\ndata: " +
                contentPayloadWithIdentity(
                    "{\"details\":{\"seq\":999},\"det\\u0061ils\":{\"seq\":1}," +
                        "\"event_type\":\"content_event\"}",
                )
        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(invalidBlocks))).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("escaped duplicate details member was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(stableMessage, error.message)
        }

        val accepted = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        val bracketText = buildString {
            repeat(256) { append('[') }
            repeat(256) { append(']') }
        }
        val escapedBracketText = "\\\"quote\\\" and \\\\ [$bracketText]"
        val validCases = listOf(
            "single escaped seq" to
                "{\"event_type\":\"content_event\",\"details\":{\"\\u0073eq\":1}}",
            "reordered distinct extras" to
                "{\"extra_root\":{\"v\":1},\"details\":{\"extra_nested\":[1,2],\"seq\":1}," +
                    "\"event_type\":\"content_event\"}",
            "text containing seq and details" to
                "{\"event_type\":\"content_event\",\"details\":{\"seq\":1," +
                    "\"note\":\"seq details\"}}",
            "string brackets" to
                "{\"event_type\":\"content_event\",\"details\":{\"seq\":1," +
                    "\"note\":\"$bracketText\"}}",
            "escaped quote backslash brackets" to
                "{\"event_type\":\"content_event\",\"details\":{\"seq\":1," +
                    "\"note\":\"$escapedBracketText\"}}",
            "duplicate unknown root" to
                "{\"noise\":{\"v\":1},\"noise\":{\"v\":2}," +
                    "\"event_type\":\"content_event\",\"details\":{\"seq\":1}}",
        )
        validCases.forEach { (name, data) ->
            val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
            blocks[1] = "event: content_event\ndata: ${contentPayloadWithIdentity(data)}"
            try {
                PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = canonicalRequestBody(),
                )
                accepted += name
            } catch (error: IllegalArgumentException) {
                rejected += "$name -> ${error.message}"
            } catch (error: Throwable) {
                rejected += "$name -> ${error::class.simpleName}: ${error.message}"
            }
        }

        assertTrue(
            "accepted=$accepted; rejected=$rejected",
            accepted == validCases.map { it.first } && rejected.isEmpty(),
        )

        val duplicateClaimedFields = listOf(
            "seq" to "\"seq\":1,\"seq\":1",
            "planned offset" to "\"seq\":1,\"planned_offset_ms\":0,\"planned_offset_ms\":0",
            "payload id" to "\"seq\":1,\"payload_id\":\"payload-1\",\"payload_id\":\"payload-1\"",
            "profile manifest" to
                "\"seq\":1,\"profile_manifest_sha256\":\"manifest\"," +
                    "\"profile_manifest_sha256\":\"manifest\"",
            "schedule hash" to
                "\"seq\":1,\"schedule_hash\":\"schedule\",\"schedule_hash\":\"schedule\"",
        )
        val duplicateAccepted = mutableListOf<String>()
        val duplicateWrongErrors = mutableListOf<String>()
        duplicateClaimedFields.forEach { (name, details) ->
            val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
            blocks[1] =
                "event: content_event\ndata: " +
                    contentPayloadWithIdentity("{\"event_type\":\"content_event\",\"details\":{$details}}")
            try {
                PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = canonicalRequestBody(),
                )
                duplicateAccepted += name
            } catch (error: IllegalArgumentException) {
                if (error.message != stableMessage) {
                    duplicateWrongErrors += "$name -> ${error.message}"
                }
            } catch (error: Throwable) {
                duplicateWrongErrors += "$name -> ${error::class.simpleName}: ${error.message}"
            }
        }
        assertTrue(
            "duplicateAccepted=$duplicateAccepted; duplicateWrongErrors=$duplicateWrongErrors",
            duplicateAccepted.isEmpty() && duplicateWrongErrors.isEmpty(),
        )
    }

    @Test
    fun truncatedPostStreamFailsClosedBeforeTerminalDecode() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 1).dropLast(1)
        val rawStream = rawStreamWithArrivals(
            blocks = blocks,
            arrivals = listOf(1_000L, 2_000L),
            truncatedTail = true,
        )
        var postCalls = 0
        val transport = object : PrototypeRawPostTransport {
            override suspend fun post(url: String, requestBody: String): RawSseStream {
                postCalls += 1
                return rawStream
            }
        }

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = canonicalRequestBody(),
            )
            org.junit.Assert.fail("truncated stream was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals("prototype SSE stream has a truncated tail", error.message)
        }
        assertEquals(1, postCalls)
    }

    @Test
    fun interruptedPrefixContentErrorPrecedesOutgoingRequestIdentity() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 1).dropLast(1).toMutableList()
        blocks[1] = serverContentBlock(seq = 2)
        val transport = FakeRawPostTransport(rawStreamOf(blocks))
        val mismatchedRequest =
            "{\"campaign_id\":\"campaign-other\",\"run_id\":\"run-1\"," +
                "\"condition_id\":\"baseline_v0.1\"}"

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = mismatchedRequest,
            )
            org.junit.Assert.fail("invalid interrupted prefix was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content events must have exact seq 1 through 120",
                error.message,
            )
        }
    }

    @Test
    fun duplicateDonePostStreamFailsClosedBeforeTerminalDecode() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val streamText = buildString {
            append("event: run_started\ndata: {\"event_type\":\"run_started\"}\n\n")
            append("event: content_event\ndata: {\"event_type\":\"content_event\"}\n\n")
            append(doneFrame)
            append(doneFrame)
        }
        val blocks = streamText.split("\n\n").filter(String::isNotBlank)
        val arrivals = listOf(1_000L, 2_000L, 3_000L, 4_000L)
        val rawStream = RawSseStream(
            events = blocks.mapIndexed { index, block ->
                RawSseEvent(
                    bytes = block.toByteArray(Charsets.UTF_8),
                    arrivalNanos = arrivals[index],
                    sameReadBatch = false,
                )
            },
            readCount = blocks.size,
            totalBytes = streamText.toByteArray(Charsets.UTF_8).size.toLong(),
            truncatedTail = false,
            eofNanos = 5_000L,
        )
        val transport = FakeRawPostTransport(rawStream)

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("duplicate final done stream was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE stream must contain exactly one final done event",
                error.message,
            )
        }
    }

    @Test
    fun emptyPostStreamFailsClosedBeforeInterruptedEvidence() = runBlocking {
        val transport = FakeRawPostTransport(rawStreamOf(emptyList()))

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("empty stream was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content events must have exact seq 1 through 120",
                error.message,
            )
        }
    }

    @Test
    fun canonicalPrefixWithoutDoneReturnsMissingTerminalInterruption() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val rawStream = rawStreamOf(canonicalBlocks(doneFrame, contentCount = 1).dropLast(1))
        val transport = FakeRawPostTransport(rawStream)

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = canonicalRequestBody(),
            )
            org.junit.Assert.fail("stream without done was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE stream ended without a terminal done event",
                error.message,
            )
        }
    }

    @Test
    fun canonicalDoneFollowedByContentIsRejectedByTerminalDecoder() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val content = "event: content_event\ndata: {\"event_type\":\"content_event\"}"
        val rawStream = rawStreamOf(carrierBlocks() + doneFrame.removeSuffix("\n\n") + content)
        val transport = FakeRawPostTransport(rawStream)

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("content after done was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals("done SSE event line must be exactly 'event: done'", error.message)
        }
    }

    @Test
    fun malformedFinalDoneIsRejectedByTerminalDecoder() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val malformedDone = doneFrame.replace(
            "\"event_type\":\"terminal_event\"",
            "\"event_type\":\"content_event\"",
        )
        val transport = FakeRawPostTransport(rawStreamOf(listOf(malformedDone.removeSuffix("\n\n"))))

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("malformed final done was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals("done SSE event_type must be exactly terminal_event", error.message)
        }
    }

    @Test
    fun postTransportIOExceptionIsPropagatedUnchanged() = runBlocking {
        val failure = IOException("prototype transport failed")
        var postCalls = 0
        val transport = object : PrototypeRawPostTransport {
            override suspend fun post(url: String, requestBody: String): RawSseStream {
                postCalls += 1
                throw failure
            }
        }

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("IOException was swallowed")
        } catch (error: IOException) {
            assertSame(failure, error)
        }
        assertEquals(1, postCalls)
    }

    @Test
    fun postTransportCancellationIsPropagatedUnchanged() = runBlocking {
        val failure = CancellationException("prototype transport cancelled")
        var postCalls = 0
        val transport = object : PrototypeRawPostTransport {
            override suspend fun post(url: String, requestBody: String): RawSseStream {
                postCalls += 1
                throw failure
            }
        }

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("CancellationException was swallowed")
        } catch (error: CancellationException) {
            assertSame(failure, error)
        }
        assertEquals(1, postCalls)
    }

    @Test
    fun validObservedPrefixIOExceptionBecomesInterruptedWithCauseAndClockSample() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val prefix = rawStreamOf(canonicalBlocks(doneFrame, contentCount = 1).dropLast(1)).events
        val failure = IOException("forced observed-prefix interruption")
        val clock = RecordingTestClock()
        val adapter = com.aneb.probe.prototype.PrototypeRunStreamAdapter(
            transport = failingObservedTransport(prefix, failure),
            clock = clock,
        )

        try {
            adapter.run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = canonicalRequestBody(),
            )
            org.junit.Assert.fail("observed-prefix IOException was not converted to interruption")
        } catch (error: PrototypeRunStreamInterruptedException) {
            assertSame(failure, error.cause)
            assertEquals(2, error.evidence.rawEvents.size)
            prefix.indices.forEach { index ->
                assertSame(prefix[index], error.evidence.rawEvents[index])
            }
            assertEquals(1, error.evidence.validatedContentEvents.size)
            assertEquals(clock.samples.last(), error.evidence.interruptionClientMonotonicNanos)
            assertTrue(
                error.evidence.interruptionClientMonotonicNanos >
                    error.evidence.validatedContentEvents.single().clientMonotonicNanos,
            )
        }
        assertEquals(3, clock.samples.size)
    }

    @Test
    fun malformedObservedPrefixIOExceptionPreservesValidationErrors() = runBlocking {
        data class Case(
            val label: String,
            val blocks: List<String>,
            val requestBody: String,
            val expectedMessage: String,
        )

        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val canonicalPrefix = canonicalBlocks(doneFrame, contentCount = 1).dropLast(1)
        val canonicalContent = canonicalPrefix[1]
        val cases = listOf(
            Case(
                label = "content sequence",
                blocks = listOf(canonicalPrefix[0], serverContentBlock(seq = 2)),
                requestBody = canonicalRequestBody(),
                expectedMessage = "prototype SSE content events must have exact seq 1 through 120",
            ),
            Case(
                label = "content run identity",
                blocks = listOf(
                    canonicalPrefix[0],
                    canonicalContent.replaceFirst(
                        "\"campaign_id\":\"campaign-1\"",
                        "\"campaign_id\":\"campaign-other\"",
                    ),
                ),
                requestBody = canonicalRequestBody(),
                expectedMessage = "prototype SSE content event identity must match the run",
            ),
            Case(
                label = "outgoing request run identity",
                blocks = canonicalPrefix,
                requestBody =
                    "{\"campaign_id\":\"campaign-other\",\"run_id\":\"run-1\"," +
                        "\"condition_id\":\"baseline_v0.1\"}",
                expectedMessage = REQUEST_RUN_IDENTITY_ERROR,
            ),
            Case(
                label = "condition identity",
                blocks = listOf(
                    canonicalPrefix[0],
                    canonicalContent.replaceFirst(
                        "\"condition_id\":\"baseline_v0.1\"",
                        "\"condition_id\":\"slow_v0.1\"",
                    ),
                ),
                requestBody = canonicalRequestBody(),
                expectedMessage = CONDITION_IDENTITY_ERROR,
            ),
        )

        cases.forEach { case ->
            val prefix = rawStreamOf(case.blocks).events
            try {
                com.aneb.probe.prototype.PrototypeRunStreamAdapter(
                    transport = failingObservedTransport(
                        prefix,
                        IOException("forced ${case.label} interruption"),
                    ),
                    clock = IncrementingTestClock(),
                ).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = case.requestBody,
                )
                org.junit.Assert.fail("${case.label} prefix was repaired as interrupted")
            } catch (error: IllegalArgumentException) {
                assertTrue(
                    "${case.label} was converted to typed interruption",
                    error !is PrototypeRunStreamInterruptedException,
                )
                assertEquals(case.label, case.expectedMessage, error.message)
            }
        }
    }

    @Test
    fun validObservedPrefixCancellationIsPropagatedUnchanged() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val prefix = rawStreamOf(canonicalBlocks(doneFrame, contentCount = 1).dropLast(1)).events
        val failure = CancellationException("forced observed-prefix cancellation")

        try {
            com.aneb.probe.prototype.PrototypeRunStreamAdapter(
                transport = failingObservedTransport(prefix, failure),
                clock = IncrementingTestClock(),
            ).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = canonicalRequestBody(),
            )
            org.junit.Assert.fail("observed-prefix CancellationException was swallowed")
        } catch (error: CancellationException) {
            assertSame(failure, error)
        }
    }

    /** Keeps local JVM tests off the Android SystemClock while exercising production logic. */
    private class PrototypeRunStreamAdapter(
        transport: PrototypeRawPostTransport,
    ) {
        private val delegate = com.aneb.probe.prototype.PrototypeRunStreamAdapter(
            transport = transport,
            clock = IncrementingTestClock(),
        )

        suspend fun run(endpoint: String, requestBody: String): PrototypeRunStreamResult =
            delegate.run(endpoint, requestBody)
    }

    private class IncrementingTestClock : MonotonicNanosClock {
        private var nextNanos = 1L

        override fun now(): Long = nextNanos++
    }

    private class RecordingTestClock : MonotonicNanosClock {
        private var nextNanos = 1_000L
        val samples = mutableListOf<Long>()

        override fun now(): Long = nextNanos.also { sample ->
            samples += sample
            nextNanos += 10L
        }
    }

    private class FakeRawPostTransport(
        private val response: RawSseStream,
    ) : PrototypeRawPostTransport {
        var callCount = 0
        var postedUrl: String? = null
        var postedBody: String? = null

        override suspend fun post(url: String, requestBody: String): RawSseStream {
            callCount += 1
            postedUrl = url
            postedBody = requestBody
            return response
        }
    }

    private fun failingObservedTransport(
        prefix: List<RawSseEvent>,
        failure: Throwable,
    ): PrototypeRawPostTransport = object : PrototypeRawPostTransport {
        override suspend fun post(url: String, requestBody: String): RawSseStream =
            error("observed transport path required")

        override suspend fun postObserved(
            url: String,
            requestBody: String,
            observer: PrototypeRawPostObserver,
        ): RawSseStream {
            observer.beforeDispatch()
            prefix.forEach(observer.onRawEvent)
            throw failure
        }
    }

    private fun carrierBlocks(): List<String> = listOf(
        "event: run_started\ndata: {\"event_type\":\"run_started\"}",
        "event: content_event\ndata: {\"event_type\":\"content_event\"}",
    )

    private fun runStartedBlock(payload: String): String =
        "event: run_started\ndata: $payload"

    private data class OfficialProducerCase(
        val label: String,
        val campaignId: String,
        val runId: String,
        val runIndex: Int,
        val campaignMode: String,
        val conditionId: String,
        val scheduleHash: String,
        val nominalIntervalMs: Int,
        val serverMonotonicNs: Long,
        val t0MonotonicNs: Long,
    )

    private val officialProducerCases = listOf(
        OfficialProducerCase(
            label = "baseline",
            campaignId = "campaign-official-baseline",
            runId = "run-official-baseline",
            runIndex = 1,
            campaignMode = "quick",
            conditionId = "baseline_v0.1",
            scheduleHash = "46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e",
            nominalIntervalMs = 50,
            serverMonotonicNs = 4_200_000_000L,
            t0MonotonicNs = 4_200_000_000L,
        ),
        OfficialProducerCase(
            label = "slow",
            campaignId = "campaign-official-slow",
            runId = "run-official-slow",
            runIndex = 2,
            campaignMode = "quick",
            conditionId = "slow_v0.1",
            scheduleHash = "b51b27fe8332b3fc8a97472a44312b3001ccd54364a61ed8799816c299d27062",
            nominalIntervalMs = 125,
            serverMonotonicNs = 5_300_000_000L,
            t0MonotonicNs = 5_300_000_000L,
        ),
        OfficialProducerCase(
            label = "unstable",
            campaignId = "campaign-official-unstable",
            runId = "run-official-unstable",
            runIndex = 3,
            campaignMode = "quick",
            conditionId = "unstable_v0.1",
            scheduleHash = "d11dce2a877d7c3772a4552f2d922d5f96730c9a01bb829f0203c65b110a8c58",
            nominalIntervalMs = 65,
            serverMonotonicNs = 6_400_000_000L,
            t0MonotonicNs = 6_400_000_000L,
        ),
    )

    private fun acceptanceProducerCases(): List<OfficialProducerCase> = (4..9).map { runIndex ->
        val template = officialProducerCases[(runIndex - 4) % officialProducerCases.size]
        val suffix = runIndex.toString().padStart(2, '0')
        template.copy(
            label = "acceptance-$suffix",
            campaignId = "campaign-official-acceptance-$suffix",
            runId = "run-official-acceptance-$suffix",
            runIndex = runIndex,
            campaignMode = "acceptance",
        )
    }

    private fun conditionSurfaceVariant(
        surface: String,
        canonicalRequest: String,
        canonicalBlocks: List<String>,
        canonicalCondition: String,
        replacementCondition: String,
    ): Pair<String, List<String>> {
        val canonicalMember = "\"condition_id\":\"$canonicalCondition\""
        val replacementMember = "\"condition_id\":\"$replacementCondition\""
        if (surface == "request root") {
            val requestBody = canonicalRequest.replaceFirst(canonicalMember, replacementMember)
            require(requestBody != canonicalRequest) { "request condition mutation did not apply" }
            return requestBody to canonicalBlocks
        }
        val blocks = canonicalBlocks.toMutableList()
        val blockIndex = when (surface) {
            "run_started root" -> 0
            "content seq60" -> 60
            "terminal root", "terminal details" -> blocks.lastIndex
            else -> error("unsupported condition surface: $surface")
        }
        val original = blocks[blockIndex]
        blocks[blockIndex] = if (surface == "terminal details") {
            replaceSecondOccurrence(original, canonicalMember, replacementMember)
        } else {
            original.replaceFirst(canonicalMember, replacementMember)
        }
        require(blocks[blockIndex] != original) { "$surface condition mutation did not apply" }
        return canonicalRequest to blocks
    }

    private fun producerRunStartedPayload(
        eventTypeMembers: List<String> = listOf("\"event_type\":\"run_started\""),
        reordered: Boolean = false,
        producer: OfficialProducerCase = officialProducerCases.first(),
    ): String {
        val eventEnvelope = listOf(
            "\"schema_version\":\"aneb-prototype-evidence-0.1\"",
            "\"protocol_version\":\"prototype-stream-0.1\"",
            "\"campaign_id\":\"${producer.campaignId}\"",
            "\"run_id\":\"${producer.runId}\"",
            "\"condition_id\":\"${producer.conditionId}\"",
        )
        val serverClock = listOf(
            "\"server_monotonic_ns\":${producer.serverMonotonicNs}",
            "\"clock_source\":\"server.monotonic\"",
            "\"clock_unit\":\"ns\"",
            "\"clock_epoch\":\"process\"",
            "\"source\":\"server\"",
        )
        val details =
            "\"details\":{" +
                "\"profile_id\":\"streaming_text_reference_v0.1\"," +
                "\"profile_version\":\"0.1\"," +
                "\"profile_manifest_sha256\":\"44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc\"," +
                "\"schedule_hash\":\"${producer.scheduleHash}\"," +
                "\"nominal_interval_ms\":${producer.nominalIntervalMs}," +
                "\"t0_monotonic_ns\":${producer.t0MonotonicNs}}"
        val members = if (reordered) {
            eventEnvelope + serverClock + listOf(details) + eventTypeMembers
        } else {
            eventEnvelope + eventTypeMembers + serverClock + listOf(details)
        }
        return "{${members.joinToString(",")}}"
    }

    private suspend fun assertTerminalCompletionRejected(
        label: String,
        blocks: List<String>,
        arrivals: List<Long>? = null,
        expectedMessage: String = TERMINAL_COMPLETION_ERROR,
    ) {
        val rawStream = arrivals?.let { rawStreamWithArrivals(blocks, it) } ?: rawStreamOf(blocks)
        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(rawStream)).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("$label was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(expectedMessage, error.message)
        }
    }

    private suspend fun assertTerminalCompletionAccepted(
        label: String,
        blocks: List<String>,
    ) {
        try {
            val result = PrototypeRunStreamAdapter(
                FakeRawPostTransport(rawStreamOf(blocks)),
            ).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = canonicalRequestBody(),
            )
            assertNotNull(result.decodedTerminal)
        } catch (error: Throwable) {
            org.junit.Assert.fail("$label was rejected: ${error.message}")
        }
    }

    private fun completionBlocks(
        doneFrame: String,
        mutate: (String) -> String,
    ): MutableList<String> = canonicalBlocks(doneFrame, contentCount = 120).toMutableList().also { blocks ->
        val original = blocks[blocks.lastIndex]
        val mutated = mutate(original)
        require(mutated != original) { "terminal completion fixture mutation did not apply" }
        blocks[blocks.lastIndex] = mutated
    }

    private fun replaceTerminalDetailValue(
        frame: String,
        field: String,
        canonicalValue: String,
        replacementValue: String,
    ): String {
        val canonicalMember = "\"$field\":$canonicalValue"
        val replacementMember = "\"$field\":$replacementValue"
        require(frame.contains(canonicalMember)) { "terminal field missing: $field" }
        return frame.replace(canonicalMember, replacementMember)
    }

    private fun removeTerminalDetailValue(
        frame: String,
        field: String,
        canonicalValue: String,
    ): String {
        val canonicalMember = "\"$field\":$canonicalValue"
        val withComma = frame.replace("$canonicalMember,", "")
        if (withComma != frame) return withComma
        val withLeadingComma = frame.replace(",$canonicalMember", "")
        if (withLeadingComma != frame) return withLeadingComma
        val withoutComma = frame.replace(canonicalMember, "")
        require(withoutComma != frame) { "terminal field missing: $field" }
        return withoutComma
    }

    private fun duplicateTerminalDetailValues(
        frame: String,
        field: String,
        firstValue: String,
        secondValue: String,
    ): String {
        val canonicalMember = "\"$field\":120"
        val statusMember = "\"$field\":\"complete\""
        val target = if (field == "terminal_status") statusMember else canonicalMember
        val replacement = "\"$field\":$firstValue,\"$field\":$secondValue"
        require(frame.contains(target)) { "terminal field missing: $field" }
        return frame.replace(target, replacement)
    }

    private fun duplicateTerminalDetailKeys(
        frame: String,
        field: String,
        escapedField: String,
        canonicalValue: String,
        escapedFirst: Boolean,
    ): String {
        val canonicalMember = "\"$field\":$canonicalValue"
        val escapedMember = "\"$escapedField\":$canonicalValue"
        val replacement = if (escapedFirst) {
            "$escapedMember,$canonicalMember"
        } else {
            "$canonicalMember,$escapedMember"
        }
        require(frame.contains(canonicalMember)) { "terminal field missing: $field" }
        return frame.replace(canonicalMember, replacement)
    }

    private fun replaceTerminalDetailKey(
        frame: String,
        field: String,
        escapedField: String,
        canonicalValue: String,
    ): String {
        val canonicalMember = "\"$field\":$canonicalValue"
        val escapedMember = "\"$escapedField\":$canonicalValue"
        require(frame.contains(canonicalMember)) { "terminal field missing: $field" }
        return frame.replace(canonicalMember, escapedMember)
    }

    private fun canonicalBlocks(doneFrame: String, contentCount: Int): List<String> = buildList {
        add(
            "event: run_started\ndata: " +
                "{\"event_type\":\"run_started\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"," +
                "\"condition_id\":\"baseline_v0.1\"}",
        )
        repeat(contentCount) { index ->
            add(serverContentBlock(index + 1))
        }
        add(doneFrameForRun(doneFrame).removeSuffix("\n\n"))
    }

    private fun canonicalBlocksForIdentity(
        doneFrame: String,
        contentCount: Int,
        campaignId: String,
        runId: String,
    ): List<String> = canonicalBlocks(doneFrame, contentCount).map { block ->
        block
            .replace("\"campaign-1\"", "\"$campaignId\"")
            .replace("\"run-1\"", "\"$runId\"")
    }

    private fun officialRunRequestBody(producer: OfficialProducerCase): String =
        "{\"protocol_version\":\"prototype-stream-0.1\"," +
            "\"campaign_id\":\"${producer.campaignId}\",\"run_id\":\"${producer.runId}\"," +
            "\"campaign_mode\":\"${producer.campaignMode}\",\"run_index\":${producer.runIndex}," +
            "\"workload_id\":\"streaming_text_reference_v0.1\",\"workload_version\":\"0.1\"," +
            "\"profile_id\":\"streaming_text_reference_v0.1\",\"profile_version\":\"0.1\"," +
            "\"profile_manifest_sha256\":\"44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc\"," +
            "\"condition_id\":\"${producer.conditionId}\",\"condition_version\":\"0.1\"}"

    private fun canonicalRequestBody(): String =
        "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"," +
            "\"condition_id\":\"baseline_v0.1\"}"

    private fun officialRunRequestBodyWithRepresentation(
        producer: OfficialProducerCase,
        reordered: Boolean = false,
        escapedIdentityValues: Boolean = false,
    ): String {
        val campaignId = if (escapedIdentityValues) {
            unicodeEscapedJsonValue(producer.campaignId)
        } else {
            producer.campaignId
        }
        val runId = if (escapedIdentityValues) {
            unicodeEscapedJsonValue(producer.runId)
        } else {
            producer.runId
        }
        val members = listOf(
            "\"protocol_version\":\"prototype-stream-0.1\"",
            "\"campaign_id\":\"$campaignId\"",
            "\"run_id\":\"$runId\"",
            "\"campaign_mode\":\"${producer.campaignMode}\"",
            "\"run_index\":${producer.runIndex}",
            "\"workload_id\":\"streaming_text_reference_v0.1\"",
            "\"workload_version\":\"0.1\"",
            "\"profile_id\":\"streaming_text_reference_v0.1\"",
            "\"profile_version\":\"0.1\"",
            "\"profile_manifest_sha256\":\"44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc\"",
            "\"condition_id\":\"${producer.conditionId}\"",
            "\"condition_version\":\"0.1\"",
        )
        if (!reordered) {
            return "{${members.joinToString(",")}}"
        }
        val spaced = members.asReversed().map { member ->
            member.replace("\":", "\" : ")
        }
        return "{ \t${spaced.joinToString(" ,\t")} \t}"
    }

    private fun unicodeEscapedJsonValue(value: String): String =
        value.asIterable().joinToString("") { char ->
            "\\u" + char.code.toString(16).padStart(4, '0')
        }

    private fun requestBodyWithIdentityValue(field: String, value: String?): String {
        val members = mutableListOf<String>()
        if (field != "campaign_id") {
            members += "\"campaign_id\":\"campaign-1\""
        } else if (value != null) {
            members += "\"campaign_id\":$value"
        }
        if (field != "run_id") {
            members += "\"run_id\":\"run-1\""
        } else if (value != null) {
            members += "\"run_id\":$value"
        }
        return "{${members.joinToString(",")}}"
    }

    private fun requestBodyWithDuplicateIdentity(
        producer: OfficialProducerCase,
        field: String,
        canonicalValue: String,
        firstKey: String,
        firstValue: String,
        secondKey: String,
        secondValue: String,
    ): String {
        val canonicalMember = "\"$field\":\"$canonicalValue\""
        val firstMember = "\"$firstKey\":\"$firstValue\""
        val secondMember = "\"$secondKey\":\"$secondValue\""
        val original = officialRunRequestBody(producer)
        require(original.contains(canonicalMember))
        val withFirst = original.replaceFirst(canonicalMember, firstMember)
        return withFirst.replaceFirst(firstMember, "$firstMember,$secondMember")
    }

    private fun producerShapedBlocks(
        doneFrame: String,
        producer: OfficialProducerCase,
    ): List<String> = buildList {
        add(runStartedBlock(producerRunStartedPayload(producer = producer)))
        repeat(120) { index ->
            add(serverContentBlock(index + 1, producer))
        }
        add(doneFrameForProducer(doneFrame, producer).removeSuffix("\n\n"))
    }

    private fun doneFrameForProducer(
        doneFrame: String,
        producer: OfficialProducerCase,
    ): String {
        val frame = doneFrame
            .replace("\"campaign-fixture-01\"", "\"${producer.campaignId}\"")
            .replace("\"run-fixture-01\"", "\"${producer.runId}\"")
        val baselineConditionMember = "\"condition_id\":\"baseline_v0.1\""
        require(frame.split(baselineConditionMember).size - 1 == 2) {
            "done fixture must contain exactly two baseline condition_id members"
        }
        val withCondition = frame.replace(
            baselineConditionMember,
            "\"condition_id\":\"${producer.conditionId}\"",
        )
        require(withCondition != frame || producer.conditionId == "baseline_v0.1") {
            "done fixture condition replacement was a no-op"
        }
        val baselineCampaignModeMember = "\"campaign_mode\":\"quick\""
        require(frame.split(baselineCampaignModeMember).size - 1 == 1) {
            "done fixture must contain exactly one terminal campaign_mode member"
        }
        val withCampaignMode = withCondition.replace(
            baselineCampaignModeMember,
            "\"campaign_mode\":\"${producer.campaignMode}\"",
        )
        val baselineRunIndexMember = "\"run_index\":1"
        require(withCampaignMode.split(baselineRunIndexMember).size - 1 == 1) {
            "done fixture must contain exactly one terminal run_index member"
        }
        val withRunIndex = withCampaignMode.replace(
            baselineRunIndexMember,
            "\"run_index\":${producer.runIndex}",
        )
        return withRunIndex.replace(
            "\"server_monotonic_ns\":0",
            "\"server_monotonic_ns\":${producer.serverMonotonicNs + 121}",
        )
    }

    private fun doneFrameForRun(doneFrame: String): String = doneFrame
        .replace("\"campaign-fixture-01\"", "\"campaign-1\"")
        .replace("\"run-fixture-01\"", "\"run-1\"")

    private fun replaceSecondOccurrence(
        input: String,
        target: String,
        replacement: String,
    ): String {
        val first = input.indexOf(target)
        val second = input.indexOf(target, first + target.length)
        require(first >= 0 && second >= 0)
        return input.substring(0, second) + replacement + input.substring(second + target.length)
    }

    private fun removeFirstOccurrence(input: String, target: String): String {
        val first = input.indexOf(target)
        require(first >= 0)
        return input.removeRange(first, first + target.length)
    }

    private fun removeSecondOccurrence(input: String, target: String): String {
        val first = input.indexOf(target)
        val second = input.indexOf(target, first + target.length)
        require(first >= 0 && second >= 0)
        return input.removeRange(second, second + target.length)
    }

    private fun duplicateRootDetails(
        doneFrame: String,
        escapedCanonicalLast: Boolean,
        firstDetails: String = "{\"campaign_id\":\"forged-campaign\",\"run_id\":\"forged-run\"}",
    ): String {
        val frame = doneFrame.removeSuffix("\n\n")
        val marker = ",\"details\":"
        val prefix = frame.substringBefore(marker)
        val canonicalDetails = frame.substringAfter(marker).removeSuffix("}")
        val canonicalMarker = if (escapedCanonicalLast) {
            ",\"\\u0064etails\":"
        } else {
            marker
        }
        return prefix + marker + firstDetails + canonicalMarker + canonicalDetails + "}"
    }

    private fun serverContentBlock(seq: Int): String =
        "event: content_event\ndata: " +
            "{\"schema_version\":\"aneb-prototype-evidence-0.1\"," +
            "\"protocol_version\":\"prototype-stream-0.1\"," +
            "\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"," +
            "\"condition_id\":\"baseline_v0.1\",\"event_type\":\"content_event\"," +
            "\"server_monotonic_ns\":0,\"clock_source\":\"server.monotonic\"," +
            "\"clock_unit\":\"ns\",\"clock_epoch\":\"process\",\"source\":\"server\"," +
            "\"details\":{\"seq\":$seq,\"planned_offset_ms\":0," +
            "\"payload_id\":\"payload-$seq\",\"profile_manifest_sha256\":\"manifest\"," +
            "\"schedule_hash\":\"schedule\"}}"

    private fun serverContentBlock(seq: Int, producer: OfficialProducerCase): String =
        "event: content_event\ndata: " +
            "{\"schema_version\":\"aneb-prototype-evidence-0.1\"," +
            "\"protocol_version\":\"prototype-stream-0.1\"," +
            "\"campaign_id\":\"${producer.campaignId}\",\"run_id\":\"${producer.runId}\"," +
            "\"condition_id\":\"${producer.conditionId}\",\"event_type\":\"content_event\"," +
            "\"server_monotonic_ns\":${producer.serverMonotonicNs + seq}," +
            "\"clock_source\":\"server.monotonic\",\"clock_unit\":\"ns\"," +
            "\"clock_epoch\":\"process\",\"source\":\"server\"," +
            "\"details\":{\"seq\":$seq,\"planned_offset_ms\":${200 + (seq - 1) * producer.nominalIntervalMs}," +
            "\"payload_id\":\"ref-${"%04d".format(seq)}\"," +
            "\"profile_manifest_sha256\":\"44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc\"," +
            "\"schedule_hash\":\"${producer.scheduleHash}\"}}"

    private fun contentPayloadWithIdentity(data: String): String =
        if (data.trimStart().startsWith("{")) {
            data.replaceFirst(
                "{",
                "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"," +
                    "\"condition_id\":\"baseline_v0.1\",",
            )
        } else {
            data
        }

    private fun rawStreamOf(
        blocks: List<String>,
        truncatedTail: Boolean = false,
    ): RawSseStream {
        val arrivals = blocks.indices.map { (it + 1) * 1_000L }
        return rawStreamWithArrivals(blocks, arrivals, truncatedTail)
    }

    private fun rawStreamWithArrivals(
        blocks: List<String>,
        arrivals: List<Long>,
        truncatedTail: Boolean = false,
    ): RawSseStream {
        require(arrivals.size == blocks.size)
        val streamText = blocks.joinToString(separator = "\n\n", postfix = "\n\n")
        return RawSseStream(
            events = blocks.mapIndexed { index, block ->
                RawSseEvent(
                    bytes = block.toByteArray(Charsets.UTF_8),
                    arrivalNanos = arrivals[index],
                    sameReadBatch = false,
                )
            },
            readCount = blocks.size,
            totalBytes = streamText.toByteArray(Charsets.UTF_8).size.toLong(),
            truncatedTail = truncatedTail,
            eofNanos = (blocks.size + 1) * 1_000L,
        )
    }

    private fun readFixture(name: String): String {
        val candidates = listOf(
            Path.of("server/testdata/$name"),
            Path.of("../../server/testdata/$name"),
        )
        val path = candidates.firstOrNull { Files.isRegularFile(it) }
            ?: error("shared fixture not found: ${candidates.joinToString()}")
        val raw = Files.readAllBytes(path).toString(Charsets.UTF_8)
        val normalized = raw.replace("\r\n", "\n")
        require('\r' !in normalized) { "shared fixture contains a bare CR" }
        return normalized
    }

    private fun readProductionSource(): String {
        val candidates = listOf(
            Path.of("app/probe/src/main/java/com/aneb/probe/prototype/PrototypeRunStreamAdapter.kt"),
            Path.of("src/main/java/com/aneb/probe/prototype/PrototypeRunStreamAdapter.kt"),
            Path.of("../../app/probe/src/main/java/com/aneb/probe/prototype/PrototypeRunStreamAdapter.kt"),
        )
        val path = candidates.firstOrNull { Files.isRegularFile(it) }
            ?: error("adapter source not found: ${candidates.joinToString()}")
        val raw = Files.readAllBytes(path).toString(Charsets.UTF_8)
        val normalized = raw.replace("\r\n", "\n")
        require('\r' !in normalized) { "adapter source contains a bare CR" }
        return normalized
    }
}
