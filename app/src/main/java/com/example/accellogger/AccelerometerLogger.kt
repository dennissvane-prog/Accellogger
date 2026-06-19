package com.example.accellogger

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

class AccelerometerLogger(
    context: Context,
    private val onSample: (AccelSample) -> Unit,
    private val onLiveReading: (AccelSample) -> Unit,
    private val onError: (String) -> Unit,
) : SensorEventListener {

    private companion object {
        const val GRAVITY_FILTER_ALPHA = 0.8f
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var captureSamples = false
    private var isRegistered = false
    private var liveUpdateIntervalMs = 100L
    private var lastLiveUpdateMs = 0L
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var gravityInitialized = false

    fun hasAccelerometer(): Boolean = accelerometer != null

    fun sensorDetails(): SensorDetails? {
        val sensor = accelerometer ?: return null
        return SensorDetails(
            name = sensor.name,
            vendor = sensor.vendor,
            resolution = sensor.resolution,
            maximumRange = sensor.maximumRange,
            powerMilliAmps = sensor.power,
        )
    }

    fun startLiveUpdates(sampleRateHz: Int) {
        captureSamples = false
        register(sampleRateHz)
    }

    fun startLogging(sampleRateHz: Int) {
        captureSamples = true
        register(sampleRateHz)
    }

    fun stop() {
        if (isRegistered) {
            sensorManager.unregisterListener(this)
            isRegistered = false
        }
        captureSamples = false
    }

    private fun register(sampleRateHz: Int) {
        val sensor = accelerometer
        if (sensor == null) {
            onError("No accelerometer found on this device.")
            return
        }

        if (sampleRateHz <= 0) {
            onError("Sample rate must be greater than zero.")
            return
        }

        if (isRegistered) {
            sensorManager.unregisterListener(this)
            isRegistered = false
        }

        val samplingPeriodUs = (1_000_000L / sampleRateHz).coerceAtLeast(1L).toInt()
        val registered = sensorManager.registerListener(this, sensor, samplingPeriodUs)
        if (!registered) {
            onError("Unable to register the accelerometer listener.")
            return
        }

        lastLiveUpdateMs = 0L
        gravityInitialized = false
        isRegistered = true
    }

    override fun onSensorChanged(event: SensorEvent) {
        val callbackElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos()
        val callbackWallClockMs = System.currentTimeMillis()
        val eventWallClockMs =
            callbackWallClockMs - ((callbackElapsedRealtimeNs - event.timestamp) / 1_000_000L)

        val linearAcceleration = filterGravity(event.values)

        val sample = AccelSample(
            sensorTimestampNs = event.timestamp,
            systemTimeMs = eventWallClockMs,
            x = linearAcceleration[0],
            y = linearAcceleration[1],
            z = linearAcceleration[2],
            accuracy = event.accuracy,
        )

        if (captureSamples) {
            onSample(sample)
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastLiveUpdateMs >= liveUpdateIntervalMs) {
            lastLiveUpdateMs = now
            onLiveReading(sample)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun filterGravity(values: FloatArray): FloatArray {
        if (!gravityInitialized) {
            gravityX = values[0]
            gravityY = values[1]
            gravityZ = values[2]
            gravityInitialized = true
        } else {
            gravityX = GRAVITY_FILTER_ALPHA * gravityX + (1f - GRAVITY_FILTER_ALPHA) * values[0]
            gravityY = GRAVITY_FILTER_ALPHA * gravityY + (1f - GRAVITY_FILTER_ALPHA) * values[1]
            gravityZ = GRAVITY_FILTER_ALPHA * gravityZ + (1f - GRAVITY_FILTER_ALPHA) * values[2]
        }

        return floatArrayOf(
            values[0] - gravityX,
            values[1] - gravityY,
            values[2] - gravityZ,
        )
    }
}
