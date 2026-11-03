package io.github.storskegg.graphite.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.storskegg.graphite.Controller
import io.github.storskegg.graphite.log
import io.github.storskegg.graphite.ui.Screen
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey

/**
 * Setup over the cable: when graphite doesn't answer on the old phone and that
 * phone has USB debugging on, this phone (the USB host) acts as an adb client,
 * installs its own APK there and grants what graphite needs. Without USB
 * debugging it explains how to sideload instead.
 */
class CableSetup(private val ctl: Controller, private val conn: Connection) {
    fun offer(dev: UsbDevice) {
        if (Usb.adbInterface(dev) == null) {
            ctl.ask(Screen("Install graphite on the old phone", SIDELOAD, buttons = listOf("Check again", OPEN_THERE)))
            return
        }
        val a = ctl.ask(Screen("Install graphite on the old phone?",
            "graphite isn't answering on the old phone, but USB debugging is on there, so this phone can install " +
                "graphite over the cable and allow what it needs (access to all files, notifications).\n\n" +
                "The old phone asks \"Allow USB debugging?\" for this phone's key: tap Allow there. You can turn USB " +
                "debugging off again afterwards.",
            buttons = listOf("Install over the cable", "I'll install it myself", OPEN_THERE)))
        if (a.button == OPEN_THERE) return
        if (a.button != "Install over the cable") {
            ctl.ask(Screen("Install graphite on the old phone", SIDELOAD, buttons = listOf("Check again")))
            return
        }
        ctl.requireAuth("installs graphite on the other phone")
        val result = try {
            conn.withLinkReleased { install(dev) }
        } catch (e: Exception) {
            log("cable setup: $e")
            "Setup failed: ${e.message}\n\n$SIDELOAD"
        }
        ctl.ask(Screen("Setting up the old phone", result, buttons = listOf("Continue")))
    }

    private fun install(dev: UsbDevice): String {
        val um = ctl.usbManager
        val intf = Usb.adbInterface(dev) ?: throw IOException("the adb interface went away")
        val c = um.openDevice(dev) ?: throw IOException("could not open ${Usb.describe(dev)}")
        try {
            if (!c.claimInterface(intf, true)) throw IOException("could not claim the adb interface")
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until intf.endpointCount) intf.getEndpoint(i).let {
                if (it.direction == UsbConstants.USB_DIR_IN) epIn = it else epOut = it
            }
            val adb = AdbClient(c, epIn!!, epOut!!)
            ctl.show(Screen("Setting up the old phone", "On the old phone, tap Allow on \"Allow USB debugging?\"."))
            val banner = adb.connect(AdbKey.private(), AdbKey.publicKey())
            log("adb connected: $banner")
            val apk = File(ctl.app.applicationInfo.sourceDir).readBytes()
            ctl.show(Screen("Setting up the old phone", "Installing graphite (${apk.size / 1_000_000} MB)…"))
            val out = adb.run("exec:cmd package install -r -S ${apk.size}", apk).trim()
            log("adb install: $out")
            if (!out.contains("Success")) throw IOException("install refused: $out")
            val pkg = ctl.app.packageName
            val grants = adb.run("shell:appops set $pkg MANAGE_EXTERNAL_STORAGE allow; " +
                "pm grant $pkg android.permission.POST_NOTIFICATIONS; appops get $pkg MANAGE_EXTERNAL_STORAGE; " +
                "am start -n $pkg/.MainActivity" +
                // Test builds: the old phone gets the same test scope, so it never lists the user's own files.
                (ctl.testScope?.let { " --es test_scope '$it'" } ?: "")).trim()
            log("adb grants: $grants")
            return "graphite is installed on the old phone and allowed to read all files. It opens there now: " +
                "confirm it's you on the old phone, and allow it to use the USB accessory if asked."
        } finally {
            c.close()
        }
    }

    companion object {
        const val OPEN_THERE = "It's already open there"
        const val SIDELOAD = "graphite isn't answering on the old phone. If it is open there, confirm it's you on " +
            "the old phone and allow it to use the USB accessory. If it isn't installed, either:\n\n" +
            "• Install it there yourself: copy graphite's APK to the old phone (or download it from " +
            "github.com/storskegg/graphite), open it in Files and allow installing from that app; then open graphite.\n\n" +
            "• Or turn on USB debugging on the old phone (Settings > About phone: tap Build number 7 times; then " +
            "Settings > System > Developer options > USB debugging), and this phone installs it over the cable."
    }
}

