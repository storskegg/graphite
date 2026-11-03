package io.github.storskegg.graphite.media

import io.github.storskegg.graphite.ffi.FileDigest
import io.github.storskegg.graphite.ffi.ManifestEntry
import io.github.storskegg.graphite.ffi.PlanStatus
import io.github.storskegg.graphite.util.hex

/** Same size and hash. The generated [FileDigest] holds a ByteArray, so its `==` compares references. */
fun FileDigest.same(o: FileDigest) = size == o.size && blake3.contentEquals(o.blake3)

/** A file in the target's MediaStore, trashed or not (pending items are left out). */
data class TargetFile(
    val id: Long,
    /** Absolute path (`_data`). */
    val path: String,
    val size: Long,
    /** MediaStore `DATE_MODIFIED`, in seconds: part of the hash cache key. */
    val modifiedS: Long,
    val trashed: Boolean,
)

/** What the target already holds for one manifest entry. */
data class Compared(
    val status: PlanStatus,
    /** The target file that matched, for verification. */
    val match: TargetFile? = null,
    val note: String = "",
    /** The matched file's hash, when it is neither of the entry's (a copy stripped in an earlier run). */
    val matchHash: String? = null,
)

/**
 * Phase 2: which manifest entries the target already has. Identity is the
 * bytes: a target file is the same item when its BLAKE3 matches the entry's
 * sent or original hash. Name, folder and dates don't decide it.
 */
object Comparison {
    const val WITH_METADATA = "already on the new phone, with its metadata"
    const val WITHOUT_METADATA = "already on the new phone, without its metadata"

    /** Only target files whose size matches either size of some entry need hashing. */
    fun candidates(entries: List<ManifestEntry>, files: List<TargetFile>): List<TargetFile> {
        val sizes = HashSet<Long>()
        for (e in entries) { sizes += e.original.size.toLong(); sizes += e.sent.size.toLong() }
        return files.filter { it.size in sizes }
    }

    /**
     * Gives every entry a status. [hashOf] returns a candidate's BLAKE3 in hex,
     * or null when it can't be read (then it matches nothing). Each candidate
     * is hashed at most once.
     */
    fun compare(
        entries: List<ManifestEntry>,
        files: List<TargetFile>,
        strippedCopies: Map<String, Pair<String, Long>> = emptyMap(),
        hashOf: (TargetFile) -> String?,
    ): List<Compared> {
        val byHash = HashMap<String, MutableList<TargetFile>>()
        // A copy stripped in an earlier run has neither of the entry's sizes, so it is a candidate by ID.
        val wanted = entries.mapNotNull { strippedCopies[it.original.blake3.hex()]?.second }.toSet()
        for (f in (candidates(entries, files) + files.filter { it.id in wanted }).distinctBy { it.id }) {
            val h = hashOf(f) ?: continue
            byHash.getOrPut(h) { mutableListOf() } += f
        }
        return entries.map { e ->
            val sent = byHash[e.sent.blake3.hex()].orEmpty()
            val original = if (e.original.blake3.contentEquals(e.sent.blake3)) emptyList()
                else byHash[e.original.blake3.hex()].orEmpty()
            val live = sent.firstOrNull { !it.trashed }
            val liveOriginal = original.firstOrNull { !it.trashed }
            when {
                live != null -> Compared(PlanStatus.PRESENT, live)
                liveOriginal != null -> Compared(PlanStatus.PRESENT, liveOriginal, WITH_METADATA)
                (sent + original).isNotEmpty() -> Compared(PlanStatus.PRESENT_IN_TRASH, (sent + original).first())
                else -> {
                    // The reverse of WITH_METADATA: graphite stripped this original in an earlier run and the
                    // copy is still there. Never copy the original next to it, never replace it.
                    val stripped = strippedCopies[e.original.blake3.hex()]
                    val copy = stripped?.let { (hash, id) -> byHash[hash]?.firstOrNull { it.id == id && !it.trashed } }
                    if (copy != null) Compared(PlanStatus.PRESENT, copy, WITHOUT_METADATA, stripped.first)
                    else Compared(PlanStatus.TO_COPY)
                }
            }
        }
    }

    /**
     * Same sent bytes at several source paths are copied once. Returns, for
     * each sent hash among [toCopy], the index to copy: `DCIM/` first, then
     * `Pictures/`, then the shortest path.
     */
    fun primaries(entries: List<ManifestEntry>, toCopy: Collection<Int>): Map<String, Int> =
        toCopy.groupBy { entries[it].sent.blake3.hex() }.mapValues { (_, group) ->
            group.minWith(compareBy<Int>({ rank(entries[it].path) }, { entries[it].path.length }, { entries[it].path }))
        }

    private fun rank(path: String) = when {
        path.startsWith("DCIM/") -> 0
        path.startsWith("Pictures/") -> 1
        else -> 2
    }
}
