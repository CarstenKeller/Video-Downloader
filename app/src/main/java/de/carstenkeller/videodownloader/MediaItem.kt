package de.carstenkeller.videodownloader

enum class MediaKind { VIDEO, GIF }

enum class DownloadStatus { IDLE, DOWNLOADING, DONE, ERROR }

data class MediaItem(
    val id: String,
    val url: String,
    val kind: MediaKind,
    val fileName: String,
    val sizeBytes: Long? = null,
    val selected: Boolean = true,
    val status: DownloadStatus = DownloadStatus.IDLE,
    val progress: Int = -1,
    val errorMessage: String? = null
)
