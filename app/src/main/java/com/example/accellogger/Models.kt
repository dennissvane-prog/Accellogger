package com.example.accellogger

data class AccelSample(
    val sensorTimestampNs: Long,
    val systemTimeMs: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val accuracy: Int,
) {
    val magnitude: Double
        get() = kotlin.math.sqrt((x * x + y * y + z * z).toDouble())
}

data class LoggedSample(
    val sampleIndex: Long,
    val elapsedMs: Long,
    val sensorTimestampNs: Long,
    val systemTimeMs: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val accuracy: Int,
) {
    fun toCsvRow(): String {
        return buildString {
            append(sampleIndex)
            append(',')
            append(elapsedMs)
            append(',')
            append(sensorTimestampNs)
            append(',')
            append(systemTimeMs)
            append(',')
            append(String.format(java.util.Locale.US, "%.3f", x))
            append(',')
            append(String.format(java.util.Locale.US, "%.3f", y))
            append(',')
            append(String.format(java.util.Locale.US, "%.3f", z))
            append(',')
            append(accuracy)
            append('\n')
        }
    }
}

data class SensorDetails(
    val name: String,
    val vendor: String,
    val resolution: Float,
    val maximumRange: Float,
    val powerMilliAmps: Float,
)

data class LogFileItem(
    val fileName: String,
    val storageReference: String,
    val sizeBytes: Long,
    val modifiedTimeMs: Long,
)

data class MainUiState(
    val sensorAvailable: Boolean,
    val sensorDetails: SensorDetails?,
    val currentSample: AccelSample?,
    val isLogging: Boolean,
    val sampleCount: Long,
    val elapsedMs: Long,
    val statusText: String,
    val sampleRateHz: Int,
    val lastSavedFileName: String?,
    val lastSavedStorageReference: String?,
) {
    companion object {
        fun initial(sensorAvailable: Boolean, sensorDetails: SensorDetails?, lastLog: LogFileItem?) = MainUiState(
            sensorAvailable = sensorAvailable,
            sensorDetails = sensorDetails,
            currentSample = null,
            isLogging = false,
            sampleCount = 0L,
            elapsedMs = 0L,
            statusText = "",
            sampleRateHz = 50,
            lastSavedFileName = lastLog?.fileName,
            lastSavedStorageReference = lastLog?.storageReference,
        )
    }
}

sealed interface MainUiEvent {
    data class Error(val message: String) : MainUiEvent
    data class Info(val message: String) : MainUiEvent
}

data class LoggingServiceState(
    val isLogging: Boolean = false,
    val currentSample: AccelSample? = null,
    val sampleCount: Long = 0L,
    val elapsedMs: Long = 0L,
    val sampleRateHz: Int = 0,
    val lastSavedFileName: String? = null,
    val lastSavedStorageReference: String? = null,
)
