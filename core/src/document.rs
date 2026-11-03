//! The manifest the source signs and the receipt the target signs
//! (see docs/media.md), with their canonical encoding.

use crate::Error;
use crate::codec::{Reader, Writer};

/// What the user chose to do with photo and video metadata.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum MetadataChoice {
    KeepAll = 0,
    RemoveLocationAndIdentifiers = 1,
    RemoveAllButDisplay = 2,
}

/// Size and BLAKE3 of one version of a file's bytes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FileDigest {
    pub size: u64,
    pub blake3: [u8; 32],
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ManifestEntry {
    /// Relative to shared storage, `/`-separated, for example `DCIM/Camera/x.jpg`.
    pub path: String,
    pub mime_type: String,
    /// MediaStore `DATE_TAKEN` on the source, in milliseconds since the epoch.
    pub date_taken_ms: Option<i64>,
    /// File modification time, in milliseconds since the epoch.
    pub modified_ms: i64,
    /// The bytes on the source.
    pub original: FileDigest,
    /// The bytes that will be sent, after metadata removal.
    pub sent: FileDigest,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Manifest {
    pub metadata: MetadataChoice,
    pub stripper_version: String,
    pub files: Vec<ManifestEntry>,
}

/// What the target found for one manifest entry during comparison.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum PlanStatus {
    /// Held under neither hash: requested.
    ToCopy = 0,
    /// Held, not in the trash.
    Present = 1,
    /// Held, but only in the target's trash: not copied.
    PresentInTrash = 2,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PlanEntry {
    /// Position of the entry in the manifest.
    pub index: u32,
    pub status: PlanStatus,
}

/// What the plan adds up to. [`plan_totals`] computes it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct PlanTotals {
    /// Sum of the sent sizes of the entries to copy, each sent hash counted
    /// once: same bytes at two paths are copied once.
    pub bytes_to_copy: u64,
    pub to_copy: u64,
    pub present: u64,
    pub present_in_trash: u64,
}

/// The target's plan, signed after comparison and preflight; the source
/// checks it before the copy starts.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Plan {
    /// [`crate::manifest_hash`] of the signed manifest this answers.
    pub manifest_hash: [u8; 32],
    /// One per manifest entry, in any order.
    pub entries: Vec<PlanEntry>,
    pub totals: PlanTotals,
}

/// Why an entry was not copied.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum NotCopiedReason {
    /// The file changed on the old phone after the manifest.
    ChangedOnSource = 0,
    DidNotFit = 1,
    /// Its metadata could not be removed and the user skipped it.
    MetadataNotRemovable = 2,
    /// Failed twice.
    Failed = 3,
    /// The user unticked it.
    Unticked = 4,
}

