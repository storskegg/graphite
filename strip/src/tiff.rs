//! EXIF: a TIFF structure. Filtering parses IFD0, the Exif IFD and the
//! Interop IFD, keeps the tags the choice allows and writes a new TIFF in a
//! fixed layout, so the output depends only on the kept tags. The GPS IFD,
//! IFD1 (the thumbnail), SubIFDs and maker notes are never written.

use crate::policy::{self, Ifd, Strip};

#[derive(Clone, Copy)]
struct Order {
    le: bool,
}

impl Order {
    fn u16(self, b: &[u8]) -> u16 {
        let a = [b[0], b[1]];
        if self.le {
            u16::from_le_bytes(a)
        } else {
            u16::from_be_bytes(a)
        }
    }
    fn u32(self, b: &[u8]) -> u32 {
        let a = [b[0], b[1], b[2], b[3]];
        if self.le {
            u32::from_le_bytes(a)
        } else {
            u32::from_be_bytes(a)
        }
    }
    fn put16(self, v: u16) -> [u8; 2] {
        if self.le {
            v.to_le_bytes()
        } else {
            v.to_be_bytes()
        }
    }
    fn put32(self, v: u32) -> [u8; 4] {
        if self.le {
            v.to_le_bytes()
        } else {
            v.to_be_bytes()
        }
    }
}

struct Entry {
    tag: u16,
    kind: u16,
    count: u32,
    data: Vec<u8>,
}

#[derive(Default)]
struct Dir {
    entries: Vec<Entry>,
    /// Sub-IFDs by pointer tag.
    children: Vec<(u16, Dir)>,
}

impl Dir {
    fn is_empty(&self) -> bool {
        self.entries.is_empty() && self.children.is_empty()
    }
}

fn type_size(kind: u16) -> Option<usize> {
    Some(match kind {
        1 | 2 | 6 | 7 => 1,
        3 | 8 => 2,
        4 | 9 | 11 | 13 => 4,
        5 | 10 | 12 => 8,
        _ => return None,
    })
}

pub(crate) struct Filtered {
    /// The new TIFF, or None when no tag is left.
    pub tiff: Option<Vec<u8>>,
}

pub(crate) fn filter(tiff: &[u8], strip: Strip) -> Result<Filtered, String> {
    if tiff.len() < 8 {
        return Err("no TIFF header".into());
    }
    let order = match tiff.get(0..4) {
        Some(b"II*\0") => Order { le: true },
        Some(b"MM\0*") => Order { le: false },
        _ => return Err("no TIFF header".into()),
    };
    let first = order.u32(&tiff[4..8]) as usize;
    // IFD1 (the thumbnail), linked from IFD0, is not read.
    let ifd0 = read_dir(tiff, order, first, Ifd::Zero, strip)?;
    let tiff = if ifd0.is_empty() {
        None
    } else {
        Some(write(order, &ifd0))
    };
    Ok(Filtered { tiff })
}

/// A TIFF header with an empty IFD0, for containers that need some EXIF.
pub(crate) fn empty(like: &[u8]) -> Vec<u8> {
    let order = Order {
        le: like.starts_with(b"II"),
    };
    write(order, &Dir::default())
}

fn read_dir(tiff: &[u8], order: Order, at: usize, which: Ifd, strip: Strip) -> Result<Dir, String> {
    let count_end = at
        .checked_add(2)
        .filter(|&e| e <= tiff.len())
        .ok_or("IFD out of range")?;
    let n = order.u16(&tiff[at..count_end]) as usize;
    let end = count_end + 12 * n + 4;
    if end > tiff.len() {
        return Err("IFD entries out of range".into());
    }
    let mut dir = Dir::default();
    for i in 0..n {
        let e = &tiff[count_end + 12 * i..count_end + 12 * i + 12];
        let tag = order.u16(&e[0..2]);
        let kind = order.u16(&e[2..4]);
        let count = order.u32(&e[4..8]);
        if dir.entries.iter().any(|x| x.tag == tag) || dir.children.iter().any(|x| x.0 == tag) {
            // A repeated tag: the first one wins, as in most readers.
            continue;
        }
        let child = match (which, tag) {
            (Ifd::Zero, policy::TAG_EXIF_IFD) => Some(Ifd::Exif),
            (Ifd::Exif, policy::TAG_INTEROP_IFD) => Some(Ifd::Interop),
            _ => None,
        };
        if let Some(child) = child {
            if !(kind == 4 || kind == 13) || count != 1 {
                return Err("bad IFD pointer".into());
            }
            let sub = read_dir(tiff, order, order.u32(&e[8..12]) as usize, child, strip)?;
            if !sub.is_empty() {
                dir.children.push((tag, sub));
            }
            continue;
        }
        if !strip.keeps(policy::exif_tag(which, tag)) {
            continue;
        }
        let Some(size) = type_size(kind).and_then(|s| s.checked_mul(count as usize)) else {
            continue;
        };
        let data = if size <= 4 {
            e[8..8 + size].to_vec()
        } else {
            let off = order.u32(&e[8..12]) as usize;
            match tiff.get(off..off.saturating_add(size)) {
                Some(d) => d.to_vec(),
                None => return Err(format!("value of tag {tag:#06x} out of range")),
            }
        };
        dir.entries.push(Entry {
            tag,
            kind,
            count,
            data,
        });
    }
    Ok(dir)
}

