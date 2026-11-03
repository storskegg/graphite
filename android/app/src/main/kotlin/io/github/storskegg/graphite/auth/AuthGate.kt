package io.github.storskegg.graphite.auth

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The user proves it is them before graphite pairs, sets up the other phone or
 * touches files (design.md, "User authentication").
 *
 * A Keystore key that needs a fresh strong biometric or device credential for
 * every single use signs a random nonce. The gated code checks that
 * signature, so skipping the prompt (launching a later screen directly, say)
 * yields nothing to check. The proof is dropped when the screen locks or
 * graphite leaves the foreground.
 */
class AuthGate(private val context: Context) {
    private companion object {
        const val ALIAS = "graphite-user-presence"
        const val TAG = "graphite"
    }

    private class Proof(val nonce: ByteArray, val signature: ByteArray)

    class AuthRequired(why: String) : Exception(why)

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    @Volatile private var proof: Proof? = null
    private val listeners = mutableListOf<(Boolean) -> Unit>()

    /** A PIN, pattern or password is set. Without one graphite stops. */
    val deviceSecure: Boolean get() = context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    /** The current proof verifies against the Keystore key. Checked at every gate, not cached. */
    fun isValid(): Boolean {
        val p = proof ?: return false
        return runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(keyStore.getCertificate(ALIAS).publicKey)
                update(p.nonce)
                verify(p.signature)
            }
        }.getOrDefault(false)
    }

    /** Throws unless the user has proven it is them since the last lock or leaving graphite. */
    fun require(what: String) {
        if (!isValid()) throw AuthRequired("confirm it's you before $what")
    }

    fun invalidate(why: String) {
        if (proof == null) return
        Log.i(TAG, "authentication dropped: $why")
        proof = null
        notifyListeners(false)
    }

    fun onChange(l: (Boolean) -> Unit) = synchronized(listeners) { listeners += l }

    private fun notifyListeners(valid: Boolean) =
        synchronized(listeners) { listeners.toList() }.forEach { runCatching { it(valid) } }

    /**
     * Shows Android's prompt (strong biometric or device credential) and, on
     * success, keeps a fresh signed nonce as the proof. [done] gets null on
     * success, or why it failed.
     */
    fun authenticate(activityContext: Context, reason: String, done: (String?) -> Unit) {
        val nonce = ByteArray(32).also(SecureRandom()::nextBytes)
        val signature = try {
            initSignature()
        } catch (e: Exception) {
            Log.e(TAG, "auth key", e)
            return done("the Keystore key could not be used: ${e.message}")
        }
        val prompt = BiometricPrompt.Builder(activityContext)
            .setTitle("Confirm it's you")
            .setSubtitle(reason)
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(
            BiometricPrompt.CryptoObject(signature), CancellationSignal(), activityContext.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val s = result.cryptoObject?.signature ?: return done("no signature from the prompt")
                    val ok = runCatching {
                        s.update(nonce)
                        proof = Proof(nonce, s.sign())
                        isValid()
                    }.getOrElse { Log.e(TAG, "sign", it); false }
                    if (ok) { notifyListeners(true); done(null) } else { proof = null; done("the proof did not verify") }
                }

                override fun onAuthenticationError(code: Int, msg: CharSequence) = done(msg.toString())
            })
    }

    private fun initSignature(): Signature {
        repeat(2) {
            val key = (keyStore.getKey(ALIAS, null) as? PrivateKey) ?: run { createKey(); keyStore.getKey(ALIAS, null) as PrivateKey }
            try {
                return Signature.getInstance("SHA256withECDSA").apply { initSign(key) }
            } catch (_: KeyPermanentlyInvalidatedException) {
                // A biometric was added or the lock screen changed: make a new key.
                keyStore.deleteEntry(ALIAS)
            }
        }
        error("key invalidated twice")
    }

    private fun createKey() {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            // Timeout 0: every single signature needs its own authentication.
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .setIsStrongBoxBacked(strongBox)
            .build()
        val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        try {
            gen.initialize(spec(strongBox)); gen.generateKeyPair()
        } catch (e: Exception) {
            if (!strongBox) throw e
            Log.w(TAG, "StrongBox key failed, using the TEE: $e")
            gen.initialize(spec(false)); gen.generateKeyPair()
        }
    }
}
