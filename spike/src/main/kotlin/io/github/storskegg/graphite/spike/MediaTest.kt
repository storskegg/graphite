package io.github.storskegg.graphite.spike

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Q5: can the target's MediaStore rows carry the source's DATE_TAKEN and mtime?
class MediaTest(val a: MainActivity) {
    val taken = 1560602096000L      // 2019-06-15T12:34:56Z
    val modS = 1577934245L          // 2020-01-02T03:04:05Z
    val exifDate = "2017:03:04 05:06:07"
    val dir = "Pictures/graphite-spike"
    val stamp = System.currentTimeMillis() / 1000
    val cr get() = a.contentResolver
    val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun jpeg(exif: String?, offset: String? = null): ByteArray {
        val f = File(a.cacheDir, "t.jpg")
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 40, 90)) }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (exif != null) ExifInterface(f.path).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exif)
            setAttribute(ExifInterface.TAG_DATETIME, exif)
            if (offset != null) setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
            saveAttributes()
        }
        return f.readBytes()
    }

    fun run() {
        a.log("all-files access: ${Environment.isExternalStorageManager()}")
        a.log("want DATE_TAKEN=$taken (2019-06-15) DATE_MODIFIED=$modS (2020-01-02); EXIF variant has $exifDate")
        variant("A-insert", null, atInsert = true, atPublish = false, after = false, touch = false)
        variant("B-publish", null, atInsert = false, atPublish = true, after = false, touch = false)
        variant("C-after", null, atInsert = false, atPublish = false, after = true, touch = false)
        variant("D-touch", null, atInsert = true, atPublish = true, after = true, touch = true)
        variant("E-exif-touch", exifDate, atInsert = true, atPublish = true, after = true, touch = true)
        variant("G-exif-offset", exifDate, atInsert = true, atPublish = true, after = false, touch = true, offset = "+02:00")
        variant("H-touch-first", null, atInsert = true, atPublish = true, after = false, touch = false, touchFirst = true)
        // EXIF local time without an offset, mtime within a day of it: the scanner may infer the offset.
        variant("I-exif-nooffset-touch-first", "2020:01:02 04:04:05", atInsert = true, atPublish = true, after = false, touch = false, touchFirst = true)
        download()
        query()
    }

    // Videos: does the scanner take DATE_TAKEN from the mvhd creation time, mvhd modification time, or the file's mtime?
    // v-dated.mp4: mvhd created 2018-07-08T09:10:11Z; v-nodate.mp4: 0; v-modonly.mp4: only mvhd modified 2016-05-01T10:53:20Z.
    fun videos() {
        val video = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        a.log("want: mvhd created 1531041011000 (2018-07-08); restored mtime $modS (2020-01-02)")
        for ((name, asset, touchFirst) in listOf(
            Triple("V1-dated", "v-dated.mp4", false), Triple("V2-dated-touch", "v-dated.mp4", true),
            Triple("V3-nodate", "v-nodate.mp4", false), Triple("V4-nodate-touch", "v-nodate.mp4", true),
            Triple("V5-modonly-touch", "v-modonly.mp4", true))) {
            val v = ContentValues().apply {
                put(MediaColumns.DISPLAY_NAME, "gs-$name-$stamp.mp4")
                put(MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaColumns.RELATIVE_PATH, "Movies/graphite-spike")
                put(MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(video, v) ?: continue.also { a.log("$name: insert failed") }
            cr.openOutputStream(uri)!!.use { o -> a.assets.open(asset).use { it.copyTo(o) } }
            if (touchFirst) path(uri)?.let { File(it).setLastModified(modS * 1000) }
            cr.update(uri, ContentValues().apply { put(MediaColumns.IS_PENDING, 0) }, null, null)
            show("$name id=${uri.lastPathSegment} published", uri)
        }
        // One photo too, for the shell DATE_ADDED test.
        variant("H-touch-first", null, atInsert = true, atPublish = true, after = false, touch = false, touchFirst = true)
        query()
    }

    fun dates() = ContentValues().apply {
        put(MediaColumns.DATE_TAKEN, taken)
        put(MediaColumns.DATE_MODIFIED, modS)
    }

    fun variant(name: String, exif: String?, atInsert: Boolean, atPublish: Boolean, after: Boolean, touch: Boolean,
                offset: String? = null, touchFirst: Boolean = false) {
        val v = ContentValues().apply {
            put(MediaColumns.DISPLAY_NAME, "gs-$name-$stamp.jpg")
            put(MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaColumns.RELATIVE_PATH, dir)
            put(MediaColumns.IS_PENDING, 1)
            if (atInsert) putAll(dates())
        }
        val uri = cr.insert(images, v) ?: return a.log("$name: insert failed")
        cr.openOutputStream(uri)!!.use { it.write(jpeg(exif, offset)) }
        show("$name pending", uri)
        if (touchFirst) path(uri)?.let { p ->
            a.log("$name pending setLastModified=${runCatching { File(p).setLastModified(modS * 1000) }.getOrElse { it.toString() }} stat ${File(p).lastModified() / 1000}")
        }
        val pub = ContentValues().apply { put(MediaColumns.IS_PENDING, 0); if (atPublish) putAll(dates()) }
        a.log("$name publish rows=${cr.update(uri, pub, null, null)}")
        show("$name published", uri)
        if (after) {
            a.log("$name later update rows=${runCatching { cr.update(uri, dates(), null, null) }.getOrElse { it.toString() }}")
            show("$name after update", uri)
        }
        if (touch) touchAndScan(name, uri)
    }

    fun touchAndScan(name: String, uri: Uri) {
        val path = path(uri) ?: return a.log("$name: no _data path")
        val ok = runCatching { File(path).setLastModified(modS * 1000) }.getOrElse { a.log("$name setLastModified: $it"); false }
        a.log("$name setLastModified=$ok, stat now ${File(path).lastModified() / 1000}")
        val latch = CountDownLatch(1)
        MediaScannerConnection.scanFile(a, arrayOf(path), null) { _, _ -> latch.countDown() }
        latch.await(20, TimeUnit.SECONDS)
        show("$name after touch+scan", uri)
        a.log("$name re-set DATE_TAKEN rows=${runCatching { cr.update(uri, ContentValues().apply { put(MediaColumns.DATE_TAKEN, taken) }, null, null) }.getOrElse { it.toString() }}")
        show("$name final", uri)
    }

    fun download() {
        val uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val v = ContentValues().apply {
            put(MediaColumns.DISPLAY_NAME, "gs-doc-$stamp.txt")
            put(MediaColumns.MIME_TYPE, "text/plain")
            put(MediaColumns.RELATIVE_PATH, "Download/graphite-spike")
            put(MediaColumns.IS_PENDING, 1)
            put(MediaColumns.DATE_MODIFIED, modS)
        }
        val u = cr.insert(uri, v) ?: return a.log("download insert failed")
        cr.openOutputStream(u)!!.use { it.write("hello\n".toByteArray()) }
        cr.update(u, ContentValues().apply { put(MediaColumns.IS_PENDING, 0); put(MediaColumns.DATE_MODIFIED, modS) }, null, null)
        show("F-download published", u)
        touchAndScan("F-download", u)
    }

    fun path(uri: Uri): String? = cr.query(uri, arrayOf(MediaColumns.DATA), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }

    fun show(label: String, uri: Uri) {
        val cols = arrayOf(MediaColumns.DATE_TAKEN, MediaColumns.DATE_MODIFIED, MediaColumns.DATE_ADDED, MediaColumns.DATA)
        cr.query(uri, cols, null, null, null)?.use {
            if (!it.moveToFirst()) return a.log("$label: row not visible")
            val p = it.getString(3)
            val dt = if (it.isNull(0)) "null" else it.getLong(0).toString()
            a.log("$label: DATE_TAKEN=$dt${if (dt == taken.toString()) " OK" else ""} DATE_MODIFIED=${it.getLong(1)}" +
                "${if (it.getLong(1) == modS) " OK" else ""} DATE_ADDED=${it.getLong(2)} stat=${p?.let { f -> File(f).lastModified() / 1000 }}")
        }
    }

    fun query() {
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        cr.query(files, arrayOf(MediaColumns._ID, MediaColumns.DISPLAY_NAME), "${MediaColumns.DISPLAY_NAME} LIKE 'gs-%'", null, null)?.use {
            a.log("re-query: ${it.count} spike rows")
            while (it.moveToNext()) show("${it.getString(1)} id=${it.getLong(0)}", Uri.withAppendedPath(files, it.getLong(0).toString()))
        }
    }
}
