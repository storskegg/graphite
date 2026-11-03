mod common;

use common::pair;
use graphite_core::{
    Error, FileDigest, FileHasher, Manifest, ManifestEntry, MetadataChoice, Method, Outcome,
    Receipt, ReceiptEntry, manifest_hash,
};

fn digest(bytes: &[u8]) -> FileDigest {
    FileDigest {
        size: bytes.len() as u64,
        blake3: FileHasher::hash(bytes),
    }
}

fn manifest() -> Manifest {
    Manifest {
        metadata: MetadataChoice::RemoveLocationAndIdentifiers,
        stripper_version: "1.0.0".into(),
        files: vec![
            ManifestEntry {
                path: "DCIM/Camera/PXL_20260101_120000000.jpg".into(),
                mime_type: "image/jpeg".into(),
                date_taken_ms: Some(1_767_268_800_000),
                modified_ms: 1_767_268_801_000,
                original: digest(b"jpeg with gps"),
                sent: digest(b"jpeg without gps"),
            },
            ManifestEntry {
                path: "Download/r\u{e9}sum\u{e9}.pdf".into(),
                mime_type: "application/pdf".into(),
                date_taken_ms: None,
                modified_ms: 1_700_000_000_000,
                original: digest(b"pdf"),
                sent: digest(b"pdf"),
            },
        ],
    }
}

fn receipt(signed_manifest: &[u8]) -> Receipt {
    Receipt {
        manifest_hash: manifest_hash(signed_manifest),
        entries: vec![
            ReceiptEntry {
                path: "DCIM/Camera/PXL_20260101_120000000.jpg".into(),
                sent_blake3: FileHasher::hash(b"jpeg without gps"),
                outcome: Outcome::Copied,
                note: String::new(),
            },
            ReceiptEntry {
                path: "Download/r\u{e9}sum\u{e9}.pdf".into(),
                sent_blake3: FileHasher::hash(b"pdf"),
                outcome: Outcome::AlreadyPresentWithMetadata,
                note: "already on the new phone, with its metadata".into(),
            },
        ],
    }
}

#[test]
fn manifest_signed_by_the_source_verifies_at_the_target() {
    let (source, target) = pair(Method::Nfc);
    let signed = source.sign_manifest(&manifest()).unwrap();
    assert_eq!(target.verify_manifest(&signed).unwrap(), manifest());
}

#[test]
fn receipt_signed_by_the_target_verifies_at_the_source() {
    let (source, target) = pair(Method::Qr);
    let signed_manifest = source.sign_manifest(&manifest()).unwrap();
    let signed = target.sign_receipt(&receipt(&signed_manifest)).unwrap();
    assert_eq!(
        source.verify_receipt(&signed, &signed_manifest).unwrap(),
        receipt(&signed_manifest)
    );
}

#[test]
fn receipt_for_a_different_manifest_is_rejected() {
    let (source, target) = pair(Method::Nfc);
    let signed_manifest = source.sign_manifest(&manifest()).unwrap();
    let mut other = manifest();
    other.files.pop();
    let other_signed = source.sign_manifest(&other).unwrap();
    let signed = target.sign_receipt(&receipt(&other_signed)).unwrap();
    assert_eq!(
        source.verify_receipt(&signed, &signed_manifest),
        Err(Error::ReceiptMismatch)
    );
}

#[test]
fn each_role_signs_only_its_own_document() {
    let (source, target) = pair(Method::Nfc);
    let signed_manifest = source.sign_manifest(&manifest()).unwrap();
    assert_eq!(target.sign_manifest(&manifest()), Err(Error::WrongRole));
    assert_eq!(
        source.sign_receipt(&receipt(&signed_manifest)),
        Err(Error::WrongRole)
    );
    assert_eq!(
        source.verify_manifest(&signed_manifest),
        Err(Error::WrongRole)
    );
    let signed_receipt = target.sign_receipt(&receipt(&signed_manifest)).unwrap();
    assert_eq!(
        target.verify_receipt(&signed_receipt, &signed_manifest),
        Err(Error::WrongRole)
    );
}

#[test]
fn any_change_to_a_signed_manifest_is_rejected() {
    let (source, target) = pair(Method::Nfc);
    let signed = source.sign_manifest(&manifest()).unwrap();
    for i in [0, 10, signed.len() / 2, signed.len() - 1] {
        let mut tampered = signed.clone();
        tampered[i] ^= 1;
        assert!(target.verify_manifest(&tampered).is_err(), "byte {i}");
    }
    assert!(target.verify_manifest(&signed[..signed.len() - 1]).is_err());
}

#[test]
fn manifest_from_another_session_is_rejected() {
    let (other_source, _) = pair(Method::Nfc);
    let (_, target) = pair(Method::Nfc);
    let signed = other_source.sign_manifest(&manifest()).unwrap();
    assert_eq!(
        target.verify_manifest(&signed),
        Err(Error::SignatureInvalid)
    );
}

/// A manifest signature cannot be passed off as a receipt signature, even
/// between the same two keys: the signing contexts differ.
#[test]
fn signatures_are_domain_separated() {
    let (source, target) = pair(Method::Nfc);
    let signed_manifest = source.sign_manifest(&manifest()).unwrap();
    assert!(
        source
            .verify_receipt(&signed_manifest, &signed_manifest)
            .is_err()
    );
    let signed_receipt = target.sign_receipt(&receipt(&signed_manifest)).unwrap();
    assert!(target.verify_manifest(&signed_receipt).is_err());
}

#[test]
fn unsafe_paths_are_refused() {
    let (source, _) = pair(Method::Nfc);
    for path in [
        "",
        "/sdcard/DCIM/a.jpg",
        "DCIM/../../data/a.jpg",
        "DCIM/./a.jpg",
        "DCIM//a.jpg",
        "DCIM/a.jpg/",
        "DCIM\\a.jpg",
        "DCIM/a\0.jpg",
    ] {
        let mut m = manifest();
        m.files[0].path = path.into();
        assert_eq!(
            source.sign_manifest(&m),
            Err(Error::InvalidManifest),
            "{path:?}"
        );
    }
}

#[test]
fn keeping_metadata_means_sent_equals_original() {
    let (source, _) = pair(Method::Nfc);
    let mut m = manifest();
    m.metadata = MetadataChoice::KeepAll;
    assert_eq!(source.sign_manifest(&m), Err(Error::InvalidManifest));
    for f in &mut m.files {
        f.sent = f.original;
    }
    assert!(source.sign_manifest(&m).is_ok());
}
