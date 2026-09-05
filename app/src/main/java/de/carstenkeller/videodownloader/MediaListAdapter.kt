package de.carstenkeller.videodownloader

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import de.carstenkeller.videodownloader.databinding.ItemMediaBinding

class MediaListAdapter(
    private val onToggle: (String) -> Unit
) : ListAdapter<MediaItem, MediaListAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(val binding: ItemMediaBinding) : RecyclerView.ViewHolder(binding.root) {
        var player: MediaPlayer? = null
        // The item currently meant to play in this row's TextureView - tracked separately from
        // the player itself since the surface can become available *after* onBindViewHolder
        // already moved on to binding a still-later item (fast scrolling); the listener below
        // must always start whatever is bound *now*, not whatever was bound when it fired.
        var pendingPreviewItem: MediaItem? = null

        init {
            binding.videoPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    pendingPreviewItem?.let { startPreview(this@ViewHolder, it, Surface(surface)) }
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    releasePreview(this@ViewHolder)
                    return true
                }
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
    }

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

        // A <video loop muted> that behaves like a GIF gets a live, muted, looping preview
        // instead of a static thumbnail - see the item_media.xml comment for why: extracting a
        // representative frame to a Bitmap for this exact content proved unfixable (multiple
        // MediaMetadataRetriever/MediaCodec strategies all only produced a degenerate 1x1 stub
        // on some devices), while normal Surface-based playback is a different, far more
        // reliable path and shows the real thing instead of guessing at a substitute image.
        val showLivePreview = item.kind == MediaKind.VIDEO && item.looksLikeGif && item.streamPlan == null
        if (showLivePreview) {
            b.thumbnail.visibility = View.GONE
            b.videoPreview.visibility = View.VISIBLE
            holder.pendingPreviewItem = item
            releasePreview(holder)
            if (b.videoPreview.isAvailable) {
                startPreview(holder, item, Surface(b.videoPreview.surfaceTexture))
            }
            // else: onSurfaceTextureAvailable will start it once the surface is ready.
        } else {
            b.videoPreview.visibility = View.GONE
            b.thumbnail.visibility = View.VISIBLE
            holder.pendingPreviewItem = null
            releasePreview(holder)
            if (item.thumbnail != null) {
                b.thumbnail.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                b.thumbnail.setImageBitmap(item.thumbnail)
            } else {
                b.thumbnail.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                b.thumbnail.setImageResource(R.drawable.ic_media_placeholder)
            }
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
        if (!showLivePreview && item.thumbnail == null && item.thumbnailError != null) {
            subtitle += "\n⚠ Thumbnail: ${item.thumbnailError}"
        }
        // Temporary diagnostic - see MediaItem.thumbnailDebug. Not applicable once a live
        // preview replaces the static thumbnail attempt entirely.
        if (!showLivePreview) {
            item.thumbnailDebug?.let { subtitle += "\n🔧 Frame: $it" }
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

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.pendingPreviewItem = null
        releasePreview(holder)
    }

    private fun startPreview(holder: ViewHolder, item: MediaItem, surface: Surface) {
        releasePreview(holder)
        try {
            val player = MediaPlayer()
            val headers = mutableMapOf("User-Agent" to NetworkHeaders.USER_AGENT)
            NetworkHeaders.cookiesFor(item.url)?.let { headers["Cookie"] = it }
            item.sourcePageUrl?.let { headers["Referer"] = it }
            player.setDataSource(holder.binding.root.context, Uri.parse(item.url), headers)
            player.setSurface(surface)
            player.isLooping = true
            player.setVolume(0f, 0f)
            player.setOnPreparedListener { it.start() }
            player.setOnErrorListener { _, _, _ -> true }
            player.prepareAsync()
            holder.player = player
        } catch (e: Exception) {
            // Leave the row blank rather than crash the list over one clip that won't stream -
            // no worse than the static-thumbnail attempt this replaces would have been on failure.
        }
    }

    private fun releasePreview(holder: ViewHolder) {
        holder.player?.let {
            try {
                it.stop()
            } catch (e: Exception) {
                // already stopped/invalid - release() below still needs to run
            }
            try {
                it.release()
            } catch (e: Exception) {
                // ignore
            }
        }
        holder.player = null
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MediaItem>() {
            override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem) = oldItem == newItem
        }
    }
}
