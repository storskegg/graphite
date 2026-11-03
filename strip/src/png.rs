//! PNG (and APNG). Chunks needed to show the image are copied; `eXIf` and
//! the XMP `iTXt` are filtered and rewritten with a new CRC; other text
//! chunks and unknown ancillary chunks are dropped, as is anything after
//! IEND.

use crate::out::{Out, Stripped};
use crate::policy::Strip;
use crate::{Unstrippable, malformed, tiff, unsupported, xmp};

const SIGNATURE: &[u8] = b"\x89PNG\r\n\x1a\n";
const XMP_KEYWORD: &[u8] = b"XML:com.adobe.xmp";

fn chunk(kind: &[u8; 4], data: &[u8]) -> Vec<u8> {
    let mut c = Vec::with_capacity(data.len() + 12);
    c.extend_from_slice(&(data.len() as u32).to_be_bytes());
    c.extend_from_slice(kind);
    c.extend_from_slice(data);
    let mut h = crc32fast::Hasher::new();
    h.update(kind);
    h.update(data);
    c.extend_from_slice(&h.finalize().to_be_bytes());
    c
}

pub(crate) fn strip(d: &[u8], strip: Strip) -> Result<Stripped<'_>, Unstrippable> {
    let bad = |r: String| malformed("PNG", r);
    let mut out = Out::new(d);
    out.copy(0..SIGNATURE.len());
    let mut i = SIGNATURE.len();
    loop {
        let head = d.get(i..i + 8).ok_or_else(|| bad("no IEND".into()))?;
        let len = u32::from_be_bytes(head[0..4].try_into().unwrap()) as usize;
        let kind: [u8; 4] = head[4..8].try_into().unwrap();
        let end = i
            .checked_add(12 + len)
            .filter(|&e| e <= d.len())
            .ok_or_else(|| bad("chunk overruns the file".into()))?;
        let data = &d[i + 8..i + 8 + len];
        match &kind {
            b"IHDR" | b"PLTE" | b"IDAT" | b"IEND" | b"tRNS" | b"cHRM" | b"gAMA" | b"iCCP"
            | b"sBIT" | b"sRGB" | b"cICP" | b"mDCV" | b"cLLI" | b"pHYs" | b"bKGD" | b"hIST"
            | b"sPLT" | b"acTL" | b"fcTL" | b"fdAT" => out.copy(i..end),
            b"tIME" if strip == Strip::Identifiers => out.copy(i..end),
            b"eXIf" => {
                let f = tiff::filter(data, strip).map_err(|e| bad(format!("eXIf: {e}")))?;
                if let Some(t) = f.tiff {
                    out.bytes(&chunk(b"eXIf", &t));
                }
            }
            b"iTXt" if data.starts_with(XMP_KEYWORD) && data.get(XMP_KEYWORD.len()) == Some(&0) => {
                // keyword \0 compressed method lang \0 translated \0 text
                let rest = &data[XMP_KEYWORD.len() + 1..];
                if rest.len() < 2 {
                    return Err(bad("short XMP iTXt".into()));
                }
                if rest[0] != 0 {
                    return Err(unsupported("PNG", "compressed XMP"));
                }
                let mut at = 2;
                for _ in 0..2 {
                    at += rest[at..]
                        .iter()
                        .position(|&b| b == 0)
                        .ok_or_else(|| bad("bad iTXt".into()))?
                        + 1;
                }
                let head_len = XMP_KEYWORD.len() + 1 + at;
                let f = xmp::filter(&rest[at..], strip, xmp::Extended::Remove)
                    .map_err(|e| bad(format!("XMP: {e}")))?;
                if f.kept > 0 {
                    let mut body = data[..head_len].to_vec();
                    body.extend_from_slice(&f.xml);
                    out.bytes(&chunk(b"iTXt", &body));
                }
            }
            _ if kind[0].is_ascii_uppercase() => {
                return Err(unsupported(
                    "PNG",
                    format!("critical chunk {}", String::from_utf8_lossy(&kind)),
                ));
            }
            // Text, tIME in the strictest choice, and unknown ancillary chunks.
            _ => {}
        }
        i = end;
        if &kind == b"IEND" {
            return Ok(out.finish());
        }
    }
}
