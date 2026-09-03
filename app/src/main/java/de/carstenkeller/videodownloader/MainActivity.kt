package de.carstenkeller.videodownloader

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import de.carstenkeller.videodownloader.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume

private const val DEFAULT_URL = "https://www.google.com"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MediaListViewModel by viewModels()
    private val httpClient = OkHttpClient()

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var lastHost: String? = null

    // Only one hidden crawler WebView exists, so only one source page can be crawled at a
    // time - this serializes crawlSourcePage() calls instead of letting them race on it.
    private val crawlerMutex = Mutex()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupCrawlerWebView()
        setupControls()

        onBackPressedDispatcher.addCallback(this) {
            if (customView != null) {
                binding.webView.webChromeClient?.onHideCustomView()
            } else if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.progressBar.visibility = View.VISIBLE
                if (url != null) binding.addressBar.setText(url)

                // Only clear the media list when navigating to a genuinely different site
                // (different host). Many pages reload/paginate within the same site while
                // scrolling (infinite scroll, "load more"), which also fires onPageStarted -
                // clearing on every one of those would wipe out finds mid-scroll.
                val host = url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                if (host != null && host != lastHost) {
                    viewModel.setItems(emptyList())
                }
                if (host != null) lastHost = host
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progressBar.visibility = View.GONE
                if (url != null) binding.addressBar.setText(url)
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                binding.fullscreenContainer.addView(view)
                binding.fullscreenContainer.visibility = View.VISIBLE
                binding.webView.visibility = View.GONE
                @Suppress("DEPRECATION")
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }

            override fun onHideCustomView() {
                binding.fullscreenContainer.visibility = View.GONE
                binding.fullscreenContainer.removeView(customView)
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                binding.webView.visibility = View.VISIBLE
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

        binding.webView.loadUrl(DEFAULT_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupCrawlerWebView() {
        binding.crawlerWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { if (binding.webView.canGoBack()) binding.webView.goBack() }
        binding.btnForward.setOnClickListener { if (binding.webView.canGoForward()) binding.webView.goForward() }
        binding.btnReload.setOnClickListener { binding.webView.reload() }
        binding.btnScan.setOnClickListener { scanCurrentPage() }

        binding.addressBar.setOnEditorActionListener { _, actionId, event ->
            val isEnterKey = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE || isEnterKey) {
                loadTypedUrl()
                true
            } else {
                false
            }
        }
    }

    private fun loadTypedUrl() {
        var input = binding.addressBar.text.toString().trim()
        if (input.isEmpty()) return

        input = if (input.startsWith("http://") || input.startsWith("https://")) {
            input
        } else if (input.contains(" ") || !input.contains(".")) {
            "https://www.google.com/search?q=" + Uri.encode(input)
        } else {
            "https://$input"
        }

        binding.webView.loadUrl(input)
        currentFocus?.let { focused ->
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(focused.windowToken, 0)
        }
        binding.webView.clearFocus()
    }

    /**
     * Runs a JS expression on the given WebView and suspends until the result comes back,
     * instead of the raw callback-based evaluateJavascript API.
     */
    private suspend fun evalJs(webView: WebView, script: String): String? = suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript(script) { result ->
            if (cont.isActive) cont.resume(result)
        }
    }

    /**
     * Scans the media currently in the DOM and merges genuinely new finds into the list.
     * Returns how many new items were found, so callers can decide whether to show a
     * "nothing found" message after a whole scroll-and-scan pass.
     */
    private suspend fun scanAndMergeOnce(): Int {
        val rawResult = evalJs(binding.webView, MediaScanner.SCAN_JS)
        val scanned = MediaScanner.parseResult(rawResult)
        val existing = viewModel.items.value
        val existingUrls = existing.map { it.url }.toSet()
        val newScanned = scanned.filter { it.url !in existingUrls }
        if (newScanned.isEmpty()) return 0

        val fileNames = MediaScanner.buildFileNames(newScanned, existing.map { it.fileName }.toSet())
        val currentPageUrl = binding.webView.url
        val newItems = newScanned.mapIndexed { index, media ->
            MediaItem(
                id = media.url,
                url = media.url,
                kind = media.kind,
                fileName = fileNames[index],
                posterUrl = media.posterUrl,
                sourcePageUrl = currentPageUrl,
                looksLikeGif = media.looksLikeGif
            )
        }

        viewModel.setItems(existing + newItems)
        if (supportFragmentManager.findFragmentByTag(MEDIA_LIST_TAG) == null) {
            MediaListBottomSheet().show(supportFragmentManager, MEDIA_LIST_TAG)
        }
        fetchSizes(newItems)
        fetchThumbnails(newItems)
        upgradeFromSourceLinks(newScanned.zip(newItems))
        return newItems.size
    }

    /**
     * Best-effort background upgrade: for any newly found item whose page linked it to another
     * page (e.g. a search-result grid linking a reduced preview through to its original source
     * page - see MediaScanner.SCAN_JS's sourceLink), crawl that source page in a hidden WebView
     * and, if it contains a matching-kind media file, replace the preview with it. Runs after
     * the item is already shown with its own (possibly reduced) data, so this only improves the
     * entry in place if and when a crawl succeeds - it never blocks or delays the visible scan.
     *
     * This is inherently best-effort: it depends entirely on the page actually exposing such a
     * link near the media (true for many search-result/gallery grids, not guaranteed anywhere),
     * and on a single hidden WebView successfully loading and rendering that other page.
     */
    private fun upgradeFromSourceLinks(pairs: List<Pair<ScannedMedia, MediaItem>>) {
        pairs.forEach { (media, item) ->
            val sourceLink = media.sourceLink ?: return@forEach
            lifecycleScope.launch {
                val candidates = crawlSourcePage(sourceLink)
                val match = pickBestMatch(media, candidates) ?: return@launch
                if (match.url == item.url) return@launch

                val newFileName = MediaScanner.deriveFileName(match.url, match.kind, 1)
                viewModel.updateItem(item.id) {
                    it.copy(
                        url = match.url,
                        kind = match.kind,
                        looksLikeGif = match.looksLikeGif,
                        posterUrl = match.posterUrl,
                        sourcePageUrl = sourceLink,
                        fileName = newFileName,
                        sizeBytes = null,
                        thumbnail = null,
                        thumbnailError = null
                    )
                }
                val updated = viewModel.items.value.find { it.id == item.id } ?: return@launch
                fetchSizes(listOf(updated))
                fetchThumbnails(listOf(updated))
            }
        }
    }

    /** Prefers a candidate of the exact same kind/looksLikeGif; falls back to matching kind. */
    private fun pickBestMatch(target: ScannedMedia, candidates: List<ScannedMedia>): ScannedMedia? {
        return candidates.firstOrNull { it.kind == target.kind && it.looksLikeGif == target.looksLikeGif }
            ?: candidates.firstOrNull { it.kind == target.kind }
    }

    /**
     * Loads [url] in the hidden crawler WebView and runs the same scan used for the visible
     * page against it. Serialized via [crawlerMutex] since only one hidden WebView exists.
     * Gives up (returning an empty list) if the page doesn't finish loading within 10s - some
     * pages never fire a clean load-complete event, and this is a background nice-to-have, not
     * something worth hanging on indefinitely.
     */
    private suspend fun crawlSourcePage(url: String): List<ScannedMedia> = crawlerMutex.withLock {
        val loaded = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine<Unit> { cont ->
                binding.crawlerWebView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
                binding.crawlerWebView.loadUrl(url)
            }
        }
        if (loaded == null) return@withLock emptyList()

        // A short settle delay for any immediate script-driven content, mirroring the visible
        // scan's approach without its scroll steps - the hidden WebView isn't scrolled, so this
        // only catches content that loads on its own, not content gated behind scroll position.
        delay(500L)
        val raw = evalJs(binding.crawlerWebView, MediaScanner.SCAN_JS)
        MediaScanner.parseResult(raw)
    }

    /**
     * Scans the current page while driving it top-to-bottom in steps, scanning again after
     * *each* step - not just once at the end. This matters: many media-heavy pages
     * virtualize their DOM, removing off-screen <video>/<img> elements again once you
     * scroll past them to save memory. Scanning only after returning to the top would miss
     * anything that got unloaded again in the meantime; scanning right after each step
     * catches it while it's still there. Also recurses into same-origin iframes (see
     * MediaScanner.SCAN_JS); cross-origin iframes cannot be inspected at all - the
     * browser's same-origin policy blocks that unconditionally, not just here.
     *
     * The scroll is a best-effort simulation of manual scrolling, not a guarantee: a page
     * that only creates elements once truly on-screen still only reveals as much as this
     * step/time budget covers, and a genuinely infinite-scroll feed has no real bottom -
     * the step cap is what stops this from running forever on one.
     *
     * Always starts from an empty list: pressing the scan button rebuilds the results for
     * whatever page is currently loaded, rather than adding to whatever an earlier page (or
     * an earlier scan) had found. Matching same-host navigations (e.g. a same-site search
     * that doesn't change the WebView's host) would otherwise keep stale entries around.
     */
    private fun scanCurrentPage() {
        // Immediate feedback on tap, independent of how long this takes - so it reads as
        // "working" rather than as the button doing nothing.
        binding.btnScan.isEnabled = false
        viewModel.setItems(emptyList())
        lifecycleScope.launch {
            var totalNew = scanAndMergeOnce()

            var lastHeight = -1.0
            var steps = 0
            while (steps < MAX_AUTO_SCROLL_STEPS) {
                evalJs(binding.webView, "window.scrollBy(0, Math.round(window.innerHeight * 0.85));")
                delay(AUTO_SCROLL_STEP_DELAY_MS)
                totalNew += scanAndMergeOnce()

                val height = evalJs(
                    binding.webView,
                    "Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);"
                )?.toDoubleOrNull() ?: break
                val scrollY = evalJs(binding.webView, "window.scrollY;")?.toDoubleOrNull() ?: 0.0
                steps++
                val reachedBottom = scrollY + binding.webView.height >= height - 50
                if (reachedBottom && height <= lastHeight + 5) break
                lastHeight = height
            }
            evalJs(binding.webView, "window.scrollTo(0, 0);")

            binding.btnScan.isEnabled = true
            if (totalNew == 0) {
                Toast.makeText(this@MainActivity, R.string.no_media_found, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun fetchSizes(items: List<MediaItem>) {
        items.forEach { item ->
            lifecycleScope.launch(Dispatchers.IO) {
                val size = fetchContentLength(item.url)
                if (size != null) {
                    withContext(Dispatchers.Main) {
                        viewModel.updateItem(item.id) { it.copy(sizeBytes = size) }
                    }
                }
            }
        }
    }

    private fun fetchContentLength(url: String): Long? {
        return try {
            val headRequest = withCommonHeaders(Request.Builder().url(url), url).head().build()
            httpClient.newCall(headRequest).execute().use { response ->
                response.header("Content-Length")?.toLongOrNull()?.let { return it }
            }
            val rangeRequest = withCommonHeaders(Request.Builder().url(url), url)
                .header("Range", "bytes=0-0")
                .get()
                .build()
            httpClient.newCall(rangeRequest).execute().use { response ->
                val contentRange = response.header("Content-Range")
                contentRange?.substringAfterLast('/')?.toLongOrNull()
                    ?: response.header("Content-Length")?.toLongOrNull()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Thumbnails: GIFs use the file itself. Videos use the page's declared <video poster>
     * if present (free - no extra download); otherwise a real frame is extracted directly
     * from the video file via MediaMetadataRetriever, which streams only as much of the
     * remote file as it needs - not the whole thing, but still real network/data usage per
     * video, unlike the poster case.
     */
    private fun fetchThumbnails(items: List<MediaItem>) {
        items.forEach { item ->
            lifecycleScope.launch(Dispatchers.IO) {
                var bitmap: Bitmap? = null
                // Captured and shown in the list (see MediaListAdapter) instead of just being
                // dropped: after several rounds of guessing at header/decoder fixes that
                // didn't hold up on all sites, seeing the actual failure reason per item is
                // what's needed to diagnose the remaining cases instead of guessing again.
                var error: String? = null
                try {
                    bitmap = when {
                        item.kind == MediaKind.GIF -> loadBitmapFromUrl(item.url, item.sourcePageUrl)
                        item.posterUrl != null -> loadBitmapFromUrl(item.posterUrl, item.sourcePageUrl)
                        else -> extractVideoFrame(item.url, item.sourcePageUrl)
                    }
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                }
                withContext(Dispatchers.Main) {
                    viewModel.updateItem(item.id) {
                        it.copy(thumbnail = bitmap, thumbnailError = if (bitmap == null) error else null)
                    }
                }
            }
        }
    }

    /** Adds User-Agent and, if the WebView has any for this URL, the session's cookies. */
    private fun withCommonHeaders(builder: Request.Builder, url: String): Request.Builder {
        builder.header("User-Agent", NetworkHeaders.USER_AGENT)
        NetworkHeaders.cookiesFor(url)?.let { builder.header("Cookie", it) }
        return builder
    }

    private fun loadBitmapFromUrl(url: String, referer: String?): Bitmap {
        val builder = withCommonHeaders(Request.Builder().url(url), url)
        if (referer != null) builder.header("Referer", referer)
        httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            val bytes = response.body?.bytes() ?: throw java.io.IOException("Leere Antwort")
            return decodeBitmap(bytes) ?: throw java.io.IOException("Nicht dekodierbar (${bytes.size} Bytes)")
        }
    }

    /**
     * ImageDecoder (API 28+) handles GIF and other formats more robustly than the legacy
     * BitmapFactory in some edge cases; falls back to BitmapFactory for anything it rejects.
     */
    private fun decodeBitmap(bytes: ByteArray): Bitmap? {
        return try {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            ImageDecoder.decodeBitmap(source) { decoder, _, _ -> decoder.isMutableRequired = false }
        } catch (e: Exception) {
            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e2: Exception) {
                null
            }
        }
    }

    /**
     * Downloads the video to a temp file (capped at [MAX_THUMBNAIL_DOWNLOAD_BYTES]) and
     * extracts the frame from that local file, instead of pointing MediaMetadataRetriever at
     * the network URL directly. Reported symptom that led here: the downloaded file itself
     * plays back fine (real frames, not blank) once saved, but retriever.setDataSource(url,
     * headers) kept "succeeding" with a blank bitmap and no exception - i.e. real video data,
     * but MediaMetadataRetriever's own HTTP/seek handling wasn't reading it correctly over the
     * network for these clips. Extracting from a local file sidesteps that entirely, at the
     * cost of downloading the bytes twice for anything the user goes on to also download - an
     * acceptable trade for a working thumbnail on a typically-small preview clip.
     */
    private fun extractVideoFrame(url: String, referer: String?): Bitmap {
        val tempFile = File.createTempFile("thumb_", ".tmp", cacheDir)
        try {
            val builder = withCommonHeaders(Request.Builder().url(url), url)
            if (referer != null) builder.header("Referer", referer)
            httpClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
                val body = response.body ?: throw java.io.IOException("Leere Antwort")
                val written = tempFile.outputStream().use { out ->
                    copyLimited(body.byteStream(), out, MAX_THUMBNAIL_DOWNLOAD_BYTES)
                }
                if (written <= 0L) throw java.io.IOException("Leere Antwort")
            }

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(tempFile.absolutePath)
                // Picking a point roughly a third into the clip's own duration, with
                // OPTION_CLOSEST (exact decode, not just the nearest keyframe), is more likely
                // to land on an actual content frame than a fixed early timestamp would for a
                // short looping clip.
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                val targetUs = if (durationMs != null && durationMs > 0) (durationMs * 1000L) / 3 else 300_000L

                return retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: throw IllegalStateException("Kein Frame extrahierbar")
            } finally {
                retriever.release()
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(8192)
        var total = 0L
        while (total < maxBytes) {
            val toRead = minOf(buffer.size.toLong(), maxBytes - total).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read == -1) break
            output.write(buffer, 0, read)
            total += read
        }
        return total
    }

    companion object {
        private const val MEDIA_LIST_TAG = "media_list"

        // Auto-scroll budget before scanning: up to 20 steps of ~85% viewport height each,
        // waiting 400ms between steps for lazy-loaded content to arrive. Also the safety cap
        // that keeps this from scrolling forever on a genuinely infinite-scroll page.
        private const val MAX_AUTO_SCROLL_STEPS = 20
        private const val AUTO_SCROLL_STEP_DELAY_MS = 400L

        // Thumbnail extraction downloads to a temp file first (see extractVideoFrame) rather
        // than streaming from the network URL directly; capped so a large real video doesn't
        // fully download just to render a list thumbnail.
        private const val MAX_THUMBNAIL_DOWNLOAD_BYTES = 8L * 1024 * 1024
    }
}
