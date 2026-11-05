package io.github.storskegg.graphite.records

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import io.github.storskegg.graphite.Controller
import io.github.storskegg.graphite.MainActivity
import io.github.storskegg.graphite.log
import io.github.storskegg.graphite.role.SmsRole
import io.github.storskegg.graphite.ui.Screen

/** The permissions and the SMS role step 2 needs (docs/records.md). */
object RecordAccess {
    fun read(c: Category) = when (c) {
        Category.MESSAGES -> listOf(android.Manifest.permission.READ_SMS)
        Category.CALLS -> listOf(android.Manifest.permission.READ_CALL_LOG)
        Category.CONTACTS -> listOf(android.Manifest.permission.READ_CONTACTS)
        Category.BLOCKED -> emptyList()
    }

    fun write(c: Category) = when (c) {
        Category.MESSAGES, Category.BLOCKED -> emptyList()   // the SMS role
        Category.CALLS -> listOf(android.Manifest.permission.READ_CALL_LOG, android.Manifest.permission.WRITE_CALL_LOG)
        Category.CONTACTS -> listOf(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS)
    }

    fun has(c: Context, perms: List<String>) = perms.all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /**
     * Asks once with Android's prompts for what [cats] need; the categories it can then use.
     * SMS and call-log permissions can only be granted to an app installed the adb way.
     */
    fun request(ctl: Controller, cats: Set<Category>, need: (Category) -> List<String>): Set<Category> {
        val missing = cats.flatMap(need).distinct().filter { ctl.app.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            val activity = MainActivity.current
            if (activity != null) runCatching { activity.requestPermissionsFor(missing).get() }
        }
        return cats.filter { has(ctl.app, need(it)) }.toSet()
    }
}

/**
 * graphite as the default SMS app for as short a time as it can (docs/records.md, "The SMS role").
 * Taking it is one tap in Android's dialog; giving it back is done by the previous app, which
 * graphite opens. Which app held it is kept in preferences, so a crash still ends with it given back.
 */
class RoleWindow(private val ctl: Controller) {
    private val prefs = ctl.app.getSharedPreferences("sms_role", Context.MODE_PRIVATE)

    /** Takes the role; false if the user declined. [why] finishes "graphite needs to be your SMS app…". */
    fun take(why: String): Boolean {
        if (SmsRole.held(ctl.app)) return true
        val previous = SmsRole.holder(ctl.app)
        var note = ""
        while (true) {
            val a = ctl.ask(Screen("graphite needs to be the SMS app for a moment",
                "Only the default SMS app can $why. Android asks next: choose graphite. When graphite is done, " +
                    "it opens ${name(previous)} so you can switch back." + note +
                    "\n\nAn SMS that arrives meanwhile is kept and shows in ${name(previous)} afterwards. An MMS is " +
                    "not downloaded while graphite is the SMS app.",
                buttons = listOf("Continue", "Skip")))
            if (a.button != "Continue") return false
            prefs.edit().putString("previous", previous ?: "").apply()
            MainActivity.current?.launchForResult(SmsRole.requestIntent(ctl.app))?.get()
            if (SmsRole.held(ctl.app)) { log("took the SMS role from $previous"); return true }
            note = "\n\ngraphite is not the SMS app yet: choose graphite in Android's dialog."
        }
    }

    /** Gives the role back to the app that had it, with the user, and checks it went. */
    fun giveBack() {
        if (!SmsRole.held(ctl.app)) { prefs.edit().clear().apply(); return }
        val previous = prefs.getString("previous", null)?.ifEmpty { null }
        val (sms, mms) = Arrivals.take(ctl.app)
        val arrived = (if (sms > 0) "\n\n$sms SMS arrived meanwhile: they are in ${name(previous)}'s inbox." else "") +
            (if (mms > 0) "\n\n$mms MMS arrived meanwhile and were not downloaded: ask the senders to send them again." else "")
        var note = ""
        while (SmsRole.held(ctl.app)) {
            val a = ctl.ask(Screen("Give the SMS role back",
                "graphite is done with messages. Switch back to ${name(previous)} as your SMS app: " +
                    (if (previous != null) "graphite opens it, and it asks to be the default again (\"Set default chat app\", then " +
                        "\"Set as default\"). " else "") +
                    "Or choose it in Settings, Default apps, SMS app." + arrived + note,
                buttons = listOfNotNull(previous?.let { "Open ${name(it)}" }, "Open Settings", "Check again")))
            val activity = MainActivity.current
            when {
                a.button.startsWith("Open ") && a.button != "Open Settings" ->
                    previous?.let { ctl.app.packageManager.getLaunchIntentForPackage(it) }?.let { activity?.startActivity(it) }
                a.button == "Open Settings" -> activity?.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
            note = "\n\ngraphite is still the SMS app."
        }
        log("gave the SMS role back; holder now ${SmsRole.holder(ctl.app)}")
        prefs.edit().clear().apply()
    }

    /** After a crash: graphite still holds the role it borrowed. */
    fun leftOver() = SmsRole.held(ctl.app) && prefs.contains("previous")

    private fun name(pkg: String?): String {
        if (pkg == null) return "your messaging app"
        return runCatching { ctl.app.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg)
    }
}
