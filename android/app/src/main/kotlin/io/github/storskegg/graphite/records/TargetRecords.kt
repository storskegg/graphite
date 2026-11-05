package io.github.storskegg.graphite.records

import android.content.Context
import android.net.Uri
import io.github.storskegg.graphite.Controller
import io.github.storskegg.graphite.ffi.Manifest
import io.github.storskegg.graphite.ffi.NotCopiedReason
import io.github.storskegg.graphite.ffi.PlanStatus
import io.github.storskegg.graphite.ffi.blake3Hash
import io.github.storskegg.graphite.ffi.recordIdentity
import io.github.storskegg.graphite.log
import io.github.storskegg.graphite.media.Meter
import io.github.storskegg.graphite.media.Outcome
import io.github.storskegg.graphite.media.Tile
import io.github.storskegg.graphite.media.TileMap
import io.github.storskegg.graphite.session.Channel
import io.github.storskegg.graphite.session.FileEnd
import io.github.storskegg.graphite.session.Msg
import io.github.storskegg.graphite.session.PeerAbortException
import io.github.storskegg.graphite.util.BinReader
import io.github.storskegg.graphite.util.BinWriter
import io.github.storskegg.graphite.util.hex
import java.io.ByteArrayOutputStream

/**
 * Step 2 on the new phone: compare, write and verify the manifest's records (docs/records.md).
 * Records are numbered after the files: record `k` is manifest entry `files.size + k`. They are
 * copied and verified before the files, so the SMS role is held only from the comparison to here.
 */
class TargetRecords(private val ctl: Controller, private val ch: Channel, manifest: Manifest) {
    private val first = manifest.files.size
    private val records = manifest.records
    private val providers = Providers(ctl.app)
    private val identity = Identity { blake3Hash(it) }
    private val window = RoleWindow(ctl)
    private val prefs = ctl.app.getSharedPreferences("records", Context.MODE_PRIVATE)
    /** Where each present or copied record is on this phone, by manifest index. */
    private val where = HashMap<Int, Uri>()
    private var holding = false

    init { providers.onMmsStarted = { prefs.edit().putString("mms_uri", it.toString()).commit() } }

    private fun kind(i: Int) = Kind.of(records[i - first].kind)
    private fun idHex(i: Int) = records[i - first].identity.hex()

    val indices get() = first until first + records.size
    private fun needsRole(i: Int) = kind(i).let { it == Kind.SMS || it == Kind.MMS || it == Kind.BLOCKED }

    /**
     * Phase 2 for the records: [statuses] gets *present* or *to copy* for each, or *unticked* for a
     * category this phone can't write (no permission, or the user kept the SMS role).
     */
    fun compare(statuses: MutableList<PlanStatus>) {
        cleanUpLeftover()
        if (records.isEmpty()) return
        val cats = indices.map { kind(it).category }.toSet()
        val usable = RecordAccess.request(ctl, cats, RecordAccess::write).toMutableSet()
        if (indices.any(::needsRole)) {
            holding = window.take("read and write messages and blocked numbers")
            if (!holding) { usable -= Category.MESSAGES; usable -= Category.BLOCKED }
        }
        for ((kind, idx) in indices.groupBy(::kind)) {
            if (kind.category !in usable) {
                log("records: ${idx.size} ${kind.folder.lowercase()} can't be written here: unticked")
                idx.forEach { statuses[it] = PlanStatus.UNTICKED }
                continue
            }
            val have = held(kind, idx)
            for (i in idx) {
                val hit = have[idHex(i)]
                if (hit != null) { statuses[i] = PlanStatus.PRESENT; where[i] = hit } else statuses[i] = PlanStatus.TO_COPY
            }
        }
    }

    /** This phone's records of [kind] by identity. MMS are read part by part only where a date matches. */
    private fun held(kind: Kind, idx: List<Int>): Map<String, Uri> {
        val found = when (kind) {
            Kind.MMS -> {
                val dates = idx.mapNotNull { records[it - first].dateMs?.div(1000) }.toSet()
                providers.mmsDates().filterValues { it in dates }.keys.mapNotNull { providers.read(kind, it) }
            }
            Kind.CONTACT -> providers.list(kind) + providers.rawContacts()
            else -> providers.list(kind)
        }
        val out = HashMap<String, Uri>()
        for (f in found) out.putIfAbsent(idOf(kind, f.rows), f.uri)
        return out
    }

