mod common;

use common::pair;
use graphite_core::{
    Error, FileDigest, FileHasher, Manifest, ManifestEntry, MetadataChoice, NotCopiedReason, Plan,
    PlanEntry, PlanStatus, PlanTotals, Receipt, ReceiptEntry, ReceiptStatus, Session,
    manifest_hash, plan_hash, plan_totals,
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
                date_added_ms: Some(1_767_268_802_000),
                modified_ms: 1_767_268_801_000,
                original: digest(b"jpeg with gps"),
                sent: digest(b"jpeg without gps"),
            },
            ManifestEntry {
                path: "Download/r\u{e9}sum\u{e9}.pdf".into(),
                mime_type: "application/pdf".into(),
                date_taken_ms: None,
                date_added_ms: None,
                modified_ms: 1_700_000_000_000,
                original: digest(b"pdf"),
                sent: digest(b"pdf"),
            },
        ],
    }
}

/// A copy of the first file at another path: same bytes, so the same sent hash.
fn duplicate_entry() -> ManifestEntry {
    ManifestEntry {
        path: "Pictures/PXL_20260101_120000000.jpg".into(),
        ..manifest().files[0].clone()
    }
}

fn manifest3() -> Manifest {
    let mut m = manifest();
    m.files.push(duplicate_entry());
    m
}

fn plan_entries() -> Vec<PlanEntry> {
    vec![
        PlanEntry {
            index: 0,
            status: PlanStatus::ToCopy,
        },
        PlanEntry {
            index: 1,
            status: PlanStatus::Present,
        },
        PlanEntry {
            index: 2,
            status: PlanStatus::ToCopy,
        },
    ]
}

fn plan(signed_manifest: &[u8]) -> Plan {
    let entries = plan_entries();
    Plan {
        manifest_hash: manifest_hash(signed_manifest),
        totals: plan_totals(&manifest3(), &entries),
        entries,
    }
}

fn receipt(signed_manifest: &[u8], signed_plan: &[u8]) -> Receipt {
    Receipt {
        manifest_hash: manifest_hash(signed_manifest),
        plan_hash: plan_hash(signed_plan),
        entries: vec![
            ReceiptEntry {
                index: 0,
                status: ReceiptStatus::CopiedVerified,
                note: String::new(),
            },
            ReceiptEntry {
                index: 1,
                status: ReceiptStatus::PresentVerified,
                note: "date taken differs".into(),
            },
            ReceiptEntry {
                index: 2,
                status: ReceiptStatus::NotCopied(NotCopiedReason::ChangedOnSource),
                note: String::new(),
            },
        ],
    }
}

/// A source and target that have exchanged the three-entry manifest and plan.
struct Transfer {
    source: Session,
    target: Session,
    manifest: Vec<u8>,
    plan: Vec<u8>,
}

fn transfer() -> Transfer {
    let (source, target) = pair();
    let manifest = source.sign_manifest(&manifest3()).unwrap();
    let plan = target.sign_plan(&plan(&manifest), &manifest).unwrap();
    Transfer {
        source,
        target,
        manifest,
        plan,
    }
}

#[test]
fn manifest_signed_by_the_source_verifies_at_the_target() {
    let (source, target) = pair();
    let signed = source.sign_manifest(&manifest()).unwrap();
    assert_eq!(target.verify_manifest(&signed).unwrap(), manifest());
}

#[test]
fn plan_signed_by_the_target_verifies_at_the_source() {
    let t = transfer();
    let verified = t.source.verify_plan(&t.plan, &t.manifest).unwrap();
    assert_eq!(verified, plan(&t.manifest));
    // The duplicate is copied once: its bytes count once.
    assert_eq!(
        verified.totals,
        PlanTotals {
            bytes_to_copy: b"jpeg without gps".len() as u64,
            to_copy: 2,
            present: 1,
            present_in_trash: 0,
            unticked: 0,
        }
    );
}

#[test]
fn receipt_signed_by_the_target_verifies_at_the_source() {
    let t = transfer();
    let r = receipt(&t.manifest, &t.plan);
    let signed = t.target.sign_receipt(&r, &t.manifest, &t.plan).unwrap();
    assert_eq!(
        t.source
            .verify_receipt(&signed, &t.manifest, &t.plan)
            .unwrap(),
        r
    );
}

