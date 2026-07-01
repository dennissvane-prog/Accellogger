package com.example.accellogger

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File
import java.io.FileInputStream
import java.io.IOException

class LogSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {

    private val autoSyncPreferences = AutoSyncPreferences(appContext)
    private val logFileManager = LogFileManager(appContext)

    override suspend fun doWork(): Result {
        val config = autoSyncPreferences.loadConfig()
        val destinationTreeUri = config.destinationTreeUri ?: return Result.success()
        val destinationDirectory = DocumentFile.fromTreeUri(applicationContext, destinationTreeUri)
            ?: return Result.success()

        return try {
            val logs = logFileManager.listLogs()
            logs.forEach { logItem ->
                if (!syncLog(logItem, destinationDirectory)) {
                    return Result.retry()
                }
            }
            Result.success()
        } catch (_: SecurityException) {
            Result.success()
        } catch (_: IOException) {
            Result.retry()
        }
    }

    private fun syncLog(logItem: LogFileItem, destinationDirectory: DocumentFile): Boolean {
        val existingFile = destinationDirectory.findFile(logItem.fileName)
        if (existingFile != null && existingFile.length() == logItem.sizeBytes) {
            return true
        }

        return if (existingFile == null) {
            val destinationFile = destinationDirectory.createFile(CSV_MIME_TYPE, logItem.fileName)
                ?: return false
            copyLogContents(logItem.storageReference, destinationFile.uri)
        } else {
            replaceExistingFile(logItem, destinationDirectory, existingFile)
        }
    }

    private fun replaceExistingFile(
        logItem: LogFileItem,
        destinationDirectory: DocumentFile,
        existingFile: DocumentFile,
    ): Boolean {
        val temporaryFile = destinationDirectory.createFile(CSV_MIME_TYPE, "${logItem.fileName}.partial")
            ?: return false

        if (!copyLogContents(logItem.storageReference, temporaryFile.uri)) {
            temporaryFile.delete()
            return false
        }

        if (!existingFile.delete()) {
            temporaryFile.delete()
            return false
        }

        if (temporaryFile.renameTo(logItem.fileName)) {
            return true
        }

        val destinationFile = destinationDirectory.createFile(CSV_MIME_TYPE, logItem.fileName)
            ?: run {
                temporaryFile.delete()
                return false
            }

        val copiedFromTemporary = copyDocumentContents(temporaryFile.uri, destinationFile.uri)
        temporaryFile.delete()
        return copiedFromTemporary
    }

    private fun copyLogContents(storageReference: String, destinationUri: Uri): Boolean {
        return openLocalInputStream(storageReference)?.use { inputStream ->
            applicationContext.contentResolver.openOutputStream(destinationUri, "w")?.use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        } != null
    }

    private fun copyDocumentContents(sourceUri: Uri, destinationUri: Uri): Boolean {
        return applicationContext.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
            applicationContext.contentResolver.openOutputStream(destinationUri, "w")?.use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        } != null
    }

    private fun openLocalInputStream(storageReference: String) =
        when (val parsedUri = Uri.parse(storageReference)) {
            Uri.EMPTY -> null
            else -> if (parsedUri.scheme == "content") {
                applicationContext.contentResolver.openInputStream(parsedUri)
            } else {
                FileInputStream(File(storageReference))
            }
        }

    companion object {
        private const val CSV_MIME_TYPE = "text/csv"
    }
}