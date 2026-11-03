#![allow(dead_code)]

use graphite_core::{Session, SourcePairing, TargetPairing};

/// Runs a whole pairing between an honest source and target, with the user
/// confirming the six-digit code on both.
pub fn pair() -> (Session, Session) {
    let mut target = TargetPairing::new().unwrap();
    let mut source = SourcePairing::new().unwrap();

    let commit = source.handle(&target.hello()).unwrap().unwrap();
    let target_share = target.handle(&commit).unwrap().unwrap();
    let source_share = source.handle(&target_share).unwrap().unwrap();
    let target_kem = target.handle(&source_share).unwrap().unwrap();
    let source_confirm = source.handle(&target_kem).unwrap().unwrap();
    assert!(target.handle(&source_confirm).unwrap().is_none());

    assert_eq!(source.code(), target.code());
    source.confirm_code().unwrap();
    target.confirm_code().unwrap();
    (source.finish().unwrap(), target.finish().unwrap())
}
