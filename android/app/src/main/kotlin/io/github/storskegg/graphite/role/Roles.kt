package io.github.storskegg.graphite.role

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build

enum class AppRole(val wire: Int) {
    SOURCE(1), TARGET(2);

    companion object {
        fun fromWire(v: Int) = entries.firstOrNull { it.wire == v }
    }
}

data class RoleResult(
    val role: AppRole?,
    /** True only once the role comes from hardware key attestation. */
    val attested: Boolean,
    val explanation: String,
)

/** How a phone decides whether it is the target (GrapheneOS) or the source. */
fun interface RoleCheck {
    fun check(context: Context): RoleResult
}

/**
 * NOT ATTESTED. Looks for GrapheneOS's own system apps. A modified OS can fake
 * this; design.md ("How each phone knows what it is") requires Android key
 * attestation against GrapheneOS's published verified-boot keys instead.
 *
 * ATTESTATION SEAM: replace this with an attesting [RoleCheck] (key attestation
 * with a challenge, verified-boot key against the shipped GrapheneOS
 * fingerprints, locked bootloader), and have the source verify the target's
 * fresh attestation chain over the paired session. [RoleResult.attested] and
 * the UI note follow from it.
 */
object UnattestedRoleCheck : RoleCheck {
    /** System packages only GrapheneOS ships. Two are required, so one stray app does not decide it. */
    val GRAPHENEOS_PACKAGES = listOf("app.grapheneos.setupwizard", "app.grapheneos.info", "app.grapheneos.apps")

    override fun check(context: Context): RoleResult {
        if (Build.VERSION.SDK_INT < 30) return RoleResult(null, false, "Android 11 or newer is needed.")
        val found = GRAPHENEOS_PACKAGES.filter { pkg ->
            runCatching {
                context.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
            }.getOrDefault(false)
        }
        return if (found.size >= 2) {
            RoleResult(AppRole.TARGET, false,
                "New phone: GrapheneOS detected by its system apps. Not yet verified by hardware attestation.")
        } else {
            RoleResult(AppRole.SOURCE, false,
                "Old phone: not GrapheneOS (Android ${Build.VERSION.RELEASE}). Detected by a simple check, not yet verified by hardware attestation.")
        }
    }
}
