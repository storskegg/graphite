//! uniffi wrapper around `graphite-core` and `graphite-strip` for the Android app.
//!
//! Thin on purpose: every type mirrors one in the core, every method forwards
//! to it. The core's API is unchanged. Objects hold their core value behind a
//! mutex because uniffi hands out shared references.

use std::sync::Mutex;

use graphite_core as core;

mod strip;

uniffi::setup_scaffolding!();

/// The core's errors, one variant each.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Error)]
#[uniffi(flat_error)]
pub enum CoreError {
    Malformed,
    UnexpectedMessage,
    UnsupportedVersion,
    UnsupportedSuite,
    SessionMismatch,
    CommitmentMismatch,
    KeyConfirmationFailed,
    CodeNotConfirmed,
    Failed,
    Crypto,
    Randomness,
    StreamAuthentication,
    StreamTruncated,
    StreamFinished,
    ChunkTooLarge,
    StreamExhausted,
    SignatureInvalid,
    WrongRole,
    InvalidManifest,
    InvalidPlan,
    InvalidReceipt,
    ManifestMismatch,
    PlanMismatch,
    NotInPlan,
    /// An object was used after it was consumed (a pairing finished twice).
    Consumed,
    /// The core added an error this wrapper does not know yet.
    Other,
}

impl std::fmt::Display for CoreError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        std::fmt::Debug::fmt(self, f)
    }
}

impl From<core::Error> for CoreError {
    fn from(e: core::Error) -> Self {
        use core::Error as E;
        match e {
            E::Malformed => Self::Malformed,
            E::UnexpectedMessage => Self::UnexpectedMessage,
            E::UnsupportedVersion => Self::UnsupportedVersion,
            E::UnsupportedSuite => Self::UnsupportedSuite,
            E::SessionMismatch => Self::SessionMismatch,
            E::CommitmentMismatch => Self::CommitmentMismatch,
            E::KeyConfirmationFailed => Self::KeyConfirmationFailed,
            E::CodeNotConfirmed => Self::CodeNotConfirmed,
            E::Failed => Self::Failed,
            E::Crypto => Self::Crypto,
            E::Randomness => Self::Randomness,
            E::StreamAuthentication => Self::StreamAuthentication,
            E::StreamTruncated => Self::StreamTruncated,
            E::StreamFinished => Self::StreamFinished,
            E::ChunkTooLarge => Self::ChunkTooLarge,
            E::StreamExhausted => Self::StreamExhausted,
            E::SignatureInvalid => Self::SignatureInvalid,
            E::WrongRole => Self::WrongRole,
            E::InvalidManifest => Self::InvalidManifest,
            E::InvalidPlan => Self::InvalidPlan,
            E::InvalidReceipt => Self::InvalidReceipt,
            E::ManifestMismatch => Self::ManifestMismatch,
            E::PlanMismatch => Self::PlanMismatch,
            E::NotInPlan => Self::NotInPlan,
            _ => Self::Other,
        }
    }
}

type Result<T> = std::result::Result<T, CoreError>;

/// Largest plaintext in one stream chunk.
#[uniffi::export]
pub fn chunk_size() -> u32 {
    core::CHUNK_SIZE as u32
}

/// Bytes a sealed chunk adds to its plaintext.
#[uniffi::export]
pub fn chunk_overhead() -> u32 {
    core::CHUNK_OVERHEAD as u32
}

// ---------------------------------------------------------------- pairing

#[derive(uniffi::Object)]
pub struct TargetPairing(Mutex<core::TargetPairing>);

#[uniffi::export]
impl TargetPairing {
    #[uniffi::constructor]
    pub fn new() -> Result<Self> {
        Ok(Self(Mutex::new(core::TargetPairing::new()?)))
    }

    /// The first message on the cable.
    pub fn hello(&self) -> Vec<u8> {
        self.0.lock().unwrap().hello()
    }

