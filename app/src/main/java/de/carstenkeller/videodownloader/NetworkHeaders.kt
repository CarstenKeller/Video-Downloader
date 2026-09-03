package de.carstenkeller.videodownloader

import android.webkit.CookieManager

/**
 * Headers shared between the app's own OkHttp requests (size lookups, thumbnail/poster
 * fetches, downloads) and MediaMetadataRetriever's video frame extraction.
 */
object NetworkHeaders {
    // Some CDNs reject requests without a browser-like User-Agent (OkHttp's and
    // MediaMetadataRetriever's defaults are easy to fingerprint and block).
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    /**
     * The app's own network calls use a separate OkHttp client from the WebView, which has
     * its own cookie jar (session/auth tokens, hotlink-protection cookies set while
     * browsing). Sharing those cookies via CookieManager lets our requests look like a
     * continuation of the same browsing session instead of a cookie-less stranger.
     */
    fun cookiesFor(url: String): String? = CookieManager.getInstance().getCookie(url)
}
