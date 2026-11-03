//! graphite's metadata stripper.
//!
//! [`strip`] removes metadata from a photo or video without re-encoding it.
//! The output is a pure function of the input bytes, the [`Choice`] and
//! [`STRIPPER_VERSION`], and stripping its own output again changes nothing.
//! The spec is the "Removing metadata" section of `docs/media.md`.
//!
//! The output is returned as [`Stripped`]: pieces of the input and new bytes,
//! so a large video can be streamed from the file (or a memory map) without
//! copying it. Only the metadata is parsed.

mod bmff;
mod gif;
mod heif;
mod jpeg;
mod mp4;
mod out;
mod png;
mod policy;
mod tiff;
mod webp;
mod xmp;

use std::fmt;
use std::io;

pub use out::{Part, Stripped};

/// Goes in the manifest next to each sent hash. Bump it whenever the output
/// for any input and choice changes.
pub const STRIPPER_VERSION: u32 = 1;

/// What the user chose to do with metadata.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Choice {
    /// Nothing is removed; the file is copied byte for byte.
    KeepAll,
    /// Removes location, camera make, model and serial numbers, lens serial,
    /// owner and artist names, software, unique image IDs, maker notes and
    /// the embedded EXIF thumbnail. Keeps dates and time-zone offsets,
    /// orientation, colour profile, exposure settings, motion photos, Ultra
    /// HDR and depth maps.
    RemoveLocationAndIdentifiers,
    /// Also removes dates and exposure settings, and zeroes the video
    /// creation and modification times. Keeps orientation, colour profile,
    /// motion photos, Ultra HDR and depth maps.
    KeepOnlyDisplay,
}

/// Why a file cannot have its metadata removed. The caller asks the user
/// whether to copy such files as they are or skip them; the stripper never
/// returns the input unchanged in their place.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Unstrippable {
    /// RAW files (DNG and others) are built around EXIF.
    Raw,
    /// Not a photo or video format the stripper handles. Documents and other
    /// audio are copied unchanged in every choice (see [`applies_to`]).
    NotMedia { mime: String },
    /// The file does not parse as the format it claims to be.
    Malformed {
        format: &'static str,
        reason: String,
    },
    /// The file parses, but uses a structure the stripper cannot rewrite
    /// without losing data or leaving metadata behind.
    Unsupported {
        format: &'static str,
        reason: String,
    },
}

impl fmt::Display for Unstrippable {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Unstrippable::Raw => write!(f, "RAW files are built around EXIF"),
            Unstrippable::NotMedia { mime } => write!(f, "not a photo or video: {mime}"),
            Unstrippable::Malformed { format, reason } => write!(f, "malformed {format}: {reason}"),
            Unstrippable::Unsupported { format, reason } => {
                write!(f, "unsupported {format}: {reason}")
            }
        }
    }
}

impl std::error::Error for Unstrippable {}

impl From<Unstrippable> for io::Error {
    fn from(e: Unstrippable) -> io::Error {
        io::Error::new(io::ErrorKind::InvalidData, e)
    }
}

/// The formats [`strip`] handles.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Format {
    Jpeg,
    Png,
    WebP,
    Gif,
    Heif,
    Mp4,
}

/// Whether the stripper is meant for files of this MIME type: photos and
/// videos, RAW included (which [`strip`] refuses). Everything else is copied
/// unchanged in every choice.
pub fn applies_to(mime: &str) -> bool {
    is_raw(mime) || format_for_mime(mime).is_some()
}

/// Removes metadata from `input` as `choice` asks. [`Choice::KeepAll`] returns
/// the input as it is, without looking at it. `mime` is the MediaStore
/// MIME type; the file's own signature wins when they disagree.
pub fn strip<'a>(
    input: &'a [u8],
    mime: &str,
    choice: Choice,
) -> Result<Stripped<'a>, Unstrippable> {
    let strip = match choice {
        // Byte for byte, whatever the file is.
        Choice::KeepAll => return Ok(Stripped::unchanged(input)),
        Choice::RemoveLocationAndIdentifiers => policy::Strip::Identifiers,
        Choice::KeepOnlyDisplay => policy::Strip::AllButDisplay,
    };
    let mime = mime.trim().to_ascii_lowercase();
    if is_raw(&mime) {
        return Err(Unstrippable::Raw);
    }
    let format = match (sniff(input), format_for_mime(&mime)) {
        (Some(sniffed), _) => sniffed,
        (None, Some(Format::Jpeg)) => return Err(malformed("JPEG", "no SOI marker")),
        (None, Some(Format::Png)) => return Err(malformed("PNG", "no PNG signature")),
        (None, Some(Format::WebP)) => return Err(malformed("WebP", "no RIFF WEBP header")),
        (None, Some(Format::Gif)) => return Err(malformed("GIF", "no GIF signature")),
        (None, Some(Format::Heif | Format::Mp4)) => {
            return Err(malformed("ISO BMFF", "does not start with a box"));
        }
        (None, None) if looks_like_tiff(input) => return Err(Unstrippable::Raw),
        (None, None) => return Err(Unstrippable::NotMedia { mime }),
    };
    match format {
        Format::Jpeg => jpeg::strip(input, strip),
        Format::Png => png::strip(input, strip),
        Format::WebP => webp::strip(input, strip),
        Format::Gif => gif::strip(input, strip),
        Format::Heif => heif::strip(input, strip),
        Format::Mp4 => mp4::strip(input, strip),
    }
}

