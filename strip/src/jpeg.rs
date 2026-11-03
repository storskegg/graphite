//! JPEG. Metadata segments before the image data are filtered, rewritten or
//! dropped; everything else, and every byte after the primary image's EOI
//! (Ultra HDR gain maps, depth maps, motion-photo videos) is copied, except:
//!
//! - MPF offsets are recalculated, because removed segments move the images
//!   after the primary closer to the MPF header;
//! - a motion photo's video is stripped in place, at the same size, so the
//!   offsets that count from the end of the file stay right.
//!
//! Images that MPF points to (gain maps) are kept as they are; if one of them
//! carries metadata the choice would remove, the file is refused.

use std::ops::Range;

use md5::{Digest, Md5};

use crate::out::{Out, Stripped};
use crate::policy::{Strip, ns};
use crate::{Unstrippable, malformed, mp4, tiff, unsupported, xmp};

const EXIF: &[u8] = b"Exif\0\0";
const XMP: &[u8] = b"http://ns.adobe.com/xap/1.0/\0";
const XMP_EXT: &[u8] = b"http://ns.adobe.com/xmp/extension/\0";
const ICC: &[u8] = b"ICC_PROFILE\0";
const MPF: &[u8] = b"MPF\0";
const ISO_GAIN_MAP: &[u8] = b"urn:iso:std:iso:ts:21496:-1\0";
/// Extended XMP is written in chunks of this size.
const EXT_CHUNK: usize = 65400;

/// A marker segment with a length: `d[start]` is 0xFF, `d[start + 4..end]`
/// the payload.
#[derive(Clone, Copy, Debug)]
struct Seg {
    marker: u8,
    start: usize,
    end: usize,
}

impl Seg {
    fn payload<'a>(&self, d: &'a [u8]) -> &'a [u8] {
        &d[self.start + 4..self.end]
    }
}

#[derive(Debug)]
enum Item {
    Seg(Seg),
    /// SOI, EOI, RSTn, fill bytes and entropy-coded data.
    Raw(Range<usize>),
}

fn bad(reason: impl Into<String>) -> Unstrippable {
    malformed("JPEG", reason)
}

/// Parses the image starting at `at`, up to and including its EOI.
fn parse(d: &[u8], at: usize) -> Result<(Vec<Item>, usize), Unstrippable> {
    if d.get(at..at + 2) != Some(&[0xFF, 0xD8]) {
        return Err(bad("no SOI"));
    }
    let mut items = vec![Item::Raw(at..at + 2)];
    let mut i = at + 2;
    loop {
        if d.get(i) != Some(&0xFF) {
            return Err(bad(format!("expected a marker at {i}")));
        }
        let mut j = i;
        while d.get(j + 1) == Some(&0xFF) {
            j += 1;
        }
        let marker = *d.get(j + 1).ok_or_else(|| bad("no EOI"))?;
        match marker {
            0xD9 => {
                items.push(Item::Raw(i..j + 2));
                return Ok((items, j + 2));
            }
            0x01 | 0xD0..=0xD7 => {
                items.push(Item::Raw(i..j + 2));
                i = j + 2;
                continue;
            }
            0x00 | 0xD8 => return Err(bad(format!("unexpected marker {marker:#04x}"))),
            _ => {}
        }
        let len = d
            .get(j + 2..j + 4)
            .map(|b| u16::from_be_bytes([b[0], b[1]]) as usize);
        let end = match len {
            Some(len) if len >= 2 && j + 2 + len <= d.len() => j + 2 + len,
            _ => return Err(bad(format!("segment {marker:#04x} overruns the file"))),
        };
        if j > i {
            items.push(Item::Raw(i..j));
        }
        items.push(Item::Seg(Seg {
            marker,
            start: j,
            end,
        }));
        i = end;
        if marker == 0xDA {
            // Entropy-coded data runs to the next marker that is not a
            // stuffed zero or a restart marker.
            let mut k = end;
            loop {
                let p = d[k..]
                    .iter()
                    .position(|&b| b == 0xFF)
                    .ok_or_else(|| bad("no EOI"))?;
                k += p;
                match d.get(k + 1) {
                    None => return Err(bad("no EOI")),
                    Some(0x00 | 0xD0..=0xD7) => k += 2,
                    Some(_) => break,
                }
            }
            items.push(Item::Raw(end..k));
            i = k;
        }
    }
}

