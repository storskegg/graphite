package io.github.storskegg.graphite.media

import io.github.storskegg.graphite.util.formatBytes
import io.github.storskegg.graphite.util.formatDuration

data class Battery(val percent: Int, val charging: Boolean)

/** Phase 3's checks: space, battery, names and time. */
object Preflight {
    /** Kept free on the target beyond what Android itself reserves. */
    const val SPARE = 1L shl 30
    const val WARN_PERCENT = 50
    const val REFUSE_PERCENT = 15
    /** A transfer shorter than this doesn't trigger the battery warning. */
    const val LONG_SECONDS = 10 * 60L

    fun fits(bytesToCopy: Long, allocatable: Long) = bytesToCopy + SPARE <= allocatable

    /** Bytes to untick before the copy fits; 0 when it fits. */
    fun shortfall(bytesToCopy: Long, allocatable: Long) = maxOf(0L, bytesToCopy + SPARE - allocatable)

    fun seconds(bytes: Long, bytesPerSecond: Double) =
        if (bytesPerSecond <= 0) 0L else (bytes / bytesPerSecond).toLong()

    /** Why the copy can't start (refuse), or what the user should know first (warn). */
    data class BatteryVerdict(val refuse: String?, val warn: String?)

    fun battery(source: Battery, target: Battery, seconds: Long): BatteryVerdict {
        val phones = listOf("old" to source, "new" to target)
        val low = phones.filter { (_, b) -> !b.charging && b.percent < REFUSE_PERCENT }
        if (low.isNotEmpty()) return BatteryVerdict(
            low.joinToString(" and ") { (n, b) -> "the $n phone is at ${b.percent} %" } +
                ". Charge it to at least $REFUSE_PERCENT % (or plug it in) before copying.", null)
        val warn = phones.filter { (_, b) -> !b.charging && b.percent < WARN_PERCENT }
        if (warn.isNotEmpty() && seconds >= LONG_SECONDS) return BatteryVerdict(null,
            warn.joinToString(" and ") { (n, b) -> "the $n phone is at ${b.percent} % and not charging" } +
                "; the copy takes ${formatDuration(seconds)}. The new phone powers the old one over the cable.")
        return BatteryVerdict(null, null)
    }

    /** Characters FAT and MediaStore refuse in a name. */
    private val BAD = Regex("[\\u0000-\\u001f\"*:<>?\\\\|\\u007f]")
    private const val MAX_NAME = 255

    /** A path component the target's MediaStore accepts. */
    fun safeName(name: String): String {
        var n = BAD.replace(name, "_").trimEnd('.', ' ')
        if (n.isEmpty() || n == "." || n == "..") n = "_"
        if (n.toByteArray(Charsets.UTF_8).size <= MAX_NAME) return n
        val dot = n.lastIndexOf('.')
        val ext = if (dot > 0 && n.length - dot <= 16) n.substring(dot) else ""
        var stem = n.substring(0, n.length - ext.length)
        while ((stem + ext).toByteArray(Charsets.UTF_8).size > MAX_NAME) {
            // Drop whole code points so the name stays valid UTF-8.
            stem = stem.substring(0, stem.offsetByCodePoints(stem.length, -1))
        }
        return stem + ext
    }

    /** The relative path a source file gets on the target. */
    fun targetPath(path: String) = path.split('/').joinToString("/") { safeName(it) }

    /** Where unticking happens: the first two folders (`DCIM/Camera`), or the top one for files directly in it. */
    fun folder(path: String): String {
        val parts = path.split('/')
        return if (parts.size <= 2) parts[0] else parts[0] + "/" + parts[1]
    }

    fun describeFolders(sizes: Map<String, Long>) =
        sizes.entries.sortedByDescending { it.value }.joinToString("\n") { "${it.key}: ${formatBytes(it.value)}" }
}
