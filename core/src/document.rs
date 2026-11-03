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

/// What happened to one manifest entry.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum Outcome {
    Copied = 0,
    /// The same bytes (original or sent) were already on the target.
    AlreadyPresent = 1,
    /// The original, with its metadata, was already on the target while the user chose to strip.
    AlreadyPresentWithMetadata = 2,
    /// Not copied by the user's choice, for example a RAW file that cannot be stripped.
    Skipped = 3,
    Failed = 4,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReceiptEntry {
    pub path: String,
    pub sent_blake3: [u8; 32],
    pub outcome: Outcome,
    /// Free text for the summary, may be empty.
    pub note: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Receipt {
    /// [`crate::manifest_hash`] of the signed manifest this answers.
    pub manifest_hash: [u8; 32],
    pub entries: Vec<ReceiptEntry>,
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

impl Receipt {
    pub(crate) fn validate(&self) -> Result<(), Error> {
        if self.entries.iter().all(|e| safe_path(&e.path)) {
            Ok(())
        } else {
            Err(Error::InvalidManifest)
        }
    }

    pub(crate) fn encode(&self) -> Vec<u8> {
        let mut w = Writer::new();
        w.fixed(&self.manifest_hash).u32(count(self.entries.len()));
        for e in &self.entries {
            w.str(&e.path)
                .fixed(&e.sent_blake3)
                .u8(e.outcome as u8)
                .str(&e.note);
        }
        w.finish()
    }

    pub(crate) fn decode(bytes: &[u8]) -> Result<Self, Error> {
        let mut r = Reader::new(bytes);
        let manifest_hash = r.array()?;
        let n = r.u32()?;
        let mut entries = Vec::new();
        for _ in 0..n {
            entries.push(ReceiptEntry {
                path: r.str()?,
                sent_blake3: r.array()?,
                outcome: match r.u8()? {
                    0 => Outcome::Copied,
                    1 => Outcome::AlreadyPresent,
                    2 => Outcome::AlreadyPresentWithMetadata,
                    3 => Outcome::Skipped,
                    4 => Outcome::Failed,
                    _ => return Err(Error::Malformed),
                },
                note: r.str()?,
            });
        }
        r.end()?;
        Ok(Receipt {
            manifest_hash,
            entries,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
