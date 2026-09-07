package com.aneb.probe.prototype

import com.aneb.probe.net.AnebClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PrototypeEvidencePublisherTest {
    @Test
    fun acceptsOnlyTheVerifiedReceiptForTheUploadedCampaign() = runBlocking {
        val campaignId = "campaign-0001"
        val upload = "{\"schema_version\":\"aneb-prototype-upload-0.1\",\"campaign_id\":\"$campaignId\"}"
        val manifestSha256 = "a".repeat(64)
        val receiptBody =
            "{\"campaign_id\":\"$campaignId\",\"manifest_sha256\":\"$manifestSha256\"," +
                "\"publication_status\":\"verified\",\"schema_version\":" +
                "\"aneb-prototype-publication-receipt-0.1\"}\n"
        var observedUrl: String? = null
        var observedUpload: String? = null
        val transport = PrototypeEvidenceTransport { url, body ->
            observedUrl = url
            observedUpload = body
            AnebClient.HttpTextResult(httpCode = 200, body = receiptBody, error = null)
        }

        val receipt = PrototypeEvidencePublisher.publish(
            transport = transport,
            nodeBaseUrl = "http://192.168.1.20:18088",
            expectedCampaignId = campaignId,
            canonicalUpload = upload,
        )

        assertEquals("http://192.168.1.20:18088/api/v1/prototype/campaigns/evidence", observedUrl)
        assertEquals(upload, observedUpload)
        assertEquals(campaignId, receipt.campaignId)
        assertEquals(manifestSha256, receipt.manifestSha256)
    }

    @Test
    fun rejectsA200ResponseThatIsNotTheExactVerifiedReceipt() = runBlocking {
        val campaignId = "campaign-0001"
        val hash = "a".repeat(64)
        val canonical =
            "{\"campaign_id\":\"$campaignId\",\"manifest_sha256\":\"$hash\"," +
                "\"publication_status\":\"verified\",\"schema_version\":" +
                "\"aneb-prototype-publication-receipt-0.1\"}\n"
        val invalidReceipts = listOf(
            canonical.replace(campaignId, "campaign-other"),
            canonical.replace("verified", "candidate"),
            canonical.replace(hash, hash.uppercase()),
            canonical.replace("{", "{\"extra\":true,"),
            canonical.removeSuffix("\n"),
        )

        invalidReceipts.forEach { invalidReceipt ->
            val transport = PrototypeEvidenceTransport { _, _ ->
                AnebClient.HttpTextResult(200, invalidReceipt, null)
            }
            try {
                PrototypeEvidencePublisher.publish(
                    transport = transport,
                    nodeBaseUrl = "http://192.168.1.20:18088",
                    expectedCampaignId = campaignId,
                    canonicalUpload = "{}",
                )
                fail("invalid 200 receipt must not claim publication")
            } catch (failure: IllegalStateException) {
                assertEquals("P018_EVIDENCE_PUBLICATION_FAILED", failure.message)
            }
        }
    }
}
