package de.carstenkeller.videodownloader

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

private const val MAX_CONCURRENT_DOWNLOADS = 3

class DownloadCoordinator(
    private val appContext: Context,
    private val client: OkHttpClient
) {
    suspend fun downloadAll(
        items: List<MediaItem>,
        onProgress: (id: String, progress: Int) -> Unit,
        onDone: (id: String, success: Boolean, error: String?) -> Unit
    ) = coroutineScope {
        val semaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
        items.map { item ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    downloadOne(item, onProgress, onDone)
                }
            }
        }.awaitAll()
    }

    private fun downloadOne(
        item: MediaItem,
        onProgress: (id: String, progress: Int) -> Unit,
        onDone: (id: String, success: Boolean, error: String?) -> Unit
    ) {
        try {
            val requestBuilder = Request.Builder().url(item.url).header("User-Agent", NetworkHeaders.USER_AGENT)
            item.sourcePageUrl?.let { requestBuilder.header("Referer", it) }
            NetworkHeaders.cookiesFor(item.url)?.let { requestBuilder.header("Cookie", it) }
            val request = requestBuilder.build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Leere Antwort")
                val total = item.sizeBytes?.takeIf { it > 0 } ?: body.contentLength().takeIf { it > 0 }

                val result = MediaSaver.saveToGallery(appContext, item.kind, item.fileName, body.byteStream()) { written ->
                    val pct = if (total != null && total > 0) ((written * 100) / total).toInt().coerceIn(0, 100) else -1
                    onProgress(item.id, pct)
                }

                when (result) {
                    is MediaSaver.SaveResult.Success -> onDone(item.id, true, null)
                    is MediaSaver.SaveResult.Failure -> onDone(item.id, false, result.message)
                }
            }
        } catch (e: Exception) {
            onDone(item.id, false, e.message ?: "Fehler")
        }
    }
}
