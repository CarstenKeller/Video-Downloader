package de.carstenkeller.videodownloader

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class DownloadEvent {
    data class Progress(val id: String, val percent: Int) : DownloadEvent()
    data class Done(val id: String, val success: Boolean, val error: String?) : DownloadEvent()
    data class BatchFinished(val successCount: Int, val total: Int) : DownloadEvent()
}

/**
 * In-process pub/sub between DownloadService (which keeps running independently of any UI, see
 * DownloadService) and whatever UI happens to be alive (MediaListViewModel). Lets the visible
 * list keep reflecting live per-item progress exactly as before whenever the app is in the
 * foreground, without the download itself depending on that UI still existing - the service's
 * own callbacks to DownloadCoordinator aren't suspend functions, so tryEmit (never blocks,
 * drops the oldest buffered event if a collector is ever slower than the producer) is the right
 * fit here, not emit.
 */
object DownloadEvents {
    private val _events = MutableSharedFlow<DownloadEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    fun tryEmit(event: DownloadEvent) {
        _events.tryEmit(event)
    }
}
