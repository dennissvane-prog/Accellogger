package com.example.accellogger

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object ShareHelper {
    fun createShareIntent(context: Context, storageReference: String): Intent {
        val parsedUri = Uri.parse(storageReference)
        val shareUri = if (parsedUri.scheme == "content") {
            parsedUri
        } else {
            FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                File(storageReference),
            )
        }

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
