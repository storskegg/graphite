mod common;

use common::pair;
use graphite_core::{Error, Role, SourcePairing, TargetPairing};

/// Runs the first four messages, up to where both sides show the code.
fn up_to_code(target: &mut TargetPairing, source: &mut SourcePairing) -> Vec<u8> {
    let m = source.handle(&target.hello()).unwrap().unwrap();
    let m = target.handle(&m).unwrap().unwrap();
    let m = source.handle(&m).unwrap().unwrap();
    target.handle(&m).unwrap().unwrap()
}

#[test]
fn pairing_completes_and_both_sides_agree() {
    let (mut source, mut target) = pair();
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

#[test]
fn two_pairings_never_share_a_transcript() {
    let (a, _) = pair();
    let (b, _) = pair();
    assert_ne!(a.transcript_hash(), b.transcript_hash());
}

#[test]
fn code_is_six_digits_and_appears_once_both_shares_are_known() {
    let mut target = TargetPairing::new().unwrap();
    let mut source = SourcePairing::new().unwrap();
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
}

#[test]
fn pairing_does_not_finish_until_the_user_confirms_the_code() {
    let mut target = TargetPairing::new().unwrap();
    let mut source = SourcePairing::new().unwrap();
    let m = up_to_code(&mut target, &mut source);
    let m = source.handle(&m).unwrap().unwrap();
    assert!(target.handle(&m).unwrap().is_none());
    assert!(matches!(source.finish(), Err(Error::CodeNotConfirmed)));
    assert!(matches!(target.finish(), Err(Error::CodeNotConfirmed)));
    source.confirm_code().unwrap();
    target.confirm_code().unwrap();
    assert!(source.finish().is_ok());
    assert!(target.finish().is_ok());
    // A session can be taken once.
    assert!(matches!(target.finish(), Err(Error::UnexpectedMessage)));
}

#[test]
fn finish_or_confirm_before_the_handshake_completes_fails() {
    let mut target = TargetPairing::new().unwrap();
    let mut source = SourcePairing::new().unwrap();
    assert!(matches!(target.finish(), Err(Error::UnexpectedMessage)));
    // Confirming a code that has not been shown is refused.
    assert_eq!(target.confirm_code(), Err(Error::UnexpectedMessage));
    let m = source.handle(&target.hello()).unwrap().unwrap();
    target.handle(&m).unwrap();
    assert_eq!(source.confirm_code(), Err(Error::UnexpectedMessage));
    assert!(matches!(source.finish(), Err(Error::UnexpectedMessage)));
    // Confirmed but not finished: the last messages are still needed.
    let mut target = TargetPairing::new().unwrap();
    let mut source = SourcePairing::new().unwrap();
    up_to_code(&mut target, &mut source);
    target.confirm_code().unwrap();
    assert!(matches!(target.finish(), Err(Error::UnexpectedMessage)));
}

/// An attacker in the cable runs one pairing with each phone. Both
/// handshakes complete, but the two screens show different codes.
#[test]
fn mitm_shows_different_codes() {
    let mut target = TargetPairing::new().unwrap();
    let mut source = SourcePairing::new().unwrap();
    let mut evil_target = TargetPairing::new().unwrap();
    let mut evil_source = SourcePairing::new().unwrap();

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

/// An attacker that only swaps the target's share for its own changes the
/// source's code, and key confirmation fails too.
#[test]
fn substituted_target_share_changes_the_code() {
    let mut target = TargetPairing::new().unwrap();
    let mut source = SourcePairing::new().unwrap();
    let commit = source.handle(&target.hello()).unwrap().unwrap();
    target.handle(&commit).unwrap();
    // A well-formed share message from the attacker's own keys.
    let mut evil_target = TargetPairing::new().unwrap();
    let mut evil_source = SourcePairing::new().unwrap();
    let evil_commit = evil_source.handle(&evil_target.hello()).unwrap().unwrap();
    let evil_share = evil_target.handle(&evil_commit).unwrap().unwrap();

    let source_share = source.handle(&evil_share).unwrap().unwrap();
    let kem = target.handle(&source_share).unwrap().unwrap();
    assert_ne!(source.code(), target.code());
    assert_eq!(source.handle(&kem), Err(Error::KeyConfirmationFailed));
}

/// The source's commitment hides its key share until the target has sent its
/// own, and binds it: a source cannot change its share after seeing the target's.
#[test]
fn source_cannot_change_its_share_after_committing() {
    let mut target = TargetPairing::new().unwrap();
    let mut source = SourcePairing::new().unwrap();
    let mut other_source = SourcePairing::new().unwrap();
    let hello = target.hello();
    let commit = source.handle(&hello).unwrap().unwrap();
    other_source.handle(&hello).unwrap();
    let share = target.handle(&commit).unwrap().unwrap();
    let other_share = other_source.handle(&share).unwrap().unwrap();
    assert_eq!(target.handle(&other_share), Err(Error::CommitmentMismatch));
    // The failed target refuses to continue.
    assert_eq!(target.handle(&other_share), Err(Error::Failed));
}

#[test]
fn tampered_kem_ciphertext_or_confirmation_is_rejected() {
    // Flip a bit in the source's encapsulation.
    let mut target = TargetPairing::new().unwrap();
    let mut source = SourcePairing::new().unwrap();
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
    let mut target = TargetPairing::new().unwrap();
    let mut source = SourcePairing::new().unwrap();
    let mut m = up_to_code(&mut target, &mut source);
    let len = m.len();
    m[len - 1] ^= 1;
    assert_eq!(source.handle(&m), Err(Error::KeyConfirmationFailed));

    // Flip a bit in the source's confirmation tag.
    let mut target = TargetPairing::new().unwrap();
    let mut source = SourcePairing::new().unwrap();
    let m = up_to_code(&mut target, &mut source);
    let mut m = source.handle(&m).unwrap().unwrap();
    m[5] ^= 1;
    assert_eq!(target.handle(&m), Err(Error::KeyConfirmationFailed));
    assert!(matches!(target.finish(), Err(Error::Failed)));
}

/// Confirmation tags are per direction: the target's tag sent back to it as
/// if it came from the source is refused.
#[test]
fn reflected_confirmation_is_rejected() {
    let mut target = TargetPairing::new().unwrap();
    let mut source = SourcePairing::new().unwrap();
    let target_kem = up_to_code(&mut target, &mut source);
    let source_confirm = source.handle(&target_kem).unwrap().unwrap();
    let mut reflected = source_confirm.clone();
    let tag = &target_kem[target_kem.len() - 32..];
    let len = reflected.len();
    reflected[len - 32..].copy_from_slice(tag);
    assert_eq!(target.handle(&reflected), Err(Error::KeyConfirmationFailed));
}

#[test]
fn hello_is_checked_for_version_and_suite() {
    let hello = TargetPairing::new().unwrap().hello();

    let mut wrong_version = hello.clone();
    wrong_version[1] ^= 0xff; // version is the u16 after the tag
    let mut source = SourcePairing::new().unwrap();
    assert_eq!(
        source.handle(&wrong_version),
        Err(Error::UnsupportedVersion)
    );

    let mut wrong_suite = hello.clone();
    wrong_suite[3] ^= 0xff; // suite is the u16 after the version
    let mut source = SourcePairing::new().unwrap();
    assert_eq!(source.handle(&wrong_suite), Err(Error::UnsupportedSuite));
}

#[test]
fn hello_is_tag_version_suite_and_session_id_only() {
    let target = TargetPairing::new().unwrap();
    let hello = target.hello();
    assert_eq!(hello.len(), 1 + 2 + 2 + 16);
    // The same bytes on every call.
    assert_eq!(target.hello(), hello);
    assert_ne!(TargetPairing::new().unwrap().hello(), hello);
}

#[test]
fn malformed_and_out_of_order_messages_are_rejected() {
    let hello = TargetPairing::new().unwrap().hello();

    let mut source = SourcePairing::new().unwrap();
    assert_eq!(
        source.handle(&hello[..hello.len() - 1]),
        Err(Error::Malformed)
    );

    let mut long = hello.clone();
    long.push(0);
    let mut source = SourcePairing::new().unwrap();
    assert_eq!(source.handle(&long), Err(Error::Malformed));

    let mut source = SourcePairing::new().unwrap();
    assert_eq!(source.handle(&[]), Err(Error::Malformed));

    let mut source = SourcePairing::new().unwrap();
    assert_eq!(source.handle(&[0x7f]), Err(Error::Malformed));

    // A hello sent to the target, and the source's commit sent twice.
    let mut target = TargetPairing::new().unwrap();
    assert_eq!(target.handle(&hello), Err(Error::UnexpectedMessage));
    let mut target = TargetPairing::new().unwrap();
    let mut source = SourcePairing::new().unwrap();
    let commit = source.handle(&target.hello()).unwrap().unwrap();
    target.handle(&commit).unwrap();
    assert_eq!(target.handle(&commit), Err(Error::UnexpectedMessage));
}

#[test]
fn commit_for_another_session_is_rejected() {
    let mut target = TargetPairing::new().unwrap();
    let other = TargetPairing::new().unwrap();
    let mut source = SourcePairing::new().unwrap();
    let commit = source.handle(&other.hello()).unwrap().unwrap();
    assert_eq!(target.handle(&commit), Err(Error::SessionMismatch));
}
