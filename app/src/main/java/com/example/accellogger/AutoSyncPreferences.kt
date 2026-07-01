package com.example.accellogger

import android.content.Context

data class DriveSyncConfig(
    val accountEmail: String?,
    val folderId: String?,
) {
    val isEnabled: Boolean
        get() = accountEmail != null
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
    }

    fun clearAccount() {
        preferences.edit()
            .remove(KEY_ACCOUNT_EMAIL)
            .remove(KEY_FOLDER_ID)
            .apply()
        clearSyncedFileSizes()
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

    private fun clearSyncedFileSizes() {
        preferences.edit().remove(KEY_SYNCED_FILE_SIZES).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "auto_sync_preferences"
        private const val KEY_ACCOUNT_EMAIL = "drive_account_email"
        private const val KEY_FOLDER_ID = "drive_folder_id"
        private const val KEY_SYNCED_FILE_SIZES = "drive_synced_file_sizes"
    }
}