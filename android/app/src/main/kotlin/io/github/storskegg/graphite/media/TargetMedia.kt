package io.github.storskegg.graphite.media

import io.github.storskegg.graphite.Controller
import io.github.storskegg.graphite.Flows
import io.github.storskegg.graphite.Paired
import io.github.storskegg.graphite.StoppedException
import io.github.storskegg.graphite.ffi.Manifest
import io.github.storskegg.graphite.ffi.MetadataChoice
import io.github.storskegg.graphite.ffi.NotCopiedReason
import io.github.storskegg.graphite.ffi.Plan
import io.github.storskegg.graphite.ffi.PlanEntry
import io.github.storskegg.graphite.ffi.PlanStatus
import io.github.storskegg.graphite.ffi.Receipt
import io.github.storskegg.graphite.ffi.manifestHash
import io.github.storskegg.graphite.ffi.planHash
import io.github.storskegg.graphite.ffi.planTotals
import io.github.storskegg.graphite.log
import io.github.storskegg.graphite.session.FileEnd
import io.github.storskegg.graphite.session.Msg
import io.github.storskegg.graphite.ui.Choice
import io.github.storskegg.graphite.ui.Progress
import io.github.storskegg.graphite.ui.Screen
import io.github.storskegg.graphite.util.BinReader
import io.github.storskegg.graphite.util.BinWriter
import io.github.storskegg.graphite.util.formatBytes
import io.github.storskegg.graphite.util.formatDuration
import io.github.storskegg.graphite.util.hex
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Phases 1-5 on the new phone: choose, compare, plan, copy, verify, sign the receipt. */
class TargetMedia(private val ctl: Controller, private val p: Paired) {
    private val ch = p.channel
    private val store = TargetStore(ctl.app)
    private val journal = Journal(File(ctl.app.filesDir, "journal.log"))
    private var bytesPerSecond = 0.0

    fun run() {
        Access.requireAllFiles(ctl, "check what this phone already has, including its trash, and write the copies")
        ctl.requireAuth("writes files")
        cleanUpLeftovers()
        measureSpeed()

        // 1. Options, then the manifest.
        val inv = Inventory.decode(Flows.expect(ch, Msg.INVENTORY).body)
        val options = askOptions(inv)
        ch.send(Msg.OPTIONS, options.encode())
        val mm = waitForManifest()
        val manifest = p.session.verifyManifest(mm.signed)
        check(manifest.metadata == options.metadata) { "the manifest has a different metadata choice" }
        val entries = manifest.files
        log("manifest: ${entries.size} entries, ${mm.unremovable.size} can't be stripped")

        // 2. Comparison.
        val targetFiles = store.list()
        val candidates = Comparison.candidates(entries, targetFiles)
        var hashed = 0
        val compared = Comparison.compare(entries, targetFiles, journal.strippedCopies()) { f ->
            if (hashed++ % 20 == 0) ctl.show(Screen("Comparing", "Checking which files this phone already has…",
                progress = Progress(hashed.toLong(), candidates.size.toLong(), "$hashed of ${candidates.size} files with a matching size")))
            store.hash(f)
        }

        // 3. Preflight and the plan.
        val statuses = compared.map { it.status }.toMutableList()
        val planned = preflight(manifest, statuses, mm, inv) ?: return
        val (plan, signedPlan) = planned
        ch.send(Msg.PLAN, signedPlan)
        val answer = Verdict.decode(Flows.expect(ch, Msg.PLAN_ANSWER).body)
        if (!answer.ok) throw IOException("the old phone rejected the plan: ${answer.why}")
        val toCopy = statuses.indices.filter { statuses[it] == PlanStatus.TO_COPY }
        val start = ctl.ask(Screen("Ready to copy", summary(plan, inv) + "\n\nThe old phone shows the same summary.",
            buttons = listOf("Start copy", "Cancel")))
        if (start.button != "Start copy") {
            ch.send(Msg.ERROR, BinWriter().str("the copy was cancelled on the new phone").finish())
            throw StoppedException()
        }
        ctl.requireAuth("writes files")
        ch.send(Msg.START, ByteArray(0))

        // 4. Copy, one file per sent hash, in DATE_ADDED order.
        val primaries = Comparison.primaries(entries, toCopy)
        val primaryOf = toCopy.associateWith { primaries.getValue(entries[it].sent.blake3.hex()) }
        val order = primaries.values.sortedWith(compareBy({ entries[it].dateAddedMs ?: Long.MAX_VALUE }, { it }))
        val outcomes = HashMap<Int, Outcome>()
        val copiedIds = HashMap<Int, Long>()
        copy(entries, order, outcomes, copiedIds)
        ch.send(Msg.COPY_DONE, ByteArray(0))

        // 5. Verification, without the cache.
        val notes = HashMap<Int, String>()
        for ((i, id) in copiedIds) {
            outcomes[i] = verify(i, id, manifest, notes)
            if (outcomes[i] is Outcome.Verified) journal.verified(entries[i].sent.blake3.hex())
        }
        compared.forEachIndexed { i, c ->
            if (statuses[i] == PlanStatus.PRESENT) {
                outcomes[i] = verify(i, c.match!!.id, manifest, notes, check = false, note = c.note, alsoAccept = c.matchHash)
            }
        }
        val receiptEntries = Receipts.build(entries, statuses, outcomes, primaryOf, notes)
        val receipt = Receipt(manifestHash(mm.signed), planHash(signedPlan), receiptEntries)
        val signedReceipt = p.session.signReceipt(receipt, mm.signed, signedPlan)
        ch.send(Msg.RECEIPT, signedReceipt)
        val ra = Verdict.decode(Flows.expect(ch, Msg.RECEIPT_ANSWER).body)
        ch.send(Msg.BYE, ByteArray(0), last = true)
        ch.flush(3000)
        val s = Receipts.summary(receiptEntries)
        val dated = notes.size
        ctl.ask(Screen(if (ra.ok) "Copy finished" else "Copy finished, but the old phone rejected the receipt",
            "$s.\n\n" +
                (if (dated > 0) "$dated files have a note about their dates (see the old phone's receipt).\n" else "") +
                "\"Recently added\" on this phone shows today for the copies: Android sets that date itself.\n\n" +
                (if (ra.ok) "The old phone checked the signed receipt. You can now choose there whether to remove " +
                    "what was verified from the old phone." else "Why: ${ra.why}"),
            buttons = listOf("Done")))
    }

