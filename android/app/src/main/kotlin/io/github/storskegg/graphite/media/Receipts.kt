package io.github.storskegg.graphite.media

import io.github.storskegg.graphite.ffi.NotCopiedReason
import io.github.storskegg.graphite.ffi.PlanStatus
import io.github.storskegg.graphite.ffi.ReceiptEntry
import io.github.storskegg.graphite.ffi.ReceiptStatus

/** How one entry came out of the copy or of verification. */
sealed class Outcome {
    data class Verified(val note: String = "") : Outcome()
    data class NotCopied(val reason: NotCopiedReason, val note: String = "") : Outcome()
}

/** Phase 5: the receipt's entries from the plan and what happened to each entry. */
object Receipts {
    /**
     * [outcomes] holds every entry that was copied or verified as present.
     * [primaryOf] maps an entry *to copy* to the entry that carried its bytes
     * (itself for the one copied). [notes] adds to an entry's note (dates,
     * metadata kept). [names] are the entries' paths and record labels.
     */
    fun build(
        names: List<String>,
        plan: List<PlanStatus>,
        outcomes: Map<Int, Outcome>,
        primaryOf: Map<Int, Int>,
        notes: Map<Int, String> = emptyMap(),
    ): List<ReceiptEntry> = plan.indices.map { i ->
        fun note(vararg parts: String) = (parts.toList() + listOfNotNull(notes[i])).filter { it.isNotEmpty() }.joinToString("; ")
        val (status, n) = when (plan[i]) {
            PlanStatus.UNTICKED -> ReceiptStatus.NotCopied(NotCopiedReason.UNTICKED) to note()
            PlanStatus.PRESENT_IN_TRASH -> ReceiptStatus.PresentInTrash to note()
            PlanStatus.PRESENT -> when (val o = outcomes[i]) {
                is Outcome.Verified -> ReceiptStatus.PresentVerified to note(o.note)
                is Outcome.NotCopied -> ReceiptStatus.NotCopied(o.reason) to note(o.note)
                null -> ReceiptStatus.NotCopied(NotCopiedReason.FAILED) to note("not verified")
            }
            PlanStatus.TO_COPY -> {
                val p = primaryOf[i] ?: i
                val via = if (p != i) "same as ${names[p]}, copied once" else ""
                when (val o = outcomes[p]) {
                    is Outcome.Verified -> ReceiptStatus.CopiedVerified to note(o.note, via)
                    is Outcome.NotCopied -> ReceiptStatus.NotCopied(o.reason) to note(o.note, via)
                    null -> ReceiptStatus.NotCopied(NotCopiedReason.FAILED) to note("not copied", via)
                }
            }
        }
        ReceiptEntry(i.toUInt(), status, n)
    }

    data class Summary(val copied: Int, val present: Int, val inTrash: Int, val notCopied: Map<NotCopiedReason, Int>) {
        override fun toString() = buildString {
            append("$copied copied and verified, $present already on the new phone (verified)")
            if (inTrash > 0) append(", $inTrash in the new phone's trash (not copied)")
            for ((r, n) in notCopied) append(", $n not copied (${reasonText(r)})")
        }
    }

    fun summary(receipt: List<ReceiptEntry>): Summary {
        val notCopied = sortedMapOf<NotCopiedReason, Int>()
        var copied = 0; var present = 0; var trash = 0
        for (e in receipt) when (val s = e.status) {
            ReceiptStatus.CopiedVerified -> copied++
            ReceiptStatus.PresentVerified -> present++
            ReceiptStatus.PresentInTrash -> trash++
            is ReceiptStatus.NotCopied -> notCopied.merge(s.reason, 1, Int::plus)
        }
        return Summary(copied, present, trash, notCopied)
    }

    fun reasonText(r: NotCopiedReason) = when (r) {
        NotCopiedReason.CHANGED_ON_SOURCE -> "changed on the old phone; copied next run"
        NotCopiedReason.DID_NOT_FIT -> "did not fit"
        NotCopiedReason.METADATA_NOT_REMOVABLE -> "metadata could not be removed"
        NotCopiedReason.FAILED -> "failed twice"
        NotCopiedReason.UNTICKED -> "unticked"
    }
}
