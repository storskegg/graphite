package io.github.storskegg.graphite.usb

import io.github.storskegg.graphite.link.BytePipe
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Debug builds only: the link over TCP instead of USB, so two emulators can
 * run the whole flow (see android/emulator-test.sh). The release source set
 * has a stub that refuses, so no release build can open a socket.
 */
object TestTransport {
    const val AVAILABLE = true

    private var server: ServerSocket? = null

    /** Target: waits up to [timeoutMs] for the source to connect on [port]. */
    @Synchronized
    fun accept(port: Int, timeoutMs: Int): BytePipe? {
        val s = server ?: ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }.also { server = it }
        s.soTimeout = timeoutMs
        return try { SocketPipe(s.accept()) } catch (_: java.net.SocketTimeoutException) { null }
    }

    /** Source: connects to the target. */
    fun connect(host: String, port: Int): BytePipe =
        SocketPipe(Socket().apply { connect(InetSocketAddress(host, port), 3000); tcpNoDelay = true })

    private class SocketPipe(private val s: Socket) : BytePipe {
        private val ins = s.getInputStream()
        private val out = s.getOutputStream()

        override fun read(into: ByteArray): Int {
            val n = ins.read(into)
            if (n < 0) throw IOException("socket closed")
            return n
        }

        override fun write(b: ByteArray) = out.write(b)

        override fun close() = s.close()
    }
}
