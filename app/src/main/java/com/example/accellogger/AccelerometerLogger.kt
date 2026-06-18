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

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var captureSamples = false
    private var isRegistered = false
    private var liveUpdateIntervalMs = 100L
    private var lastLiveUpdateMs = 0L

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

    fun startLiveUpdates(rate: Int) {
        captureSamples = false
        register(rate)
    }

    fun startLogging(rate: Int) {
        captureSamples = true
        register(rate)
    }

    fun stop() {
        if (isRegistered) {
            sensorManager.unregisterListener(this)
            isRegistered = false
        }
        captureSamples = false
    }

    private fun register(rate: Int) {
        val sensor = accelerometer
        if (sensor == null) {
            onError("No accelerometer found on this device.")
            return
        }

        if (isRegistered) {
            sensorManager.unregisterListener(this)
            isRegistered = false
        }

        val registered = sensorManager.registerListener(this, sensor, rate)
        if (!registered) {
            onError("Unable to register the accelerometer listener.")
            return
        }

        lastLiveUpdateMs = 0L
        isRegistered = true
    }

    override fun onSensorChanged(event: SensorEvent) {
        val callbackElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos()
        val callbackWallClockMs = System.currentTimeMillis()
        val eventWallClockMs =
            callbackWallClockMs - ((callbackElapsedRealtimeNs - event.timestamp) / 1_000_000L)

        val sample = AccelSample(
            sensorTimestampNs = event.timestamp,
            systemTimeMs = eventWallClockMs,
            x = event.values[0],
            y = event.values[1],
            z = event.values[2],
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
}
