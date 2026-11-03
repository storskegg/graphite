package io.github.storskegg.graphite.link

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One attachment of the cable: raw bytes each way, nothing more. */
interface BytePipe : Closeable {
    /**
     * Blocks for the next bytes from the peer and returns how many were put
     * in [into]. 0 means a zero-length packet: no data, not an error.
     * Throws [IOException] once the link is gone (EIO on a pulled cable or a
     * USB role change).
     */
    fun read(into: ByteArray): Int

    /** Writes all of [b]; throws [IOException] once the link is gone. */
    fun write(b: ByteArray)
}

class LinkResetException : IOException("the other phone started over")
class LinkClosedException(why: String) : IOException(why)

/**
 * A reliable, resumable record link over a [BytePipe] that can come and go.
 *
 * - **Handshake.** Each attachment starts with SYNC (host) and SYNC_ACK
 *   (accessory) carrying a fresh token. Everything before the handshake is
 *   thrown away: bytes from an earlier session can still be waiting in the
 *   AOA pipe.
 * - **Records** are numbered. The receiver acknowledges them; the sender keeps
 *   the unacknowledged ones (up to [window] bytes) and sends them again after
 *   a gap (NACK) or on a new attachment. Duplicates are dropped.
 * - **Resume.** The host names the logical link in SYNC. If the accessory
 *   still has it, both sides carry on from what the other has received, so a
 *   pulled cable or a USB role change costs nothing above this layer. If
 *   either side has lost it (an app restarted), both start over and
 *   [receive] throws [LinkResetException] once, so the session above
 *   restarts too.
 * - **Liveness.** Each side pings when idle; [deadAfterMs] without a frame
 *   counts as a lost link.
 *
 * Each attachment has a reader and a writer thread. The reader never writes,
 * so neither phone can block the other's reading.
 */
