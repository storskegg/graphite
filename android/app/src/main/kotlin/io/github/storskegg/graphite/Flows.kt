package io.github.storskegg.graphite

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import io.github.storskegg.graphite.ffi.Session
import io.github.storskegg.graphite.ffi.SourcePairing
import io.github.storskegg.graphite.ffi.TargetPairing
import io.github.storskegg.graphite.link.LinkResetException
import io.github.storskegg.graphite.media.Battery
import io.github.storskegg.graphite.media.SourceMedia
import io.github.storskegg.graphite.media.TargetMedia
import io.github.storskegg.graphite.role.AppRole
import io.github.storskegg.graphite.session.Caps
import io.github.storskegg.graphite.session.Channel
import io.github.storskegg.graphite.session.Msg
import io.github.storskegg.graphite.session.PeerAbortException
import io.github.storskegg.graphite.session.SessionCipher
import io.github.storskegg.graphite.session.receivePair
import io.github.storskegg.graphite.session.sendAbort
import io.github.storskegg.graphite.session.sendPair
import io.github.storskegg.graphite.ui.Screen
import io.github.storskegg.graphite.usb.CableSetup
import io.github.storskegg.graphite.usb.Connection
import io.github.storskegg.graphite.util.BinReader
import io.github.storskegg.graphite.util.BinWriter
import java.io.IOException

/** A paired, accepted session: the channel and what the other phone said it is. */
class Paired(val session: Session, val channel: Channel, val peer: Caps)

/**
 * The session from the first screen to the end: authenticate, connect,
 * pair with the six digits, exchange capabilities, then the transfer.
 */
class Flows(private val ctl: Controller) {
    private val role = ctl.role.role

    fun run() {
        if (role == null) {
            ctl.ask(Screen("graphite can't run here", ctl.role.explanation))
            return
        }
        ctl.requireAuth("connects to the other phone over the cable")
        val conn = Connection(ctl)
        while (true) {
            conn.awaitUp({ ctl.show(Screen("Connecting", it, progress = io.github.storskegg.graphite.ui.Progress(0, 0, ""))) }) { dev ->
                CableSetup(ctl, conn).offer(dev)
            }
            if (conn.link.peerRole == role.wire) {
                val both = if (role == AppRole.TARGET) "new phones (GrapheneOS)" else "old phones (not GrapheneOS)"
                conn.close("both phones are $both")
                ctl.ask(Screen("Both phones are $both",
                    "graphite copies from an old phone to a new GrapheneOS phone. Connect one of each."))
                return
            }
            TransferService.start(ctl.app, "Connected to the other phone")
            try {
                val paired = if (role == AppRole.TARGET) pairTarget(conn) else pairSource(conn)
                if (role == AppRole.TARGET) TargetMedia(ctl, paired).run()
                else SourceMedia(ctl, paired).run()
                conn.close("finished")
                return
            } catch (_: LinkResetException) {
                log("the other phone started over: pairing again")
                ctl.show(Screen("The other phone started over", "Pairing again…"))
            } catch (e: PeerAbortException) {
                conn.close("aborted")
                ctl.ask(Screen("Stopped", e.message ?: ""))
                return
            }
        }
    }

    private fun pairTarget(conn: Connection): Paired {
        val link = conn.link
        ctl.requireAuth("pairs with the other phone")
        ctl.show(Screen("Pairing", "Exchanging keys over the cable…"))
        val p = TargetPairing()
        link.sendPair(p.hello())
        while (true) {
            val reply = p.handle(link.receivePair(PAIR_TIMEOUT)) ?: break
            link.sendPair(reply)
        }
        return confirm(conn, p.code()!!, { p.confirmCode() }, { p.finish() })
    }

    private fun pairSource(conn: Connection): Paired {
        val link = conn.link
        ctl.requireAuth("pairs with the other phone")
        ctl.show(Screen("Pairing", "Waiting for the new phone…"))
        val p = SourcePairing()
        // The source answers hello, share and the target's encapsulation; its third reply ends the handshake.
        repeat(3) {
            val reply = p.handle(link.receivePair(PAIR_TIMEOUT)) ?: throw IOException("pairing ended early")
            link.sendPair(reply)
        }
        return confirm(conn, p.code()!!, { p.confirmCode() }, { p.finish() })
    }

