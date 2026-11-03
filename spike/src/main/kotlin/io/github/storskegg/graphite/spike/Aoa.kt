package io.github.storskegg.graphite.spike

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val GOOGLE_VID = 0x18D1
fun isAccessoryMode(d: UsbDevice) = d.vendorId == GOOGLE_VID && d.productId in 0x2D00..0x2D05

// Q1: host side switches the other phone into AOA and measures bulk throughput;
// accessory side serves a tiny command protocol: 13-byte header 'G', cmd, u64 length (LE), 3 pad.
class Aoa(val a: MainActivity) {
    val MiB = 1024L * 1024
    val total = 512 * MiB

    fun header(cmd: Char, len: Long): ByteArray =
        ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN).put('G'.code.toByte()).put(cmd.code.toByte()).putLong(len).array()

    fun host() {
        var dev = a.um.deviceList.values.firstOrNull()
            ?: return a.log("no USB device attached: this phone is not the USB host")
        a.log("found " + a.describe(dev))
        if (!isAccessoryMode(dev)) {
            if (!a.awaitPermission(d = dev)) return
            val c = a.um.openDevice(dev)
            val v = ByteArray(2)
            val n = c.controlTransfer(0xC0, 51, 0, 0, v, 2, 1000)
            a.log("AOA get-protocol: rc=$n version=${(v[0].toInt() and 0xff) or (v[1].toInt() shl 8)}")
            listOf("storskegg", "graphite-spike", "graphite feasibility spike", "1",
                "https://github.com/storskegg/graphite", "spike0001").forEachIndexed { i, s ->
                val b = (s + "\u0000").toByteArray()
                c.controlTransfer(0x40, 52, 0, i, b, b.size, 1000)
            }
            val t0 = System.nanoTime()
            a.log("AOA start: rc=${c.controlTransfer(0x40, 53, 0, 0, null, 0, 1000)}")
            c.close()
            var found: UsbDevice? = null
            while (found == null && System.nanoTime() - t0 < 15_000_000_000) {
                Thread.sleep(100)
                found = a.um.deviceList.values.firstOrNull { isAccessoryMode(it) }
            }
            dev = found ?: return a.log("device did not re-enumerate in accessory mode within 15 s")
            a.log("re-enumerated in ${(System.nanoTime() - t0) / 1_000_000} ms as " + a.describe(dev))
        }
        if (!a.awaitPermission(d = dev)) return
        val c = a.um.openDevice(dev)
        val raw = c.rawDescriptors
        a.log("bcdUSB=%x.%02x".format(raw[3].toInt() and 0xff, raw[2].toInt() and 0xff))
        val intf = (0 until dev.interfaceCount).map { dev.getInterface(it) }
            .first { it.interfaceSubclass != 0x42 && it.endpointCount == 2 }
        check(c.claimInterface(intf, true))
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) intf.getEndpoint(i).let {
            if (it.direction == UsbConstants.USB_DIR_IN) epIn = it else epOut = it
        }
        a.log("accessory bulk maxPacket=${epIn!!.maxPacketSize} (512 = USB 2 high speed, 1024 = USB 3)")

        a.log("ping: waiting for the other phone to open the accessory (tap OK there)")
        val buf = ByteArray(16384)
        var stale = 0L
        while (true) { val k = c.bulkTransfer(epIn, buf, buf.size, 300); if (k < 0) break; stale += k }
        if (stale > 0) a.log("drained $stale stale bytes left in the pipe by an earlier session")
        check(c.bulkTransfer(epOut, header('P', 0), 13, 90_000) == 13) { "ping write failed" }
        val n = c.bulkTransfer(epIn, buf, buf.size, 90_000)
        val pong = String(buf, 0, maxOf(minOf(n, 8), 0))
        a.log("pong: $pong (rc=$n)")
        if (pong != "PONG") return a.log("no clean PONG; aborting")

        // Async download is left out: zero-length packets after each accessory write() broke its accounting.
        for (round in 1..2) {
            syncUp(c, epOut!!, epIn!!); drain(c, epIn!!)
            syncDown(c, epOut!!, epIn!!); drain(c, epIn!!)
            asyncUp(c, epOut!!, epIn!!); drain(c, epIn!!)
        }
        c.bulkTransfer(epOut, header('Q', 0), 13, 2000)
        c.close()
        a.log("AOA throughput done")
    }

    // Swallows the trailing zero-length packet (or anything else) so the next ack read is clean.
    fun drain(c: UsbDeviceConnection, epIn: UsbEndpoint) {
        val b = ByteArray(16384)
        while (c.bulkTransfer(epIn, b, b.size, 200) >= 0) {}
    }

    fun rate(bytes: Long, ns: Long) = "%.1f MB/s (%.1f MiB/s) over %d MiB in %.2f s".format(
        bytes / (ns / 1e9) / 1e6, bytes / (ns / 1e9) / MiB, bytes / MiB, ns / 1e9)

    fun ack(c: UsbDeviceConnection, epIn: UsbEndpoint): Long {
        val b = ByteArray(16384)
        val n = c.bulkTransfer(epIn, b, b.size, 60_000)
        return if (n >= 8) ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).long else -1
    }

    fun syncUp(c: UsbDeviceConnection, epOut: UsbEndpoint, epIn: UsbEndpoint) {
        val chunk = ByteArray(256 * 1024) { it.toByte() }
        c.bulkTransfer(epOut, header('U', total), 13, 10_000)
        val t0 = System.nanoTime()
        var sent = 0L
        while (sent < total) {
            val n = c.bulkTransfer(epOut, chunk, chunk.size, 10_000)
            if (n <= 0) return a.log("sync up: write failed rc=$n after $sent")
            sent += n
        }
        val accNs = ack(c, epIn)
        a.log("host->acc sync 256K: host ${rate(sent, System.nanoTime() - t0)}; accessory saw ${rate(sent, accNs)}")
    }

    fun syncDown(c: UsbDeviceConnection, epOut: UsbEndpoint, epIn: UsbEndpoint) {
        val chunk = ByteArray(256 * 1024)
        c.bulkTransfer(epOut, header('D', total), 13, 10_000)
        var got = 0L
        var t0 = 0L
        var zlps = 0
        while (got < total) {
            val n = c.bulkTransfer(epIn, chunk, chunk.size, 10_000)
            if (n == 0) { zlps++; continue }  // f_accessory ends each write() with a zero-length packet
            if (n < 0) return a.log("sync down: read failed rc=$n after $got")
            if (t0 == 0L) t0 = System.nanoTime()
            got += n
        }
        a.log("acc->host sync 256K: ${rate(got, System.nanoTime() - t0)} ($zlps zero-length packets)")
    }

    // Eight queued 256 KiB requests, so the host controller never idles between transfers.
    fun pump(c: UsbDeviceConnection, ep: UsbEndpoint, out: Boolean): Pair<Long, Long> {
        val size = 256 * 1024
        val reqs = (0 until 8).map {
            UsbRequest().apply {
                initialize(c, ep)
                clientData = ByteBuffer.allocateDirect(size)
            }
        }
        var queued = 0L
        var done = 0L
        var t0 = if (out) System.nanoTime() else 0L
        fun queue(r: UsbRequest) {
            val b = r.clientData as ByteBuffer
            b.clear()
            r.queue(b)
            queued += size
        }
        reqs.forEach { if (queued < total) queue(it) }
        var outstanding = reqs.size
        while (done < total) {
            val r = try { c.requestWait(10_000) } catch (e: java.util.concurrent.TimeoutException) { a.log("request timed out after $done bytes"); return done to -1L }
            outstanding--
            if (t0 == 0L) t0 = System.nanoTime()
            val b = r.clientData as ByteBuffer
            done += if (out) size.toLong() else b.position().toLong()
            if (queued < total) { queue(r); outstanding++ }
        }
        val ns = System.nanoTime() - t0
        reqs.forEach { it.cancel() }
        repeat(outstanding) { runCatching { c.requestWait(200) } }
        reqs.forEach { it.close() }
        return done to ns
    }

    fun asyncUp(c: UsbDeviceConnection, epOut: UsbEndpoint, epIn: UsbEndpoint) {
        c.bulkTransfer(epOut, header('U', total), 13, 10_000)
        val (bytes, ns) = pump(c, epOut, out = true)
        val accNs = ack(c, epIn)
        a.log("host->acc async 8x256K: host ${rate(bytes, ns)}; accessory saw ${rate(bytes, accNs)}")
    }

    fun asyncDown(c: UsbDeviceConnection, epOut: UsbEndpoint, epIn: UsbEndpoint) {
        c.bulkTransfer(epOut, header('D', total), 13, 10_000)
        val (bytes, ns) = pump(c, epIn, out = false)
        a.log("acc->host async 8x256K: ${rate(bytes, ns)}")
    }

    fun serve() {
        val acc = a.um.accessoryList?.firstOrNull() ?: return a.log("no accessory: this phone is not in accessory mode")
        a.log("accessory: $acc")
        if (!a.awaitPermission(a = acc)) return
        val pfd = a.um.openAccessory(acc) ?: return a.log("openAccessory returned null")
        val ins = FileInputStream(pfd.fileDescriptor)
        val out = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(16384)
        val pattern = ByteArray(1024 * 1024) { it.toByte() }
        a.log("serving")
        try {
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                if (n != 13 || buf[0] != 'G'.code.toByte()) { a.log("unexpected $n bytes"); continue }
                val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                val cmd = bb.get(1).toInt().toChar()
                val len = bb.getLong(2)
                when (cmd) {
                    'P' -> out.write("PONG".toByteArray())
                    'U' -> {
                        var got = 0L
                        var t0 = 0L
                        while (got < len) {
                            val k = ins.read(buf)
                            if (k < 0) throw java.io.EOFException()
                            if (t0 == 0L) t0 = System.nanoTime()
                            got += k
                        }
                        val ns = System.nanoTime() - t0
                        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(ns).array())
                        a.log("received ${got / (1024 * 1024)} MiB, ${rate(got, ns)}")
                    }
                    'D' -> {
                        val t0 = System.nanoTime()
                        var sent = 0L
                        while (sent < len) {
                            val k = minOf(pattern.size.toLong(), len - sent).toInt()
                            out.write(pattern, 0, k)
                            sent += k
                        }
                        a.log("sent ${sent / (1024 * 1024)} MiB, ${rate(sent, System.nanoTime() - t0)}")
                    }
                    'Q' -> break
                }
            }
        } catch (e: java.io.IOException) {
            a.log("accessory closed: $e")
        }
        pfd.close()
        a.log("accessory session over")
    }
}