#[test]
fn every_receipt_status_round_trips() {
    let t = transfer();
    let statuses = [
        ReceiptStatus::CopiedVerified,
        ReceiptStatus::PresentVerified,
        ReceiptStatus::PresentInTrash,
        ReceiptStatus::NotCopied(NotCopiedReason::ChangedOnSource),
        ReceiptStatus::NotCopied(NotCopiedReason::DidNotFit),
        ReceiptStatus::NotCopied(NotCopiedReason::MetadataNotRemovable),
        ReceiptStatus::NotCopied(NotCopiedReason::Failed),
    ];
    for status in statuses {
        let mut r = receipt(&t.manifest, &t.plan);
        r.entries[0].status = status;
        let signed = t.target.sign_receipt(&r, &t.manifest, &t.plan).unwrap();
        assert_eq!(
            t.source
                .verify_receipt(&signed, &t.manifest, &t.plan)
                .unwrap(),
            r
        );
    }
    for status in [
        PlanStatus::ToCopy,
        PlanStatus::Present,
        PlanStatus::PresentInTrash,
        PlanStatus::Unticked,
    ] {
        let mut entries = plan_entries();
        entries[1].status = status;
        let p = Plan {
            manifest_hash: manifest_hash(&t.manifest),
            totals: plan_totals(&manifest3(), &entries),
            entries,
        };
        let signed = t.target.sign_plan(&p, &t.manifest).unwrap();
        assert_eq!(t.source.verify_plan(&signed, &t.manifest).unwrap(), p);
    }
}

#[test]
fn plan_for_another_manifest_is_rejected() {
    let t = transfer();
    let other = t.source.sign_manifest(&manifest3()).unwrap();
    // Same content, different signature (hedged signing): a different manifest.
    assert_ne!(other, t.manifest);
    assert_eq!(
        t.source.verify_plan(&t.plan, &other),
        Err(Error::ManifestMismatch)
    );
    // The target will not sign a plan whose hash names another manifest either.
    assert_eq!(
        t.target.sign_plan(&plan(&other), &t.manifest),
        Err(Error::ManifestMismatch)
    );
}

#[test]
fn plan_with_a_missing_duplicated_or_unknown_entry_is_rejected() {
    let t = transfer();
    let cases: [fn(&mut Vec<PlanEntry>); 4] = [
        |e| {
            e.pop();
        },
        |e| e[2].index = 0,
        |e| {
            e.push(PlanEntry {
                index: 1,
                status: PlanStatus::Present,
            })
        },
        |e| e[2].index = 3,
    ];
    for (i, change) in cases.iter().enumerate() {
        let mut entries = plan_entries();
        change(&mut entries);
        let p = Plan {
            manifest_hash: manifest_hash(&t.manifest),
            totals: plan_totals(&manifest3(), &entries),
            entries,
        };
        assert_eq!(
            t.target.sign_plan(&p, &t.manifest),
            Err(Error::InvalidPlan),
            "case {i}"
        );
    }
}

#[test]
fn plan_with_wrong_totals_is_rejected() {
    let t = transfer();
    let changes: [fn(&mut PlanTotals); 5] = [
        |x| x.bytes_to_copy += 1,
        |x| x.to_copy -= 1,
        |x| x.present += 1,
        |x| x.present_in_trash += 1,
        |x| x.unticked += 1,
    ];
    for (i, change) in changes.iter().enumerate() {
        let mut p = plan(&t.manifest);
        change(&mut p.totals);
        assert_eq!(
            t.target.sign_plan(&p, &t.manifest),
            Err(Error::InvalidPlan),
            "case {i}"
        );
    }
}

#[test]
fn receipt_with_a_missing_duplicated_or_unknown_entry_is_rejected() {
    let t = transfer();
    let cases: [fn(&mut Vec<ReceiptEntry>); 3] = [
        |e| {
            e.pop();
        },
        |e| e[2].index = 1,
        |e| e[2].index = 7,
    ];
    for (i, change) in cases.iter().enumerate() {
        let mut r = receipt(&t.manifest, &t.plan);
        change(&mut r.entries);
        assert_eq!(
            t.target.sign_receipt(&r, &t.manifest, &t.plan),
            Err(Error::InvalidReceipt),
            "case {i}"
        );
    }
}

