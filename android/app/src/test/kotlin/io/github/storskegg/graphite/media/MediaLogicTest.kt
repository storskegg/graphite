package io.github.storskegg.graphite.media

import io.github.storskegg.graphite.ffi.FileDigest
import io.github.storskegg.graphite.ffi.ManifestEntry
import io.github.storskegg.graphite.ffi.MetadataChoice
import io.github.storskegg.graphite.ffi.NotCopiedReason
import io.github.storskegg.graphite.ffi.PlanStatus
import io.github.storskegg.graphite.ffi.ReceiptStatus
import io.github.storskegg.graphite.util.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

private fun digest(tag: Int, size: Long) = FileDigest(size.toULong(), ByteArray(32) { tag.toByte() })

private fun entry(path: String, original: FileDigest, sent: FileDigest = original, added: Long? = null) =
    ManifestEntry(path, "image/jpeg", null, added, 0, original, sent)

class ComparisonTest {
    private val a = digest(1, 100)
    private val aStripped = digest(2, 90)
    private val b = digest(3, 200)
    private val c = digest(4, 300)

    private fun target(id: Long, d: FileDigest, trashed: Boolean = false) =
        TargetFile(id, "/t/$id", d.size.toLong(), 0, trashed) to d.blake3.hex()

    private fun run(entries: List<ManifestEntry>, vararg files: Pair<TargetFile, String>): List<Compared> {
        val hashes = files.toMap()
        val hashed = mutableListOf<Long>()
        return Comparison.compare(entries, hashes.keys.toList()) { hashed += it.id; hashes[it] }
            .also { assertEquals("each candidate hashed once", hashed.distinct(), hashed) }
    }

    @Test fun digestsCompareByContent() {
        assertTrue(digest(1, 100).same(digest(1, 100)))
        assertFalse(digest(1, 100).same(digest(1, 101)))
        assertFalse(digest(1, 100).same(digest(2, 100)))
    }

    @Test fun onlySizeCandidatesAreHashed() {
        val entries = listOf(entry("DCIM/a.jpg", a, aStripped))
        val files = listOf(TargetFile(1, "/x", 100, 0, false), TargetFile(2, "/y", 90, 0, false), TargetFile(3, "/z", 55, 0, false))
        assertEquals(listOf(1L, 2L), Comparison.candidates(entries, files).map { it.id })
    }

    @Test fun statusesFollowTheHashes() {
        val entries = listOf(
            entry("DCIM/a.jpg", a),          // present
            entry("DCIM/b.jpg", b),          // only in the trash
            entry("DCIM/c.jpg", c),          // nowhere
        )
        val r = run(entries, target(10, a), target(11, b, trashed = true), target(12, digest(9, 300)))
        assertEquals(listOf(PlanStatus.PRESENT, PlanStatus.PRESENT_IN_TRASH, PlanStatus.TO_COPY), r.map { it.status })
        assertEquals(10L, r[0].match!!.id)
    }

    @Test fun aStrippedCopyAlreadyThereIsPresent() {
        val r = run(listOf(entry("DCIM/a.jpg", a, aStripped)), target(5, aStripped))
        assertEquals(PlanStatus.PRESENT, r[0].status)
        assertEquals("", r[0].note)
    }

    @Test fun theOriginalWithMetadataIsPresentWithANote() {
        val r = run(listOf(entry("DCIM/a.jpg", a, aStripped)), target(5, a))
        assertEquals(PlanStatus.PRESENT, r[0].status)
        assertEquals(Comparison.WITH_METADATA, r[0].note)
    }

    @Test fun keepAllAfterAStrippedCopySkipsTheOriginal() {
        // An earlier run stripped `a` to `aStripped` (id 7, a size no entry has); now "Keep all" sends `a` itself.
        val copies = mapOf(a.blake3.hex() to (aStripped.blake3.hex() to 7L))
        val files = mapOf(TargetFile(7, "/t/7", 90, 0, false) to aStripped.blake3.hex())
        val r = Comparison.compare(listOf(entry("DCIM/a.jpg", digest(1, 100))), files.keys.toList(), copies) { files[it] }
        assertEquals(PlanStatus.PRESENT, r[0].status)
        assertEquals(Comparison.WITHOUT_METADATA, r[0].note)
        assertEquals(7L, r[0].match!!.id)
        assertEquals(aStripped.blake3.hex(), r[0].matchHash)
    }

