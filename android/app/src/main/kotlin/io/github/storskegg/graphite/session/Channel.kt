package io.github.storskegg.graphite.session

import io.github.storskegg.graphite.ffi.Session
import io.github.storskegg.graphite.ffi.chunkSize
import io.github.storskegg.graphite.link.Link
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Records on the [Link]: the first byte says what follows. */
object Records {
    /** A pairing message, in the clear (the core's pairing protocol authenticates it). */
    const val PAIR = 1
    /** A chunk sealed by the core's stream. */
    const val SEALED = 2
    /** The other phone stopped, with a reason in UTF-8. Unauthenticated: only ever ends things. */
    const val ABORT = 3
}

class PeerAbortException(why: String) : IOException("the other phone stopped: $why")

/** Seals and opens one chunk each; the core's [Session] in the app, a fake in tests. */
interface ChunkCipher {
    fun seal(plaintext: ByteArray, last: Boolean): ByteArray
    /** Returns the plaintext and whether it was the last chunk. */
    fun open(sealed: ByteArray): Pair<ByteArray, Boolean>
    fun finishReceiving()
}

class SessionCipher(private val s: Session) : ChunkCipher {
    override fun seal(plaintext: ByteArray, last: Boolean) = s.seal(plaintext, last)
    override fun open(sealed: ByteArray) = s.open(sealed).let { it.data to it.last }
    override fun finishReceiving() = s.finishReceiving()
}

class Message(val type: Int, val body: ByteArray)

/**
 * App messages over the encrypted stream. A message is `type u8 | length u32 |
 * body`, cut into chunks of at most the core's chunk size; each chunk is
 * sealed on its own, so a file's data never waits for a whole message.
 */
class Channel(private val link: Link, private val cipher: ChunkCipher, private val maxChunk: Int = chunkSize().toInt()) {
    private var finished = false

    /** The most body bytes that fit in a single chunk with its header. */
    val maxSingleChunkBody get() = maxChunk - 5

    @Synchronized
    fun send(type: Int, body: ByteArray, last: Boolean = false) {
        val header = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put(type.toByte()).putInt(body.size).array()
        var off = 0
        var first = true
        do {
            val room = if (first) maxChunk - 5 else maxChunk
            val n = minOf(room, body.size - off)
            val plain = if (first) header + body.copyOfRange(off, off + n) else body.copyOfRange(off, off + n)
            off += n
            first = false
            val isLast = last && off == body.size
            link.send(byteArrayOf(Records.SEALED.toByte()) + cipher.seal(plain, isLast))
        } while (off < body.size)
    }

    private var pending: ByteArrayOutputStream? = null
    private var pendingType = 0
    private var pendingLen = 0

    fun receive(timeoutMs: Long = Long.MAX_VALUE): Message {
        while (true) {
            if (finished) throw IOException("the other phone ended the session")
            val rec = link.receive(timeoutMs)
            when (rec.firstOrNull()?.toInt()) {
                Records.SEALED -> {}
                Records.ABORT -> throw PeerAbortException(String(rec, 1, rec.size - 1))
                else -> continue   // a late pairing record: ignore
            }
            val (plain, last) = cipher.open(rec.copyOfRange(1, rec.size))
            if (last) { finished = true; cipher.finishReceiving() }
            val p = pending
            if (p == null) {
                val b = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN)
                val type = b.get().toInt() and 0xff
                val len = b.int
                if (len == plain.size - 5) return Message(type, plain.copyOfRange(5, plain.size))
                pending = ByteArrayOutputStream(len).also { it.write(plain, 5, plain.size - 5) }
                pendingType = type
                pendingLen = len
            } else {
                p.write(plain)
                if (p.size() >= pendingLen) {
                    pending = null
                    return Message(pendingType, p.toByteArray())
                }
            }
        }
    }
}

fun Link.sendPair(msg: ByteArray) = send(byteArrayOf(Records.PAIR.toByte()) + msg)

fun Link.sendAbort(why: String) = runCatching { send(byteArrayOf(Records.ABORT.toByte()) + why.toByteArray()) }

/** The next pairing message; an abort from the other phone throws. */
fun Link.receivePair(timeoutMs: Long = Long.MAX_VALUE): ByteArray {
    while (true) {
        val rec = receive(timeoutMs)
        when (rec.firstOrNull()?.toInt()) {
            Records.PAIR -> return rec.copyOfRange(1, rec.size)
            Records.ABORT -> throw PeerAbortException(String(rec, 1, rec.size - 1))
        }
    }
}
