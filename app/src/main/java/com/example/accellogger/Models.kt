package com.example.accellogger

import java.time.Instant

const val DEFAULT_SAMPLE_RATE_HZ = 50
const val METERS_PER_SECOND_SQUARED_PER_G = 9.80665
const val DEFAULT_EVENT_TRIGGER_THRESHOLD_MPS2 = 6.0
const val DEFAULT_EVENT_WINDOW_MS = 500
val DEFAULT_EVENT_TRIGGER_THRESHOLD_G =
    DEFAULT_EVENT_TRIGGER_THRESHOLD_MPS2 / METERS_PER_SECOND_SQUARED_PER_G

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
    val utcTimestamp: String
        get() = Instant.ofEpochMilli(systemTimeMs).toString()

    val magnitude: Double
        get() = kotlin.math.sqrt((x * x + y * y + z * z).toDouble())

    fun toCsvRow(): String {
        return buildString {
            append(utcTimestamp)
            append(',')
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
            append(String.format(java.util.Locale.US, "%.3f", magnitude))
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
    val eventTriggerThresholdG: Double,
    val eventWindowMs: Int,
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
            sampleRateHz = DEFAULT_SAMPLE_RATE_HZ,
            eventTriggerThresholdG = DEFAULT_EVENT_TRIGGER_THRESHOLD_G,
            eventWindowMs = DEFAULT_EVENT_WINDOW_MS,
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
    val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    val eventTriggerThresholdG: Double = DEFAULT_EVENT_TRIGGER_THRESHOLD_G,
    val eventWindowMs: Int = DEFAULT_EVENT_WINDOW_MS,
    val lastSavedFileName: String? = null,
    val lastSavedStorageReference: String? = null,
)