    @Test fun aTrashedOrGoneStrippedCopyDoesNotCount() {
        val copies = mapOf(a.blake3.hex() to (aStripped.blake3.hex() to 7L))
        val trashed = mapOf(TargetFile(7, "/t/7", 90, 0, true) to aStripped.blake3.hex())
        assertEquals(PlanStatus.TO_COPY, Comparison.compare(listOf(entry("DCIM/a.jpg", a)), trashed.keys.toList(), copies) { trashed[it] }[0].status)
        assertEquals(PlanStatus.TO_COPY, Comparison.compare(listOf(entry("DCIM/a.jpg", a)), emptyList(), copies) { null }[0].status)
    }

    @Test fun aLiveCopyWinsOverATrashedOne() {
        val r = run(listOf(entry("DCIM/a.jpg", a)), target(1, a, trashed = true), target(2, a))
        assertEquals(PlanStatus.PRESENT, r[0].status)
        assertEquals(2L, r[0].match!!.id)
    }

    @Test fun nameAndFolderDoNotDecide() {
        // Same name, different bytes: copied.
        val r = run(listOf(entry("DCIM/a.jpg", c)), TargetFile(1, "/storage/emulated/0/DCIM/a.jpg", 300, 0, false) to "ff".repeat(32))
        assertEquals(PlanStatus.TO_COPY, r[0].status)
    }

    @Test fun anUnreadableCandidateMatchesNothing() {
        val r = Comparison.compare(listOf(entry("DCIM/a.jpg", a)), listOf(TargetFile(1, "/x", 100, 0, false))) { null }
        assertEquals(PlanStatus.TO_COPY, r[0].status)
    }

    @Test fun duplicatesAreCopiedOncePreferringDcimThenPicturesThenShortest() {
        val entries = listOf(
            entry("Download/longer/name/a.jpg", a),
            entry("Pictures/a.jpg", a),
            entry("DCIM/Camera/a.jpg", a),
            entry("Music/b.jpg", b),
            entry("Movie/b.jpg", b),
        )
        val p = Comparison.primaries(entries, entries.indices.toList())
        assertEquals(2, p[a.blake3.hex()])
        assertEquals(4, p[b.blake3.hex()]) // same length: alphabetical, so the choice is stable

        val c = Comparison.primaries(listOf(entry("Download/c.jpg", c), entry("Documents/c.jpg", c)), listOf(0, 1))
        assertEquals(0, c.values.single()) // shorter path
    }
}

class PreflightTest {
    @Test fun spaceKeepsAGigabyteFree() {
        val gib = 1L shl 30
        assertTrue(Preflight.fits(4 * gib, 5 * gib))
        assertFalse(Preflight.fits(4 * gib + 1, 5 * gib))
        assertEquals(1L, Preflight.shortfall(4 * gib + 1, 5 * gib))
        assertEquals(0L, Preflight.shortfall(1, 5 * gib))
    }

    @Test fun batteryRefusesUnder15UnlessCharging() {
        val ok = Battery(80, false)
        assertTrue(Preflight.battery(Battery(14, false), ok, 60).refuse!!.contains("old phone is at 14 %"))
        assertEquals(null, Preflight.battery(Battery(14, true), ok, 60).refuse)
        assertTrue(Preflight.battery(ok, Battery(3, false), 60).refuse!!.contains("new phone"))
    }

    @Test fun batteryWarnsUnder50OnlyForLongTransfers() {
        val ok = Battery(80, false)
        assertEquals(null, Preflight.battery(Battery(40, false), ok, 60).warn)
        assertTrue(Preflight.battery(Battery(40, false), ok, 3600).warn!!.contains("old phone is at 40 %"))
        assertEquals(null, Preflight.battery(Battery(40, true), ok, 3600).warn)
        assertEquals(null, Preflight.battery(Battery(50, false), ok, 3600).warn)
    }

    @Test fun timeFromTheMeasuredSpeed() {
        assertEquals(100L, Preflight.seconds(6_000_000_000, 60e6))
        assertEquals(0L, Preflight.seconds(1, 0.0))
    }

    @Test fun namesAreMadeSafe() {
        assertEquals("a_b_c.jpg", Preflight.safeName("a:b?c.jpg"))
        assertEquals("_", Preflight.safeName(".."))
        assertEquals("x", Preflight.safeName("x. "))
        assertEquals("Download/a_b/ok.pdf", Preflight.targetPath("Download/a|b/ok.pdf"))
        assertEquals("DCIM/Camera/IMG_1.jpg", Preflight.targetPath("DCIM/Camera/IMG_1.jpg"))
    }

    @Test fun longNamesAreCutToBytesKeepingTheExtension() {
        val long = "é".repeat(200) + ".jpeg" // 405 bytes
        val safe = Preflight.safeName(long)
        assertTrue(safe.toByteArray().size <= 255)
        assertTrue(safe.endsWith(".jpeg"))
        assertTrue(safe.removeSuffix(".jpeg").all { it == 'é' })
    }

