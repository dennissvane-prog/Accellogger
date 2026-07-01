package com.example.accellogger

import android.app.Application
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val logFileManager = LogFileManager(appContext)
    private val autoSyncPreferences = AutoSyncPreferences(appContext)
    private val accelerometerLogger = AccelerometerLogger(
        context = appContext,
        onSample = {},
        onLiveReading = ::onLiveReading,
        onError = ::emitError,
    )

    private val _events = MutableSharedFlow<MainUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<MainUiEvent> = _events.asSharedFlow()
    private val autoSyncChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refreshAutoSyncConfig()
    }

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

    init {
        autoSyncPreferences.registerChangeListener(autoSyncChangeListener)
        refreshAutoSyncConfig()
        if (autoSyncPreferences.loadConfig().isEnabled) {
            LogSyncScheduler.enable(appContext)
        }
        viewModelScope.launch {
            LoggingService.state.collect(::applyLoggingState)
        }
        viewModelScope.launch {
            LoggingService.events.collect { event -> _events.emit(event) }
        }
    }

    fun onAppVisible() {
        appVisible = true
        refreshLastSavedLog()
        refreshAutoSyncConfig()
        if (_uiState.value.sensorAvailable && !_uiState.value.isLogging) {
            accelerometerLogger.startLiveUpdates(_uiState.value.sampleRateHz)
        }
    }

    fun onAppHidden() {
        appVisible = false
        if (!_uiState.value.isLogging) {
            accelerometerLogger.stop()
        }
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

    fun setEventTriggerThresholdG(eventTriggerThresholdG: Double) {
        if (eventTriggerThresholdG <= 0.0) {
            return
        }

        _uiState.update { it.copy(eventTriggerThresholdG = eventTriggerThresholdG) }
    }

    fun setEventWindowMs(eventWindowMs: Int) {
        if (eventWindowMs <= 0) {
            return
        }

        _uiState.update { it.copy(eventWindowMs = eventWindowMs) }
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

        accelerometerLogger.stop()
        ContextCompat.startForegroundService(
            appContext,
            LoggingService.startIntent(
                appContext,
                state.sampleRateHz,
                state.eventTriggerThresholdG,
                state.eventWindowMs,
            ),
        )
    }

    fun stopLogging() {
        if (!_uiState.value.isLogging) {
            return
        }

        appContext.startService(LoggingService.stopIntent(appContext))
    }

    fun configureDriveSync(accountEmail: String) {
        autoSyncPreferences.saveAccount(accountEmail)
        autoSyncPreferences.markSyncQueued(getString(R.string.sync_status_detail_queued))
        LogSyncScheduler.enable(appContext)
        refreshAutoSyncConfig()
        _events.tryEmit(MainUiEvent.Info(getString(R.string.auto_sync_configured_message, accountEmail)))
    }

    fun clearAutoSync() {
        autoSyncPreferences.clearAccount()
        LogSyncScheduler.disable(appContext)
        refreshAutoSyncConfig()
        _events.tryEmit(MainUiEvent.Info(getString(R.string.auto_sync_disabled_message)))
    }

    fun syncNow() {
        if (!autoSyncPreferences.loadConfig().isEnabled) {
            emitError(getString(R.string.auto_sync_not_configured_error))
            return
        }

        autoSyncPreferences.markSyncQueued(getString(R.string.sync_status_detail_queued))
        LogSyncScheduler.enqueueImmediate(appContext)
        _events.tryEmit(MainUiEvent.Info(getString(R.string.auto_sync_sync_now_message)))
    }

    private fun refreshLastSavedLog() {
        val latest = logFileManager.latestLog()
        _uiState.update {
            it.copy(
                lastSavedFileName = latest?.fileName,
                lastSavedStorageReference = latest?.storageReference,
            )
        }
    }

    private fun refreshAutoSyncConfig() {
        val config = autoSyncPreferences.loadConfig()
        val syncStatus = autoSyncPreferences.loadSyncStatus()
        _uiState.update {
            it.copy(
                autoSyncEnabled = config.isEnabled,
                autoSyncDestinationLabel = config.accountEmail,
                autoSyncLastStatusText = formatAutoSyncLastStatus(config.isEnabled, syncStatus),
            )
        }
    }

    private fun formatAutoSyncLastStatus(
        isEnabled: Boolean,
        syncStatus: DriveSyncStatus,
    ): String {
        if (!isEnabled) {
            return getString(R.string.auto_sync_last_status_disabled)
        }

        val detail = when (syncStatus.state) {
            DriveSyncStatus.STATE_NEVER -> return getString(R.string.auto_sync_last_status_never)
            DriveSyncStatus.STATE_QUEUED -> syncStatus.detail ?: getString(R.string.sync_status_detail_queued)
            DriveSyncStatus.STATE_RUNNING -> syncStatus.detail ?: getString(R.string.sync_status_detail_running)
            DriveSyncStatus.STATE_SUCCESS -> syncStatus.detail ?: getString(R.string.sync_status_detail_up_to_date)
            DriveSyncStatus.STATE_FAILURE -> syncStatus.detail ?: getString(R.string.sync_status_detail_retrying)
            else -> syncStatus.detail ?: getString(R.string.sync_status_detail_queued)
        }

        val statusWithTime = syncStatus.updatedTimeMs?.let { timestampMs ->
            getString(
                R.string.sync_status_detail_with_time,
                detail,
                formatTimestamp(timestampMs),
            )
        } ?: detail

        return getString(R.string.auto_sync_last_status_label, statusWithTime)
    }

    private fun formatTimestamp(timestampMs: Long): String {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(timestampMs))
    }

    private fun onLiveReading(sample: AccelSample) {
        if (_uiState.value.isLogging) {
            return
        }

        _uiState.update {
            it.copy(
                currentSample = sample,
            )
        }
    }

    private fun applyLoggingState(state: LoggingServiceState) {
        _uiState.update {
            it.copy(
                isLogging = state.isLogging,
                currentSample = state.currentSample ?: it.currentSample,
                elapsedMs = if (state.isLogging || state.elapsedMs > 0L) {
                    state.elapsedMs
                } else {
                    it.elapsedMs
                },
                sampleCount = state.sampleCount,
                sampleRateHz = state.sampleRateHz,
                eventTriggerThresholdG = state.eventTriggerThresholdG,
                eventWindowMs = state.eventWindowMs,
                statusText = when {
                    state.isLogging -> appContext.getString(R.string.status_logging)
                    !it.sensorAvailable -> appContext.getString(R.string.sensor_unavailable)
                    state.sampleCount == 0L && state.elapsedMs == 0L -> it.statusText
                    else -> appContext.getString(R.string.status_idle)
                },
                lastSavedFileName = state.lastSavedFileName ?: it.lastSavedFileName,
                lastSavedStorageReference = state.lastSavedStorageReference ?: it.lastSavedStorageReference,
            )
        }

        if (!state.isLogging && appVisible && _uiState.value.sensorAvailable) {
            accelerometerLogger.startLiveUpdates(_uiState.value.sampleRateHz)
        }
    }

    private fun emitError(message: String) {
        _events.tryEmit(MainUiEvent.Error(message))
    }

    private fun getString(resId: Int, vararg formatArgs: Any): String {
        return appContext.getString(resId, *formatArgs)
    }

    override fun onCleared() {
        autoSyncPreferences.unregisterChangeListener(autoSyncChangeListener)
        accelerometerLogger.stop()
        super.onCleared()
    }
}
