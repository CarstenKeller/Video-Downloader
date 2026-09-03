package de.carstenkeller.videodownloader

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val DEFAULT_URL = "https://www.google.com"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MediaListViewModel by viewModels()
    private val httpClient = OkHttpClient()

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

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
     * Scans the current page and *merges* new finds into the existing list rather than
     * replacing it, so scrolling to load more lazy-loaded media and scanning again grows
     * the list instead of resetting it. Also recurses into same-origin iframes (see
     * MediaScanner.SCAN_JS); cross-origin iframes cannot be inspected at all - the
     * browser's same-origin policy blocks that unconditionally, not just here.
     */
    private fun scanCurrentPage() {
        binding.webView.evaluateJavascript(MediaScanner.SCAN_JS) { rawResult ->
            val scanned = MediaScanner.parseResult(rawResult)
            val existing = viewModel.items.value
            val existingUrls = existing.map { it.url }.toSet()
            val newScanned = scanned.filter { it.url !in existingUrls }

            if (newScanned.isEmpty()) {
                if (existing.isEmpty()) {
                    Toast.makeText(this, R.string.no_media_found, Toast.LENGTH_SHORT).show()
                }
                return@evaluateJavascript
            }

            val fileNames = MediaScanner.buildFileNames(newScanned, existing.map { it.fileName }.toSet())
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
            fetchThumbnails(newItems)
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
            val headRequest = Request.Builder().url(url).head().build()
            httpClient.newCall(headRequest).execute().use { response ->
                response.header("Content-Length")?.toLongOrNull()?.let { return it }
            }
            val rangeRequest = Request.Builder().url(url).header("Range", "bytes=0-0").get().build()
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
                val bitmap = try {
                    when {
                        item.kind == MediaKind.GIF -> loadBitmapFromUrl(item.url)
                        item.posterUrl != null -> loadBitmapFromUrl(item.posterUrl)
                        else -> extractVideoFrame(item.url)
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

    private fun loadBitmapFromUrl(url: String): Bitmap? {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bytes = response.body?.bytes() ?: return null
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    private fun extractVideoFrame(url: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(url, HashMap<String, String>())
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
    }
}
