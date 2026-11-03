package io.github.storskegg.graphite.link

import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Two ends of an in-memory cable. [cut] fails both like EIO. */
class FakeCable {
    private val toHost = LinkedBlockingQueue<ByteArray>()
    private val toAcc = LinkedBlockingQueue<ByteArray>()
    @Volatile var cut = false
    /** Called with each write from the accessory; return null to drop it. */
    @Volatile var accFilter: (ByteArray) -> ByteArray? = { it }

    private inner class End(val inbox: LinkedBlockingQueue<ByteArray>, val outbox: LinkedBlockingQueue<ByteArray>,
                            val filter: () -> (ByteArray) -> ByteArray?) : BytePipe {
        private var pending: ByteArray? = null
        private var at = 0
        override fun read(into: ByteArray): Int {
            while (true) {
                if (cut) throw IOException("EIO")
                val p = pending ?: inbox.poll(20, TimeUnit.MILLISECONDS)?.also { pending = it; at = 0 } ?: continue
                if (p.isEmpty()) { pending = null; return 0 }   // a zero-length packet
                val n = minOf(into.size, p.size - at)
                System.arraycopy(p, at, into, 0, n)
                at += n
                if (at == p.size) pending = null
                return n
            }
        }
        override fun write(b: ByteArray) {
            if (cut) throw IOException("EIO")
            filter()(b)?.let { outbox.put(it.copyOf()); outbox.put(ByteArray(0)) }
        }
        override fun close() {}
    }

    val host: BytePipe = End(toHost, toAcc) { { it } }
    val accessory: BytePipe = End(toAcc, toHost) { accFilter }

    /** Bytes already waiting for the host before anyone writes: an earlier session's leftovers. */
    fun stale(toHostSide: ByteArray) = toHost.put(toHostSide)
    fun staleForAccessory(b: ByteArray) = toAcc.put(b)
}

class FramesTest {
    @Test fun roundTripThroughGarbageAndSplits() {
        val r = Random(1)
        val payloads = List(50) { r.nextBytes(r.nextInt(0, 5000)) }
        val stream = java.io.ByteArrayOutputStream()
        stream.write(r.nextBytes(777))                    // leftovers
        payloads.forEachIndexed { i, p ->
            stream.write(Frames.encode(3, i.toLong(), p))
            if (i % 7 == 0) stream.write(Frames.MAGIC)    // a false start
        }
        val bytes = stream.toByteArray()
        val d = FrameDecoder()
        val got = mutableListOf<Frame>()
        var off = 0
        while (off < bytes.size) {
            val n = minOf(r.nextInt(1, 3000), bytes.size - off)
            d.push(bytes, off, n)
            off += n
            while (true) got += d.next() ?: break
        }
        assertEquals(payloads.size, got.size)
        got.forEachIndexed { i, f ->
            assertEquals(i.toLong(), f.seq)
            assertArrayEquals(payloads[i], f.payload)
        }
        assertTrue(d.skipped >= 777)
    }

    @Test fun corruptFrameIsSkippedAndTheNextOneFound() {
        val a = Frames.encode(3, 1, "first".toByteArray())
        val b = Frames.encode(3, 2, "second".toByteArray())
        a[a.size - 6] = (a[a.size - 6] + 1).toByte()      // payload byte flipped
        val d = FrameDecoder()
        d.push(a + b)
        val f = d.next()!!
        assertEquals(2L, f.seq)
        assertNull(d.next())
    }

    @Test fun setSeqKeepsFrameValid() {
        val f = Frames.encode(3, 0, byteArrayOf(1, 2, 3))
        Frames.setSeq(f, 41)
        val d = FrameDecoder()
        d.push(f)
        assertEquals(41L, d.next()!!.seq)
    }
}

class LinkTest {
    private fun pair(cable: FakeCable, window: Long = 32L shl 20): Pair<Link, Link> {
        val host = Link(isHost = true, localRole = 2, window = window, resendAfterMs = 300)
        val acc = Link(isHost = false, localRole = 1, window = window, resendAfterMs = 300)
        acc.attach(cable.accessory)
        host.attach(cable.host)
        assertTrue(host.awaitUp(5000))
        assertTrue(acc.awaitUp(5000))
        return host to acc
    }