#[test]
fn receipt_for_another_manifest_or_plan_is_rejected() {
    let t = transfer();
    let other_manifest = t.source.sign_manifest(&manifest3()).unwrap();
    let other_plan = t.target.sign_plan(&plan(&t.manifest), &t.manifest).unwrap();
    assert_ne!(other_plan, t.plan);

    let r = receipt(&t.manifest, &other_plan);
    let signed = t.target.sign_receipt(&r, &t.manifest, &other_plan).unwrap();
    assert_eq!(
        t.source.verify_receipt(&signed, &t.manifest, &t.plan),
        Err(Error::PlanMismatch)
    );
    assert_eq!(
        t.source
            .verify_receipt(&signed, &other_manifest, &other_plan),
        Err(Error::ManifestMismatch)
    );
    assert_eq!(
        t.target.sign_receipt(&r, &t.manifest, &t.plan),
        Err(Error::PlanMismatch)
    );
}

#[test]
fn each_role_signs_only_its_own_document() {
    let t = transfer();
    let r = receipt(&t.manifest, &t.plan);
    let signed_receipt = t.target.sign_receipt(&r, &t.manifest, &t.plan).unwrap();
    assert_eq!(t.target.sign_manifest(&manifest()), Err(Error::WrongRole));
    assert_eq!(t.source.verify_manifest(&t.manifest), Err(Error::WrongRole));
    assert_eq!(
        t.source.sign_plan(&plan(&t.manifest), &t.manifest),
        Err(Error::WrongRole)
    );
    assert_eq!(
        t.target.verify_plan(&t.plan, &t.manifest),
        Err(Error::WrongRole)
    );
    assert_eq!(
        t.source.sign_receipt(&r, &t.manifest, &t.plan),
        Err(Error::WrongRole)
    );
    assert_eq!(
        t.target
            .verify_receipt(&signed_receipt, &t.manifest, &t.plan),
        Err(Error::WrongRole)
    );
}

#[test]
fn plan_and_receipt_from_another_session_are_rejected() {
    let t = transfer();
    let other = transfer();
    // The other target signs a plan and receipt for this transfer's manifest.
    let p = other.target.sign_plan(&plan(&t.manifest), &t.manifest);
    // It cannot: that manifest's signature is not from its own peer.
    assert_eq!(p, Err(Error::SignatureInvalid));
    // Documents signed in the other session are refused here.
    assert_eq!(
        t.source.verify_plan(&other.plan, &other.manifest),
        Err(Error::SignatureInvalid)
    );
    let r = receipt(&other.manifest, &other.plan);
    let signed = other
        .target
        .sign_receipt(&r, &other.manifest, &other.plan)
        .unwrap();
    assert_eq!(
        t.source
            .verify_receipt(&signed, &other.manifest, &other.plan),
        Err(Error::SignatureInvalid)
    );
}

#[test]
fn any_change_to_a_signed_plan_is_rejected() {
    let t = transfer();
    for i in [0, 10, t.plan.len() / 2, t.plan.len() - 1] {
        let mut tampered = t.plan.clone();
        tampered[i] ^= 1;
        assert!(
            t.source.verify_plan(&tampered, &t.manifest).is_err(),
            "byte {i}"
        );
    }
}

#[test]
fn any_change_to_a_signed_manifest_is_rejected() {
    let (source, target) = pair();
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
    let (other_source, _) = pair();
    let (_, target) = pair();
    let signed = other_source.sign_manifest(&manifest()).unwrap();
    assert_eq!(
        target.verify_manifest(&signed),
        Err(Error::SignatureInvalid)
    );
}

/// No signed document passes as another kind, even between the same two
/// keys: the plan and the receipt are both signed by the target, and only the
/// context strings tell them apart.
#[test]
fn signatures_are_domain_separated() {
    let t = transfer();
    let r = receipt(&t.manifest, &t.plan);
    let signed_receipt = t.target.sign_receipt(&r, &t.manifest, &t.plan).unwrap();

    // Plan presented as a receipt, and the reverse.
    assert_eq!(
        t.source.verify_receipt(&t.plan, &t.manifest, &t.plan),
        Err(Error::SignatureInvalid)
    );
    assert_eq!(
        t.source.verify_plan(&signed_receipt, &t.manifest),
        Err(Error::SignatureInvalid)
    );
    // Manifest presented as a plan or receipt, and plan or receipt as a manifest.
    assert!(t.source.verify_plan(&t.manifest, &t.manifest).is_err());
    assert!(
        t.source
            .verify_receipt(&t.manifest, &t.manifest, &t.plan)
            .is_err()
    );
    assert!(t.target.verify_manifest(&t.plan).is_err());
    assert!(t.target.verify_manifest(&signed_receipt).is_err());
}

