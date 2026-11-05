package io.github.storskegg.graphite.media

import io.github.storskegg.graphite.ffi.MetadataChoice
import io.github.storskegg.graphite.records.Category
import io.github.storskegg.graphite.records.RecordCounts
import io.github.storskegg.graphite.util.BinReader
import io.github.storskegg.graphite.util.BinWriter
import io.github.storskegg.graphite.util.formatBytes

/** Count and bytes of one kind of file. */
data class Tally(val count: Long = 0, val bytes: Long = 0) {
    operator fun plus(size: Long) = Tally(count + 1, bytes + size)
    /** "6 photos (43 kB)". */
    fun of(what: String) = "$count $what (${formatBytes(bytes)})"
}

/** Source → target, before any hashing: what is in scope. */
data class Inventory(
    val photos: Tally,
    val videos: Tally,
    val audio: Tally,
    val other: Tally,
    /** `Android/media/`, offered as an opt-in. */
    val androidMedia: Tally,
    /** Test builds only: the folder name the scope is limited to; empty otherwise. */
    val testScope: String,
    /** Messages, calls, contacts and blocked numbers the source can read (docs/records.md). */
    val records: RecordCounts = RecordCounts(),
) {
    fun encode(): ByteArray = BinWriter().apply {
        for (t in listOf(photos, videos, audio, other, androidMedia)) { i64(t.count); i64(t.bytes) }
        str(testScope)
        for (c in Category.entries) i64(records.of(c))
    }.finish()

    companion object {
        fun decode(b: ByteArray) = BinReader(b).run {
            fun t() = Tally(i64(), i64())
            Inventory(t(), t(), t(), t(), t(), str(), if (more()) RecordCounts(i64(), i64(), i64(), i64()) else RecordCounts())
        }
    }
}

/** Target → source: the user's choices for this transfer. */
data class Options(val metadata: MetadataChoice, val androidMedia: Boolean, val records: Set<Category> = emptySet()) {
    fun encode() = BinWriter().u8(metadata.ordinal).bool(androidMedia).u8(Category.mask(records)).finish()

    companion object {
        fun decode(b: ByteArray) = BinReader(b).run {
            Options(MetadataChoice.entries[u8()], bool(), if (more()) Category.of(u8()) else emptySet())
        }
    }
}

/**
 * Either way: progress to show on the other phone; [label] goes under the bar (bytes, rate, time
 * left), [tiles] is a [TileMap.delta] (empty for none).
 */
data class ProgressMsg(val text: String, val done: Long, val total: Long, val label: String = "", val tiles: ByteArray = ByteArray(0)) {
    fun encode() = BinWriter().str(text).i64(done).i64(total).str(label).bytes(tiles).finish()

    override fun equals(other: Any?) = other is ProgressMsg && text == other.text && done == other.done &&
        total == other.total && label == other.label && tiles.contentEquals(other.tiles)

    override fun hashCode() = listOf(text, done, total, label, tiles.contentHashCode()).hashCode()

    companion object {
        fun decode(b: ByteArray) = BinReader(b).run {
            ProgressMsg(str(), i64(), i64(), if (more()) str() else "", if (more()) bytes() else ByteArray(0))
        }
    }
}

/** Why a file under a stripping choice can't have its metadata removed. */
enum class Unremovable(val label: String) {
    RAW("RAW files"),
    MALFORMED("files that could not be read as their format"),
    UNSUPPORTED("files with a structure graphite can't rewrite safely"),
}

/** Source → target: the signed manifest, and which entries can't be stripped (sent as they are if the user agrees). */
class ManifestMsg(val signed: ByteArray, val unremovable: Map<Int, Pair<Unremovable, String>>) {
    fun encode() = BinWriter().bytes(signed).u32(unremovable.size).apply {
        for ((i, v) in unremovable) { u32(i); u8(v.first.ordinal); str(v.second) }
    }.finish()

    companion object {
        fun decode(b: ByteArray) = BinReader(b).run {
            val signed = bytes()
            val n = u32().toInt()
            ManifestMsg(signed, (0 until n).associate { u32().toInt() to (Unremovable.entries[u8()] to str()) })
        }
    }
}

/** An answer to a signed document: accepted, or why not. */
data class Verdict(val ok: Boolean, val why: String = "") {
    fun encode() = BinWriter().bool(ok).str(why).finish()

    companion object {
        fun decode(b: ByteArray) = BinReader(b).run { Verdict(bool(), str()) }
    }
}
