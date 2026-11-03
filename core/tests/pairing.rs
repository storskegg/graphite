mod common;

use common::pair;
use graphite_core::{Error, Method, Role, SourcePairing, TargetPairing};

const METHODS: [Method; 3] = [Method::Nfc, Method::Qr, Method::Code];

#[test]
fn every_method_pairs_and_both_sides_agree() {
    for method in METHODS {
        let (mut source, mut target) = pair(method);
        assert_eq!(source.role(), Role::Source);
        assert_eq!(target.role(), Role::Target);
        assert_eq!(source.transcript_hash(), target.transcript_hash());

        let chunk = source.seal(b"to the new phone", true).unwrap();
        let opened = target.open(&chunk).unwrap();
        assert_eq!(opened.data, b"to the new phone");
        assert!(opened.last);

        let chunk = target.seal(b"to the old phone", true).unwrap();
        assert_eq!(source.open(&chunk).unwrap().data, b"to the old phone");
    }
}

#[test]
fn two_pairings_never_share_a_transcript() {
    let (a, _) = pair(Method::Nfc);
    let (b, _) = pair(Method::Nfc);
    assert_ne!(a.transcript_hash(), b.transcript_hash());
}

#[test]
fn code_is_six_digits_and_only_for_the_code_method() {
    let mut target = TargetPairing::new(Method::Code).unwrap();
    let mut source = SourcePairing::new(Method::Code).unwrap();
    assert_eq!(target.code(), None);
    let commit = source.handle(&target.hello()).unwrap().unwrap();
    let share = target.handle(&commit).unwrap().unwrap();
    // The source has only committed so far: no code yet on either side.
    assert_eq!(source.code(), None);
    assert_eq!(target.code(), None);
    let source_share = source.handle(&share).unwrap().unwrap();
    let code = source.code().unwrap();
    assert_eq!(code.len(), 6);
    assert!(code.bytes().all(|b| b.is_ascii_digit()));
    target.handle(&source_share).unwrap();
    assert_eq!(target.code(), Some(code));

    let mut nfc = TargetPairing::new(Method::Nfc).unwrap();
    assert_eq!(nfc.code(), None);
    assert_eq!(nfc.confirm_code(), Err(Error::WrongMethod));
}

#[test]
fn code_method_does_not_finish_until_the_user_confirms() {
    let mut target = TargetPairing::new(Method::Code).unwrap();
    let mut source = SourcePairing::new(Method::Code).unwrap();
    let m = source.handle(&target.hello()).unwrap().unwrap();
    let m = target.handle(&m).unwrap().unwrap();
    let m = source.handle(&m).unwrap().unwrap();
    let m = target.handle(&m).unwrap().unwrap();
    let m = source.handle(&m).unwrap().unwrap();
    target.handle(&m).unwrap();
    assert!(matches!(source.finish(), Err(Error::CodeNotConfirmed)));
    assert!(matches!(target.finish(), Err(Error::CodeNotConfirmed)));
    source.confirm_code().unwrap();
    target.confirm_code().unwrap();
    assert!(source.finish().is_ok());
    assert!(target.finish().is_ok());
}

#[test]
fn finish_before_the_handshake_completes_fails() {
    let mut target = TargetPairing::new(Method::Nfc).unwrap();
    let mut source = SourcePairing::new(Method::Nfc).unwrap();
    assert!(matches!(target.finish(), Err(Error::UnexpectedMessage)));
    let m = source.handle(&target.hello()).unwrap().unwrap();
    target.handle(&m).unwrap();
    assert!(matches!(source.finish(), Err(Error::UnexpectedMessage)));
    // Confirming a code that has not been shown is refused too.
    let mut code_target = TargetPairing::new(Method::Code).unwrap();
    assert_eq!(code_target.confirm_code(), Err(Error::UnexpectedMessage));
}

