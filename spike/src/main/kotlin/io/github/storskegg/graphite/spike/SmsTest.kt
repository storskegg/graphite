package io.github.storskegg.graphite.spike

import android.app.IntentService
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.CallLog
import android.provider.Telephony
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File

// SMS, MMS and call-log writes on the target. Every row uses the fake number +1 555 0100 and a
// "GRAPHITE SPIKE TEST" body; every inserted URI is appended to files/spike-ids.txt for cleanup.
const val FAKE = "+15550100"
const val MARK = "GRAPHITE SPIKE TEST — safe to delete"

class SmsTest(val a: MainActivity) {
    val cr get() = a.contentResolver
    val ids = File(a.filesDir, "spike-ids.txt")
    val day = 86_400_000L
    val now = System.currentTimeMillis()

    fun record(uri: Uri?, what: String): Uri? {
        a.log("$what -> $uri")
        if (uri != null) ids.appendText("$uri\n")
        return uri
    }

    fun role() {
        val rm = a.getSystemService(RoleManager::class.java)
        a.log("SMS role available=${rm.isRoleAvailable(RoleManager.ROLE_SMS)} held=${rm.isRoleHeld(RoleManager.ROLE_SMS)} default=${Telephony.Sms.getDefaultSmsPackage(a)}")
    }

    fun requestRole() {
        val rm = a.getSystemService(RoleManager::class.java)
        a.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_SMS), 42)
    }

    fun show(uri: Uri, cols: Array<String>) = cr.query(uri, cols, null, null, null)?.use { c ->
        if (!c.moveToFirst()) return@use a.log("  $uri: not visible")
        a.log("  $uri: " + cols.indices.joinToString(" ") { "${cols[it]}=${c.getString(it)}" })
    }

    val smsCols = arrayOf("_id", "thread_id", "address", "type", "date", "date_sent", "read", "seen", "body")

    fun sms() {
        role()
        // Three received and two sent, interleaved, with dates in 2020, so thread order is checkable.
        val base = 1577934245000L  // 2020-01-02T03:04:05Z
        val plan = listOf(
            Triple(Telephony.Sms.Inbox.CONTENT_URI, 0L, "1 of 5 (received)"),
            Triple(Telephony.Sms.Sent.CONTENT_URI, 60_000L, "2 of 5 (sent)"),
            Triple(Telephony.Sms.Inbox.CONTENT_URI, 120_000L, "3 of 5 (received)"),
            Triple(Telephony.Sms.Sent.CONTENT_URI, day, "4 of 5 (sent, next day)"),
            Triple(Telephony.Sms.Inbox.CONTENT_URI, 2 * day, "5 of 5 (received, two days on)"))
        for ((box, off, label) in plan) {
            val v = ContentValues().apply {
                put("address", FAKE)
                put("body", "$MARK — $label")
                put("date", base + off)
                put("date_sent", base + off - 30_000)
                put("read", 1); put("seen", 1)
            }
            val u = record(runCatching { cr.insert(box, v) }.getOrElse { a.log("insert: $it"); null }, "sms $label") ?: continue
            show(u, smsCols)
        }
    }

    fun png(): ByteArray {
        val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20, 160, 90)) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    fun mms() {
        role()
        val thread = Telephony.Threads.getOrCreateThreadId(a, FAKE)
        val dateS = 1578020645L  // 2020-01-03T03:04:05Z; MMS dates are in seconds
        val v = ContentValues().apply {
            put("thread_id", thread)
            put("msg_box", Telephony.Mms.MESSAGE_BOX_INBOX)
            put("date", dateS); put("date_sent", dateS - 30)
            put("read", 1); put("seen", 1)
            put("m_type", 132)  // m-retrieve-conf
            put("v", 18)
            put("ct_t", "application/vnd.wap.multipart.related")
            put("sub", "GRAPHITE SPIKE TEST"); put("sub_cs", 106)
            put("m_id", "graphite-spike-${System.currentTimeMillis()}")
            put("pri", 129); put("rr", 129); put("d_rpt", 129)
        }
        val u = record(cr.insert(Telephony.Mms.CONTENT_URI, v), "mms (thread $thread)") ?: return
        val id = u.lastPathSegment!!
        val partUri = Uri.parse("content://mms/$id/part")
        val smil = "<smil><head><layout><root-layout/><region id=\"Image\" top=\"0\" left=\"0\" height=\"80%\" width=\"100%\"/>" +
            "<region id=\"Text\" top=\"80%\" left=\"0\" height=\"20%\" width=\"100%\"/></layout></head><body><par dur=\"5000ms\">" +
            "<img src=\"image.png\" region=\"Image\"/><text src=\"text.txt\" region=\"Text\"/></par></body></smil>"
        record(cr.insert(partUri, ContentValues().apply {
            put("mid", id); put("seq", -1); put("ct", "application/smil"); put("cid", "<smil>"); put("cl", "smil.xml"); put("text", smil)
        }), "mms smil part")
        val img = record(cr.insert(partUri, ContentValues().apply {
            put("mid", id); put("seq", 0); put("ct", "image/png"); put("cid", "<image.png>"); put("cl", "image.png"); put("name", "image.png")
        }), "mms image part")
        if (img != null) cr.openOutputStream(img)!!.use { it.write(png()) }
        record(cr.insert(partUri, ContentValues().apply {
            put("mid", id); put("seq", 0); put("ct", "text/plain"); put("chset", 106); put("cid", "<text.txt>"); put("cl", "text.txt")
            put("text", "$MARK — MMS with a green square")
        }), "mms text part")
        val addr = Uri.parse("content://mms/$id/addr")
        record(cr.insert(addr, ContentValues().apply { put("msg_id", id); put("address", FAKE); put("type", 137); put("charset", 106) }), "mms from addr")
        record(cr.insert(addr, ContentValues().apply { put("msg_id", id); put("address", "insert-address-token"); put("type", 151); put("charset", 106) }), "mms to addr")
        show(u, arrayOf("_id", "thread_id", "msg_box", "date", "date_sent", "m_type", "sub"))
        cr.query(partUri, arrayOf("_id", "ct", "_data", "text"), null, null, null)?.use { c ->
            while (c.moveToNext()) a.log("  part ${c.getString(0)} ${c.getString(1)} data=${c.getString(2)} text=${c.getString(3)?.take(40)}")
        }
        a.log("thread $thread messages:")
        cr.query(Uri.parse("content://mms-sms/conversations/$thread"), arrayOf("_id", "transport_type", "date"), null, null, "normalized_date")?.use { c ->
            while (c.moveToNext()) a.log("  ${c.getString(1)} ${c.getString(0)} date=${c.getString(2)}")
        }
    }

    val callCols = arrayOf("_id", "number", "type", "date", "duration", "new", "is_read", "presentation", "countryiso",
        "geocoded_location", "features", "data_usage", "via_number", "subscription_component_name", "subscription_id",
        "last_modified", "name", "numbertype", "transcription", "call_screening_app_name", "priority", "subject")

    fun calls() {
        // A few days ago, so they show near the top of Recents.
        for ((type, off, dur, label) in listOf(
            listOf(CallLog.Calls.INCOMING_TYPE, 2 * day, 65, "incoming"),
            listOf(CallLog.Calls.OUTGOING_TYPE, 3 * day, 300, "outgoing"),
            listOf(CallLog.Calls.MISSED_TYPE, 4 * day, 0, "missed"))) {
            val v = ContentValues().apply {
                put("number", FAKE); put("type", type as Int); put("date", now - (off as Long)); put("duration", dur as Int)
                put("new", if (type == CallLog.Calls.MISSED_TYPE) 1 else 0); put("is_read", 1)
                put("presentation", CallLog.Calls.PRESENTATION_ALLOWED)
                put("countryiso", "US"); put("geocoded_location", "GRAPHITE SPIKE TEST"); put("features", 0)
                put("data_usage", 1234); put("last_modified", 1500000000000L); put("name", "GRAPHITE SPIKE TEST")
                put("subject", MARK)
            }
            val u = record(runCatching { cr.insert(CallLog.Calls.CONTENT_URI, v) }.getOrElse { a.log("insert: $it"); null }, "call $label") ?: continue
            show(u, callCols)
        }
    }

    // Counts only: never logs the user's blocked numbers.
    fun blocked() {
        role()
        a.log("canCurrentUserBlockNumbers=${runCatching { android.provider.BlockedNumberContract.canCurrentUserBlockNumbers(a) }.getOrElse { it.toString() }}")
        runCatching {
            cr.query(android.provider.BlockedNumberContract.BlockedNumbers.CONTENT_URI, null, null, null, null)?.use {
                a.log("blocked list: ${it.count} rows, columns ${it.columnNames.joinToString()}")
            } ?: a.log("blocked list: null cursor")
        }.onFailure { a.log("blocked list: $it") }
    }

    // Deletes exactly the recorded rows, and only if they still carry the fake number.
    fun cleanup() {
        role()
        if (!ids.exists()) return a.log("nothing recorded")
        for (line in ids.readLines().reversed()) {
            val u = Uri.parse(line)
            val n = runCatching { cr.delete(u, null, null) }.getOrElse { a.log("delete $u: $it"); -1 }
            a.log("delete $u -> $n")
        }
        ids.renameTo(File(a.filesDir, "spike-ids.done.txt"))
    }
}