#[test]
fn unsafe_paths_are_refused() {
    let (source, _) = pair();
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
    let (source, _) = pair();
    let mut m = manifest();
    m.metadata = MetadataChoice::KeepAll;
    assert_eq!(source.sign_manifest(&m), Err(Error::InvalidManifest));
    for f in &mut m.files {
        f.sent = f.original;
    }
    assert!(source.sign_manifest(&m).is_ok());
}

/// The second copy of the duplicate is unticked: a plan and its receipt.
fn unticked_transfer() -> (Transfer, Receipt) {
    let (source, target) = pair();
    let manifest = source.sign_manifest(&manifest3()).unwrap();
    let mut entries = plan_entries();
    entries[0].status = PlanStatus::Unticked;
    let p = Plan {
        manifest_hash: manifest_hash(&manifest),
        totals: plan_totals(&manifest3(), &entries),
        entries,
    };
    let plan = target.sign_plan(&p, &manifest).unwrap();
    let mut r = receipt(&manifest, &plan);
    r.entries[0].status = ReceiptStatus::NotCopied(NotCopiedReason::Unticked);
    let t = Transfer {
        source,
        target,
        manifest,
        plan,
    };
    (t, r)
}

#[test]
fn unticked_entries_are_in_the_plan_and_its_totals() {
    let (t, r) = unticked_transfer();
    let plan = t.source.verify_plan(&t.plan, &t.manifest).unwrap();
    // Entry 0 is unticked; entry 2 has the same sent bytes and is still copied.
    assert_eq!(
        plan.totals,
        PlanTotals {
            bytes_to_copy: b"jpeg without gps".len() as u64,
            to_copy: 1,
            present: 1,
            present_in_trash: 0,
            unticked: 1,
        }
    );
    let signed = t.target.sign_receipt(&r, &t.manifest, &t.plan).unwrap();
    assert_eq!(
        t.source
            .verify_receipt(&signed, &t.manifest, &t.plan)
            .unwrap(),
        r
    );
}

#[test]
fn unticked_bytes_are_not_counted() {
    let mut entries = plan_entries();
    entries[0].status = PlanStatus::Unticked;
    entries[2].status = PlanStatus::Unticked;
    assert_eq!(plan_totals(&manifest3(), &entries).bytes_to_copy, 0);
}

#[test]
fn receipt_must_agree_with_the_plan_on_unticked_entries() {
    // Unticked in the plan, but the receipt says it was copied, or present,
    // or not copied for another reason.
    let (t, good) = unticked_transfer();
    for status in [
        ReceiptStatus::CopiedVerified,
        ReceiptStatus::PresentVerified,
        ReceiptStatus::PresentInTrash,
        ReceiptStatus::NotCopied(NotCopiedReason::Failed),
    ] {
        let mut r = good.clone();
        r.entries[0].status = status;
        assert_eq!(
            t.target.sign_receipt(&r, &t.manifest, &t.plan),
            Err(Error::InvalidReceipt),
            "{status:?}"
        );
    }

    // Unticked in the receipt, but the plan had it to copy.
    let t = transfer();
    let mut r = receipt(&t.manifest, &t.plan);
    r.entries[0].status = ReceiptStatus::NotCopied(NotCopiedReason::Unticked);
    assert_eq!(
        t.target.sign_receipt(&r, &t.manifest, &t.plan),
        Err(Error::InvalidReceipt)
    );
}

#[test]
fn source_sends_only_what_the_plan_asks_for() {
    let (t, _) = unticked_transfer();
    let plan = t.source.verify_plan(&t.plan, &t.manifest).unwrap();
    assert_eq!(plan.check_request(2), Ok(()));
    // Unticked, present, and not in the manifest at all.
    assert_eq!(plan.check_request(0), Err(Error::NotInPlan));
    assert_eq!(plan.check_request(1), Err(Error::NotInPlan));
    assert_eq!(plan.check_request(3), Err(Error::NotInPlan));
}

#[test]
fn date_added_round_trips_with_and_without_a_value() {
    let (source, target) = pair();
    for (taken, added) in [
        (None, None),
        (None, Some(1_600_000_000_000)),
        (Some(1_500_000_000_000), None),
        (Some(-1), Some(i64::MAX)),
    ] {
        let mut m = manifest();
        m.files[0].date_taken_ms = taken;
        m.files[0].date_added_ms = added;
        let signed = source.sign_manifest(&m).unwrap();
        let verified = target.verify_manifest(&signed).unwrap();
        assert_eq!(verified.files[0].date_taken_ms, taken);
        assert_eq!(verified.files[0].date_added_ms, added);
        assert_eq!(verified, m);
    }
}