    /** Pending items left by an interrupted run are deleted; published ones stay and count as present. */
    private fun cleanUpLeftovers() {
        val ids = (journal.leftovers().map { it.mediaId } + store.ownPending()).toSet()
        for (id in ids) store.delete(id)
        for (e in journal.leftovers()) journal.forget(e.sent)
        if (ids.isNotEmpty()) log("deleted ${ids.size} half-written items from an earlier run")
    }

    private fun measureSpeed() {
        ctl.show(Screen("Measuring the cable", "Sending test data…"))
        val t0 = System.nanoTime()
        ch.send(Msg.SPEED_TEST, BinWriter().u32(SPEED_BYTES).finish())
        val got = Flows.expect(ch, Msg.SPEED_TEST).body.size
        bytesPerSecond = got / ((System.nanoTime() - t0) / 1e9)
        log("link: %.1f MB/s".format(bytesPerSecond / 1e6))
    }

    private fun askOptions(inv: Inventory): Options {
        val choices = listOf(
            Choice("keep", "Keep all metadata", "Copied byte for byte.", group = "m"),
            Choice("loc", "Remove location and identifiers",
                "Removes where photos and videos were taken, which phone and camera took them (make, model, " +
                    "serial numbers), owner names, software and the embedded thumbnail. Keeps dates, orientation " +
                    "and colours, motion photos, HDR and portrait depth.", group = "m"),
            Choice("all", "Remove all but what display needs",
                "Also removes dates and exposure settings. Galleries then sort these by the file's modification " +
                    "time, which is still copied.", group = "m"),
        ) + if (inv.androidMedia.count > 0) listOf(Choice("am", "Also copy Android/media",
            "${inv.androidMedia.of("files")}: media saved by apps such as messengers. Off unless you tick it.")) else emptyList()
        var note = ""
        while (true) {
            val a = ctl.ask(Screen("What to copy",
                (if (inv.testScope.isNotEmpty()) "TEST BUILD: only folders named \"${inv.testScope}\" are in scope.\n\n" else "") +
                    "The old phone has ${inv.photos.of("photos")}, ${inv.videos.of("videos")}, ${inv.audio.of("audio files")} " +
                    "and ${inv.other.of("other files")} (downloads and documents).\n\n" +
                    "Photos and videos can carry metadata that identifies you, such as where they were taken. " +
                    "The old phone removes what you choose before anything is sent. Documents and other files " +
                    "are copied unchanged." + note,
                choices = choices, buttons = listOf("Continue")))
            val m = when {
                "keep" in a.checked -> MetadataChoice.KEEP_ALL
                "loc" in a.checked -> MetadataChoice.REMOVE_LOCATION_AND_IDENTIFIERS
                "all" in a.checked -> MetadataChoice.REMOVE_ALL_BUT_DISPLAY
                else -> { note = "\n\nChoose what to do with metadata first."; continue }
            }
            return Options(m, "am" in a.checked)
        }
    }

