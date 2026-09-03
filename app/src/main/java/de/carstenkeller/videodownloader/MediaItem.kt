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
    val thumbnailError: String? = null,
    // Diagnostic text for the source-page crawl (see MainActivity.upgradeFromSourceLinks),
    // shown in the list so real crawl behavior can be read off the device instead of guessed
    // at - previous guesses about *why* a crawl didn't improve an item have repeatedly not
    // matched what was actually happening.
    val crawlStatus: String? = null,
    // Present for HLS/DASH streaming video (see StreamManifest) - url holds the manifest
    // (.m3u8/.mpd) itself for identity/display, while this plan holds the resolved segment
    // list DownloadCoordinator actually fetches and concatenates.
    val streamPlan: StreamDownloadPlan? = null,
    // Informational text for streams that couldn't be fully resolved into one downloadable
    // file (e.g. separate audio/video tracks saved as two files, or an unsupported segment
    // addressing mode) - shown in the list like crawlStatus.
    val streamNote: String? = null,
    // True for a detected stream this app genuinely cannot download (encrypted HLS,
    // byte-range DASH, ...) - streamPlan is null in that case, so there is nothing a normal
    // download attempt could act on; the row is shown (so the user knows it was found) but not
    // selectable, rather than letting a download attempt save the raw manifest text as if it
    // were the video.
    val downloadDisabled: Boolean = false
)
