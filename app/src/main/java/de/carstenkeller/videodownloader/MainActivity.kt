package de.carstenkeller.videodownloader

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaExtractor
import android.media.MediaFormat
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
import org.json.JSONObject
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

    // HLS/DASH manifest URLs sniffed from network traffic (see shouldInterceptRequest) for the
    // currently loaded page. Chromium (Android WebView's engine) has no native HLS/DASH
    // playback, so sites use a JS player that fetches the manifest itself - it practically
    // never shows up as a plain <video src> the DOM scanner could see, only as a network
    // request. A thread-safe set since shouldInterceptRequest can be called off the main
    // thread; cleared on an actual host change, same as the media list.
    private val detectedStreamUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
                    detectedStreamUrls.clear()
                }
                if (host != null) lastHost = host
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progressBar.visibility = View.GONE
                if (url != null) binding.addressBar.setText(url)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: android.webkit.WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
                // Observation only - always returns null so normal loading continues
                // untouched; this just lets us see manifest URLs the page's own JS player
                // fetches, which the DOM scanner has no way to see at all.
                val path = request?.url?.path?.lowercase()
                if (path != null && (path.endsWith(".m3u8") || path.endsWith(".mpd"))) {
                    detectedStreamUrls.add(request.url.toString())
                }
                return null
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

    /** [candidateLinks]: see MediaScanner.parseCandidateLinks - link-only fallback candidates. */
    private data class ScanPassResult(val newCount: Int, val candidateLinks: List<String>)

    /**
     * Scans the media currently in the DOM and merges genuinely new finds into the list.
     * Returns how many new items were found, so callers can decide whether to show a
     * "nothing found" message after a whole scroll-and-scan pass.
     */
    private suspend fun scanAndMergeOnce(): ScanPassResult {
        val rawResult = evalJs(binding.webView, MediaScanner.SCAN_JS)
        val scanned = MediaScanner.parseResult(rawResult)
        val candidateLinks = MediaScanner.parseCandidateLinks(rawResult)
        val existing = viewModel.items.value
        val existingUrls = existing.map { it.url }.toSet()
        val newScanned = scanned.filter { it.url !in existingUrls }
        if (newScanned.isEmpty()) return ScanPassResult(0, candidateLinks)

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
                looksLikeGif = media.looksLikeGif,
                // Duration is now always attempted for both real kinds (GIF, VIDEO) - see
                // fetchThumbnails - a poster attribute no longer skips it.
                durationPending = true
            )
        }

        viewModel.setItems(existing + newItems)
        if (supportFragmentManager.findFragmentByTag(MEDIA_LIST_TAG) == null) {
            MediaListBottomSheet().show(supportFragmentManager, MEDIA_LIST_TAG)
        }
        fetchSizes(newItems)
        fetchThumbnails(newItems)
        upgradeFromSourceLinks(newScanned.zip(newItems), currentPageUrl)
        return ScanPassResult(newItems.size, candidateLinks)
    }

    /**
     * Best-effort background upgrade for each newly found item, trying two ways to reach its
     * real, original file:
     * 1. If the page links it to another page (sourceLink - e.g. a search-result/gallery grid
     *    linking a reduced preview through to its source page), crawl that page directly.
     * 2. Otherwise (confirmed by testing: Google Images does NOT wrap its GIF results in real
     *    <a href> links at all, so there's nothing for (1) to find there), simulate a real
     *    click on the matching element in a fresh, hidden load of the current page - Google's
     *    grid opens its results via a JS click handler, not a link, so this is the only way
     *    left to trigger whatever detail/lightbox view it opens and read that instead.
     *
     * Both are best-effort and run after the item is already shown with its own (possibly
     * reduced) data - this only improves the entry in place if a crawl succeeds, and never
     * blocks or delays the visible scan itself.
     */
    private fun upgradeFromSourceLinks(pairs: List<Pair<ScannedMedia, MediaItem>>, pageUrl: String?) {
        // Several items can each resolve to the very same "upgraded" source URL - e.g. two
        // click-simulation runs both landing on the same generic lightbox element because the
        // page reveals it for more than one grid card. Tracked across this whole batch (not
        // per item) and claimed atomically so only the first item to resolve a given URL keeps
        // it; every later claim on the same URL is treated as a duplicate and left unchanged
        // rather than silently downloading the same file twice.
        val claimedUpgradeUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        pairs.forEach { (media, item) ->
            lifecycleScope.launch {
                viewModel.updateItem(item.id) { it.copy(crawlStatus = "Quelle wird geprüft…") }

                val viaLink = media.sourceLink != null
                val candidates = when {
                    viaLink -> crawlSourcePage(media.sourceLink!!)
                    pageUrl != null -> crawlWithClickSimulation(pageUrl, media.url)
                    else -> emptyList()
                }

                val prefix = if (viaLink) "Quelle" else "Quelle (Klick-Simulation)"
                val match = pickBestMatch(media, candidates)
                if (!viaLink && pageUrl == null) {
                    viewModel.updateItem(item.id) { it.copy(crawlStatus = "$prefix: keine Seiten-URL verfügbar") }
                    return@launch
                }
                if (candidates.isEmpty()) {
                    viewModel.updateItem(item.id) {
                        it.copy(crawlStatus = "$prefix: kein Ergebnis (Timeout, leere Seite oder Element nicht gefunden)")
                    }
                    return@launch
                }
                if (match == null) {
                    viewModel.updateItem(item.id) { it.copy(crawlStatus = "$prefix: ${candidates.size} Medien gefunden, keins passte") }
                    return@launch
                }
                if (match.url == item.url) {
                    viewModel.updateItem(item.id) { it.copy(crawlStatus = "$prefix: identisch mit Vorschau") }
                    return@launch
                }
                if (!claimedUpgradeUrls.add(match.url)) {
                    viewModel.updateItem(item.id) {
                        it.copy(crawlStatus = "$prefix: gleiches Ergebnis wie bei einem anderen Eintrag - übersprungen (Duplikat vermieden)")
                    }
                    return@launch
                }

                val newFileName = MediaScanner.deriveFileName(match.url, match.kind, 1)
                viewModel.updateItem(item.id) {
                    it.copy(
                        url = match.url,
                        kind = match.kind,
                        looksLikeGif = match.looksLikeGif,
                        posterUrl = match.posterUrl,
                        sourcePageUrl = media.sourceLink ?: pageUrl,
                        fileName = newFileName,
                        sizeBytes = null,
                        thumbnail = null,
                        thumbnailError = null,
                        crawlStatus = null,
                        durationMs = null,
                        durationUnknown = false,
                        durationPending = true
                    )
                }
                val updated = viewModel.items.value.find { it.id == item.id } ?: return@launch
                fetchSizes(listOf(updated))
                fetchThumbnails(listOf(updated))
            }
        }
    }

    /**
     * Matches by "what it looks like" (GIF-like vs. a genuine video), not by the exact
     * underlying kind - a real bug found from testing: the original preview is very often a
     * <video loop muted> ("GIF-like" but kind == VIDEO), while its source page serves the
     * actual GIF as a plain <img src="*.gif"> (kind == GIF). A strict kind match rejected
     * that source-page candidate entirely, so most upgrades silently found nothing even
     * though a perfectly good replacement existed right there.
     */
    private fun pickBestMatch(target: ScannedMedia, candidates: List<ScannedMedia>): ScannedMedia? {
        fun isGifLike(m: ScannedMedia) = m.kind == MediaKind.GIF || m.looksLikeGif
        return if (isGifLike(target)) {
            candidates.firstOrNull { isGifLike(it) }
        } else {
            candidates.firstOrNull { it.kind == MediaKind.VIDEO && !it.looksLikeGif }
        }
    }

    /**
     * Loads [url] in the hidden crawler WebView, waiting up to [CRAWL_LOAD_TIMEOUT_MS] for it
     * to finish. Returns false (without throwing) if the page never fires a clean load-complete
     * event - some pages don't, and this is a background nice-to-have, not worth hanging on
     * indefinitely. Does NOT acquire [crawlerMutex] itself - callers must hold it, since it's
     * shared by crawlSourcePage and crawlWithClickSimulation, which sometimes call each other.
     */
    private suspend fun loadInCrawler(url: String): Boolean {
        return withTimeoutOrNull(CRAWL_LOAD_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                binding.crawlerWebView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
                binding.crawlerWebView.loadUrl(url)
            }
        } != null
    }

    /**
     * Scans whatever is currently loaded in the crawler WebView, retrying after a few scroll
     * steps if nothing turns up right away (some pages lazy-load their media only once
     * scrolled into view, same as the visible page). Does NOT acquire [crawlerMutex] - same
     * reason as [loadInCrawler].
     */
    private suspend fun scanCrawlerWithRetry(): List<ScannedMedia> {
        var found = MediaScanner.parseResult(evalJs(binding.crawlerWebView, MediaScanner.SCAN_JS))
        var steps = 0
        while (found.isEmpty() && steps < CRAWL_SCROLL_STEPS) {
            evalJs(binding.crawlerWebView, "window.scrollBy(0, Math.round(window.innerHeight * 0.85));")
            delay(AUTO_SCROLL_STEP_DELAY_MS)
            found = MediaScanner.parseResult(evalJs(binding.crawlerWebView, MediaScanner.SCAN_JS))
            steps++
        }
        return found
    }

    /**
     * Loads [url] in the hidden crawler WebView and scans it for media. Serialized via
     * [crawlerMutex] since only one hidden WebView exists. Per the user's explicit call,
     * crawling is allowed to take a while for a good result.
     */
    private suspend fun crawlSourcePage(url: String): List<ScannedMedia> = crawlerMutex.withLock {
        if (!loadInCrawler(url)) return@withLock emptyList()
        delay(CRAWL_SETTLE_DELAY_MS)
        scanCrawlerWithRetry()
    }

    /** JS: finds the element whose src matches [targetMediaUrl] and dispatches a real click on
     * it, returning "true"/"false" as a string (evaluateJavascript's result is always a JSON
     * literal). Used when a page opens its content via a JS click handler instead of a real
     * link - see [crawlWithClickSimulation]. */
    private fun buildClickOnMediaJs(targetMediaUrl: String): String {
        val target = JSONObject.quote(targetMediaUrl)
        return """
            (function() {
              var target = $target;
              function absolutize(url) {
                if (!url) return null;
                try { return new URL(url, document.baseURI).href; } catch (e) { return null; }
              }
              function candidateSrcs(el) {
                var out = [];
                if (el.currentSrc) out.push(el.currentSrc);
                ['src', 'data-src', 'data-original', 'data-lazy-src', 'data-video-src', 'data-url'].forEach(function(attr) {
                  var v = el.getAttribute(attr);
                  if (v) out.push(v);
                });
                return out;
              }
              var found = null;
              document.querySelectorAll('video, img').forEach(function(el) {
                if (found) return;
                if (candidateSrcs(el).some(function(s) { return absolutize(s) === target; })) found = el;
              });
              if (!found) return false;
              try {
                found.scrollIntoView({ block: 'center' });
                found.click();
                return true;
              } catch (e) { return false; }
            })();
        """
    }

    /**
     * Fallback for pages that open their content via a JS click handler instead of a real link
     * - confirmed by testing that Google Images is exactly this case (0 of 12 GIF results had
     * any <a href> at all, so crawlSourcePage's link-following has nothing to follow there).
     * Loads [pageUrl] fresh in the hidden WebView, simulates a real click on the element whose
     * src matches [targetMediaUrl] (see buildClickOnMediaJs), waits for whatever opens, then
     * scans again for anything new. If what's revealed itself carries a source link (e.g. a
     * "visit site" link in an opened detail view), follows that one extra hop too.
     *
     * A confirmed real bug in an earlier version of this function: it only excluded
     * [targetMediaUrl] from the post-click scan, not everything else already present on the
     * page before the click. A grid page like Google Images has many other previews already
     * loaded in the DOM at once, so that "revealed" set was really just whatever other,
     * unrelated grid item happened to come first - and since a fresh reload of the same URL
     * produces the same DOM order every time, EVERY item's "upgrade" converged on that same
     * other item's own short preview, silently overwriting distinct correct downloads with
     * repeated copies of one wrong file. Fixed by capturing a baseline scan before clicking and
     * only treating something as "revealed" if it wasn't already present in that baseline.
     *
     * This is speculative and Google's exact behavior here could not be verified without a
     * live browser to inspect - it depends on the element actually being click-driven, on the
     * resulting UI rendering within the hidden WebView the same way it would visibly, and on
     * the reveal happening within [CLICK_REVEAL_DELAY_MS]/the scroll-retry budget.
     */
    private suspend fun crawlWithClickSimulation(pageUrl: String, targetMediaUrl: String): List<ScannedMedia> =
        crawlerMutex.withLock {
            if (!loadInCrawler(pageUrl)) return@withLock emptyList()
            delay(CRAWL_SETTLE_DELAY_MS)

            val baselineUrls = MediaScanner.parseResult(evalJs(binding.crawlerWebView, MediaScanner.SCAN_JS))
                .map { it.url }
                .toSet() + targetMediaUrl

            val clicked = evalJs(binding.crawlerWebView, buildClickOnMediaJs(targetMediaUrl))
            if (clicked != "true") return@withLock emptyList()
            delay(CLICK_REVEAL_DELAY_MS)

            var revealed = MediaScanner.parseResult(evalJs(binding.crawlerWebView, MediaScanner.SCAN_JS))
                .filter { it.url !in baselineUrls }
            if (revealed.isEmpty()) {
                revealed = scanCrawlerWithRetry().filter { it.url !in baselineUrls }
            }
            if (revealed.isEmpty()) return@withLock emptyList()

            val deeperLink = revealed.firstOrNull { it.sourceLink != null }?.sourceLink
            if (deeperLink != null && loadInCrawler(deeperLink)) {
                delay(CRAWL_SETTLE_DELAY_MS)
                val deeper = scanCrawlerWithRetry()
                if (deeper.isNotEmpty()) return@withLock deeper
            }
            revealed
        }

    /**
     * Fallback for when a scan finds no media at all through normal element-based detection:
     * crawls each duration-badge link candidate (see MediaScanner.parseCandidateLinks - result
     * cards, e.g. a video search tab, that show only a thumbnail + duration with no media
     * element in the DOM until opened) and adds whatever real media those pages contain. Only
     * runs as a last resort, since it means loading several whole pages one by one through the
     * single hidden crawler WebView - slow, but only when the fast path found nothing, and the
     * user explicitly said a slower, more thorough attempt is worth it for video search results.
     */
    private suspend fun crawlCandidateLinksForMedia(candidateLinks: List<String>): Int {
        var totalNew = 0
        for (link in candidateLinks.take(MAX_CANDIDATE_LINK_CRAWLS)) {
            val found = crawlSourcePage(link)
            val existing = viewModel.items.value
            val existingUrls = existing.map { it.url }.toSet()
            val newOnes = found.filter { it.url !in existingUrls }
            if (newOnes.isEmpty()) continue

            val fileNames = MediaScanner.buildFileNames(newOnes, existing.map { it.fileName }.toSet())
            val newItems = newOnes.mapIndexed { index, media ->
                MediaItem(
                    id = media.url,
                    url = media.url,
                    kind = media.kind,
                    fileName = fileNames[index],
                    posterUrl = media.posterUrl,
                    sourcePageUrl = link,
                    looksLikeGif = media.looksLikeGif,
                    durationPending = true
                )
            }
            viewModel.setItems(existing + newItems)
            if (supportFragmentManager.findFragmentByTag(MEDIA_LIST_TAG) == null) {
                MediaListBottomSheet().show(supportFragmentManager, MEDIA_LIST_TAG)
            }
            fetchSizes(newItems)
            fetchThumbnails(newItems)
            totalNew += newItems.size
        }
        return totalNew
    }

    /**
     * Resolves any HLS/DASH manifest URLs sniffed since the last navigation (see
     * shouldInterceptRequest/[detectedStreamUrls]) into downloadable items, skipping any
     * already represented in the list. Returns how many new items were added. A manifest is
     * only sniffed once the page's own JS player actually requests it - if the video is
     * click-to-play and was never started, there is nothing to detect yet; pressing play once
     * before scanning again picks it up.
     */
    private suspend fun resolveDetectedStreams(pageUrl: String?): Int {
        val existingUrls = viewModel.items.value.map { it.url }.toSet()
        val toResolve = detectedStreamUrls.filter { it !in existingUrls }
        var added = 0
        for (manifestUrl in toResolve) {
            added += resolveAndAddStream(manifestUrl, pageUrl)
        }
        return added
    }

    private suspend fun fetchTextBody(url: String, referer: String? = null): String? = withContext(Dispatchers.IO) {
        try {
            val builder = withCommonHeaders(Request.Builder().url(url), url)
            referer?.let { builder.header("Referer", it) }
            httpClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun deriveStreamBaseName(manifestUrl: String): String {
        val lastSegment = try {
            Uri.parse(manifestUrl).lastPathSegment?.substringBefore('?')?.substringBeforeLast('.')
        } catch (e: Exception) {
            null
        }
        val name = lastSegment?.let { runCatching { Uri.decode(it) }.getOrDefault(it) }?.takeIf { it.isNotBlank() }
            ?: "stream"
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    /**
     * Fetches and parses one detected manifest, adding either a downloadable item or (for
     * encrypted HLS or a DASH segment-addressing mode this app doesn't handle) a disabled
     * placeholder row so the user at least sees that something was found. Returns 1 if an item
     * was added, 0 otherwise (including "already handled" and outright fetch/parse failures -
     * treated the same as a normal scan pass finding nothing, not surfaced as a hard error).
     */
    private suspend fun resolveAndAddStream(manifestUrl: String, pageUrl: String?): Int {
        val isDash = manifestUrl.substringBefore('?').lowercase().endsWith(".mpd")
        return if (isDash) {
            val text = fetchTextBody(manifestUrl, pageUrl) ?: return 0
            when (val result = StreamManifest.parseDash(text, manifestUrl)) {
                is StreamManifest.DashResult.Unsupported -> addUnsupportedStreamItem(manifestUrl, pageUrl, result.reason)
                is StreamManifest.DashResult.Plan -> addStreamItem(manifestUrl, pageUrl, result.plan)
            }
        } else {
            var url = manifestUrl
            var mediaPlaylist: HlsParseResult? = null
            repeat(2) {
                if (mediaPlaylist != null) return@repeat
                val text = fetchTextBody(url, pageUrl) ?: return 0
                val parsed = StreamManifest.parseHls(text, url)
                if (parsed.variantUrl != null) url = parsed.variantUrl else mediaPlaylist = parsed
            }
            val result = mediaPlaylist ?: return 0
            when {
                result.encrypted -> addUnsupportedStreamItem(manifestUrl, pageUrl, "Verschlüsselter Stream nicht unterstützt")
                result.segmentUrls.isEmpty() -> 0
                else -> {
                    val ext = result.segmentUrls.first().substringAfterLast('.').substringBefore('?').lowercase()
                    val outputExt = if (ext == "m4s" || ext == "mp4") "mp4" else if (ext == "ts") "ts" else "ts"
                    addStreamItem(
                        manifestUrl, pageUrl,
                        StreamDownloadPlan(StreamKind.HLS, result.initSegmentUrl, result.segmentUrls, outputExtension = outputExt)
                    )
                }
            }
        }
    }

    private suspend fun addStreamItem(manifestUrl: String, pageUrl: String?, plan: StreamDownloadPlan): Int =
        withContext(Dispatchers.Main) {
            val existing = viewModel.items.value
            if (existing.any { it.url == manifestUrl }) return@withContext 0
            val baseName = deriveStreamBaseName(manifestUrl)
            // A DASH stream with separate audio/video representations can't be muxed into one
            // file here (that needs a real media container library this project doesn't have)
            // - saving just the video track is an honest, working partial result rather than a
            // silently broken combined file.
            val note = if (plan.audioSegmentUrls.isNotEmpty()) {
                "Video und Audio sind getrennte Spuren und werden nicht automatisch zusammengeführt - Datei ist stumm"
            } else null
            val item = MediaItem(
                id = manifestUrl,
                url = manifestUrl,
                kind = MediaKind.VIDEO,
                fileName = "$baseName.${plan.outputExtension}",
                sourcePageUrl = pageUrl,
                streamPlan = plan.copy(audioInitUrl = null, audioSegmentUrls = emptyList()),
                streamNote = note
            )
            viewModel.setItems(existing + item)
            if (supportFragmentManager.findFragmentByTag(MEDIA_LIST_TAG) == null) {
                MediaListBottomSheet().show(supportFragmentManager, MEDIA_LIST_TAG)
            }
            fetchSizes(listOf(item))
            fetchThumbnails(listOf(item))
            1
        }

    private suspend fun addUnsupportedStreamItem(manifestUrl: String, pageUrl: String?, reason: String): Int =
        withContext(Dispatchers.Main) {
            val existing = viewModel.items.value
            if (existing.any { it.url == manifestUrl }) return@withContext 0
            val item = MediaItem(
                id = manifestUrl,
                url = manifestUrl,
                kind = MediaKind.VIDEO,
                fileName = "${deriveStreamBaseName(manifestUrl)}.mp4",
                sourcePageUrl = pageUrl,
                selected = false,
                downloadDisabled = true,
                streamNote = "Stream erkannt, aber nicht unterstützt: $reason"
            )
            viewModel.setItems(existing + item)
            if (supportFragmentManager.findFragmentByTag(MEDIA_LIST_TAG) == null) {
                MediaListBottomSheet().show(supportFragmentManager, MEDIA_LIST_TAG)
            }
            1
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
            val firstPass = scanAndMergeOnce()
            var totalNew = firstPass.newCount
            val candidateLinks = LinkedHashSet(firstPass.candidateLinks)

            var lastHeight = -1.0
            var steps = 0
            while (steps < MAX_AUTO_SCROLL_STEPS) {
                evalJs(binding.webView, "window.scrollBy(0, Math.round(window.innerHeight * 0.85));")
                delay(AUTO_SCROLL_STEP_DELAY_MS)
                val pass = scanAndMergeOnce()
                totalNew += pass.newCount
                candidateLinks.addAll(pass.candidateLinks)

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

            // Streaming video (HLS/DASH) is detected independently of the DOM scan - see
            // shouldInterceptRequest - so this always runs, not just as a last-resort fallback.
            totalNew += resolveDetectedStreams(binding.webView.url)

            // Nothing found the fast way (no <video>/<img src=*.gif> element in the DOM at
            // all) - fall back to crawling result cards that only show a thumbnail + duration
            // badge (e.g. a video search tab), one page at a time. Only reached when the
            // normal scan came up empty, so the extra time this takes only shows up then.
            if (totalNew == 0 && candidateLinks.isNotEmpty()) {
                totalNew += crawlCandidateLinksForMedia(candidateLinks.toList())
            }

            binding.btnScan.isEnabled = true
            if (totalNew == 0) {
                // Diagnostic detail appended so a "nothing found" report says exactly what was
                // tried, rather than just "nothing" - the previous silent version gave no way
                // to tell whether the duration-badge link heuristic even matched anything on
                // the page at all.
                val detail = if (candidateLinks.isNotEmpty()) {
                    " (${candidateLinks.size} Link-Kandidat(en) geprüft, keine Medien gefunden)"
                } else {
                    " (keine Link-Kandidaten auf der Seite gefunden)"
                }
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.no_media_found) + detail,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun fetchSizes(items: List<MediaItem>) {
        items.forEach { item ->
            lifecycleScope.launch(Dispatchers.IO) {
                val size = item.streamPlan?.let { estimateStreamSize(it, item.sourcePageUrl) }
                    ?: fetchContentLength(item.url, item.sourcePageUrl)
                if (size != null) {
                    withContext(Dispatchers.Main) {
                        viewModel.updateItem(item.id) { it.copy(sizeBytes = size) }
                    }
                }
            }
        }
    }

    private fun fetchContentLength(url: String, referer: String? = null): Long? {
        return try {
            val headRequest = withCommonHeaders(Request.Builder().url(url), url)
                .apply { referer?.let { header("Referer", it) } }
                .head().build()
            httpClient.newCall(headRequest).execute().use { response ->
                response.header("Content-Length")?.toLongOrNull()?.let { return it }
            }
            val rangeRequest = withCommonHeaders(Request.Builder().url(url), url)
                .apply { referer?.let { header("Referer", it) } }
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
     * Rough estimate only: HEADs/Range-requests just the first media segment and multiplies by
     * the total segment count. Real per-segment sizes vary (especially with variable bitrate),
     * so this is an approximation shown to give a sense of scale, not an exact figure - there
     * is no cheap way to know a segmented stream's exact total size without fetching everything.
     */
    private fun estimateStreamSize(plan: StreamDownloadPlan, referer: String?): Long? {
        val sampleUrl = plan.videoSegmentUrls.firstOrNull() ?: return null
        val perSegmentBytes = fetchContentLength(sampleUrl, referer) ?: return null
        val totalSegments = plan.videoSegmentUrls.size + if (plan.videoInitUrl != null) 1 else 0
        return perSegmentBytes * totalSegments
    }

    /**
     * Thumbnails: GIFs use the file itself. Videos always download and read the actual video
     * file via MediaMetadataRetriever - a real bug found from testing: skipping that whenever
     * the page happened to declare a <video poster> (using the poster image alone instead)
     * left duration permanently unmeasured for any such item, since a poster is just a static
     * image with no length of its own - silently defeating the minimum-length filter for every
     * poster-having video regardless of the slider. The poster is still used as the *shown*
     * thumbnail when it's actually fetchable (typically a lighter, curated preview than
     * whatever timestamp the video extraction lands on), falling back to the extracted frame
     * otherwise - but duration now always comes from the real file.
     */
    private fun fetchThumbnails(items: List<MediaItem>) {
        items.forEach { item ->
            lifecycleScope.launch(Dispatchers.IO) {
                var bitmap: Bitmap? = null
                var durationMs: Long? = null
                // Only set for the two branches below that actually attempt to measure a
                // duration - a stream (its length lives in the manifest, not a downloadable
                // frame) never does, so its durationMs being null isn't a failure worth
                // flagging.
                var durationAttempted = false
                // Captured and shown in the list (see MediaListAdapter) instead of just being
                // dropped: after several rounds of guessing at header/decoder fixes that
                // didn't hold up on all sites, seeing the actual failure reason per item is
                // what's needed to diagnose the remaining cases instead of guessing again.
                var error: String? = null
                try {
                    when {
                        item.streamPlan != null -> bitmap = extractStreamThumbnail(item.streamPlan, item.sourcePageUrl)
                        item.kind == MediaKind.GIF -> {
                            durationAttempted = true
                            val bytes = downloadCapped(item.url, item.sourcePageUrl, MAX_THUMBNAIL_DOWNLOAD_BYTES)
                            bitmap = decodeBitmap(bytes) ?: throw java.io.IOException("Nicht dekodierbar (${bytes.size} Bytes)")
                            durationMs = GifDuration.parseMs(bytes)
                        }
                        else -> {
                            // Duration can only come from the real file - always read it here,
                            // poster or not (see the fetchThumbnails doc comment above). The
                            // poster, when present and actually fetchable, is preferred only
                            // for the *displayed* thumbnail image.
                            durationAttempted = true
                            val frame = extractVideoFrame(item.url, item.sourcePageUrl)
                            durationMs = frame.durationMs
                            bitmap = item.posterUrl?.let { poster ->
                                try {
                                    loadBitmapFromUrl(poster, item.sourcePageUrl)
                                } catch (e: Exception) {
                                    null
                                }
                            } ?: frame.bitmap
                        }
                    }
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                }
                withContext(Dispatchers.Main) {
                    viewModel.updateItem(item.id) {
                        it.copy(
                            thumbnail = bitmap,
                            thumbnailError = if (bitmap == null) error else null,
                            durationMs = durationMs ?: it.durationMs,
                            durationUnknown = durationAttempted && durationMs == null && bitmap != null,
                            durationPending = false
                        )
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
        val bytes = downloadCapped(url, referer, MAX_THUMBNAIL_DOWNLOAD_BYTES)
        return decodeBitmap(bytes) ?: throw java.io.IOException("Nicht dekodierbar (${bytes.size} Bytes)")
    }

    /** Downloads into memory, capped at [maxBytes] so a large file only ever gets partially
     * read for a thumbnail/duration check - see [MAX_THUMBNAIL_DOWNLOAD_BYTES]. */
    private fun downloadCapped(url: String, referer: String?, maxBytes: Long): ByteArray {
        val builder = withCommonHeaders(Request.Builder().url(url), url)
        if (referer != null) builder.header("Referer", referer)
        httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            val body = response.body ?: throw java.io.IOException("Leere Antwort")
            val buffer = java.io.ByteArrayOutputStream()
            val written = copyLimited(body.byteStream(), buffer, maxBytes)
            if (written <= 0L) throw java.io.IOException("Leere Antwort")
            return buffer.toByteArray()
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
     * Cheap heuristic for "this decoded frame carries no real information" - samples a 3x3 grid
     * of pixels and checks whether they're all within a small tolerance of each other (a solid
     * or near-solid color). Real video content almost never passes this at 9 spread-out points;
     * a blank/black/white placeholder frame reliably does.
     */
    private fun isLikelyBlankFrame(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 1 || height <= 1) return true
        val fractions = listOf(0.1f, 0.5f, 0.9f)
        val samples = fractions.flatMap { fx -> fractions.map { fy -> fx to fy } }.map { (fx, fy) ->
            bitmap.getPixel((fx * (width - 1)).toInt(), (fy * (height - 1)).toInt())
        }
        val reference = samples.first()
        val tolerance = 12
        return samples.all { pixel ->
            kotlin.math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(reference)) <= tolerance &&
                kotlin.math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(reference)) <= tolerance &&
                kotlin.math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(reference)) <= tolerance
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
    private data class VideoFrame(val bitmap: Bitmap, val durationMs: Long?)

    private fun extractVideoFrame(url: String, referer: String?): VideoFrame {
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
                // short looping clip. Also fed into the item's durationMs, driving the
                // minimum-length filter in the list.
                // Some of the short preview clips this app deals with (e.g. re-encoded search-
                // result snippets) don't carry the top-level duration metadata this reads - the
                // container is otherwise fine (a frame decodes without issue) but this key comes
                // back null. Falling back to the video track's own format duration (a different
                // read path - MediaExtractor's demuxer, not the metadata table) recovers it for
                // exactly that case instead of silently leaving the item unfiltered by length.
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: extractDurationViaTrackFormat(tempFile.absolutePath)
                val targetUs = if (durationMs != null && durationMs > 0) (durationMs * 1000L) / 3 else 300_000L

                // MediaMetadataRetriever can "succeed" with a technically valid but visually
                // blank/flat-colored bitmap for a given timestamp (the exact failure mode that
                // originally led to reading from a local file at all, above) - so rather than
                // trust the first non-null frame, try a few candidate timestamps and keep the
                // first one that actually looks like real content, falling back to whichever
                // decoded frame was found if every candidate looks blank.
                val candidateTimestampsUs = listOfNotNull(
                    targetUs,
                    0L,
                    if (durationMs != null && durationMs > 0) (durationMs * 1000L * 2) / 3 else null
                ).distinct()
                var bitmap: Bitmap? = null
                for (ts in candidateTimestampsUs) {
                    val candidate = retriever.getFrameAtTime(ts, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                    if (bitmap == null) bitmap = candidate
                    if (!isLikelyBlankFrame(candidate)) {
                        bitmap = candidate
                        break
                    }
                }
                val finalBitmap = bitmap ?: throw IllegalStateException("Kein Frame extrahierbar")
                return VideoFrame(finalBitmap, durationMs)
            } finally {
                retriever.release()
            }
        } finally {
            tempFile.delete()
        }
    }

    /** Longest track duration reported by the container's own format, in ms - a fallback for
     * clips whose top-level duration metadata (see [extractVideoFrame]) is missing. */
    private fun extractDurationViaTrackFormat(path: String): Long? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            var longestMs: Long? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    val ms = format.getLong(MediaFormat.KEY_DURATION) / 1000
                    if (longestMs == null || ms > longestMs) longestMs = ms
                }
            }
            longestMs
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    /**
     * Downloads just the init segment (if any) plus the first media segment - enough for most
     * MPEG-TS/fMP4 streams to yield a readable first frame, without fetching the whole
     * (potentially very long) stream just for a list thumbnail.
     */
    private fun extractStreamThumbnail(plan: StreamDownloadPlan, referer: String?): Bitmap {
        val previewSegments = listOfNotNull(plan.videoInitUrl) + plan.videoSegmentUrls.take(1)
        if (previewSegments.isEmpty()) throw IllegalStateException("Keine Segmente")

        val tempFile = File.createTempFile("streamthumb_", ".tmp", cacheDir)
        try {
            tempFile.outputStream().use { out ->
                previewSegments.forEach { segmentUrl ->
                    val builder = withCommonHeaders(Request.Builder().url(segmentUrl), segmentUrl)
                    if (referer != null) builder.header("Referer", referer)
                    httpClient.newCall(builder.build()).execute().use { response ->
                        if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
                        val body = response.body ?: throw java.io.IOException("Leere Antwort")
                        copyLimited(body.byteStream(), out, MAX_THUMBNAIL_DOWNLOAD_BYTES)
                    }
                }
            }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(tempFile.absolutePath)
                return retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST)
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

        // Background source-page crawling (crawlSourcePage/crawlCandidateLinksForMedia): more
        // generous than the visible scan's own timing since it runs after the item is already
        // shown, and per explicit user request is allowed to take longer for a better result.
        private const val CRAWL_LOAD_TIMEOUT_MS = 15_000L
        private const val CRAWL_SETTLE_DELAY_MS = 1000L
        private const val CRAWL_SCROLL_STEPS = 3

        // How long to wait after simulating a click for whatever detail/lightbox view it opens
        // to render (see crawlWithClickSimulation).
        private const val CLICK_REVEAL_DELAY_MS = 1200L
        private const val MAX_CANDIDATE_LINK_CRAWLS = 6
    }
}
