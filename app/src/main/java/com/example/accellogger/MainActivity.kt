package com.example.accellogger

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.widget.doAfterTextChanged
import com.example.accellogger.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.sampleRateInput.setText(viewModel.uiState.value.sampleRateHz.toString())
        binding.sampleRateInput.doAfterTextChanged { text ->
            val sampleRateHz = text?.toString()?.toIntOrNull()
            if (sampleRateHz != null) {
                viewModel.setSampleRateHz(sampleRateHz)
            }
        }

        binding.startStopButton.setOnClickListener {
            if (viewModel.uiState.value.isLogging) {
                viewModel.stopLogging()
            } else {
                viewModel.startLogging()
            }
        }
        binding.shareLastLogButton.setOnClickListener { shareLastLog() }
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

        binding.startStopButton.isEnabled = state.sensorAvailable
        binding.startStopButton.text = if (state.isLogging) {
            getString(R.string.stop_logging)
        } else {
            getString(R.string.start_logging)
        }

        val hasLastLog = !state.lastSavedStorageReference.isNullOrBlank()
        binding.shareLastLogButton.isEnabled = hasLastLog
        binding.lastFileText.text = getString(
            R.string.last_file_label,
            state.lastSavedFileName ?: getString(R.string.no_log_saved),
        )
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

    private fun formatElapsed(elapsedMs: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(elapsedMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsedMs) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsedMs) % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }
}
