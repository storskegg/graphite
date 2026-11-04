package io.github.storskegg.graphite.usb

import io.github.storskegg.graphite.link.BytePipe

/** Release builds: graphite talks over the USB cable only. The TCP test transport exists in debug builds. */
object TestTransport {
    const val AVAILABLE = false

    fun accept(port: Int, timeoutMs: Int): BytePipe? = throw UnsupportedOperationException("debug builds only")

    fun connect(host: String, port: Int): BytePipe = throw UnsupportedOperationException("debug builds only")
}
