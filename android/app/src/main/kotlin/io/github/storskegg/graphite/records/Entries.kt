package io.github.storskegg.graphite.records

import io.github.storskegg.graphite.ffi.Manifest
import io.github.storskegg.graphite.ffi.PlanStatus
import io.github.storskegg.graphite.media.Comparison
import io.github.storskegg.graphite.util.hex

/** The manifest's entries, files then records, as the screens and the receipt name them. */
object Entries {
    fun names(m: Manifest) = m.files.map { it.path } + m.records.map { it.label }

    fun sizes(m: Manifest) = m.files.map { it.sent.size.toLong() } + m.records.map { it.sent.size.toLong() }

    /**
     * For each entry *to copy*, the entry whose copy it takes: files by sent hash
     * ([Comparison.primaries]), records by identity, each the first of its kind.
     */
    fun primaries(m: Manifest, statuses: List<PlanStatus>): Map<Int, Int> {
        val n = m.files.size
        val files = statuses.indices.filter { it < n && statuses[it] == PlanStatus.TO_COPY }
        val byHash = Comparison.primaries(m.files, files)
        val out = files.associateWith { byHash.getValue(m.files[it].sent.blake3.hex()) }.toMutableMap()
        val firstOf = HashMap<String, Int>()
        for (i in n until statuses.size) if (statuses[i] == PlanStatus.TO_COPY) {
            out[i] = firstOf.getOrPut(m.records[i - n].identity.hex()) { i }
        }
        return out
    }
}