    private fun idOf(kind: Kind, rows: List<Row>) = recordIdentity(kind.ffi, identity.fields(kind, rows)).hex()

    /**
     * Phases 4 and 5 for the records: requests each one to copy (several at a time: they are
     * small), writes it, then reads back everything the receipt will vouch for; gives the SMS
     * role back at the end.
     */
    fun copyAndVerify(statuses: List<PlanStatus>, primaryOf: Map<Int, Int>, outcomes: MutableMap<Int, Outcome>,
                      notes: MutableMap<Int, String>, tiles: TileMap) {
        try {
            val rank = listOf(Kind.CONTACT, Kind.CALL, Kind.BLOCKED, Kind.SMS, Kind.MMS)
            // [primaryOf] covers the files too; only the records are copied here.
            val order = primaryOf.filterKeys { it in indices }.values.distinct().sortedWith(compareBy({ rank.indexOf(kind(it)) },
                { if (kind(it).category == Category.MESSAGES) records[it - first].dateMs ?: Long.MAX_VALUE else 0L }, { it }))
            if (order.isNotEmpty()) {
                val meter = Meter(ctl, "Copying messages, calls and contacts", order.sumOf { records[it - first].sent.size.toLong() }, order.size, tiles)
                var failed = copy(order, outcomes, notes, tiles, meter, 0)
                if (failed.isNotEmpty()) { log("retrying ${failed.size} records"); failed = copy(failed, outcomes, notes, tiles, meter, order.size - failed.size) }
            }
            val toVerify = indices.filter { statuses[it] == PlanStatus.PRESENT || where.containsKey(it) && outcomes[it] is Outcome.Verified }
            val meter = Meter(ctl, "Verifying messages, calls and contacts", toVerify.size.toLong(), toVerify.size, tiles)
            for ((n, i) in toVerify.withIndex()) {
                tiles.set(i, Tile.ACTIVE)
                meter.update(n.toLong(), n + 1, records[i - first].label)
                val o = verify(i)
                if (statuses[i] == PlanStatus.PRESENT || o is Outcome.NotCopied) outcomes[i] = o
                tiles.set(i, if (o is Outcome.Verified) Tile.DONE else Tile.FAILED)
            }
            if (toVerify.isNotEmpty()) meter.update(toVerify.size.toLong(), toVerify.size, "done", force = true)
            log("records: ${outcomes.count { it.key in indices && it.value is Outcome.Verified }} verified, " +
                "${outcomes.count { it.key in indices && it.value is Outcome.NotCopied }} not")
        } finally {
            if (holding) { window.giveBack(); holding = false }
        }
    }

    /** Gives the SMS role back if the transfer stops before the copy (the user cancelled). */
    fun release() { if (holding) { window.giveBack(); holding = false } }

    /** Copies [order]; the ones that failed, for a second try. */
    private fun copy(order: List<Int>, outcomes: MutableMap<Int, Outcome>, notes: MutableMap<Int, String>, tiles: TileMap,
                     meter: Meter, startAt: Int): List<Int> {
        val failed = ArrayList<Int>()
        val requested = ArrayDeque<Int>()
        var next = 0
        var done = 0L
        var n = startAt
        while (next < order.size || requested.isNotEmpty()) {
            while (requested.size < WINDOW && next < order.size) {
                val i = order[next++]
                ch.send(Msg.REQUEST, BinWriter().u32(i).finish())
                requested.addLast(i)
            }
            val i = requested.removeFirst()
            val r = records[i - first]
            tiles.set(i, Tile.ACTIVE)
            meter.update(done, ++n, r.label)
            val o = receiveOne(i, notes)
            done += r.sent.size.toLong()
            if (o is Outcome.NotCopied && o.reason == NotCopiedReason.FAILED && startAt == 0) {
                log("record ${r.label}: ${o.note}; again later")
                tiles.set(i, Tile.PENDING)
                failed += i
                continue
            }
            outcomes[i] = o
            tiles.set(i, when {
                o is Outcome.Verified -> Tile.DONE
                o is Outcome.NotCopied && o.reason == NotCopiedReason.FAILED -> Tile.FAILED
                else -> Tile.SKIPPED
            })
        }
        return failed
    }

