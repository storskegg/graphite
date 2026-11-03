//! HEIC, HEIF and AVIF. The `Exif` and XMP (`mime`, application/rdf+xml)
//! items are filtered and written back into their own extents, padded to
//! the same length (zeros after the TIFF, spaces after the XMP). No box
//! changes size and no offset moves, so `iloc` stays as it is.

use crate::bmff::{self, BoxRef};
use crate::out::{Out, Stripped};
use crate::policy::Strip;
use crate::{Unstrippable, malformed, tiff, unsupported, xmp};

fn bad(r: impl Into<String>) -> Unstrippable {
    malformed("HEIF", r)
}

#[derive(Debug, PartialEq)]
enum Kind {
    Exif,
    Xmp,
}

struct Location {
    /// Absolute file ranges, in order.
    extents: Vec<std::ops::Range<usize>>,
}

pub(crate) fn strip(d: &[u8], strip: Strip) -> Result<Stripped<'_>, Unstrippable> {
    let top = bmff::children(d, 0, d.len()).map_err(bad)?;
    let meta = top
        .iter()
        .find(|b| &b.kind == b"meta")
        .ok_or_else(|| unsupported("HEIF", "no meta box"))?;
    let kids = bmff::children(d, meta.body + 4, meta.end).map_err(bad)?;
    let find = |k: &[u8; 4]| kids.iter().find(|b| &b.kind == k);
    let items = match find(b"iinf") {
        Some(b) => item_kinds(d, b)?,
        None => Vec::new(),
    };
    let iloc = find(b"iloc").ok_or_else(|| bad("no iloc"))?;
    let idat = find(b"idat").map(|b| b.body..b.end);
    let mut patches: Vec<(usize, Vec<u8>)> = Vec::new();
    for (id, kind) in &items {
        let loc = locate(d, iloc, *id, idat.clone())?;
        let mut data = Vec::new();
        for e in &loc.extents {
            data.extend_from_slice(&d[e.clone()]);
        }
        let new = match kind {
            Kind::Exif => {
                let off = bmff::be32(&data, 0).map_err(bad)? as usize;
                let t = off
                    .checked_add(4)
                    .and_then(|s| data.get(s..))
                    .ok_or_else(|| bad("bad Exif item"))?;
                let f = tiff::filter(t, strip).map_err(|e| bad(format!("EXIF: {e}")))?;
                let mut new = data[..4 + off].to_vec();
                new.extend(f.tiff.unwrap_or_else(|| tiff::empty(t)));
                new.resize(new.len().max(data.len()), 0);
                new
            }
            Kind::Xmp => {
                let f = xmp::filter(&data, strip, xmp::Extended::Remove)
                    .map_err(|e| bad(format!("XMP: {e}")))?;
                let mut new = f.xml;
                new.resize(new.len().max(data.len()), b' ');
                new
            }
        };
        if new.len() != data.len() {
            return Err(unsupported(
                "HEIF",
                "filtered metadata is larger than the item",
            ));
        }
        let mut at = 0;
        for e in &loc.extents {
            patches.push((e.start, new[at..at + e.len()].to_vec()));
            at += e.len();
        }
    }
    patches.sort_by_key(|p| p.0);
    if patches.windows(2).any(|w| w[0].0 + w[0].1.len() > w[1].0) {
        return Err(bad("metadata items overlap"));
    }
    let mut out = Out::new(d);
    out.copy_patched(0..d.len(), &patches);
    Ok(out.finish())
}

