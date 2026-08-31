package com.aneb.probe.prototype

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Collections

data class PrototypeNodeCapability(
    val serverVersion: String,
    val workloadVersion: String,
    val profileManifestSha256: String,
    val conditions: List<String>,
    val evidenceSchemaVersion: String,
    val scorePolicyId: String,
)

data class PrototypeCapabilityWorkloadIdentity(
    val id: String,
    val version: String,
    val contentEventCount: Int,
)

data class PrototypeCapabilityConditionIdentity(
    val id: String,
    val version: String,
    val nominalIntervalMs: Int,
    val scheduleSha256: String,
)

data class PrototypeCapabilityIdentity(
    val schemaVersion: String,
    val productVersion: String,
    val protocolVersion: String,
    val serverVersion: String,
    val serverBinarySha256: String,
    val claimScope: String,
    val evidenceMode: String,
    val impairmentLayer: String,
    val profileManifestSha256: String,
    val workload: PrototypeCapabilityWorkloadIdentity,
    val conditions: List<PrototypeCapabilityConditionIdentity>,
    val evidenceSchemaVersion: String,
    val scorePolicyId: String,
    val terminalReceiptVersion: String,
) {
    fun displaySummary(): PrototypeNodeCapability = PrototypeNodeCapability(
        serverVersion = serverVersion,
        workloadVersion = workload.version,
        profileManifestSha256 = profileManifestSha256,
        conditions = conditions.map(PrototypeCapabilityConditionIdentity::id),
        evidenceSchemaVersion = evidenceSchemaVersion,
        scorePolicyId = scorePolicyId,
    )
}

/**
 * Process-local admission ticket for one strictly validated capability response.
 *
 * [rawCapabilityBody] preserves the exact decoded HTTP response String for diagnostics. Runtime
 * equality uses [identity], so equivalent JSON whitespace, member order, and numeric lexemes do
 * not invalidate the ticket.
 */
class CompatibleNodeTicket private constructor(
    val nodeBaseUrl: String,
    val runUrl: String,
    val capabilityUrl: String,
    val rawCapabilityBody: String,
    val identity: PrototypeCapabilityIdentity,
) {
    val capability: PrototypeNodeCapability
        get() = identity.displaySummary()

    companion object {
        internal fun fromValidatedCapability(
            endpoint: PrototypeNodeEndpoint,
            rawCapabilityBody: String,
            identity: PrototypeCapabilityIdentity,
        ): CompatibleNodeTicket = CompatibleNodeTicket(
            nodeBaseUrl = endpoint.baseUrl,
            runUrl = endpoint.runUrl,
            capabilityUrl = endpoint.capabilityUrl,
            rawCapabilityBody = rawCapabilityBody,
            identity = identity.copy(
                conditions = Collections.unmodifiableList(identity.conditions.toList()),
            ),
        )
    }
}

fun interface PrototypeNodeCompatibilityChecker {
    suspend fun check(runUrl: String): CompatibleNodeTicket
}

class PrototypeNodeIncompatibleException(message: String) : IllegalArgumentException(message)

interface PrototypeNodeSettings {
    fun loadNodeUrl(): String

    fun saveNodeUrl(nodeBaseUrl: String)
}

sealed interface PrototypeNodeState {
    val canStartQuick: Boolean
        get() = this is Compatible

    data class Compatible(
        val ticket: CompatibleNodeTicket,
    ) : PrototypeNodeState {
        val nodeBaseUrl: String
            get() = ticket.nodeBaseUrl

        val capability: PrototypeNodeCapability
            get() = ticket.capability
    }

    data class ConnectedIncompatible(
        val nodeBaseUrl: String,
        val message: String,
    ) : PrototypeNodeState
}

