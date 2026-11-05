package io.github.storskegg.graphite.media

import io.github.storskegg.graphite.Controller
import io.github.storskegg.graphite.log
import io.github.storskegg.graphite.ui.Progress
import io.github.storskegg.graphite.ui.Screen
import io.github.storskegg.graphite.util.formatBytes
import io.github.storskegg.graphite.util.formatDuration

/**
 * Progress on screen (copying, verifying), updated per chunk but at most [PER_SECOND] times a
 * second, with the rate and the time left; a log line every [LOG_EVERY_MS]. [onShow] gets each
 * update that was shown, to pass on to the other phone.
 */
class Meter(
    private val ctl: Controller,
    private val title: String,
    private val total: Long,
    private val files: Int,
    private val tiles: TileMap? = null,
    private val onShow: ((ProgressMsg) -> Unit)? = null,
) {
    private val t0 = System.nanoTime()
    private var lastShown = 0L
    private var lastLogged = 0L

    /** [done] bytes so far in all; [n] is the 1-based file number, [path] the current file. */
    fun update(done: Long, n: Int, path: String, force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && now - lastShown < 1_000_000_000L / PER_SECOND) return
        lastShown = now
        val shown = done.coerceIn(0, total)
        val label = "${formatBytes(shown)} of ${formatBytes(total)}" + rateAndLeft(shown, total, (now - t0) / 1e9)
        val counted = "%,d of %,d".format(n, files)
        val snapshot = tiles?.snapshot()
        ctl.show(Screen(title, "$counted: $path", progress = Progress(shown, total, label), tiles = snapshot))
        onShow?.invoke(ProgressMsg("$counted files: $path", shown, total, label, tiles?.delta() ?: ByteArray(0)))
        if (now - lastLogged > LOG_EVERY_MS * 1_000_000) {
            lastLogged = now
            log("$title: $label, file $n of $files" + (snapshot?.let { "; tiles: ${it.summary()}" } ?: ""))
        }
    }

    companion object {
        const val PER_SECOND = 4
        const val LOG_EVERY_MS = 5000L

        /** " at 27 MB/s, about 3 minutes left", once a rate means something. */
        fun rateAndLeft(done: Long, total: Long, secs: Double): String {
            if (secs < 1 || done <= 0) return ""
            val rate = done / secs
            val left = ((total - done) / rate).toLong()
            return " at %.0f MB/s".format(rate / 1e6) + if (secs >= 3 && done < total) ", ${formatDuration(left)} left" else ""
        }
    }
}
