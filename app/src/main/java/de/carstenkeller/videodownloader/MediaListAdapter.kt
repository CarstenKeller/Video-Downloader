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

        val interactive = item.status == DownloadStatus.IDLE && !item.downloadDisabled
        b.checkbox.isChecked = item.selected
        b.checkbox.isEnabled = interactive
        b.root.isEnabled = interactive
        b.root.setOnClickListener {
            if (interactive) onToggle(item.id)
        }

        if (item.thumbnail != null) {
            b.thumbnail.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            b.thumbnail.setImageBitmap(item.thumbnail)
        } else {
            b.thumbnail.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            b.thumbnail.setImageResource(R.drawable.ic_media_placeholder)
        }

        b.fileName.text = item.fileName
        // looksLikeGif: a <video loop muted> that behaves like a GIF but is a real video file
        // (e.g. saved as .mp4) - labeled "GIF" here to match how it looks, without changing
        // what's actually saved.
        val baseKindLabel = if (item.kind == MediaKind.GIF || item.looksLikeGif) "GIF" else "Video"
        val kindLabel = if (item.streamPlan != null || item.downloadDisabled) "$baseKindLabel (Stream)" else baseKindLabel
        // "~" for streams: an estimate from one sample segment's size times the segment count,
        // not an exact figure - there is no cheap way to know a segmented stream's exact total
        // size without downloading it in full.
        val sizeLabel = item.sizeBytes?.let { (if (item.streamPlan != null) "~" else "") + formatFileSize(it) }
            ?: context.getString(R.string.size_unknown)
        var subtitle = "$kindLabel · $sizeLabel"
        item.durationMs?.let { subtitle += " · ${formatDuration(it)}" }
        if (item.thumbnail == null && item.thumbnailError != null) {
            subtitle += "\n⚠ Thumbnail: ${item.thumbnailError}"
        }
        // See MediaItem.durationUnknown - without this, an item the length filter couldn't
        // judge just silently shows up regardless of the slider, which looks like the filter
        // doesn't work at all rather than like a specific, unmeasurable file.
        if (item.durationUnknown) {
            subtitle += "\nℹ Länge nicht ermittelbar - wird vom Mindestlängen-Filter nicht ausgeblendet"
        }
        // Debug-style diagnostic for the source-page crawl - see MainActivity.
        // upgradeFromSourceLinks - shown so real behavior can be read off the device.
        item.crawlStatus?.let { subtitle += "\nℹ $it" }
        item.streamNote?.let { subtitle += "\nℹ $it" }
        b.subtitle.text = subtitle

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
