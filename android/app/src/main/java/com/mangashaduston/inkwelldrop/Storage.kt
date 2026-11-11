package com.mangashaduston.inkwelldrop

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.mangashaduston.inkwelldrop.core.FileSink
import com.mangashaduston.inkwelldrop.core.ItemMeta
import com.mangashaduston.inkwelldrop.core.SinkFactory
import com.mangashaduston.inkwelldrop.core.Source
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

fun guessMime(name: String, offered: String?): String {
    if (!offered.isNullOrBlank() && offered.contains('/') && offered != "application/octet-stream") return offered
    val ext = name.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}

/** Saves received files where the user expects them: photos and videos in the gallery, the rest in Downloads. */
class MediaStoreSinks(private val ctx: Context) : SinkFactory {
    override fun create(item: ItemMeta): FileSink {
        val mime = guessMime(item.name, item.mime)
        val (collection, folder) = when {
            mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
            mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
            mime.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MUSIC
            else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/Inkwell Drop")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(collection, values) ?: throw IOException("Couldn't create ${item.name}")
        val out: OutputStream = try {
            resolver.openOutputStream(uri) ?: throw IOException("Couldn't open ${item.name}")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        val buffered = out.buffered(256 * 1024)
        return object : FileSink {
            override fun write(buf: ByteArray, off: Int, len: Int) = buffered.write(buf, off, len)

            override fun finish(): String {
                buffered.close()
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                return uri.toString()
            }

            override fun abort() {
                try {
                    buffered.close()
                } catch (_: Exception) {
                }
                resolver.delete(uri, null, null)
            }
        }
    }
}

/** A file shared to Inkwell Drop by another app, or picked by the user. */
class UriSource private constructor(
    private val ctx: Context,
    private val uri: Uri,
    override val meta: ItemMeta,
    private val copy: File?,
) : Source {
    override fun open(): InputStream =
        copy?.inputStream() ?: ctx.contentResolver.openInputStream(uri) ?: throw IOException("Can't read ${meta.name}")

    companion object {
        /** Reads the name and size. Call off the main thread: a file with no known size is copied to measure it. */
        fun from(ctx: Context, uri: Uri): UriSource {
            val resolver = ctx.contentResolver
            var name: String? = null
            var size = -1L
            try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        if (!c.isNull(0)) name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            } catch (_: Exception) {
            }
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            var fileName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            if (!fileName.contains('.')) {
                MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { fileName += ".$it" }
            }
            var copy: File? = null
            if (size < 0) {
                size = try {
                    resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
                } catch (_: Exception) {
                    -1
                }
            }
            if (size < 0) {
                val dir = File(ctx.cacheDir, "outgoing").apply { mkdirs() }
                val f = File.createTempFile("share", null, dir)
                resolver.openInputStream(uri)?.use { ins -> f.outputStream().use { ins.copyTo(it) } }
                    ?: throw IOException("Can't read $fileName")
                copy = f
                size = f.length()
            }
            return UriSource(ctx, uri, ItemMeta(fileName, size, mime), copy)
        }
    }
}

fun formatBytes(n: Long): String = when {
    n >= 1L shl 30 -> "%.1f GB".format(n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> "%.1f MB".format(n / (1L shl 20).toDouble())
    n >= 1L shl 10 -> "%d KB".format(n shr 10)
    else -> "$n bytes"
}
