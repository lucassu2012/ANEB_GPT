package com.aneb.probe.engine

import com.aneb.probe.data.ResultEnvelopeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FormalResultUploadReceiptContractTest {
    @Test
    fun `delivery receipt binds verified evidence with explicit stable wire values`() {
        val resultId = "123e4567-e89b-47d3-a456-426614174000"
        val canonicalSha256 =
            "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val envelope = envelope(resultId, canonicalSha256)
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-private",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        val receipts = listOf(
            FormalResultUploadReceipt.from(
                delivery = FormalResultDelivery.Accepted(201),
                envelope = envelope,
                scope = scope,
            ),
            FormalResultUploadReceipt.from(
                delivery = FormalResultDelivery.Rejected(409),
                envelope = envelope,
                scope = scope,
            ),
            FormalResultUploadReceipt.from(
                delivery = FormalResultDelivery.Failed,
                envelope = envelope,
                scope = scope,
            ),
        )

        assertEquals(
            listOf("accepted", "rejected", "delivery_unconfirmed"),
            receipts.map(FormalResultUploadReceipt::terminalCode),
        )
        assertEquals(listOf(201, 409, null), receipts.map(FormalResultUploadReceipt::httpCode))
        assertEquals(
            listOf(
                FormalResultUploadReceiptTerminal.ACCEPTED,
                FormalResultUploadReceiptTerminal.REJECTED,
                FormalResultUploadReceiptTerminal.DELIVERY_UNCONFIRMED,
            ),
            receipts.map(FormalResultUploadReceipt::terminal),
        )
        receipts.forEach { receipt ->
            assertEquals(resultId, receipt.resultId)
            assertEquals(canonicalSha256, receipt.canonicalSha256)
            assertEquals(scope.recipient, receipt.recipient)
            assertEquals("result_submission", receipt.purposeCode)
            assertEquals(scope.policyVersion, receipt.policyVersion)
            assertNotEquals(receipt.terminal.name, receipt.terminalCode)
            listOf(resultId, canonicalSha256, scope.recipient).forEach { forbidden ->
                assertFalse(receipt.toString().contains(forbidden))
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            FormalResultUploadReceipt.from(
                delivery = FormalResultDelivery.Accepted(201),
                envelope = envelope("not-a-result-id", canonicalSha256),
                scope = scope,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FormalResultUploadReceipt.from(
                delivery = FormalResultDelivery.Accepted(201),
                envelope = envelope(resultId, "sha256:RAW_HASH_SENTINEL"),
                scope = scope,
            )
        }
    }

    private fun envelope(resultId: String, canonicalSha256: String) = ResultEnvelopeEntity(
        runId = resultId,
        schemaVersion = "aneb-result-v2",
        testType = "token_simulation",
        startedAtEpochMs = 1_700_000_000_000L,
        serializedAtEpochMs = 1_700_000_000_001L,
        canonicalSha256 = canonicalSha256,
        bodyJson = "{}",
    )
}
