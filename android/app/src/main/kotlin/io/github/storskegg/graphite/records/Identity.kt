package io.github.storskegg.graphite.records

import io.github.storskegg.graphite.util.hex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What makes two records the same item, and how a record is shown (docs/records.md, "What counts
 * as a duplicate"). Both phones run this on rows read from their own providers, so the source's
 * manifest and the target's comparison agree. [hash] is BLAKE3 (passed in so this stays testable
 * off the phone).
 */
class Identity(private val hash: (ByteArray) -> ByteArray) {
    /** The identity fields, in order, for core's `record_identity`. */
    fun fields(kind: Kind, rows: List<Row>): List<ByteArray> = when (kind) {
        Kind.SMS -> rows.first().let { listOf(it.str("type"), number(it.str("address")), it.str("date"), it.str("body")) }
        Kind.MMS -> {
            val pdu = rows.first { it.table == MMS }
            val addrs = rows.filter { it.table == ADDR }.map { "${it.str("type")}:${number(it.str("address"))}" }.sorted()
            val parts = rows.filter { it.table == PART }.map { p ->
                val content = p.bytes(DATA_BYTES)?.let { hash(it).hex() } ?: p.str("text").orEmpty()
                "${p.str("ct")}\u0000$content"
            }.sorted()
            listOf(pdu.str("msg_box"), pdu.str("date"), addrs.joinToString("\n"), pdu.str("sub")) + parts
        }
        Kind.CALL -> rows.first().let { listOf(number(it.str("number")), it.str("date"), it.str("duration"), it.str("type")) }
        Kind.CONTACT -> {
            val data = rows.filter { it.table == CONTACT_DATA }
            fun of(mime: String, norm: (String?) -> String) =
                data.filter { it.str("mimetype") == mime }.map { norm(it.str("data1")) }.filter { it.isNotEmpty() }.toSortedSet().joinToString("\n")
            listOf(of(NAME, ::name), of(PHONE, ::number), of(EMAIL) { it.orEmpty().trim().lowercase(Locale.ROOT) })
        }
        Kind.BLOCKED -> listOf(number(rows.first().str("original_number")))
    }.map { (it ?: "").toByteArray(Charsets.UTF_8) }

    /** When it happened, for the copy order: SMS and calls in ms, MMS in s. */
    fun date(kind: Kind, rows: List<Row>): Long? = when (kind) {
        Kind.SMS, Kind.CALL -> rows.first().long("date")
        Kind.MMS -> rows.first { it.table == MMS }.long("date")?.let { it * 1000 }
        Kind.CONTACT, Kind.BLOCKED -> null
    }

    /** What the screens show: the kind's folder, then the other party and the time, or the name. */
    fun label(kind: Kind, rows: List<Row>): String {
        val at = date(kind, rows)?.let { " " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(it)) }.orEmpty()
        val who = when (kind) {
            Kind.SMS -> rows.first().str("address")
            Kind.MMS -> parties(rows).joinToString(", ")
            Kind.CALL -> rows.first().str("number")
            Kind.CONTACT -> rows.filter { it.table == CONTACT_DATA && it.str("mimetype") == NAME }.firstNotNullOfOrNull { it.str("data1") }
                ?: rows.firstOrNull { it.table == CONTACT_DATA && it.str("mimetype") == PHONE }?.str("data1")
            Kind.BLOCKED -> rows.first().str("original_number")
        }
        return "${kind.folder}/${(who ?: "unknown").replace('/', '_')}$at"
    }

    /** The other phones in an MMS: its addresses but this phone's own placeholder. */
    fun parties(rows: List<Row>) = rows.filter { it.table == ADDR }.mapNotNull { it.str("address") }
        .filter { it != INSERT_ADDRESS_TOKEN }.distinct()

    /**
     * Test builds: only clearly marked test records (docs/records.md, "Test data"): numbers in the
     * fictional +1 555 010x range, contacts named "GT …".
     */
    fun inTestScope(kind: Kind, rows: List<Row>): Boolean {
        fun test(n: String?) = number(n).startsWith(TEST_PREFIX)
        return when (kind) {
            Kind.SMS -> test(rows.first().str("address"))
            Kind.MMS -> parties(rows).let { p -> p.isNotEmpty() && p.all(::test) }
            Kind.CALL -> test(rows.first().str("number"))
            Kind.CONTACT -> rows.any { it.table == CONTACT_DATA && it.str("mimetype") == NAME && name(it.str("data1")).startsWith("gt ") }
            Kind.BLOCKED -> test(rows.first().str("original_number"))
        }
    }

    companion object {
        const val MMS = "pdu"
        const val ADDR = "addr"
        const val PART = "part"
        /** A contact's data rows and its raw-contact row. */
        const val CONTACT_DATA = "data"
        const val RAW = "raw"
        /** An MMS part's bytes, carried as a pseudo-column. */
        const val DATA_BYTES = "graphite_data"
        const val NAME = "vnd.android.cursor.item/name"
        const val PHONE = "vnd.android.cursor.item/phone_v2"
        const val EMAIL = "vnd.android.cursor.item/email_v2"
        const val INSERT_ADDRESS_TOKEN = "insert-address-token"
        const val TEST_PREFIX = "+1555010"

        /** Digits and a leading +; a sender with no digits ("AMAZON") as written, lowercased. */
        fun number(s: String?): String {
            val t = s?.trim().orEmpty()
            val digits = t.filter { it.isDigit() }
            if (digits.isEmpty()) return t.lowercase(Locale.ROOT)
            return (if (t.startsWith("+")) "+" else "") + digits
        }

        /** Whitespace collapsed, case-folded. */
        fun name(s: String?) = s?.trim()?.replace(Regex("\\s+"), " ")?.lowercase(Locale.ROOT).orEmpty()
    }
}
