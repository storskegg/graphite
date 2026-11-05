package io.github.storskegg.graphite.attest

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.storskegg.graphite.log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec

/** This phone's own key attestation: a fresh Keystore key whose certificate chain carries [challenge]. */
object KeyAttestation {
    private const val ALIAS = "graphite-attestation"

    /** The chain, leaf first; StrongBox when the phone has it, else the TEE. Throws when the phone can't attest. */
    fun chain(c: Context, challenge: ByteArray, strongBox: Boolean = true): List<ByteArray> {
        val strongBox = strongBox && c.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        return try {
            generate(challenge, strongBox)
        } catch (x: Exception) {
            if (!strongBox) throw x
            log("StrongBox attestation failed ($x); using the TEE")
            generate(challenge, false)
        }
    }

    private fun generate(challenge: ByteArray, strongBox: Boolean): List<ByteArray> {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(challenge)
                    .setIsStrongBoxBacked(strongBox)
                    .build())
            }.generateKeyPair()
            return ks.getCertificateChain(ALIAS)?.map { it.encoded } ?: error("no certificate chain")
        } finally {
            runCatching { ks.deleteEntry(ALIAS) }
        }
    }

    /** What graphite ships to check a chain against (android/attestation-data.sh). */
    fun trust(c: Context): AttestationTrust {
        fun asset(n: String) = c.assets.open("attestation/$n").use { String(it.readBytes()) }
        return AttestationTrust.fromText(asset("roots.json"), asset("status.json"), asset("grapheneos.json"))
    }
}
