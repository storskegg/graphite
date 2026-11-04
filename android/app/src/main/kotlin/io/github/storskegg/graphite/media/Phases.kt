package io.github.storskegg.graphite.media

/**
 * Where the copy's time goes: wall time per named phase, summed over files, for the log
 * line at the end of the copy ("copy phases: write 41.2 s, publish 12.0 s, …").
 */
class Phases(private val clock: () -> Long = System::nanoTime) {
    private val nanos = LinkedHashMap<String, Long>()
    private val t0 = clock()

    fun <T> time(phase: String, block: () -> T): T {
        val start = clock()
        try { return block() } finally { nanos.merge(phase, clock() - start, Long::plus) }
    }

    /** Phases by time spent, the rest of the wall time as "other". */
    fun summary(): String {
        val all = clock() - t0
        val rest = all - nanos.values.sum()
        val parts = nanos.entries.sortedByDescending { it.value }.map { "${it.key} ${secs(it.value)}" } +
            if (rest > 0) listOf("other ${secs(rest)}") else emptyList()
        return "${secs(all)} in all: " + parts.joinToString(", ")
    }

    private fun secs(n: Long) = "%.1f s".format(n / 1e9)
}
