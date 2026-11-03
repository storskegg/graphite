package io.github.storskegg.graphite.media

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import io.github.storskegg.graphite.Controller
import io.github.storskegg.graphite.MainActivity
import io.github.storskegg.graphite.ui.Screen

/** All-files access, which both phones need for shared storage beyond their own files. */
object Access {
    fun requireAllFiles(ctl: Controller, why: String) {
        while (!Environment.isExternalStorageManager()) {
            ctl.ask(Screen("Allow access to all files",
                "graphite needs \"All files access\" to $why. Android shows the setting next; switch it on for " +
                    "graphite and come back. graphite only reads and writes the folders listed in its plan.",
                buttons = listOf("Open the setting")))
            MainActivity.current?.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${ctl.app.packageName}")))
            ctl.ask(Screen("Allow access to all files", "Switch on \"Allow access to manage all files\" for graphite, then come back.",
                buttons = listOf("I've allowed it")))
        }
    }
}
