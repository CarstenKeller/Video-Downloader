package de.carstenkeller.videodownloader

import android.graphics.Bitmap

enum class MediaKind { VIDEO, GIF }

enum class DownloadStatus { IDLE, DOWNLOADING, DONE, ERROR }

data class MediaItem(
    val id: String,
    val url: String,
    val kind: MediaKind,
    val fileName: String,
    val posterUrl: String? = null,
    val sourcePageUrl: String? = null,
    // Many sites implement "GIFs" as silent, looping <video> elements rather than real .gif
    // files (much smaller than an actual GIF for the same content). The saved file is
    // genuinely a video (kind stays VIDEO, extension stays .mp4) - this only drives showing
    // "GIF" instead of "Video" as the list label, so it matches what the label actually shows.
    val looksLikeGif: Boolean = false,
    val sizeBytes: Long? = null,
    val selected: Boolean = true,
    val status: DownloadStatus = DownloadStatus.IDLE,
    val progress: Int = -1,
    val errorMessage: String? = null,
    val thumbnail: Bitmap? = null,
    val thumbnailError: String? = null
)
