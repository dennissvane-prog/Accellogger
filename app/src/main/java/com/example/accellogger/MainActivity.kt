package com.example.accellogger

import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.widget.EditText
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.widget.doAfterTextChanged
import com.example.accellogger.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val geolocationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grantResults ->
            if (grantResults.values.any { granted -> !granted }) {
                Toast.makeText(this, R.string.geolocation_permissions_limited, Toast.LENGTH_LONG).show()
            }
            viewModel.startLogging()
        }
    private val syncFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }

        val permissionFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val persisted = runCatching {
            contentResolver.takePersistableUriPermission(uri, permissionFlags)
        }.isSuccess
        if (!persisted) {
            handleEvent(MainUiEvent.Error(getString(R.string.auto_sync_permission_error)))
            return@registerForActivityResult
        }

        val folderName = DocumentFile.fromTreeUri(this, uri)?.name
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.auto_sync_destination_selected)
        viewModel.configureAutoSync(uri, folderName)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()
        binding.titleText.text = buildTitleText()

        binding.sampleRateInput.setText(viewModel.uiState.value.sampleRateHz.toString())
        binding.sampleRateInput.doAfterTextChanged { text ->
            val sampleRateHz = text?.toString()?.toIntOrNull()
            if (sampleRateHz != null) {
                viewModel.setSampleRateHz(sampleRateHz)
            }
        }
        binding.thresholdInput.setText(formatThresholdG(viewModel.uiState.value.eventTriggerThresholdG))
        binding.thresholdInput.doAfterTextChanged { text ->
            val eventTriggerThresholdG = parseThresholdInput(text)
            if (eventTriggerThresholdG != null) {
                viewModel.setEventTriggerThresholdG(eventTriggerThresholdG)
            }
        }
        binding.eventWindowInput.setText(viewModel.uiState.value.eventWindowMs.toString())
        binding.eventWindowInput.doAfterTextChanged { text ->
            val eventWindowMs = text?.toString()?.toIntOrNull()
            if (eventWindowMs != null) {
                viewModel.setEventWindowMs(eventWindowMs)
            }
        }

        binding.startStopButton.setOnClickListener {
            if (viewModel.uiState.value.isLogging) {
                viewModel.stopLogging()
            } else {
                startLoggingWithPermissions()
            }
        }
        binding.shareLastLogButton.setOnClickListener { shareLastLog() }
        binding.chooseSyncFolderButton.setOnClickListener {
            syncFolderLauncher.launch(null)
        }
        binding.disableSyncButton.setOnClickListener {
            viewModel.clearAutoSync()
        }
        binding.viewLogsButton.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect(::render)
                }
                launch {
                    viewModel.events.collect(::handleEvent)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppVisible()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onAppHidden()
    }

    private fun render(state: MainUiState) {
        val sensorDetails = state.sensorDetails
        binding.sensorUnavailableText.visibility =
            if (state.sensorAvailable) android.view.View.GONE else android.view.View.VISIBLE

        binding.sensorNameText.text = getString(
            R.string.sensor_name_label,
            sensorDetails?.name ?: getString(R.string.unknown_sensor_value),
        )
        binding.sensorVendorText.text = getString(
            R.string.sensor_vendor_label,
            sensorDetails?.vendor ?: getString(R.string.unknown_sensor_value),
        )
        binding.sensorRangeText.text = getString(
            R.string.sensor_range_label,
            sensorDetails?.maximumRange ?: 0f,
        )
        binding.sensorResolutionText.text = getString(
            R.string.sensor_resolution_label,
            sensorDetails?.resolution ?: 0f,
        )
        binding.sensorPowerText.text = getString(
            R.string.sensor_power_label,
            sensorDetails?.powerMilliAmps ?: 0f,
        )

        val currentSample = state.currentSample
        binding.xValueText.text = getString(R.string.x_value_label, currentSample?.x ?: 0f)
        binding.yValueText.text = getString(R.string.y_value_label, currentSample?.y ?: 0f)
        binding.zValueText.text = getString(R.string.z_value_label, currentSample?.z ?: 0f)
        binding.magnitudeValueText.text = getString(
            R.string.magnitude_value_label,
            currentSample?.magnitude ?: 0.0,
        )

        binding.statusText.text = getString(R.string.status_label, state.statusText)
        binding.elapsedTimeText.text = getString(
            R.string.elapsed_time_label,
            formatElapsed(state.elapsedMs),
        )
        binding.samplesText.text = getString(R.string.samples_label, state.sampleCount)
        syncInputValue(binding.sampleRateInput, state.sampleRateHz.toString())
        syncInputValue(binding.thresholdInput, formatThresholdG(state.eventTriggerThresholdG))
        syncInputValue(binding.eventWindowInput, state.eventWindowMs.toString())

        binding.startStopButton.isEnabled = state.sensorAvailable
        binding.startStopButton.text = if (state.isLogging) {
            getString(R.string.stop_logging)
        } else {
            getString(R.string.start_logging)
        }
        binding.sampleRateInput.isEnabled = state.sensorAvailable && !state.isLogging
        binding.thresholdInput.isEnabled = state.sensorAvailable && !state.isLogging
        binding.eventWindowInput.isEnabled = state.sensorAvailable && !state.isLogging

        val hasLastLog = !state.lastSavedStorageReference.isNullOrBlank()
        binding.shareLastLogButton.isEnabled = hasLastLog
        binding.lastFileText.text = getString(
            R.string.last_file_label,
            state.lastSavedFileName ?: getString(R.string.no_log_saved),
        )
        binding.syncStatusText.text = if (state.autoSyncEnabled) {
            getString(
                R.string.auto_sync_status_enabled,
                state.autoSyncDestinationLabel ?: getString(R.string.auto_sync_destination_selected),
            )
        } else {
            getString(R.string.auto_sync_status_disabled)
        }
        binding.chooseSyncFolderButton.text = if (state.autoSyncEnabled) {
            getString(R.string.change_sync_folder)
        } else {
            getString(R.string.choose_sync_folder)
        }
        binding.disableSyncButton.visibility = if (state.autoSyncEnabled) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
    }

    private fun handleEvent(event: MainUiEvent) {
        when (event) {
            is MainUiEvent.Error -> {
                MaterialAlertDialogBuilder(this)
                    .setMessage(event.message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }

            is MainUiEvent.Info -> {
                Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareLastLog() {
        val storageReference = viewModel.uiState.value.lastSavedStorageReference
        if (storageReference.isNullOrBlank()) {
            handleEvent(MainUiEvent.Error(getString(R.string.export_error_no_file)))
            return
        }

        startActivity(
            Intent.createChooser(
                ShareHelper.createShareIntent(this, storageReference),
                getString(R.string.share_sheet_title),
            ),
        )
    }

    private fun startLoggingWithPermissions() {
        val missingPermissions = GeolocationEvidenceCollector.missingRuntimePermissions(this)
        if (missingPermissions.isEmpty()) {
            viewModel.startLogging()
            return
        }

        geolocationPermissionLauncher.launch(missingPermissions)
    }

    private fun formatElapsed(elapsedMs: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(elapsedMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsedMs) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsedMs) % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun parseThresholdInput(text: CharSequence?): Double? {
        return text
            ?.toString()
            ?.trim()
            ?.replace(',', '.')
            ?.toDoubleOrNull()
    }

    private fun formatThresholdG(value: Double): String {
        return NumberFormat.getNumberInstance().apply {
            maximumFractionDigits = 2
            minimumFractionDigits = 0
            isGroupingUsed = false
        }.format(value)
    }

    private fun syncInputValue(editText: EditText, value: String) {
        if (editText.hasFocus()) {
            return
        }
        if (editText.text?.toString() == value) {
            return
        }
        editText.setText(value)
    }

    private fun buildTitleText(): CharSequence {
        val appName = getString(R.string.app_name)
        val versionText = getString(R.string.app_version_label, BuildConfig.VERSION_NAME)
        return SpannableStringBuilder(appName).apply {
            append(' ')
            val versionStart = length
            append(versionText)
            setSpan(RelativeSizeSpan(0.55f), versionStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun applySystemBarInsets() {
        val root = binding.root
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                initialLeft + systemBars.left,
                initialTop + systemBars.top,
                initialRight + systemBars.right,
                initialBottom + systemBars.bottom,
            )
            insets
        }

        ViewCompat.requestApplyInsets(root)
    }
}
