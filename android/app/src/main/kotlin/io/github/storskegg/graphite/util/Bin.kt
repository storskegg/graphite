package io.github.storskegg.graphite.util

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Little-endian writer for the app's own messages (the core encodes its own documents). */
class BinWriter {
    private val out = ByteArrayOutputStream()
    private val b8 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)

    fun u8(v: Int) = apply { out.write(v) }
    fun bool(v: Boolean) = u8(if (v) 1 else 0)
    fun u32(v: Long) = apply { b8.clear(); b8.putInt(v.toInt()); out.write(b8.array(), 0, 4) }
    fun u32(v: Int) = u32(v.toLong())
    fun i64(v: Long) = apply { b8.clear(); b8.putLong(v); out.write(b8.array(), 0, 8) }
    fun bytes(v: ByteArray) = apply { u32(v.size); out.write(v) }
    fun raw(v: ByteArray, off: Int = 0, len: Int = v.size) = apply { out.write(v, off, len) }
    fun str(v: String) = bytes(v.toByteArray(Charsets.UTF_8))
    fun optI64(v: Long?) = apply { if (v == null) u8(0) else { u8(1); i64(v) } }
    fun finish(): ByteArray = out.toByteArray()
}

class BinReader(private val b: ByteArray, start: Int = 0) {
    private val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).also { it.position(start) }

    fun u8(): Int = buf.get().toInt() and 0xff
    fun bool(): Boolean = u8() != 0
    fun u32(): Long = buf.int.toLong() and 0xffffffffL
    fun i64(): Long = buf.long
    fun bytes(): ByteArray {
        val n = u32().toInt()
        require(n >= 0 && n <= buf.remaining()) { "bad length $n" }
        return ByteArray(n).also { buf.get(it) }
    }
    fun str(): String = String(bytes(), Charsets.UTF_8)
    fun optI64(): Long? = if (u8() == 0) null else i64()
    fun more(): Boolean = buf.hasRemaining()
    fun rest(): ByteArray = ByteArray(buf.remaining()).also { buf.get(it) }
    val position get() = buf.position()
    val remaining get() = buf.remaining()
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun formatBytes(n: Long): String = when {
    n >= 1L shl 30 -> "%.1f GB".format(n / 1e9)
    n >= 1L shl 20 -> "%.1f MB".format(n / 1e6)
    n >= 1L shl 10 -> "%.0f kB".format(n / 1e3)
    else -> "$n bytes"
}

fun formatDuration(seconds: Long): String = when {
    seconds < 60 -> "under a minute"
    seconds < 3600 -> "about ${(seconds + 30) / 60} minutes"
    else -> "about %d h %02d min".format(seconds / 3600, (seconds % 3600) / 60)
}
