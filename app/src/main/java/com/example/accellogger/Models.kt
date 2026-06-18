package com.example.accellogger

import android.hardware.SensorManager

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
    val absolutePath: String,
    val sizeBytes: Long,
    val modifiedTimeMs: Long,
)

enum class LogRateOption(
    val labelResId: Int,
    val sensorDelay: Int,
) {
    UI(R.string.sample_rate_ui, SensorManager.SENSOR_DELAY_UI),
    NORMAL(R.string.sample_rate_normal, SensorManager.SENSOR_DELAY_NORMAL),
    GAME(R.string.sample_rate_game, SensorManager.SENSOR_DELAY_GAME),
}

data class MainUiState(
    val sensorAvailable: Boolean,
    val sensorDetails: SensorDetails?,
    val currentSample: AccelSample?,
    val isLogging: Boolean,
    val sampleCount: Long,
    val elapsedMs: Long,
    val statusText: String,
    val selectedRate: LogRateOption,
    val lastSavedFileName: String?,
    val lastSavedFilePath: String?,
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
            selectedRate = LogRateOption.NORMAL,
            lastSavedFileName = lastLog?.fileName,
            lastSavedFilePath = lastLog?.absolutePath,
        )
    }
}

sealed interface MainUiEvent {
    data class Error(val message: String) : MainUiEvent
    data class Info(val message: String) : MainUiEvent
}