class PrototypeNodeController(
    private val settings: PrototypeNodeSettings,
    private val compatibilityChecker: PrototypeNodeCompatibilityChecker,
) {
    private val ticketLock = Any()
    private var ticketRevision = 0L
    private var compatibleTicket: CompatibleNodeTicket? = null

    suspend fun configureAndCheck(input: String): PrototypeNodeState {
        val endpoint = PrototypeNodeEndpoint.parse(input)
        val attemptRevision = synchronized(ticketLock) {
            ticketRevision += 1
            compatibleTicket = null
            ticketRevision
        }
        settings.saveNodeUrl(endpoint.baseUrl)
        return try {
            val ticket = compatibilityChecker.check(endpoint.runUrl)
            require(
                ticket.nodeBaseUrl == endpoint.baseUrl &&
                    ticket.runUrl == endpoint.runUrl &&
                    ticket.capabilityUrl == endpoint.capabilityUrl
            ) { "Prototype capability ticket does not match the checked node" }
            synchronized(ticketLock) {
                require(ticketRevision == attemptRevision) {
                    "Prototype node URL changed; test the connection again"
                }
                compatibleTicket = ticket
            }
            PrototypeNodeState.Compatible(ticket)
        } catch (error: PrototypeNodeIncompatibleException) {
            synchronized(ticketLock) {
                require(ticketRevision == attemptRevision) {
                    "Prototype node URL changed; test the connection again"
                }
            }
            PrototypeNodeState.ConnectedIncompatible(
                nodeBaseUrl = endpoint.baseUrl,
                message = error.message.orEmpty(),
            )
        }
    }

    fun invalidateTicket() {
        synchronized(ticketLock) {
            ticketRevision += 1
            compatibleTicket = null
        }
    }

    fun ticketForStart(input: String): CompatibleNodeTicket? {
        val endpoint = runCatching { PrototypeNodeEndpoint.parse(input) }.getOrNull() ?: return null
        return synchronized(ticketLock) {
            compatibleTicket?.takeIf { ticket ->
                ticket.nodeBaseUrl == endpoint.baseUrl &&
                    ticket.runUrl == endpoint.runUrl &&
                    ticket.capabilityUrl == endpoint.capabilityUrl
            }
        }
    }
}

class PrototypeNodeEndpoint private constructor(
    val baseUrl: String,
    val runUrl: String,
    val capabilityUrl: String,
) {
    companion object {
        fun parse(input: String): PrototypeNodeEndpoint {
            val trimmed = input.trim()
            val parsed = trimmed.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("invalid Prototype node URL")
            require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
                "Prototype node URL must not contain user information"
            }
            require(parsed.query == null && parsed.fragment == null) {
                "Prototype node URL must not contain query or fragment"
            }
            require(parsed.encodedPath == "/") {
                "Prototype node URL must be a base URL"
            }
            require(parsed.scheme == "https" || isCanonicalPrivateHttp(trimmed, parsed)) {
                "cleartext Prototype nodes must use a literal RFC1918 or loopback IPv4 address with an explicit port"
            }
            val base = parsed.newBuilder()
                .encodedPath("/")
                .query(null)
                .fragment(null)
                .build()
            return PrototypeNodeEndpoint(
                baseUrl = base.toString().removeSuffix("/"),
                runUrl = base.newBuilder()
                    .encodedPath(RUN_PATH)
                    .build()
                    .toString(),
                capabilityUrl = base.newBuilder()
                    .encodedPath(CAPABILITY_PATH)
                    .build()
                    .toString(),
            )
        }

        fun parseRunUrl(input: String): PrototypeNodeEndpoint {
            val trimmed = input.trim()
            val parsed = trimmed.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("invalid Prototype run URL")
            require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
                "Prototype run URL must not contain user information"
            }
            require(parsed.query == null && parsed.fragment == null && parsed.encodedPath == RUN_PATH) {
                "Prototype run URL must use the exact run path"
            }
            val baseInput = if (parsed.scheme == "http") {
                require(trimmed.endsWith(RUN_PATH)) { "Prototype run URL must be canonical" }
                trimmed.removeSuffix(RUN_PATH)
            } else {
                parsed.newBuilder().encodedPath("/").build().toString().removeSuffix("/")
            }
            return parse(baseInput).also { endpoint ->
                require(endpoint.runUrl == parsed.toString()) { "Prototype run URL must be canonical" }
            }
        }

        private fun isCanonicalPrivateHttp(raw: String, url: HttpUrl): Boolean {
            if (url.scheme != "http") return false
            val match = PRIVATE_HTTP_BASE.matchEntire(raw) ?: return false
            val literalHost = match.groupValues[1]
            val literalPort = match.groupValues[2]
            if (literalHost != url.host || literalPort.toIntOrNull()?.toString() != literalPort) return false
            if (url.port != literalPort.toIntOrNull() || url.port !in 1..65535) return false
            val parts = literalHost.split('.')
            if (parts.size != 4 || parts.any { part -> part.toIntOrNull()?.toString() != part }) return false
            val octets = parts.map { part -> part.toIntOrNull() ?: return false }
            if (octets.size != 4 || octets.any { it !in 0..255 }) return false
            return octets[0] == 10 ||
                octets[0] == 127 ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168)
        }

        private val PRIVATE_HTTP_BASE = Regex("^http://([0-9.]+):([0-9]{1,5})/?$")
        private const val RUN_PATH = "/api/v1/prototype/runs"
        private const val CAPABILITY_PATH = "/api/v1/prototype/capabilities"
    }
}
