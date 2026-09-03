package de.carstenkeller.videodownloader

import android.net.Uri
import org.json.JSONArray
import org.json.JSONTokener

data class ScannedMedia(
    val url: String,
    val kind: MediaKind,
    val posterUrl: String? = null,
    // True for a <video> that behaves like a "GIF" (loop + muted, the common pattern sites
    // use to serve GIF-style content as a much smaller video file). Display-only signal -
    // the file itself is still a real video and is saved as one.
    val looksLikeGif: Boolean = false,
    // The nearest wrapping <a href> around the found element, when it points somewhere other
    // than the media file itself - the common pattern for search-result/gallery grids that
    // link a (often reduced/preview) thumbnail through to its original source page. Used to
    // opportunistically crawl that page for the real, full file - best-effort: only present
    // when the page happens to expose such a link at all.
    val sourceLink: String? = null
)

/**
 * Finds <video>/<source> elements, .gif <img> tags and direct links to video/gif files
 * in the currently loaded page - including same-origin <iframe> content, recursively.
 * Blob: and data: URIs are intentionally skipped since they cannot be re-downloaded with
 * a plain HTTP request (e.g. MSE-based streaming players such as YouTube commonly only
 * expose a blob: URL). Cross-origin iframes cannot be inspected either - the browser's
 * same-origin policy blocks script access to their content, with no workaround from JS.
 */
object MediaScanner {

    const val SCAN_JS = """
        (function() {
          var urls = [];
          var seen = {};
          function absolutize(url, doc) {
            if (!url) return null;
            try { return new URL(url, doc.baseURI).href; } catch (e) { return null; }
          }
          function add(src, kind, poster, doc, looksLikeGif, sourceLink) {
            var abs = absolutize(src, doc);
            if (!abs) return;
            if (abs.indexOf('blob:') === 0 || abs.indexOf('data:') === 0) return;
            if (seen[abs]) return;
            seen[abs] = true;
            urls.push({
              url: abs, kind: kind, poster: absolutize(poster, doc), looksLikeGif: !!looksLikeGif,
              sourceLink: (sourceLink && sourceLink !== abs) ? sourceLink : null
            });
          }
          function closestAnchorHref(el, doc) {
            try {
              var a = el.closest ? el.closest('a[href]') : null;
              if (!a) return null;
              var href = a.getAttribute('href');
              if (!href || href.indexOf('#') === 0 || href.indexOf('javascript:') === 0) return null;
              return absolutize(href, doc);
            } catch (e) { return null; }
          }
          var videoExt = /\.(mp4|webm|mov|m4v|mkv|3gp|avi)(\?|#|${'$'})/i;
          var gifExt = /\.gif(\?|#|${'$'})/i;
          var lazyAttrs = ['src', 'data-src', 'data-original', 'data-lazy-src', 'data-video-src', 'data-url'];
          function candidateSrcs(el) {
            var out = [];
            if (el.currentSrc) out.push(el.currentSrc);
            lazyAttrs.forEach(function(attr) {
              var v = el.getAttribute(attr);
              if (v) out.push(v);
            });
            return out;
          }
          function scanDocument(doc) {
            try {
              doc.querySelectorAll('video').forEach(function(v) {
                var poster = v.getAttribute('poster');
                var gifLike = v.hasAttribute('loop') && (v.muted || v.hasAttribute('muted'));
                var link = closestAnchorHref(v, doc);
                candidateSrcs(v).forEach(function(src) { add(src, 'video', poster, doc, gifLike, link); });
                v.querySelectorAll('source').forEach(function(s) {
                  candidateSrcs(s).forEach(function(src) { add(src, 'video', poster, doc, gifLike, link); });
                });
              });
              doc.querySelectorAll('img').forEach(function(img) {
                var link = closestAnchorHref(img, doc);
                candidateSrcs(img).forEach(function(s) {
                  if (gifExt.test(s)) add(s, 'gif', null, doc, false, link);
                });
              });
              doc.querySelectorAll('a[href]').forEach(function(a) {
                var href = a.getAttribute('href') || '';
                if (videoExt.test(href)) add(href, 'video', null, doc);
                else if (gifExt.test(href)) add(href, 'gif', null, doc);
              });
              doc.querySelectorAll('iframe').forEach(function(frame) {
                try {
                  var innerDoc = frame.contentDocument;
                  if (innerDoc) scanDocument(innerDoc);
                } catch (e) {
                  // Cross-origin iframe: blocked by the browser's same-origin policy.
                }
              });
            } catch (e) {
              // Defensive: one broken frame should not abort the whole scan.
            }
          }
          scanDocument(document);
          return JSON.stringify(urls);
        })();
    """

    fun parseResult(rawEvaluateResult: String?): List<ScannedMedia> {
        if (rawEvaluateResult == null || rawEvaluateResult == "null") return emptyList()
        val jsonString = try {
            JSONTokener(rawEvaluateResult).nextValue() as? String
        } catch (e: Exception) {
            null
        } ?: return emptyList()

        val array = try {
            JSONArray(jsonString)
        } catch (e: Exception) {
            return emptyList()
        }

        val result = mutableListOf<ScannedMedia>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val url = obj.optString("url").takeIf { it.isNotBlank() } ?: continue
            val kind = if (obj.optString("kind") == "gif") MediaKind.GIF else MediaKind.VIDEO
            val poster = obj.optString("poster").takeIf { it.isNotBlank() && it != "null" }
            val looksLikeGif = obj.optBoolean("looksLikeGif", false)
            val sourceLink = obj.optString("sourceLink").takeIf { it.isNotBlank() && it != "null" }
            result.add(ScannedMedia(url, kind, poster, looksLikeGif, sourceLink))
        }
        return result
    }

    /**
     * Builds unique, filesystem-safe file names for a freshly scanned list of media.
     * [alreadyUsedNames] lets repeated scans (e.g. after scrolling for more lazy-loaded
     * content) avoid colliding with files already shown from an earlier scan.
     */
    fun buildFileNames(scanned: List<ScannedMedia>, alreadyUsedNames: Set<String> = emptySet()): List<String> {
        val usedNames = mutableMapOf<String, Int>()
        alreadyUsedNames.forEach { usedNames[it] = 1 }
        return scanned.mapIndexed { index, media ->
            var name = deriveFileName(media.url, media.kind, index + 1)
            val occurrence = usedNames.getOrDefault(name, 0)
            usedNames[name] = occurrence + 1
            if (occurrence > 0) {
                val dot = name.lastIndexOf('.')
                name = if (dot > 0) {
                    "${name.substring(0, dot)}_$occurrence${name.substring(dot)}"
                } else {
                    "${name}_$occurrence"
                }
                usedNames[name] = 1
            }
            name
        }
    }

    fun deriveFileName(url: String, kind: MediaKind, fallbackIndex: Int): String {
        val defaultExt = if (kind == MediaKind.GIF) "gif" else "mp4"
        val lastSegment = try {
            Uri.parse(url).lastPathSegment?.substringBefore('?')
        } catch (e: Exception) {
            null
        }
        var name = lastSegment?.let { runCatching { Uri.decode(it) }.getOrDefault(it) }.orEmpty()
        if (name.isBlank()) {
            name = "media_$fallbackIndex.$defaultExt"
        } else if (!name.contains('.')) {
            name = "$name.$defaultExt"
        }
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}
