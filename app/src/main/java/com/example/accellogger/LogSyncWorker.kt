package com.example.accellogger

import android.accounts.Account
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val config = autoSyncPreferences.loadConfig()
        val accountEmail = config.accountEmail ?: return@withContext Result.success()

        if (!hasValidatedInternetConnection()) {
            autoSyncPreferences.markSyncQueued(getString(R.string.sync_status_detail_waiting_for_internet))
            return@withContext Result.retry()
        }

        autoSyncPreferences.markSyncRunning(getString(R.string.sync_status_detail_running))

        val accessToken = try {
            fetchAccessToken(accountEmail)
        } catch (_: Exception) {
            // Covers both transient IOExceptions and Google auth errors (e.g. a
            // consent/token issue). Retrying is safe here: previously a non-IOException
            // permanently failed the work, so a one-time consent hiccup could look like
            // sync being stuck forever with no files ever sent.
            autoSyncPreferences.markSyncFailure(getString(R.string.sync_status_detail_retrying))
            return@withContext Result.retry()
        }

        val client = DriveRestClient(accessToken)
        val folderId = try {
            client.ensureFolder(config.folderId)
        } catch (_: Exception) {
            // A lookup/network failure must NOT fall through to creating a new folder -
            // that previously produced duplicate "AccelLogger" folders in Drive every
            // time a request hiccupped, which is why files appeared to fork into
            // separate copies instead of updating in place.
            autoSyncPreferences.markSyncFailure(getString(R.string.sync_status_detail_retrying))
            return@withContext Result.retry()
        }
        if (folderId != config.folderId) {
            autoSyncPreferences.saveFolderId(folderId)
        }

        var hadFailure = false
        var syncedFileCount = 0
        logFileManager.listLogs().forEach { logItem ->
            if (autoSyncPreferences.syncedFileSize(logItem.fileName) == logItem.sizeBytes) {
                return@forEach
            }

            val content = readLogBytes(logItem.storageReference)
            if (content == null) {
                hadFailure = true
                return@forEach
            }

            val succeeded = try {
                val existingFileId = client.findFile(logItem.fileName, folderId)
                if (existingFileId != null) {
                    client.updateFileContent(existingFileId, CSV_MIME_TYPE, content)
                } else {
                    client.uploadNewFile(logItem.fileName, folderId, CSV_MIME_TYPE, content)
                }
                true
            } catch (_: Exception) {
                // A failed lookup must not be mistaken for "file doesn't exist yet" -
                // that previously caused a brand new duplicate file to be uploaded on
                // every hiccup instead of updating the existing daily file in place.
                false
            }

            if (succeeded) {
                syncedFileCount += 1
                autoSyncPreferences.markFileSynced(logItem.fileName, logItem.sizeBytes)
            } else {
                hadFailure = true
            }
        }

        if (hadFailure) {
            autoSyncPreferences.markSyncFailure(getString(R.string.sync_status_detail_retrying))
            Result.retry()
        } else {
            val detail = if (syncedFileCount == 0) {
                getString(R.string.sync_status_detail_up_to_date)
            } else {
                getString(R.string.sync_status_detail_synced_files, syncedFileCount)
            }
            autoSyncPreferences.markSyncSuccess(detail)
            Result.success()
        }
    }

    private fun fetchAccessToken(accountEmail: String): String {
        val account = Account(accountEmail, GOOGLE_ACCOUNT_TYPE)
        return GoogleAuthUtil.getToken(applicationContext, account, "oauth2:${DriveSyncConstants.DRIVE_SCOPE_URL}")
    }

    private fun hasValidatedInternetConnection(): Boolean {
        val manager = connectivityManager ?: return false
        val activeNetwork = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun getString(resId: Int, vararg formatArgs: Any): String {
        return applicationContext.getString(resId, *formatArgs)
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
