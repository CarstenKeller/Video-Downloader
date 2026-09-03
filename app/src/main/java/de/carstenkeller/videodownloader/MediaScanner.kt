package de.carstenkeller.videodownloader

import android.net.Uri
import org.json.JSONArray
import org.json.JSONTokener

data class ScannedMedia(val url: String, val kind: MediaKind)

/**
 * Finds <video>/<source> elements, .gif <img> tags and direct links to video/gif files
 * in the currently loaded page. Blob: and data: URIs are intentionally skipped since
 * they cannot be re-downloaded with a plain HTTP request (e.g. MSE-based streaming
 * players such as YouTube commonly only expose a blob: URL).
 */
object MediaScanner {

    const val SCAN_JS = """
        (function() {
          var urls = [];
          var seen = {};
          function add(src, kind) {
            if (!src) return;
            var abs;
            try { abs = new URL(src, document.baseURI).href; } catch (e) { return; }
            if (abs.indexOf('blob:') === 0 || abs.indexOf('data:') === 0) return;
            if (seen[abs]) return;
            seen[abs] = true;
            urls.push({url: abs, kind: kind});
          }
          var videoExt = /\.(mp4|webm|mov|m4v|mkv|3gp|avi)(\?|#|${'$'})/i;
          var gifExt = /\.gif(\?|#|${'$'})/i;
          document.querySelectorAll('video').forEach(function(v) {
            add(v.currentSrc, 'video');
            add(v.getAttribute('src'), 'video');
            v.querySelectorAll('source').forEach(function(s) {
              add(s.getAttribute('src'), 'video');
            });
          });
          document.querySelectorAll('img').forEach(function(img) {
            var s = img.currentSrc || img.getAttribute('src') || '';
            if (gifExt.test(s)) add(s, 'gif');
          });
          document.querySelectorAll('a[href]').forEach(function(a) {
            var href = a.getAttribute('href') || '';
            if (videoExt.test(href)) add(href, 'video');
            else if (gifExt.test(href)) add(href, 'gif');
          });
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
            result.add(ScannedMedia(url, kind))
        }
        return result
    }

    /** Builds unique, filesystem-safe file names for a freshly scanned list of media. */
    fun buildFileNames(scanned: List<ScannedMedia>): List<String> {
        val usedNames = mutableMapOf<String, Int>()
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
            }
            name
        }
    }

    private fun deriveFileName(url: String, kind: MediaKind, fallbackIndex: Int): String {
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
