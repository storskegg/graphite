package io.github.storskegg.graphite.records

import android.net.Uri
import io.github.storskegg.graphite.Controller
import io.github.storskegg.graphite.ffi.FileDigest
import io.github.storskegg.graphite.ffi.RecordEntry
import io.github.storskegg.graphite.ffi.blake3Hash
import io.github.storskegg.graphite.ffi.recordIdentity
import io.github.storskegg.graphite.log

/** How many records of each category the source can read, for the first screen; -1 when it can't tell yet. */
data class RecordCounts(val messages: Long = 0, val calls: Long = 0, val contacts: Long = 0, val blocked: Long = -1) {
    fun of(c: Category) = when (c) {
        Category.MESSAGES -> messages
        Category.CALLS -> calls
        Category.CONTACTS -> contacts
        Category.BLOCKED -> blocked
    }
}

/** A record in the manifest and where it came from. MMS payloads are read again when sent; the rest are kept. */
class Listed(val entry: RecordEntry, val kind: Kind, val uri: Uri, val payload: ByteArray?)

/** Step 2 on the old phone: list and identify records, and send their payloads (docs/records.md). */
class SourceRecords(private val ctl: Controller) {
    private val providers = Providers(ctl.app)
    private val identity = Identity { blake3Hash(it) }
    private val test = ctl.testScope != null

    /** What can be read now; categories without permission count 0. */
    fun counts(readable: Set<Category>): RecordCounts {
        fun n(c: Category, kinds: List<Kind>) = if (c !in readable) 0L else kinds.sumOf { k ->
            if (test) listed(k).size.toLong() else count(k)
        }
        return RecordCounts(n(Category.MESSAGES, listOf(Kind.SMS, Kind.MMS)), n(Category.CALLS, listOf(Kind.CALL)),
            n(Category.CONTACTS, listOf(Kind.CONTACT)), -1)
    }

    /**
     * The manifest's records for [cats]: contacts, calls, blocked numbers, then messages by date.
     * Blocked numbers need the SMS role here too, held only for the read.
     */
    fun list(cats: Set<Category>, onProgress: (String) -> Unit): List<Listed> {
        val out = ArrayList<Listed>()
        for (kind in listOf(Kind.CONTACT, Kind.CALL, Kind.BLOCKED, Kind.SMS, Kind.MMS)) {
            if (kind.category !in cats) continue
            onProgress("Reading ${kind.folder.lowercase()}")
            val found = if (kind == Kind.BLOCKED) blocked() else listed(kind)
            for (f in found) {
                val payload = Payload.encode(f.rows)
                out += Listed(entry(kind, f.rows, payload), kind, f.uri, payload.takeIf { kind != Kind.MMS })
            }
        }
        val (messages, rest) = out.partition { it.kind.category == Category.MESSAGES }
        log("records: ${out.groupingBy { it.kind }.eachCount()}")
        return rest + messages.sortedBy { it.entry.dateMs ?: Long.MAX_VALUE }
    }

    /** The payload to send, or null if the record changed or went since the manifest. */
    fun payload(l: Listed): ByteArray? {
        val p = l.payload ?: providers.read(l.kind, l.uri)?.let { Payload.encode(it.rows) } ?: return null
        return p.takeIf { it.size.toLong() == l.entry.sent.size.toLong() && blake3Hash(it).contentEquals(l.entry.sent.blake3) }
    }

    private fun entry(kind: Kind, rows: List<Row>, payload: ByteArray) = RecordEntry(
        kind = kind.ffi, label = identity.label(kind, rows), dateMs = identity.date(kind, rows),
        identity = recordIdentity(kind.ffi, identity.fields(kind, rows)),
        sent = FileDigest(payload.size.toULong(), blake3Hash(payload)),
    )

    private fun listed(kind: Kind): List<Found> {
        val all = if (kind == Kind.MMS) providers.mmsDates().keys.mapNotNull { providers.read(kind, it) } else providers.list(kind)
        return if (test) all.filter { identity.inTestScope(kind, it.rows) } else all
    }

    private fun count(kind: Kind): Long = runCatching {
        ctl.app.contentResolver.query(Providers.uri(kind, 0).let { it.buildUpon().path(it.path!!.substringBeforeLast('/')).build() },
            arrayOf("_id"), null, null, null)?.use { it.count.toLong() } ?: 0L
    }.getOrDefault(0L)

    /** Blocked numbers: only the default SMS app (or the system) may read them. */
    private fun blocked(): List<Found> {
        val window = RoleWindow(ctl)
        if (!window.take("read your blocked numbers")) { log("blocked numbers skipped: no SMS role"); return emptyList() }
        try {
            return listed(Kind.BLOCKED)
        } finally {
            window.giveBack()
        }
    }
}
