package com.example.accellogger

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val logFileManager = LogFileManager(appContext)
    private val accelerometerLogger = AccelerometerLogger(
        context = appContext,
        onSample = ::onSampleReceived,
        onLiveReading = ::onLiveReading,
        onError = ::onSensorError,
    )

    private val _events = MutableSharedFlow<MainUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<MainUiEvent> = _events.asSharedFlow()

    private val _uiState = MutableStateFlow(
        MainUiState.initial(
            sensorAvailable = accelerometerLogger.hasAccelerometer(),
            sensorDetails = accelerometerLogger.sensorDetails(),
            lastLog = logFileManager.latestLog(),
        ).copy(
            statusText = if (accelerometerLogger.hasAccelerometer()) {
                appContext.getString(R.string.status_sensor_ready)
            } else {
                appContext.getString(R.string.sensor_unavailable)
            },
        ),
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var appVisible = false
    private var sampleCount = 0L
    private var loggingStartedElapsedRealtimeMs = 0L
    private var loggingStartedSensorTimestampNs: Long? = null
    private var elapsedJob: Job? = null

    fun onAppVisible() {
        appVisible = true
        refreshLastSavedLog()
        if (_uiState.value.sensorAvailable && !_uiState.value.isLogging) {
            accelerometerLogger.startLiveUpdates(_uiState.value.sampleRateHz)
        }
    }

    fun onAppHidden() {
        appVisible = false
    }

    fun setSampleRateHz(sampleRateHz: Int) {
        if (sampleRateHz <= 0) {
            return
        }

        _uiState.update { it.copy(sampleRateHz = sampleRateHz) }

        if (_uiState.value.sensorAvailable && !_uiState.value.isLogging && appVisible) {
            accelerometerLogger.startLiveUpdates(sampleRateHz)
        }
    }

    fun startLogging() {
        val state = _uiState.value
        if (!state.sensorAvailable) {
            emitError(appContext.getString(R.string.sensor_unavailable))
            return
        }
        if (state.isLogging) {
            return
        }

        try {
            logFileManager.startNewLog()
        } catch (exception: Exception) {
            emitError(exception.message ?: appContext.getString(R.string.file_create_error))
            return
        }

        sampleCount = 0L
        loggingStartedElapsedRealtimeMs = SystemClock.elapsedRealtime()
        loggingStartedSensorTimestampNs = null
        elapsedJob?.cancel()
        elapsedJob = viewModelScope.launch {
            while (true) {
                _uiState.update {
                    it.copy(
                        elapsedMs = SystemClock.elapsedRealtime() - loggingStartedElapsedRealtimeMs,
                        sampleCount = sampleCount,
                    )
                }
                delay(250L)
            }
        }

        _uiState.update {
            it.copy(
                isLogging = true,
                elapsedMs = 0L,
                sampleCount = 0L,
                statusText = appContext.getString(R.string.status_logging),
            )
        }
        accelerometerLogger.startLogging(state.sampleRateHz)
    }

    fun stopLogging() {
        if (!_uiState.value.isLogging) {
            return
        }

        viewModelScope.launch {
            stopLoggingInternal(null)
        }
    }

    private fun refreshLastSavedLog() {
        val latest = logFileManager.latestLog()
        _uiState.update {
            it.copy(
                lastSavedFileName = latest?.fileName,
                lastSavedFilePath = latest?.absolutePath,
            )
        }
    }

    private fun onSampleReceived(sample: AccelSample) {
        if (!_uiState.value.isLogging) {
            return
        }

        sampleCount += 1L
        val sessionStartSensorTimestampNs =
            loggingStartedSensorTimestampNs ?: sample.sensorTimestampNs.also {
                loggingStartedSensorTimestampNs = it
            }
        val loggedSample = LoggedSample(
            sampleIndex = sampleCount,
            elapsedMs = (sample.sensorTimestampNs - sessionStartSensorTimestampNs) / 1_000_000L,
            sensorTimestampNs = sample.sensorTimestampNs,
            systemTimeMs = sample.systemTimeMs,
            x = sample.x,
            y = sample.y,
            z = sample.z,
            accuracy = sample.accuracy,
        )

        if (!logFileManager.enqueueSample(loggedSample)) {
            viewModelScope.launch {
                emitError(
                    logFileManager.consumeWriteFailureMessage()
                        ?: appContext.getString(R.string.file_write_error),
                )
                stopLoggingInternal(null, emitSavedMessage = false)
            }
        }
    }

    private fun onLiveReading(sample: AccelSample) {
        _uiState.update {
            it.copy(
                currentSample = sample,
                sampleCount = sampleCount,
            )
        }
    }

    private fun onSensorError(message: String) {
        emitError(message)
        if (_uiState.value.isLogging) {
            viewModelScope.launch {
                stopLoggingInternal(null, emitSavedMessage = false)
            }
        }
    }

    private suspend fun stopLoggingInternal(
        infoMessage: String?,
        emitSavedMessage: Boolean = true,
    ) {
        accelerometerLogger.stop()
        elapsedJob?.cancel()
        elapsedJob = null

        val stoppedFile = try {
            logFileManager.stopLog()
        } catch (exception: Exception) {
            emitError(exception.message ?: appContext.getString(R.string.file_close_error))
            null
        }

        _uiState.update {
            it.copy(
                isLogging = false,
                elapsedMs = if (loggingStartedElapsedRealtimeMs == 0L) {
                    it.elapsedMs
                } else {
                    SystemClock.elapsedRealtime() - loggingStartedElapsedRealtimeMs
                },
                sampleCount = sampleCount,
                statusText = if (it.sensorAvailable) {
                    appContext.getString(R.string.status_idle)
                } else {
                    appContext.getString(R.string.sensor_unavailable)
                },
                lastSavedFileName = stoppedFile?.name ?: it.lastSavedFileName,
                lastSavedFilePath = stoppedFile?.absolutePath ?: it.lastSavedFilePath,
            )
        }

        loggingStartedElapsedRealtimeMs = 0L
        loggingStartedSensorTimestampNs = null

        if (appVisible && _uiState.value.sensorAvailable) {
            accelerometerLogger.startLiveUpdates(_uiState.value.sampleRateHz)
        }

        when {
            infoMessage != null -> _events.tryEmit(MainUiEvent.Info(infoMessage))
            stoppedFile != null && emitSavedMessage -> {
                _events.tryEmit(
                    MainUiEvent.Info(
                        appContext.getString(R.string.saved_file_message, stoppedFile.name),
                    ),
                )
            }
        }
    }

    private fun emitError(message: String) {
        _events.tryEmit(MainUiEvent.Error(message))
    }

    override fun onCleared() {
        accelerometerLogger.stop()
        super.onCleared()
    }
}