/** This phone's adb key: RSA 2048 in the Android Keystore, never leaving it. */
object AdbKey {
    private const val ALIAS = "graphite-adb"

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun private(): PrivateKey {
        val ks = store()
        (ks.getKey(ALIAS, null) as? PrivateKey)?.let { return it }
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                // adb signs a 20-byte token as if it were a SHA-1 digest: raw PKCS#1 v1.5 over DigestInfo.
                .setDigests(KeyProperties.DIGEST_NONE)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .build())
        }.generateKeyPair()
        return store().getKey(ALIAS, null) as PrivateKey
    }

    fun publicKey(): RSAPublicKey = store().getCertificate(ALIAS).publicKey as RSAPublicKey

    /** adbd's public key format: little-endian modulus words plus Montgomery constants, base64, then a name. */
    fun encode(pub: RSAPublicKey): ByteArray {
        val n = pub.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32).toLong().toInt()
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
        fun le(x: BigInteger): ByteArray {
            val be = x.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            return (ByteArray(256 - be.size) + be).reversedArray()
        }
        val bb = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(64).putInt(n0inv).put(le(n)).put(le(rr)).putInt(pub.publicExponent.toInt())
        return (Base64.encodeToString(bb.array(), Base64.NO_WRAP) + " graphite@new-phone\u0000").toByteArray()
    }
}

/** The smallest adb client that installs an APK over a USB host port (from the spike). */
class AdbClient(private val c: UsbDeviceConnection, private val epIn: UsbEndpoint, private val epOut: UsbEndpoint) {
    private class Msg(val cmd: Int, val a0: Int, val a1: Int, val data: ByteArray)

    private var maxData = 4096
    private var nextId = 1

    private fun write(b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val n = c.bulkTransfer(epOut, b, off, minOf(16384, b.size - off), 10_000)
            if (n <= 0) throw IOException("USB write failed ($n)")
            off += n
        }
        if (b.isNotEmpty() && b.size % epOut.maxPacketSize == 0) c.bulkTransfer(epOut, ByteArray(0), 0, 1000)
    }

    private fun send(cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) {
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(cmd).putInt(a0).putInt(a1).putInt(data.size)
            .putInt(data.sumOf { it.toInt() and 0xff }).putInt(cmd.inv())
        write(h.array())
        if (data.isNotEmpty()) write(data)
    }

    private fun read(timeout: Int): Msg {
        val buf = ByteArray(16384)
        var n: Int
        do { n = c.bulkTransfer(epIn, buf, buf.size, timeout) } while (n == 0)
        if (n < 24) throw IOException("no answer from adb on the other phone ($n)")
        val h = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val len = h.getInt(12)
        val data = ByteArrayOutputStream().apply { write(buf, 24, n - 24) }
        while (data.size() < len) {
            val k = c.bulkTransfer(epIn, buf, buf.size, timeout)
            if (k < 0) throw IOException("adb read failed")
            data.write(buf, 0, k)
        }
        return Msg(h.getInt(0), h.getInt(4), h.getInt(8), data.toByteArray())
    }

    /** Connects, signing adbd's token; if the key isn't trusted yet, offers it and waits for the user's Allow. */
    fun connect(key: PrivateKey, pub: RSAPublicKey): String {
        send(CNXN, 0x01000001, 256 * 1024, "host::features=shell_v2,cmd\u0000".toByteArray())
        var signed = false
        while (true) {
            val m = read(if (signed) 120_000 else 10_000)
            when (m.cmd) {
                AUTH -> if (!signed) {
                    val s = Signature.getInstance("NONEwithRSA")
                    s.initSign(key)
                    s.update(SHA1_PREFIX + m.data)
                    send(AUTH, 2, 0, s.sign())
                    signed = true
                } else {
                    send(AUTH, 3, 0, AdbKey.encode(pub))
                }
                CNXN -> { maxData = m.a1; return String(m.data).trimEnd('\u0000') }
                STLS -> throw IOException("the other phone asked for adb over TLS, which graphite doesn't support")
                else -> log("adb: unexpected 0x%08x while connecting".format(m.cmd))
            }
        }
    }

    /** Runs one service ("shell:…" or "exec:…"), optionally feeding it [input], and returns its output. */
    fun run(service: String, input: ByteArray? = null): String {
        val local = nextId++
        send(OPEN, local, 0, (service + "\u0000").toByteArray())
        // Late messages for an earlier stream carry another local id: skip them.
        fun next(timeout: Int): Msg { while (true) { val m = read(timeout); if (m.a1 == local) return m } }
        var m = next(10_000)
        if (m.cmd == CLSE) throw IOException("the other phone refused $service")
        if (m.cmd != OKAY) throw IOException("expected OKAY, got 0x%08x".format(m.cmd))
        val remote = m.a0
        val out = ByteArrayOutputStream()
        if (input != null) {
            var off = 0
            while (off < input.size) {
                val k = minOf(maxData, input.size - off)
                send(WRTE, local, remote, input.copyOfRange(off, off + k))
                off += k
                while (true) {
                    m = next(30_000)
                    if (m.cmd == OKAY) break
                    if (m.cmd == WRTE) { out.write(m.data); send(OKAY, local, remote) }
                    if (m.cmd == CLSE) return out.toString()
                }
            }
        }
        while (true) {
            m = next(120_000)
            when (m.cmd) {
                WRTE -> { out.write(m.data); send(OKAY, local, remote) }
                CLSE -> { send(CLSE, local, remote); return out.toString() }
            }
        }
    }

    companion object {
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
        const val STLS = 0x534c5453
        private val SHA1_PREFIX = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
    }
}
