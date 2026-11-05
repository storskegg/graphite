package io.github.storskegg.graphite.role

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * The default-SMS role. Only its holder can write SMS and MMS, so the new phone holds it while
 * it writes messages and gives it back right after (docs/records.md, "The SMS role").
 */
object SmsRole {
    /** The current default SMS app, or null if there is none. */
    fun holder(c: Context): String? = Telephony.Sms.getDefaultSmsPackage(c)

    fun held(c: Context) = c.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)

    /** Android's "Set graphite as your default SMS app?" dialog. */
    fun requestIntent(c: Context): Intent = c.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS)

    /** Android's "Change SMS app?" dialog naming [pkg], which the current holder may show. */
    fun giveBackIntent(pkg: String): Intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
        .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, pkg)
}