/// A man-in-the-middle on the cable runs one pairing with each phone. The NFC
/// hashes it cannot change (proximity) catch the substituted key shares.
#[test]
fn nfc_catches_a_cable_mitm_on_either_side() {
    // Attacker swaps the target's share on its way to the source.
    let mut target = TargetPairing::new(Method::Nfc).unwrap();
    let mut source = SourcePairing::new(Method::Nfc).unwrap();
    let commit = source.handle(&target.hello()).unwrap().unwrap();
    let real_share = target.handle(&commit).unwrap().unwrap();
    let evil_share = target_share_for(Method::Nfc);
    assert_eq!(source.handle(&evil_share), Err(Error::CommitmentMismatch));
    // The failed source refuses to continue, even with the genuine share.
    assert_eq!(source.handle(&real_share), Err(Error::Failed));

    // Attacker swaps the source's share on its way to the target.
    let mut target = TargetPairing::new(Method::Nfc).unwrap();
    let mut source = SourcePairing::new(Method::Nfc).unwrap();
    let hello = target.hello();
    let commit = source.handle(&hello).unwrap().unwrap();
    let share = target.handle(&commit).unwrap().unwrap();
    let mut evil_source = SourcePairing::new(Method::Nfc).unwrap();
    evil_source.handle(&hello).unwrap();
    let evil_source_share = evil_source.handle(&share).unwrap().unwrap();
    assert_eq!(
        target.handle(&evil_source_share),
        Err(Error::CommitmentMismatch)
    );
}

/// A well-formed target share message from the attacker's own keys.
fn target_share_for(method: Method) -> Vec<u8> {
    let mut target = TargetPairing::new(method).unwrap();
    let mut source = SourcePairing::new(method).unwrap();
    let commit = source.handle(&target.hello()).unwrap().unwrap();
    target.handle(&commit).unwrap().unwrap()
}

/// Without the QR's one-time secret, an attacker on the cable cannot pose as
/// the source: key confirmation fails at the target.
#[test]
fn qr_rejects_a_source_that_did_not_scan_the_code() {
    let mut target = TargetPairing::new(Method::Qr).unwrap();
    let hello = target.hello();
    // The attacker knows everything in the QR except the secret: it rebuilds
    // the hello with a secret of its own.
    let mut forged = hello.clone();
    let len = forged.len();
    forged[len - 32..].copy_from_slice(&[0x42; 32]);
    let mut evil_source = SourcePairing::new(Method::Qr).unwrap();
    let commit = evil_source.handle(&forged).unwrap().unwrap();
    let share = target.handle(&commit).unwrap().unwrap();
    let evil_share = evil_source.handle(&share).unwrap().unwrap();
    // The target cannot tell yet, and answers with its key confirmation,
    // which the attacker, lacking the secret, cannot check...
    let target_kem = target.handle(&evil_share).unwrap().unwrap();
    assert_eq!(
        evil_source.handle(&target_kem),
        Err(Error::KeyConfirmationFailed)
    );
    // ...nor produce: the target refuses whatever confirmation it sends.
    let mut guessed = vec![6u8];
    guessed.extend_from_slice(&[0; 32]);
    assert_eq!(target.handle(&guessed), Err(Error::KeyConfirmationFailed));
}

/// In the QR method the source checks the target's share against the hash
/// in the QR code.
#[test]
fn qr_rejects_a_substituted_target_share() {
    let target = TargetPairing::new(Method::Qr).unwrap();
    let mut source = SourcePairing::new(Method::Qr).unwrap();
    source.handle(&target.hello()).unwrap().unwrap();
    let evil_share = target_share_for(Method::Qr);
    assert_eq!(source.handle(&evil_share), Err(Error::CommitmentMismatch));
}