fn segment(marker: u8, parts: &[&[u8]]) -> Result<Vec<u8>, Unstrippable> {
    let len = 2 + parts.iter().map(|p| p.len()).sum::<usize>();
    if len > 0xFFFF {
        return Err(unsupported("JPEG", "rewritten segment exceeds 64 KB"));
    }
    let mut s = vec![0xFF, marker, (len >> 8) as u8, len as u8];
    for p in parts {
        s.extend_from_slice(p);
    }
    Ok(s)
}

/// The extended XMP packet the main packet points to, reassembled.
fn extended(d: &[u8], items: &[Item], guid: &str) -> Result<Option<Vec<u8>>, Unstrippable> {
    let mut buf: Option<Vec<u8>> = None;
    let mut filled = 0;
    for item in items {
        let Item::Seg(s) = item else { continue };
        let p = s.payload(d);
        if s.marker != 0xE1 || !p.starts_with(XMP_EXT) {
            continue;
        }
        let p = &p[XMP_EXT.len()..];
        if p.len() < 40 {
            return Err(bad("short extended XMP chunk"));
        }
        if &p[..32] != guid.as_bytes() {
            continue;
        }
        let full = u32::from_be_bytes(p[32..36].try_into().unwrap()) as usize;
        let off = u32::from_be_bytes(p[36..40].try_into().unwrap()) as usize;
        let data = &p[40..];
        let buf = buf.get_or_insert_with(|| vec![0; full]);
        if buf.len() != full || off + data.len() > full {
            return Err(bad("inconsistent extended XMP chunks"));
        }
        buf[off..off + data.len()].copy_from_slice(data);
        filled += data.len();
    }
    match buf {
        Some(b) if filled != b.len() => Err(bad("extended XMP chunks missing")),
        b => Ok(b),
    }
}

/// Length of the motion-photo video at the end of the file, from the XMP as
/// it will be written (so stripping the output again finds the same).
fn motion_video(facts: &[xmp::Fact]) -> Result<Option<usize>, Unstrippable> {
    let get = |uri: &str, local: &str| facts.iter().find(|f| f.uri == uri && f.local == local);
    let number = |f: &xmp::Fact| {
        f.value
            .trim()
            .parse::<usize>()
            .map(Some)
            .map_err(|_| bad(format!("bad {} in XMP", f.local)))
    };
    if get(ns::G_CAMERA, "MotionPhoto").is_some_and(|f| f.value.trim() == "1") {
        let item = facts
            .iter()
            .find(|f| f.uri == ns::G_ITEM && f.local == "Semantic" && f.value == "MotionPhoto")
            .and_then(|f| f.item)
            .ok_or_else(|| unsupported("JPEG", "motion photo without a MotionPhoto item"))?;
        let len = facts
            .iter()
            .find(|f| f.item == Some(item) && f.uri == ns::G_ITEM && f.local == "Length")
            .ok_or_else(|| unsupported("JPEG", "motion photo item without a length"))?;
        return number(len);
    }
    if get(ns::G_CAMERA, "MicroVideo").is_some_and(|f| f.value.trim() == "1") {
        let off = get(ns::G_CAMERA, "MicroVideoOffset")
            .ok_or_else(|| unsupported("JPEG", "micro video without an offset"))?;
        return number(off);
    }
    Ok(None)
}

/// MPF: byte order, where the MP entries are in the payload, how many.
struct Mpf {
    le: bool,
    entries_at: usize,
    count: usize,
    uids: Option<Range<usize>>,
}

fn mpf(p: &[u8]) -> Result<Mpf, Unstrippable> {
    let t = &p[MPF.len()..];
    let le = match t.get(0..4) {
        Some(b"II*\0") => true,
        Some(b"MM\0*") => false,
        _ => return Err(bad("MPF without a TIFF header")),
    };
    let u16_at = |at: usize| -> Result<u16, Unstrippable> {
        let b = t.get(at..at + 2).ok_or_else(|| bad("truncated MPF"))?;
        Ok(if le {
            u16::from_le_bytes([b[0], b[1]])
        } else {
            u16::from_be_bytes([b[0], b[1]])
        })
    };
    let u32_at = |at: usize| -> Result<u32, Unstrippable> {
        let b: [u8; 4] = t
            .get(at..at + 4)
            .ok_or_else(|| bad("truncated MPF"))?
            .try_into()
            .unwrap();
        Ok(if le {
            u32::from_le_bytes(b)
        } else {
            u32::from_be_bytes(b)
        })
    };
    let ifd = u32_at(4)? as usize;
    let n = u16_at(ifd)? as usize;
    let mut entries = None;
    let mut uids = None;
    for i in 0..n {
        let e = ifd + 2 + 12 * i;
        let (tag, count, value) = (u16_at(e)?, u32_at(e + 4)? as usize, u32_at(e + 8)? as usize);
        match tag {
            0xB002 => entries = Some((value, count / 16)),
            0xB003 if count > 4 => uids = Some(value..value + count),
            0xB003 => uids = Some(e + 8..e + 8 + count),
            _ => {}
        }
    }
    let (at, count) = entries.ok_or_else(|| bad("MPF without MP entries"))?;
    if t.len() < at + 16 * count || uids.as_ref().is_some_and(|u| u.end > t.len()) {
        return Err(bad("MPF entries out of range"));
    }
    let shift = MPF.len();
    Ok(Mpf {
        le,
        entries_at: at + shift,
        count,
        uids: uids.map(|u| u.start + shift..u.end + shift),
    })
}

