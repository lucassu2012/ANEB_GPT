package com.aneb.probe.data

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore

/**
 * 导出到 Downloads（P1-C07）：MediaStore.Downloads API（minSdk 29，无需存储权限）。
 * 只做落盘，内容构造在调用方（JSON=report_body 原文；CSV=ResultFormat.buildCsv）。
 */
object Exporter {

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
        return try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return ExportOutcome(false, null, bytes.size, "mediastore_insert_null")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: return ExportOutcome(false, uri.toString(), bytes.size, "open_output_stream_null")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            ExportOutcome(true, uri.toString(), bytes.size, null)
        } catch (e: Exception) {
            ExportOutcome(false, null, bytes.size, e.toString())
        }
    }
}