/// An attacker in the middle of a code pairing completes the handshake with
/// both phones, but the two screens show different codes.
#[test]
fn code_mitm_shows_different_codes() {
    let mut target = TargetPairing::new(Method::Code).unwrap();
    let mut source = SourcePairing::new(Method::Code).unwrap();
    let mut evil_target = TargetPairing::new(Method::Code).unwrap();
    let mut evil_source = SourcePairing::new(Method::Code).unwrap();

    // source <-> evil_target, evil_source <-> target
    let m = source.handle(&evil_target.hello()).unwrap().unwrap();
    let n = evil_source.handle(&target.hello()).unwrap().unwrap();
    let m = evil_target.handle(&m).unwrap().unwrap();
    let n = target.handle(&n).unwrap().unwrap();
    let m = source.handle(&m).unwrap().unwrap();
    let n = evil_source.handle(&n).unwrap().unwrap();
    evil_target.handle(&m).unwrap();
    target.handle(&n).unwrap();

    assert_ne!(source.code().unwrap(), target.code().unwrap());
}

/// The source's commitment hides its key share until the target has sent its
/// own, and binds it: a source cannot change its share after seeing the target's.
#[test]
fn code_source_cannot_change_its_share_after_committing() {
    let mut target = TargetPairing::new(Method::Code).unwrap();
    let mut source = SourcePairing::new(Method::Code).unwrap();
    let mut other_source = SourcePairing::new(Method::Code).unwrap();
    let hello = target.hello();
    let commit = source.handle(&hello).unwrap().unwrap();
    other_source.handle(&hello).unwrap();
    let share = target.handle(&commit).unwrap().unwrap();
    let other_share = other_source.handle(&share).unwrap().unwrap();
    assert_eq!(target.handle(&other_share), Err(Error::CommitmentMismatch));
}

#[test]
fn tampered_kem_ciphertext_or_confirmation_is_rejected() {
    // Flip a bit in the source's encapsulation.
    let mut target = TargetPairing::new(Method::Nfc).unwrap();
    let mut source = SourcePairing::new(Method::Nfc).unwrap();
    let m = source.handle(&target.hello()).unwrap().unwrap();
    let m = target.handle(&m).unwrap().unwrap();
    let mut m = source.handle(&m).unwrap().unwrap();
    let len = m.len();
    m[len - 10] ^= 1;
    let reply = target.handle(&m).unwrap().unwrap();
    // The target cannot tell yet; the source's check of the target's
    // confirmation fails.
    assert_eq!(source.handle(&reply), Err(Error::KeyConfirmationFailed));

    // Flip a bit in the target's confirmation tag.
    let mut target = TargetPairing::new(Method::Nfc).unwrap();
    let mut source = SourcePairing::new(Method::Nfc).unwrap();
    let m = source.handle(&target.hello()).unwrap().unwrap();
    let m = target.handle(&m).unwrap().unwrap();
    let m = source.handle(&m).unwrap().unwrap();
    let mut m = target.handle(&m).unwrap().unwrap();
    let len = m.len();
    m[len - 1] ^= 1;
    assert_eq!(source.handle(&m), Err(Error::KeyConfirmationFailed));

    // Flip a bit in the source's confirmation tag.
    let mut target = TargetPairing::new(Method::Nfc).unwrap();
    let mut source = SourcePairing::new(Method::Nfc).unwrap();
    let m = source.handle(&target.hello()).unwrap().unwrap();
    let m = target.handle(&m).unwrap().unwrap();
    let m = source.handle(&m).unwrap().unwrap();
    let m = target.handle(&m).unwrap().unwrap();
    let mut m = source.handle(&m).unwrap().unwrap();
    m[5] ^= 1;
    assert_eq!(target.handle(&m), Err(Error::KeyConfirmationFailed));
    assert!(matches!(target.finish(), Err(Error::Failed)));
}

