package com.example.accellogger

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId

class LogFileManager(private val context: Context) {

    suspend fun appendEventSamples(eventSamples: List<LoggedSample>): LogFileItem? =
        withContext(Dispatchers.IO) {
            if (eventSamples.isEmpty()) {
                return@withContext null
            }

            var latestItem: LogFileItem? = null
            eventSamples
                .groupBy { dayKeyFor(it.systemTimeMs) }
                .values
                .forEach { samplesForDay ->
                    latestItem = appendSamplesToDailyLog(
                        timestampMs = samplesForDay.first().systemTimeMs,
                        samples = samplesForDay,
                    )
                }

            latestItem
        }

    fun latestLog(): LogFileItem? = listLogs().firstOrNull()

    fun listLogs(): List<LogFileItem> {
        return if (usesMediaStoreStorage()) {
            listMediaStoreLogs()
        } else {
            listLegacyLogs()
        }
    }

    fun deleteLog(storageReference: String): Boolean {
        val uri = Uri.parse(storageReference)
        return if (uri.scheme == "content") {
            context.contentResolver.delete(uri, null, null) > 0
        } else {
            File(storageReference).takeIf { it.exists() }?.delete() == true
        }
    }

    fun deleteAllLogs(): Int {
        return listLogs().count { deleteLog(it.storageReference) }
    }

    private fun usesMediaStoreStorage(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    private fun appendSamplesToDailyLog(timestampMs: Long, samples: List<LoggedSample>): LogFileItem {
        return if (usesMediaStoreStorage()) {
            appendMediaStoreLog(timestampMs, samples)
        } else {
            appendLegacyLog(timestampMs, samples)
        }
    }

    private fun appendMediaStoreLog(timestampMs: Long, samples: List<LoggedSample>): LogFileItem {
        val fileName = dailyFileName(timestampMs)
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/AccelLogger/"
        val existingItem = findMediaStoreLog(fileName, relativePath)
        val uri = existingItem?.let { Uri.parse(it.storageReference) }
            ?: createMediaStoreLog(fileName, relativePath, timestampMs)
        val outputStream = context.contentResolver.openOutputStream(
            uri,
            if (existingItem == null) "w" else "wa",
        )
            ?: throw IllegalStateException(context.getString(R.string.storage_unavailable_error))

        bufferedWriter(outputStream).use { writer ->
            writeHeaderIfNeeded(writer, existingItem?.sizeBytes ?: 0L)
            samples.forEach { writer.write(it.toCsvRow()) }
            writer.flush()
        }

        val fallbackItem = LogFileItem(
            fileName = fileName,
            storageReference = uri.toString(),
            sizeBytes = existingItem?.sizeBytes ?: 0L,
            modifiedTimeMs = System.currentTimeMillis(),
        )
        publishLogItem(fallbackItem)
        return queryMediaStoreLog(uri) ?: fallbackItem
    }

    @Suppress("DEPRECATION")
    private fun appendLegacyLog(timestampMs: Long, samples: List<LoggedSample>): LogFileItem {
        val directory = context.getExternalFilesDir(null)
            ?: throw IllegalStateException(context.getString(R.string.storage_unavailable_error))
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException(context.getString(R.string.storage_unavailable_error))
        }

        val file = File(directory, dailyFileName(timestampMs))
        val existingSizeBytes = if (file.exists()) file.length() else 0L

        FileOutputStream(file, true).use { outputStream ->
            bufferedWriter(outputStream).use { writer ->
                writeHeaderIfNeeded(writer, existingSizeBytes)
                samples.forEach { writer.write(it.toCsvRow()) }
                writer.flush()
            }
        }

        return LogFileItem(
            fileName = file.name,
            storageReference = file.absolutePath,
            sizeBytes = file.length(),
            modifiedTimeMs = file.lastModified(),
        )
    }

    private fun writeHeaderIfNeeded(writer: BufferedWriter, existingSizeBytes: Long) {
        if (existingSizeBytes == 0L) {
            writer.write(CSV_HEADER)
            writer.newLine()
        }
    }

    private fun createMediaStoreLog(fileName: String, relativePath: String, timestampMs: Long): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.DATE_MODIFIED, timestampMs / 1000L)
        }

        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException(context.getString(R.string.storage_unavailable_error))
    }

    private fun findMediaStoreLog(fileName: String, relativePath: String): LogFileItem? {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val selection =
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(fileName, relativePath)
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        return context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return@use null
            }

            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
            val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                ?: return@use null
            val sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
            val modifiedTimeMs =
                cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)) * 1000L

            LogFileItem(
                fileName = name,
                storageReference = Uri.withAppendedPath(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    id.toString(),
                ).toString(),
                sizeBytes = sizeBytes,
                modifiedTimeMs = modifiedTimeMs,
            )
        }
    }

    private fun queryMediaStoreLog(uri: Uri): LogFileItem? {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )

        return context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return@use null
            }

            val fileName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                ?: return@use null
            val sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
            val modifiedTimeMs =
                cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)) * 1000L

            LogFileItem(
                fileName = fileName,
                storageReference = uri.toString(),
                sizeBytes = sizeBytes,
                modifiedTimeMs = modifiedTimeMs,
            )
        }
    }

    private fun listMediaStoreLogs(): List<LogFileItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        return context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val relativePathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)

            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val fileName = cursor.getString(nameColumn) ?: continue
                    val relativePath = cursor.getString(relativePathColumn).orEmpty()
                    val sizeBytes = cursor.getLong(sizeColumn)
                    val modifiedTimeMs = cursor.getLong(modifiedColumn) * 1000L
                    val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())

                    val isOurLog = fileName.startsWith("accelerometer_log_") && fileName.endsWith(".csv", ignoreCase = true) && relativePath.contains("AccelLogger")
                    if (isOurLog) {
                        add(
                            LogFileItem(
                                fileName = fileName,
                                storageReference = uri.toString(),
                                sizeBytes = sizeBytes,
                                modifiedTimeMs = modifiedTimeMs,
                            ),
                        )
                    }
                }
            }
        } ?: emptyList()
    }

    @Suppress("DEPRECATION")
    private fun listLegacyLogs(): List<LogFileItem> {
        val directory = context.getExternalFilesDir(null) ?: return emptyList()
        val files = directory.listFiles { file -> file.isFile && file.extension.equals("csv", ignoreCase = true) }
            ?: return emptyList()

        return files
            .sortedByDescending { it.lastModified() }
            .map {
                LogFileItem(
                    fileName = it.name,
                    storageReference = it.absolutePath,
                    sizeBytes = it.length(),
                    modifiedTimeMs = it.lastModified(),
                )
            }
    }

    private fun publishLogItem(item: LogFileItem?) {
        if (item == null) {
            return
        }

        val parsedUri = Uri.parse(item.storageReference)
        if (parsedUri.scheme != "content") {
            return
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
        }
        context.contentResolver.update(parsedUri, values, null, null)
    }

    private fun bufferedWriter(outputStream: OutputStream): BufferedWriter {
        return BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8))
    }

    companion object {
        private const val CSV_HEADER =
            "sample_index,elapsed_ms,event_timestamp_ns,system_time_ms,x_mps2,y_mps2,z_mps2,accuracy"

        private fun dailyFileName(timestampMs: Long): String {
            return "accelerometer_log_${dayKeyFor(timestampMs)}.csv"
        }

        private fun dayKeyFor(timestampMs: Long): String {
            return Instant.ofEpochMilli(timestampMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .toString()
        }
    }
}
