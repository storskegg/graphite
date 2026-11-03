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
        while (true) {
            val a = ctl.ask(Screen("Copy verified", "The new phone signed a receipt and this phone checked it.\n\n$summary.$noteText" +
                if (removable.isEmpty()) "" else "\n\nYou can now move what was verified to this phone's trash (kept for 30 days). Nothing is preselected.",
                buttons = if (removable.isEmpty()) listOf("Done") else listOf(REMOVE, "Done")))
            if (a.button == REMOVE) { Removal(ctl).run(files, manifest, removable); return }
            // The receipt is only good for this session: leaving now means another transfer before any removal.
            if (removable.isNotEmpty()) {
                val sure = ctl.ask(Screen("Keep everything on this phone?",
                    "Leave the ${removable.size} verified files on this phone? To remove them later you need to run " +
                        "another transfer, since removal only uses this session's receipt.",
                    buttons = listOf("Keep them", "Back")))
                if (sure.button == "Back") continue
            }
            ctl.ask(Screen("Done", "Nothing was removed from this phone.", buttons = emptyList()))
            return
        }
    }

    private companion object {
        const val REMOVE = "Remove from this phone…"
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
        val items = ArrayList<SourceLibrary.Item>()
        var changed = 0
        for ((n, i) in chosen.withIndex()) {
            ctl.show(Screen("Checking", "Re-reading each file before removal…", progress = Progress(n.toLong(), chosen.size.toLong(), "${n + 1} of ${chosen.size}")))
            val f = files[i]
            val same = runCatching { openFile(f.file.path).use { it.original().same(manifest.files[i].original) } }.getOrDefault(false)
            val item = if (same) SourceLibrary.mediaItem(ctl.app, f.file) else null
            if (item == null) { changed++; log("not removing ${f.path}: changed or unknown to MediaStore"); continue }
            items += item
        }
        // Android's trash request takes only photos, videos and audio ("All requested items must be Media
        // items"); it shows Android's own confirmation. Other files (documents, downloads) go to the trash
        // through IS_TRASHED, which all-files access allows; the user confirmed above and authenticated again.
        val (media, other) = items.partition { it.media }
        val declined = HashSet<android.net.Uri>()
        for (batch in media.map { it.uri }.chunked(BATCH)) {
            val pi = MediaStore.createTrashRequest(ctl.app.contentResolver, batch, true)
            val activity = MainActivity.current
            if (activity == null || !activity.launchForResult(pi.intentSender).get()) declined += batch
        }
        fun trashDirectly(uri: android.net.Uri) = runCatching {
            ctl.app.contentResolver.update(uri, android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null)
        }.onFailure { log("trash $uri: $it") }
        other.forEach { trashDirectly(it.uri) }
        // An accepted trash request can still skip an item (a .mov that MediaStore types video/mp4 stayed put
        // on the Pixel 7 Pro), so each item's IS_TRASHED decides. Skipped items the user approved are trashed
        // directly; declined ones are left alone.
        media.filter { it.uri !in declined && !isTrashed(it.uri) }.forEach { log("trash request skipped ${it.uri}"); trashDirectly(it.uri) }
        val notTrashed = items.filter { it.uri !in declined && !isTrashed(it.uri) }
        notTrashed.forEach { log("not trashed: ${it.uri}") }
        val trashed = items.size - notTrashed.size - declined.size
        val failed = notTrashed.size
        log("removal: $trashed moved to the trash, $changed skipped, ${declined.size} declined, $failed failed")
        ctl.ask(Screen("Removal finished", "$trashed files moved to this phone's trash (restorable for 30 days)." +
            (if (changed > 0) "\n$changed were not removed because they changed since the copy or MediaStore doesn't list them." else "") +
            (if (declined.isNotEmpty()) "\n${declined.size} were not removed: the request was declined." else "") +
            (if (failed > 0) "\n$failed could not be moved to the trash; they are still on this phone." else ""),
            buttons = emptyList()))
    }

    private fun isTrashed(uri: android.net.Uri): Boolean {
        val args = android.os.Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
        return ctl.app.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_TRASHED), args, null)?.use {
            it.moveToFirst() && it.getInt(0) == 1
        } ?: false
    }

    companion object {
        const val BATCH = 1000
    }
}
