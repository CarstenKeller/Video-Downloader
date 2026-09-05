package de.carstenkeller.videodownloader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists browser bookmarks (saved web pages, not media items) as a JSON array in
 * SharedPreferences - there are only ever a handful of entries, so a full read/rewrite on
 * every change is simpler than a real database and cheap enough not to matter.
 */
class BookmarkStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAll(): List<Bookmark> {
        val raw = prefs.getString(KEY_BOOKMARKS, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val url = obj.optString("url").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Bookmark(title = obj.optString("title", url), url = url)
        }
    }

    fun isBookmarked(url: String): Boolean = getAll().any { it.url == url }

    fun add(bookmark: Bookmark) {
        val existing = getAll().filterNot { it.url == bookmark.url }
        save(existing + bookmark)
    }

    fun remove(url: String) {
        save(getAll().filterNot { it.url == url })
    }

    private fun save(bookmarks: List<Bookmark>) {
        val array = JSONArray()
        bookmarks.forEach { bookmark ->
            array.put(
                JSONObject().apply {
                    put("title", bookmark.title)
                    put("url", bookmark.url)
                }
            )
        }
        prefs.edit().putString(KEY_BOOKMARKS, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "bookmarks"
        private const val KEY_BOOKMARKS = "bookmarks_json"
    }
}
