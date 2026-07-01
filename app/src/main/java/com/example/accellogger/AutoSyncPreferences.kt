package com.example.accellogger

import android.content.Context
import android.net.Uri

data class AutoSyncConfig(
    val destinationTreeUri: Uri?,
    val destinationDisplayName: String?,
) {
    val isEnabled: Boolean
        get() = destinationTreeUri != null
}

class AutoSyncPreferences(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun loadConfig(): AutoSyncConfig {
        val destinationTreeUri = preferences.getString(KEY_DESTINATION_TREE_URI, null)
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
        val destinationDisplayName = preferences.getString(KEY_DESTINATION_DISPLAY_NAME, null)
            ?.takeIf { it.isNotBlank() }

        return AutoSyncConfig(
            destinationTreeUri = destinationTreeUri,
            destinationDisplayName = destinationDisplayName,
        )
    }

    fun saveDestination(destinationTreeUri: Uri, destinationDisplayName: String) {
        preferences.edit()
            .putString(KEY_DESTINATION_TREE_URI, destinationTreeUri.toString())
            .putString(KEY_DESTINATION_DISPLAY_NAME, destinationDisplayName)
            .apply()
    }

    fun clearDestination() {
        preferences.edit()
            .remove(KEY_DESTINATION_TREE_URI)
            .remove(KEY_DESTINATION_DISPLAY_NAME)
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "auto_sync_preferences"
        private const val KEY_DESTINATION_TREE_URI = "destination_tree_uri"
        private const val KEY_DESTINATION_DISPLAY_NAME = "destination_display_name"
    }
}