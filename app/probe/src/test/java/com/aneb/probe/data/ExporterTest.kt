package com.aneb.probe.data

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ExporterTest {
    @Test
    fun `binary export closes exact pending bytes before publishing`() {
        val payload = byteArrayOf(0x00, 0x41, 0xff.toByte(), 0x0a, 0x42)
        val fileName = "aneb-prototype-evidence.zip"
        val mimeType = "application/zip"
        val gateway = RecordingPendingDownloadsGateway()
        var writerCalls = 0

        val outcome = Exporter.exportToDownloads(
            gateway = gateway,
            fileName = fileName,
            mimeType = mimeType,
        ) { output ->
            writerCalls += 1
            output.write(payload, 0, 2)
            output.write(payload[2].toInt() and 0xff)
            output.write(payload, 3, payload.size - 3)
        }

        assertEquals(listOf("insert", "open", "write", "close", "update"), gateway.operations)
        assertEquals(MediaStore.Downloads.EXTERNAL_CONTENT_URI, gateway.insertCollection)
        assertEquals(
            setOf(
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.MIME_TYPE,
                MediaStore.Downloads.RELATIVE_PATH,
                MediaStore.Downloads.IS_PENDING,
            ),
            gateway.insertValues.keySet(),
        )
        assertEquals(fileName, gateway.insertValues.getAsString(MediaStore.Downloads.DISPLAY_NAME))
        assertEquals(mimeType, gateway.insertValues.getAsString(MediaStore.Downloads.MIME_TYPE))
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/ANEB"
        assertEquals("Download/ANEB", relativePath)
        assertEquals(relativePath, gateway.insertValues.getAsString(MediaStore.Downloads.RELATIVE_PATH))
        assertEquals(1, gateway.insertValues.getAsInteger(MediaStore.Downloads.IS_PENDING).toInt())
        assertEquals(gateway.itemUri, gateway.openedUri)
        assertArrayEquals(payload, gateway.output.bytes())
        assertEquals(1, gateway.output.closeCalls)
        assertEquals(gateway.itemUri, gateway.updatedUri)
        assertEquals(setOf(MediaStore.Downloads.IS_PENDING), gateway.updateValues.keySet())
        assertEquals(0, gateway.updateValues.getAsInteger(MediaStore.Downloads.IS_PENDING).toInt())
        assertEquals(1, gateway.updateCalls)
        assertEquals(0, gateway.deleteCalls)
        assertEquals(1, writerCalls)
        assertTrue(outcome.ok)
        assertEquals(gateway.itemUri.toString(), outcome.uri)
        assertEquals(payload.size, outcome.bytes)
        assertNull(outcome.error)
        assertFalse(gateway.output.closedBeforeWrite)
    }

    @Test
    fun `insert failure returns zero bytes without cleanup`() {
        val gateway = RecordingPendingDownloadsGateway(insertReturnsNull = true)

        val outcome = export(gateway) { output -> output.write(byteArrayOf(1, 2, 3)) }

        assertFailure(outcome, bytes = 0, error = "mediastore_insert_null")
        assertEquals(listOf("insert"), gateway.operations)
        assertEquals(0, gateway.updateCalls)
        assertEquals(0, gateway.deleteCalls)
    }

    @Test
    fun `open failure deletes pending row without publishing or exposing uri`() {
        val gateway = RecordingPendingDownloadsGateway(openReturnsNull = true)

        val outcome = export(gateway) { output -> output.write(byteArrayOf(1, 2, 3)) }

        assertFailure(outcome, bytes = 0, error = "open_output_stream_null")
        assertEquals(listOf("insert", "open", "delete"), gateway.operations)
        assertEquals(gateway.itemUri, gateway.deletedUri)
        assertEquals(0, gateway.updateCalls)
        assertEquals(1, gateway.deleteCalls)
    }

    @Test
    fun `writer failure closes stream deletes pending row and reports successful prefix bytes`() {
        val failure = IOException("writer sentinel")
        val prefix = byteArrayOf(0x01, 0x02, 0x03)
        val gateway = RecordingPendingDownloadsGateway()

        val outcome = export(gateway) { output ->
            output.write(prefix)
            throw failure
        }

        assertFailure(outcome, bytes = prefix.size, error = failure.toString())
        assertArrayEquals(prefix, gateway.output.bytes())
        assertEquals(listOf("insert", "open", "write", "close", "delete"), gateway.operations)
        assertEquals(1, gateway.output.closeCalls)
        assertEquals(0, gateway.updateCalls)
        assertEquals(1, gateway.deleteCalls)
    }

    @Test
    fun `destination write and close failures delete pending row without publishing`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val writeFailure = IOException("destination write sentinel")
        val writeGateway = RecordingPendingDownloadsGateway(outputWriteFailure = writeFailure)
        val writeOutcome = export(writeGateway) { output -> output.write(payload) }
        assertFailure(writeOutcome, bytes = 0, error = writeFailure.toString())
        assertEquals(listOf("insert", "open", "write", "close", "delete"), writeGateway.operations)
        assertEquals(0, writeGateway.updateCalls)
        assertEquals(1, writeGateway.deleteCalls)

        val closeFailure = IOException("destination close sentinel")
        val closeGateway = RecordingPendingDownloadsGateway(outputCloseFailure = closeFailure)
        val closeOutcome = export(closeGateway) { output -> output.write(payload) }
        assertFailure(closeOutcome, bytes = payload.size, error = closeFailure.toString())
        assertEquals(listOf("insert", "open", "write", "close", "delete"), closeGateway.operations)
        assertEquals(0, closeGateway.updateCalls)
        assertEquals(1, closeGateway.deleteCalls)
    }

    @Test
    fun `update exception deletes pending row and preserves primary failure`() {
        val failure = IOException("update sentinel")
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val gateway = RecordingPendingDownloadsGateway(updateFailure = failure)

        val outcome = export(gateway) { output -> output.write(payload) }

        assertFailure(outcome, bytes = payload.size, error = failure.toString())
        assertEquals(listOf("insert", "open", "write", "close", "update", "delete"), gateway.operations)
        assertEquals(1, gateway.updateCalls)
        assertEquals(1, gateway.deleteCalls)
    }

    @Test
    fun `non singular publish counts fail and delete pending row`() {
        listOf(0, 2).forEach { updateResult ->
            val payload = byteArrayOf(0x01, 0x02, 0x03)
            val gateway = RecordingPendingDownloadsGateway(updateResult = updateResult)

            val outcome = export(gateway) { output -> output.write(payload) }

            assertFailure(
                outcome,
                bytes = payload.size,
                error = "mediastore_publish_count_$updateResult",
            )
            assertEquals(
                "updateResult=$updateResult",
                listOf("insert", "open", "write", "close", "update", "delete"),
                gateway.operations,
            )
            assertEquals(1, gateway.updateCalls)
            assertEquals(1, gateway.deleteCalls)
        }
    }

    @Test
    fun `writer cancellation closes stream deletes pending row and rethrows same object`() {
        val cancellation = CancellationException("cancellation sentinel")
        val gateway = RecordingPendingDownloadsGateway()

        val thrown = runCatching {
            export(gateway) { output ->
                output.write(0x41)
                throw cancellation
            }
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(listOf("insert", "open", "write", "close", "delete"), gateway.operations)
        assertEquals(1, gateway.output.closeCalls)
        assertEquals(0, gateway.updateCalls)
        assertEquals(1, gateway.deleteCalls)
    }

    @Test
    fun `cleanup failure never replaces writer update or cancellation primary failure`() {
        val deleteFailure = IOException("delete sentinel")
        val writerFailure = IOException("writer primary")
        val writerGateway = RecordingPendingDownloadsGateway(deleteFailure = deleteFailure)
        val writerOutcome = export(writerGateway) { throw writerFailure }
        assertFailure(writerOutcome, bytes = 0, error = writerFailure.toString())
        assertEquals(1, writerGateway.deleteCalls)

        val updateFailure = IOException("update primary")
        val updateGateway = RecordingPendingDownloadsGateway(
            updateFailure = updateFailure,
            deleteFailure = deleteFailure,
        )
        val updateOutcome = export(updateGateway) { output -> output.write(0x41) }
        assertFailure(updateOutcome, bytes = 1, error = updateFailure.toString())
        assertEquals(1, updateGateway.deleteCalls)

        val cancellation = CancellationException("cancellation primary")
        val cancellationGateway = RecordingPendingDownloadsGateway(deleteFailure = deleteFailure)
        val thrown = runCatching {
            export(cancellationGateway) { throw cancellation }
        }.exceptionOrNull()
        assertSame(cancellation, thrown)
        assertEquals(1, cancellationGateway.deleteCalls)
    }

    private fun export(
        gateway: PendingDownloadsGateway,
        writer: (OutputStream) -> Unit,
    ): Exporter.ExportOutcome = Exporter.exportToDownloads(
        gateway = gateway,
        fileName = "aneb-prototype-evidence.zip",
        mimeType = "application/zip",
        writer = writer,
    )

    private fun assertFailure(
        outcome: Exporter.ExportOutcome,
        bytes: Int,
        error: String,
    ) {
        assertFalse(outcome.ok)
        assertNull(outcome.uri)
        assertEquals(bytes, outcome.bytes)
        assertEquals(error, outcome.error)
    }

    private class RecordingPendingDownloadsGateway(
        private val insertReturnsNull: Boolean = false,
        private val openReturnsNull: Boolean = false,
        private val updateResult: Int = 1,
        private val updateFailure: IOException? = null,
        deleteFailure: IOException? = null,
        outputWriteFailure: IOException? = null,
        outputCloseFailure: IOException? = null,
    ) : PendingDownloadsGateway {
        val operations = mutableListOf<String>()
        val itemUri: Uri = Uri.parse("content://media/external/downloads/42")
        val output = RecordingOutputStream(
            operations = operations,
            writeFailure = outputWriteFailure,
            closeFailure = outputCloseFailure,
        )

        lateinit var insertCollection: Uri
            private set
        lateinit var insertValues: ContentValues
            private set
        lateinit var openedUri: Uri
            private set
        lateinit var updatedUri: Uri
            private set
        lateinit var updateValues: ContentValues
            private set
        var deletedUri: Uri? = null
            private set
        var updateCalls = 0
            private set
        var deleteCalls = 0
            private set

        private val deleteFailure = deleteFailure

        override fun insert(collection: Uri, values: ContentValues): Uri? {
            operations += "insert"
            insertCollection = collection
            insertValues = ContentValues(values)
            return itemUri.takeUnless { insertReturnsNull }
        }

        override fun openOutputStream(uri: Uri): OutputStream? {
            operations += "open"
            openedUri = uri
            return output.takeUnless { openReturnsNull }
        }

        override fun update(uri: Uri, values: ContentValues): Int {
            operations += "update"
            updateCalls += 1
            updatedUri = uri
            updateValues = ContentValues(values)
            updateFailure?.let { throw it }
            return updateResult
        }

        override fun delete(uri: Uri): Int {
            operations += "delete"
            deleteCalls += 1
            deletedUri = uri
            deleteFailure?.let { throw it }
            return 1
        }
    }

    private class RecordingOutputStream(
        private val operations: MutableList<String>,
        private val writeFailure: IOException?,
        private val closeFailure: IOException?,
    ) : ByteArrayOutputStream() {
        var closeCalls = 0
            private set
        var closedBeforeWrite = false
            private set

        private var writeRecorded = false

        override fun write(value: Int) {
            recordWrite()
            writeFailure?.let { throw it }
            super.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            recordWrite()
            writeFailure?.let { throw it }
            super.write(bytes, offset, length)
        }

        override fun close() {
            operations += "close"
            closeCalls += 1
            closeFailure?.let { throw it }
            super.close()
        }

        fun bytes(): ByteArray = toByteArray()

        private fun recordWrite() {
            if (!writeRecorded) {
                closedBeforeWrite = closeCalls > 0
                operations += "write"
                writeRecorded = true
            }
        }
    }
}
