package io.github.storskegg.graphite.attest

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** The root of trust the secure hardware signs into the key description. */
class RootOfTrust(val bootKey: ByteArray, val locked: Boolean, val state: Int) {
    companion object {
        const val VERIFIED = 0
        const val SELF_SIGNED = 1
        const val UNVERIFIED = 2
        const val FAILED = 3
    }
}

/** The parts of an attestation certificate's key description graphite checks. */
class KeyDescription(
    val attestationLevel: Int,
    val keymasterLevel: Int,
    val challenge: ByteArray,
    val rootOfTrust: RootOfTrust?,
    /** YYYYMM, from the hardware-enforced list. */
    val osPatchLevel: Int?,
) {
    companion object {
        const val SOFTWARE = 0
        const val TEE = 1
        const val STRONGBOX = 2
        const val OID = "1.3.6.1.4.1.11129.2.1.17"
        private const val ROOT_OF_TRUST = 704
        private const val OS_PATCH_LEVEL = 706

        /** Reads the key description from an attestation certificate's extension. */
        fun of(cert: X509Certificate): KeyDescription {
            val ext = cert.getExtensionValue(OID) ?: error("no key description: not an attestation certificate")
            val seq = Der.parse(Der.parse(ext).octets()).children()
            check(seq.size >= 8) { "key description too short" }
            fun hardware(): Der = seq[7]
            val rot = hardware().context(ROOT_OF_TRUST)?.inner()?.children()?.let {
                RootOfTrust(it[0].octets(), it[1].bool(), it[2].int())
            }
            return KeyDescription(seq[1].int(), seq[3].int(), seq[4].octets(), rot,
                hardware().context(OS_PATCH_LEVEL)?.inner()?.int())
        }
    }
}

/**
 * What the source trusts: Google's attestation roots (by public key, so a re-issued root
 * counts), the revoked serials, and GrapheneOS's verified-boot key fingerprints with the
 * device names they belong to.
 */
class AttestationTrust(val roots: List<PublicKey>, val revoked: Set<String>, val grapheneos: Set<String>, val minPatchLevel: Int) {
    companion object {
        /** The oldest security patch that passes without a warning (YYYYMM). */
        const val MIN_PATCH_LEVEL = 202609

        /** From the shipped files (android/attestation-data.sh). */
        fun fromText(rootsJson: String, statusJson: String, grapheneosJson: String, minPatchLevel: Int = MIN_PATCH_LEVEL): AttestationTrust {
            val pems = Regex("-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", RegexOption.DOT_MATCHES_ALL)
                .findAll(rootsJson.replace("\\n", "\n")).map { it.value }.toList()
            val cf = CertificateFactory.getInstance("X.509")
            val roots = pems.map { cf.generateCertificate(ByteArrayInputStream(it.toByteArray())).publicKey }
            check(roots.isNotEmpty()) { "no attestation roots" }
            val revoked = Regex("\"([0-9a-fA-F]+)\"\\s*:\\s*\\{").findAll(statusJson).map { it.groupValues[1].lowercase() }.toSet()
            val keys = Regex("[0-9a-fA-F]{64}").findAll(grapheneosJson).map { it.value.lowercase() }.toSet()
            check(keys.isNotEmpty()) { "no GrapheneOS keys" }
            return AttestationTrust(roots, revoked, keys, minPatchLevel)
        }

        /** Device names for GrapheneOS's fingerprints, for the screens only (from its attestation compatibility guide). */
        val DEVICE_NAMES = mapOf(
            "d8f879d10419eddc9fcda6280718be763f6bf12299e1f72df3ea8ad8a8eb7f80" to "Pixel 10a",
            "55a2d44103e56d5ec65496399c417987ba77730e6488fc60ba058d09fc3caee3" to "Pixel 10 Pro Fold",
            "141d7fc32af7958a416f2661b37cf6f27bfb376fb5ce616aeaa27a82c7a04f74" to "Pixel 10 Pro XL",
            "4e8ee8f717754052198ca6d2d3aaa232e2461b4293c0d6f297e519cc778de093" to "Pixel 10 Pro",
            "3f7415ea26f5df5b14ea6d153256071a7a1af9ce7b0970b7311cc463c7ea02c7" to "Pixel 10",
            "0508de44ee00bfb49ece32c418af1896391abde0f05b64f41bc9a2dfb589445b" to "Pixel 9a",
            "af4d2c6e62be0fec54f0271b9776ff061dd8392d9f51cf6ab1551d346679e24c" to "Pixel 9 Pro Fold",
            "55d3c2323db91bb91f20d38d015e85112d038f6b6b5738fe352c1a80dba57023" to "Pixel 9 Pro XL",
            "f729cab861da1b83fdfab402fc9480758f2ae78ee0b61c1f2137dd1ab7076e86" to "Pixel 9 Pro",
            "9e6a8f3e0d761a780179f93acd5721ba1ab7c8c537c7761073c0a754b0e932de" to "Pixel 9",
            "096b8bd6d44527a24ac1564b308839f67e78202185cbff9cfdcb10e63250bc5e" to "Pixel 8a",
            "896db2d09d84e1d6bb747002b8a114950b946e5825772a9d48ba7eb01d118c1c" to "Pixel 8 Pro",
            "cd7479653aa88208f9f03034810ef9b7b0af8a9d41e2000e458ac403a2acb233" to "Pixel 8",
            "ee0c9dfef6f55a878538b0dbf7e78e3bc3f1a13c8c44839b095fe26dd5fe2842" to "Pixel Fold",
            "94df136e6c6aa08dc26580af46f36419b5f9baf46039db076f5295b91aaff230" to "Pixel Tablet",
            "508d75dea10c5cbc3e7632260fc0b59f6055a8a49dd84e693b6d8899edbb01e4" to "Pixel 7a",
            "bc1c0dd95664604382bb888412026422742eb333071ea0b2d19036217d49182f" to "Pixel 7 Pro",
            "3efe5392be3ac38afb894d13de639e521675e62571a8a9b3ef9fc8c44fd17fa1" to "Pixel 7",
            "08c860350a9600692d10c8512f7b8e80707757468e8fbfeea2a870c0a83d6031" to "Pixel 6a",
            "439b76524d94c40652ce1bf0d8243773c634d2f99ba3160d8d02aa5e29ff925c" to "Pixel 6 Pro",
            "f0a890375d1405e62ebfd87e8d3f475f948ef031bbf9ddd516d5f600a23677e8" to "Pixel 6",
        )
    }
}

