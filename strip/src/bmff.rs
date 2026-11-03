//! ISO base media file format boxes (QuickTime atoms), shared by MP4 and HEIF.

/// A box inside the input.
#[derive(Clone, Copy, Debug)]
pub(crate) struct BoxRef {
    pub kind: [u8; 4],
    pub start: usize,
    /// Where the payload starts (after size, type and any 64-bit size).
    pub body: usize,
    pub end: usize,
}

impl BoxRef {
    pub fn payload<'a>(&self, d: &'a [u8]) -> &'a [u8] {
        &d[self.body..self.end]
    }
}

/// The boxes in `d[start..end]`. QuickTime allows a 32-bit zero to end a
/// list of atoms early; it is accepted and ends the list.
pub(crate) fn children(d: &[u8], start: usize, end: usize) -> Result<Vec<BoxRef>, String> {
    let mut boxes = Vec::new();
    let mut i = start;
    while i < end {
        if end - i < 8 {
            if d[i..end].iter().all(|&b| b == 0) {
                break;
            }
            return Err(format!("{} stray bytes at {i}", end - i));
        }
        let size32 = u32::from_be_bytes(d[i..i + 4].try_into().unwrap());
        let kind: [u8; 4] = d[i + 4..i + 8].try_into().unwrap();
        let (size, header) = match size32 {
            0 if kind == [0; 4] => break,
            0 => (end - i, 8),
            1 => {
                let big = d.get(i + 8..i + 16).ok_or("truncated 64-bit box size")?;
                let size = u64::from_be_bytes(big.try_into().unwrap());
                (usize::try_from(size).map_err(|_| "box too large")?, 16)
            }
            n => (n as usize, 8),
        };
        if size < header || size > end - i {
            return Err(format!(
                "box {} at {i} overruns its parent",
                String::from_utf8_lossy(&kind)
            ));
        }
        boxes.push(BoxRef {
            kind,
            start: i,
            body: i + header,
            end: i + size,
        });
        i += size;
    }
    Ok(boxes)
}

pub(crate) fn be16(d: &[u8], at: usize) -> Result<u16, String> {
    d.get(at..at + 2)
        .map(|b| u16::from_be_bytes(b.try_into().unwrap()))
        .ok_or_else(|| "truncated".into())
}

pub(crate) fn be32(d: &[u8], at: usize) -> Result<u32, String> {
    d.get(at..at + 4)
        .map(|b| u32::from_be_bytes(b.try_into().unwrap()))
        .ok_or_else(|| "truncated".into())
}

/// A big-endian unsigned integer of 0, 4 or 8 bytes (iloc fields).
pub(crate) fn be_n(d: &[u8], at: usize, n: usize) -> Result<u64, String> {
    let b = d.get(at..at + n).ok_or("truncated")?;
    Ok(b.iter().fold(0u64, |v, &x| (v << 8) | x as u64))
}
