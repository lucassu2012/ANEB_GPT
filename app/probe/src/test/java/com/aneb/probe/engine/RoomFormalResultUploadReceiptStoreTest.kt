package com.aneb.probe.engine

import androidx.room.Room
import com.aneb.probe.data.AnebDatabase
import com.aneb.probe.data.ResultEnvelopeEntity
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RoomFormalResultUploadReceiptStoreTest {
    @Test
    fun `commits an idempotent typed receipt without overwriting a conflicting outcome`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AnebDatabase::class.java,
        ).allowMainThreadQueries().build()
        val clockCalls = AtomicInteger()
        val store = RoomFormalResultUploadReceiptStore(
            databaseProvider = { database },
            recordedAtEpochMs = {
                assertEquals(1, clockCalls.incrementAndGet())
                1_700_000_000_002L
            },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val accepted = receipt(FormalResultDelivery.Accepted(201))
        val conflicting = receipt(FormalResultDelivery.Rejected(409))

        try {
            assertTrue(store.commit(accepted))
            assertTrue(store.commit(accepted))
            assertFalse(store.commit(conflicting))
            assertEquals(1, clockCalls.get())

            database.openHelper.writableDatabase.query(
                "SELECT * FROM `formal_result_upload_receipt` WHERE `resultId` = ?",
                arrayOf(RESULT_ID),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(RESULT_ID, cursor.string("resultId"))
                assertEquals(CANONICAL_SHA256, cursor.string("canonicalSha256"))
                assertEquals("aneb-node:e01-private", cursor.string("recipient"))
                assertEquals("result_submission", cursor.string("purpose"))
                assertEquals("1", cursor.string("policyVersion"))
                assertEquals("accepted", cursor.string("terminalCode"))
                assertEquals(201, cursor.int("httpCode"))
                assertEquals(1_700_000_000_002L, cursor.long("recordedAtEpochMs"))
                assertFalse(cursor.moveToNext())
            }
        } finally {
            database.close()
        }
    }

    private fun receipt(delivery: FormalResultDelivery): FormalResultUploadReceipt =
        FormalResultUploadReceipt.from(
            delivery = delivery,
            envelope = ResultEnvelopeEntity(
                runId = RESULT_ID,
                schemaVersion = "aneb-result-v2",
                testType = "token_simulation",
                startedAtEpochMs = 1_700_000_000_000L,
                serializedAtEpochMs = 1_700_000_000_001L,
                canonicalSha256 = CANONICAL_SHA256,
                bodyJson = "{}",
            ),
            scope = ResultUploadConsentScope(
                recipient = "aneb-node:e01-private",
                purpose = ResultUploadPurpose.RESULT_SUBMISSION,
                policyVersion = "1",
            ),
        )

    private fun android.database.Cursor.string(column: String): String =
        getString(getColumnIndexOrThrow(column))

    private fun android.database.Cursor.int(column: String): Int =
        getInt(getColumnIndexOrThrow(column))

    private fun android.database.Cursor.long(column: String): Long =
        getLong(getColumnIndexOrThrow(column))

    private companion object {
        const val RESULT_ID = "123e4567-e89b-47d3-a456-426614174000"
        const val CANONICAL_SHA256 =
            "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
