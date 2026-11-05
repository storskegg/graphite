package io.github.storskegg.graphite.attest

import android.content.Context
import android.os.Build
import io.github.storskegg.graphite.log
import io.github.storskegg.graphite.role.AppRole
import io.github.storskegg.graphite.role.RoleCheck
import io.github.storskegg.graphite.role.RoleResult
import io.github.storskegg.graphite.role.UnattestedRoleCheck
import java.security.SecureRandom

/**
 * The role from this phone's own key attestation (design.md, "How each phone knows what it is"):
 * a chain rooted in Google's keys whose verified-boot key is GrapheneOS's makes a target; any
 * other hardware-attested phone is a source. A phone with GrapheneOS's apps whose attestation
 * doesn't match is refused rather than guessed. Without hardware attestation (an emulator) it
 * falls back to [UnattestedRoleCheck] and says so.
 */
object AttestedRoleCheck : RoleCheck {
    override fun check(context: Context): RoleResult {
        val simple = UnattestedRoleCheck.check(context)
        if (simple.role == null) return simple
        val r = try {
            val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
            AttestationCheck.check(KeyAttestation.chain(context, challenge, strongBox = false), challenge, KeyAttestation.trust(context))
        } catch (x: Exception) {
            log("self-attestation failed: $x")
            return simple.copy(explanation = simple.explanation + " This phone could not attest itself (${x.message}).")
        }
        log("self-attestation: ${r.why ?: "GrapheneOS on ${r.device}"}; boot key ${r.bootKey}; patch ${r.description?.osPatchLevel}")
        return when {
            r.ok -> RoleResult(AppRole.TARGET, true,
                "New phone: GrapheneOS on a ${r.device}, bootloader locked, verified by hardware attestation." +
                    (r.warning?.let { " Note: $it." } ?: ""))
            !r.rootTrusted -> simple.copy(explanation = simple.explanation + " (No hardware attestation: ${r.why}.)")
            simple.role == AppRole.TARGET -> RoleResult(null, true,
                "This phone has GrapheneOS's apps, but its hardware attestation does not match a GrapheneOS device in " +
                    "graphite's list: ${r.why}. graphite stops rather than guess; a newer graphite may know this device.")
            else -> RoleResult(AppRole.SOURCE, true,
                "Old phone: not GrapheneOS (Android ${Build.VERSION.RELEASE}), verified by hardware attestation.")
        }
    }
}