    private fun confirm(conn: Connection, code: String, confirmCode: () -> Unit, finish: () -> Session): Paired {
        val link = conn.link
        val a = ctl.ask(Screen("Compare the codes", "Check that the other phone shows exactly the same six digits. " +
            "If they differ, something in the cable is in the middle: do not continue.",
            code = code, buttons = listOf(MATCH, DIFFERENT)))
        if (a.button != MATCH) {
            link.sendAbort("the six digits did not match on the ${if (ctl.isTarget) "new" else "old"} phone")
            link.flush(2000)
            conn.close("codes differ")
            ctl.ask(Screen("Pairing stopped", "The codes did not match, so nothing was sent. Check the cable " +
                "(no hubs or adapters you don't trust) and start again."))
            throw StoppedException()
        }
        ctl.requireAuth("finishes pairing")
        confirmCode()
        val session = finish()
        val channel = Channel(link, SessionCipher(session))
        channel.send(Msg.CONFIRMED, ByteArray(0))
        ctl.show(Screen("Waiting for the other phone", "Confirm the code on the other phone too."))
        expect(channel, Msg.CONFIRMED)

        channel.send(Msg.CAPS, caps(conn).encode())
        val peer = Caps.decode(expect(channel, Msg.CAPS).body)
        log("paired with ${peer.name}; transcript ${session.transcriptHash().take(4)}")
        if (ctl.isTarget) {
            val a2 = ctl.ask(Screen("Accept the old phone?",
                "Paired with ${peer.name}. Accept it as the phone to copy from? Nothing has been copied yet.",
                buttons = listOf("Accept", "Refuse")))
            if (a2.button != "Accept") {
                channel.send(Msg.ERROR, BinWriter().str("the new phone refused this phone").finish())
                throw StoppedException()
            }
            channel.send(Msg.ACCEPTED, ByteArray(0))
        } else {
            ctl.show(Screen("Waiting for the new phone", "Paired with ${peer.name}. Accept this phone on the new phone."))
            expect(channel, Msg.ACCEPTED)
        }
        return Paired(session, channel, peer)
    }

    private fun caps(conn: Connection): Caps {
        val (level, charging) = battery(ctl.app)
        return Caps(
            role = role!!.wire, sdk = Build.VERSION.SDK_INT, manufacturer = Build.MANUFACTURER, model = Build.MODEL,
            release = Build.VERSION.RELEASE, appVersion = BuildConfigVersion.name(ctl.app),
            roleAttested = ctl.role.attested, batteryPercent = level, charging = charging,
            allocatableBytes = if (ctl.isTarget) allocatableBytes(ctl.app) else 0,
            usb = ctl.linkSpeed?.toString() ?: "", allFilesAccess = Environment.isExternalStorageManager(),
        )
    }

    companion object {
        const val MATCH = "They match"
        const val DIFFERENT = "They're different"
        const val PAIR_TIMEOUT = 120_000L

        /** The next message, which must be [type]; an ERROR from the other phone stops the session. */
        fun expect(channel: Channel, type: Int): io.github.storskegg.graphite.session.Message {
            val m = channel.receive()
            if (m.type == Msg.ERROR) throw PeerAbortException(BinReader(m.body).str())
            if (m.type != type) throw IOException("expected message 0x%02x, got 0x%02x".format(type, m.type))
            return m
        }

        fun battery(c: Context): Battery {
            val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = b?.let {
                it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / maxOf(1, it.getIntExtra(BatteryManager.EXTRA_SCALE, 100))
            } ?: 0
            // Plugged in counts as charging: the host powers the other phone, and a full phone stops charging.
            val plugged = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            return Battery(level, plugged)
        }

        fun allocatableBytes(c: Context): Long {
            val sm = c.getSystemService(StorageManager::class.java)
            return sm.getAllocatableBytes(sm.getUuidForPath(Environment.getExternalStorageDirectory()))
        }
    }
}

object BuildConfigVersion {
    fun name(c: Context): String = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
}
