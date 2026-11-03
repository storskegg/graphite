//! uniffi wrapper around `graphite-strip`, working on files.
//!
//! The source opens each file once per use: for the manifest it needs both
//! hashes, for the copy it streams the bytes to send. The file is memory
//! mapped, so a video is never read into memory whole; only the metadata the
//! stripper rewrites is held as new bytes.

use std::fs::File;
use std::sync::Mutex;

use graphite_core as core;
use graphite_strip as strip;
use memmap2::Mmap;

use crate::{FileDigest, MetadataChoice};

/// Why a file could not be opened or stripped. Documents and other files the
/// stripper does not handle are not errors: they are sent unchanged.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Error)]
pub enum StripError {
    /// RAW (DNG and others): built around EXIF.
    Raw,
    /// The file does not parse as the format it claims to be.
    Malformed { detail: String },
    /// A structure the stripper cannot rewrite without leaving metadata or losing data.
    Unsupported { detail: String },
    /// Reading the file failed.
    Io { detail: String },
}

impl std::fmt::Display for StripError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            StripError::Raw => write!(f, "RAW files are built around EXIF"),
            StripError::Malformed { detail }
            | StripError::Unsupported { detail }
            | StripError::Io { detail } => write!(f, "{detail}"),
        }
    }
}

impl std::error::Error for StripError {}

/// The input of a stripped file: a memory map, or nothing for an empty file
/// (which cannot be mapped).
enum Input {
    Map(Mmap),
    Empty,
}

impl Input {
    fn bytes(&self) -> &[u8] {
        match self {
            Input::Map(m) => m,
            Input::Empty => &[],
        }
    }
}

enum Piece {
    Input { start: u64, end: u64 },
    New(Vec<u8>),
}

impl Piece {
    fn len(&self) -> u64 {
        match self {
            Piece::Input { start, end } => end - start,
            Piece::New(b) => b.len() as u64,
        }
    }
}

/// A file as it will be sent: the original bytes with the chosen metadata
/// removed. Both hashes are computed when it is opened.
#[derive(uniffi::Object)]
pub struct StrippedFile {
    input: Input,
    pieces: Vec<Piece>,
    original: FileDigest,
    sent: FileDigest,
    stripped: bool,
    /// The piece the last read ended in, so sequential reads don't rescan.
    cursor: Mutex<(usize, u64)>,
}

fn map(path: &str) -> Result<Input, StripError> {
    let io = |e: std::io::Error| StripError::Io {
        detail: format!("{path}: {e}"),
    };
    let file = File::open(path).map_err(io)?;
    if file.metadata().map_err(io)?.len() == 0 {
        return Ok(Input::Empty);
    }
    // SAFETY: the map is read-only. If another app changes the file while it
    // is mapped, the bytes read may be a mix; the hashes checked after the
    // copy catch that (and a truncation raises SIGBUS, as any read would fail).
    Ok(Input::Map(unsafe { Mmap::map(&file) }.map_err(io)?))
}

fn digest(bytes: &[u8]) -> FileDigest {
    FileDigest {
        size: bytes.len() as u64,
        blake3: core::FileHasher::hash(bytes).to_vec(),
    }
}

impl StrippedFile {
    fn unchanged(input: Input) -> Self {
        let original = digest(input.bytes());
        let len = original.size;
        Self {
            input,
            pieces: vec![Piece::Input { start: 0, end: len }],
            sent: original.clone(),
            original,
            stripped: false,
            cursor: Mutex::new((0, 0)),
        }
    }
}

/// Opens `path` to send as it is.
#[uniffi::export]
pub fn open_file(path: String) -> Result<std::sync::Arc<StrippedFile>, StripError> {
    Ok(StrippedFile::unchanged(map(&path)?).into())
}

/// Opens `path` and removes metadata as `choice` asks. Files that are not
/// photos or videos are sent unchanged in every choice.
#[uniffi::export]
pub fn strip_file(
    path: String,
    mime: String,
    choice: MetadataChoice,
) -> Result<std::sync::Arc<StrippedFile>, StripError> {
    let input = map(&path)?;
    let choice = match choice {
        MetadataChoice::KeepAll => strip::Choice::KeepAll,
        MetadataChoice::RemoveLocationAndIdentifiers => strip::Choice::RemoveLocationAndIdentifiers,
        MetadataChoice::RemoveAllButDisplay => strip::Choice::KeepOnlyDisplay,
    };
    let (pieces, sent) = match strip::strip(input.bytes(), &mime, choice) {
        Ok(s) if s.is_unchanged() => return Ok(StrippedFile::unchanged(input).into()),
        Ok(s) => {
            let mut h = core::FileHasher::new();
            let pieces = s
                .parts()
                .map(|p| {
                    h.update(p.bytes());
                    match p {
                        strip::Part::Input { offset, bytes } => Piece::Input {
                            start: offset as u64,
                            end: (offset + bytes.len()) as u64,
                        },
                        strip::Part::New(b) => Piece::New(b.to_vec()),
                    }
                })
                .collect::<Vec<_>>();
            let sent = FileDigest {
                size: s.len(),
                blake3: h.finalize().to_vec(),
            };
            (pieces, sent)
        }
        Err(strip::Unstrippable::NotMedia { .. }) => {
            return Ok(StrippedFile::unchanged(input).into());
        }
        Err(strip::Unstrippable::Raw) => return Err(StripError::Raw),
        Err(e @ strip::Unstrippable::Malformed { .. }) => {
            return Err(StripError::Malformed {
                detail: e.to_string(),
            });
        }
        Err(e @ strip::Unstrippable::Unsupported { .. }) => {
            return Err(StripError::Unsupported {
                detail: e.to_string(),
            });
        }
    };
    let original = digest(input.bytes());
    if sent == original {
        return Ok(StrippedFile::unchanged(input).into());
    }
    Ok(StrippedFile {
        input,
        pieces,
        original,
        sent,
        stripped: true,
        cursor: Mutex::new((0, 0)),
    }
    .into())
}

