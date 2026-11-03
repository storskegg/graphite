package io.github.storskegg.graphite.media

import java.io.File
import java.io.FileOutputStream

/**
 * The target's record of what it wrote, keyed by sent hash (hex): pending,
 * published, verified, with the MediaStore ID. After an interruption the
 * pending entries are the half-written items to delete before the next run.
 *
 * Stored as an append-only log so each change is one short write; it is
 * compacted when opened.
 */
class Journal(private val file: File) {
    enum class State { PENDING, PUBLISHED, VERIFIED }

    /** [original] is the source file's hash when graphite stripped it (sent differs); empty otherwise. */
    data class Entry(val sent: String, val state: State, val mediaId: Long, val original: String = "")

    private val entries = LinkedHashMap<String, Entry>()

    init {
        if (file.exists()) {
            file.forEachLine { line ->
                val p = line.split(' ')
                when {
                    p.size == 2 && p[0] == "forget" -> entries.remove(p[1])
                    p.size == 3 || p.size == 4 -> runCatching { Entry(p[0], State.valueOf(p[1]), p[2].toLong(), p.getOrElse(3) { "" }) }
                        .onSuccess { entries[it.sent] = it }
                    // A line cut short by a crash is ignored.
                }
            }
        }
        compact()
    }

    @Synchronized fun get(sent: String) = entries[sent]
    @Synchronized fun all() = entries.values.toList()
    @Synchronized fun leftovers() = entries.values.filter { it.state == State.PENDING }

    fun pending(sent: String, mediaId: Long) = put(Entry(sent, State.PENDING, mediaId))
    fun published(sent: String, mediaId: Long, original: String = "") =
        put(Entry(sent, State.PUBLISHED, mediaId, if (original == sent) "" else original))

    /** Copies graphite stripped, by the hash of the original they came from: original → (sent hash, MediaStore ID). */
    @Synchronized fun strippedCopies(): Map<String, Pair<String, Long>> = entries.values
        .filter { it.original.isNotEmpty() && it.state != State.PENDING }
        .associate { it.original to (it.sent to it.mediaId) }

    fun verified(sent: String) {
        val e = get(sent) ?: return
        put(e.copy(state = State.VERIFIED))
    }

    @Synchronized fun forget(sent: String) {
        if (entries.remove(sent) != null) append("forget $sent")
    }

    @Synchronized private fun put(e: Entry) {
        entries[e.sent] = e
        append(line(e))
    }

    private fun line(e: Entry) = "${e.sent} ${e.state} ${e.mediaId}" + if (e.original.isEmpty()) "" else " ${e.original}"

    private fun append(line: String) {
        FileOutputStream(file, true).use { out ->
            out.write("$line\n".toByteArray())
            out.fd.sync()
        }
    }

    @Synchronized private fun compact() {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            for (e in entries.values) out.write("${line(e)}\n".toByteArray())
            out.fd.sync()
        }
        check(tmp.renameTo(file)) { "could not replace $file" }
    }
}
