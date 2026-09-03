package de.carstenkeller.videodownloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.IOException
import java.io.InputStream

/**
 * Saves downloaded media into the device's shared media collection (visible in Photos /
 * Google Photos) under an album named "VideoDownloader". On API 29+ (our minSdk) the
 * relative-path folder is created automatically by MediaStore on the first insert, so no
 * separate "create album" step is needed.
 */
object MediaSaver {

    private const val ALBUM_NAME = "VideoDownloader"

    sealed class SaveResult {
        data class Success(val uri: Uri) : SaveResult()
        data class Failure(val message: String) : SaveResult()
    }

    fun saveToGallery(
        context: Context,
        kind: MediaKind,
        fileName: String,
        input: InputStream,
        onProgress: (bytesWritten: Long) -> Unit
    ): SaveResult {
        val resolver = context.contentResolver
        val mimeType = guessMimeType(fileName, kind)

        val collection: Uri
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        if (kind == MediaKind.GIF) {
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME")
        } else {
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ALBUM_NAME")
        }

        val itemUri = resolver.insert(collection, values)
            ?: return SaveResult.Failure("MediaStore-Eintrag konnte nicht erstellt werden")

        return try {
            val out = resolver.openOutputStream(itemUri)
                ?: throw IOException("Kein Output-Stream verfügbar")
            out.use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                var read: Int
                while (input.read(buffer).also { read = it } >= 0) {
                    output.write(buffer, 0, read)
                    total += read
                    onProgress(total)
                }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            SaveResult.Success(itemUri)
        } catch (e: Exception) {
            resolver.delete(itemUri, null, null)
            SaveResult.Failure(e.message ?: "Download fehlgeschlagen")
        }
    }

    private fun guessMimeType(fileName: String, kind: MediaKind): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (kind == MediaKind.GIF) "image/gif" else "video/mp4"
    }
}