class Link(
    private val isHost: Boolean,
    /** This side's app role, told to the peer in the handshake. */
    val localRole: Int,
    private val log: (String) -> Unit = {},
    private val window: Long = 32L shl 20,
    private val deadAfterMs: Long = 15_000,
    private val pingAfterMs: Long = 1_000,
    private val resendAfterMs: Long = 3_000,
) : Closeable {
    enum class State { DOWN, HANDSHAKING, UP, CLOSED }

    private companion object {
        const val SYNC = 1
        const val SYNC_ACK = 2
        const val DATA = 3
        const val ACK = 4
        const val NACK = 5
        const val CLOSE = 6
        const val PING = 7
    }

    private class Sent(val seq: Long, val frame: ByteArray)
    private sealed interface Event {
        class Data(val payload: ByteArray) : Event
        object Reset : Event
        class Closed(val why: String) : Event
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object() as java.lang.Object
    private val random = SecureRandom()

    @Volatile var state = State.DOWN
        private set
    /** The peer's app role from the last handshake, or -1. */
    @Volatile var peerRole = -1
        private set
    var onState: (State) -> Unit = {}

    // All below guarded by [lock].
    private var linkId: ByteArray? = null
    private var nextSeq = 0L
    private val unacked = ArrayDeque<Sent>()
    private var unackedBytes = 0L
    private var rxNext = 0L
    private var lastAcked = 0L
    private var lastNacked = -1L
    private var lastRelease = 0L
    private var token = ByteArray(16)
    private var pipe: BytePipe? = null
    private var generation = 0
    private val outControl = ArrayDeque<ByteArray>()
    private val outData = ArrayDeque<ByteArray>()

    @Volatile private var lastRx = 0L
    @Volatile private var lastTx = 0L
    private val inbox = LinkedBlockingQueue<Event>()
    @Volatile private var skippedBefore = 0L
    @Volatile private var decoderSkipped = 0L

    /** Bytes thrown away while looking for frames, over all attachments. */
    val skippedBytes get() = skippedBefore + decoderSkipped

    fun attach(p: BytePipe) {
        val gen: Int
        val old: BytePipe?
        synchronized(lock) {
            check(state != State.CLOSED) { "link closed" }
            old = pipe
            pipe = p
            gen = ++generation
            token = ByteArray(16).also(random::nextBytes)
            if (isHost && linkId == null) linkId = ByteArray(16).also(random::nextBytes)
            outControl.clear()
            outData.clear()
            skippedBefore += decoderSkipped
            decoderSkipped = 0
            lastRx = System.currentTimeMillis()
            setState(State.HANDSHAKING)
        }
        old?.let { runCatching { it.close() } }
        Thread({ readLoop(p, gen) }, "link-read").apply { isDaemon = true }.start()
        Thread({ writeLoop(p, gen) }, "link-write").apply { isDaemon = true }.start()
    }

    /** Waits until the link is up; false on timeout or close. */
    fun awaitUp(timeoutMs: Long): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (state != State.UP) {
                if (state == State.CLOSED) return false
                val left = until - System.currentTimeMillis()
                if (left <= 0) return false
                lock.wait(left)
            }
        }
        return true
    }

    /**
     * Queues one record; blocks while [window] bytes are unacknowledged.
     * While the link is down the record waits and goes out on resume.
     */
    fun send(payload: ByteArray) {
        val f = Frames.encode(DATA, 0, payload)
        synchronized(lock) {
            while (unackedBytes > window && state != State.CLOSED) lock.wait(500)
            if (state == State.CLOSED) throw LinkClosedException("link closed")
            Frames.setSeq(f, nextSeq)
            unacked.addLast(Sent(nextSeq, f))
            unackedBytes += f.size
            nextSeq++
            if (state == State.UP) { outData.addLast(f); lock.notifyAll() }
        }
    }

    /** The next record; throws [LinkResetException] once when the peer started over. */
    fun receive(timeoutMs: Long = Long.MAX_VALUE): ByteArray {
        val e = (if (timeoutMs == Long.MAX_VALUE) inbox.take() else inbox.poll(timeoutMs, TimeUnit.MILLISECONDS))
            ?: throw IOException("nothing from the other phone for ${timeoutMs / 1000} s")
        return when (e) {
            is Event.Data -> e.payload
            Event.Reset -> throw LinkResetException()
            is Event.Closed -> { inbox.put(e); throw LinkClosedException(e.why) }
        }
    }

    /** Waits until everything sent has been acknowledged; false on timeout. */
    fun flush(timeoutMs: Long): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (unacked.isNotEmpty() && state != State.CLOSED) {
                val left = until - System.currentTimeMillis()
                if (left <= 0) return false
                lock.wait(minOf(left, 200))
            }
            return unacked.isEmpty()
        }
    }

    override fun close() = close("closed")

    fun close(why: String) {
        val p: BytePipe?
        synchronized(lock) {
            if (state == State.CLOSED) return
            p = pipe
            pipe = null
            generation++
            setState(State.CLOSED)
        }
        if (p != null) {
            runCatching { p.write(Frames.encode(CLOSE, 0, ByteArray(0))) }
            runCatching { p.close() }
        }
        inbox.put(Event.Closed(why))
    }

    /** Call with [lock] held. */
    private fun setState(s: State) {
        if (state == s) return
        state = s
        lock.notifyAll()
        runCatching { onState(s) }
    }

    /** Marks attachment [gen] as lost, if it is still the current one. */
    private fun lost(gen: Int, why: String) {
        val p: BytePipe?
        synchronized(lock) {
            if (gen != generation || state == State.CLOSED || state == State.DOWN) return
            p = pipe
            pipe = null
            log("link lost: $why")
            setState(State.DOWN)
        }
        runCatching { p?.close() }
    }

    private fun control(type: Int, payload: ByteArray = ByteArray(0)) = synchronized(lock) {
        outControl.addLast(Frames.encode(type, 0, payload))
        lock.notifyAll()
    }

    private fun u32(v: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()
    private fun u32(b: ByteArray, at: Int = 0) =
        ByteBuffer.wrap(b, at, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL

    /** Call with [lock] held. */
    private fun syncPayload(resumed: Boolean): ByteArray =
        ByteBuffer.allocate(16 + 16 + 4 + 1 + 1).order(ByteOrder.LITTLE_ENDIAN)
            .put(token).put(linkId ?: ByteArray(16)).putInt(rxNext.toInt())
            .put(localRole.toByte()).put(if (resumed) 1 else 0).array()

    private fun writeLoop(p: BytePipe, gen: Int) {
        var lastSync = 0L
        try {
            while (true) {
                val frame: ByteArray?
                var sync: ByteArray? = null
                synchronized(lock) {
                    if (gen != generation) return
                    val now = System.currentTimeMillis()
                    if (now - lastRx > deadAfterMs) {
                        lost(gen, "nothing received for ${deadAfterMs / 1000} s")
                        return
                    }
                    if (state == State.HANDSHAKING && isHost && now - lastSync >= 250) {
                        lastSync = now
                        sync = Frames.encode(SYNC, 0, syncPayload(false))
                    }
                    if (state == State.UP) {
                        if (rxNext > lastAcked) {   // acknowledge what arrived since last time
                            lastAcked = rxNext
                            outControl.addLast(Frames.encode(ACK, 0, u32(rxNext)))
                        }
                        // A record lost with nothing after it never shows up as a gap: resend on silence.
                        if (unacked.isNotEmpty() && outData.isEmpty() && now - lastRelease > resendAfterMs) {
                            lastRelease = now
                            requeueLocked()
                        }
                        if (outControl.isEmpty() && outData.isEmpty() && now - lastTx >= pingAfterMs) {
                            outControl.addLast(Frames.encode(PING, 0, ByteArray(0)))
                        }
                    }
                    frame = sync ?: outControl.removeFirstOrNull()
                        ?: (if (state == State.UP) outData.removeFirstOrNull() else null)
                    if (frame == null) lock.wait(100)
                }
                if (frame != null) {
                    p.write(frame)
                    lastTx = System.currentTimeMillis()
                }
            }
        } catch (e: IOException) {
            lost(gen, "write failed: ${e.message}")
        } catch (_: InterruptedException) {
            lost(gen, "interrupted")
        }
    }

    private fun readLoop(p: BytePipe, gen: Int) {
        val decoder = FrameDecoder()
        val buf = ByteArray(256 * 1024)
        try {
            while (true) {
                val n = p.read(buf)
                if (gen != generation) return
                if (n <= 0) continue
                lastRx = System.currentTimeMillis()
                decoder.push(buf, 0, n)
                while (true) handle(gen, decoder.next() ?: break)
                decoderSkipped = decoder.skipped
                // Acknowledge promptly under load, so the sender's window keeps moving.
                synchronized(lock) { if (rxNext - lastAcked >= 8) lock.notifyAll() }
            }
        } catch (e: IOException) {
            lost(gen, "read failed: ${e.message}")
        }
    }

    private fun handle(gen: Int, f: Frame) {
        val st = state
        if (st == State.HANDSHAKING) {
            if (isHost && f.type == SYNC_ACK) hostHandshake(f)
            else if (!isHost && f.type == SYNC) accessoryHandshake(f)
            // Anything else before the handshake is left over from earlier: dropped.
            return
        }
        when (f.type) {
            SYNC -> if (!isHost) accessoryHandshake(f)
            DATA -> onData(f)
            ACK -> synchronized(lock) { releaseLocked(u32(f.payload)) }
            NACK -> synchronized(lock) { releaseLocked(u32(f.payload)); requeueLocked() }
            CLOSE -> if (gen == generation) close("the other phone closed the link")
            PING, SYNC_ACK -> {}
        }
    }

    private fun hostHandshake(f: Frame) {
        if (f.payload.size < 38) return
        var reset = false
        synchronized(lock) {
            if (!f.payload.copyOfRange(0, 16).contentEquals(token)) return
            val peerLink = f.payload.copyOfRange(16, 32)
            val peerRx = u32(f.payload, 32)
            peerRole = f.payload[36].toInt()
            if (f.payload[37].toInt() == 1 && peerLink.contentEquals(linkId)) {
                log("link resumed: the other phone has $peerRx of our $nextSeq records")
                releaseLocked(peerRx)
            } else {
                reset = nextSeq > 0 || rxNext > 0
                if (reset) log("link started over: the other phone lost the session")
                restartLocked()
            }
            setState(State.UP)
            lastRelease = System.currentTimeMillis()
            requeueLocked()
        }
        if (reset) inbox.put(Event.Reset)
    }

    private fun accessoryHandshake(f: Frame) {
        if (f.payload.size < 37) return
        var reset = false
        synchronized(lock) {
            val tok = f.payload.copyOfRange(0, 16)
            val hostLink = f.payload.copyOfRange(16, 32)
            val hostRx = u32(f.payload, 32)
            if (tok.contentEquals(token) && state == State.UP) {
                // The host repeats SYNC until it hears back: answer again, change nothing.
                outControl.addLast(Frames.encode(SYNC_ACK, 0, syncPayload(true)))
                lock.notifyAll()
                return
            }
            token = tok
            peerRole = f.payload[36].toInt()
            val resumed = linkId?.contentEquals(hostLink) == true
            if (resumed) {
                log("link resumed: the other phone has $hostRx of our $nextSeq records")
                releaseLocked(hostRx)
            } else {
                reset = linkId != null && (nextSeq > 0 || rxNext > 0)
                restartLocked()
                linkId = hostLink
            }
            setState(State.UP)
            lastRelease = System.currentTimeMillis()
            // SYNC_ACK goes before any resent record.
            outControl.clear()
            outControl.addLast(Frames.encode(SYNC_ACK, 0, syncPayload(resumed)))
            requeueLocked()
        }
        if (reset) inbox.put(Event.Reset)
    }

    /** Forgets everything in flight both ways: a new logical link. Call with [lock] held. */
    private fun restartLocked() {
        nextSeq = 0; rxNext = 0; lastAcked = 0; lastNacked = -1
        unacked.clear(); unackedBytes = 0
        outData.clear()
        inbox.clear()
        lock.notifyAll()
    }

    private fun onData(f: Frame) {
        synchronized(lock) {
            when {
                f.seq == rxNext -> {
                    rxNext++
                    inbox.put(Event.Data(f.payload))
                }
                f.seq > rxNext -> if (lastNacked != rxNext) {
                    lastNacked = rxNext
                    log("gap: expected $rxNext, got ${f.seq}; asking for a resend")
                    outControl.addLast(Frames.encode(NACK, 0, u32(rxNext)))
                    lock.notifyAll()
                }
                else -> {}   // a duplicate after a resend
            }
        }
    }

    /** Call with [lock] held. */
    private fun releaseLocked(upTo: Long) {
        while (unacked.isNotEmpty() && unacked.first().seq < upTo) {
            unackedBytes -= unacked.removeFirst().frame.size
            lastRelease = System.currentTimeMillis()
        }
        lock.notifyAll()
    }

    /** Queues every unacknowledged record again, in order. Call with [lock] held. */
    private fun requeueLocked() {
        outData.clear()
        unacked.forEach { outData.addLast(it.frame) }
        lock.notifyAll()
    }
}
