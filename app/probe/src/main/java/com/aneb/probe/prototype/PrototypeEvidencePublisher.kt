package com.aneb.probe.prototype

import com.aneb.probe.net.AnebClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun interface PrototypeEvidenceTransport {
    suspend fun post(url: String, canonicalUpload: String): AnebClient.HttpTextResult
}

internal data class PrototypePublicationReceipt(
    val campaignId: String,
    val manifestSha256: String,
)

internal object PrototypeEvidencePublisher {
    suspend fun publish(
        client: AnebClient,
        nodeBaseUrl: String,
        expectedCampaignId: String,
        canonicalUpload: String,
    ): PrototypePublicationReceipt = publish(
        transport = PrototypeEvidenceTransport(client::postPrototypeEvidence),
        nodeBaseUrl = nodeBaseUrl,
        expectedCampaignId = expectedCampaignId,
        canonicalUpload = canonicalUpload,
    )

    suspend fun publish(
        transport: PrototypeEvidenceTransport,
        nodeBaseUrl: String,
        expectedCampaignId: String,
        canonicalUpload: String,
    ): PrototypePublicationReceipt {
        require(SAFE_CAMPAIGN_ID.matches(expectedCampaignId)) { PUBLICATION_FAILED }
        val endpoint = PrototypeNodeEndpoint.parse(nodeBaseUrl)
        val response = try {
            transport.post(
                "${endpoint.baseUrl}/api/v1/prototype/campaigns/evidence",
                canonicalUpload,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw IllegalStateException(PUBLICATION_FAILED)
        }
        if (response.httpCode != 200 || response.error != null || response.body == null) {
            throw IllegalStateException(PUBLICATION_FAILED)
        }
        return parseReceipt(response.body, expectedCampaignId)
    }

    private fun parseReceipt(source: String, expectedCampaignId: String): PrototypePublicationReceipt {
        try {
            require(source.endsWith('\n') && !source.dropLast(1).containsAny("\r\n"))
            val line = source.dropLast(1)
            val root = JSON.parseToJsonElement(line) as? JsonObject ?: error(PUBLICATION_FAILED)
            require(root.toString() == line)
            require(root.keys == RECEIPT_KEYS)
            val campaignId = root.exactString("campaign_id")
            val manifestSha256 = root.exactString("manifest_sha256")
            require(campaignId == expectedCampaignId)
            require(SHA256.matches(manifestSha256))
            require(root.exactString("publication_status") == "verified")
            require(root.exactString("schema_version") == RECEIPT_SCHEMA_VERSION)
            return PrototypePublicationReceipt(campaignId, manifestSha256)
        } catch (_: Exception) {
            throw IllegalStateException(PUBLICATION_FAILED)
        }
    }

    private fun JsonObject.exactString(key: String): String {
        val value = get(key) as? JsonPrimitive ?: error(PUBLICATION_FAILED)
        require(value.isString)
        return value.content
    }

    private fun String.containsAny(chars: String): Boolean = any(chars::contains)

    private const val PUBLICATION_FAILED = "P018_EVIDENCE_PUBLICATION_FAILED"
    private const val RECEIPT_SCHEMA_VERSION = "aneb-prototype-publication-receipt-0.1"
    private val SAFE_CAMPAIGN_ID = Regex("^[A-Za-z0-9._:-]{1,128}$")
    private val SHA256 = Regex("^[a-f0-9]{64}$")
    private val RECEIPT_KEYS = setOf(
        "campaign_id",
        "manifest_sha256",
        "publication_status",
        "schema_version",
    )
    private val JSON = Json { ignoreUnknownKeys = false }
}