fn write(order: Order, ifd0: &Dir) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(if order.le { b"II*\0" } else { b"MM\0*" });
    out.extend_from_slice(&order.put32(8));
    write_dir(&mut out, order, ifd0);
    out
}

/// Writes `dir` at the end of `out` (word aligned): its entries sorted by
/// tag, then its values that do not fit in an entry, then its sub-IFDs.
fn write_dir(out: &mut Vec<u8>, order: Order, dir: &Dir) -> u32 {
    if out.len() % 2 == 1 {
        out.push(0);
    }
    let start = out.len();
    enum Slot<'a> {
        Value(&'a Entry),
        Child(&'a Dir),
    }
    let mut slots: Vec<(u16, Slot)> = dir
        .entries
        .iter()
        .map(|e| (e.tag, Slot::Value(e)))
        .collect();
    slots.extend(dir.children.iter().map(|(t, d)| (*t, Slot::Child(d))));
    slots.sort_by_key(|s| s.0);
    out.extend_from_slice(&order.put16(slots.len() as u16));
    for (tag, slot) in &slots {
        out.extend_from_slice(&order.put16(*tag));
        match slot {
            Slot::Value(e) => {
                out.extend_from_slice(&order.put16(e.kind));
                out.extend_from_slice(&order.put32(e.count));
            }
            Slot::Child(_) => {
                out.extend_from_slice(&order.put16(4));
                out.extend_from_slice(&order.put32(1));
            }
        }
        out.extend_from_slice(&[0; 4]);
    }
    out.extend_from_slice(&[0; 4]); // no next IFD
    for (i, (_, slot)) in slots.iter().enumerate() {
        let field = start + 2 + 12 * i + 8;
        match slot {
            Slot::Value(e) if e.data.len() <= 4 => {
                out[field..field + e.data.len()].copy_from_slice(&e.data);
            }
            Slot::Value(e) => {
                if out.len() % 2 == 1 {
                    out.push(0);
                }
                let at = out.len() as u32;
                out.extend_from_slice(&e.data);
                out[field..field + 4].copy_from_slice(&order.put32(at));
            }
            Slot::Child(_) => {}
        }
    }
    for (i, (_, slot)) in slots.iter().enumerate() {
        if let Slot::Child(d) = slot {
            let at = write_dir(out, order, d);
            let field = start + 2 + 12 * i + 8;
            out[field..field + 4].copy_from_slice(&order.put32(at));
        }
    }
    start as u32
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(order: Order, tag: u16, kind: u16, count: u32, value: [u8; 4]) -> Vec<u8> {
        let mut e = Vec::new();
        e.extend_from_slice(&order.put16(tag));
        e.extend_from_slice(&order.put16(kind));
        e.extend_from_slice(&order.put32(count));
        e.extend_from_slice(&value);
        e
    }

    /// IFD0: Make (out of line), Orientation; IFD1 present.
    fn sample(le: bool) -> Vec<u8> {
        let o = Order { le };
        let mut t = Vec::new();
        t.extend_from_slice(if le { b"II*\0" } else { b"MM\0*" });
        t.extend_from_slice(&o.put32(8));
        t.extend_from_slice(&o.put16(2));
        t.extend(entry(o, 0x010F, 2, 7, o.put32(38)));
        t.extend(entry(o, 0x0112, 3, 1, [o.put16(6)[0], o.put16(6)[1], 0, 0]));
        t.extend_from_slice(&o.put32(45)); // IFD1
        t.extend_from_slice(b"Google\0");
        t.extend_from_slice(&o.put16(0));
        t.extend_from_slice(&o.put32(0));
        t
    }

    #[test]
    fn keeps_orientation_drops_make_and_ifd1() {
        for le in [true, false] {
            let t = filter(&sample(le), Strip::AllButDisplay)
                .unwrap()
                .tiff
                .unwrap();
            let o = Order { le };
            assert_eq!(o.u16(&t[8..10]), 1);
            assert_eq!(o.u16(&t[10..12]), 0x0112);
            assert_eq!(o.u16(&t[18..20]), 6);
            assert_eq!(o.u32(&t[22..26]), 0);
            // Filtering again changes nothing.
            assert_eq!(filter(&t, Strip::AllButDisplay).unwrap().tiff.unwrap(), t);
        }
    }

    #[test]
    fn rejects_out_of_range() {
        let mut t = sample(true);
        t.truncate(20);
        assert!(filter(&t, Strip::Identifiers).is_err());
    }
}
