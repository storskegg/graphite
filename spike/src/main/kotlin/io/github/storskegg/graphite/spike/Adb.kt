package io.github.storskegg.graphite.spike

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

// Q2: the smallest adb client that can talk to another phone's adbd over a USB host port.
class AdbUsb(val c: UsbDeviceConnection, val epIn: UsbEndpoint, val epOut: UsbEndpoint, val log: (String) -> Unit) {
    companion object {
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
        const val STLS = 0x534c5453
        val SHA1_PREFIX = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
    }

    class Msg(val cmd: Int, val a0: Int, val a1: Int, val data: ByteArray)

    var maxData = 4096
    var nextId = 1
    val pending = ArrayDeque<ByteArray>()

    fun write(b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val n = c.bulkTransfer(epOut, b, off, minOf(16384, b.size - off), 10_000)
            check(n > 0) { "bulk out failed rc=$n" }
            off += n
        }
        if (b.isNotEmpty() && b.size % epOut.maxPacketSize == 0) c.bulkTransfer(epOut, ByteArray(0), 0, 1000)
    }

    fun send(cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) {
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(cmd).putInt(a0).putInt(a1).putInt(data.size)
            .putInt(data.sumOf { it.toInt() and 0xff }).putInt(cmd.inv())
        write(h.array())
        if (data.isNotEmpty()) write(data)
    }

    fun read(timeout: Int): Msg {
        val buf = ByteArray(16384)
        var n: Int
        do { n = c.bulkTransfer(epIn, buf, buf.size, timeout) } while (n == 0)
        check(n >= 24) { "short adb header rc=$n" }
        val h = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = h.getInt(0); val a0 = h.getInt(4); val a1 = h.getInt(8); val len = h.getInt(12)
        val data = ByteArrayOutputStream()
        data.write(buf, 24, n - 24)
        while (data.size() < len) {
            val k = c.bulkTransfer(epIn, buf, buf.size, timeout)
            check(k >= 0) { "payload read failed" }
            data.write(buf, 0, k)
        }
        return Msg(cmd, a0, a1, data.toByteArray())
    }

    fun connect(key: KeyPair): String? {
        send(CNXN, 0x01000001, 256 * 1024, "host::features=shell_v2,cmd\u0000".toByteArray())
        var signed = false
        while (true) {
            val m = read(if (signed) 120_000 else 10_000)
            when (m.cmd) {
                AUTH -> if (!signed) {
                    val s = Signature.getInstance("NONEwithRSA")
                    s.initSign(key.private)
                    s.update(SHA1_PREFIX + m.data)
                    send(AUTH, 2, 0, s.sign())
                    signed = true
                } else {
                    log("key not yet trusted: sent public key; tap Allow USB debugging on the other phone")
                    send(AUTH, 3, 0, adbPublicKey(key.public as RSAPublicKey))
                }
                CNXN -> { maxData = m.a1; return String(m.data) }
                STLS -> { log("device asked for TLS (not supported by spike)"); return null }
                else -> log("unexpected 0x%08x during connect".format(m.cmd))
            }
        }
    }

    // Runs one service ("shell:..." or "exec:...") and returns everything it printed.
    fun run(service: String, input: ByteArray? = null): String {
        val local = nextId++
        send(OPEN, local, 0, (service + "\u0000").toByteArray())
        // Messages for an earlier stream (late CLSE/OKAY) carry another local id in arg1: skip them.
        fun next(timeout: Int): Msg {
            while (true) { val m = read(timeout); if (m.a1 == local) return m }
        }
        var m = next(10_000)
        if (m.cmd == CLSE) return "<refused>"
        check(m.cmd == OKAY) { "expected OKAY, got 0x%08x".format(m.cmd) }
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
                    if (m.cmd == CLSE) return out.toString() + "<closed early>"
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
}

// Android's own adb public key format: little-endian modulus words plus Montgomery constants.
fun adbPublicKey(pub: RSAPublicKey): ByteArray {
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
    return (Base64.encodeToString(bb.array(), Base64.NO_WRAP) + " graphite-spike@phone\u0000").toByteArray()
}

class AdbProbe(val a: MainActivity) {
    fun key(): KeyPair {
        val priv = File(a.filesDir, "adbkey"); val pub = File(a.filesDir, "adbkey.pub")
        val kf = KeyFactory.getInstance("RSA")
        if (priv.exists() && pub.exists())
            return KeyPair(kf.generatePublic(X509EncodedKeySpec(pub.readBytes())), kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes())))
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        priv.writeBytes(kp.private.encoded); pub.writeBytes(kp.public.encoded)
        return kp
    }

    fun run(install: Boolean) {
        val dev = a.um.deviceList.values.firstOrNull { d ->
            (0 until d.interfaceCount).any { d.getInterface(it).run { interfaceClass == 255 && interfaceSubclass == 0x42 && interfaceProtocol == 1 } }
        } ?: return a.log("no attached device exposes an adb interface (is USB debugging on over there?). Attached: " +
            a.um.deviceList.values.joinToString { a.describe(it) })
        a.log("adb interface on " + a.describe(dev))
        if (!a.awaitPermission(d = dev)) return
        val c = a.um.openDevice(dev)
        val intf = (0 until dev.interfaceCount).map { dev.getInterface(it) }.first { it.interfaceSubclass == 0x42 }
        check(c.claimInterface(intf, true))
        var epIn: UsbEndpoint? = null; var epOut: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) intf.getEndpoint(i).let {
            if (it.direction == UsbConstants.USB_DIR_IN) epIn = it else epOut = it
        }
        val adb = AdbUsb(c, epIn!!, epOut!!, a::log)
        val t0 = System.nanoTime()
        val banner = adb.connect(key()) ?: return
        a.log("connected in ${(System.nanoTime() - t0) / 1_000_000} ms: $banner (maxdata ${adb.maxData})")
        a.log(adb.run("shell:id; getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.display.id"))
        if (install) {
            val apk = File(a.applicationInfo.sourceDir).readBytes()
            val ti = System.nanoTime()
            a.log("install: " + adb.run("exec:cmd package install -r -S ${apk.size}", apk).trim() +
                " (${apk.size} bytes in ${(System.nanoTime() - ti) / 1_000_000} ms)")
            val pkg = a.packageName
            a.log(adb.run("shell:pm grant $pkg android.permission.READ_CALL_LOG; pm grant $pkg android.permission.READ_SMS; " +
                "appops set $pkg MANAGE_EXTERNAL_STORAGE allow; dumpsys package $pkg | grep -E 'READ_SMS|CALL_LOG|installerPackageName|packageSource'; " +
                "appops get $pkg MANAGE_EXTERNAL_STORAGE"))
        }
        c.close()
        a.log("adb probe done")
    }
}
