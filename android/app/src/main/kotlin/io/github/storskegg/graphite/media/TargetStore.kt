package io.github.storskegg.graphite.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import io.github.storskegg.graphite.ffi.FileHasher
import io.github.storskegg.graphite.ffi.openFile
import io.github.storskegg.graphite.log
import io.github.storskegg.graphite.util.hex
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * The target's MediaStore: what it holds (trash included), hashing with the
 * cache, and writing files as pending items that are published only once
 * their bytes check out.
 */
class TargetStore(private val c: Context) {
    private val cr = c.contentResolver
    val files: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val cache = HashCache(c)

    /** Every published item, trashed or not. */
    fun list(): List<TargetFile> {
        val cols = arrayOf(MediaColumns._ID, MediaColumns.DATA, MediaColumns.SIZE, MediaColumns.DATE_MODIFIED, MediaColumns.IS_TRASHED)
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_EXCLUDE)
        }
        val out = ArrayList<TargetFile>()
        cr.query(files, cols, args, null)?.use { cur ->
            while (cur.moveToNext()) {
                val path = cur.getString(1) ?: continue
                if (File(path).isDirectory) continue
                out += TargetFile(cur.getLong(0), path, cur.getLong(2), cur.getLong(3), cur.getInt(4) != 0)
            }
        }
        return out
    }

    /** BLAKE3 in hex, from the cache while ID, size and modification time still match. */
    fun hash(f: TargetFile): String? {
        cache.get(f)?.let { return it }
        return runCatching { openFile(f.path).use { it.original().blake3.hex() } }
            .onFailure { log("hash ${f.path}: $it") }
            .getOrNull()
            ?.also { cache.put(f, it) }
    }

    /** Hash read through MediaStore, never the cache (verification). */
    fun hashUncached(id: Long): String? = runCatching {
        cr.openFileDescriptor(ContentUris.withAppendedId(files, id), "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { ins -> hashStream(ins) }
        }
    }.onFailure { log("verify $id: $it") }.getOrNull()

    /** State of an item for verification: null when it's gone. */
    data class Row(val pending: Boolean, val trashed: Boolean, val dateTakenMs: Long?, val path: String?)

    fun row(id: Long): Row? {
        val cols = arrayOf(MediaColumns.IS_PENDING, MediaColumns.IS_TRASHED, MediaColumns.DATE_TAKEN, MediaColumns.DATA)
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        return cr.query(ContentUris.withAppendedId(files, id), cols, args, null)?.use { cur ->
            if (!cur.moveToFirst()) null
            else Row(cur.getInt(0) != 0, cur.getInt(1) != 0, if (cur.isNull(2)) null else cur.getLong(2), cur.getString(3))
        }
    }

    /** A pending item for [relPath] (already made safe); MediaStore renames it if the name is taken. */
    fun insertPending(relPath: String, mime: String): Long {
        val dir = relPath.substringBeforeLast('/', "")
        val v = ContentValues().apply {
            put(MediaColumns.DISPLAY_NAME, relPath.substringAfterLast('/'))
            put(MediaColumns.RELATIVE_PATH, "$dir/")
            // MediaStore appends an extension when the name's doesn't match the MIME type (clip.mov as
            // video/mp4 became clip.mov.mp4), so the name's own type wins when Android knows it.
            put(MediaColumns.MIME_TYPE, SourceLibrary.knownMimeFor(relPath) ?: mime)
            put(MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(files, v) ?: error("MediaStore refused $relPath")
        return ContentUris.parseId(uri)
    }

    fun openForWrite(id: Long) = cr.openFileDescriptor(ContentUris.withAppendedId(files, id), "w")!!

    /** Sets the source's modification time on the pending file, then publishes it. */
    fun publish(id: Long, modifiedMs: Long): Boolean {
        val path = row(id)?.path
        val touched = path != null && File(path).setLastModified(modifiedMs)
        if (!touched) log("setLastModified failed on $path")
        cr.update(ContentUris.withAppendedId(files, id), ContentValues().apply { put(MediaColumns.IS_PENDING, 0) }, null, null)
        return touched
    }

    fun delete(id: Long) {
        runCatching { cr.delete(ContentUris.withAppendedId(files, id), null, null) }.onFailure { log("delete $id: $it") }
    }

    /** Our own pending items MediaStore still has (the journal may have missed some in a crash). */
    fun ownPending(): List<Long> {
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaColumns.OWNER_PACKAGE_NAME} = ?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(c.packageName))
        }
        return cr.query(files, arrayOf(MediaColumns._ID), args, null)?.use { cur ->
            buildList { while (cur.moveToNext()) add(cur.getLong(0)) }
        }.orEmpty()
    }

    companion object {
        fun hashStream(ins: java.io.InputStream): String {
            val h = FileHasher()
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                h.update(if (n == buf.size) buf else buf.copyOf(n))
            }
            return h.finalize().hex()
        }

        fun sync(out: FileOutputStream) { out.flush(); out.fd.sync() }
    }
}

/** MediaStore ID, size, modification time → BLAKE3. Only saves time; a stale or missing entry is hashed again. */
private class HashCache(c: Context) : SQLiteOpenHelper(c, "hashcache.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE hashes (id INTEGER PRIMARY KEY, size INTEGER NOT NULL, mtime INTEGER NOT NULL, blake3 TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    fun get(f: TargetFile): String? = readableDatabase.rawQuery(
        "SELECT blake3 FROM hashes WHERE id = ? AND size = ? AND mtime = ?",
        arrayOf(f.id.toString(), f.size.toString(), f.modifiedS.toString()),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun put(f: TargetFile, hex: String) {
        writableDatabase.insertWithOnConflict("hashes", null, ContentValues().apply {
            put("id", f.id); put("size", f.size); put("mtime", f.modifiedS); put("blake3", hex)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