    /** One record's payload, checked against the manifest, then written. */
    private fun receiveOne(i: Int, notes: MutableMap<Int, String>): Outcome {
        val r = records[i - first]
        var m = ch.receive()
        if (m.type == Msg.ERROR) throw PeerAbortException(BinReader(m.body).str())
        check(BinReader(m.body).u32().toInt() == i) { "the old phone answered for another entry" }
        if (m.type == Msg.FILE_BEGIN) {
            val buf = ByteArrayOutputStream()
            while (true) {
                m = ch.receive()
                if (m.type != Msg.FILE_DATA) break
                buf.write(m.body, 4, m.body.size - 4)
            }
            check(m.type == Msg.FILE_END) { "expected FILE_END, got 0x%02x".format(m.type) }
            if (BinReader(m.body).run { u32(); u8() } == FileEnd.OK) return write(i, buf.toByteArray(), notes)
        }
        check(m.type == Msg.FILE_END) { "expected FILE_BEGIN or FILE_END, got 0x%02x".format(m.type) }
        return when (BinReader(m.body).run { u32(); u8() }) {
            FileEnd.CHANGED -> Outcome.NotCopied(NotCopiedReason.CHANGED_ON_SOURCE)
            else -> Outcome.NotCopied(NotCopiedReason.FAILED, "the old phone could not read it")
        }
    }

    private fun write(i: Int, payload: ByteArray, notes: MutableMap<Int, String>): Outcome {
        val r = records[i - first]
        if (payload.size.toLong() != r.sent.size.toLong() || !blake3Hash(payload).contentEquals(r.sent.blake3)) {
            return Outcome.NotCopied(NotCopiedReason.FAILED, "the payload did not match the manifest")
        }
        val rows = runCatching { Payload.decode(payload) }.getOrElse { return Outcome.NotCopied(NotCopiedReason.FAILED, "unreadable payload: ${it.message}") }
        val kind = kind(i)
        // Its identity must be the manifest's, or the source described one record and sent another.
        if (idOf(kind, rows) != idHex(i)) return Outcome.NotCopied(NotCopiedReason.FAILED, "the payload is not the record the manifest names")
        return try {
            // An MMS is several inserts: noted until whole, so a crash's half is deleted next time.
            if (kind == Kind.MMS) prefs.edit().putBoolean("writing_mms", true).commit()
            val (uri, dropped) = providers.write(kind, rows)
            if (kind == Kind.MMS) prefs.edit().remove("writing_mms").remove("mms_uri").apply()
            where[i] = uri
            if (dropped.isNotEmpty()) notes[i] = "not stored on this phone: ${dropped.joinToString()}"
            Outcome.Verified()
        } catch (x: Exception) {
            log("record ${r.label}: write failed: $x")
            Outcome.NotCopied(NotCopiedReason.FAILED, "this phone refused it: ${x.message}")
        }
    }

    /** Reads the record back from its provider and recomputes its identity. */
    private fun verify(i: Int): Outcome {
        val uri = where[i] ?: return Outcome.NotCopied(NotCopiedReason.FAILED, "not found on the new phone")
        val f = runCatching { providers.read(kind(i), uri) }.getOrNull()
            ?: return Outcome.NotCopied(NotCopiedReason.FAILED, "gone from the new phone during the transfer")
        return if (idOf(kind(i), f.rows) == idHex(i)) Outcome.Verified()
        else Outcome.NotCopied(NotCopiedReason.FAILED, "reads back differently on the new phone")
    }

    /** An MMS a crash left half-written is deleted before comparing; the comparison then finds it missing. */
    private fun cleanUpLeftover() {
        if (!prefs.getBoolean("writing_mms", false)) return
        prefs.getString("mms_uri", null)?.let { u ->
            runCatching { providers.delete(Uri.parse(u)) }
            log("deleted an MMS left half-written when graphite stopped: $u")
        }
        prefs.edit().clear().apply()
    }

    companion object {
        /** Requests in flight at once: records are small, so the round trips overlap. */
        const val WINDOW = 16
    }
}