/// The verified outcome for one manifest entry (docs/media.md, Verification).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReceiptStatus {
    /// Written in this run and read back with the right hash.
    CopiedVerified,
    /// Already on the target and read back with a matching hash.
    PresentVerified,
    /// On the target, but in its trash; not copied, not verified.
    PresentInTrash,
    NotCopied(NotCopiedReason),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReceiptEntry {
    /// Position of the entry in the manifest.
    pub index: u32,
    pub status: ReceiptStatus,
    /// Free text for the summary, for example a `DATE_TAKEN` difference. May be empty.
    pub note: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Receipt {
    /// [`crate::manifest_hash`] of the signed manifest this answers.
    pub manifest_hash: [u8; 32],
    /// [`crate::plan_hash`] of the signed plan this follows.
    pub plan_hash: [u8; 32],
    /// One per manifest entry, in any order.
    pub entries: Vec<ReceiptEntry>,
}

/// The totals a plan with these entries must carry.
pub fn plan_totals(manifest: &Manifest, entries: &[PlanEntry]) -> PlanTotals {
    let mut totals = PlanTotals::default();
    let mut counted = std::collections::HashSet::new();
    for e in entries {
        match e.status {
            PlanStatus::ToCopy => {
                totals.to_copy += 1;
                if let Some(f) = manifest.files.get(e.index as usize) {
                    if counted.insert(f.sent.blake3) {
                        totals.bytes_to_copy = totals.bytes_to_copy.saturating_add(f.sent.size);
                    }
                }
            }
            PlanStatus::Present => totals.present += 1,
            PlanStatus::PresentInTrash => totals.present_in_trash += 1,
        }
    }
    totals
}

/// True when `indices` names every entry of a manifest with `len` entries exactly once.
fn each_entry_once(len: usize, indices: impl ExactSizeIterator<Item = u32>) -> bool {
    if indices.len() != len {
        return false;
    }
    let mut seen = vec![false; len];
    for i in indices {
        match seen.get_mut(i as usize) {
            Some(s) if !*s => *s = true,
            _ => return false,
        }
    }
    true
}

/// A path the target can write without leaving shared storage: relative,
/// `/`-separated, no empty, `.` or `..` components, no backslash or NUL.
fn safe_path(path: &str) -> bool {
    !path.is_empty()
        && !path.contains(['\\', '\0'])
        && path
            .split('/')
            .all(|c| !c.is_empty() && c != "." && c != "..")
}

fn digest(w: &mut Writer, d: &FileDigest) {
    w.u64(d.size).fixed(&d.blake3);
}

fn read_digest(r: &mut Reader) -> Result<FileDigest, Error> {
    Ok(FileDigest {
        size: r.u64()?,
        blake3: r.array()?,
    })
}

fn count(n: usize) -> u32 {
    u32::try_from(n).expect("over 4 billion entries")
}

impl Manifest {
    pub(crate) fn validate(&self) -> Result<(), Error> {
        let keep = self.metadata == MetadataChoice::KeepAll;
        for f in &self.files {
            if !safe_path(&f.path) || (keep && f.sent != f.original) {
                return Err(Error::InvalidManifest);
            }
        }
        Ok(())
    }

    pub(crate) fn encode(&self) -> Vec<u8> {
        let mut w = Writer::new();
        w.u8(self.metadata as u8)
            .str(&self.stripper_version)
            .u32(count(self.files.len()));
        for f in &self.files {
            w.str(&f.path).str(&f.mime_type);
            match f.date_taken_ms {
                Some(t) => w.u8(1).i64(t),
                None => w.u8(0),
            };
            w.i64(f.modified_ms);
            digest(&mut w, &f.original);
            digest(&mut w, &f.sent);
        }
        w.finish()
    }

    pub(crate) fn decode(bytes: &[u8]) -> Result<Self, Error> {
        let mut r = Reader::new(bytes);
        let metadata = match r.u8()? {
            0 => MetadataChoice::KeepAll,
            1 => MetadataChoice::RemoveLocationAndIdentifiers,
            2 => MetadataChoice::RemoveAllButDisplay,
            _ => return Err(Error::Malformed),
        };
        let stripper_version = r.str()?;
        let n = r.u32()?;
        let mut files = Vec::new();
        for _ in 0..n {
            files.push(ManifestEntry {
                path: r.str()?,
                mime_type: r.str()?,
                date_taken_ms: match r.u8()? {
                    0 => None,
                    1 => Some(r.i64()?),
                    _ => return Err(Error::Malformed),
                },
                modified_ms: r.i64()?,
                original: read_digest(&mut r)?,
                sent: read_digest(&mut r)?,
            });
        }
        r.end()?;
        Ok(Manifest {
            metadata,
            stripper_version,
            files,
        })
    }
}

impl Plan {
    /// The rules, against the manifest the plan answers.
    pub(crate) fn validate(&self, manifest: &Manifest) -> Result<(), Error> {
        if each_entry_once(manifest.files.len(), self.entries.iter().map(|e| e.index))
            && self.totals == plan_totals(manifest, &self.entries)
        {
            Ok(())
        } else {
            Err(Error::InvalidPlan)
        }
    }

    pub(crate) fn encode(&self) -> Vec<u8> {
        let mut w = Writer::new();
        w.fixed(&self.manifest_hash).u32(count(self.entries.len()));
        for e in &self.entries {
            w.u32(e.index).u8(e.status as u8);
        }
        let t = &self.totals;
        w.u64(t.bytes_to_copy)
            .u64(t.to_copy)
            .u64(t.present)
            .u64(t.present_in_trash);
        w.finish()
    }

    pub(crate) fn decode(bytes: &[u8]) -> Result<Self, Error> {
        let mut r = Reader::new(bytes);
        let manifest_hash = r.array()?;
        let n = r.u32()?;
        let mut entries = Vec::new();
        for _ in 0..n {
            entries.push(PlanEntry {
                index: r.u32()?,
                status: match r.u8()? {
                    0 => PlanStatus::ToCopy,
                    1 => PlanStatus::Present,
                    2 => PlanStatus::PresentInTrash,
                    _ => return Err(Error::Malformed),
                },
            });
        }
        let totals = PlanTotals {
            bytes_to_copy: r.u64()?,
            to_copy: r.u64()?,
            present: r.u64()?,
            present_in_trash: r.u64()?,
        };
        r.end()?;
        Ok(Plan {
            manifest_hash,
            entries,
            totals,
        })
    }
}

impl NotCopiedReason {
    fn from_u8(v: u8) -> Result<Self, Error> {
        Ok(match v {
            0 => Self::ChangedOnSource,
            1 => Self::DidNotFit,
            2 => Self::MetadataNotRemovable,
            3 => Self::Failed,
            4 => Self::Unticked,
            _ => return Err(Error::Malformed),
        })
    }
}

impl Receipt {
    /// The rules, against the manifest the receipt answers.
    pub(crate) fn validate(&self, manifest: &Manifest) -> Result<(), Error> {
        if each_entry_once(manifest.files.len(), self.entries.iter().map(|e| e.index)) {
            Ok(())
        } else {
            Err(Error::InvalidReceipt)
        }
    }

    pub(crate) fn encode(&self) -> Vec<u8> {
        let mut w = Writer::new();
        w.fixed(&self.manifest_hash)
            .fixed(&self.plan_hash)
            .u32(count(self.entries.len()));
        for e in &self.entries {
            w.u32(e.index);
            match e.status {
                ReceiptStatus::CopiedVerified => w.u8(0),
                ReceiptStatus::PresentVerified => w.u8(1),
                ReceiptStatus::PresentInTrash => w.u8(2),
                ReceiptStatus::NotCopied(reason) => w.u8(3).u8(reason as u8),
            };
            w.str(&e.note);
        }
        w.finish()
    }

    pub(crate) fn decode(bytes: &[u8]) -> Result<Self, Error> {
        let mut r = Reader::new(bytes);
        let manifest_hash = r.array()?;
        let plan_hash = r.array()?;
        let n = r.u32()?;
        let mut entries = Vec::new();
        for _ in 0..n {
            entries.push(ReceiptEntry {
                index: r.u32()?,
                status: match r.u8()? {
                    0 => ReceiptStatus::CopiedVerified,
                    1 => ReceiptStatus::PresentVerified,
                    2 => ReceiptStatus::PresentInTrash,
                    3 => ReceiptStatus::NotCopied(NotCopiedReason::from_u8(r.u8()?)?),
                    _ => return Err(Error::Malformed),
                },
                note: r.str()?,
            });
        }
        r.end()?;
        Ok(Receipt {
            manifest_hash,
            plan_hash,
            entries,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn each_entry_exactly_once() {
        assert!(each_entry_once(3, [2, 0, 1].into_iter()));
        assert!(each_entry_once(0, [].into_iter()));
        assert!(!each_entry_once(3, [0, 1].into_iter()));
        assert!(!each_entry_once(3, [0, 1, 1].into_iter()));
        assert!(!each_entry_once(3, [0, 1, 3].into_iter()));
        assert!(!each_entry_once(2, [0, 1, 2].into_iter()));
    }

    #[test]
    fn paths() {
        for ok in ["a", "DCIM/Camera/x.jpg", "Download/.hidden", "a/b..c"] {
            assert!(safe_path(ok), "{ok}");
        }
        for bad in ["", "/a", "a/", "a//b", "./a", "a/..", "..", "a\\b", "a\0"] {
            assert!(!safe_path(bad), "{bad:?}");
        }
    }
}
