package com.example.accellogger

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogFileManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var writer: BufferedWriter? = null
    private var writerChannel: Channel<LoggedSample>? = null
    private var currentFile: File? = null
    private var writerJob: Job? = null
    @Volatile
    private var writeFailureMessage: String? = null
    private var currentLogDateKey: String? = null

    fun startNewLog(): File {
        check(writer == null) { "A log session is already active." }

        val directory = context.getExternalFilesDir(null)
            ?: throw IllegalStateException(context.getString(R.string.storage_unavailable_error))
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException(context.getString(R.string.storage_unavailable_error))
        }

        writeFailureMessage = null

        val initialFileState = openLogFile(directory, System.currentTimeMillis())

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
                        activeFileState = openLogFile(directory, sample.systemTimeMs)
                        currentLogDateKey = sampleDateKey
                        writer = activeFileState.writer
                        currentFile = activeFileState.file
                        activeLogPath = activeFileState.file.absolutePath
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
        currentFile = initialFileState.file
        activeLogPath = initialFileState.file.absolutePath
        currentLogDateKey = initialFileState.dateKey
        writerJob = job
        return initialFileState.file
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

    suspend fun stopLog(): File? = withContext(Dispatchers.IO) {
        val channel = writerChannel
        val activeWriter = writer
        val file = currentFile

        writerChannel = null
        currentFile = null
        activeLogPath = null

        channel?.close()
        writerJob?.join()
        writerJob = null

        if (writeFailureMessage != null) {
            try {
                activeWriter?.close()
            } finally {
                writer = null
            }
            return@withContext file
        }

        try {
            activeWriter?.flush()
            activeWriter?.close()
        } finally {
            writer = null
        }

        file
    }

    fun latestLog(): LogFileItem? = listLogs().firstOrNull()

    fun listLogs(): List<LogFileItem> {
        val directory = context.getExternalFilesDir(null) ?: return emptyList()
        val files = directory.listFiles { file -> file.isFile && file.extension.equals("csv", ignoreCase = true) }
            ?: return emptyList()

        return files
            .filterNot { it.absolutePath == activeLogPath }
            .sortedByDescending { it.lastModified() }
            .map {
                LogFileItem(
                    fileName = it.name,
                    absolutePath = it.absolutePath,
                    sizeBytes = it.length(),
                    modifiedTimeMs = it.lastModified(),
                )
            }
    }

    fun deleteLog(path: String): Boolean {
        if (path == activeLogPath) {
            return false
        }
        return File(path).takeIf { it.exists() }?.delete() == true
    }

    fun deleteAllLogs(): Int {
        return listLogs().count { deleteLog(it.absolutePath) }
    }

    companion object {
        private const val CSV_HEADER =
            "sample_index,elapsed_ms,event_timestamp_ns,system_time_ms,x_mps2,y_mps2,z_mps2,accuracy"
        @Volatile
        private var activeLogPath: String? = null

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
            val file: File,
            val writer: BufferedWriter,
            val dateKey: String,
        )

        private fun openLogFile(directory: File, timestampMs: Long): LogFileState {
            val file = File(directory, "accelerometer_log_${timestampForFile(timestampMs)}.csv")
            val newWriter = BufferedWriter(FileWriter(file, false))
            newWriter.write(CSV_HEADER)
            newWriter.newLine()
            return LogFileState(
                file = file,
                writer = newWriter,
                dateKey = dayKeyFor(timestampMs),
            )
        }
    }
}