// Holding the SMS role means Android hands incoming SMS to us instead of Messages: write each one
// to the inbox exactly as a normal SMS app would, so nothing is lost while the spike holds it.
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        for (m in Telephony.Sms.Intents.getMessagesFromIntent(i) ?: return) {
            val v = ContentValues().apply {
                put("address", m.displayOriginatingAddress); put("body", m.displayMessageBody)
                put("date", System.currentTimeMillis()); put("date_sent", m.timestampMillis); put("read", 0); put("seen", 0)
                put("subscription_id", i.getIntExtra("subscription", -1))
            }
            val u = c.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, v)
            Log.i("GSPIKE", "SMS_DELIVER from ${m.displayOriginatingAddress} (${m.displayMessageBody.length} chars) saved as $u")
            File(c.filesDir, "spike.log").appendText("SMS_DELIVER saved as $u\n")
        }
    }
}

class WapPushDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Log.w("GSPIKE", "WAP_PUSH_DELIVER (MMS notification) received while the spike holds the role: not downloaded")
        File(c.filesDir, "spike.log").appendText("WAP_PUSH_DELIVER received, not downloaded\n")
    }
}

@Suppress("DEPRECATION")
class RespondViaMessageService : IntentService("respond") {
    override fun onHandleIntent(i: Intent?) { Log.i("GSPIKE", "RESPOND_VIA_MESSAGE ignored") }
}
