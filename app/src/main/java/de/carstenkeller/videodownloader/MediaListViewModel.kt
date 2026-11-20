package de.carstenkeller.videodownloader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MediaListViewModel : ViewModel() {

    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items: StateFlow<List<MediaItem>> = _items.asStateFlow()

    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading.asStateFlow()

    init {
        // DownloadService does the actual work (see its class doc for why), independently of
        // whether this ViewModel - or any UI - is even around to see it; this just mirrors its
        // progress back into the list exactly like the old fragment-local callbacks did,
        // whenever the app happens to be in the foreground to show it.
        viewModelScope.launch {
            DownloadEvents.events.collect { event ->
                when (event) {
                    is DownloadEvent.Progress -> updateItem(event.id) { it.copy(progress = event.percent) }
                    is DownloadEvent.Done -> updateItem(event.id) {
                        it.copy(
                            status = if (event.success) DownloadStatus.DONE else DownloadStatus.ERROR,
                            errorMessage = event.error
                        )
                    }
                    is DownloadEvent.BatchFinished -> setDownloading(false)
                }
            }
        }
    }

    // 0 = off. Only affects items with a known durationMs (see isVisibleForMinDuration) - a
    // stream (whose length lives in its manifest, never extracted here) or anything whose
    // duration extraction genuinely couldn't determine one has nothing to judge against, so
    // it's never hidden by this; an item still awaiting extraction is hidden until it settles.
    private val _minDurationSeconds = MutableStateFlow(0)
    val minDurationSeconds: StateFlow<Int> = _minDurationSeconds.asStateFlow()

    fun setMinDurationSeconds(seconds: Int) {
        _minDurationSeconds.value = seconds
    }

    fun setItems(newItems: List<MediaItem>) {
        _items.value = newItems
    }

    fun toggleSelection(id: String) {
        _items.value = _items.value.map { if (it.id == id) it.copy(selected = !it.selected) else it }
    }

    /** [onlyIds], when given, restricts the change to those items (e.g. only the ones
     * currently visible under the minimum-length filter) instead of the whole list. */
    fun setAllSelected(selected: Boolean, onlyIds: Set<String>? = null) {
        _items.value = _items.value.map {
            if (it.status == DownloadStatus.IDLE && (onlyIds == null || it.id in onlyIds)) {
                it.copy(selected = selected)
            } else {
                it
            }
        }
    }

    fun updateItem(id: String, transform: (MediaItem) -> MediaItem) {
        _items.value = _items.value.map { if (it.id == id) transform(it) else it }
    }

    fun setDownloading(value: Boolean) {
        _downloading.value = value
    }
}

fun isVisibleForMinDuration(item: MediaItem, minDurationSeconds: Int): Boolean {
    if (minDurationSeconds <= 0) return true
    // Not measured yet, as opposed to durationMs being permanently null (durationPending false)
    // - hide it from selection/download until it settles, rather than let it through simply
    // because its (possibly too-short) length hadn't been checked yet.
    if (item.durationPending) return false
    val durationMs = item.durationMs ?: return true
    return durationMs >= minDurationSeconds * 1000L
}
