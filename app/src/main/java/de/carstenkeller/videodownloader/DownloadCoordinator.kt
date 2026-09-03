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
        if (item.downloadDisabled) {
            onDone(item.id, false, "Nicht unterstützt")
            return
        }
        val plan = item.streamPlan
        if (plan != null) {
            downloadStream(item, plan, onProgress, onDone)
            return
        }
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

    /**
     * HLS/DASH streaming video comes as many small segments (plus an optional init segment for
     * fragmented MP4/CMAF streams) rather than one file - each is fetched in order and its
     * bytes written directly to the same output, which is a valid way to reassemble both
     * MPEG-TS segments (simple concatenation) and fMP4/CMAF segments (init segment followed by
     * fragments) into one playable file.
     */
    private fun downloadStream(
        item: MediaItem,
        plan: StreamDownloadPlan,
        onProgress: (id: String, progress: Int) -> Unit,
        onDone: (id: String, success: Boolean, error: String?) -> Unit
    ) {
        val segments = listOfNotNull(plan.videoInitUrl) + plan.videoSegmentUrls
        if (segments.isEmpty()) {
            onDone(item.id, false, "Keine Segmente im Stream gefunden")
            return
        }
        try {
            val result = MediaSaver.saveToGallery(appContext, item.kind, item.fileName) { output ->
                segments.forEachIndexed { index, segmentUrl ->
                    val requestBuilder = Request.Builder().url(segmentUrl).header("User-Agent", NetworkHeaders.USER_AGENT)
                    item.sourcePageUrl?.let { requestBuilder.header("Referer", it) }
                    NetworkHeaders.cookiesFor(segmentUrl)?.let { requestBuilder.header("Cookie", it) }
                    client.newCall(requestBuilder.build()).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("Segment ${index + 1}/${segments.size}: HTTP ${response.code}")
                        }
                        val body = response.body ?: throw IOException("Segment ${index + 1}/${segments.size}: leere Antwort")
                        body.byteStream().copyTo(output)
                    }
                    onProgress(item.id, ((index + 1) * 100) / segments.size)
                }
            }
            when (result) {
                is MediaSaver.SaveResult.Success -> onDone(item.id, true, null)
                is MediaSaver.SaveResult.Failure -> onDone(item.id, false, result.message)
            }
        } catch (e: Exception) {
            onDone(item.id, false, e.message ?: "Fehler")
        }
    }
}
