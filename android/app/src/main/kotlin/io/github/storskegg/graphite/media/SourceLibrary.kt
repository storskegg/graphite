package io.github.storskegg.graphite.media

import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.nio.file.Files

/** A file in scope on the source. */
data class SourceFile(
    /** Relative to shared storage, `/`-separated: `DCIM/Camera/PXL_1.jpg`. */
    val path: String,
    val file: File,
    val size: Long,
    val modifiedMs: Long,
    val mime: String,
    val dateAddedMs: Long?,
    val dateTakenMs: Long?,
    val androidMedia: Boolean,
)

/** What is in scope on the source, read with all-files access. */
object SourceLibrary {
    /** Shared-storage folders in scope (media.md, "What is in scope"). */
    val ROOTS = listOf(
        "DCIM", "Pictures", "Movies", "Music", "Recordings", "Audiobooks", "Podcasts",
        "Ringtones", "Alarms", "Notifications", "Download", "Documents",
    )
    const val ANDROID_MEDIA = "Android/media"

    /**
     * Every in-scope file, `Android/media/` included (marked) so the
     * inventory can offer it. Dot-files and dot-folders are skipped: thumbnails,
     * MediaStore's `.trashed-` and `.pending-` items. With [testScope], only
     * files with a folder of that name in their path.
     */
    fun list(c: Context, testScope: String?): List<SourceFile> {
        val root = Environment.getExternalStorageDirectory()
        val rows = mediaStoreRows(c)
        val out = ArrayList<SourceFile>()
        fun walk(dir: File, rel: String, androidMedia: Boolean) {
            val children = dir.listFiles() ?: return
            for (f in children.sortedBy { it.name }) {
                if (f.name.startsWith(".") || Files.isSymbolicLink(f.toPath())) continue
                val p = "$rel/${f.name}"
                if (f.isDirectory) { walk(f, p, androidMedia); continue }
                if (!f.isFile) continue
                if (testScope != null && testScope !in p.split('/').dropLast(1)) continue
                val row = rows[f.path]
                out += SourceFile(
                    path = p, file = f, size = f.length(), modifiedMs = f.lastModified(),
                    mime = row?.mime ?: mimeFor(f.name), dateAddedMs = row?.addedS?.times(1000),
                    dateTakenMs = row?.takenMs, androidMedia = androidMedia,
                )
            }
        }
        for (r in ROOTS) walk(File(root, r), r, false)
        walk(File(root, ANDROID_MEDIA), ANDROID_MEDIA, true)
        // The copy runs in DATE_ADDED order, so the manifest is in that order too.
        return out.sortedWith(compareBy<SourceFile>({ it.dateAddedMs ?: Long.MAX_VALUE }, { it.path }))
    }

    fun inventory(files: List<SourceFile>, testScope: String?): Inventory {
        var photos = Tally(); var videos = Tally(); var audio = Tally(); var other = Tally(); var am = Tally()
        for (f in files) {
            if (f.androidMedia) { am += f.size; continue }
            when (category(f.mime)) {
                "photos" -> photos += f.size
                "videos" -> videos += f.size
                "audio" -> audio += f.size
                else -> other += f.size
            }
        }
        return Inventory(photos, videos, audio, other, am, testScope.orEmpty())
    }

    fun category(mime: String) = when {
        mime.startsWith("image/") -> "photos"
        mime.startsWith("video/") -> "videos"
        mime.startsWith("audio/") -> "audio"
        else -> "other files"
    }

    private class Row(val mime: String?, val addedS: Long?, val takenMs: Long?)

    private fun mediaStoreRows(c: Context): Map<String, Row> {
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val cols = arrayOf(MediaColumns.DATA, MediaColumns.MIME_TYPE, MediaColumns.DATE_ADDED, MediaColumns.DATE_TAKEN)
        val out = HashMap<String, Row>()
        c.contentResolver.query(uri, cols, Bundle(), null)?.use { cur ->
            while (cur.moveToNext()) {
                val path = cur.getString(0) ?: continue
                out[path] = Row(
                    cur.getString(1),
                    if (cur.isNull(2)) null else cur.getLong(2),
                    if (cur.isNull(3)) null else cur.getLong(3),
                )
            }
        }
        return out
    }

    fun mimeFor(name: String) = knownMimeFor(name) ?: "application/octet-stream"

    fun knownMimeFor(name: String): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('/').substringAfterLast('.', "").lowercase())

    /** A source file's MediaStore item: in its media collection for photos, videos and audio, else in Files. */
    data class Item(val uri: android.net.Uri, val media: Boolean)

    /** The MediaStore item for a source file, for removal; null when MediaStore doesn't know it. */
    fun mediaItem(c: Context, f: File): Item? {
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val cols = arrayOf(MediaColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE)
        c.contentResolver.query(files, cols, "${MediaColumns.DATA} = ?", arrayOf(f.path), null)?.use { cur ->
            if (!cur.moveToFirst()) return null
            val id = cur.getLong(0)
            val vol = MediaStore.VOLUME_EXTERNAL_PRIMARY
            val collection = when (cur.getInt(1)) {
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.getContentUri(vol)
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.getContentUri(vol)
                MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> MediaStore.Audio.Media.getContentUri(vol)
                else -> null
            }
            return Item(android.content.ContentUris.withAppendedId(collection ?: files, id), collection != null)
        }
        return null
    }
}
