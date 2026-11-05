package io.github.storskegg.graphite.records

import android.content.ContentValues
import android.database.Cursor
import io.github.storskegg.graphite.ffi.RecordKind
import io.github.storskegg.graphite.util.BinReader
import io.github.storskegg.graphite.util.BinWriter
import java.util.SortedMap

/** What the user ticks: messages are SMS and MMS together. */
enum class Category(val label: String) {
    MESSAGES("Messages"), CALLS("Call log"), CONTACTS("Contacts"), BLOCKED("Blocked numbers");

    companion object {
        fun mask(cs: Set<Category>) = cs.fold(0) { m, c -> m or (1 shl c.ordinal) }
        fun of(mask: Int) = entries.filter { mask and (1 shl it.ordinal) != 0 }.toSet()
    }
}

/** A record kind: how it is labelled and which category it is ticked under. */
enum class Kind(val ffi: RecordKind, val folder: String, val category: Category) {
    SMS(RecordKind.SMS, "Messages", Category.MESSAGES),
    MMS(RecordKind.MMS, "Messages", Category.MESSAGES),
    CALL(RecordKind.CALL, "Calls", Category.CALLS),
    CONTACT(RecordKind.CONTACT, "Contacts", Category.CONTACTS),
    BLOCKED(RecordKind.BLOCKED_NUMBER, "Blocked numbers", Category.BLOCKED);

    companion object {
        fun of(k: RecordKind) = entries.first { it.ffi == k }
    }
}

/**
 * One provider row as graphite carries it: its table and the listed columns that have a value
 * (an integer, a string or bytes), sorted by name. Nulls are left out.
 */
class Row(val table: String, val cols: SortedMap<String, Any>) {
    constructor(table: String, vararg cols: Pair<String, Any?>) :
        this(table, sortedMapOf<String, Any>().apply { for ((k, v) in cols) if (v != null) put(k, v) })

    fun long(c: String): Long? = when (val v = cols[c]) { is Long -> v; is String -> v.toLongOrNull(); else -> null }
    fun str(c: String): String? = when (val v = cols[c]) { is String -> v; is Long -> v.toString(); else -> null }
    fun bytes(c: String): ByteArray? = cols[c] as? ByteArray

    /** The columns as values to insert, without [skip] and keeping only [allowed] (the target's columns). */
    fun values(allowed: Set<String>? = null, skip: Set<String> = emptySet()) = ContentValues().apply {
        for ((k, v) in cols) {
            if (k in skip || (allowed != null && k !in allowed)) continue
            when (v) { is Long -> put(k, v); is String -> put(k, v); is ByteArray -> put(k, v) }
        }
    }

    fun encode(w: BinWriter) {
        w.str(table).u32(cols.size)
        for ((k, v) in cols) {
            w.str(k)
            when (v) {
                is Long -> w.u8(1).i64(v)
                is String -> w.u8(2).str(v)
                is ByteArray -> w.u8(3).bytes(v)
                else -> error("unexpected value for $k")
            }
        }
    }

    fun encoded(): ByteArray = BinWriter().also { encode(it) }.finish()

    override fun equals(other: Any?) = other is Row && encoded().contentEquals(other.encoded())
    override fun hashCode() = encoded().contentHashCode()
    override fun toString() = "$table${cols.mapValues { (_, v) -> if (v is ByteArray) "${v.size} bytes" else v }}"

    companion object {
        fun decode(r: BinReader): Row {
            val table = r.str()
            val cols = sortedMapOf<String, Any>()
            repeat(r.u32().toInt()) {
                val k = r.str()
                cols[k] = when (val t = r.u8()) { 1 -> r.i64(); 2 -> r.str(); 3 -> r.bytes(); else -> error("bad value type $t") }
            }
            return Row(table, cols)
        }

        /** The current row of [c], keeping only the columns in [keep]. Floats are carried as text. */
        fun of(c: Cursor, table: String, keep: Set<String>): Row {
            val m = sortedMapOf<String, Any>()
            for (i in 0 until c.columnCount) {
                val n = c.getColumnName(i)
                if (n !in keep) continue
                when (c.getType(i)) {
                    Cursor.FIELD_TYPE_INTEGER -> m[n] = c.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> m[n] = c.getDouble(i).toString()
                    Cursor.FIELD_TYPE_STRING -> m[n] = c.getString(i)
                    Cursor.FIELD_TYPE_BLOB -> m[n] = c.getBlob(i)
                }
            }
            return Row(table, m)
        }
    }
}

/** A record's payload: its rows in a canonical encoding, the bytes that are sent and hashed. */
object Payload {
    fun encode(rows: List<Row>): ByteArray = BinWriter().apply { u32(rows.size); rows.forEach { it.encode(this) } }.finish()

    fun decode(b: ByteArray): List<Row> = BinReader(b).run {
        val rows = (0 until u32().toInt()).map { Row.decode(this) }
        check(!more()) { "bytes after the payload" }
        rows
    }
}
