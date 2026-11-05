package io.github.storskegg.graphite.records

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import io.github.storskegg.graphite.log
import io.github.storskegg.graphite.role.SmsRole
import java.io.ByteArrayOutputStream

/**
 * Test builds only (`--es test_seed_records 1`, emulator-test.sh): writes clearly marked synthetic
 * records on the old phone, all in the fictional +1 555 010x range or named "GT …". Messages and
 * blocked numbers need graphite to hold the SMS role, which the test gives it with `cmd role`.
 */
object TestSeed {
    const val DAY = 86_400_000L
    /** 2020-01-02 09:04:05.123 UTC. */
    const val T0 = 1577955845123L

    fun run(c: Context) {
        val p = Providers(c)
        val written = HashMap<Kind, Int>()
        fun w(kind: Kind, vararg rows: Row) {
            runCatching { p.write(kind, rows.toList()) }.onSuccess { written.merge(kind, 1, Int::plus) }
                .onFailure { log("test seed: $kind not written: $it") }
        }
        if (SmsRole.held(c)) {
            w(Kind.SMS, sms("+15550100", "GT received, day one", T0, 1))
            w(Kind.SMS, sms("+15550100", "GT sent, day one", T0 + 60_000, 2))
            w(Kind.SMS, sms("+15550101", "GT received, day two", T0 + DAY, 1))
            // The same message twice: copied once.
            w(Kind.SMS, sms("+15550101", "GT saved twice", T0 + 2 * DAY, 1))
            w(Kind.SMS, sms("+15550101", "GT saved twice", T0 + 2 * DAY, 1))
            // Outside the test range: never read by a test run.
            w(Kind.SMS, sms("+15551234", "not test data", T0, 1))
            w(Kind.MMS, Row(Identity.MMS, "msg_box" to 1L, "date" to (T0 + 3 * DAY) / 1000, "date_sent" to (T0 + 3 * DAY) / 1000 - 30,
                "read" to 1L, "seen" to 1L, "m_type" to 132L, "v" to 18L, "ct_t" to "application/vnd.wap.multipart.related",
                "sub" to "GT picture", "sub_cs" to 106L, "m_id" to "graphite-test-1", "pri" to 129L),
                Row(Identity.ADDR, "address" to "+15550100", "type" to 137L, "charset" to 106L),
                Row(Identity.ADDR, "address" to Identity.INSERT_ADDRESS_TOKEN, "type" to 151L, "charset" to 106L),
                Row(Identity.PART, "seq" to -1L, "ct" to "application/smil", "cid" to "<smil>", "cl" to "smil.xml",
                    "text" to "<smil><body><par><img src=\"image.png\"/><text src=\"text.txt\"/></par></body></smil>"),
                Row(Identity.PART, "seq" to 0L, "ct" to "image/png", "cid" to "<image.png>", "cl" to "image.png", "name" to "image.png",
                    Identity.DATA_BYTES to png()),
                Row(Identity.PART, "seq" to 0L, "ct" to "text/plain", "chset" to 106L, "cid" to "<text.txt>", "cl" to "text.txt",
                    "text" to "GT a green square"))
            w(Kind.BLOCKED, Row("blocked", "original_number" to "+15550109"))
            w(Kind.BLOCKED, Row("blocked", "original_number" to "+15550108"))
        } else log("test seed: graphite does not hold the SMS role, so no messages or blocked numbers")
        for ((n, type) in listOf(1L, 2L, 3L).withIndex()) {
            w(Kind.CALL, Row("call", "number" to "+1555010$n", "date" to T0 + n * DAY, "duration" to 60L * n, "type" to type,
                "new" to 0L, "is_read" to 1L, "presentation" to 1L))
        }
        w(Kind.CONTACT, Row(Identity.RAW, "starred" to 1L),
            Row(Identity.CONTACT_DATA, "mimetype" to Identity.NAME, "data1" to "GT Jane Doe", "data2" to "GT Jane", "data3" to "Doe"),
            Row(Identity.CONTACT_DATA, "mimetype" to Identity.PHONE, "data1" to "+1 555 0100", "data2" to 2L),
            Row(Identity.CONTACT_DATA, "mimetype" to Identity.EMAIL, "data1" to "jane@example.org", "data2" to 1L),
            Row(Identity.CONTACT_DATA, "mimetype" to Providers.PHOTO, "data15" to png()))
        w(Kind.CONTACT, Row(Identity.RAW),
            Row(Identity.CONTACT_DATA, "mimetype" to Identity.NAME, "data1" to "GT Bob"),
            Row(Identity.CONTACT_DATA, "mimetype" to Identity.PHONE, "data1" to "+15550101", "data2" to 1L),
            Row(Identity.CONTACT_DATA, "mimetype" to "vnd.android.cursor.item/note", "data1" to "GT note"))
        log("test seed: wrote $written")
    }

    /**
     * Test builds only (`--es test_clear_records 1`): deletes this phone's test records, those
     * [Identity.inTestScope] accepts, and nothing else. Messages and blocked numbers need the SMS role.
     */
    fun clear(c: Context) {
        val p = Providers(c)
        val identity = Identity { io.github.storskegg.graphite.ffi.blake3Hash(it) }
        val deleted = HashMap<Kind, Int>()
        for (kind in Kind.entries) {
            if ((kind == Kind.SMS || kind == Kind.MMS || kind == Kind.BLOCKED) && !SmsRole.held(c)) continue
            val found = runCatching { p.list(kind) }.getOrElse { log("test clear: can't list $kind: $it"); emptyList() }
            for (f in found) if (identity.inTestScope(kind, f.rows)) {
                runCatching { p.delete(kind, f.uri) }.onSuccess { if (it > 0) deleted.merge(kind, it, Int::plus) }
                    .onFailure { log("test clear: ${f.uri}: $it") }
            }
        }
        log("test clear: deleted $deleted")
    }

    private fun sms(address: String, body: String, date: Long, type: Long) =
        Row("sms", "address" to address, "body" to body, "date" to date, "date_sent" to date - 5000, "type" to type, "read" to 1L, "seen" to 1L)

    private fun png(): ByteArray {
        val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20, 160, 90)) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}