/// Whether the stripper handles this MIME type (photos and videos, RAW included).
#[uniffi::export]
pub fn strip_applies_to(mime: String) -> bool {
    strip::applies_to(&mime)
}

/// The stripper version that goes in the manifest.
#[uniffi::export]
pub fn stripper_version() -> String {
    strip::STRIPPER_VERSION.to_string()
}

#[uniffi::export]
impl StrippedFile {
    /// Size and BLAKE3 of the file on disk.
    pub fn original(&self) -> FileDigest {
        self.original.clone()
    }

    /// Size and BLAKE3 of the bytes [`Self::read`] gives.
    pub fn sent(&self) -> FileDigest {
        self.sent.clone()
    }

    /// Whether the sent bytes differ from the original.
    pub fn stripped(&self) -> bool {
        self.stripped
    }

    /// Up to `max` bytes of the sent file from `offset`; empty at the end.
    pub fn read(&self, offset: u64, max: u32) -> Vec<u8> {
        let mut out = Vec::with_capacity(max.min(1 << 24) as usize);
        let mut cursor = self.cursor.lock().unwrap();
        // Start from the remembered piece when reading on from it.
        let (mut i, mut at) = if cursor.1 <= offset { *cursor } else { (0, 0) };
        while i < self.pieces.len() && out.len() < max as usize {
            let piece = &self.pieces[i];
            let len = piece.len();
            if offset + out.len() as u64 >= at + len {
                at += len;
                i += 1;
                continue;
            }
            let from = offset + out.len() as u64 - at;
            let want = ((len - from) as usize).min(max as usize - out.len());
            match piece {
                Piece::Input { start, .. } => {
                    let s = (start + from) as usize;
                    out.extend_from_slice(&self.input.bytes()[s..s + want]);
                }
                Piece::New(b) => out.extend_from_slice(&b[from as usize..from as usize + want]),
            }
        }
        *cursor = (i, at);
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp(name: &str, bytes: &[u8]) -> String {
        let p = std::env::temp_dir().join(format!("graphite-ffi-{}-{name}", std::process::id()));
        std::fs::write(&p, bytes).unwrap();
        p.to_string_lossy().into_owned()
    }

    fn read_all(f: &StrippedFile, step: u32) -> Vec<u8> {
        let mut out = Vec::new();
        loop {
            let b = f.read(out.len() as u64, step);
            if b.is_empty() {
                return out;
            }
            out.extend(b);
        }
    }

    #[test]
    fn a_document_is_sent_unchanged_in_every_choice() {
        let p = temp("doc.txt", b"hello, world");
        for choice in [
            MetadataChoice::KeepAll,
            MetadataChoice::RemoveLocationAndIdentifiers,
            MetadataChoice::RemoveAllButDisplay,
        ] {
            let f = strip_file(p.clone(), "text/plain".into(), choice).unwrap();
            assert!(!f.stripped());
            assert_eq!(f.sent(), f.original());
            assert_eq!(read_all(&f, 5), b"hello, world");
        }
    }

    #[test]
    fn a_stripped_jpeg_reads_back_as_hashed_and_strips_to_a_fixed_point() {
        let fixture = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../strip/tests/fixtures/jpeg-full.jpg"
        );
        let Ok(bytes) = std::fs::read(fixture) else {
            panic!("fixture missing: {fixture}");
        };
        let p = temp("in.jpg", &bytes);
        let f = strip_file(
            p,
            "image/jpeg".into(),
            MetadataChoice::RemoveLocationAndIdentifiers,
        )
        .unwrap();
        assert!(f.stripped());
        assert_eq!(f.original(), digest(&bytes));
        // Odd step sizes cross piece boundaries in the middle.
        let out = read_all(&f, 777);
        assert_eq!(f.sent(), digest(&out));
        assert_eq!(read_all(&f, 1 << 20), out);
        let again = strip_file(
            temp("again.jpg", &out),
            "image/jpeg".into(),
            MetadataChoice::RemoveLocationAndIdentifiers,
        )
        .unwrap();
        assert!(!again.stripped());
        assert_eq!(again.sent(), f.sent());
    }

    #[test]
    fn raw_is_refused_and_empty_files_open() {
        let p = temp("x.dng", b"II*\0\x08\0\0\0");
        assert_eq!(
            strip_file(
                p,
                "image/x-adobe-dng".into(),
                MetadataChoice::RemoveAllButDisplay
            )
            .err(),
            Some(StripError::Raw)
        );
        let e = open_file(temp("empty", b"")).unwrap();
        assert_eq!(e.original().size, 0);
        assert!(e.read(0, 10).is_empty());
    }
}