    private fun waitForManifest(): ManifestMsg {
        while (true) {
            val m = ch.receive()
            when (m.type) {
                Msg.PROGRESS -> ProgressMsg.decode(m.body).let {
                    ctl.show(Screen("Reading the old phone", it.text, progress = Progress(it.done, it.total,
                        "${formatBytes(it.done)} of ${formatBytes(it.total)}")))
                }
                Msg.MANIFEST -> return ManifestMsg.decode(m.body)
                Msg.ERROR -> throw io.github.storskegg.graphite.session.PeerAbortException(BinReader(m.body).str())
                else -> throw IOException("unexpected message 0x%02x".format(m.type))
            }
        }
    }

    /** Space, battery, names and the open questions; returns the signed plan, or null if the user stopped. */
    private fun preflight(manifest: Manifest, statuses: MutableList<PlanStatus>, mm: ManifestMsg, inv: Inventory): Pair<Plan, ByteArray>? {
        val entries = manifest.files
        // Files whose metadata can't be removed: one question per kind.
        for ((kind, items) in mm.unremovable.entries.filter { statuses[it.key] == PlanStatus.TO_COPY }.groupBy { it.value.first }) {
            val a = ctl.ask(Screen("Metadata can't be removed",
                "${items.size} ${kind.label} can't have their metadata removed. Copy them as they are " +
                    "(with all their metadata), or skip them?\n\n" +
                    items.take(5).joinToString("\n") { entries[it.key].path } + if (items.size > 5) "\n…" else "",
                buttons = listOf("Skip them", "Copy as they are")))
            if (a.button == "Skip them") for (it in items) statuses[it.key] = PlanStatus.UNTICKED
        }
        // What the user can tick or untick by folder: everything to copy now, before any unticking.
        val offered = entries.indices.filter { statuses[it] == PlanStatus.TO_COPY }
        val folders = offered.map { Preflight.folder(entries[it].path) }.distinct().sorted()
        fun apply(ticked: Set<String>) {
            for (i in offered) statuses[i] = if (Preflight.folder(entries[i].path) in ticked) PlanStatus.TO_COPY else PlanStatus.UNTICKED
        }
        fun ticked() = offered.filter { statuses[it] == PlanStatus.TO_COPY }.map { Preflight.folder(entries[it].path) }.toSet()
        val renamed = entries.indices.filter { statuses[it] == PlanStatus.TO_COPY && Preflight.targetPath(entries[it].path) != entries[it].path }
        while (true) {
            val totals = planTotals(manifest, planEntries(statuses))
            val bytes = totals.bytesToCopy.toLong()
            val allocatable = ctl.testFreeBytes ?: Flows.allocatableBytes(ctl.app)
            if (!Preflight.fits(bytes, allocatable)) {
                val byFolder = sortedMapOf<String, Long>()
                for (i in offered) byFolder.merge(Preflight.folder(entries[i].path), entries[i].sent.size.toLong(), Long::plus)
                val now = ticked()
                val a = ctl.ask(Screen("Not enough space on this phone",
                    "${formatBytes(bytes)} to copy, but only ${formatBytes(allocatable - Preflight.SPARE)} can be used " +
                        "(1 GB is kept free). Untick folders to free ${formatBytes(Preflight.shortfall(bytes, allocatable))}, " +
                        "or free space on this phone and check again.",
                    choices = byFolder.map { (f, n) -> Choice(f, "$f (${formatBytes(n)})", checked = f in now) },
                    buttons = listOf("Check again", "Stop")))
                if (a.button == "Stop") { ch.send(Msg.ERROR, BinWriter().str("not enough space on the new phone").finish()); return null }
                apply(a.checked)
                continue
            }
            val seconds = Preflight.seconds(bytes, bytesPerSecond)
            val verdict = Preflight.battery(Battery(p.peer.batteryPercent, p.peer.charging), Flows.battery(ctl.app), seconds)
            if (verdict.refuse != null) {
                val a = ctl.ask(Screen("Battery too low", verdict.refuse.replaceFirstChar { it.uppercase() },
                    buttons = listOf("Check again", "Stop")))
                if (a.button == "Stop") { ch.send(Msg.ERROR, BinWriter().str("battery too low").finish()); return null }
                continue
            }
            val warn = verdict.warn?.let { "\n\nBattery: $it" }.orEmpty()
            val names = if (renamed.isEmpty()) "" else "\n\n${renamed.size} names aren't allowed on this phone and get a safe name:\n" +
                renamed.take(5).joinToString("\n") { "${entries[it].path} → ${Preflight.targetPath(entries[it].path)}" }
            val plan = Plan(ByteArray(0), planEntries(statuses), totals)
            val now = ticked()
            val a = ctl.ask(Screen("The plan", summary(plan, inv) + warn + names +
                "\n\nUntick folders you don't want copied, then continue.",
                choices = folders.map { Choice(it, it, checked = it in now) },
                buttons = listOf("Continue", "Stop")))
            if (a.button == "Stop") { ch.send(Msg.ERROR, BinWriter().str("stopped on the new phone").finish()); return null }
            // A changed selection is shown again with new totals before anything is signed.
            if (a.checked != now) { apply(a.checked); continue }
            val signedManifest = mm.signed
            val final = Plan(manifestHash(signedManifest), planEntries(statuses), planTotals(manifest, planEntries(statuses)))
            return final to p.session.signPlan(final, signedManifest)
        }
    }

