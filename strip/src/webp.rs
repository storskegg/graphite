//! WebP. The `EXIF` and `XMP ` chunks are filtered (or dropped when nothing
//! is left), unknown chunks are dropped, and the VP8X flags and the RIFF size
//! are updated to match.

use crate::out::{Out, Stripped};
use crate::policy::Strip;
use crate::{Unstrippable, malformed, tiff, xmp};

const EXIF_FLAG: u8 = 0x08;
const XMP_FLAG: u8 = 0x04;

fn chunk(kind: &[u8], data: &[u8]) -> Vec<u8> {
    let mut c = Vec::with_capacity(data.len() + 9);
    c.extend_from_slice(kind);
    c.extend_from_slice(&(data.len() as u32).to_le_bytes());
    c.extend_from_slice(data);
    if data.len() % 2 == 1 {
        c.push(0);
    }
    c
}

pub(crate) fn strip(d: &[u8], strip: Strip) -> Result<Stripped<'_>, Unstrippable> {
    let bad = |r: String| malformed("WebP", r);
    let riff = u32::from_le_bytes(d[4..8].try_into().unwrap()) as usize;
    let end = riff
        .checked_add(8)
        .filter(|&e| e <= d.len())
        .ok_or_else(|| bad("RIFF size overruns the file".into()))?;
    // Chunks to write: Ok(range of the input) or Err(new bytes).
    let mut chunks: Vec<Result<std::ops::Range<usize>, Vec<u8>>> = Vec::new();
    let (mut vp8x, mut has_exif, mut has_xmp) = (None, false, false);
    let mut i = 12;
    while i < end {
        let head = d
            .get(i..i + 8)
            .filter(|_| i + 8 <= end)
            .ok_or_else(|| bad("truncated chunk".into()))?;
        let kind: [u8; 4] = head[0..4].try_into().unwrap();
        let len = u32::from_le_bytes(head[4..8].try_into().unwrap()) as usize;
        let next = (i + 8)
            .checked_add(len + len % 2)
            .ok_or_else(|| bad("chunk too large".into()))?;
        if next > end && !(next == end + 1 && i + 8 + len == end) {
            return Err(bad("chunk overruns the RIFF".into()));
        }
        let data = &d[i + 8..i + 8 + len];
        match &kind {
            b"VP8X" => {
                if len < 10 {
                    return Err(bad("short VP8X".into()));
                }
                vp8x = Some(chunks.len());
                chunks.push(Err(chunk(b"VP8X", data)));
            }
            b"VP8 " | b"VP8L" | b"ALPH" | b"ANIM" | b"ANMF" | b"ICCP" => {
                chunks.push(Ok(i..next.min(end)))
            }
            b"EXIF" => {
                let (prefix, t) = match data.strip_prefix(b"Exif\0\0") {
                    Some(t) => (&data[..6], t),
                    None => (&data[..0], data),
                };
                let f = tiff::filter(t, strip).map_err(|e| bad(format!("EXIF: {e}")))?;
                if let Some(t) = f.tiff {
                    chunks.push(Err(chunk(b"EXIF", &[prefix, &t].concat())));
                    has_exif = true;
                }
            }
            b"XMP " => {
                let f = xmp::filter(data, strip, xmp::Extended::Remove)
                    .map_err(|e| bad(format!("XMP: {e}")))?;
                if f.kept > 0 {
                    chunks.push(Err(chunk(b"XMP ", &f.xml)));
                    has_xmp = true;
                }
            }
            _ => {}
        }
        i = next;
    }
    if let Some(v) = vp8x
        && let Err(c) = &mut chunks[v]
    {
        let flags = &mut c[8];
        *flags &= !(EXIF_FLAG | XMP_FLAG);
        if has_exif {
            *flags |= EXIF_FLAG;
        }
        if has_xmp {
            *flags |= XMP_FLAG;
        }
    }
    let mut size = 4usize;
    for c in &chunks {
        size += match c {
            Ok(r) => r.len(),
            Err(b) => b.len(),
        };
    }
    // A chunk ending the RIFF without its pad byte gets one.
    let unpadded = chunks
        .last()
        .is_some_and(|c| matches!(c, Ok(r) if r.len() % 2 == 1));
    if unpadded {
        size += 1;
    }
    let mut out = Out::new(d);
    out.copy(0..4);
    out.bytes(&(size as u32).to_le_bytes());
    out.copy(8..12);
    for c in chunks {
        match c {
            Ok(r) => out.copy(r),
            Err(b) => out.bytes(&b),
        }
    }
    if unpadded {
        out.bytes(&[0]);
    }
    Ok(out.finish())
}
