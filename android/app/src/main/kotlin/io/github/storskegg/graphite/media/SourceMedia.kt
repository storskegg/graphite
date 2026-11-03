package io.github.storskegg.graphite.media

import android.provider.MediaStore
import io.github.storskegg.graphite.Controller
import io.github.storskegg.graphite.Flows
import io.github.storskegg.graphite.MainActivity
import io.github.storskegg.graphite.Paired
import io.github.storskegg.graphite.ffi.FileDigest
import io.github.storskegg.graphite.ffi.Manifest
import io.github.storskegg.graphite.ffi.ManifestEntry
import io.github.storskegg.graphite.ffi.MetadataChoice
import io.github.storskegg.graphite.ffi.ReceiptStatus
import io.github.storskegg.graphite.ffi.StripException
import io.github.storskegg.graphite.ffi.StrippedFile
import io.github.storskegg.graphite.ffi.openFile
import io.github.storskegg.graphite.ffi.planCheckRequest
import io.github.storskegg.graphite.ffi.stripFile
import io.github.storskegg.graphite.ffi.stripperVersion
import io.github.storskegg.graphite.log
import io.github.storskegg.graphite.session.FileEnd
import io.github.storskegg.graphite.session.Msg
import io.github.storskegg.graphite.ui.Choice
import io.github.storskegg.graphite.ui.Progress
import io.github.storskegg.graphite.ui.Screen
import io.github.storskegg.graphite.util.BinReader
import io.github.storskegg.graphite.util.BinWriter
import io.github.storskegg.graphite.util.formatBytes
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Phases 1-6 on the old phone: list and hash, answer requests, check the receipt, optionally remove. */
class SourceMedia(private val ctl: Controller, private val p: Paired) {
    private val ch = p.channel

    fun run() {
        Access.requireAllFiles(ctl, "read your photos, videos, downloads and documents")
        ctl.requireAuth("reads your files")
        answerSpeedTest()

        // 1. Inventory, the user's choices, then the manifest.
        val testScope = ctl.testScope
        ctl.show(Screen("Looking at your files", "Listing what is in scope…"))
        val all = SourceLibrary.list(ctl.app, testScope)
        ch.send(Msg.INVENTORY, SourceLibrary.inventory(all, testScope).encode())
        ctl.show(Screen("Choices on the new phone", "On the new phone, choose what to do with metadata, then continue there."))
        val options = Options.decode(Flows.expect(ch, Msg.OPTIONS).body)
        val (manifest, unremovable, files) = buildManifest(all.filter { options.androidMedia || !it.androidMedia }, options.metadata)
        val signedManifest = p.session.signManifest(manifest)
        ch.send(Msg.MANIFEST, ManifestMsg(signedManifest, unremovable).encode())

        // 3. The plan, checked against this manifest.
        ctl.show(Screen("Waiting for the plan", "The new phone is checking what it already has."))
        val signedPlan = Flows.expect(ch, Msg.PLAN).body
        val plan = try {
            p.session.verifyPlan(signedPlan, signedManifest)
        } catch (e: io.github.storskegg.graphite.ffi.CoreException) {
            ch.send(Msg.PLAN_ANSWER, Verdict(false, "plan rejected: ${e.message}").encode())
            throw IOException("the new phone's plan did not check out: ${e.message}")
        }
        ch.send(Msg.PLAN_ANSWER, Verdict(true).encode())
        val t = plan.totals
        ctl.show(Screen("Ready to copy", "${t.toCopy} to copy (${formatBytes(t.bytesToCopy.toLong())}), ${t.present} " +
            "already on the new phone, ${t.presentInTrash} in the new phone's trash (not copied), ${t.unticked} unticked." +
            "\n\nStart the copy on the new phone."))
        Flows.expect(ch, Msg.START)

        // 4. Answer requests until the target is done.
        var sent = 0L
        var count = 0
        while (true) {
            val m = ch.receive()
            when (m.type) {
                Msg.REQUEST -> {
                    val i = BinReader(m.body).u32().toInt()
                    planCheckRequest(plan, i.toUInt())
                    val e = manifest.files[i]
                    ctl.show(Screen("Sending", "${++count} of ${t.toCopy}: ${e.path}", progress = Progress(sent, t.bytesToCopy.toLong(),
                        "${formatBytes(sent)} of ${formatBytes(t.bytesToCopy.toLong())}")))
                    sendFile(i, files[i], e, manifest.metadata, i in unremovable)
                    sent += e.sent.size.toLong()
                }
                Msg.COPY_DONE -> break
                Msg.ERROR -> throw io.github.storskegg.graphite.session.PeerAbortException(BinReader(m.body).str())
                else -> throw IOException("unexpected message 0x%02x".format(m.type))
            }
        }

        // 5. The receipt.
        ctl.show(Screen("Verifying", "The new phone is reading back every file it vouches for."))
        val signedReceipt = Flows.expect(ch, Msg.RECEIPT).body
        val receipt = try {
            p.session.verifyReceipt(signedReceipt, signedManifest, signedPlan)
        } catch (e: io.github.storskegg.graphite.ffi.CoreException) {
            ch.send(Msg.RECEIPT_ANSWER, Verdict(false, "receipt rejected: ${e.message}").encode())
            throw IOException("the new phone's receipt did not check out: ${e.message}")
        }
        ch.send(Msg.RECEIPT_ANSWER, Verdict(true).encode())
        Flows.expect(ch, Msg.BYE)
        val summary = Receipts.summary(receipt.entries)
        val notes = receipt.entries.filter { it.note.isNotEmpty() }
        val noteText = if (notes.isEmpty()) "" else "\n\nNotes:\n" + notes.take(20).joinToString("\n") {
            "${manifest.files[it.index.toInt()].path}: ${it.note}"
        } + if (notes.size > 20) "\n… and ${notes.size - 20} more" else ""
        log("receipt verified: $summary")
        notes.forEach { log("receipt note: ${manifest.files[it.index.toInt()].path}: ${it.note}") }

        // 6. Removal, only if the user asks.
        val removable = receipt.entries.filter { it.status == ReceiptStatus.CopiedVerified || it.status == ReceiptStatus.PresentVerified }
            .map { it.index.toInt() }
        val a = ctl.ask(Screen("Copy verified", "The new phone signed a receipt and this phone checked it.\n\n$summary.$noteText" +
            if (removable.isEmpty()) "" else "\n\nYou can now move what was verified to this phone's trash (kept for 30 days). Nothing is preselected.",
            buttons = if (removable.isEmpty()) listOf("Done") else listOf("Done", "Remove from this phone…")))
        if (a.button != "Done") Removal(ctl).run(files, manifest, removable)
        else ctl.ask(Screen("Done", "Nothing was removed from this phone.", buttons = emptyList()))
    }