    private fun planEntries(statuses: List<PlanStatus>) = statuses.mapIndexed { i, s -> PlanEntry(i.toUInt(), s) }

    private fun summary(plan: Plan, inv: Inventory): String {
        val t = plan.totals
        val seconds = Preflight.seconds(t.bytesToCopy.toLong(), bytesPerSecond)
        return "${t.toCopy} to copy (${formatBytes(t.bytesToCopy.toLong())}), ${t.present} already on the new phone, " +
            "${t.presentInTrash} in the new phone's trash (not copied), ${t.unticked} unticked. " +
            (if (t.toCopy > 0u) "Copying takes ${formatDuration(seconds)} at %.0f MB/s.".format(bytesPerSecond / 1e6) else "Nothing to copy.") +
            if (inv.testScope.isNotEmpty()) "\nTEST BUILD: only folders named \"${inv.testScope}\"." else ""
    }

    private fun copy(entries: List<io.github.storskegg.graphite.ffi.ManifestEntry>, order: List<Int>, outcomes: MutableMap<Int, Outcome>, copiedIds: MutableMap<Int, Long>) {
        val total = order.sumOf { entries[it].sent.size.toLong() }
        var done = 0L
        val meter = Meter(ctl, "Copying", total, order.size)
        var stopRest = false
        for ((n, i) in order.withIndex()) {
            if (stopRest) { outcomes[i] = Outcome.NotCopied(NotCopiedReason.DID_NOT_FIT); continue }
            val e = entries[i]
            var attempt = 0
            while (true) {
                attempt++
                meter.update(done, n + 1, e.path, force = true)
                val before = done
                val r = try { receiveOne(i, e) { got -> meter.update(before + got, n + 1, e.path) } } catch (x: OutOfSpace) {
                    val a = ctl.ask(Screen("This phone is full", "Another app used the space while copying. Nothing " +
                        "half-written is kept. Free some space and retry, or stop copying (the rest is reported as did not fit).",
                        buttons = listOf("Retry", "Stop copying")))
                    if (a.button == "Retry") { attempt--; continue }
                    stopRest = true
                    Outcome.NotCopied(NotCopiedReason.DID_NOT_FIT) to null
                }
                val (o, id) = r
                if (o is Outcome.NotCopied && o.reason == NotCopiedReason.FAILED && attempt < 2) { log("retrying ${e.path}: ${o.note}"); continue }
                outcomes[i] = o
                if (id != null) copiedIds[i] = id
                break
            }
            done += e.sent.size.toLong()
        }
    }

    private class OutOfSpace : Exception()

