package io.github.storskegg.graphite.media

import io.github.storskegg.graphite.ffi.NotCopiedReason
import io.github.storskegg.graphite.ffi.PlanStatus
import io.github.storskegg.graphite.ffi.ReceiptEntry
import io.github.storskegg.graphite.ffi.ReceiptStatus
import io.github.storskegg.graphite.util.BinReader
import io.github.storskegg.graphite.util.BinWriter

/** A tile's state: its colour in the treemap. */
enum class Tile(val label: String) {
    PENDING("waiting"),
    ACTIVE("in progress"),
    DONE("done"),
    FAILED("failed"),
    /** Already on the new phone: verified with the rest. */
    PRESENT("already on the new phone"),
    /** Unticked, in the new phone's trash, did not fit, changed on the old phone: drawn dimmed. */
    SKIPPED("not copied");

    companion object { val all = entries }
}

/**
 * The treemap of a transfer: one tile per manifest entry, sized by the bytes sent, grouped by
 * folder, laid out once from the manifest so both phones draw the same map. [states] change as
 * files are copied and verified; [snapshot] is what a screen shows, [delta] what the other phone
 * needs to catch up.
 */
class TileMap(val paths: List<String>, val sizes: List<Long>, initial: List<Tile>, follows: Map<Int, Int> = emptyMap()) {
    val n = paths.size
    /** x, y, w, h per entry, in a box [WIDTH] by [HEIGHT]. */
    val rects = FloatArray(4 * n)
    val groups: List<Group>
    private val states = ByteArray(n) { initial[it].ordinal.toByte() }
    private val sent = states.copyOf()
    private var sentAll = false
    /** A copied file's duplicates take its state. */
    private val followers: Map<Int, List<Int>> = follows.filter { (d, p) -> d != p }.entries.groupBy({ it.value }, { it.key })

    class Group(val name: String, val x: Float, val y: Float, val w: Float, val h: Float)

    init {
        val byFolder = (0 until n).groupBy { folderOf(paths[it]) }.entries.toList()
        val folderRects = squarify(byFolder.map { (_, idx) -> idx.sumOf { weight(it) } }, 0.0, 0.0, WIDTH, HEIGHT)
        groups = byFolder.mapIndexed { g, (name, idx) ->
            val r = folderRects[g]
            squarify(idx.map { weight(it) }, r[0], r[1], r[2], r[3]).forEachIndexed { k, t ->
                for (c in 0..3) rects[4 * idx[k] + c] = t[c].toFloat()
            }
            Group(name, r[0].toFloat(), r[1].toFloat(), r[2].toFloat(), r[3].toFloat())
        }
    }

    private fun weight(i: Int) = sizes[i].coerceAtLeast(1).toDouble()

    operator fun get(i: Int): Tile = Tile.all[states[i].toInt()]

    @Synchronized fun set(i: Int, t: Tile) {
        states[i] = t.ordinal.toByte()
        followers[i]?.forEach { states[it] = t.ordinal.toByte() }
    }

    /** Every tile in [from] goes to [to] (copied files back to waiting, before verification). */
    @Synchronized fun reset(from: Set<Tile>, to: Tile) {
        for (i in 0 until n) if (this[i] in from) states[i] = to.ordinal.toByte()
    }

    /** The final state from the signed receipt, the same on both phones. */
    @Synchronized fun applyReceipt(entries: List<ReceiptEntry>) {
        for (e in entries) states[e.index.toInt()] = when (val s = e.status) {
            ReceiptStatus.CopiedVerified, ReceiptStatus.PresentVerified -> Tile.DONE
            ReceiptStatus.PresentInTrash -> Tile.SKIPPED
            is ReceiptStatus.NotCopied -> if (s.reason == NotCopiedReason.FAILED) Tile.FAILED else Tile.SKIPPED
        }.ordinal.toByte()
    }

    @Synchronized fun snapshot() = Tiles(this, states.copyOf())

    @Synchronized fun counts(): Map<Tile, Int> = states.asList().groupingBy { Tile.all[it.toInt()] }.eachCount()

    /** What changed since the last call, for the other phone; everything the first time. */
    @Synchronized fun delta(): ByteArray {
        val w = BinWriter()
        if (!sentAll) {
            sentAll = true
            w.u8(FULL).bytes(states)
        } else {
            val changed = (0 until n).filter { states[it] != sent[it] }
            w.u8(CHANGES).u32(changed.size)
            for (i in changed) w.u32(i).u8(states[i].toInt())
        }
        states.copyInto(sent)
        return w.finish()
    }

