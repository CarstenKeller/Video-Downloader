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
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
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
     * Runs a JS expression and suspends until the result comes back, instead of the raw
     * callback-based evaluateJavascript API - lets scanCurrentPage() read as ordinary
     * sequential code even though it now does several JS round-trips (scrolling, then
     * scanning) instead of just one.
     */
    private suspend fun evalJs(script: String): String? = suspendCancellableCoroutine { cont ->
        binding.webView.evaluateJavascript(script) { result ->
            if (cont.isActive) cont.resume(result)
        }
    }

    /**
     * Scrolls the page from top to bottom in steps, pausing between each so that
     * scroll-triggered lazy-loading (IntersectionObserver-based images/videos, "load more"
     * sections) has a chance to fire - the same mechanism a manual scroll relies on, just
     * driven from code so the user doesn't have to do it by hand before every scan.
     *
     * This is a best-effort simulation, not a guarantee: a page that only creates video
     * elements once truly visible on screen (rather than pre-loading nearby content) will
     * still only reveal as much as this scroll budget covers, and an infinite-scroll feed
     * has no real "bottom" - the step/time cap below is what keeps this from running
     * forever on one.
     */
    private suspend fun autoScrollThroughPage() {
        var lastHeight = -1.0
        var steps = 0
        while (steps < MAX_AUTO_SCROLL_STEPS) {
            evalJs("window.scrollBy(0, Math.round(window.innerHeight * 0.85));")
            delay(AUTO_SCROLL_STEP_DELAY_MS)
            val height = evalJs(
                "Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);"
            )?.toDoubleOrNull() ?: break
            val scrollY = evalJs("window.scrollY;")?.toDoubleOrNull() ?: 0.0
            steps++
            val reachedBottom = scrollY + binding.webView.height >= height - 50
            if (reachedBottom && height <= lastHeight + 5) break
            lastHeight = height
        }
        evalJs("window.scrollTo(0, 0);")
        delay(150)
    }

    /**
     * Scans the current page and *merges* new finds into the existing list rather than
     * replacing it, so scanning again (e.g. after the page itself loaded more content)
     * grows the list instead of resetting it. Also recurses into same-origin iframes (see
     * MediaScanner.SCAN_JS); cross-origin iframes cannot be inspected at all - the
     * browser's same-origin policy blocks that unconditionally, not just here.
     */
    private fun scanCurrentPage() {
        // Immediate feedback on tap, independent of how long the scroll+scan takes - so it
        // reads as "working" rather than as the button doing nothing.
        binding.btnScan.isEnabled = false
        lifecycleScope.launch {
            autoScrollThroughPage()
            val rawResult = evalJs(MediaScanner.SCAN_JS)
            binding.btnScan.isEnabled = true

            val scanned = MediaScanner.parseResult(rawResult)
            val existing = viewModel.items.value
            val existingUrls = existing.map { it.url }.toSet()
            val newScanned = scanned.filter { it.url !in existingUrls }

            if (newScanned.isEmpty()) {
                val messageRes = if (existing.isEmpty()) R.string.no_media_found else R.string.no_new_media_found
                Toast.makeText(this@MainActivity, messageRes, Toast.LENGTH_SHORT).show()
                if (existing.isNotEmpty() && supportFragmentManager.findFragmentByTag(MEDIA_LIST_TAG) == null) {
                    MediaListBottomSheet().show(supportFragmentManager, MEDIA_LIST_TAG)
                }
                return@launch
            }

            val fileNames = MediaScanner.buildFileNames(newScanned, existing.map { it.fileName }.toSet())
            val currentPageUrl = binding.webView.url
            val newItems = newScanned.mapIndexed { index, media ->
                MediaItem(
                    id = media.url,
                    url = media.url,
                    kind = media.kind,
                    fileName = fileNames[index],
                    posterUrl = media.posterUrl
                )
            }

            viewModel.setItems(existing + newItems)
            if (supportFragmentManager.findFragmentByTag(MEDIA_LIST_TAG) == null) {
                MediaListBottomSheet().show(supportFragmentManager, MEDIA_LIST_TAG)
            }
            fetchSizes(newItems)
            fetchThumbnails(newItems, currentPageUrl)
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
            val headRequest = Request.Builder().url(url).header("User-Agent", USER_AGENT).head().build()
            httpClient.newCall(headRequest).execute().use { response ->
                response.header("Content-Length")?.toLongOrNull()?.let { return it }
            }
            val rangeRequest = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
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
    private fun fetchThumbnails(items: List<MediaItem>, pageUrl: String?) {
        items.forEach { item ->
            lifecycleScope.launch(Dispatchers.IO) {
                val bitmap = try {
                    when {
                        item.kind == MediaKind.GIF -> loadBitmapFromUrl(item.url, pageUrl)
                        item.posterUrl != null -> loadBitmapFromUrl(item.posterUrl, pageUrl)
                        else -> extractVideoFrame(item.url, pageUrl)
                    }
                } catch (e: Exception) {
                    null
                }
                if (bitmap != null) {
                    withContext(Dispatchers.Main) {
                        viewModel.updateItem(item.id) { it.copy(thumbnail = bitmap) }
                    }
                }
            }
        }
    }

    private fun loadBitmapFromUrl(url: String, referer: String?): Bitmap? {
        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        if (referer != null) builder.header("Referer", referer)
        httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val bytes = response.body?.bytes() ?: return null
            return decodeBitmap(bytes)
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

    private fun extractVideoFrame(url: String, referer: String?): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            val headers = mutableMapOf("User-Agent" to USER_AGENT)
            if (referer != null) headers["Referer"] = referer
            retriever.setDataSource(url, headers)
            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    companion object {
        private const val MEDIA_LIST_TAG = "media_list"

        // Auto-scroll budget before scanning: up to 20 steps of ~85% viewport height each,
        // waiting 400ms between steps for lazy-loaded content to arrive. Also the safety cap
        // that keeps this from scrolling forever on a genuinely infinite-scroll page.
        private const val MAX_AUTO_SCROLL_STEPS = 20
        private const val AUTO_SCROLL_STEP_DELAY_MS = 400L

        // Some CDNs reject requests without a browser-like User-Agent (OkHttp's and
        // MediaMetadataRetriever's defaults are easy to fingerprint and block). Used for
        // size lookups, poster/GIF thumbnail fetches, and video frame extraction.
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
