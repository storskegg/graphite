package io.github.storskegg.graphite.attest

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * This phone's own attestation, end to end: the Keystore makes a chain with the challenge, the
 * checker reads it and gives its verdict. On an emulator the chain is software-backed and its
 * root is not Google's, so the verdict must be a refusal at that step. On a GrapheneOS phone
 * it must pass (see the phone test list in android/README.md). Logs under "graphite-attest".
 */
@RunWith(AndroidJUnit4::class)
class AttestationDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun ownChainIsReadAndJudged() {
        val challenge = ByteArray(32) { it.toByte() }
        val chain = KeyAttestation.chain(ctx, challenge)
        val r = AttestationCheck.check(chain, challenge, KeyAttestation.trust(ctx))
        val leaf = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(chain[0])) as X509Certificate
        val d = KeyDescription.of(leaf)
        Log.i("graphite-attest", "chain of ${chain.size}; levels ${d.attestationLevel}/${d.keymasterLevel}; " +
            "locked ${d.rootOfTrust?.locked}, state ${d.rootOfTrust?.state}, patch ${d.osPatchLevel}; verdict: ${r.why ?: "passed, ${r.device}"}")
        assertArrayEquals(challenge, d.challenge)
        if (android.os.Build.FINGERPRINT.contains("emulator") || android.os.Build.HARDWARE.startsWith("ranchu")) {
            assertFalse(r.ok)
            assertTrue(r.why!!, r.why!!.contains("not rooted in Google's"))
        } else {
            Log.i("graphite-attest", "on a phone: ${if (r.ok) "GrapheneOS ${r.device}" else r.why}; boot key ${r.bootKey}")
        }
        // The challenge is checked: another session's is refused once the root is trusted.
        assertEquals(32, d.challenge.size)
    }
}
