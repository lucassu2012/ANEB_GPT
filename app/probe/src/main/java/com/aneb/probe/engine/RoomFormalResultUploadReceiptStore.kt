package com.aneb.probe.engine

import androidx.room.withTransaction
import com.aneb.probe.data.AnebDatabase
import com.aneb.probe.data.FormalResultUploadReceiptEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class RoomFormalResultUploadReceiptStore(
    private val databaseProvider: () -> AnebDatabase,
    private val recordedAtEpochMs: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FormalResultUploadReceiptStore {
    override suspend fun commit(receipt: FormalResultUploadReceipt): Boolean =
        withContext(ioDispatcher) {
            try {
                val database = databaseProvider()
                database.withTransaction {
                    val dao = database.formalResultUploadReceiptDao()
                    val existing = dao.byId(receipt.resultId)
                    if (existing != null) return@withTransaction existing.matches(receipt)

                    val recordedAt = recordedAtEpochMs()
                    if (recordedAt < 0L) return@withTransaction false
                    val candidate = receipt.toEntity(recordedAt)
                    if (dao.insertIfAbsent(candidate) != -1L) {
                        true
                    } else {
                        dao.byId(receipt.resultId)?.matches(receipt) == true
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
            }
        }

    private fun FormalResultUploadReceipt.toEntity(
        recordedAt: Long,
    ): FormalResultUploadReceiptEntity = FormalResultUploadReceiptEntity(
        resultId = resultId,
        canonicalSha256 = canonicalSha256,
        recipient = recipient,
        purpose = purposeCode,
        policyVersion = policyVersion,
        terminalCode = terminalCode,
        httpCode = httpCode,
        recordedAtEpochMs = recordedAt,
    )

    private fun FormalResultUploadReceiptEntity.matches(
        receipt: FormalResultUploadReceipt,
    ): Boolean = resultId == receipt.resultId &&
        canonicalSha256 == receipt.canonicalSha256 &&
        recipient == receipt.recipient &&
        purpose == receipt.purposeCode &&
        policyVersion == receipt.policyVersion &&
        terminalCode == receipt.terminalCode &&
        httpCode == receipt.httpCode &&
        recordedAtEpochMs >= 0L
}
