package com.example.accellogger

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.accellogger.databinding.ItemLogFileBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsAdapter(
    private val onShare: (LogFileItem) -> Unit,
    private val onDelete: (LogFileItem) -> Unit,
) : RecyclerView.Adapter<LogsAdapter.LogViewHolder>() {

    private val items = mutableListOf<LogFileItem>()
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun submitItems(newItems: List<LogFileItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemLogFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class LogViewHolder(
        private val binding: ItemLogFileBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: LogFileItem) {
            binding.fileNameText.text = item.fileName
            val date = dateFormatter.format(Date(item.modifiedTimeMs))
            val size = formatSize(item.sizeBytes)
            binding.fileDetailsText.text = binding.root.context.getString(
                R.string.log_file_details,
                size,
                date,
            )
            binding.shareButton.setOnClickListener { onShare(item) }
            binding.deleteButton.setOnClickListener { onDelete(item) }
        }
    }

    private fun formatSize(sizeBytes: Long): String {
        val kib = 1024L
        val mib = kib * 1024L
        return when {
            sizeBytes >= mib -> String.format(Locale.US, "%.1f MB", sizeBytes.toDouble() / mib)
            sizeBytes >= kib -> String.format(Locale.US, "%.1f KB", sizeBytes.toDouble() / kib)
            else -> "$sizeBytes B"
        }
    }
}
