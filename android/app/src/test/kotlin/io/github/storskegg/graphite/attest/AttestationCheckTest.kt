package io.github.storskegg.graphite.attest

import org.bouncycastle.asn1.ASN1Boolean
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.Date

/** The source's checks against synthetic chains (a test root standing in for Google's). */
class AttestationCheckTest {
    private fun keys(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val root = keys()
    private val intermediate = keys()
    private val leaf = keys()
    private val challenge = ByteArray(32) { 7 }
    /** The Pixel 10 Pro XL's GrapheneOS key. */
    private val bootKey = "141d7fc32af7958a416f2661b37cf6f27bfb376fb5ce616aeaa27a82c7a04f74"

    private fun cert(subject: KeyPair, issuer: KeyPair, serial: Long, ext: ASN1Encodable? = null): X509Certificate {
        val b = JcaX509v3CertificateBuilder(X500Name("CN=issuer $serial"), BigInteger.valueOf(serial),
            Date(0), Date(4_000_000_000_000), X500Name("CN=subject $serial"), subject.public)
        if (ext != null) b.addExtension(ASN1ObjectIdentifier(KeyDescription.OID), false, ext)
        return JcaX509CertificateConverter().getCertificate(b.build(JcaContentSignerBuilder("SHA256withECDSA").build(issuer.private)))
    }

    private fun description(level: Int = 1, challenge: ByteArray = this.challenge, key: String = bootKey, locked: Boolean = true,
                            state: Int = RootOfTrust.SELF_SIGNED, patch: Int = 202611): ASN1Encodable {
        val rot = DERSequence(arrayOf(DEROctetString(key.chunked(2).map { it.toInt(16).toByte() }.toByteArray()),
            ASN1Boolean.getInstance(locked), ASN1Enumerated(state), DEROctetString(ByteArray(32))))
        val hardware = DERSequence(arrayOf<ASN1Encodable>(DERTaggedObject(true, 704, rot), DERTaggedObject(true, 706, ASN1Integer(patch.toLong()))))
        return DERSequence(arrayOf(ASN1Integer(300), ASN1Enumerated(level), ASN1Integer(300), ASN1Enumerated(level),
            DEROctetString(challenge), DEROctetString(ByteArray(0)), DERSequence(), hardware))
    }

    private fun chain(desc: ASN1Encodable = description(), leafSigner: KeyPair = intermediate, rootKey: KeyPair = root) =
        listOf(cert(leaf, leafSigner, 3, desc), cert(intermediate, rootKey, 2), cert(rootKey, rootKey, 1)).map { it.encoded }

    private fun trust(revoked: Set<String> = emptySet()) = AttestationTrust(listOf(root.public), revoked, setOf(bootKey), 202609)

    private fun check(chain: List<ByteArray> = chain(), challenge: ByteArray = this.challenge, trust: AttestationTrust = trust()) =
        AttestationCheck.check(chain, challenge, trust)

    @Test fun grapheneOsWithALockedBootloaderPasses() {
        val r = check()
        assertNull(r.why)
        assertEquals("Pixel 10 Pro XL", r.device)
        assertNull(r.warning)
        assertEquals(202611, r.description!!.osPatchLevel)
    }

    @Test fun eachFailureIsNamed() {
        fun why(r: Attested) = r.why ?: "passed"
        assertTrue(why(check(chain(rootKey = keys()))).contains("not rooted in Google's"))
        assertTrue(why(check(chain(leafSigner = keys()))).contains("not signed by the next"))
        assertTrue(why(check(trust = trust(setOf("2")))).contains("revoked"))
        assertTrue(why(check(challenge = ByteArray(32))).contains("not for this session"))
        assertTrue(why(check(chain(description(level = 0)))).contains("software"))
        assertTrue(why(check(chain(description(locked = false)))).contains("bootloader is unlocked"))
        assertTrue(why(check(chain(description(state = RootOfTrust.VERIFIED)))).contains("not running GrapheneOS"))
        assertTrue(why(check(chain(description(key = "00".repeat(32))))).contains("not running GrapheneOS"))
        assertTrue(why(check(chain().take(1))).contains("too short"))
        assertTrue(why(check(listOf(byteArrayOf(1, 2), byteArrayOf(3)))).contains("could not be read"))
    }

    @Test fun anOldPatchLevelIsAWarningNotARefusal() {
        val r = check(chain(description(patch = 202512)))
        assertTrue(r.ok)
        assertTrue(r.warning!!.contains("2025-12"))
    }

    @Test fun theShippedFilesParse() {
        val dir = File("src/main/assets/attestation")
        val t = AttestationTrust.fromText(File(dir, "roots.json").readText(), File(dir, "status.json").readText(),
            File(dir, "grapheneos.json").readText())
        assertEquals(2, t.roots.size)
        assertTrue(t.revoked.size > 1000)
        assertEquals(21, t.grapheneos.size)
        assertTrue(bootKey in t.grapheneos)
        // Every shipped GrapheneOS key has a name for the screens.
        assertEquals(t.grapheneos, AttestationTrust.DEVICE_NAMES.keys)
    }

    @Test fun derReadsHighTagNumbersAndLongLengths() {
        val big = DERSequence(arrayOf<ASN1Encodable>(DERTaggedObject(true, 704, DEROctetString(ByteArray(300) { 1 })))).encoded
        val inner = Der.parse(big).context(704)!!.inner()
        assertEquals(300, inner.octets().size)
    }
}