    private fun answerSpeedTest() {
        val n = BinReader(Flows.expect(ch, Msg.SPEED_TEST).body).u32().toInt().coerceIn(0, 64 shl 20)
        ch.send(Msg.SPEED_TEST, ByteArray(n))
    }

    /** Opens the file as it will be sent: stripped when chosen, unchanged when it can't be or isn't media. */
    private fun open(f: SourceFile, choice: MetadataChoice, asIs: Boolean): StrippedFile =
        if (asIs || choice == MetadataChoice.KEEP_ALL) openFile(f.file.path) else stripFile(f.file.path, f.mime, choice)

    /** The manifest, the entries that can't be stripped, and the files by manifest index (unreadable ones are left out). */
    private fun buildManifest(files: List<SourceFile>, choice: MetadataChoice): Triple<Manifest, Map<Int, Pair<Unremovable, String>>, List<SourceFile>> {
        val total = files.sumOf { it.size }
        var done = 0L
        var lastShown = 0L
        val entries = ArrayList<ManifestEntry>()
        val kept = ArrayList<SourceFile>()
        val unremovable = HashMap<Int, Pair<Unremovable, String>>()
        var unreadable = 0
        for (f in files) {
            val now = System.nanoTime()
            if (now - lastShown > TimeUnit.MILLISECONDS.toNanos(500)) {
                lastShown = now
                val text = "Reading and hashing files on the old phone" +
                    if (choice != MetadataChoice.KEEP_ALL) " and removing metadata in memory" else ""
                ch.send(Msg.PROGRESS, ProgressMsg(text, done, total).encode())
                ctl.show(Screen("Preparing the manifest", text, progress = Progress(done, total, "${formatBytes(done)} of ${formatBytes(total)}")))
            }
            done += f.size
            val (digests, why) = try {
                open(f, choice, false).use { (it.original() to it.sent()) to null }
            } catch (e: StripException) {
                val kind = when (e) {
                    is StripException.Raw -> Unremovable.RAW
                    is StripException.Malformed -> Unremovable.MALFORMED
                    is StripException.Unsupported -> Unremovable.UNSUPPORTED
                    is StripException.Io -> null
                }
                if (kind == null) { unreadable++; log("unreadable ${f.path}: ${e.message}"); continue }
                // Listed with its original bytes; the target asks whether to copy it as it is.
                val d = runCatching { openFile(f.file.path).use { it.original() } }.getOrNull()
                    ?: run { unreadable++; continue }
                (d to d) to (kind to (e.message ?: ""))
            }
            if (why != null) unremovable[entries.size] = why
            entries += entry(f, digests.first, digests.second)
            kept += f
        }
        if (unreadable > 0) log("$unreadable files could not be read and are left out")
        return Triple(Manifest(choice, stripperVersion(), entries), unremovable, kept)
    }

