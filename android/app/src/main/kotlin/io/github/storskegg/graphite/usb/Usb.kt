package io.github.storskegg.graphite.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.ParcelFileDescriptor
import io.github.storskegg.graphite.link.BytePipe
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

const val GOOGLE_VID = 0x18D1

/** What this phone really is on the cable, read from [UsbManager], never from the Settings label. */
enum class UsbRole {
    /** A device is attached: this phone is USB host. */
    HOST,
    /** An accessory is attached: the other phone is host and has put this one in AOA mode. */
    ACCESSORY,
    /** Cable in and this phone is the USB device, but not (yet) in accessory mode. */
    DEVICE,
    /** No data connection. */
    NONE,
}

data class LinkSpeed(val bcdUsb: String, val maxPacket: Int) {
    val usb3 get() = maxPacket >= 1024
    override fun toString() = if (usb3) "USB 3 (bcdUSB $bcdUsb, $maxPacket-byte packets)"
        else "USB 2 (bcdUSB $bcdUsb, $maxPacket-byte packets): a USB 3 cable is 3-4 times faster"
}

object Usb {
    private const val USB_STATE = "android.hardware.usb.action.USB_STATE"

    fun isAccessoryMode(d: UsbDevice) = d.vendorId == GOOGLE_VID && d.productId in 0x2D00..0x2D05

    fun adbInterface(d: UsbDevice): UsbInterface? = (0 until d.interfaceCount).map { d.getInterface(it) }
        .firstOrNull { it.interfaceClass == 255 && it.interfaceSubclass == 0x42 && it.interfaceProtocol == 1 }

    fun role(context: Context): UsbRole {
        val um = context.getSystemService(UsbManager::class.java)
        if (um.deviceList.isNotEmpty()) return UsbRole.HOST
        if (!um.accessoryList.isNullOrEmpty()) return UsbRole.ACCESSORY
        // USB_STATE is a sticky system broadcast: "connected" means a cable with data and us as the device.
        val sticky = context.registerReceiver(null, IntentFilter(USB_STATE), Context.RECEIVER_NOT_EXPORTED)
        return if (sticky?.getBooleanExtra("connected", false) == true) UsbRole.DEVICE else UsbRole.NONE
    }

    fun describe(d: UsbDevice) = "%04x:%04x %s %s".format(d.vendorId, d.productId,
        d.manufacturerName ?: "", d.productName ?: "").trim()

    /** Asks for USB permission and blocks the calling (worker) thread until the user answers. */
    fun requestPermission(context: Context, device: UsbDevice? = null, accessory: UsbAccessory? = null): Boolean {
        val um = context.getSystemService(UsbManager::class.java)
        if (device != null && um.hasPermission(device)) return true
        if (accessory != null && um.hasPermission(accessory)) return true
        val action = "${context.packageName}.USB_PERMISSION"
        val latch = CountDownLatch(1)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = latch.countDown()
        }
        context.registerReceiver(r, IntentFilter(action), Context.RECEIVER_EXPORTED)
        try {
            val pi = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            if (device != null) um.requestPermission(device, pi) else um.requestPermission(accessory, pi)
            // The answer can also come without the broadcast: when the accessory intent reopens graphite, Android
            // grants the permission and the dialog goes away unanswered (a 2-minute stall after a cable pull).
            val deadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < deadline && !latch.await(500, TimeUnit.MILLISECONDS)) {
                if (if (device != null) um.hasPermission(device) else um.hasPermission(accessory)) break
            }
        } finally {
            context.unregisterReceiver(r)
        }
        return if (device != null) um.hasPermission(device) else um.hasPermission(accessory)
    }

    /**
     * Host side: puts the attached phone into Android Open Accessory mode. It
     * then drops off the bus and comes back as 18d1:2d0x within 1-2 s.
     */
    fun switchToAccessory(um: UsbManager, device: UsbDevice): Int {
        val c = um.openDevice(device) ?: throw IOException("could not open ${describe(device)}")
        try {
            val v = ByteArray(2)
            val n = c.controlTransfer(0xC0, 51, 0, 0, v, 2, 1000)
            if (n != 2) throw IOException("the other phone does not speak AOA (rc $n)")
            val version = (v[0].toInt() and 0xff) or (v[1].toInt() shl 8)
            listOf("storskegg", "graphite", "graphite: move data to a GrapheneOS phone", "1",
                "https://github.com/storskegg/graphite", "graphite").forEachIndexed { i, s ->
                val b = (s + "\u0000").toByteArray()
                c.controlTransfer(0x40, 52, 0, i, b, b.size, 1000)
            }
            if (c.controlTransfer(0x40, 53, 0, 0, null, 0, 1000) < 0) throw IOException("AOA start refused")
            return version
        } finally {
            c.close()
        }
    }

    /** Host side: opens the accessory interface of a phone in AOA mode. */
    fun openHostPipe(um: UsbManager, device: UsbDevice): Pair<HostPipe, LinkSpeed> {
        val c = um.openDevice(device) ?: throw IOException("could not open ${describe(device)}")
        val intf = (0 until device.interfaceCount).map { device.getInterface(it) }
            .firstOrNull { it.interfaceSubclass != 0x42 && it.endpointCount == 2 }
            ?: run { c.close(); throw IOException("no accessory interface on ${describe(device)}") }
        if (!c.claimInterface(intf, true)) { c.close(); throw IOException("could not claim the accessory interface") }
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) intf.getEndpoint(i).let {
            if (it.direction == UsbConstants.USB_DIR_IN) epIn = it else epOut = it
        }
        val raw = c.rawDescriptors
        val bcd = if (raw != null && raw.size >= 4) "%x.%02x".format(raw[3].toInt() and 0xff, raw[2].toInt() and 0xff) else "?"
        return HostPipe(c, intf, epIn!!, epOut!!) to LinkSpeed(bcd, epIn!!.maxPacketSize)
    }

    fun openAccessoryPipe(um: UsbManager, accessory: UsbAccessory): AccessoryPipe {
        val pfd = um.openAccessory(accessory) ?: throw IOException("could not open the accessory")
        return AccessoryPipe(pfd)
    }
}