/// Refuses an MPF image (a gain map) that carries metadata the choice
/// removes. It is kept byte for byte, so it must not hold any.
fn check_secondary(d: &[u8], at: usize, strip: Strip) -> Result<(), Unstrippable> {
    let (items, _) = parse(d, at)?;
    for item in &items {
        let Item::Seg(s) = item else { continue };
        let p = s.payload(d);
        let clean = match s.marker {
            0xE0 => p.starts_with(b"JFIF\0") && p.get(12..14).is_none_or(|t| t == [0, 0]),
            0xE1 if p.starts_with(XMP) => {
                let f = xmp::filter(&p[XMP.len()..], strip, xmp::Extended::Keep)
                    .map_err(|e| bad(format!("gain map XMP: {e}")))?;
                f.removed == 0
            }
            0xE2 => p.starts_with(ICC) || p.starts_with(ISO_GAIN_MAP) || p.starts_with(MPF),
            0xEE => p.starts_with(b"Adobe"),
            0xE1 | 0xE3..=0xED | 0xEF | 0xFE => false,
            _ => true,
        };
        if !clean {
            return Err(unsupported(
                "JPEG",
                "an image MPF points to carries metadata",
            ));
        }
    }
    Ok(())
}

pub(crate) fn strip(d: &[u8], strip: Strip) -> Result<Stripped<'_>, Unstrippable> {
    let (items, primary_end) = parse(d, 0)?;
    let main_xmp = items.iter().find_map(|i| match i {
        Item::Seg(s) if s.marker == 0xE1 && s.payload(d).starts_with(XMP) => {
            Some(&s.payload(d)[XMP.len()..])
        }
        _ => None,
    });
    let facts = match main_xmp {
        Some(x) => xmp::facts(x).map_err(|e| bad(format!("XMP: {e}")))?,
        None => Vec::new(),
    };
    let guid = facts
        .iter()
        .find(|f| f.uri == ns::XMP_NOTE && f.local == "HasExtendedXMP")
        .map(|f| f.value.clone());

    // Extended XMP: filtered on its own, then the main packet points to it
    // by the MD5 of the new bytes.
    let ext_xml = match &guid {
        Some(guid) => match extended(d, &items, guid)? {
            Some(x) => {
                let f = xmp::filter(&x, strip, xmp::Extended::Keep)
                    .map_err(|e| bad(format!("extended XMP: {e}")))?;
                (f.kept > 0).then_some(f.xml)
            }
            None => None,
        },
        None => None,
    };
    let ext_guid = ext_xml.as_ref().map(|x| {
        Md5::digest(x)
            .iter()
            .map(|b| format!("{b:02X}"))
            .collect::<String>()
    });
    let ext_mode = match (&guid, &ext_guid) {
        (None, _) => xmp::Extended::Keep,
        (Some(_), Some(g)) => xmp::Extended::Set(g),
        (Some(_), None) => xmp::Extended::Remove,
    };
    let video = match main_xmp {
        Some(x) => {
            let f = xmp::filter(x, strip, ext_mode).map_err(|e| bad(format!("XMP: {e}")))?;
            let kept = if f.kept > 0 {
                xmp::facts(&f.xml)
            } else {
                Ok(Vec::new())
            };
            motion_video(&kept.map_err(|e| bad(format!("XMP: {e}")))?)?
        }
        None => None,
    };

    let mut out = Out::new(d);
    let mut mpf_slot: Option<(usize, usize, Seg)> = None;
    let mut ext_done = false;
    for item in &items {
        let s = match item {
            Item::Raw(r) => {
                out.copy(r.clone());
                continue;
            }
            Item::Seg(s) => *s,
        };
        let p = s.payload(d);
        let whole = s.start..s.end;
        match s.marker {
            0xE0 if p.starts_with(b"JFIF\0") => {
                if p.len() >= 14 && (p[12] != 0 || p[13] != 0) {
                    // A JFIF thumbnail: keep the header, drop the pixels.
                    out.bytes(&segment(0xE0, &[&p[..12], &[0, 0]])?);
                } else {
                    out.copy(whole);
                }
            }
            0xE1 if p.starts_with(EXIF) => {
                let f =
                    tiff::filter(&p[EXIF.len()..], strip).map_err(|e| bad(format!("EXIF: {e}")))?;
                if let Some(t) = f.tiff {
                    out.bytes(&segment(0xE1, &[EXIF, &t])?);
                }
            }
            0xE1 if p.starts_with(XMP) => {
                let f = xmp::filter(&p[XMP.len()..], strip, ext_mode)
                    .map_err(|e| bad(format!("XMP: {e}")))?;
                if f.kept > 0 {
                    out.bytes(&segment(0xE1, &[XMP, &f.xml])?);
                }
            }
            0xE1 if p.starts_with(XMP_EXT) => {
                if let (false, Some(x), Some(g)) = (ext_done, &ext_xml, &ext_guid) {
                    let total = (x.len() as u32).to_be_bytes();
                    for (i, chunk) in x.chunks(EXT_CHUNK).enumerate() {
                        let off = ((i * EXT_CHUNK) as u32).to_be_bytes();
                        out.bytes(&segment(
                            0xE1,
                            &[XMP_EXT, g.as_bytes(), &total, &off, chunk],
                        )?);
                    }
                }
                ext_done = true;
            }
            0xE2 if p.starts_with(MPF) => {
                let index = out.placeholder(d[whole].to_vec());
                mpf_slot = Some((index, out.len() - (s.end - s.start), s));
            }
            0xE2 if p.starts_with(ICC) || p.starts_with(ISO_GAIN_MAP) => out.copy(whole),
            0xEE if p.starts_with(b"Adobe") => out.copy(whole),
            // Other APPn (APP13 IPTC among them) and comments.
            0xE0..=0xEF | 0xFE => {}
            _ => out.copy(whole),
        }
    }
    let new_primary_end = out.len();

    // Motion-photo video: stripped in place.
    let mut patches = Vec::new();
    if let Some(len) = video {
        let start = d
            .len()
            .checked_sub(len)
            .filter(|&s| s >= primary_end)
            .ok_or_else(|| bad("motion-photo video is not after the image"))?;
        patches = mp4::patches(d, start..d.len(), strip)
            .map_err(|e| unsupported("JPEG", format!("motion-photo video: {e}")))?;
    }

    // MPF images after the primary.
    let mut mpf_bytes = None;
    if let Some((index, out_at, s)) = mpf_slot {
        let p = s.payload(d);
        let m = mpf(p)?;
        let old_base = s.start + 4 + MPF.len();
        let new_base = out_at + 4 + MPF.len();
        let mut seg = d[s.start..s.end].to_vec();
        let get = |b: &[u8], at: usize| {
            let a: [u8; 4] = b[at..at + 4].try_into().unwrap();
            if m.le {
                u32::from_le_bytes(a)
            } else {
                u32::from_be_bytes(a)
            }
        };
        let put = |v: u32| {
            if m.le {
                v.to_le_bytes()
            } else {
                v.to_be_bytes()
            }
        };
        for i in 0..m.count {
            let e = 4 + m.entries_at + 16 * i;
            if i == 0 {
                let size =
                    u32::try_from(new_primary_end).map_err(|_| bad("primary image too large"))?;
                seg[e + 4..e + 8].copy_from_slice(&put(size));
                continue;
            }
            let (size, off) = (get(&seg, e + 4) as usize, get(&seg, e + 8) as usize);
            if off == 0 {
                continue;
            }
            let abs = old_base + off;
            if abs < primary_end || abs + size > d.len() {
                return Err(bad("MPF image outside the file"));
            }
            check_secondary(d, abs, strip)?;
            let new_abs = abs - primary_end + new_primary_end;
            seg[e + 8..e + 12].copy_from_slice(&put((new_abs - new_base) as u32));
        }
        if let Some(u) = m.uids {
            // Unique image IDs.
            seg[4 + u.start..4 + u.end].fill(0);
        }
        mpf_bytes = Some((index, seg));
    }

    out.copy_patched(primary_end..d.len(), &patches);
    if let Some((index, seg)) = mpf_bytes {
        out.replace(index, seg);
    }
    Ok(out.finish())
}
