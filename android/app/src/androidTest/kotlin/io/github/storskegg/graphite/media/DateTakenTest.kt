package io.github.storskegg.graphite.media

import android.content.ContentUris
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What MediaStore makes of DATE_TAKEN for a copy graphite publishes, for a JPEG whose EXIF date
 * has no time zone (2020:01:02 03:04:05.123, no OffsetTime, no GPS time): the case where the old
 * phone's DATE_TAKEN (set by the app that saved the photo) and the new phone's (derived by its
 * scanner) disagreed in the real-library run. Each case logs its result under "graphite-probe".
 *
 * Found on API 37 (and in the spike on the phones): the scanner takes the zone from the
 * modification time, to within 24 hours, else gives no date; what an app writes is overwritten
 * at publish or refused after it. So only the modification time can steer DATE_TAKEN.
 */
@RunWith(AndroidJUnit4::class)
class DateTakenTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val cr = ctx.contentResolver
    private val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val made = mutableListOf<Long>()

    /** The moment the photo was taken if the camera was at UTC-6: 2020-01-02 09:04:05.123 UTC. */
    private val trueMs = 1577955845123L
    private val hour = 3_600_000L

    @After fun cleanUp() {
        for (id in made) cr.delete(ContentUris.withAppendedId(images, id), null, null)
    }

    @Test fun derivedFromExifAndTheModificationTime() {
        val atTheMoment = publish("gt-probe-a.jpg", mtime = trueMs)
        val hoursLater = publish("gt-probe-b.jpg", mtime = trueMs + 3 * hour + hour / 2)
        val daysLater = publish("gt-probe-c.jpg", mtime = trueMs + 6 * 24 * hour)
        assertEquals(trueMs, report("mtime = the moment", atTheMoment))
        assertEquals(trueMs + 3 * hour + hour / 2, report("mtime 3.5 h later", hoursLater))
        assertNull(report("mtime 6 days later", daysLater))
    }

    @Test fun writtenByGraphite() {
        val onInsert = publish("gt-probe-d.jpg", mtime = trueMs + 3 * hour + hour / 2, dateTaken = trueMs)
        val derived = trueMs + 3 * hour + hour / 2
        assertEquals("overwritten at publish", derived, report("DATE_TAKEN on insert", onInsert))
        assertEquals(derived, report("  after a rescan", rescan(onInsert)))

        val afterPublish = publish("gt-probe-e.jpg", mtime = trueMs + 3 * hour + hour / 2)
        val n = cr.update(ContentUris.withAppendedId(images, afterPublish), ContentValues().apply {
            put(MediaColumns.DATE_TAKEN, trueMs)
        }, null, null)
        assertEquals("refused after publishing", 0, n)
        assertEquals(derived, report("DATE_TAKEN updated after publishing ($n rows)", afterPublish))
        assertEquals(derived, report("  after a rescan", rescan(afterPublish)))
    }

    /** Inserts a pending copy of the test JPEG, sets its mtime, publishes it; its id. */
    private fun publish(name: String, mtime: Long, dateTaken: Long? = null): Long {
        val uri = cr.insert(images, ContentValues().apply {
            put(MediaColumns.DISPLAY_NAME, name)
            put(MediaColumns.RELATIVE_PATH, "Pictures/graphite-test/")
            put(MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaColumns.IS_PENDING, 1)
            dateTaken?.let { put(MediaColumns.DATE_TAKEN, it) }
        })!!
        val id = ContentUris.parseId(uri).also { made += it }
        cr.openOutputStream(uri)!!.use { out ->
            InstrumentationRegistry.getInstrumentation().context.assets.open("no-offset.jpg").use { it.copyTo(out) }
        }
        check(File(path(id)).setLastModified(mtime)) { "setLastModified failed" }
        cr.update(uri, ContentValues().apply { put(MediaColumns.IS_PENDING, 0) }, null, null)
        return id
    }

    private fun rescan(id: Long): Long {
        val done = CountDownLatch(1)
        MediaScannerConnection.scanFile(ctx, arrayOf(path(id)), null) { _, _ -> done.countDown() }
        check(done.await(30, TimeUnit.SECONDS)) { "scan timed out" }
        return id
    }

    private fun path(id: Long): String = query(id) { it.getString(1) }

    private fun <T> query(id: Long, read: (android.database.Cursor) -> T): T =
        cr.query(ContentUris.withAppendedId(images, id), arrayOf(MediaColumns.DATE_TAKEN, MediaColumns.DATA, MediaColumns.DATE_MODIFIED), null, null)!!
            .use { check(it.moveToFirst()); read(it) }

    /** Logs and returns the item's DATE_TAKEN. */
    private fun report(case: String, id: Long): Long? {
        val (taken, modified) = query(id) { (if (it.isNull(0)) null else it.getLong(0)) to it.getLong(2) }
        val off = taken?.let { "%+.2f h".format((it - trueMs) / hour.toDouble()) } ?: "none"
        Log.i("graphite-probe", "$case: DATE_TAKEN $taken ($off from the true moment), DATE_MODIFIED $modified")
        return taken
    }
}
