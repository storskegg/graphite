package io.github.storskegg.graphite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.storskegg.graphite.auth.AuthGate
import io.github.storskegg.graphite.role.AppRole
import io.github.storskegg.graphite.role.RoleResult
import io.github.storskegg.graphite.role.UnattestedRoleCheck
import io.github.storskegg.graphite.ui.Answer
import io.github.storskegg.graphite.ui.Screen
import io.github.storskegg.graphite.ui.Status
import io.github.storskegg.graphite.usb.LinkSpeed
import java.util.concurrent.CompletableFuture

const val TAG = "graphite"

fun log(s: String) { Log.i(TAG, s) }

/** Thrown into the flow thread when the user stops the session. */
class StoppedException(val why: String = "") : Exception("stopped")

/**
 * The app's state for the life of the process: the user-authentication gate,
 * this phone's role, what the screen shows, and the session's worker thread.
 * The activity draws [screen] and [status] and hands back answers; the flows
 * block on [ask] for them.
 */
class Controller(val app: Context) {
    val gate = AuthGate(app)
    private val checkedRole: RoleResult = UnattestedRoleCheck.check(app)

    /** This phone's role; test builds can force it with `test_role` (for the emulator harness). */
    val role: RoleResult get() = when (if (debuggable) prefs.getString("test_role", null) else null) {
        "target" -> RoleResult(AppRole.TARGET, false, "TEST: forced to be the new phone (test_role).")
        "source" -> RoleResult(AppRole.SOURCE, false, "TEST: forced to be the old phone (test_role).")
        else -> checkedRole
    }
    val usbManager: UsbManager = app.getSystemService(UsbManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    // Declared before init, which reads it through updateStatus().
    private val prefs = app.getSharedPreferences("graphite", Context.MODE_PRIVATE)

    @Volatile var screen = Screen("graphite")
        private set
    @Volatile var status = Status()
        private set
    private var pending: CompletableFuture<Answer>? = null
    private val listeners = mutableListOf<() -> Unit>()
    @Volatile var linkSpeed: LinkSpeed? = null
    private var worker: Thread? = null

    init {
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = gate.invalidate("the screen turned off")
        }, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        gate.onChange { updateStatus() }
        updateStatus()
    }

    fun onChange(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun changed() = main.post { synchronized(listeners) { listeners.toList() }.forEach { it() } }

    fun updateStatus(usb: String? = null) {
        val was = status
        status = Status(
            role = role.explanation + (testScope?.let { "\nTEST SCOPE: only folders named \"$it\"." } ?: ""),
            usb = usb ?: status.usb,
            auth = if (gate.isValid()) "You confirmed it's you." else "Not confirmed: graphite will ask before pairing or touching files.",
        )
        // The USB keeper calls this twice a second; redraw only on a change.
        if (status != was) changed()
    }

    /** Shows a screen without waiting for an answer. */
    fun show(s: Screen) {
        screen = s
        changed()
    }

    /** Shows a question and blocks the flow thread until the user answers it. */
    fun ask(s: Screen): Answer {
        val f = CompletableFuture<Answer>()
        synchronized(this) { pending?.cancel(false); pending = f }
        show(s)
        return try { f.get() } catch (e: java.util.concurrent.CancellationException) { throw StoppedException() }
    }

    /** From the activity: a button was tapped. */
    fun answer(a: Answer) {
        val f = synchronized(this) { pending.also { pending = null } } ?: return
        f.complete(a)
    }

    /**
     * Blocks until the user has authenticated since the last lock or leaving
     * graphite. With [fresh], always asks again (removal from the source).
     */
    fun requireAuth(reason: String, fresh: Boolean = false) {
        if (!gate.deviceSecure) {
            ask(Screen("A screen lock is needed",
                "graphite only works on a phone with a PIN, pattern or password, so that someone holding " +
                    "your unlocked phone cannot pull files off it or push files onto it. Set a screen lock " +
                    "in Settings > Security, then come back.", buttons = listOf("I've set one")))
            return requireAuth(reason, fresh)
        }
        if (fresh) gate.invalidate("asked again before $reason")
        while (!gate.isValid()) {
            ask(Screen("Confirm it's you", "graphite needs you to unlock it before it $reason.",
                buttons = listOf("Confirm it's you"), authReason = reason))
        }
        updateStatus()
    }

    val isTarget get() = role.role == AppRole.TARGET

    /**
     * Test builds only: limits the source's scope to folders with this name,
     * so a test run never reads or copies the user's own files. Set with
     * `adb shell am start -n io.github.storskegg.graphite/.MainActivity --es test_scope graphite-test`
     * (an empty value clears it). Ignored unless the app is debuggable.
     */
    var testScope: String?
        get() = if (debuggable) prefs.getString("test_scope", null)?.ifEmpty { null } else null
        set(v) { prefs.edit().putString("test_scope", v).apply(); updateStatus() }

    /** Test builds only: "target" or "source", overriding the role check (emulators). */
    var testRole: String?
        get() = if (debuggable) prefs.getString("test_role", null)?.ifEmpty { null } else null
        set(v) { prefs.edit().putString("test_role", v).apply() }

    /**
     * Test builds only: the link over TCP instead of USB. The target listens on
     * "listen:PORT", the source connects to "HOST:PORT" (see emulator-test.sh).
     */
    var testTcp: String?
        get() = if (debuggable && io.github.storskegg.graphite.usb.TestTransport.AVAILABLE) prefs.getString("test_tcp", null)?.ifEmpty { null } else null
        set(v) { prefs.edit().putString("test_tcp", v).apply() }

    /** Test builds only: the target's free space as preflight sees it, to test the space check. */
    var testFreeBytes: Long?
        get() = if (debuggable) prefs.getString("test_free_bytes", null)?.toLongOrNull() else null
        set(v) { prefs.edit().putString("test_free_bytes", v?.toString()).apply() }

    val debuggable get() = (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Starts the session's flow once; later calls do nothing while it runs. */
    @Synchronized
    fun start() {
        if (worker?.isAlive == true) return
        worker = Thread({
            // A session that ends with Done starts the next one: back to the start, ready to pair again.
            Sessions.loop {
                try {
                    Flows(this).run()
                    true
                } catch (e: StoppedException) {
                    log("stopped: ${e.why}")
                    // A stop ends this session, not graphite: it waits to start again.
                    ask(Screen("Stopped", e.why, buttons = listOf("Start again")))
                    true
                } catch (t: Throwable) {
                    Log.e(TAG, "flow failed", t)
                    ask(Screen("Something went wrong", t.message ?: t.toString(), buttons = listOf("Start again")))
                    true
                } finally {
                    TransferService.stop(app)
                }
            }
        }, "graphite-flow").apply { start() }
    }
}
