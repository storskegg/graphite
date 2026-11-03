package io.github.storskegg.graphite

import android.content.ContentValues
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import io.github.storskegg.graphite.ffi.FileHasher
import io.github.storskegg.graphite.session.FileEnd
import io.github.storskegg.graphite.session.Msg
import io.github.storskegg.graphite.ui.Screen
import io.github.storskegg.graphite.usb.Connection
import io.github.storskegg.graphite.util.BinReader
import io.github.storskegg.graphite.util.BinWriter
import io.github.storskegg.graphite.util.hex
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom

/** Tracer bullet, target side: receive one encrypted test file and verify it. */
class TargetTransfer(private val ctl: Controller, private val conn: Connection, private val p: Paired) {
    fun run() {
        val ch = p.channel
        ctl.requireAuth("writes files")
        ctl.show(Screen("Test file", "Receiving an encrypted test file…"))
        ch.send(Msg.SPEED_TEST, ByteArray(0))
        val begin = BinReader(Flows.expect(ch, Msg.FILE_BEGIN).body)
        begin.u32()
        val size = begin.i64()
        val name = begin.str()
        val expected = begin.bytes()
        val cr = ctl.app.contentResolver
        val uri = cr.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaColumns.DISPLAY_NAME, name)
            put(MediaColumns.RELATIVE_PATH, "Download/graphite-test/")
            put(MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaColumns.IS_PENDING, 1)
        })!!
        val t0 = System.nanoTime()
        var got = 0L
        cr.openFileDescriptor(uri, "w")!!.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { out ->
                while (true) {
                    val m = ch.receive()
                    if (m.type == Msg.FILE_END) break
                    check(m.type == Msg.FILE_DATA)
                    out.write(m.body, 4, m.body.size - 4)
                    got += m.body.size - 4
                }
                out.fd.sync()
            }
        }
        val secs = (System.nanoTime() - t0) / 1e9
        val h = FileHasher()
        cr.openInputStream(uri)!!.use { ins ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = ins.read(buf); if (n < 0) break; h.update(buf.copyOf(n)) }
        }
        val actual = h.finalize()
        val ok = got == size && actual.contentEquals(expected)
        if (ok) cr.update(uri, ContentValues().apply { put(MediaColumns.IS_PENDING, 0) }, null, null)
        else cr.delete(uri, null, null)
        ch.send(Msg.BYE, ByteArray(0), last = true)
        conn.link.flush(3000)
        val text = "%s: %d bytes in %.2f s (%.1f MB/s over %s)\nBLAKE3 %s\n%s".format(
            if (ok) "Verified" else "MISMATCH", got, secs, got / secs / 1e6, ctl.linkSpeed, actual.hex(),
            if (ok) "Saved as Download/graphite-test/$name" else "Deleted.")
        log("tracer: $text")
        ctl.ask(Screen(if (ok) "Test file received" else "Test file failed", text))
    }
}

/** Tracer bullet, source side: send one encrypted test file. */
class SourceTransfer(private val ctl: Controller, private val conn: Connection, private val p: Paired) {
    fun run() {
        val ch = p.channel
        Flows.expect(ch, Msg.SPEED_TEST)
        ctl.requireAuth("reads files")
        ctl.show(Screen("Test file", "Sending an encrypted test file…"))
        val data = ByteArray(64 shl 20).also(SecureRandom()::nextBytes)
        val f = File(ctl.app.cacheDir, "graphite-tracer-test.bin").apply { writeBytes(data) }
        val h = FileHasher()
        val bytes = f.readBytes()
        h.update(bytes)
        ch.send(Msg.FILE_BEGIN, BinWriter().u32(0).i64(bytes.size.toLong())
            .str("graphite-tracer-test-${System.currentTimeMillis() / 1000}.bin").bytes(h.finalize()).finish())
        val piece = ch.maxSingleChunkBody - 4
        var off = 0
        val t0 = System.nanoTime()
        while (off < bytes.size) {
            val n = minOf(piece, bytes.size - off)
            ch.send(Msg.FILE_DATA, BinWriter().u32(0).raw(bytes, off, n).finish())
            off += n
        }
        ch.send(Msg.FILE_END, BinWriter().u32(0).u8(FileEnd.OK).finish())
        Flows.expect(ch, Msg.BYE)
        val secs = (System.nanoTime() - t0) / 1e9
        f.delete()
        ctl.ask(Screen("Test file sent", "%d bytes in %.2f s (%.1f MB/s)".format(bytes.size, secs, bytes.size / secs / 1e6)))
    }
}
