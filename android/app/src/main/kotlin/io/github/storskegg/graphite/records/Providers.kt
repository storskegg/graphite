package io.github.storskegg.graphite.records

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.BlockedNumberContract.BlockedNumbers
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import io.github.storskegg.graphite.records.Identity.Companion.ADDR
import io.github.storskegg.graphite.records.Identity.Companion.CONTACT_DATA
import io.github.storskegg.graphite.records.Identity.Companion.DATA_BYTES
import io.github.storskegg.graphite.records.Identity.Companion.MMS
import io.github.storskegg.graphite.records.Identity.Companion.PART
import io.github.storskegg.graphite.records.Identity.Companion.RAW

/** A record as a provider holds it: where it is and its rows, in canonical order. */
class Found(val kind: Kind, val uri: Uri, val rows: List<Row>)

/**
 * Reads and writes the providers step 2 copies (docs/records.md). Only the listed columns are
 * carried: provider IDs, SIM subscriptions and computed columns stay behind. Both phones read
 * with the same code, so a record reads back as the same rows it was written from.
 */
class Providers(c: Context) {
    private val app = c.applicationContext
    private val cr = c.contentResolver

    /** Every record of [kind] on this phone. */
    fun list(kind: Kind): List<Found> = when (kind) {
        Kind.SMS -> each(Telephony.Sms.CONTENT_URI) { id, cur -> Found(kind, uri(kind, id), listOf(Row.of(cur, "sms", SMS_COLS))) }
        Kind.MMS -> mmsDates().keys.mapNotNull { read(kind, it) }
        Kind.CALL -> each(CallLog.Calls.CONTENT_URI) { id, cur -> Found(kind, uri(kind, id), listOf(Row.of(cur, "call", CALL_COLS))) }
        Kind.BLOCKED -> each(BlockedNumbers.CONTENT_URI) { id, cur -> Found(kind, uri(kind, id), listOf(Row.of(cur, "blocked", BLOCKED_COLS))) }
        Kind.CONTACT -> contacts(ContactsContract.Data.CONTACT_ID).map { (id, rows) ->
            Found(kind, ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id), rows)
        }
    }

    /**
     * The phone's own contacts one raw contact at a time (what graphite writes is one raw
     * contact), for the target's comparison and verification next to the joined ones.
     */
    fun rawContacts(): List<Found> = contacts(ContactsContract.Data.RAW_CONTACT_ID).map { (id, rows) ->
        Found(Kind.CONTACT, ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, id), rows)
    }

    /** The MMS on this phone by URI, with their dates in seconds: the target reads parts only where a date matches. */
    fun mmsDates(): Map<Uri, Long> {
        val out = LinkedHashMap<Uri, Long>()
        cr.query(Telephony.Mms.CONTENT_URI, arrayOf("_id", "date"), null, null, "_id")?.use { cur ->
            while (cur.moveToNext()) out[uri(Kind.MMS, cur.getLong(0))] = cur.getLong(1)
        }
        return out
    }

    /** One record again, from its URI; null if it is gone. */
    fun read(kind: Kind, uri: Uri): Found? = when (kind) {
        Kind.SMS -> one(uri) { Row.of(it, "sms", SMS_COLS) }?.let { Found(kind, uri, listOf(it)) }
        Kind.CALL -> one(CallLog.Calls.CONTENT_URI, "_id = ${ContentUris.parseId(uri)}") { Row.of(it, "call", CALL_COLS) }
            ?.let { Found(kind, uri, listOf(it)) }
        Kind.BLOCKED -> one(BlockedNumbers.CONTENT_URI, "_id = ${ContentUris.parseId(uri)}") { Row.of(it, "blocked", BLOCKED_COLS) }
            ?.let { Found(kind, uri, listOf(it)) }
        Kind.MMS -> {
            val id = ContentUris.parseId(uri)
            one(uri) { Row.of(it, MMS, MMS_COLS) }?.let { pdu ->
                val addrs = rows(Uri.parse("content://mms/$id/addr"), null) { Row.of(it, ADDR, ADDR_COLS) }
                val parts = rows(Uri.parse("content://mms/part"), "mid = $id") { cur ->
                    val row = Row.of(cur, PART, PART_COLS)
                    val hasData = !cur.isNull(cur.getColumnIndexOrThrow("_data"))
                    if (!hasData) row else {
                        val partId = cur.getLong(cur.getColumnIndexOrThrow("_id"))
                        val bytes = cr.openInputStream(Uri.parse("content://mms/part/$partId"))?.use { it.readBytes() } ?: ByteArray(0)
                        Row(PART, row.cols.apply { put(DATA_BYTES, bytes) })
                    }
                }
                Found(kind, uri, listOf(pdu) + canonical(addrs) + canonical(parts))
            }
        }
        Kind.CONTACT -> {
            val raw = uri.toString().startsWith(ContactsContract.RawContacts.CONTENT_URI.toString())
            val by = if (raw) ContactsContract.Data.RAW_CONTACT_ID else ContactsContract.Data.CONTACT_ID
            contacts(by, ContentUris.parseId(uri)).firstOrNull()?.let { Found(kind, uri, it.second) }
        }
    }

    /**
     * Writes a record from its rows; its URI and the columns this phone's provider has no
     * place for. The SMS role is needed for messages and blocked numbers.
     */
    fun write(kind: Kind, rows: List<Row>): Pair<Uri, List<String>> = when (kind) {
        Kind.SMS -> insert(Telephony.Sms.CONTENT_URI, rows.first())
        Kind.CALL -> insert(CallLog.Calls.CONTENT_URI, rows.first())
        Kind.BLOCKED -> insert(BlockedNumbers.CONTENT_URI, rows.first())
        Kind.MMS -> writeMms(rows)
        Kind.CONTACT -> writeContact(rows)
    }

    /** Called once an MMS's own row is in, before its parts: until it returns, a crash leaves it half-written. */
    var onMmsStarted: (Uri) -> Unit = {}

    /** Deletes a record graphite wrote (an MMS a crash left without its parts, test data). */
    fun delete(uri: Uri) { cr.delete(uri, null, null) }

    /** Deletes one record; calls and blocked numbers by `_id` (their providers refuse a single-item URI). */
    fun delete(kind: Kind, uri: Uri): Int = when (kind) {
        Kind.CALL -> cr.delete(CallLog.Calls.CONTENT_URI, "_id = ?", arrayOf(ContentUris.parseId(uri).toString()))
        Kind.BLOCKED -> cr.delete(BlockedNumbers.CONTENT_URI, "_id = ?", arrayOf(ContentUris.parseId(uri).toString()))
        else -> cr.delete(uri, null, null)
    }

    private fun insert(table: Uri, row: Row): Pair<Uri, List<String>> {
        val have = columns(table)
        val uri = cr.insert(table, row.values(have)) ?: error("the provider refused the insert")
        return uri to row.cols.keys.filter { it !in have }
    }

    private fun writeMms(rows: List<Row>): Pair<Uri, List<String>> {
        val pdu = rows.first { it.table == MMS }
        val parties = Identity(::ignore).parties(rows).toSet().ifEmpty { setOf(Identity.INSERT_ADDRESS_TOKEN) }
        val thread = Telephony.Threads.getOrCreateThreadId(app, parties)
        val have = columns(Telephony.Mms.CONTENT_URI)
        val uri = cr.insert(Telephony.Mms.CONTENT_URI, pdu.values(have).apply { put("thread_id", thread) })
            ?: error("the provider refused the MMS")
        onMmsStarted(uri)
        val id = ContentUris.parseId(uri)
        try {
            for (a in rows.filter { it.table == ADDR }) {
                cr.insert(Uri.parse("content://mms/$id/addr"), a.values().apply { put("msg_id", id) }) ?: error("address refused")
            }
            for (p in rows.filter { it.table == PART }) {
                val partUri = cr.insert(Uri.parse("content://mms/$id/part"), p.values(skip = setOf(DATA_BYTES)).apply { put("mid", id) })
                    ?: error("part refused")
                p.bytes(DATA_BYTES)?.let { b -> cr.openOutputStream(partUri)!!.use { it.write(b) } }
            }
        } catch (x: Exception) {
            delete(uri)   // its parts and addresses go with it
            throw x
        }
        return uri to pdu.cols.keys.filter { it !in have }
    }

    /** One new raw contact in the phone's own account, with all the data rows. */
    private fun writeContact(rows: List<Row>): Pair<Uri, List<String>> {
        val ops = ArrayList<ContentProviderOperation>()
        val raw = rows.firstOrNull { it.table == RAW }
        ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .withValues(raw?.values() ?: android.content.ContentValues())
            .build()
        for (d in rows.filter { it.table == CONTACT_DATA }) {
            ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValues(d.values())
                .build()
        }
        val result = cr.applyBatch(ContactsContract.AUTHORITY, ops)
        return (result[0].uri ?: error("the contact was refused")) to emptyList()
    }

    private val columnCache = HashMap<Uri, Set<String>>()

    /** The columns this phone's provider has for [table]. */
    private fun columns(table: Uri) = columnCache.getOrPut(table) {
        cr.query(table, null, "0", null, null)?.use { it.columnNames.toSet() } ?: emptySet()
    }

    /** Contacts as (id, rows), grouped by [by]: joined contacts or raw contacts; [only] limits to one id. */
    private fun contacts(by: String, only: Long? = null): List<Pair<Long, List<Row>>> {
        val live = HashSet<Long>()
        cr.query(ContactsContract.RawContacts.CONTENT_URI, arrayOf("_id"), "${ContactsContract.RawContacts.DELETED} = 0", null, null)
            ?.use { while (it.moveToNext()) live += it.getLong(0) }
        val starred = HashMap<Long, Long>()
        val voicemail = HashMap<Long, Long>()
        val data = LinkedHashMap<Long, MutableSet<Row>>()
        val sel = "${ContactsContract.Data.MIMETYPE} IN (${CONTACT_MIMES.joinToString { "'$it'" }})" +
            (only?.let { " AND $by = $it" } ?: "")
        cr.query(ContactsContract.Data.CONTENT_URI, null, sel, null, null)?.use { cur ->
            val key = cur.getColumnIndexOrThrow(by)
            val rawId = cur.getColumnIndexOrThrow(ContactsContract.Data.RAW_CONTACT_ID)
            val star = cur.getColumnIndex(ContactsContract.Contacts.STARRED)
            val vm = cur.getColumnIndex(ContactsContract.Contacts.SEND_TO_VOICEMAIL)
            while (cur.moveToNext()) {
                if (cur.getLong(rawId) !in live) continue
                val k = cur.getLong(key)
                var row = Row.of(cur, CONTACT_DATA, DATA_COLS)
                // A photo's data14 is this phone's own file ID for the full-size picture; the thumbnail in data15 goes.
                if (row.str("mimetype") == PHOTO) row = Row(CONTACT_DATA, row.cols.apply { remove("data14") })
                data.getOrPut(k) { LinkedHashSet() } += row
                if (star >= 0 && cur.getLong(star) != 0L) starred[k] = 1
                if (vm >= 0 && cur.getLong(vm) != 0L) voicemail[k] = 1
            }
        }
        return data.map { (k, rows) ->
            val raw = Row(RAW, ContactsContract.RawContacts.STARRED to starred[k], ContactsContract.RawContacts.SEND_TO_VOICEMAIL to voicemail[k])
            k to (listOf(raw) + canonical(rows))
        }
    }

    private fun <T> each(table: Uri, f: (Long, android.database.Cursor) -> T): List<T> {
        val out = ArrayList<T>()
        cr.query(table, null, null, null, "_id")?.use { cur ->
            val id = cur.getColumnIndexOrThrow("_id")
            while (cur.moveToNext()) out += f(cur.getLong(id), cur)
        }
        return out
    }

    private fun one(uri: Uri, sel: String? = null, f: (android.database.Cursor) -> Row): Row? =
        cr.query(uri, null, sel, null, null)?.use { if (it.moveToFirst()) f(it) else null }

    private fun rows(uri: Uri, sel: String?, f: (android.database.Cursor) -> Row): List<Row> {
        val out = ArrayList<Row>()
        cr.query(uri, null, sel, null, null)?.use { while (it.moveToNext()) out += f(it) }
        return out
    }

    companion object {
        /** Rows in a fixed order (cursor order is not), so a record re-read gives the same payload. */
        fun canonical(rows: Collection<Row>): List<Row> = rows.map { it to it.encoded() }
            .sortedWith { a, b -> compareBytes(a.second, b.second) }.map { it.first }

        /** Unsigned, byte by byte (Arrays.compareUnsigned needs API 33). */
        fun compareBytes(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
                if (d != 0) return d
            }
            return a.size - b.size
        }

        private fun ignore(b: ByteArray) = b

        fun uri(kind: Kind, id: Long): Uri = ContentUris.withAppendedId(when (kind) {
            Kind.SMS -> Telephony.Sms.CONTENT_URI
            Kind.MMS -> Telephony.Mms.CONTENT_URI
            Kind.CALL -> CallLog.Calls.CONTENT_URI
            Kind.BLOCKED -> BlockedNumbers.CONTENT_URI
            Kind.CONTACT -> ContactsContract.Contacts.CONTENT_URI
        }, id)

        val SMS_COLS = setOf("address", "body", "date", "date_sent", "type", "read", "seen", "status", "subject", "locked",
            "protocol", "reply_path_present", "service_center", "error_code")
        val MMS_COLS = setOf("msg_box", "date", "date_sent", "read", "seen", "sub", "sub_cs", "ct_t", "ct_l", "ct_cls", "m_type",
            "m_cls", "m_id", "tr_id", "v", "pri", "rr", "rpt_a", "resp_st", "st", "retr_st", "retr_txt", "retr_txt_cs",
            "read_status", "d_rpt", "d_tm", "exp", "m_size", "locked", "text_only")
        val ADDR_COLS = setOf("address", "type", "charset")
        val PART_COLS = setOf("seq", "ct", "name", "chset", "cd", "fn", "cid", "cl", "ctt_s", "ctt_t", "text")
        val CALL_COLS = setOf("number", "date", "duration", "type", "new", "is_read", "presentation", "features", "countryiso",
            "post_dial_digits", "via_number", "data_usage", "block_reason", "call_screening_app_name",
            "call_screening_component_name", "missed_reason", "priority", "subject")
        val BLOCKED_COLS = setOf("original_number", "e164_number")
        const val PHOTO = "vnd.android.cursor.item/photo"
        val DATA_COLS = setOf("mimetype", "is_primary", "is_super_primary") + (1..15).map { "data$it" }
        /** The standard kinds of contact data; an app's own (a messenger's profile link) stays behind. */
        val CONTACT_MIMES = listOf("name", "phone_v2", "email_v2", "postal-address_v2", "organization", "im", "nickname",
            "note", "website", "contact_event", "relation", "sip_address", "photo").map { "vnd.android.cursor.item/$it" }
    }
}
