package com.example.accellogger

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.ScanResult as WifiScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GeolocationEvidenceCollector(private val context: Context) {

    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var latestLocation: Location? = null

    @Volatile
    private var latestWifiObservationTimeMs: Long? = null

    @Volatile
    private var latestWifiAccessPoints: List<WifiAccessPointEvidence> = emptyList()

    @Volatile
    private var latestBleObservationTimeMs: Long? = null

    private var wifiRefreshJob: Job? = null
    private var bleScanStarted = false
    private var locationUpdatesStarted = false
    private val recentBleObservations = LinkedHashMap<String, BleBeaconObservation>()

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            latestLocation = location
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            recordBleObservation(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::recordBleObservation)
        }
    }

    fun start() {
        if (hasLocationPermission()) {
            seedLastKnownLocation()
            startLocationUpdates()
        }

        refreshWifiEvidence()
        ensureBleScanStarted()

        if (wifiRefreshJob == null) {
            wifiRefreshJob = scope.launch {
                while (isActive) {
                    refreshWifiEvidence()
                    pruneBleObservations(System.currentTimeMillis())
                    ensureBleScanStarted()
                    delay(WIFI_REFRESH_INTERVAL_MS)
                }
            }
        }
    }

    fun stop() {
        wifiRefreshJob?.cancel()
        wifiRefreshJob = null
        stopLocationUpdates()
        stopBleScan()
        latestLocation = null
        latestWifiObservationTimeMs = null
        latestWifiAccessPoints = emptyList()
        latestBleObservationTimeMs = null
        synchronized(recentBleObservations) {
            recentBleObservations.clear()
        }
    }

    fun release() {
        stop()
        scope.cancel()
    }

    fun createEventRecord(
        eventSamples: List<LoggedSample>,
        triggerSample: LoggedSample,
    ): EventGeolocationRecord {
        val peakSample = eventSamples.maxByOrNull { it.magnitude } ?: triggerSample
        val startSample = eventSamples.firstOrNull() ?: triggerSample
        val endSample = eventSamples.lastOrNull() ?: triggerSample
        val location = latestLocation.takeIf { hasLocationPermission() }
        val wifiAccessPoints = latestWifiAccessPoints.takeIf { hasWifiPermissions() }.orEmpty()
        val wifiObservedAtMs = latestWifiObservationTimeMs.takeIf { hasWifiPermissions() }

        pruneBleObservations(triggerSample.systemTimeMs)
        val bleBeacons = if (hasBleScanPermission()) {
            synchronized(recentBleObservations) {
                recentBleObservations.values
                    .sortedByDescending { it.rssiDbm }
                    .map { BleBeaconEvidence(address = it.address, rssiDbm = it.rssiDbm) }
            }
        } else {
            emptyList()
        }

        val bleObservedAtMs = latestBleObservationTimeMs.takeIf { hasBleScanPermission() }

        return EventGeolocationRecord(
            eventTriggerTimeMs = triggerSample.systemTimeMs,
            eventPeakTimeMs = peakSample.systemTimeMs,
            eventWindowStartTimeMs = startSample.systemTimeMs,
            eventWindowEndTimeMs = endSample.systemTimeMs,
            triggerSampleIndex = triggerSample.sampleIndex,
            peakSampleIndex = peakSample.sampleIndex,
            peakMagnitudeMps2 = peakSample.magnitude,
            gpsStatus = gpsStatus(location),
            latitude = location?.latitude,
            longitude = location?.longitude,
            accuracyMeters = location?.accuracy,
            provider = location?.provider,
            locationTimestampMs = location?.time,
            wifiStatus = wifiStatus(wifiAccessPoints),
            wifiObservationTimeMs = wifiObservedAtMs,
            wifiAccessPoints = wifiAccessPoints,
            bleStatus = bleStatus(bleBeacons),
            bleObservationTimeMs = bleObservedAtMs,
            bleBeacons = bleBeacons,
        )
    }

    private fun seedLastKnownLocation() {
        val manager = locationManager ?: return
        val latestKnownLocation = LAST_KNOWN_LOCATION_PROVIDERS
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }

        if (latestKnownLocation != null) {
            latestLocation = latestKnownLocation
        }
    }

    private fun startLocationUpdates() {
        if (locationUpdatesStarted || !hasLocationPermission()) {
            return
        }

        val manager = locationManager ?: return
        ACTIVE_LOCATION_PROVIDERS.forEach { provider ->
            val providerEnabled = runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
            if (!providerEnabled) {
                return@forEach
            }

            val started = runCatching {
                manager.requestLocationUpdates(
                    provider,
                    LOCATION_UPDATE_INTERVAL_MS,
                    0f,
                    locationListener,
                    Looper.getMainLooper(),
                )
            }.isSuccess

            if (started) {
                locationUpdatesStarted = true
            }
        }
    }

    private fun stopLocationUpdates() {
        if (!locationUpdatesStarted) {
            return
        }

        runCatching { locationManager?.removeUpdates(locationListener) }
        locationUpdatesStarted = false
    }

    private fun refreshWifiEvidence() {
        val manager = wifiManager ?: return
        if (!hasWifiPermissions()) {
            latestWifiObservationTimeMs = null
            latestWifiAccessPoints = emptyList()
            return
        }

        val observation = buildWifiObservation(manager)
        latestWifiObservationTimeMs = observation.observedAtMs
        latestWifiAccessPoints = observation.accessPoints

        runCatching {
            if (manager.isWifiEnabled) {
                manager.startScan()
            }
        }
    }

    private fun buildWifiObservation(manager: WifiManager): WifiObservation {
        val nowWallClockMs = System.currentTimeMillis()
        val nowElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos()
        val accessPointsByBssid = LinkedHashMap<String, WifiAccessPointEvidence>()
        var latestObservedAtMs: Long? = null

        val scanResults = runCatching { manager.scanResults }.getOrNull().orEmpty()
        scanResults
            .sortedByDescending { it.level }
            .forEach { result ->
                val bssid = result.BSSID?.takeIf(::isUsableBssid) ?: return@forEach
                accessPointsByBssid[bssid] = WifiAccessPointEvidence(
                    bssid = bssid,
                    frequencyMHz = result.frequency.takeIf { it > 0 },
                    rssiDbm = result.level,
                )
                val observedAtMs = wifiObservationTimeMs(result, nowWallClockMs, nowElapsedRealtimeNs)
                if (observedAtMs != null) {
                    latestObservedAtMs = maxOf(latestObservedAtMs ?: observedAtMs, observedAtMs)
                }
            }

        val connectedBssid = runCatching { manager.connectionInfo?.bssid }
            .getOrNull()
            ?.takeIf(::isUsableBssid)
        if (connectedBssid != null && connectedBssid !in accessPointsByBssid) {
            accessPointsByBssid[connectedBssid] = WifiAccessPointEvidence(
                bssid = connectedBssid,
                frequencyMHz = null,
                rssiDbm = null,
            )
            latestObservedAtMs = latestObservedAtMs ?: nowWallClockMs
        }

        return WifiObservation(
            observedAtMs = latestObservedAtMs,
            accessPoints = accessPointsByBssid.values.toList(),
        )
    }

    private fun ensureBleScanStarted() {
        if (bleScanStarted || !hasBleScanPermission()) {
            return
        }

        val scanner = runCatching { bluetoothManager?.adapter?.bluetoothLeScanner }.getOrNull() ?: return
        val started = runCatching {
            scanner.startScan(
                emptyList(),
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .build(),
                bleScanCallback,
            )
        }.isSuccess

        if (started) {
            bleScanStarted = true
        }
    }

    private fun stopBleScan() {
        if (!bleScanStarted) {
            return
        }

        runCatching {
            bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(bleScanCallback)
        }
        bleScanStarted = false
    }

    private fun recordBleObservation(result: ScanResult) {
        val address = result.device.address.takeIf(::isUsableBssid) ?: return
        val observedAtMs = wallClockFromElapsedRealtimeNanos(result.timestampNanos)
        val observation = BleBeaconObservation(
            address = address,
            rssiDbm = result.rssi,
            observedAtMs = observedAtMs,
        )

        synchronized(recentBleObservations) {
            recentBleObservations[address] = observation
        }
        latestBleObservationTimeMs = observedAtMs
    }

    private fun pruneBleObservations(referenceTimeMs: Long) {
        synchronized(recentBleObservations) {
            val iterator = recentBleObservations.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (referenceTimeMs - entry.value.observedAtMs > BLE_RETENTION_MS) {
                    iterator.remove()
                }
            }

            latestBleObservationTimeMs = recentBleObservations.values
                .maxOfOrNull { it.observedAtMs }
        }
    }

    private fun gpsStatus(location: Location?): String {
        return when {
            !hasLocationPermission() -> STATUS_PERMISSION_MISSING
            !isAnyLocationProviderEnabled() -> STATUS_PROVIDER_DISABLED
            location == null -> STATUS_UNAVAILABLE
            else -> STATUS_AVAILABLE
        }
    }

    private fun wifiStatus(accessPoints: List<WifiAccessPointEvidence>): String {
        val manager = wifiManager
        return when {
            manager == null -> STATUS_UNSUPPORTED
            !hasWifiPermissions() -> STATUS_PERMISSION_MISSING
            accessPoints.isNotEmpty() -> STATUS_AVAILABLE
            runCatching { manager.isWifiEnabled }.getOrDefault(false) -> STATUS_UNAVAILABLE
            else -> STATUS_WIFI_DISABLED
        }
    }

    private fun bleStatus(beacons: List<BleBeaconEvidence>): String {
        return when {
            bluetoothManager?.adapter == null -> STATUS_UNSUPPORTED
            !hasBleScanPermission() -> STATUS_PERMISSION_MISSING
            beacons.isNotEmpty() -> STATUS_AVAILABLE
            else -> STATUS_UNAVAILABLE
        }
    }

    private fun hasLocationPermission(): Boolean {
        return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun hasWifiPermissions(): Boolean {
        if (!hasLocationPermission()) {
            return false
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            true
        }
    }

    private fun hasBleScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            hasLocationPermission()
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun isAnyLocationProviderEnabled(): Boolean {
        val manager = locationManager ?: return false
        return ACTIVE_LOCATION_PROVIDERS.any { provider ->
            runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        }
    }

    private fun wifiObservationTimeMs(
        result: WifiScanResult,
        nowWallClockMs: Long,
        nowElapsedRealtimeNs: Long,
    ): Long? {
        val timestampUs = result.timestamp.takeIf { it > 0L } ?: return null
        return wallClockFromElapsedRealtimeNanos(timestampUs * 1_000L, nowWallClockMs, nowElapsedRealtimeNs)
    }

    private fun wallClockFromElapsedRealtimeNanos(observedElapsedRealtimeNs: Long): Long {
        return wallClockFromElapsedRealtimeNanos(
            observedElapsedRealtimeNs = observedElapsedRealtimeNs,
            nowWallClockMs = System.currentTimeMillis(),
            nowElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
        )
    }

    private fun wallClockFromElapsedRealtimeNanos(
        observedElapsedRealtimeNs: Long,
        nowWallClockMs: Long,
        nowElapsedRealtimeNs: Long,
    ): Long {
        val deltaNs = (nowElapsedRealtimeNs - observedElapsedRealtimeNs).coerceAtLeast(0L)
        return nowWallClockMs - (deltaNs / 1_000_000L)
    }

    private fun isUsableBssid(value: String): Boolean {
        return value.isNotBlank() && value != UNKNOWN_BSSID
    }

    private data class WifiObservation(
        val observedAtMs: Long?,
        val accessPoints: List<WifiAccessPointEvidence>,
    )

    private data class BleBeaconObservation(
        val address: String,
        val rssiDbm: Int,
        val observedAtMs: Long,
    )

    companion object {
        private const val LOCATION_UPDATE_INTERVAL_MS = 5_000L
        private const val WIFI_REFRESH_INTERVAL_MS = 30_000L
        private const val BLE_RETENTION_MS = 60_000L
        private const val UNKNOWN_BSSID = "02:00:00:00:00:00"

        private const val STATUS_AVAILABLE = "available"
        private const val STATUS_PERMISSION_MISSING = "permission_missing"
        private const val STATUS_PROVIDER_DISABLED = "provider_disabled"
        private const val STATUS_UNSUPPORTED = "unsupported"
        private const val STATUS_UNAVAILABLE = "unavailable"
        private const val STATUS_WIFI_DISABLED = "wifi_disabled"

        private val ACTIVE_LOCATION_PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )

        private val LAST_KNOWN_LOCATION_PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )

        fun missingRuntimePermissions(context: Context): Array<String> {
            return requiredRuntimePermissions()
                .filter { permission ->
                    ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
                }
                .toTypedArray()
        }

        private fun requiredRuntimePermissions(): List<String> {
            return buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.NEARBY_WIFI_DEVICES)
                }
            }
        }
    }
}