    /** Applies the other phone's [delta]; whether it was a whole map (the first). */
    @Synchronized fun apply(delta: ByteArray): Boolean {
        if (delta.isEmpty()) return false
        return BinReader(delta).run {
            when (u8()) {
                FULL -> { bytes().copyInto(states); true }
                else -> { repeat(u32().toInt()) { val i = u32().toInt(); states[i] = u8().toByte() }; false }
            }
        }
    }

    companion object {
        const val WIDTH = 1.0
        const val HEIGHT = 0.6
        private const val FULL = 1
        private const val CHANGES = 2

        /** The tiles before the copy, from the plan. */
        fun initial(plan: List<PlanStatus>): List<Tile> = plan.map {
            when (it) {
                PlanStatus.TO_COPY -> Tile.PENDING
                PlanStatus.PRESENT -> Tile.PRESENT
                PlanStatus.PRESENT_IN_TRASH, PlanStatus.UNTICKED -> Tile.SKIPPED
            }
        }

        /** "DCIM/Camera" for "DCIM/Camera/a.jpg"; "" at the top. */
        fun folderOf(path: String) = path.substringBeforeLast('/', "")

        /**
         * Squarified treemap (Bruls, Huizing, van Wijk): rectangles x, y, w, h for [weights] (all
         * positive) filling the box, in the order given, laid out largest first.
         */
        fun squarify(weights: List<Double>, x0: Double, y0: Double, w0: Double, h0: Double): List<DoubleArray> {
            val out = Array(weights.size) { DoubleArray(4) }
            if (weights.isEmpty()) return out.toList()
            val order = weights.indices.sortedByDescending { weights[it] }
            val scale = w0 * h0 / weights.sum()
            val area = DoubleArray(weights.size) { weights[it] * scale }
            var x = x0; var y = y0; var w = w0; var h = h0
            fun worst(max: Double, min: Double, sum: Double, side: Double) =
                maxOf(side * side * max / (sum * sum), sum * sum / (side * side * min))
            var i = 0
            while (i < order.size) {
                val side = minOf(w, h)
                var sum = area[order[i]]
                var best = worst(sum, sum, sum, side)
                var j = i + 1
                while (j < order.size) {
                    val a = area[order[j]]
                    val next = worst(area[order[i]], a, sum + a, side)
                    if (next > best) break
                    best = next; sum += a; j++
                }
                if (w >= h) {
                    val cw = if (h > 0) sum / h else 0.0
                    var yy = y
                    for (k in i until j) {
                        val hh = if (cw > 0) area[order[k]] / cw else 0.0
                        out[order[k]] = doubleArrayOf(x, yy, cw, hh); yy += hh
                    }
                    x += cw; w -= cw
                } else {
                    val rh = if (w > 0) sum / w else 0.0
                    var xx = x
                    for (k in i until j) {
                        val ww = if (rh > 0) area[order[k]] / rh else 0.0
                        out[order[k]] = doubleArrayOf(xx, y, ww, rh); xx += ww
                    }
                    y += rh; h -= rh
                }
                i = j
            }
            return out.toList()
        }
    }
}

/** A treemap as a screen shows it: the shared layout and the states at that moment. */
class Tiles(val map: TileMap, private val states: ByteArray) {
    operator fun get(i: Int): Tile = Tile.all[states[i].toInt()]
    val counts: Map<Tile, Int> get() = states.asList().groupingBy { Tile.all[it.toInt()] }.eachCount()

    /** "3 done, 1 in progress, 6 waiting": for the log, accessibility and the emulator test. */
    fun summary(): String = Tile.all.mapNotNull { t -> counts[t]?.let { "$it ${t.label}" } }.joinToString(", ")

    /** The entry at (x, y) in the map's box, or -1. */
    fun at(x: Float, y: Float): Int {
        val r = map.rects
        for (i in 0 until map.n) {
            if (x >= r[4 * i] && x < r[4 * i] + r[4 * i + 2] && y >= r[4 * i + 1] && y < r[4 * i + 1] + r[4 * i + 3]) return i
        }
        return -1
    }
}