    @Test fun foldersForUnticking() {
        assertEquals("DCIM/Camera", Preflight.folder("DCIM/Camera/a.jpg"))
        assertEquals("DCIM/Camera", Preflight.folder("DCIM/Camera/sub/a.jpg"))
        assertEquals("Download", Preflight.folder("Download/a.pdf"))
    }
}

class JournalTest {
    private val dir = Files.createTempDirectory("journal").toFile()
    private val file = File(dir, "journal.log")

    @Test fun survivesReopeningAndKeepsTheLatestState() {
        Journal(file).apply {
            pending("aa", 1); published("aa", 1)
            pending("bb", 2)
            pending("cc", 3); published("cc", 3); verified("cc")
        }
        val j = Journal(file)
        assertEquals(Journal.State.PUBLISHED, j.get("aa")!!.state)
        assertEquals(listOf("bb"), j.leftovers().map { it.sent })
        assertEquals(Journal.State.VERIFIED, j.get("cc")!!.state)
    }

    @Test fun strippedCopiesKeepTheirOriginalAcrossReopening() {
        Journal(file).apply {
            pending("bb", 2); published("bb", 2, "aa")   // stripped: original aa
            pending("cc", 3); published("cc", 3, "cc")   // kept as is: no original recorded
            pending("dd", 4)                             // still pending: not a copy yet
        }
        assertEquals(mapOf("aa" to ("bb" to 2L)), Journal(file).strippedCopies())
    }

    @Test fun forgottenEntriesStayForgotten() {
        Journal(file).apply { pending("aa", 1); forget("aa") }
        assertEquals(null, Journal(file).get("aa"))
        assertTrue(Journal(file).leftovers().isEmpty())
    }

    @Test fun aLineCutShortByACrashIsIgnoredAndCompacted() {
        Journal(file).apply { pending("aa", 1) }
        file.appendText("bb PUBLI")
        val j = Journal(file)
        assertEquals(listOf("aa"), j.leftovers().map { it.sent })
        assertEquals(null, j.get("bb"))
        assertEquals(1, file.readLines().size)
    }

    @Test fun verifyingAnUnknownEntryDoesNothing() {
        Journal(file).verified("zz")
        assertEquals(null, Journal(file).get("zz"))
    }
}

class ReceiptsTest {
    private val a = digest(1, 100)
    private val b = digest(2, 200)
    private val entries = listOf(
        entry("DCIM/a.jpg", a),           // 0 copied (primary)
        entry("Download/a.jpg", a),       // 1 duplicate of 0
        entry("DCIM/b.jpg", b),           // 2 present
        entry("DCIM/c.jpg", digest(3, 1)), // 3 in trash
        entry("DCIM/d.jpg", digest(4, 1)), // 4 unticked
        entry("DCIM/e.jpg", digest(5, 1)), // 5 changed on the source
        entry("DCIM/f.jpg", digest(6, 1)), // 6 to copy but never reached
    )
    private val plan = listOf(PlanStatus.TO_COPY, PlanStatus.TO_COPY, PlanStatus.PRESENT, PlanStatus.PRESENT_IN_TRASH,
        PlanStatus.UNTICKED, PlanStatus.TO_COPY, PlanStatus.TO_COPY)

    @Test fun everyEntryGetsTheStatusThePlanAndOutcomesGive() {
        val r = Receipts.build(entries, plan,
            outcomes = mapOf(0 to Outcome.Verified(), 2 to Outcome.Verified(Comparison.WITH_METADATA),
                5 to Outcome.NotCopied(NotCopiedReason.CHANGED_ON_SOURCE)),
            primaryOf = mapOf(0 to 0, 1 to 0, 5 to 5, 6 to 6),
            notes = mapOf(0 to "date taken 1, not 2"))
        assertEquals(entries.indices.map { it.toUInt() }, r.map { it.index })
        assertEquals(ReceiptStatus.CopiedVerified, r[0].status)
        assertEquals("date taken 1, not 2", r[0].note)
        assertEquals(ReceiptStatus.CopiedVerified, r[1].status)
        assertEquals("same bytes as DCIM/a.jpg, copied once", r[1].note)
        assertEquals(ReceiptStatus.PresentVerified, r[2].status)
        assertEquals(Comparison.WITH_METADATA, r[2].note)
        assertEquals(ReceiptStatus.PresentInTrash, r[3].status)
        assertEquals(ReceiptStatus.NotCopied(NotCopiedReason.UNTICKED), r[4].status)
        assertEquals(ReceiptStatus.NotCopied(NotCopiedReason.CHANGED_ON_SOURCE), r[5].status)
        assertEquals(ReceiptStatus.NotCopied(NotCopiedReason.FAILED), r[6].status)
    }