fn malformed(format: &'static str, reason: impl Into<String>) -> Unstrippable {
    Unstrippable::Malformed {
        format,
        reason: reason.into(),
    }
}

fn unsupported(format: &'static str, reason: impl Into<String>) -> Unstrippable {
    Unstrippable::Unsupported {
        format,
        reason: reason.into(),
    }
}

fn is_raw(mime: &str) -> bool {
    matches!(
        mime,
        "image/dng"
            | "image/x-adobe-dng"
            | "image/tiff"
            | "image/x-canon-cr2"
            | "image/x-canon-cr3"
            | "image/x-nikon-nef"
            | "image/x-nikon-nrw"
            | "image/x-sony-arw"
            | "image/x-panasonic-rw2"
            | "image/x-fuji-raf"
            | "image/x-olympus-orf"
            | "image/x-pentax-pef"
            | "image/x-samsung-srw"
    )
}

fn format_for_mime(mime: &str) -> Option<Format> {
    Some(match mime {
        "image/jpeg" | "image/jpg" | "image/pjpeg" => Format::Jpeg,
        "image/png" | "image/apng" => Format::Png,
        "image/webp" => Format::WebP,
        "image/gif" => Format::Gif,
        "image/heic" | "image/heif" | "image/avif" => Format::Heif,
        "video/mp4" | "video/quicktime" | "video/3gpp" | "video/3gpp2" | "audio/mp4"
        | "audio/x-m4a" | "audio/m4a" | "audio/3gpp" => Format::Mp4,
        _ => return None,
    })
}

fn sniff(d: &[u8]) -> Option<Format> {
    if d.starts_with(&[0xFF, 0xD8, 0xFF]) {
        return Some(Format::Jpeg);
    }
    if d.starts_with(b"\x89PNG\r\n\x1a\n") {
        return Some(Format::Png);
    }
    if d.len() >= 12 && &d[0..4] == b"RIFF" && &d[8..12] == b"WEBP" {
        return Some(Format::WebP);
    }
    if d.starts_with(b"GIF87a") || d.starts_with(b"GIF89a") {
        return Some(Format::Gif);
    }
    if d.len() >= 12 && &d[4..8] == b"ftyp" {
        let size = u32::from_be_bytes(d[0..4].try_into().ok()?) as usize;
        let brands = d.get(8..size.min(d.len())).unwrap_or_default();
        let image = brands.as_chunks::<4>().0.iter().any(|b| {
            matches!(
                b,
                b"heic"
                    | b"heix"
                    | b"heim"
                    | b"heis"
                    | b"mif1"
                    | b"msf1"
                    | b"avif"
                    | b"avis"
                    | b"hevc"
            )
        });
        // A video can list mif1 among its brands; the major brand decides.
        let major_video = matches!(
            &d[8..12],
            b"isom"
                | b"mp41"
                | b"mp42"
                | b"qt  "
                | b"M4A "
                | b"3gp4"
                | b"3gp5"
                | b"3gp6"
                | b"3g2a"
                | b"avc1"
                | b"iso2"
                | b"dash"
        );
        return Some(if image && !major_video {
            Format::Heif
        } else {
            Format::Mp4
        });
    }
    // Old QuickTime files may start with another atom.
    if d.len() >= 8 && matches!(&d[4..8], b"moov" | b"mdat" | b"wide" | b"free" | b"skip") {
        return Some(Format::Mp4);
    }
    None
}

fn looks_like_tiff(d: &[u8]) -> bool {
    d.starts_with(b"II*\0") || d.starts_with(b"MM\0*")
}
