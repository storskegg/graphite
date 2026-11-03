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

    data class Entry(val sent: String, val state: State, val mediaId: Long)

    private val entries = LinkedHashMap<String, Entry>()

    init {
        if (file.exists()) {
            file.forEachLine { line ->
                val p = line.split(' ')
                when {
                    p.size == 2 && p[0] == "forget" -> entries.remove(p[1])
                    p.size == 3 -> runCatching { Entry(p[0], State.valueOf(p[1]), p[2].toLong()) }
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
    fun published(sent: String, mediaId: Long) = put(Entry(sent, State.PUBLISHED, mediaId))

    fun verified(sent: String) {
        val e = get(sent) ?: return
        put(e.copy(state = State.VERIFIED))
    }

    @Synchronized fun forget(sent: String) {
        if (entries.remove(sent) != null) append("forget $sent")
    }

    @Synchronized private fun put(e: Entry) {
        entries[e.sent] = e
        append("${e.sent} ${e.state} ${e.mediaId}")
    }

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
            for (e in entries.values) out.write("${e.sent} ${e.state} ${e.mediaId}\n".toByteArray())
            out.fd.sync()
        }
        check(tmp.renameTo(file)) { "could not replace $file" }
    }
}
