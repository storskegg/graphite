//! MP4, MOV, 3GP and M4A. Every change keeps the file's size and layout: a
//! removed atom becomes a `free` atom of the same size with its contents
//! zeroed, and times are zeroed in place, so no chunk offset moves.

use std::ops::Range;

use crate::bmff::{self, BoxRef};
use crate::out::{Out, Stripped};
use crate::policy::{self, Strip};
use crate::{Unstrippable, malformed};

/// XMP in a `uuid` box.
const XMP_UUID: [u8; 16] = [
    0xBE, 0x7A, 0xCF, 0xCB, 0x97, 0xA9, 0x42, 0xE8, 0x9C, 0x71, 0x99, 0x94, 0x91, 0xE3, 0xAF, 0xAC,
];

pub(crate) type Patches = Vec<(usize, Vec<u8>)>;

pub(crate) fn strip(input: &[u8], strip: Strip) -> Result<Stripped<'_>, Unstrippable> {
    let patches = patches(input, 0..input.len(), strip).map_err(|e| malformed("MP4", e))?;
    let mut out = Out::new(input);
    out.copy_patched(0..input.len(), &patches);
    Ok(out.finish())
}

/// Same-size patches for the movie in `d[range]`, at offsets in `d`, sorted.
pub(crate) fn patches(d: &[u8], range: Range<usize>, strip: Strip) -> Result<Patches, String> {
    let top = bmff::children(d, range.start, range.end)?;
    match top.first().map(|b| &b.kind) {
        Some(b"ftyp" | b"moov" | b"mdat" | b"free" | b"skip" | b"wide" | b"pnot") => {}
        _ => return Err("does not start with ftyp".into()),
    }
    if !top.iter().any(|b| &b.kind == b"moov") {
        return Err("no moov atom".into());
    }
    let mut p = Patches::new();
    for b in &top {
        match &b.kind {
            b"moov" => container(d, b, strip, &mut p)?,
            b"meta" => meta(d, b, strip, &mut p)?,
            b"uuid" if is_xmp_uuid(d, b) => free(b, &mut p),
            _ => {}
        }
    }
    p.sort_by_key(|x| x.0);
    Ok(p)
}

fn is_xmp_uuid(d: &[u8], b: &BoxRef) -> bool {
    d.get(b.body..b.body + 16) == Some(&XMP_UUID[..])
}

/// moov, trak, mdia.
fn container(d: &[u8], parent: &BoxRef, strip: Strip, p: &mut Patches) -> Result<(), String> {
    for b in bmff::children(d, parent.body, parent.end)? {
        match &b.kind {
            b"mvhd" | b"tkhd" | b"mdhd" if strip == Strip::AllButDisplay => zero_times(d, &b, p)?,
            b"trak" | b"mdia" => container(d, &b, strip, p)?,
            b"udta" => udta(d, &b, strip, p)?,
            b"meta" => meta(d, &b, strip, p)?,
            b"uuid" if is_xmp_uuid(d, &b) => free(&b, p),
            _ => {}
        }
    }
    Ok(())
}

fn zero_times(d: &[u8], b: &BoxRef, p: &mut Patches) -> Result<(), String> {
    let width = match d.get(b.body) {
        Some(0) => 4,
        Some(1) => 8,
        _ => {
            return Err(format!(
                "unknown {} version",
                String::from_utf8_lossy(&b.kind)
            ));
        }
    };
    let at = b.body + 4;
    if at + 2 * width > b.end {
        return Err("truncated header atom".into());
    }
    p.push((at, vec![0; 2 * width]));
    Ok(())
}

fn udta(d: &[u8], parent: &BoxRef, strip: Strip, p: &mut Patches) -> Result<(), String> {
    for b in bmff::children(d, parent.body, parent.end)? {
        match &b.kind {
            b"meta" => meta(d, &b, strip, p)?,
            b"free" | b"skip" => {}
            kind if policy::video_atom(strip, kind) => free(&b, p),
            _ => {}
        }
    }
    Ok(())
}

/// A `meta` box: a full box in MP4, a plain atom in QuickTime.
fn meta(d: &[u8], parent: &BoxRef, strip: Strip, p: &mut Patches) -> Result<(), String> {
    let full = d.get(parent.body + 4..parent.body + 8) != Some(b"hdlr");
    let start = if full { parent.body + 4 } else { parent.body };
    let kids = bmff::children(d, start.min(parent.end), parent.end)?;
    let handler = kids
        .iter()
        .find(|b| &b.kind == b"hdlr")
        .and_then(|h| d.get(h.body + 8..h.body + 12))
        .unwrap_or(b"    ");
    let keys = match kids.iter().find(|b| &b.kind == b"keys") {
        Some(k) => keys(d, k)?,
        None => Vec::new(),
    };
    for b in &kids {
        match &b.kind {
            b"hdlr" | b"keys" | b"free" | b"skip" => {}
            b"ilst" => {
                for item in bmff::children(d, b.body, b.end)? {
                    let drop = if handler == b"mdta" {
                        let index = u32::from_be_bytes(item.kind) as usize;
                        match index.checked_sub(1).and_then(|i| keys.get(i)) {
                            Some(key) => policy::video_key(strip, key),
                            // An item without a key: nothing reads it.
                            None => strip == Strip::AllButDisplay,
                        }
                    } else {
                        policy::video_atom(strip, &item.kind)
                    };
                    if drop && &item.kind != b"free" {
                        free(&item, p);
                    }
                }
            }
            b"uuid" if is_xmp_uuid(d, b) => free(b, p),
            b"XMP_" | b"xml " => free(b, p),
            _ if strip == Strip::AllButDisplay => free(b, p),
            _ => {}
        }
    }
    Ok(())
}

fn keys(d: &[u8], k: &BoxRef) -> Result<Vec<String>, String> {
    let count = bmff::be32(d, k.body + 4)? as usize;
    let mut out = Vec::new();
    let mut i = k.body + 8;
    for _ in 0..count {
        let size = bmff::be32(d, i)? as usize;
        if size < 8 || i + size > k.end {
            return Err("bad keys atom".into());
        }
        out.push(String::from_utf8_lossy(&d[i + 8..i + size]).into_owned());
        i += size;
    }
    Ok(out)
}

/// Turns `b` into a `free` atom of the same size (a 64-bit size stays as it
/// is) and zeroes what it held.
fn free(b: &BoxRef, p: &mut Patches) {
    p.push((b.start + 4, b"free".to_vec()));
    if b.end > b.body {
        p.push((b.body, vec![0; b.end - b.body]));
    }
}
