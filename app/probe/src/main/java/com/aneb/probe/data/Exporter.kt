package com.aneb.probe.data

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.core.net.toUri
import java.io.OutputStream

/**
 * 导出到 Downloads（P1-C07）：MediaStore.Downloads API（minSdk 29，无需存储权限）。
 * 只做落盘，内容构造在调用方（JSON=report_body 原文；CSV=ResultFormat.buildCsv）。
 */
object Exporter {

    internal interface ExportSink {
        fun create(): String?
        fun open(uri: String): OutputStream?
        fun finalize(uri: String): Boolean
        fun delete(uri: String): Boolean
    }

    private class ExportFailure(val code: String) : IllegalStateException(code)

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
    ): ExportOutcome = exportWithSink(
        content,
        object : ExportSink {
            private val resolver = context.contentResolver

            override fun create(): String? {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.toString()
            }

            override fun open(uri: String): OutputStream? = resolver.openOutputStream(uri.toUri())

            override fun finalize(uri: String): Boolean {
                val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                return resolver.update(uri.toUri(), values, null, null) == 1
            }

            override fun delete(uri: String): Boolean = resolver.delete(uri.toUri(), null, null) == 1
        },
    )

    /**
     * Fail-closed MediaStore lifecycle. Once a pending row exists, every later failure attempts to
     * remove it so Downloads never accumulates a zero-byte or permanently pending ANEB artifact.
     */
    internal fun exportWithSink(content: String, sink: ExportSink): ExportOutcome {
        val bytes = content.toByteArray(Charsets.UTF_8)
        var uri: String? = null
        return try {
            uri = sink.create() ?: return ExportOutcome(false, null, bytes.size, "mediastore_insert_null")
            sink.open(uri)?.use { output -> output.write(bytes) }
                ?: throw ExportFailure("open_output_stream_null")
            if (!sink.finalize(uri)) throw ExportFailure("mediastore_finalize_failed")
            ExportOutcome(true, uri, bytes.size, null)
        } catch (error: Exception) {
            val createdUri = uri
            val cleaned = createdUri != null && runCatching { sink.delete(createdUri) }.getOrDefault(false)
            val reason = (error as? ExportFailure)?.code
                ?: "${error.javaClass.simpleName}:${error.message ?: "export_failed"}"
            ExportOutcome(
                ok = false,
                uri = createdUri.takeUnless { cleaned },
                bytes = bytes.size,
                error = if (createdUri != null && !cleaned) "$reason;cleanup_failed" else reason,
            )
        }
    }
}
