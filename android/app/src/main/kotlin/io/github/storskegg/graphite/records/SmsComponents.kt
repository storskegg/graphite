package io.github.storskegg.graphite.records

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import android.widget.Toast
import io.github.storskegg.graphite.log

/*
 * What Android requires of a default SMS app. graphite holds the role only for the minute it
 * writes messages on the new phone; these components keep anything that arrives meanwhile.
 */

/** An SMS that arrives while graphite holds the role is saved to the inbox, as any SMS app would. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(i) ?: return
        val first = parts.firstOrNull() ?: return
        val v = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, first.displayOriginatingAddress)
            put(Telephony.Sms.BODY, parts.joinToString("") { it.displayMessageBody ?: "" })
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.DATE_SENT, first.timestampMillis)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
        }
        val uri = runCatching { c.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, v) }.getOrNull()
        log("an SMS arrived while graphite held the SMS role: saved to the inbox as $uri")
        Arrivals.note(c, "sms")
    }
}

/**
 * An MMS notification. graphite does not download MMS (it would need the carrier's MMS settings);
 * it counts it so the new phone can tell the user to ask for it again.
 */
class WapPushReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        log("an MMS notification arrived while graphite held the SMS role: not downloaded")
        Arrivals.note(c, "mms")
    }
}

/** "Reply with a message" from an incoming call: graphite sends nothing. */
class RespondService : Service() {
    override fun onBind(i: Intent?) = null
    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int { stopSelf(id); return START_NOT_STICKY }
}

/** "Send a message to…" from another app: graphite is not a messaging app. */
class ComposeActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Toast.makeText(this, "graphite is the SMS app only while it copies messages. Try again in a minute.", Toast.LENGTH_LONG).show()
        finish()
    }
}

/** What arrived while graphite held the role, counted until the transfer reports it. */
object Arrivals {
    private fun prefs(c: Context) = c.getSharedPreferences("arrivals", Context.MODE_PRIVATE)
    fun note(c: Context, kind: String) = prefs(c).edit().putInt(kind, prefs(c).getInt(kind, 0) + 1).apply()
    fun take(c: Context): Pair<Int, Int> = prefs(c).let { p -> (p.getInt("sms", 0) to p.getInt("mms", 0)).also { p.edit().clear().apply() } }
}
