//! GIF. Comment extensions, the XMP application extension and unknown
//! extensions are dropped; frames, graphic control, plain text, looping
//! (NETSCAPE2.0, ANIMEXTS1.0) and ICC profiles are copied. Anything after the
//! trailer is dropped.

use crate::out::{Out, Stripped};
use crate::policy::Strip;
use crate::{Unstrippable, malformed};

/// Skips data sub-blocks starting at `i`; returns the index after the
/// terminator.
fn sub_blocks(d: &[u8], mut i: usize) -> Option<usize> {
    loop {
        let n = *d.get(i)? as usize;
        i += 1 + n;
        if n == 0 {
            return (i <= d.len()).then_some(i);
        }
    }
}

pub(crate) fn strip(d: &[u8], _strip: Strip) -> Result<Stripped<'_>, Unstrippable> {
    let bad = |r: &str| malformed("GIF", r);
    let lsd = d.get(6..13).ok_or_else(|| bad("truncated header"))?;
    let mut i = 13;
    if lsd[4] & 0x80 != 0 {
        i += 3 << ((lsd[4] & 7) + 1);
    }
    let mut out = Out::new(d);
    out.copy(0..i.min(d.len()));
    loop {
        match d.get(i) {
            Some(0x3B) => {
                out.copy(i..i + 1);
                return Ok(out.finish());
            }
            Some(0x2C) => {
                let desc = d.get(i + 1..i + 10).ok_or_else(|| bad("truncated image"))?;
                let mut j = i + 10;
                if desc[8] & 0x80 != 0 {
                    j += 3 << ((desc[8] & 7) + 1);
                }
                // LZW minimum code size, then the image data.
                let end = sub_blocks(d, j + 1).ok_or_else(|| bad("truncated image data"))?;
                out.copy(i..end);
                i = end;
            }
            Some(0x21) => {
                let label = *d.get(i + 1).ok_or_else(|| bad("truncated extension"))?;
                let end = sub_blocks(d, i + 2).ok_or_else(|| bad("truncated extension"))?;
                let keep = match label {
                    0xF9 | 0x01 => true,
                    0xFF => {
                        let id = d.get(i + 3..i + 14).unwrap_or_default();
                        d.get(i + 2) == Some(&11)
                            && matches!(id, b"NETSCAPE2.0" | b"ANIMEXTS1.0" | b"ICCRGBG1012")
                    }
                    // 0xFE comments and anything unknown.
                    _ => false,
                };
                if keep {
                    out.copy(i..end);
                }
                i = end;
            }
            Some(_) => return Err(bad("unknown block")),
            None => return Err(bad("no trailer")),
        }
    }
}
