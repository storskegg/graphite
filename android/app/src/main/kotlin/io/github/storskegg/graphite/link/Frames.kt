package io.github.storskegg.graphite.link

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Frames on the cable. Each one is
 *
 * ```
 * magic "gRf1" | type u8 | seq u32 | length u32 | header CRC32 | payload | payload CRC32
 * ```
 *
 * little-endian. The CRCs only find frame boundaries again after garbage (bytes
 * left in the AOA pipe by an earlier session, or a frame cut short by a link
 * failure); integrity and authenticity come from the core's AEAD above.
 */
object Frames {
    val MAGIC = byteArrayOf('g'.code.toByte(), 'R'.code.toByte(), 'f'.code.toByte(), '1'.code.toByte())
    const val HEADER = 4 + 1 + 4 + 4 + 4
    const val TRAILER = 4
    /** A sealed 1 MiB chunk plus the record byte, with room to spare. */
    const val MAX_PAYLOAD = (1 shl 20) + 1024

    fun encode(type: Int, seq: Long, payload: ByteArray, off: Int = 0, len: Int = payload.size): ByteArray {
        require(len <= MAX_PAYLOAD) { "payload of $len bytes" }
        val b = ByteBuffer.allocate(HEADER + len + TRAILER).order(ByteOrder.LITTLE_ENDIAN)
        b.put(MAGIC).put(type.toByte()).putInt(seq.toInt()).putInt(len)
        b.putInt(crc(b.array(), 0, HEADER - 4))
        b.put(payload, off, len)
        b.putInt(crc(payload, off, len))
        return b.array()
    }

    /** Rewrites the sequence number of an encoded frame, and its header CRC. */
    fun setSeq(frame: ByteArray, seq: Long) {
        val b = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(5, seq.toInt())
        b.putInt(HEADER - 4, crc(frame, 0, HEADER - 4))
    }

    fun crc(b: ByteArray, off: Int, len: Int): Int = CRC32().run { update(b, off, len); value.toInt() }
}

class Frame(val type: Int, val seq: Long, val payload: ByteArray)

/**
 * Turns a byte stream that may contain garbage into frames: anything that is
 * not a whole frame with both CRCs right is skipped, one byte at a time, until
 * the next magic. [skipped] counts what was thrown away.
 */
class FrameDecoder {
    private var buf = ByteArray(1 shl 16)
    private var start = 0
    private var end = 0
    var skipped = 0L
        private set

    fun push(b: ByteArray, off: Int = 0, len: Int = b.size) {
        if (len == 0) return
        if (end + len > buf.size) {
            val live = end - start
            if (live + len > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, live + len))
            System.arraycopy(buf, start, buf, 0, live)
            start = 0
            end = live
        }
        System.arraycopy(b, off, buf, end, len)
        end += len
    }

    /** The next whole frame, or null until more bytes arrive. */
    fun next(): Frame? {
        while (true) {
            val at = findMagic() ?: return null
            if (end - at < Frames.HEADER) return null
            val h = ByteBuffer.wrap(buf, at, Frames.HEADER).order(ByteOrder.LITTLE_ENDIAN)
            h.position(at + 4)
            val type = h.get().toInt() and 0xff
            val seq = h.int.toLong() and 0xffffffffL
            val len = h.int
            val hcrc = h.int
            if (hcrc != Frames.crc(buf, at, Frames.HEADER - 4) || len < 0 || len > Frames.MAX_PAYLOAD) {
                drop(at + 1)
                continue
            }
            val total = Frames.HEADER + len + Frames.TRAILER
            if (end - at < total) return null
            val p = at + Frames.HEADER
            val pcrc = ByteBuffer.wrap(buf, p + len, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (pcrc != Frames.crc(buf, p, len)) {
                drop(at + 1)
                continue
            }
            val payload = buf.copyOfRange(p, p + len)
            skipped += at - start
            start = at + total
            if (start == end) { start = 0; end = 0 }
            return Frame(type, seq, payload)
        }
    }

    /** Discards everything up to [to], counting it as skipped. */
    private fun drop(to: Int) {
        skipped += to - start
        start = to
    }

    /** Position of the next magic, dropping what comes before it; null if there is none yet. */
    private fun findMagic(): Int? {
        val m = Frames.MAGIC
        var i = start
        while (i + 4 <= end) {
            if (buf[i] == m[0] && buf[i + 1] == m[1] && buf[i + 2] == m[2] && buf[i + 3] == m[3]) {
                skipped += i - start
                start = i
                return i
            }
            i++
        }
        // Keep up to three bytes that could be the start of a magic.
        val keep = maxOf(start, end - 3)
        skipped += keep - start
        start = keep
        return null
    }
}