    @Test fun aDuplicateSharesItsPrimarysFailure() {
        val r = Receipts.build(entries, plan, mapOf(0 to Outcome.NotCopied(NotCopiedReason.FAILED, "hash did not match")),
            primaryOf = mapOf(0 to 0, 1 to 0))
        assertEquals(ReceiptStatus.NotCopied(NotCopiedReason.FAILED), r[1].status)
        assertEquals("hash did not match; same bytes as DCIM/a.jpg, copied once", r[1].note)
    }

    @Test fun onlyThePlansUntickedEntriesAreNotCopiedAsUnticked() {
        // The core's rule: unticked in the receipt exactly when unticked in the plan.
        val r = Receipts.build(entries, plan, emptyMap(), emptyMap())
        val unticked = r.filter { it.status == ReceiptStatus.NotCopied(NotCopiedReason.UNTICKED) }.map { it.index.toInt() }
        assertEquals(plan.indices.filter { plan[it] == PlanStatus.UNTICKED }, unticked)
    }

    @Test fun summaryCounts() {
        val r = Receipts.build(entries, plan, mapOf(0 to Outcome.Verified(), 2 to Outcome.Verified()), mapOf(0 to 0, 1 to 0))
        val s = Receipts.summary(r)
        assertEquals(2, s.copied)
        assertEquals(1, s.present)
        assertEquals(1, s.inTrash)
        assertEquals(mapOf(NotCopiedReason.FAILED to 2, NotCopiedReason.UNTICKED to 1), s.notCopied.toMap())
    }

    @Test fun wireMessagesRoundTrip() {
        val inv = Inventory(Tally(1, 2), Tally(3, 4), Tally(), Tally(5, 6), Tally(7, 8), "graphite-test")
        assertEquals(inv, Inventory.decode(inv.encode()))
        val o = Options(MetadataChoice.REMOVE_ALL_BUT_DISPLAY, true)
        assertEquals(o, Options.decode(o.encode()))
        val m = ManifestMsg(byteArrayOf(1, 2, 3), mapOf(4 to (Unremovable.RAW to "RAW files are built around EXIF")))
        val back = ManifestMsg.decode(m.encode())
        assertTrue(back.signed.contentEquals(m.signed))
        assertEquals(m.unremovable, back.unremovable)
        val p = ProgressMsg("2,140 of 5,485 files: DCIM/a.jpg", 7, 9, "7 B of 9 B at 1 MB/s")
        assertEquals(p, ProgressMsg.decode(p.encode()))
    }

    @Test fun progressFromAnOlderBuildHasNoLabel() {
        val old = io.github.storskegg.graphite.util.BinWriter().str("Hashing").i64(1).i64(2).finish()
        assertEquals(ProgressMsg("Hashing", 1, 2, ""), ProgressMsg.decode(old))
    }

    @Test fun phasesSumPerNameAndReportTheRest() {
        var now = 0L
        val p = Phases { now }
        p.time("write") { now += 2_000_000_000 }
        p.time("publish") { now += 500_000_000 }
        p.time("write") { now += 1_000_000_000 }
        now += 1_500_000_000
        assertEquals("5.0 s in all: write 3.0 s, publish 0.5 s, other 1.5 s", p.summary())
    }

    @Test fun rateAndTimeLeft() {
        assertEquals("", Meter.rateAndLeft(0, 100, 10.0))
        assertEquals("", Meter.rateAndLeft(50, 100, 0.5))
        assertEquals(" at 10 MB/s", Meter.rateAndLeft(20_000_000, 100_000_000, 2.0))
        assertEquals(" at 10 MB/s, about 3 minutes left", Meter.rateAndLeft(100_000_000, 1_900_000_000, 10.0))
        assertEquals(" at 10 MB/s", Meter.rateAndLeft(100_000_000, 100_000_000, 10.0))
    }
}

class CableSetupTest {
    @Test fun installedVersionReadsOnlyThisPackage() {
        val pkg = "io.github.storskegg.graphite"
        val out = "package:io.github.storskegg.graphite.debug versionCode:9\npackage:io.github.storskegg.graphite versionCode:3\n"
        assertEquals(3L, io.github.storskegg.graphite.usb.CableSetup.installedVersion(out, pkg))
        assertEquals(null, io.github.storskegg.graphite.usb.CableSetup.installedVersion("", pkg))
        assertEquals(null, io.github.storskegg.graphite.usb.CableSetup.installedVersion("package:io.github.storskegg.graphite.debug versionCode:9", pkg))
    }
}