    pub fn handle(&self, msg: Vec<u8>) -> Result<Option<Vec<u8>>> {
        Ok(self.0.lock().unwrap().handle(&msg)?)
    }

    pub fn code(&self) -> Option<String> {
        self.0.lock().unwrap().code()
    }

    pub fn confirm_code(&self) -> Result<()> {
        Ok(self.0.lock().unwrap().confirm_code()?)
    }

    pub fn finish(&self) -> Result<std::sync::Arc<Session>> {
        let s = self.0.lock().unwrap().finish()?;
        Ok(std::sync::Arc::new(Session(Mutex::new(s))))
    }
}

#[derive(uniffi::Object)]
pub struct SourcePairing(Mutex<core::SourcePairing>);

#[uniffi::export]
impl SourcePairing {
    #[uniffi::constructor]
    pub fn new() -> Result<Self> {
        Ok(Self(Mutex::new(core::SourcePairing::new()?)))
    }

    pub fn handle(&self, msg: Vec<u8>) -> Result<Option<Vec<u8>>> {
        Ok(self.0.lock().unwrap().handle(&msg)?)
    }

    pub fn code(&self) -> Option<String> {
        self.0.lock().unwrap().code()
    }

    pub fn confirm_code(&self) -> Result<()> {
        Ok(self.0.lock().unwrap().confirm_code()?)
    }

    pub fn finish(&self) -> Result<std::sync::Arc<Session>> {
        let s = self.0.lock().unwrap().finish()?;
        Ok(std::sync::Arc::new(Session(Mutex::new(s))))
    }
}