    @Test fun handshakeDiscardsLeftoversBothWays() {
        val cable = FakeCable()
        // An earlier session's records, complete frames included, wait in both directions.
        cable.stale(Frames.encode(3, 0, "old from accessory".toByteArray()) + Random.nextBytes(300))
        cable.staleForAccessory(Frames.encode(3, 0, "old from host".toByteArray()))
        val (host, acc) = pair(cable)
        assertEquals(1, host.peerRole)
        assertEquals(2, acc.peerRole)
        acc.send("new".toByteArray())
        host.send("hello".toByteArray())
        assertArrayEquals("new".toByteArray(), host.receive(2000))
        assertArrayEquals("hello".toByteArray(), acc.receive(2000))
        assertTrue(host.skippedBytes > 0)
    }

    @Test fun bulkInOrderUnderASmallWindow() {
        val cable = FakeCable()
        val (host, acc) = pair(cable, window = 64 * 1024)
        val r = Random(2)
        val sent = List(300) { r.nextBytes(r.nextInt(1, 20_000)) }
        val t = Thread { sent.forEach(acc::send) }.apply { start() }
        sent.forEach { assertArrayEquals(it, host.receive(5000)) }
        t.join()
        assertTrue(acc.flush(5000))
    }

    @Test fun lostRecordIsResent() {
        val cable = FakeCable()
        val (host, acc) = pair(cable)
        var dropped = 0
        cable.accFilter = { b -> if (dropped == 0 && String(b).contains("two")) { dropped++; null } else b }
        listOf("one", "two", "three").forEach { acc.send(it.toByteArray()) }
        listOf("one", "two", "three").forEach { assertEquals(it, String(host.receive(5000))) }
        assertEquals(1, dropped)
    }

    @Test fun lastRecordLostIsResentOnSilence() {
        val cable = FakeCable()
        val (host, acc) = pair(cable)
        var dropped = 0
        cable.accFilter = { b -> if (dropped == 0 && String(b).contains("only")) { dropped++; null } else b }
        acc.send("only".toByteArray())
        assertEquals("only", String(host.receive(5000)))
    }

    @Test fun resumesAfterEioWithoutLossOrDuplicates() {
        val cable1 = FakeCable()
        val (host, acc) = pair(cable1)
        val n = 200
        val t = Thread {
            for (i in 0 until n) acc.send("record $i".toByteArray())
        }.apply { start() }
        for (i in 0 until 50) assertEquals("record $i", String(host.receive(5000)))
        cable1.cut = true                                  // cable pulled
        Thread.sleep(200)
        val cable2 = FakeCable()
        cable2.stale(Random.nextBytes(100))                // and junk on the new one
        acc.attach(cable2.accessory)
        host.attach(cable2.host)
        assertTrue(host.awaitUp(5000))
        for (i in 50 until n) assertEquals("record $i", String(host.receive(5000)))
        t.join()
        host.send("back".toByteArray())
        assertEquals("back", String(acc.receive(5000)))
    }

    @Test fun restartedPeerResetsTheSessionAbove() {
        val cable1 = FakeCable()
        val (host, acc) = pair(cable1)
        acc.send("before".toByteArray())
        assertEquals("before", String(host.receive(5000)))
        cable1.cut = true
        Thread.sleep(200)
        // The source app restarted: a new Link with no memory of the old one.
        val cable2 = FakeCable()
        val acc2 = Link(isHost = false, localRole = 1)
        acc2.attach(cable2.accessory)
        host.attach(cable2.host)
        assertTrue(host.awaitUp(5000))
        try { host.receive(2000); fail("expected a reset") } catch (_: LinkResetException) {}
        acc2.send("fresh".toByteArray())
        assertEquals("fresh", String(host.receive(5000)))
        acc.close()
    }

    @Test fun closeReachesThePeer() {
        val cable = FakeCable()
        val (host, acc) = pair(cable)
        acc.close("done")
        try { host.receive(5000); fail("expected closed") } catch (_: LinkClosedException) {}
    }
}