/// Item IDs of the metadata items.
fn item_kinds(d: &[u8], iinf: &BoxRef) -> Result<Vec<(u32, Kind)>, Unstrippable> {
    let version = d[iinf.body];
    let start = iinf.body + 4 + if version == 0 { 2 } else { 4 };
    let mut out = Vec::new();
    for infe in bmff::children(d, start, iinf.end).map_err(bad)? {
        if &infe.kind != b"infe" {
            continue;
        }
        let p = infe.payload(d);
        let v = *p.first().ok_or_else(|| bad("empty infe"))?;
        if v < 2 {
            return Err(unsupported("HEIF", "infe version 0 or 1"));
        }
        let (id, rest) = if v == 2 {
            (bmff::be16(p, 4).map_err(bad)? as u32, 6)
        } else {
            (bmff::be32(p, 4).map_err(bad)?, 8)
        };
        // protection index, then the item type.
        let kind = p.get(rest + 2..rest + 6).ok_or_else(|| bad("short infe"))?;
        let strings = &p[rest + 6..];
        let mut parts = strings.split(|&b| b == 0);
        let _name = parts.next();
        match kind {
            b"Exif" => out.push((id, Kind::Exif)),
            b"mime" => {
                let content = parts.next().unwrap_or_default();
                let encoding = parts.next().unwrap_or_default();
                if content != b"application/rdf+xml" {
                    return Err(unsupported(
                        "HEIF",
                        format!("mime item {}", String::from_utf8_lossy(content)),
                    ));
                }
                if !encoding.is_empty() {
                    return Err(unsupported("HEIF", "encoded XMP item"));
                }
                out.push((id, Kind::Xmp));
            }
            _ => {}
        }
    }
    Ok(out)
}

fn locate(
    d: &[u8],
    iloc: &BoxRef,
    want: u32,
    idat: Option<std::ops::Range<usize>>,
) -> Result<Location, Unstrippable> {
    let p = iloc.payload(d);
    let e = |r: String| bad(format!("iloc: {r}"));
    let version = *p.first().ok_or_else(|| e("empty".into()))?;
    let sizes = bmff::be16(p, 4).map_err(e)?;
    let (off_size, len_size, base_size) = (
        (sizes >> 12) as usize,
        ((sizes >> 8) & 15) as usize,
        ((sizes >> 4) & 15) as usize,
    );
    let index_size = if version >= 1 {
        (sizes & 15) as usize
    } else {
        0
    };
    let mut i = 6;
    let count = if version < 2 {
        let c = bmff::be16(p, i).map_err(e)? as usize;
        i += 2;
        c
    } else {
        let c = bmff::be32(p, i).map_err(e)? as usize;
        i += 4;
        c
    };
    for _ in 0..count {
        let id = if version < 2 {
            let v = bmff::be16(p, i).map_err(e)? as u32;
            i += 2;
            v
        } else {
            let v = bmff::be32(p, i).map_err(e)?;
            i += 4;
            v
        };
        let method = if version >= 1 {
            let m = bmff::be16(p, i).map_err(e)? & 15;
            i += 2;
            m
        } else {
            0
        };
        i += 2; // data reference index
        let base = bmff::be_n(p, i, base_size).map_err(e)? as usize;
        i += base_size;
        let n = bmff::be16(p, i).map_err(e)? as usize;
        i += 2;
        let mut extents = Vec::new();
        for _ in 0..n {
            i += index_size;
            let off = bmff::be_n(p, i, off_size).map_err(e)? as usize;
            i += off_size;
            let len = bmff::be_n(p, i, len_size).map_err(e)? as usize;
            i += len_size;
            extents.push((off, len));
        }
        if id != want {
            continue;
        }
        let (origin, limit) = match method {
            0 => (0, d.len()),
            1 => {
                let r = idat
                    .clone()
                    .ok_or_else(|| bad("item in idat without idat"))?;
                (r.start, r.end)
            }
            _ => return Err(unsupported("HEIF", "item constructed from other items")),
        };
        let mut out = Vec::new();
        for (off, len) in extents {
            let start = origin
                .checked_add(base)
                .and_then(|s| s.checked_add(off))
                .ok_or_else(|| bad("extent overflows"))?;
            let end = if len == 0 {
                limit
            } else {
                start
                    .checked_add(len)
                    .ok_or_else(|| bad("extent overflows"))?
            };
            if start > end || end > limit {
                return Err(bad("extent outside the file"));
            }
            out.push(start..end);
        }
        return Ok(Location { extents: out });
    }
    Err(bad(format!("item {want} has no location")))
}
