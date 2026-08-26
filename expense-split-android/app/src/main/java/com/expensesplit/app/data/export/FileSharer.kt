package com.expensesplit.app.data.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.expensesplit.app.R
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps exported files in a content:// URI and hands them to the system share sheet.
 *
 * Files live in the app's cache and are exposed through a FileProvider, so no storage permission is
 * needed and the receiving app gets a time-limited read grant rather than a path on disk.
 */
@Singleton
class FileSharer @Inject constructor(
    private val context: Context,
) {

    fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun shareIntent(file: File, mimeType: String = mimeTypeFor(file)): Intent {
        val uri = uriFor(file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, context.getString(R.string.action_share))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun shareImageIntent(imageUri: Uri, text: String? = null): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, imageUri)
            text?.let { putExtra(Intent.EXTRA_TEXT, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, context.getString(R.string.action_share))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun shareTextIntent(text: String, subject: String? = null): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        return Intent.createChooser(send, context.getString(R.string.action_share))
    }

    private companion object {
        fun mimeTypeFor(file: File): String = when (file.extension.lowercase()) {
            "pdf" -> "application/pdf"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "esb" -> "application/octet-stream"
            else -> "*/*"
        }
    }
}
