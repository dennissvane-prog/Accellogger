package com.example.accellogger

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.accellogger.databinding.ActivityLogsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogsBinding
    private lateinit var adapter: LogsAdapter
    private lateinit var logFileManager: LogFileManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        logFileManager = LogFileManager(applicationContext)
        adapter = LogsAdapter(
            onShare = ::shareLog,
            onDelete = ::confirmDelete,
        )

        binding.logsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.logsRecyclerView.adapter = adapter
        binding.deleteAllButton.setOnClickListener { confirmDeleteAll() }
    }

    override fun onResume() {
        super.onResume()
        loadLogs()
    }

    private fun loadLogs() {
        lifecycleScope.launch {
            val logs = withContext(Dispatchers.IO) { logFileManager.listLogs() }
            adapter.submitItems(logs)
            binding.emptyText.visibility =
                if (logs.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            binding.deleteAllButton.isEnabled = logs.isNotEmpty()
        }
    }

    private fun shareLog(item: LogFileItem) {
        if (item.storageReference.isBlank()) {
            Toast.makeText(this, R.string.export_error_no_file, Toast.LENGTH_SHORT).show()
            loadLogs()
            return
        }

        startActivity(
            Intent.createChooser(
                ShareHelper.createShareIntent(this, item.storageReference),
                getString(R.string.share_sheet_title),
            ),
        )
    }

    private fun confirmDelete(item: LogFileItem) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.delete_log_confirmation, item.fileName))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        logFileManager.deleteLog(item.storageReference)
                    }
                    loadLogs()
                }
            }
            .show()
    }

    private fun confirmDeleteAll() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.delete_all_confirmation)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        logFileManager.deleteAllLogs()
                    }
                    loadLogs()
                }
            }
            .show()
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
