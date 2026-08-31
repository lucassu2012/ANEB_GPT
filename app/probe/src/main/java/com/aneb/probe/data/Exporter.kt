package com.aneb.probe.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream
import kotlinx.coroutines.CancellationException

internal interface PendingDownloadsGateway {
    fun insert(collection: Uri, values: ContentValues): Uri?
    fun openOutputStream(uri: Uri): OutputStream?
    fun update(uri: Uri, values: ContentValues): Int
    fun delete(uri: Uri): Int
}

/**
 * 导出到 Downloads（P1-C07）：MediaStore.Downloads API（minSdk 29，无需存储权限）。
 * 只做落盘，内容构造在调用方（JSON=report_body 原文；CSV=ResultFormat.buildCsv）。
 */
object Exporter {

    private const val MEDIASTORE_INSERT_NULL = "mediastore_insert_null"
    private const val OPEN_OUTPUT_STREAM_NULL = "open_output_stream_null"
    private const val MEDIASTORE_PUBLISH_COUNT_PREFIX = "mediastore_publish_count_"

    data class ExportOutcome(
        val ok: Boolean,
        val uri: String?,
        val bytes: Int,
        val error: String?,
    )

    fun exportToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        content: String,
    ): ExportOutcome {
        val bytes = content.toByteArray(Charsets.UTF_8)
        return exportToDownloads(
            gateway = ContentResolverPendingDownloadsGateway(context.contentResolver),
            fileName = fileName,
            mimeType = mimeType,
            failureBytes = bytes.size,
        ) { output ->
            output.write(bytes)
        }
    }

    fun exportToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        writer: (OutputStream) -> Unit,
    ): ExportOutcome = exportToDownloads(
        gateway = ContentResolverPendingDownloadsGateway(context.contentResolver),
        fileName = fileName,
        mimeType = mimeType,
        writer = writer,
    )

    internal fun exportToDownloads(
        gateway: PendingDownloadsGateway,
        fileName: String,
        mimeType: String,
        writer: (OutputStream) -> Unit,
    ): ExportOutcome = exportToDownloads(
        gateway = gateway,
        fileName = fileName,
        mimeType = mimeType,
        failureBytes = null,
        writer = writer,
    )

    private fun exportToDownloads(
        gateway: PendingDownloadsGateway,
        fileName: String,
        mimeType: String,
        failureBytes: Int?,
        writer: (OutputStream) -> Unit,
    ): ExportOutcome {
        var insertedUri: Uri? = null
        var published = false
        var countingOutput: CountingOutputStream? = null
        var primaryFailure: Throwable? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ANEB")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = gateway.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw PendingExportFailure(MEDIASTORE_INSERT_NULL)
            insertedUri = uri
            val destination = gateway.openOutputStream(uri)
                ?: throw PendingExportFailure(OPEN_OUTPUT_STREAM_NULL)
            val output = CountingOutputStream(destination)
            countingOutput = output
            output.use(writer)
            val publishValues = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            val publishCount = gateway.update(uri, publishValues)
            if (publishCount != 1) {
                throw PendingExportFailure("$MEDIASTORE_PUBLISH_COUNT_PREFIX$publishCount")
            }
            published = true
            ExportOutcome(true, uri.toString(), output.bytesWritten, null)
        } catch (cancellation: CancellationException) {
            primaryFailure = cancellation
            throw cancellation
        } catch (e: Exception) {
            primaryFailure = e
            ExportOutcome(
                ok = false,
                uri = null,
                bytes = failureBytes ?: countingOutput?.bytesWritten ?: 0,
                error = (e as? PendingExportFailure)?.stableError ?: e.toString(),
            )
        } finally {
            val uri = insertedUri
            if (uri != null && !published) {
                try {
                    gateway.delete(uri)
                } catch (cleanupFailure: Exception) {
                    primaryFailure?.takeUnless { it === cleanupFailure }?.addSuppressed(cleanupFailure)
                }
            }
        }
    }

    private class PendingExportFailure(
        val stableError: String,
    ) : Exception(stableError)

    private class ContentResolverPendingDownloadsGateway(
        private val resolver: ContentResolver,
    ) : PendingDownloadsGateway {
        override fun insert(collection: Uri, values: ContentValues): Uri? =
            resolver.insert(collection, values)

        override fun openOutputStream(uri: Uri): OutputStream? = resolver.openOutputStream(uri)

        override fun update(uri: Uri, values: ContentValues): Int =
            resolver.update(uri, values, null, null)

        override fun delete(uri: Uri): Int = resolver.delete(uri, null, null)
    }

    private class CountingOutputStream(
        private val destination: OutputStream,
    ) : OutputStream() {
        var bytesWritten = 0
            private set

        override fun write(value: Int) {
            destination.write(value)
            bytesWritten = Math.addExact(bytesWritten, 1)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            destination.write(bytes, offset, length)
            bytesWritten = Math.addExact(bytesWritten, length)
        }

        override fun flush() {
            destination.flush()
        }

        override fun close() {
            destination.close()
        }
    }
}