/**
 * The outcome of checking a chain: [why] says what failed (null when it passed), [device] is the
 * GrapheneOS device the boot key belongs to, [warning] an old patch level.
 */
class Attested(val why: String?, val device: String? = null, val warning: String? = null,
               val bootKey: String? = null, val description: KeyDescription? = null, val rootTrusted: Boolean = false) {
    val ok get() = why == null
}

/** The source's check of the target's attestation chain (docs/records.md, "Attestation"), in the documented order. */
object AttestationCheck {
    fun check(chainDer: List<ByteArray>, challenge: ByteArray, trust: AttestationTrust): Attested {
        // 1. Well formed, each certificate signed by the next.
        if (chainDer.size < 2) return Attested("the attestation chain is too short")
        val cf = CertificateFactory.getInstance("X.509")
        val chain = try {
            chainDer.map { cf.generateCertificate(ByteArrayInputStream(it)) as X509Certificate }
        } catch (x: Exception) { return Attested("the attestation chain could not be read: ${x.message}") }
        for (i in 0 until chain.size - 1) {
            try { chain[i].verify(chain[i + 1].publicKey) } catch (x: Exception) {
                return Attested("certificate ${i + 1} of the attestation chain is not signed by the next")
            }
        }
        val desc = try { KeyDescription.of(chain[0]) } catch (x: Exception) {
            return Attested("the attestation certificate could not be read: ${x.message}")
        }
        // 2. Rooted in Google's hardware attestation roots.
        val root = chain.last()
        val rootTrusted = trust.roots.any { it.encoded.contentEquals(root.publicKey.encoded) } &&
            runCatching { root.verify(root.publicKey) }.isSuccess
        if (!rootTrusted) return Attested("the new phone's attestation is not rooted in Google's hardware attestation keys " +
            "(an emulator or modified phone)", description = desc)
        // 3. Nothing revoked.
        val revoked = chain.firstOrNull { serials(it.serialNumber).any { s -> s in trust.revoked } }
        if (revoked != null) return Attested("a certificate in the new phone's attestation chain has been revoked", description = desc, rootTrusted = true)
        // 4. This session's challenge.
        if (!desc.challenge.contentEquals(challenge)) return Attested("the attestation is not for this session", description = desc, rootTrusted = true)
        // 5. Hardware, not software.
        if (desc.attestationLevel == KeyDescription.SOFTWARE || desc.keymasterLevel == KeyDescription.SOFTWARE) {
            return Attested("the attestation comes from software, not the phone's secure hardware", description = desc, rootTrusted = true)
        }
        // 6. Locked bootloader, booted with GrapheneOS's key.
        val rot = desc.rootOfTrust ?: return Attested("the attestation has no root of trust", description = desc, rootTrusted = true)
        val key = rot.bootKey.joinToString("") { "%02x".format(it) }
        if (!rot.locked) return Attested("the new phone's bootloader is unlocked", bootKey = key, description = desc, rootTrusted = true)
        if (rot.state != RootOfTrust.SELF_SIGNED || key !in trust.grapheneos) {
            return Attested("the new phone is not running GrapheneOS (its verified-boot key is not one of GrapheneOS's)",
                bootKey = key, description = desc, rootTrusted = true)
        }
        // 7. Patch level: a warning, not a refusal.
        val patch = desc.osPatchLevel
        val warning = if (patch == null || patch < trust.minPatchLevel) "the new phone's security patch level is " +
            "${patch?.let { "%d-%02d".format(it / 100, it % 100) } ?: "unknown"}, older than graphite expects: update it" else null
        val device = AttestationTrust.DEVICE_NAMES[key] ?: "a GrapheneOS device"
        return Attested(null, device, warning, key, desc, true)
    }

    /** A serial as the revocation list may write it: hex and decimal. */
    private fun serials(n: BigInteger) = listOf(n.toString(16), n.toString(10))
}
