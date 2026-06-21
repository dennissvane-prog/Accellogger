package com.example.accellogger

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogFileManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var writer: BufferedWriter? = null
    private var writerChannel: Channel<LoggedSample>? = null
    private var currentLogItem: LogFileItem? = null
    private var writerJob: Job? = null
    @Volatile
    private var writeFailureMessage: String? = null
    private var currentLogDateKey: String? = null
    private var currentLogReference: String? = null

    fun startNewLog(): LogFileItem {
        check(writer == null) { "A log session is already active." }

        writeFailureMessage = null

        val initialFileState = openLogFile(System.currentTimeMillis())

        val channel = Channel<LoggedSample>(capacity = 2048)
        val job = scope.launch {
            var bufferedRows = 0
            var activeFileState = initialFileState
            try {
                for (sample in channel) {
                    val sampleDateKey = dayKeyFor(sample.systemTimeMs)
                    if (sampleDateKey != currentLogDateKey) {
                        activeFileState.writer.flush()
                        activeFileState.writer.close()
                        publishLogItem(activeFileState.item)
                        activeFileState = openLogFile(sample.systemTimeMs)
                        currentLogDateKey = sampleDateKey
                        writer = activeFileState.writer
                        currentLogReference = activeFileState.item.storageReference
                        currentLogItem = activeFileState.item
                        bufferedRows = 0
                    }

                    activeFileState.writer.write(sample.toCsvRow())
                    bufferedRows++
                    if (bufferedRows >= 25) {
                        activeFileState.writer.flush()
                        bufferedRows = 0
                    }
                }
                activeFileState.writer.flush()
            } catch (exception: Exception) {
                writeFailureMessage = exception.message ?: context.getString(R.string.file_write_error)
                channel.close(exception)
            } finally {
                try {
                    activeFileState.writer.close()
                } catch (_: Exception) {
                    // Ignore close failures after a write error or stop.
                }
            }
        }

        writer = initialFileState.writer
        writerChannel = channel
        currentLogItem = initialFileState.item
        currentLogReference = initialFileState.item.storageReference
        currentLogDateKey = initialFileState.dateKey
        writerJob = job
        return initialFileState.item
    }

    fun enqueueSample(sample: LoggedSample): Boolean {
        if (writeFailureMessage != null) {
            return false
        }
        return writerChannel?.trySend(sample)?.isSuccess == true
    }

    fun consumeWriteFailureMessage(): String? {
        val message = writeFailureMessage
        writeFailureMessage = null
        return message
    }

    suspend fun stopLog(): LogFileItem? = withContext(Dispatchers.IO) {
        val channel = writerChannel
        val activeWriter = writer
        val item = currentLogItem

        writerChannel = null
        currentLogItem = null
        currentLogReference = null

        channel?.close()
        writerJob?.join()
        writerJob = null

        if (writeFailureMessage != null) {
            try {
                activeWriter?.close()
            } finally {
                writer = null
            }
            publishLogItem(item)
            return@withContext item
        }

        try {
            activeWriter?.flush()
            activeWriter?.close()
        } finally {
            writer = null
        }

        publishLogItem(item)
        item
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
        if (storageReference == currentLogReference) {
            return false
        }

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

    private fun openLogFile(timestampMs: Long): LogFileState {
        return if (usesMediaStoreStorage()) {
            openMediaStoreLog(timestampMs)
        } else {
            openLegacyLog(timestampMs)
        }
    }

    private fun openMediaStoreLog(timestampMs: Long): LogFileState {
        val fileName = "accelerometer_log_${timestampForFile(timestampMs)}.csv"
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/AccelLogger/"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.DATE_MODIFIED, timestampMs / 1000L)
        }

        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException(context.getString(R.string.storage_unavailable_error))
        val outputStream = context.contentResolver.openOutputStream(uri, "w")
            ?: throw IllegalStateException(context.getString(R.string.storage_unavailable_error))
        val writer = bufferedWriter(outputStream)
        writer.write(CSV_HEADER)
        writer.newLine()

        return LogFileState(
            item = LogFileItem(
                fileName = fileName,
                storageReference = uri.toString(),
                sizeBytes = 0L,
                modifiedTimeMs = timestampMs,
            ),
            writer = writer,
            dateKey = dayKeyFor(timestampMs),
        )
    }

    @Suppress("DEPRECATION")
    private fun openLegacyLog(timestampMs: Long): LogFileState {
        val directory = context.getExternalFilesDir(null)
            ?: throw IllegalStateException(context.getString(R.string.storage_unavailable_error))
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException(context.getString(R.string.storage_unavailable_error))
        }

        val file = File(directory, "accelerometer_log_${timestampForFile(timestampMs)}.csv")
        val outputStream = FileOutputStream(file, false)
        val writer = bufferedWriter(outputStream)
        writer.write(CSV_HEADER)
        writer.newLine()

        return LogFileState(
            item = LogFileItem(
                fileName = file.name,
                storageReference = file.absolutePath,
                sizeBytes = file.length(),
                modifiedTimeMs = file.lastModified(),
            ),
            writer = writer,
            dateKey = dayKeyFor(timestampMs),
        )
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

        private fun timestampForFile(timestampMs: Long = System.currentTimeMillis()): String {
            val formatter = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS", Locale.US)
            return formatter.format(Date(timestampMs))
        }

        private fun dayKeyFor(timestampMs: Long): String {
            return Instant.ofEpochMilli(timestampMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .toString()
        }

        private data class LogFileState(
            val item: LogFileItem,
            val writer: BufferedWriter,
            val dateKey: String,
        )
    }
}