/// Confirmation tags are per direction: the target's tag sent back to it as
/// if it came from the source is refused.
#[test]
fn reflected_confirmation_is_rejected() {
    let mut target = TargetPairing::new(Method::Nfc).unwrap();
    let mut source = SourcePairing::new(Method::Nfc).unwrap();
    let m = source.handle(&target.hello()).unwrap().unwrap();
    let m = target.handle(&m).unwrap().unwrap();
    let m = source.handle(&m).unwrap().unwrap();
    let target_kem = target.handle(&m).unwrap().unwrap();
    let source_confirm = source.handle(&target_kem).unwrap().unwrap();
    // Build a confirm message carrying the target's own tag.
    let mut reflected = source_confirm.clone();
    let tag = &target_kem[target_kem.len() - 32..];
    let len = reflected.len();
    reflected[len - 32..].copy_from_slice(tag);
    assert_eq!(target.handle(&reflected), Err(Error::KeyConfirmationFailed));
}

#[test]
fn hello_is_checked_for_method_version_and_suite() {
    let target = TargetPairing::new(Method::Nfc).unwrap();
    let hello = target.hello();

    let mut source = SourcePairing::new(Method::Qr).unwrap();
    assert_eq!(source.handle(&hello), Err(Error::WrongMethod));

    let mut wrong_version = hello.clone();
    wrong_version[1] ^= 0xff; // version is the u16 after the tag
    let mut source = SourcePairing::new(Method::Nfc).unwrap();
    assert_eq!(
        source.handle(&wrong_version),
        Err(Error::UnsupportedVersion)
    );

    let mut wrong_suite = hello.clone();
    wrong_suite[3] ^= 0xff; // suite is the u16 after the version
    let mut source = SourcePairing::new(Method::Nfc).unwrap();
    assert_eq!(source.handle(&wrong_suite), Err(Error::UnsupportedSuite));
}

#[test]
fn malformed_and_out_of_order_messages_are_rejected() {
    let target = TargetPairing::new(Method::Nfc).unwrap();
    let hello = target.hello();

    let mut source = SourcePairing::new(Method::Nfc).unwrap();
    assert_eq!(
        source.handle(&hello[..hello.len() - 1]),
        Err(Error::Malformed)
    );

    let mut long = hello.clone();
    long.push(0);
    let mut source = SourcePairing::new(Method::Nfc).unwrap();
    assert_eq!(source.handle(&long), Err(Error::Malformed));

    let mut source = SourcePairing::new(Method::Nfc).unwrap();
    assert_eq!(source.handle(&[]), Err(Error::Malformed));

    // A hello sent to the target, and the source's commit sent twice.
    let mut target = TargetPairing::new(Method::Nfc).unwrap();
    assert_eq!(target.handle(&hello), Err(Error::UnexpectedMessage));
    let mut target = TargetPairing::new(Method::Nfc).unwrap();
    let mut source = SourcePairing::new(Method::Nfc).unwrap();
    let commit = source.handle(&target.hello()).unwrap().unwrap();
    target.handle(&commit).unwrap();
    assert_eq!(target.handle(&commit), Err(Error::UnexpectedMessage));
}

#[test]
fn commit_for_another_session_is_rejected() {
    let mut target = TargetPairing::new(Method::Nfc).unwrap();
    let other = TargetPairing::new(Method::Nfc).unwrap();
    let mut source = SourcePairing::new(Method::Nfc).unwrap();
    let commit = source.handle(&other.hello()).unwrap().unwrap();
    assert_eq!(target.handle(&commit), Err(Error::SessionMismatch));
}

#[test]
fn qr_payload_carries_a_256_bit_secret_that_nfc_and_code_do_not() {
    let qr = TargetPairing::new(Method::Qr).unwrap().hello();
    let nfc = TargetPairing::new(Method::Nfc).unwrap().hello();
    let code = TargetPairing::new(Method::Code).unwrap().hello();
    assert_eq!(qr.len(), nfc.len() + 32);
    assert_eq!(nfc.len(), code.len() + 32);
    // The hello does not change between calls (the QR can be redrawn).
    let target = TargetPairing::new(Method::Qr).unwrap();
    assert_eq!(target.hello(), target.hello());
}
