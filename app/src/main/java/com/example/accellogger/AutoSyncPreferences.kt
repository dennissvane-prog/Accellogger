package com.example.accellogger

import android.content.Context
import android.content.SharedPreferences

data class DriveSyncConfig(
    val accountEmail: String?,
    val folderId: String?,
) {
    val isEnabled: Boolean
        get() = accountEmail != null
}

data class DriveSyncStatus(
    val state: String,
    val updatedTimeMs: Long?,
    val detail: String?,
) {
    companion object {
        const val STATE_NEVER = "never"
        const val STATE_QUEUED = "queued"
        const val STATE_RUNNING = "running"
        const val STATE_SUCCESS = "success"
        const val STATE_FAILURE = "failure"
    }
}

class AutoSyncPreferences(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun loadConfig(): DriveSyncConfig {
        return DriveSyncConfig(
            accountEmail = preferences.getString(KEY_ACCOUNT_EMAIL, null)?.takeIf { it.isNotBlank() },
            folderId = preferences.getString(KEY_FOLDER_ID, null)?.takeIf { it.isNotBlank() },
        )
    }

    fun saveAccount(accountEmail: String) {
        preferences.edit()
            .putString(KEY_ACCOUNT_EMAIL, accountEmail)
            .remove(KEY_FOLDER_ID)
            .apply()
        clearSyncedFileSizes()
        clearSyncStatus()
    }

    fun clearAccount() {
        preferences.edit()
            .remove(KEY_ACCOUNT_EMAIL)
            .remove(KEY_FOLDER_ID)
            .apply()
        clearSyncedFileSizes()
        clearSyncStatus()
    }

    fun saveFolderId(folderId: String) {
        preferences.edit().putString(KEY_FOLDER_ID, folderId).apply()
    }

    fun syncedFileSize(fileName: String): Long? {
        return preferences.getStringSet(KEY_SYNCED_FILE_SIZES, emptySet())
            ?.firstNotNullOfOrNull { entry ->
                val separatorIndex = entry.lastIndexOf('|')
                if (separatorIndex <= 0) {
                    return@firstNotNullOfOrNull null
                }
                if (entry.substring(0, separatorIndex) != fileName) {
                    return@firstNotNullOfOrNull null
                }
                entry.substring(separatorIndex + 1).toLongOrNull()
            }
    }

    fun markFileSynced(fileName: String, sizeBytes: Long) {
        val currentEntries = preferences.getStringSet(KEY_SYNCED_FILE_SIZES, emptySet()).orEmpty()
        val updatedEntries = currentEntries.filterNot { it.startsWith("$fileName|") }.toMutableSet()
        updatedEntries.add("$fileName|$sizeBytes")
        preferences.edit().putStringSet(KEY_SYNCED_FILE_SIZES, updatedEntries).apply()
    }

    fun loadSyncStatus(): DriveSyncStatus {
        val storedState = preferences.getString(KEY_SYNC_STATUS_STATE, null)
            ?.takeIf { it.isNotBlank() }
            ?: DriveSyncStatus.STATE_NEVER
        val updatedTimeMs = preferences.getLong(KEY_SYNC_STATUS_UPDATED_TIME_MS, 0L)
            .takeIf { it > 0L }
        val detail = preferences.getString(KEY_SYNC_STATUS_DETAIL, null)?.takeIf { it.isNotBlank() }
        return DriveSyncStatus(
            state = storedState,
            updatedTimeMs = updatedTimeMs,
            detail = detail,
        )
    }

    fun markSyncQueued(detail: String? = null) {
        saveSyncStatus(DriveSyncStatus.STATE_QUEUED, detail)
    }

    fun markSyncRunning(detail: String? = null) {
        saveSyncStatus(DriveSyncStatus.STATE_RUNNING, detail)
    }

    fun markSyncSuccess(detail: String? = null) {
        saveSyncStatus(DriveSyncStatus.STATE_SUCCESS, detail)
    }

    fun markSyncFailure(detail: String? = null) {
        saveSyncStatus(DriveSyncStatus.STATE_FAILURE, detail)
    }

    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun clearSyncedFileSizes() {
        preferences.edit().remove(KEY_SYNCED_FILE_SIZES).apply()
    }

    private fun clearSyncStatus() {
        preferences.edit()
            .remove(KEY_SYNC_STATUS_STATE)
            .remove(KEY_SYNC_STATUS_UPDATED_TIME_MS)
            .remove(KEY_SYNC_STATUS_DETAIL)
            .apply()
    }

    private fun saveSyncStatus(state: String, detail: String?) {
        preferences.edit()
            .putString(KEY_SYNC_STATUS_STATE, state)
            .putLong(KEY_SYNC_STATUS_UPDATED_TIME_MS, System.currentTimeMillis())
            .putString(KEY_SYNC_STATUS_DETAIL, detail)
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "auto_sync_preferences"
        private const val KEY_ACCOUNT_EMAIL = "drive_account_email"
        private const val KEY_FOLDER_ID = "drive_folder_id"
        private const val KEY_SYNCED_FILE_SIZES = "drive_synced_file_sizes"
        private const val KEY_SYNC_STATUS_STATE = "drive_sync_status_state"
        private const val KEY_SYNC_STATUS_UPDATED_TIME_MS = "drive_sync_status_updated_time_ms"
        private const val KEY_SYNC_STATUS_DETAIL = "drive_sync_status_detail"
    }
}