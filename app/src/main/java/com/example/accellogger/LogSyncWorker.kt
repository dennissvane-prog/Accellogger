package com.example.accellogger

import android.accounts.Account
import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.auth.GoogleAuthUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException

class LogSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {

    private val autoSyncPreferences = AutoSyncPreferences(appContext)
    private val logFileManager = LogFileManager(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val config = autoSyncPreferences.loadConfig()
        val accountEmail = config.accountEmail ?: return@withContext Result.success()

        val accessToken = try {
            fetchAccessToken(accountEmail)
        } catch (_: IOException) {
            return@withContext Result.retry()
        } catch (_: Exception) {
            return@withContext Result.failure()
        }

        val client = DriveRestClient(accessToken)
        val folderId = client.ensureFolder(config.folderId) ?: return@withContext Result.retry()
        if (folderId != config.folderId) {
            autoSyncPreferences.saveFolderId(folderId)
        }

        var hadFailure = false
        logFileManager.listLogs().forEach { logItem ->
            if (autoSyncPreferences.syncedFileSize(logItem.fileName) == logItem.sizeBytes) {
                return@forEach
            }

            val content = readLogBytes(logItem.storageReference)
            if (content == null) {
                hadFailure = true
                return@forEach
            }

            val existingFileId = client.findFile(logItem.fileName, folderId)
            val succeeded = if (existingFileId != null) {
                client.updateFileContent(existingFileId, CSV_MIME_TYPE, content)
            } else {
                client.uploadNewFile(logItem.fileName, folderId, CSV_MIME_TYPE, content) != null
            }

            if (succeeded) {
                autoSyncPreferences.markFileSynced(logItem.fileName, logItem.sizeBytes)
            } else {
                hadFailure = true
            }
        }

        if (hadFailure) Result.retry() else Result.success()
    }

    private fun fetchAccessToken(accountEmail: String): String {
        val account = Account(accountEmail, GOOGLE_ACCOUNT_TYPE)
        return GoogleAuthUtil.getToken(applicationContext, account, "oauth2:${DriveSyncConstants.DRIVE_SCOPE_URL}")
    }

    private fun readLogBytes(storageReference: String): ByteArray? {
        return try {
            when (val parsedUri = Uri.parse(storageReference)) {
                Uri.EMPTY -> null
                else -> if (parsedUri.scheme == "content") {
                    applicationContext.contentResolver.openInputStream(parsedUri)?.use { it.readBytes() }
                } else {
                    FileInputStream(File(storageReference)).use { it.readBytes() }
                }
            }
        } catch (_: IOException) {
            null
        }
    }

    companion object {
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
        private const val CSV_MIME_TYPE = "text/csv"
    }
}