    private fun entry(f: SourceFile, original: FileDigest, sent: FileDigest) = ManifestEntry(
        path = f.path, mimeType = f.mime, dateTakenMs = f.dateTakenMs, dateAddedMs = f.dateAddedMs,
        modifiedMs = f.modifiedMs, original = original, sent = sent,
    )

    /** Re-reads the file, strips it again if chosen, and sends it only if both hashes still match the manifest. */
    private fun sendFile(i: Int, f: SourceFile, e: ManifestEntry, choice: MetadataChoice, asIs: Boolean) {
        val opened = try { open(f, choice, asIs) } catch (x: Exception) {
            log("send ${f.path}: $x")
            ch.send(Msg.FILE_END, BinWriter().u32(i).u8(FileEnd.UNREADABLE).finish())
            return
        }
        opened.use { sf ->
            if (!sf.original().same(e.original) || !sf.sent().same(e.sent)) {
                log("changed since the manifest: ${f.path}")
                ch.send(Msg.FILE_END, BinWriter().u32(i).u8(FileEnd.CHANGED).finish())
                return
            }
            ch.send(Msg.FILE_BEGIN, BinWriter().u32(i).i64(e.sent.size.toLong()).finish())
            val piece = (ch.maxSingleChunkBody - 4).toUInt()
            var off = 0UL
            while (true) {
                val b = sf.read(off, piece)
                if (b.isEmpty()) break
                ch.send(Msg.FILE_DATA, BinWriter().u32(i).raw(b).finish())
                off += b.size.toUInt()
            }
            ch.send(Msg.FILE_END, BinWriter().u32(i).u8(FileEnd.OK).finish())
        }
    }
}

/** Phase 6: move verified files to this phone's trash, from this session's receipt only. */
class Removal(private val ctl: Controller) {
    fun run(files: List<SourceFile>, manifest: Manifest, removable: List<Int>) {
        val byFolder = removable.groupBy { Preflight.folder(manifest.files[it].path) }.toSortedMap()
        val stripped = removable.count { !manifest.files[it].original.blake3.contentEquals(manifest.files[it].sent.blake3) }
        val warn = if (stripped == 0) "" else "\n\n$stripped of these were copied with metadata removed: the new phone's " +
            "copies lack what this phone's still have (such as location). Removing them here loses it for good."
        val a = ctl.ask(Screen("Remove from this phone", "Pick what to move to this phone's trash. Only files the new " +
            "phone verified are listed, and each is checked again just before. Android asks you to confirm, and the " +
            "trash keeps them for 30 days.$warn",
            choices = byFolder.map { (f, idx) -> Choice(f, "$f (${idx.size} files, ${formatBytes(idx.sumOf { manifest.files[it].original.size.toLong() })})") },
            buttons = listOf("Move to trash", "Cancel")))
        if (a.button != "Move to trash" || a.checked.isEmpty()) {
            ctl.ask(Screen("Done", "Nothing was removed from this phone.", buttons = emptyList()))
            return
        }
        ctl.requireAuth("moves files to the trash", fresh = true)
        val chosen = removable.filter { Preflight.folder(manifest.files[it].path) in a.checked }
        val uris = ArrayList<android.net.Uri>()
        var changed = 0
        for ((n, i) in chosen.withIndex()) {
            ctl.show(Screen("Checking", "Re-reading each file before removal…", progress = Progress(n.toLong(), chosen.size.toLong(), "${n + 1} of ${chosen.size}")))
            val f = files[i]
            val same = runCatching { openFile(f.file.path).use { it.original().same(manifest.files[i].original) } }.getOrDefault(false)
            val uri = if (same) SourceLibrary.mediaUri(ctl.app, f.file) else null
            if (uri == null) { changed++; log("not removing ${f.path}: changed or unknown to MediaStore"); continue }
            uris += uri
        }
        var trashed = 0
        for (batch in uris.chunked(BATCH)) {
            val pi = MediaStore.createTrashRequest(ctl.app.contentResolver, batch, true)
            val activity = MainActivity.current ?: break
            if (activity.launchForResult(pi.intentSender).get()) trashed += batch.size else break
        }
        log("removal: $trashed moved to the trash, $changed skipped")
        ctl.ask(Screen("Removal finished", "$trashed files moved to this phone's trash (restorable for 30 days)." +
            (if (changed > 0) "\n$changed were not removed because they changed since the copy or MediaStore doesn't list them." else "") +
            (if (trashed < uris.size) "\n${uris.size - trashed} were not removed: the request was declined." else ""),
            buttons = emptyList()))
    }

    companion object {
        const val BATCH = 1000
    }
}