    /** Requests one file and writes it as a pending item; publishes it when the hash matches. */
    private fun receiveOne(i: Int, e: io.github.storskegg.graphite.ffi.ManifestEntry, onBytes: (Long) -> Unit): Pair<Outcome, Long?> {
        val sentHex = e.sent.blake3.hex()
        ch.send(Msg.REQUEST, BinWriter().u32(i).finish())
        var m = ch.receive()
        if (m.type == Msg.FILE_END) return endWithoutData(m)
        check(m.type == Msg.FILE_BEGIN) { "expected FILE_BEGIN, got 0x%02x".format(m.type) }
        val id = try { store.insertPending(Preflight.targetPath(e.path), e.mimeType) } catch (x: Exception) {
            drain()
            return Outcome.NotCopied(NotCopiedReason.FAILED, "this phone's MediaStore refused it: ${x.message}") to null
        }
        journal.pending(sentHex, id)
        var full = false
        var got = 0L
        try {
            store.openForWrite(id).use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { out ->
                    while (true) {
                        m = ch.receive()
                        if (m.type != Msg.FILE_DATA) break
                        if (!full) try { out.write(m.body, 4, m.body.size - 4) } catch (x: IOException) {
                            if (x.message?.contains("ENOSPC") == true || x.message?.contains("No space") == true) full = true else throw x
                        }
                        got += m.body.size - 4
                        onBytes(got)
                    }
                    if (!full) TargetStore.sync(out)
                }
            }
        } catch (x: IOException) {
            if (m.type != Msg.FILE_END) drain()
            discard(sentHex, id)
            return Outcome.NotCopied(NotCopiedReason.FAILED, "write failed: ${x.message}") to null
        }
        check(m.type == Msg.FILE_END) { "expected FILE_END, got 0x%02x".format(m.type) }
        if (full) { discard(sentHex, id); throw OutOfSpace() }
        val status = BinReader(m.body).run { u32(); u8() }
        if (status != FileEnd.OK) { discard(sentHex, id); return endOutcome(status) to null }
        // Read back what was written, not what was received.
        val written = store.hashUncached(id)
        if (got != e.sent.size.toLong() || written != sentHex) {
            discard(sentHex, id)
            return Outcome.NotCopied(NotCopiedReason.FAILED, "hash did not match after writing") to null
        }
        val touched = store.publish(id, e.modifiedMs)
        journal.published(sentHex, id, e.original.blake3.hex())
        return Outcome.Verified(if (touched) "" else "modification time not restored") to id
    }

    private fun endWithoutData(m: io.github.storskegg.graphite.session.Message): Pair<Outcome, Long?> =
        endOutcome(BinReader(m.body).run { u32(); u8() }) to null

    private fun endOutcome(status: Int) = when (status) {
        FileEnd.CHANGED -> Outcome.NotCopied(NotCopiedReason.CHANGED_ON_SOURCE)
        else -> Outcome.NotCopied(NotCopiedReason.FAILED, "the old phone could not read it")
    }

    /** Skips the rest of a file's messages after a local failure. */
    private fun drain() {
        while (ch.receive().type != Msg.FILE_END) Unit
    }

    private fun discard(sentHex: String, id: Long) {
        store.delete(id)
        journal.forget(sentHex)
    }

    /** Re-reads an item through MediaStore and checks it is published, not trashed, with the right bytes and dates. */
    private fun verify(i: Int, id: Long, manifest: Manifest, notes: MutableMap<Int, String>, check: Boolean = true, note: String = "", alsoAccept: String? = null): Outcome {
        val e = manifest.files[i]
        val row = store.row(id) ?: return Outcome.NotCopied(NotCopiedReason.FAILED, "gone from the new phone during the transfer")
        if (row.pending || row.trashed) return Outcome.NotCopied(NotCopiedReason.FAILED, if (row.trashed) "moved to the new phone's trash during the transfer" else "still pending")
        val hex = store.hashUncached(id)
        val expected = setOfNotNull(e.sent.blake3.hex(), e.original.blake3.hex(), alsoAccept)
        if (hex !in expected) return Outcome.NotCopied(NotCopiedReason.FAILED, "changed on the new phone during the transfer")
        val dateNotes = mutableListOf<String>()
        if (check) {
            val mtime = row.path?.let { File(it).lastModified() }
            if (mtime != null && mtime / 1000 != e.modifiedMs / 1000) dateNotes += "modification time ${mtime / 1000} s, not ${e.modifiedMs / 1000} s"
            val wantTaken = e.dateTakenMs
            if (manifest.metadata != MetadataChoice.REMOVE_ALL_BUT_DISPLAY && wantTaken != null && row.dateTakenMs != wantTaken) {
                dateNotes += "date taken ${row.dateTakenMs ?: "none"}, not $wantTaken"
            }
        }
        if (dateNotes.isNotEmpty()) notes[i] = dateNotes.joinToString("; ")
        return Outcome.Verified(note)
    }

    companion object {
        const val SPEED_BYTES = 32 shl 20
    }
}
