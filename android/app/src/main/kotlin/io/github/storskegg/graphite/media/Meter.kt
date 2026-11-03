package io.github.storskegg.graphite.media

import io.github.storskegg.graphite.Controller
import io.github.storskegg.graphite.log
import io.github.storskegg.graphite.ui.Progress
import io.github.storskegg.graphite.ui.Screen
import io.github.storskegg.graphite.util.formatBytes

/**
 * Copy progress on screen, updated per chunk but at most [PER_SECOND] times a
 * second, with the rate; a log line with the rate every [LOG_EVERY_MS].
 */
class Meter(private val ctl: Controller, private val title: String, private val total: Long, private val files: Int) {
    private val t0 = System.nanoTime()
    private var lastShown = 0L
    private var lastLogged = 0L

    /** [done] bytes so far in all; [n] is the 1-based file number, [path] the current file. */
    fun update(done: Long, n: Int, path: String, force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && now - lastShown < 1_000_000_000L / PER_SECOND) return
        lastShown = now
        val secs = (now - t0) / 1e9
        val rate = if (secs > 1) " at %.0f MB/s".format(done / secs / 1e6) else ""
        ctl.show(Screen(title, "$n of $files: $path", progress = Progress(done, total, "${formatBytes(done)} of ${formatBytes(total)}$rate")))
        if (now - lastLogged > LOG_EVERY_MS * 1_000_000) {
            lastLogged = now
            log("$title: ${formatBytes(done)} of ${formatBytes(total)}$rate, file $n of $files")
        }
    }

    companion object {
        const val PER_SECOND = 4
        const val LOG_EVERY_MS = 5000L
    }
}