/**
 * The USB host's end. Reads keep [QUEUED] requests in flight so the host
 * controller never idles, and count bytes per completion: the accessory ends
 * every write with a zero-length packet, so a completion can be short or empty.
 */
class HostPipe(
    private val conn: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
) : BytePipe {
    companion object {
        const val REQUEST_SIZE = 256 * 1024
        const val QUEUED = 8
    }

    @Volatile private var closed = false
    private var requests: List<UsbRequest>? = null

    override fun read(into: ByteArray): Int {
        require(into.size >= REQUEST_SIZE)
        val reqs = requests ?: List(QUEUED) {
            UsbRequest().apply {
                if (!initialize(conn, epIn)) throw IOException("USB request init failed")
                clientData = ByteBuffer.allocateDirect(REQUEST_SIZE)
            }
        }.also { requests = it; it.forEach(::queue) }
        while (true) {
            if (closed) throw IOException("closed")
            val r = try {
                conn.requestWait(500)
            } catch (_: TimeoutException) {
                continue
            } ?: throw IOException("USB read failed (EIO)")
            if (r !in reqs) continue
            val b = r.clientData as ByteBuffer
            val n = b.position()
            b.flip()
            b.get(into, 0, n)
            queue(r)
            return n
        }
    }

    private fun queue(r: UsbRequest) {
        val b = r.clientData as ByteBuffer
        b.clear()
        if (closed || !r.queue(b)) throw IOException("USB queue failed (EIO)")
    }

    override fun write(b: ByteArray) {
        var off = 0
        while (off < b.size) {
            if (closed) throw IOException("closed")
            val n = conn.bulkTransfer(epOut, b, off, minOf(REQUEST_SIZE, b.size - off), 10_000)
            if (n < 0) throw IOException("USB write failed (EIO)")
            off += n
        }
        // A transfer that is a multiple of the packet size needs a zero-length
        // packet, or the accessory's read waits for more.
        if (b.isNotEmpty() && b.size % epOut.maxPacketSize == 0) conn.bulkTransfer(epOut, ByteArray(0), 0, 1000)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { conn.releaseInterface(intf) }
        runCatching { conn.close() }
    }
}

/** The accessory's end: a file descriptor onto f_accessory. */
class AccessoryPipe(private val pfd: ParcelFileDescriptor) : BytePipe {
    private val ins = FileInputStream(pfd.fileDescriptor)
    private val out = FileOutputStream(pfd.fileDescriptor)
    @Volatile private var closed = false
    private var empty = 0

    override fun read(into: ByteArray): Int {
        if (closed) throw IOException("closed")
        val n = ins.read(into)
        // A zero-length packet reads as end of file: it is no data, not the end.
        if (n <= 0) {
            if (++empty > 100) { Thread.sleep(5); empty = 0 }
            return 0
        }
        empty = 0
        return n
    }

    override fun write(b: ByteArray) {
        if (closed) throw IOException("closed")
        out.write(b)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { pfd.close() }
    }
}
