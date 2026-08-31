package com.aneb.probe.prototype

import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.RawSseStream
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/** Bridges the Prototype runner transport seam to the existing AnebClient HTTP/SSE path. */
class AnebClientPrototypeRawPostTransport(
    private val client: AnebClient,
) : PrototypeNodeCompatibilityChecker {
    override suspend fun check(runUrl: String): CompatibleNodeTicket {
        val endpoint = PrototypeNodeEndpoint.parseRunUrl(runUrl)
        val capability = client.fetchPrototypeCapability(endpoint.capabilityUrl)
        if (capability.httpCode !in 200..299) {
            throw IllegalStateException(capability.error ?: "prototype capability request failed")
        }
        val body = capability.body
        if (body == null || !isCompatibleCapability(body)) {
            throw PrototypeNodeIncompatibleException(CAPABILITY_ERROR)
        }
        return CompatibleNodeTicket.fromValidatedCapability(
            endpoint = endpoint,
            rawCapabilityBody = body,
            identity = capabilityIdentity(body),
        )
    }

    internal fun ticketFromValidatedSnapshot(
        runUrl: String,
        rawCapabilityBody: String,
    ): CompatibleNodeTicket {
        val endpoint = PrototypeNodeEndpoint.parseRunUrl(runUrl)
        if (!isCompatibleCapability(rawCapabilityBody)) {
            throw PrototypeNodeIncompatibleException(CAPABILITY_ERROR)
        }
        return CompatibleNodeTicket.fromValidatedCapability(
            endpoint = endpoint,
            rawCapabilityBody = rawCapabilityBody,
            identity = capabilityIdentity(rawCapabilityBody),
        )
    }

    fun forTicket(ticket: CompatibleNodeTicket): PrototypeRawPostTransport =
        TicketBoundTransport(ticket)

    private inner class TicketBoundTransport(
        private val ticket: CompatibleNodeTicket,
    ) : PrototypeRawPostTransport {
        override suspend fun post(url: String, requestBody: String): RawSseStream =
            postObserved(
                url = url,
                requestBody = requestBody,
                observer = PrototypeRawPostObserver(beforeDispatch = {}, onRawEvent = {}),
            )

        override suspend fun postObserved(
            url: String,
            requestBody: String,
            observer: PrototypeRawPostObserver,
        ): RawSseStream {
            val endpoint = PrototypeNodeEndpoint.parseRunUrl(url)
            require(
                endpoint.baseUrl == ticket.nodeBaseUrl &&
                    endpoint.runUrl == ticket.runUrl &&
                    endpoint.capabilityUrl == ticket.capabilityUrl
            ) { TICKET_URL_ERROR }
            val current = check(endpoint.runUrl)
            if (current.identity != ticket.identity) {
                throw PrototypeNodeIncompatibleException(CAPABILITY_CHANGED_ERROR)
            }
            return client.postPrototypeRawSse(
                url = endpoint.runUrl,
                requestBody = requestBody,
                beforeDispatch = observer.beforeDispatch,
                onRawEvent = observer.onRawEvent,
            )
        }
    }

    internal companion object {
        private const val CAPABILITY_ERROR = "prototype capability response is incompatible"
        private const val CAPABILITY_CHANGED_ERROR =
            "prototype capability changed since node preflight"
        private const val TICKET_URL_ERROR = "prototype node ticket does not match run URL"
        private const val PROFILE_MANIFEST_SHA256 =
            "44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc"
        private const val CLAIM_SCOPE = "application_end_to_end_to_probe_node"
        private const val EVIDENCE_MODE = "synthetic_application_impairment"
        private const val IMPAIRMENT_LAYER = "application"
        private const val WORKLOAD_ID = "streaming_text_reference_v0.1"
        private const val EVIDENCE_SCHEMA_VERSION = "aneb-prototype-evidence-0.1"
        private const val SCORE_POLICY_ID = "rpi-0.1"
        private const val TERMINAL_RECEIPT_VERSION = "prototype-terminal-receipt-0.1"
        private val capabilityJson = Json {
            ignoreUnknownKeys = false
            isLenient = false
        }
        private val hashPattern = Regex("^[a-f0-9]{64}$")

        @JvmSynthetic
        internal fun validatedCapabilityIdentityOrNull(
            body: String,
        ): PrototypeCapabilityIdentity? = try {
            if (isCompatibleCapability(body)) capabilityIdentity(body) else null
        } catch (_: Exception) {
            null
        }

        private object CapabilityWorkloadDuplicateKeyProbe : DeserializationStrategy<Unit> {
            override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
                "PrototypeCapabilityWorkloadDuplicateKeyProbe",
            ) {
                element("id", JsonElement.serializer().descriptor, isOptional = true)
                element("version", JsonElement.serializer().descriptor, isOptional = true)
                element("content_event_count", JsonElement.serializer().descriptor, isOptional = true)
            }

            override fun deserialize(decoder: Decoder) {
                decoder.decodeStructure(descriptor) {
                    val seen = BooleanArray(descriptor.elementsCount)
                    while (true) {
                        when (val index = decodeElementIndex(descriptor)) {
                            CompositeDecoder.DECODE_DONE -> break
                            in seen.indices -> {
                                require(!seen[index]) { CAPABILITY_ERROR }
                                seen[index] = true
                                decodeSerializableElement(
                                    descriptor,
                                    index,
                                    JsonElement.serializer(),
                                )
                            }
                            else -> throw IllegalArgumentException(CAPABILITY_ERROR)
                        }
                    }
                }
            }
        }

        private object CapabilityConditionDuplicateKeyProbe : KSerializer<Unit> {
            override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
                "PrototypeCapabilityConditionDuplicateKeyProbe",
            ) {
                element("id", JsonElement.serializer().descriptor, isOptional = true)
                element("version", JsonElement.serializer().descriptor, isOptional = true)
                element("nominal_interval_ms", JsonElement.serializer().descriptor, isOptional = true)
                element("schedule_sha256", JsonElement.serializer().descriptor, isOptional = true)
            }

            override fun deserialize(decoder: Decoder) {
                decoder.decodeStructure(descriptor) {
                    val seen = BooleanArray(descriptor.elementsCount)
                    while (true) {
                        when (val index = decodeElementIndex(descriptor)) {
                            CompositeDecoder.DECODE_DONE -> break
                            in seen.indices -> {
                                require(!seen[index]) { CAPABILITY_ERROR }
                                seen[index] = true
                                decodeSerializableElement(
                                    descriptor,
                                    index,
                                    JsonElement.serializer(),
                                )
                            }
                            else -> throw IllegalArgumentException(CAPABILITY_ERROR)
                        }
                    }
                }
            }

            override fun serialize(encoder: Encoder, value: Unit) {
                throw UnsupportedOperationException("Capability condition probe is decode-only")
            }
        }

        private val capabilityConditionsDuplicateKeyProbe =
            ListSerializer(CapabilityConditionDuplicateKeyProbe)

        private object CapabilityRootDuplicateKeyProbe : DeserializationStrategy<Unit> {
            override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
                "PrototypeCapabilityRootDuplicateKeyProbe",
            ) {
                element("schema_version", JsonElement.serializer().descriptor, isOptional = true)
                element("product_version", JsonElement.serializer().descriptor, isOptional = true)
                element("protocol_version", JsonElement.serializer().descriptor, isOptional = true)
                element("server_version", JsonElement.serializer().descriptor, isOptional = true)
                element("server_binary_sha256", JsonElement.serializer().descriptor, isOptional = true)
                element("claim_scope", JsonElement.serializer().descriptor, isOptional = true)
                element("evidence_mode", JsonElement.serializer().descriptor, isOptional = true)
                element("impairment_layer", JsonElement.serializer().descriptor, isOptional = true)
                element("profile_manifest_sha256", JsonElement.serializer().descriptor, isOptional = true)
                element("workload", CapabilityWorkloadDuplicateKeyProbe.descriptor, isOptional = true)
                element("conditions", capabilityConditionsDuplicateKeyProbe.descriptor, isOptional = true)
                element("evidence_schema_version", JsonElement.serializer().descriptor, isOptional = true)
                element("score_policy_id", JsonElement.serializer().descriptor, isOptional = true)
                element("terminal_receipt_version", JsonElement.serializer().descriptor, isOptional = true)
            }

            override fun deserialize(decoder: Decoder) {
                decoder.decodeStructure(descriptor) {
                    val seen = BooleanArray(descriptor.elementsCount)
                    while (true) {
                        when (val index = decodeElementIndex(descriptor)) {
                            CompositeDecoder.DECODE_DONE -> break
                            in seen.indices -> {
                                require(!seen[index]) { CAPABILITY_ERROR }
                                seen[index] = true
                                when (index) {
                                    9 -> decodeSerializableElement(
                                        descriptor,
                                        index,
                                        CapabilityWorkloadDuplicateKeyProbe,
                                    )
                                    10 -> decodeSerializableElement(
                                        descriptor,
                                        index,
                                        capabilityConditionsDuplicateKeyProbe,
                                    )
                                    else -> decodeSerializableElement(
                                        descriptor,
                                        index,
                                        JsonElement.serializer(),
                                    )
                                }
                            }
                            else -> throw IllegalArgumentException(CAPABILITY_ERROR)
                        }
                    }
                }
            }
        }

        private fun isCompatibleCapability(body: String): Boolean {
            return try {
                capabilityJson.decodeFromString(CapabilityRootDuplicateKeyProbe, body)
                val root = capabilityJson.parseToJsonElement(body) as? JsonObject ?: return false
            if (root.keys != setOf(
                    "schema_version",
                    "product_version",
                    "protocol_version",
                    "server_version",
                    "server_binary_sha256",
                    "claim_scope",
                    "evidence_mode",
                    "impairment_layer",
                    "profile_manifest_sha256",
                    "workload",
                    "conditions",
                    "evidence_schema_version",
                    "score_policy_id",
                    "terminal_receipt_version",
                )
            ) {
                return false
            }
            if (!stringEquals(root, "schema_version", "aneb-prototype-capabilities-0.1") ||
                !stringEquals(root, "product_version", "prototype-0.1") ||
                !stringEquals(root, "protocol_version", "prototype-stream-0.1") ||
                !nonEmptyString(root["server_version"]) ||
                !hashString(root["server_binary_sha256"]) ||
                !stringEquals(root, "claim_scope", CLAIM_SCOPE) ||
                !stringEquals(root, "evidence_mode", EVIDENCE_MODE) ||
                !stringEquals(root, "impairment_layer", IMPAIRMENT_LAYER) ||
                !stringEquals(root, "profile_manifest_sha256", PROFILE_MANIFEST_SHA256) ||
                !stringEquals(root, "evidence_schema_version", EVIDENCE_SCHEMA_VERSION) ||
                !stringEquals(root, "score_policy_id", SCORE_POLICY_ID) ||
                !stringEquals(root, "terminal_receipt_version", TERMINAL_RECEIPT_VERSION)
            ) {
                return false
            }
            val workload = root["workload"] as? JsonObject ?: return false
            if (workload.keys != setOf("id", "version", "content_event_count") ||
                !stringEquals(workload, "id", WORKLOAD_ID) ||
                !stringEquals(workload, "version", "0.1") ||
                !integerEquals(workload["content_event_count"], 120)
            ) {
                return false
            }
            val conditions = root["conditions"] as? JsonArray ?: return false
            if (conditions.size != 3) return false
            val expectedConditions = listOf(
                Triple("baseline_v0.1", 50, "46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e"),
                Triple("slow_v0.1", 125, "b51b27fe8332b3fc8a97472a44312b3001ccd54364a61ed8799816c299d27062"),
                Triple("unstable_v0.1", 65, "d11dce2a877d7c3772a4552f2d922d5f96730c9a01bb829f0203c65b110a8c58"),
            )
            conditions.zip(expectedConditions).all { (element, expected) ->
                val condition = element as? JsonObject ?: return@all false
                condition.keys == setOf("id", "version", "nominal_interval_ms", "schedule_sha256") &&
                    stringEquals(condition, "id", expected.first) &&
                    stringEquals(condition, "version", "0.1") &&
                    integerEquals(condition["nominal_interval_ms"], expected.second) &&
                    stringEquals(condition, "schedule_sha256", expected.third)
            }
            } catch (_: Exception) {
                false
            }
        }

        private fun capabilityIdentity(body: String): PrototypeCapabilityIdentity {
            val root = capabilityJson.parseToJsonElement(body) as JsonObject
            val workload = root.getValue("workload") as JsonObject
            val conditions = root.getValue("conditions") as JsonArray
            return PrototypeCapabilityIdentity(
                schemaVersion = (root.getValue("schema_version") as JsonPrimitive).content,
                productVersion = (root.getValue("product_version") as JsonPrimitive).content,
                protocolVersion = (root.getValue("protocol_version") as JsonPrimitive).content,
                serverVersion = (root.getValue("server_version") as JsonPrimitive).content,
                serverBinarySha256 =
                    (root.getValue("server_binary_sha256") as JsonPrimitive).content,
                claimScope = (root.getValue("claim_scope") as JsonPrimitive).content,
                evidenceMode = (root.getValue("evidence_mode") as JsonPrimitive).content,
                impairmentLayer = (root.getValue("impairment_layer") as JsonPrimitive).content,
                profileManifestSha256 =
                    (root.getValue("profile_manifest_sha256") as JsonPrimitive).content,
                workload = PrototypeCapabilityWorkloadIdentity(
                    id = (workload.getValue("id") as JsonPrimitive).content,
                    version = (workload.getValue("version") as JsonPrimitive).content,
                    contentEventCount = integerValue(workload.getValue("content_event_count")),
                ),
                conditions = conditions.map { element ->
                    val condition = element as JsonObject
                    PrototypeCapabilityConditionIdentity(
                        id = (condition.getValue("id") as JsonPrimitive).content,
                        version = (condition.getValue("version") as JsonPrimitive).content,
                        nominalIntervalMs = integerValue(
                            condition.getValue("nominal_interval_ms"),
                        ),
                        scheduleSha256 =
                            (condition.getValue("schedule_sha256") as JsonPrimitive).content,
                    )
                }.toList(),
                evidenceSchemaVersion =
                    (root.getValue("evidence_schema_version") as JsonPrimitive).content,
                scorePolicyId = (root.getValue("score_policy_id") as JsonPrimitive).content,
                terminalReceiptVersion =
                    (root.getValue("terminal_receipt_version") as JsonPrimitive).content,
            )
        }

        private fun integerValue(element: JsonElement): Int =
            (element as JsonPrimitive).content.toBigDecimal().intValueExact()

        private fun stringEquals(root: JsonObject, key: String, expected: String): Boolean =
            (root[key] as? JsonPrimitive)?.let { it.isString && it.content == expected } == true

        private fun nonEmptyString(element: JsonElement?): Boolean =
            (element as? JsonPrimitive)?.let { it.isString && it.content.isNotEmpty() } == true

        private fun hashString(element: JsonElement?): Boolean =
            (element as? JsonPrimitive)?.let { it.isString && hashPattern.matches(it.content) } == true

        private fun integerEquals(element: JsonElement?, expected: Int): Boolean =
            (element as? JsonPrimitive)?.let {
                !it.isString &&
                    it.content.toBigDecimalOrNull()
                        ?.compareTo(BigDecimal.valueOf(expected.toLong())) == 0
            } == true
    }
}
