package io.github.storskegg.graphite.attest

import io.github.storskegg.graphite.Controller
import io.github.storskegg.graphite.Flows
import io.github.storskegg.graphite.StoppedException
import io.github.storskegg.graphite.ffi.Session
import io.github.storskegg.graphite.ffi.blake3Hash
import io.github.storskegg.graphite.log
import io.github.storskegg.graphite.session.Channel
import io.github.storskegg.graphite.session.Msg
import io.github.storskegg.graphite.ui.Screen
import io.github.storskegg.graphite.util.BinReader
import io.github.storskegg.graphite.util.BinWriter

/**
 * After pairing, the old phone checks that the new one runs GrapheneOS with a locked bootloader
 * before it sends anything (design.md, "How the source checks the target"; docs/records.md).
 */
object SessionAttestation {
    /** Bound to this session: both phones derive it from the transcript hash, so it is never sent. */
    fun challenge(session: Session): ByteArray = blake3Hash("graphite/1 attestation".toByteArray() + session.transcriptHash())

    /** New phone: answers the old phone's request with a fresh chain for this session's challenge. */
    fun answer(ctl: Controller, session: Session, ch: Channel) {
        Flows.expect(ch, Msg.ATTEST)
        ctl.show(Screen("Proving this phone", "Showing the old phone that this is GrapheneOS with a locked bootloader…"))
        val w = BinWriter()
        try {
            val chain = KeyAttestation.chain(ctl.app, challenge(session))
            w.bool(true).u32(chain.size)
            chain.forEach { w.bytes(it) }
        } catch (x: Exception) {
            log("attestation failed here: $x")
            w.bool(false).str(x.message ?: x.toString())
        }
        ch.send(Msg.ATTESTATION, w.finish())
    }

    /** Old phone: checks the chain; stops the session (both phones say why) unless it passes. */
    fun check(ctl: Controller, session: Session, ch: Channel): Attested {
        ctl.show(Screen("Checking the new phone", "Asking the new phone to prove it runs GrapheneOS…"))
        ch.send(Msg.ATTEST, ByteArray(0))
        val r = BinReader(Flows.expect(ch, Msg.ATTESTATION).body)
        val result = if (r.bool()) {
            val chain = (0 until r.u32().toInt()).map { r.bytes() }
            AttestationCheck.check(chain, challenge(session), KeyAttestation.trust(ctl.app))
        } else Attested("the new phone could not attest: ${r.str()}")
        log("attestation of the new phone: ${result.why ?: "GrapheneOS on ${result.device}"}" +
            (result.warning?.let { "; $it" } ?: "") + "; boot key ${result.bootKey}")
        if (result.ok) return result
        if (ctl.testAttestation == "skip") {
            log("TEST: attestation failed, going on (test_attestation=skip)")
            return result
        }
        ch.stop(Msg.ERROR, BinWriter().str("the old phone refused this phone: ${result.why}").finish())
        throw StoppedException("Nothing was sent: ${result.why}.")
    }
}
