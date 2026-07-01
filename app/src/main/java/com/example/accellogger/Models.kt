package com.example.accellogger

import java.time.Instant
import java.util.Locale

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

data class LoggedEventWindow(
    val samples: List<LoggedSample>,
    val geolocationRecord: EventGeolocationRecord,
)

data class EventGeolocationRecord(
    val eventTriggerTimeMs: Long,
    val eventPeakTimeMs: Long,
    val eventWindowStartTimeMs: Long,
    val eventWindowEndTimeMs: Long,
    val triggerSampleIndex: Long,
    val peakSampleIndex: Long,
    val peakMagnitudeMps2: Double,
    val gpsStatus: String,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    val provider: String?,
    val locationTimestampMs: Long?,
    val wifiStatus: String,
    val wifiObservationTimeMs: Long?,
    val wifiAccessPoints: List<WifiAccessPointEvidence>,
    val bleStatus: String,
    val bleObservationTimeMs: Long?,
    val bleBeacons: List<BleBeaconEvidence>,
) {
    fun toCsvRow(): String {
        val fields = listOf(
            instantString(eventTriggerTimeMs),
            instantString(eventPeakTimeMs),
            instantString(eventWindowStartTimeMs),
            instantString(eventWindowEndTimeMs),
            eventTriggerTimeMs.toString(),
            eventPeakTimeMs.toString(),
            eventWindowStartTimeMs.toString(),
            eventWindowEndTimeMs.toString(),
            triggerSampleIndex.toString(),
            peakSampleIndex.toString(),
            String.format(Locale.US, "%.3f", peakMagnitudeMps2),
            gpsStatus,
            instantString(locationTimestampMs),
            longString(locationTimestampMs),
            doubleString(latitude, 6),
            doubleString(longitude, 6),
            doubleString(accuracyMeters?.toDouble(), 1),
            provider.orEmpty(),
            wifiStatus,
            instantString(wifiObservationTimeMs),
            longString(wifiObservationTimeMs),
            wifiAccessPoints.joinToString("|") { it.encode() },
            bleStatus,
            instantString(bleObservationTimeMs),
            longString(bleObservationTimeMs),
            bleBeacons.joinToString("|") { it.encode() },
        )

        return fields.joinToString(",") { csvEscape(it) } + "\n"
    }

    private fun instantString(timestampMs: Long?): String {
        return timestampMs?.let { Instant.ofEpochMilli(it).toString() }.orEmpty()
    }

    private fun longString(value: Long?): String = value?.toString().orEmpty()

    private fun doubleString(value: Double?, fractionDigits: Int): String {
        return value?.let { String.format(Locale.US, "%.${fractionDigits}f", it) }.orEmpty()
    }

    private fun csvEscape(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            return value
        }

        return buildString {
            append('"')
            value.forEach { character ->
                if (character == '"') {
                    append("\"\"")
                } else {
                    append(character)
                }
            }
            append('"')
        }
    }
}

data class WifiAccessPointEvidence(
    val bssid: String,
    val frequencyMHz: Int?,
    val rssiDbm: Int?,
) {
    fun encode(): String {
        val details = buildList {
            frequencyMHz?.let { add("${it}MHz") }
            rssiDbm?.let { add("${it}dBm") }
        }

        return if (details.isEmpty()) {
            bssid
        } else {
            "$bssid@${details.joinToString("/")}"
        }
    }
}

data class BleBeaconEvidence(
    val address: String,
    val rssiDbm: Int,
) {
    fun encode(): String = "$address@${rssiDbm}dBm"
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
    val autoSyncEnabled: Boolean,
    val autoSyncDestinationLabel: String?,
    val autoSyncLastStatusText: String,
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
            autoSyncEnabled = false,
            autoSyncDestinationLabel = null,
            autoSyncLastStatusText = "",
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
