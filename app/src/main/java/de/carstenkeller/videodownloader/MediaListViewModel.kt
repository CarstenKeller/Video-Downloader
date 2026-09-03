package de.carstenkeller.videodownloader

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MediaListViewModel : ViewModel() {

    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items: StateFlow<List<MediaItem>> = _items.asStateFlow()

    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading.asStateFlow()

    fun setItems(newItems: List<MediaItem>) {
        _items.value = newItems
    }

    fun toggleSelection(id: String) {
        _items.value = _items.value.map { if (it.id == id) it.copy(selected = !it.selected) else it }
    }

    fun setAllSelected(selected: Boolean) {
        _items.value = _items.value.map { if (it.status == DownloadStatus.IDLE) it.copy(selected = selected) else it }
    }

    fun updateItem(id: String, transform: (MediaItem) -> MediaItem) {
        _items.value = _items.value.map { if (it.id == id) transform(it) else it }
    }

    fun setDownloading(value: Boolean) {
        _downloading.value = value
    }
}