// ---------------------------------------------------------------- documents

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MetadataChoice {
    KeepAll,
    RemoveLocationAndIdentifiers,
    RemoveAllButDisplay,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FileDigest {
    pub size: u64,
    pub blake3: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ManifestEntry {
    pub path: String,
    pub mime_type: String,
    pub date_taken_ms: Option<i64>,
    pub date_added_ms: Option<i64>,
    pub modified_ms: i64,
    pub original: FileDigest,
    pub sent: FileDigest,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Manifest {
    pub metadata: MetadataChoice,
    pub stripper_version: String,
    pub files: Vec<ManifestEntry>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PlanStatus {
    ToCopy,
    Present,
    PresentInTrash,
    Unticked,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct PlanEntry {
    pub index: u32,
    pub status: PlanStatus,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct PlanTotals {
    pub bytes_to_copy: u64,
    pub to_copy: u64,
    pub present: u64,
    pub present_in_trash: u64,
    pub unticked: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Plan {
    pub manifest_hash: Vec<u8>,
    pub entries: Vec<PlanEntry>,
    pub totals: PlanTotals,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum NotCopiedReason {
    ChangedOnSource,
    DidNotFit,
    MetadataNotRemovable,
    Failed,
    Unticked,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ReceiptStatus {
    CopiedVerified,
    PresentVerified,
    PresentInTrash,
    NotCopied { reason: NotCopiedReason },
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ReceiptEntry {
    pub index: u32,
    pub status: ReceiptStatus,
    pub note: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Receipt {
    pub manifest_hash: Vec<u8>,
    pub plan_hash: Vec<u8>,
    pub entries: Vec<ReceiptEntry>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Chunk {
    pub data: Vec<u8>,
    pub last: bool,
}

fn hash32(v: &[u8]) -> Result<[u8; 32]> {
    v.try_into().map_err(|_| CoreError::Malformed)
}

impl From<MetadataChoice> for core::MetadataChoice {
    fn from(c: MetadataChoice) -> Self {
        match c {
            MetadataChoice::KeepAll => Self::KeepAll,
            MetadataChoice::RemoveLocationAndIdentifiers => Self::RemoveLocationAndIdentifiers,
            MetadataChoice::RemoveAllButDisplay => Self::RemoveAllButDisplay,
        }
    }
}

impl From<core::MetadataChoice> for MetadataChoice {
    fn from(c: core::MetadataChoice) -> Self {
        match c {
            core::MetadataChoice::KeepAll => Self::KeepAll,
            core::MetadataChoice::RemoveLocationAndIdentifiers => {
                Self::RemoveLocationAndIdentifiers
            }
            core::MetadataChoice::RemoveAllButDisplay => Self::RemoveAllButDisplay,
        }
    }
}

impl FileDigest {
    fn to_core(&self) -> Result<core::FileDigest> {
        Ok(core::FileDigest {
            size: self.size,
            blake3: hash32(&self.blake3)?,
        })
    }
    fn from_core(d: &core::FileDigest) -> Self {
        Self {
            size: d.size,
            blake3: d.blake3.to_vec(),
        }
    }
}

impl Manifest {
    fn to_core(&self) -> Result<core::Manifest> {
        Ok(core::Manifest {
            metadata: self.metadata.into(),
            stripper_version: self.stripper_version.clone(),
            files: self
                .files
                .iter()
                .map(|f| {
                    Ok(core::ManifestEntry {
                        path: f.path.clone(),
                        mime_type: f.mime_type.clone(),
                        date_taken_ms: f.date_taken_ms,
                        date_added_ms: f.date_added_ms,
                        modified_ms: f.modified_ms,
                        original: f.original.to_core()?,
                        sent: f.sent.to_core()?,
                    })
                })
                .collect::<Result<_>>()?,
        })
    }

    fn from_core(m: core::Manifest) -> Self {
        Self {
            metadata: m.metadata.into(),
            stripper_version: m.stripper_version,
            files: m
                .files
                .into_iter()
                .map(|f| ManifestEntry {
                    path: f.path,
                    mime_type: f.mime_type,
                    date_taken_ms: f.date_taken_ms,
                    date_added_ms: f.date_added_ms,
                    modified_ms: f.modified_ms,
                    original: FileDigest::from_core(&f.original),
                    sent: FileDigest::from_core(&f.sent),
                })
                .collect(),
        }
    }
}

impl From<PlanStatus> for core::PlanStatus {
    fn from(s: PlanStatus) -> Self {
        match s {
            PlanStatus::ToCopy => Self::ToCopy,
            PlanStatus::Present => Self::Present,
            PlanStatus::PresentInTrash => Self::PresentInTrash,
            PlanStatus::Unticked => Self::Unticked,
        }
    }
}

impl From<core::PlanStatus> for PlanStatus {
    fn from(s: core::PlanStatus) -> Self {
        match s {
            core::PlanStatus::ToCopy => Self::ToCopy,
            core::PlanStatus::Present => Self::Present,
            core::PlanStatus::PresentInTrash => Self::PresentInTrash,
            core::PlanStatus::Unticked => Self::Unticked,
        }
    }
}

impl From<PlanTotals> for core::PlanTotals {
    fn from(t: PlanTotals) -> Self {
        Self {
            bytes_to_copy: t.bytes_to_copy,
            to_copy: t.to_copy,
            present: t.present,
            present_in_trash: t.present_in_trash,
            unticked: t.unticked,
        }
    }
}

impl From<core::PlanTotals> for PlanTotals {
    fn from(t: core::PlanTotals) -> Self {
        Self {
            bytes_to_copy: t.bytes_to_copy,
            to_copy: t.to_copy,
            present: t.present,
            present_in_trash: t.present_in_trash,
            unticked: t.unticked,
        }
    }
}

fn plan_entries_to_core(entries: &[PlanEntry]) -> Vec<core::PlanEntry> {
    entries
        .iter()
        .map(|e| core::PlanEntry {
            index: e.index,
            status: e.status.into(),
        })
        .collect()
}

impl Plan {
    fn to_core(&self) -> Result<core::Plan> {
        Ok(core::Plan {
            manifest_hash: hash32(&self.manifest_hash)?,
            entries: plan_entries_to_core(&self.entries),
            totals: self.totals.into(),
        })
    }

    fn from_core(p: core::Plan) -> Self {
        Self {
            manifest_hash: p.manifest_hash.to_vec(),
            entries: p
                .entries
                .iter()
                .map(|e| PlanEntry {
                    index: e.index,
                    status: e.status.into(),
                })
                .collect(),
            totals: p.totals.into(),
        }
    }
}

impl From<NotCopiedReason> for core::NotCopiedReason {
    fn from(r: NotCopiedReason) -> Self {
        match r {
            NotCopiedReason::ChangedOnSource => Self::ChangedOnSource,
            NotCopiedReason::DidNotFit => Self::DidNotFit,
            NotCopiedReason::MetadataNotRemovable => Self::MetadataNotRemovable,
            NotCopiedReason::Failed => Self::Failed,
            NotCopiedReason::Unticked => Self::Unticked,
        }
    }
}

impl From<core::NotCopiedReason> for NotCopiedReason {
    fn from(r: core::NotCopiedReason) -> Self {
        match r {
            core::NotCopiedReason::ChangedOnSource => Self::ChangedOnSource,
            core::NotCopiedReason::DidNotFit => Self::DidNotFit,
            core::NotCopiedReason::MetadataNotRemovable => Self::MetadataNotRemovable,
            core::NotCopiedReason::Failed => Self::Failed,
            core::NotCopiedReason::Unticked => Self::Unticked,
        }
    }
}

impl From<ReceiptStatus> for core::ReceiptStatus {
    fn from(s: ReceiptStatus) -> Self {
        match s {
            ReceiptStatus::CopiedVerified => Self::CopiedVerified,
            ReceiptStatus::PresentVerified => Self::PresentVerified,
            ReceiptStatus::PresentInTrash => Self::PresentInTrash,
            ReceiptStatus::NotCopied { reason } => Self::NotCopied(reason.into()),
        }
    }
}

impl From<core::ReceiptStatus> for ReceiptStatus {
    fn from(s: core::ReceiptStatus) -> Self {
        match s {
            core::ReceiptStatus::CopiedVerified => Self::CopiedVerified,
            core::ReceiptStatus::PresentVerified => Self::PresentVerified,
            core::ReceiptStatus::PresentInTrash => Self::PresentInTrash,
            core::ReceiptStatus::NotCopied(r) => Self::NotCopied { reason: r.into() },
        }
    }
}

impl Receipt {
    fn to_core(&self) -> Result<core::Receipt> {
        Ok(core::Receipt {
            manifest_hash: hash32(&self.manifest_hash)?,
            plan_hash: hash32(&self.plan_hash)?,
            entries: self
                .entries
                .iter()
                .map(|e| core::ReceiptEntry {
                    index: e.index,
                    status: e.status.into(),
                    note: e.note.clone(),
                })
                .collect(),
        })
    }

    fn from_core(r: core::Receipt) -> Self {
        Self {
            manifest_hash: r.manifest_hash.to_vec(),
            plan_hash: r.plan_hash.to_vec(),
            entries: r
                .entries
                .into_iter()
                .map(|e| ReceiptEntry {
                    index: e.index,
                    status: e.status.into(),
                    note: e.note,
                })
                .collect(),
        }
    }
}

/// The totals a plan with these entries must carry.
#[uniffi::export]
pub fn plan_totals(manifest: Manifest, entries: Vec<PlanEntry>) -> Result<PlanTotals> {
    Ok(core::plan_totals(&manifest.to_core()?, &plan_entries_to_core(&entries)).into())
}

/// Source, during the copy: whether the plan lets the target request `index`.
#[uniffi::export]
pub fn plan_check_request(plan: Plan, index: u32) -> Result<()> {
    Ok(plan.to_core()?.check_request(index)?)
}

/// The hash a plan or receipt quotes for a signed manifest.
#[uniffi::export]
pub fn manifest_hash(signed_manifest: Vec<u8>) -> Vec<u8> {
    core::manifest_hash(&signed_manifest).to_vec()
}

/// The hash a receipt quotes for a signed plan.
#[uniffi::export]
pub fn plan_hash(signed_plan: Vec<u8>) -> Vec<u8> {
    core::plan_hash(&signed_plan).to_vec()
}

// ---------------------------------------------------------------- session

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Role {
    Source,
    Target,
}

#[derive(uniffi::Object)]
pub struct Session(Mutex<core::Session>);

#[uniffi::export]
impl Session {
    pub fn role(&self) -> Role {
        match self.0.lock().unwrap().role() {
            core::Role::Source => Role::Source,
            core::Role::Target => Role::Target,
        }
    }

    pub fn transcript_hash(&self) -> Vec<u8> {
        self.0.lock().unwrap().transcript_hash().to_vec()
    }

    pub fn seal(&self, plaintext: Vec<u8>, last: bool) -> Result<Vec<u8>> {
        Ok(self.0.lock().unwrap().seal(&plaintext, last)?)
    }

    pub fn open(&self, chunk: Vec<u8>) -> Result<Chunk> {
        let c = self.0.lock().unwrap().open(&chunk)?;
        Ok(Chunk {
            data: c.data,
            last: c.last,
        })
    }

    pub fn finish_receiving(&self) -> Result<()> {
        Ok(self.0.lock().unwrap().finish_receiving()?)
    }

    pub fn sign_manifest(&self, manifest: Manifest) -> Result<Vec<u8>> {
        Ok(self.0.lock().unwrap().sign_manifest(&manifest.to_core()?)?)
    }

    pub fn verify_manifest(&self, signed: Vec<u8>) -> Result<Manifest> {
        let m = self.0.lock().unwrap().verify_manifest(&signed)?;
        Ok(Manifest::from_core(m))
    }

    pub fn sign_plan(&self, plan: Plan, signed_manifest: Vec<u8>) -> Result<Vec<u8>> {
        Ok(self
            .0
            .lock()
            .unwrap()
            .sign_plan(&plan.to_core()?, &signed_manifest)?)
    }

    pub fn verify_plan(&self, signed: Vec<u8>, signed_manifest: Vec<u8>) -> Result<Plan> {
        let p = self
            .0
            .lock()
            .unwrap()
            .verify_plan(&signed, &signed_manifest)?;
        Ok(Plan::from_core(p))
    }

    pub fn sign_receipt(
        &self,
        receipt: Receipt,
        signed_manifest: Vec<u8>,
        signed_plan: Vec<u8>,
    ) -> Result<Vec<u8>> {
        Ok(self.0.lock().unwrap().sign_receipt(
            &receipt.to_core()?,
            &signed_manifest,
            &signed_plan,
        )?)
    }

    pub fn verify_receipt(
        &self,
        signed: Vec<u8>,
        signed_manifest: Vec<u8>,
        signed_plan: Vec<u8>,
    ) -> Result<Receipt> {
        let r = self
            .0
            .lock()
            .unwrap()
            .verify_receipt(&signed, &signed_manifest, &signed_plan)?;
        Ok(Receipt::from_core(r))
    }
}

// ---------------------------------------------------------------- hashing

/// Streaming BLAKE3 of a file's bytes.
#[derive(uniffi::Object)]
pub struct FileHasher(Mutex<core::FileHasher>);

#[uniffi::export]
impl FileHasher {
    #[uniffi::constructor]
    pub fn new() -> Self {
        Self(Mutex::new(core::FileHasher::new()))
    }

    pub fn update(&self, bytes: Vec<u8>) {
        self.0.lock().unwrap().update(&bytes);
    }

    pub fn finalize(&self) -> Vec<u8> {
        self.0.lock().unwrap().finalize().to_vec()
    }
}

impl Default for FileHasher {
    fn default() -> Self {
        Self::new()
    }
}

/// BLAKE3 of `bytes` in one call.
#[uniffi::export]
pub fn blake3_hash(bytes: Vec<u8>) -> Vec<u8> {
    core::FileHasher::hash(&bytes).to_vec()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pair() -> (std::sync::Arc<Session>, std::sync::Arc<Session>) {
        let target = TargetPairing::new().unwrap();
        let source = SourcePairing::new().unwrap();
        let mut msg = target.hello();
        while let Some(reply) = source.handle(msg.clone()).unwrap() {
            match target.handle(reply).unwrap() {
                Some(next) => msg = next,
                None => break,
            }
        }
        assert_eq!(source.code(), target.code());
        source.confirm_code().unwrap();
        target.confirm_code().unwrap();
        (source.finish().unwrap(), target.finish().unwrap())
    }

    #[test]
    fn documents_round_trip_through_the_wrapper() {
        let (source, target) = pair();
        let digest = FileDigest {
            size: 3,
            blake3: blake3_hash(b"abc".to_vec()),
        };
        let manifest = Manifest {
            metadata: MetadataChoice::KeepAll,
            stripper_version: "none".into(),
            files: vec![ManifestEntry {
                path: "DCIM/a.jpg".into(),
                mime_type: "image/jpeg".into(),
                date_taken_ms: Some(1),
                date_added_ms: None,
                modified_ms: 2,
                original: digest.clone(),
                sent: digest,
            }],
        };
        let signed_manifest = source.sign_manifest(manifest.clone()).unwrap();
        assert_eq!(
            target.verify_manifest(signed_manifest.clone()).unwrap(),
            manifest
        );

        let entries = vec![PlanEntry {
            index: 0,
            status: PlanStatus::ToCopy,
        }];
        let plan = Plan {
            manifest_hash: manifest_hash(signed_manifest.clone()),
            totals: plan_totals(manifest.clone(), entries.clone()).unwrap(),
            entries,
        };
        let signed_plan = target
            .sign_plan(plan.clone(), signed_manifest.clone())
            .unwrap();
        let verified = source
            .verify_plan(signed_plan.clone(), signed_manifest.clone())
            .unwrap();
        assert_eq!(verified, plan);
        assert!(plan_check_request(verified, 0).is_ok());

        let receipt = Receipt {
            manifest_hash: manifest_hash(signed_manifest.clone()),
            plan_hash: plan_hash(signed_plan.clone()),
            entries: vec![ReceiptEntry {
                index: 0,
                status: ReceiptStatus::CopiedVerified,
                note: String::new(),
            }],
        };
        let signed_receipt = target
            .sign_receipt(
                receipt.clone(),
                signed_manifest.clone(),
                signed_plan.clone(),
            )
            .unwrap();
        assert_eq!(
            source
                .verify_receipt(signed_receipt, signed_manifest, signed_plan)
                .unwrap(),
            receipt
        );
    }

    #[test]
    fn stream_through_the_wrapper() {
        let (source, target) = pair();
        let sealed = source.seal(b"hello".to_vec(), true).unwrap();
        let chunk = target.open(sealed).unwrap();
        assert_eq!(chunk.data, b"hello");
        assert!(chunk.last);
        target.finish_receiving().unwrap();
        assert_eq!(
            target.seal(vec![0; 1 << 21], false),
            Err(CoreError::ChunkTooLarge)
        );
    }

    #[test]
    fn bad_hash_length_is_malformed() {
        let plan = Plan {
            manifest_hash: vec![0; 31],
            entries: vec![],
            totals: PlanTotals {
                bytes_to_copy: 0,
                to_copy: 0,
                present: 0,
                present_in_trash: 0,
                unticked: 0,
            },
        };
        assert_eq!(plan_check_request(plan, 0), Err(CoreError::Malformed));
    }
}
