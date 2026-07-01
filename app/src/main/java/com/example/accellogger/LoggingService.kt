package com.example.accellogger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlin.math.ceil

class LoggingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var logFileManager: LogFileManager
    private lateinit var accelerometerLogger: AccelerometerLogger
    private lateinit var geolocationEvidenceCollector: GeolocationEvidenceCollector

    private var sampleCount = 0L
    private var loggingStartedElapsedRealtimeMs = 0L
    private var loggingStartedSensorTimestampNs: Long? = null
    private var elapsedJob: Job? = null
    private var eventWriterChannel: Channel<LoggedEventWindow>? = null
    private var eventWriterJob: Job? = null
    private val preEventSamples = ArrayDeque<LoggedSample>()
    private val activeEventSamples = mutableListOf<LoggedSample>()
    private var activeEventTriggerSample: LoggedSample? = null
    private var preEventSampleCapacity = 0
    private var postEventSampleCount = 0
    private var postEventSamplesRemaining = 0
    private var eventTriggerThresholdMps2 = DEFAULT_EVENT_TRIGGER_THRESHOLD_MPS2
    private var lastSavedFileInSession: LogFileItem? = null
    @Volatile
    private var writeFailureMessage: String? = null

    override fun onCreate() {
        super.onCreate()
        logFileManager = LogFileManager(applicationContext)
        geolocationEvidenceCollector = GeolocationEvidenceCollector(applicationContext)
        accelerometerLogger = AccelerometerLogger(
            context = applicationContext,
            onSample = ::onSampleReceived,
            onLiveReading = ::onLiveReading,
            onError = ::onSensorError,
        )
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LOGGING -> {
                val sampleRateHz = intent.getIntExtra(EXTRA_SAMPLE_RATE_HZ, 0)
                val eventTriggerThresholdG = intent.getDoubleExtra(
                    EXTRA_EVENT_TRIGGER_THRESHOLD_G,
                    DEFAULT_EVENT_TRIGGER_THRESHOLD_G,
                )
                val eventWindowMs = intent.getIntExtra(
                    EXTRA_EVENT_WINDOW_MS,
                    DEFAULT_EVENT_WINDOW_MS,
                )
                val notification = buildNotification(_state.value.sampleCount)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                startLogging(sampleRateHz, eventTriggerThresholdG, eventWindowMs)
            }

            ACTION_STOP_LOGGING -> stopLogging()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        accelerometerLogger.stop()
        geolocationEvidenceCollector.release()
        elapsedJob?.cancel()
        eventWriterChannel?.close()
        eventWriterJob?.cancel()
        serviceScope.coroutineContext.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startLogging(sampleRateHz: Int, eventTriggerThresholdG: Double, eventWindowMs: Int) {
        if (_state.value.isLogging) {
            return
        }

        if (!accelerometerLogger.hasAccelerometer()) {
            emitEvent(MainUiEvent.Error(getString(R.string.sensor_unavailable)))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        if (sampleRateHz <= 0) {
            emitEvent(MainUiEvent.Error("Sample rate must be greater than zero."))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        if (eventTriggerThresholdG <= 0.0) {
            emitEvent(MainUiEvent.Error(getString(R.string.event_threshold_error)))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        if (eventWindowMs <= 0) {
            emitEvent(MainUiEvent.Error(getString(R.string.event_window_error)))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val previousState = _state.value

        sampleCount = 0L
        loggingStartedElapsedRealtimeMs = SystemClock.elapsedRealtime()
        loggingStartedSensorTimestampNs = null
        eventTriggerThresholdMps2 = eventTriggerThresholdG * METERS_PER_SECOND_SQUARED_PER_G
        preEventSampleCapacity = samplesForEventWindow(sampleRateHz, eventWindowMs)
        postEventSampleCount = samplesForEventWindow(sampleRateHz, eventWindowMs)
        postEventSamplesRemaining = 0
        preEventSamples.clear()
        activeEventSamples.clear()
        activeEventTriggerSample = null
        lastSavedFileInSession = null
        writeFailureMessage = null
        startEventWriter()
        geolocationEvidenceCollector.start()
        elapsedJob?.cancel()
        elapsedJob = serviceScope.launch {
            while (true) {
                _state.update {
                    it.copy(
                        elapsedMs = SystemClock.elapsedRealtime() - loggingStartedElapsedRealtimeMs,
                        sampleCount = sampleCount,
                    )
                }
                updateNotification()
                delay(250L)
            }
        }

        _state.value = LoggingServiceState(
            isLogging = true,
            currentSample = null,
            sampleCount = 0L,
            elapsedMs = 0L,
            sampleRateHz = sampleRateHz,
            eventTriggerThresholdG = eventTriggerThresholdG,
            eventWindowMs = eventWindowMs,
            lastSavedFileName = previousState.lastSavedFileName,
            lastSavedStorageReference = previousState.lastSavedStorageReference,
        )

        accelerometerLogger.startLogging(sampleRateHz)
        updateNotification()
    }

    private fun onSampleReceived(sample: AccelSample) {
        if (!_state.value.isLogging || writeFailureMessage != null) {
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

        captureEventWindow(
            loggedSample = loggedSample,
            thresholdExceeded = sample.magnitude >= eventTriggerThresholdMps2,
        )
    }

    private fun captureEventWindow(loggedSample: LoggedSample, thresholdExceeded: Boolean) {
        if (activeEventSamples.isNotEmpty()) {
            activeEventSamples.add(loggedSample)
            postEventSamplesRemaining = if (thresholdExceeded) {
                postEventSampleCount
            } else {
                postEventSamplesRemaining - 1
            }
            if (postEventSamplesRemaining <= 0) {
                flushActiveEventWindow(stopOnFailure = true)
            }
        } else if (thresholdExceeded) {
            if (preEventSamples.isNotEmpty()) {
                activeEventSamples.addAll(preEventSamples)
            }
            activeEventSamples.add(loggedSample)
            activeEventTriggerSample = loggedSample
            postEventSamplesRemaining = postEventSampleCount
        }

        preEventSamples.addLast(loggedSample)
        while (preEventSamples.size > preEventSampleCapacity) {
            preEventSamples.removeFirst()
        }
    }

    private fun flushActiveEventWindow(stopOnFailure: Boolean): Boolean {
        if (activeEventSamples.isEmpty()) {
            return true
        }

        val eventSamples = activeEventSamples.toList()
        val triggerSample = activeEventTriggerSample ?: eventSamples.first()
        activeEventSamples.clear()
        activeEventTriggerSample = null
        postEventSamplesRemaining = 0

        val enqueued = enqueueEventWindow(
            LoggedEventWindow(
                samples = eventSamples,
                geolocationRecord = geolocationEvidenceCollector.createEventRecord(
                    eventSamples = eventSamples,
                    triggerSample = triggerSample,
                ),
            ),
        )
        if (!enqueued && stopOnFailure) {
            serviceScope.launch {
                emitEvent(
                    MainUiEvent.Error(
                        writeFailureMessage ?: getString(R.string.file_write_error),
                    ),
                )
                stopLoggingInternal(emitSavedMessage = false)
            }
        }

        return enqueued
    }

    private fun enqueueEventWindow(eventWindow: LoggedEventWindow): Boolean {
        if (writeFailureMessage != null) {
            return false
        }

        val sendResult = eventWriterChannel?.trySend(eventWindow)
        if (sendResult?.isSuccess == true) {
            return true
        }

        writeFailureMessage = writeFailureMessage ?: getString(R.string.file_write_error)
        return false
    }

    private fun startEventWriter() {
        val channel = Channel<LoggedEventWindow>(capacity = EVENT_WRITE_CHANNEL_CAPACITY)
        eventWriterChannel = channel
        eventWriterJob = serviceScope.launch(Dispatchers.IO) {
            try {
                for (eventWindow in channel) {
                    val savedFile = logFileManager.appendEventWindow(eventWindow) ?: continue
                    withContext(Dispatchers.Main.immediate) {
                        lastSavedFileInSession = savedFile
                        _state.update {
                            it.copy(
                                lastSavedFileName = savedFile.fileName,
                                lastSavedStorageReference = savedFile.storageReference,
                            )
                        }
                    }
                }
            } catch (exception: Exception) {
                writeFailureMessage = exception.message ?: getString(R.string.file_write_error)
                serviceScope.launch {
                    emitEvent(
                        MainUiEvent.Error(
                            writeFailureMessage ?: getString(R.string.file_write_error),
                        ),
                    )
                    stopLoggingInternal(emitSavedMessage = false)
                }
            }
        }
    }

    private fun onLiveReading(sample: AccelSample) {
        _state.update {
            it.copy(
                currentSample = sample,
                sampleCount = sampleCount,
            )
        }
    }

    private fun onSensorError(message: String) {
        emitEvent(MainUiEvent.Error(message))
        if (_state.value.isLogging) {
            serviceScope.launch {
                stopLoggingInternal(emitSavedMessage = false)
            }
        }
    }

    private fun stopLogging(emitSavedMessage: Boolean = true) {
        serviceScope.launch {
            stopLoggingInternal(emitSavedMessage)
        }
    }

    private suspend fun stopLoggingInternal(emitSavedMessage: Boolean) {
        if (!_state.value.isLogging) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        accelerometerLogger.stop()
        geolocationEvidenceCollector.stop()
        elapsedJob?.cancel()
        elapsedJob = null

        if (writeFailureMessage == null) {
            flushActiveEventWindow(stopOnFailure = false)
        } else {
            activeEventSamples.clear()
            activeEventTriggerSample = null
            postEventSamplesRemaining = 0
        }

        val channel = eventWriterChannel
        eventWriterChannel = null
        channel?.close()
        val writerJob = eventWriterJob
        eventWriterJob = null
        writerJob?.join()

        val finalWriteFailureMessage = writeFailureMessage
        val shouldEmitSavedMessage = emitSavedMessage && finalWriteFailureMessage == null
        if (finalWriteFailureMessage != null && emitSavedMessage) {
            emitEvent(MainUiEvent.Error(finalWriteFailureMessage))
        }

        val stoppedFile = lastSavedFileInSession

        val finalElapsedMs = if (loggingStartedElapsedRealtimeMs == 0L) {
            _state.value.elapsedMs
        } else {
            SystemClock.elapsedRealtime() - loggingStartedElapsedRealtimeMs
        }

        _state.value = LoggingServiceState(
            isLogging = false,
            currentSample = _state.value.currentSample,
            sampleCount = sampleCount,
            elapsedMs = finalElapsedMs,
            sampleRateHz = _state.value.sampleRateHz,
            eventTriggerThresholdG = _state.value.eventTriggerThresholdG,
            eventWindowMs = _state.value.eventWindowMs,
            lastSavedFileName = stoppedFile?.fileName ?: _state.value.lastSavedFileName,
            lastSavedStorageReference = stoppedFile?.storageReference ?: _state.value.lastSavedStorageReference,
        )

        loggingStartedElapsedRealtimeMs = 0L
        loggingStartedSensorTimestampNs = null
        preEventSamples.clear()
        activeEventSamples.clear()
        activeEventTriggerSample = null
        preEventSampleCapacity = 0
        postEventSampleCount = 0
        postEventSamplesRemaining = 0
        lastSavedFileInSession = null
        writeFailureMessage = null

        if (stoppedFile != null && shouldEmitSavedMessage) {
            emitEvent(MainUiEvent.Info(getString(R.string.saved_file_message, stoppedFile.fileName)))
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun emitEvent(event: MainUiEvent) {
        _events.tryEmit(event)
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildNotification(sampleCount))
    }

    private fun buildNotification(currentSampleCount: Long): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(getString(R.string.logging_notification_title))
            .setContentText(getString(R.string.logging_notification_text, currentSampleCount))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.logging_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val ACTION_START_LOGGING = "com.example.accellogger.action.START_LOGGING"
        private const val ACTION_STOP_LOGGING = "com.example.accellogger.action.STOP_LOGGING"
        private const val EXTRA_SAMPLE_RATE_HZ = "com.example.accellogger.extra.SAMPLE_RATE_HZ"
        private const val EXTRA_EVENT_TRIGGER_THRESHOLD_G = "com.example.accellogger.extra.EVENT_TRIGGER_THRESHOLD_G"
        private const val EXTRA_EVENT_WINDOW_MS = "com.example.accellogger.extra.EVENT_WINDOW_MS"
        private const val NOTIFICATION_CHANNEL_ID = "accel_logger_background_logging"
        private const val NOTIFICATION_ID = 1001
        private const val EVENT_WRITE_CHANNEL_CAPACITY = 16

        private val _state = MutableStateFlow(LoggingServiceState())
        val state: StateFlow<LoggingServiceState> = _state.asStateFlow()

        private val _events = MutableSharedFlow<MainUiEvent>(extraBufferCapacity = 4)
        val events: SharedFlow<MainUiEvent> = _events.asSharedFlow()

        fun startIntent(
            context: Context,
            sampleRateHz: Int,
            eventTriggerThresholdG: Double,
            eventWindowMs: Int,
        ): Intent {
            return Intent(context, LoggingService::class.java).apply {
                action = ACTION_START_LOGGING
                putExtra(EXTRA_SAMPLE_RATE_HZ, sampleRateHz)
                putExtra(EXTRA_EVENT_TRIGGER_THRESHOLD_G, eventTriggerThresholdG)
                putExtra(EXTRA_EVENT_WINDOW_MS, eventWindowMs)
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, LoggingService::class.java).apply {
                action = ACTION_STOP_LOGGING
            }
        }

        private fun samplesForEventWindow(sampleRateHz: Int, eventWindowMs: Int): Int {
            return ceil(sampleRateHz.toDouble() * eventWindowMs.toDouble() / 1000.0)
                .toInt()
                .coerceAtLeast(1)
        }
    }
}