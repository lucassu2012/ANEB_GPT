package com.aneb.probe.ui.prototype

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

internal object PrototypeZipShareLauncher {
    fun open(context: Context, publishedUri: String): Boolean {
        val uri = Uri.parse(publishedUri)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = ZIP_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("ANEB Prototype ZIP", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, CHOOSER_TITLE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private const val ZIP_MIME_TYPE = "application/zip"
    private const val CHOOSER_TITLE = "Share ANEB Prototype ZIP"
}
