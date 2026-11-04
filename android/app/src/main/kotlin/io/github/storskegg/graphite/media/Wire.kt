package io.github.storskegg.graphite.media

import io.github.storskegg.graphite.ffi.MetadataChoice
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
) {
    fun encode(): ByteArray = BinWriter().apply {
        for (t in listOf(photos, videos, audio, other, androidMedia)) { i64(t.count); i64(t.bytes) }
        str(testScope)
    }.finish()

    companion object {
        fun decode(b: ByteArray) = BinReader(b).run {
            fun t() = Tally(i64(), i64())
            Inventory(t(), t(), t(), t(), t(), str())
        }
    }
}

/** Target → source: the user's choices for this transfer. */
data class Options(val metadata: MetadataChoice, val androidMedia: Boolean) {
    fun encode() = BinWriter().u8(metadata.ordinal).bool(androidMedia).finish()

    companion object {
        fun decode(b: ByteArray) = BinReader(b).run { Options(MetadataChoice.entries[u8()], bool()) }
    }
}

/** Either way: progress to show on the other phone; [label] goes under the bar (bytes, rate, time left). */
data class ProgressMsg(val text: String, val done: Long, val total: Long, val label: String = "") {
    fun encode() = BinWriter().str(text).i64(done).i64(total).str(label).finish()

    companion object {
        fun decode(b: ByteArray) = BinReader(b).run { ProgressMsg(str(), i64(), i64(), if (more()) str() else "") }
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
