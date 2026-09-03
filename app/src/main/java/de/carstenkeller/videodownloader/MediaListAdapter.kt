package de.carstenkeller.videodownloader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import de.carstenkeller.videodownloader.databinding.ItemMediaBinding

class MediaListAdapter(
    private val onToggle: (String) -> Unit
) : ListAdapter<MediaItem, MediaListAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(val binding: ItemMediaBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        // The checkbox is purely a visual indicator here - the whole row is the tap target,
        // which is both a larger, more reliable target and avoids the checkbox's own touch
        // handling ever competing with the row's.
        binding.checkbox.isClickable = false
        binding.checkbox.isFocusable = false
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        val context = b.root.context

        b.checkbox.isChecked = item.selected
        b.checkbox.isEnabled = item.status == DownloadStatus.IDLE
        b.root.isEnabled = item.status == DownloadStatus.IDLE
        b.root.setOnClickListener {
            if (item.status == DownloadStatus.IDLE) onToggle(item.id)
        }

        if (item.thumbnail != null) {
            b.thumbnail.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            b.thumbnail.setImageBitmap(item.thumbnail)
        } else {
            b.thumbnail.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            b.thumbnail.setImageResource(R.drawable.ic_media_placeholder)
        }

        b.fileName.text = item.fileName
        val kindLabel = if (item.kind == MediaKind.GIF) "GIF" else "Video"
        val sizeLabel = item.sizeBytes?.let { formatFileSize(it) } ?: context.getString(R.string.size_unknown)
        b.subtitle.text = "$kindLabel · $sizeLabel"

        when (item.status) {
            DownloadStatus.IDLE -> {
                b.statusText.visibility = View.GONE
                b.progressBar.visibility = View.GONE
            }
            DownloadStatus.DOWNLOADING -> {
                b.statusText.visibility = View.VISIBLE
                b.progressBar.visibility = View.VISIBLE
                if (item.progress in 0..100) {
                    b.progressBar.isIndeterminate = false
                    b.progressBar.progress = item.progress
                    b.statusText.text = "${item.progress}%"
                } else {
                    b.progressBar.isIndeterminate = true
                    b.statusText.text = context.getString(R.string.status_downloading)
                }
            }
            DownloadStatus.DONE -> {
                b.statusText.visibility = View.VISIBLE
                b.statusText.text = context.getString(R.string.status_done)
                b.progressBar.visibility = View.GONE
            }
            DownloadStatus.ERROR -> {
                b.statusText.visibility = View.VISIBLE
                b.statusText.text = item.errorMessage ?: context.getString(R.string.status_error)
                b.progressBar.visibility = View.GONE
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MediaItem>() {
            override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem) = oldItem == newItem
        }
    }
}
