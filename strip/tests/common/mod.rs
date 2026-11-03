//! Checks shared by the synthetic fixtures and the private corpus. ExifTool
//! is the reference reader; ffmpeg and heif-dec, when installed, decode the
//! videos and HEIF images.

#![allow(dead_code)]

use std::collections::BTreeMap;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicUsize, Ordering};

use graphite_strip::{Choice, strip};
use serde_json::Value;

pub const CHOICES: [Choice; 2] = [
    Choice::RemoveLocationAndIdentifiers,
    Choice::KeepOnlyDisplay,
];

pub fn mime_for(path: &Path) -> &'static str {
    match path
        .extension()
        .and_then(|e| e.to_str())
        .map(str::to_ascii_lowercase)
        .as_deref()
    {
        Some("jpg" | "jpeg") => "image/jpeg",
        Some("png") => "image/png",
        Some("webp") => "image/webp",
        Some("gif") => "image/gif",
        Some("heic") => "image/heic",
        Some("heif") => "image/heif",
        Some("avif") => "image/avif",
        Some("mp4") => "video/mp4",
        Some("mov") => "video/quicktime",
        Some("3gp") => "video/3gpp",
        Some("m4a") => "audio/mp4",
        _ => "application/octet-stream",
    }
}

pub fn have(tool: &str) -> bool {
    Command::new(tool)
        .arg("-version")
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .is_ok()
}

fn scratch(name: &str, bytes: &[u8]) -> PathBuf {
    static N: AtomicUsize = AtomicUsize::new(0);
    let dir = PathBuf::from(env!("CARGO_TARGET_TMPDIR")).join("strip");
    std::fs::create_dir_all(&dir).unwrap();
    let ext = Path::new(name)
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("bin");
    let path = dir.join(format!(
        "{}-{}.{ext}",
        std::process::id(),
        N.fetch_add(1, Ordering::Relaxed)
    ));
    std::fs::write(&path, bytes).unwrap();
    path
}

fn exiftool_raw(args: &[&str], bytes: &[u8]) -> Vec<u8> {
    let mut child = Command::new("exiftool")
        .args(args)
        .arg("-")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .expect("ExifTool is the reference reader; install it (brew install exiftool)");
    let mut stdin = child.stdin.take().unwrap();
    let data = bytes.to_vec();
    let writer = std::thread::spawn(move || {
        let _ = stdin.write_all(&data);
    });
    let out = child.wait_with_output().unwrap();
    writer.join().unwrap();
    out.stdout
}

/// "Group:Tag" -> value, as ExifTool reads it (embedded documents too).
pub fn tags(bytes: &[u8]) -> BTreeMap<String, String> {
    let json = exiftool_raw(
        &[
            "-j",
            "-G1",
            "-a",
            "-s",
            "-e",
            "-ee",
            "-api",
            "LargeFileSupport=1",
        ],
        bytes,
    );
    let v: Value = serde_json::from_slice(&json).expect("ExifTool JSON");
    let mut map = BTreeMap::new();
    for (k, v) in v[0].as_object().unwrap() {
        if k.starts_with("System:") || k.starts_with("ExifTool:") || k == "SourceFile" {
            continue;
        }
        let v = match v {
            Value::String(s) => s.clone(),
            other => other.to_string(),
        };
        map.insert(k.clone(), v);
    }
    map
}

fn binary_tag(tag: &str, bytes: &[u8]) -> Vec<u8> {
    exiftool_raw(&["-b", &format!("-{tag}")], bytes)
}

fn group(key: &str) -> &str {
    key.split(':').next().unwrap()
}

fn name(key: &str) -> &str {
    let n = key.rsplit(':').next().unwrap();
    // ExifTool numbers repeated tags: "Copy1", or a language suffix.
    n.split('-').next().unwrap()
}

/// Tags no stripping choice may leave.
fn identifying(key: &str) -> bool {
    let (g, n) = (group(key), name(key));
    if matches!(
        g,
        "GPS"
            | "IFD1"
            | "IPTC"
            | "Photoshop"
            | "XMP-xmpMM"
            | "XMP-dc"
            | "XMP-photoshop"
            | "XMP-aux"
    ) && n != "DateCreated"
    {
        return true;
    }
    if n.starts_with("GPS")
        || n.contains("Location")
        || n.contains("SerialNumber")
        || n.contains("MakerNote")
    {
        return true;
    }
    if g == "PNG" {
        return matches!(
            n,
            "Comment"
                | "Software"
                | "Artist"
                | "Copyright"
                | "Make"
                | "Model"
                | "CreationTime"
                | "CreateDate"
                | "Datecreate"
                | "Datemodify"
                | "Datetimestamp"
                | "Title"
                | "Author"
                | "Description"
        );
    }
    if g == "File" && n == "Comment" {
        return true;
    }
    if g == "GIF" && n == "Comment" {
        return true;
    }
    matches!(
        n,
        "Make"
            | "Model"
            | "Software"
            | "Artist"
            | "Copyright"
            | "OwnerName"
            | "CameraOwnerName"
            | "LensMake"
            | "LensModel"
            | "ImageUniqueID"
            | "UserComment"
            | "ImageDescription"
            | "Creator"
            | "Rights"
            | "CreatorTool"
            | "DocumentID"
            | "InstanceID"
            | "OriginalDocumentID"
            | "ThumbnailImage"
            | "ThumbnailOffset"
            | "ThumbnailLength"
            | "AndroidMake"
            | "AndroidModel"
            | "AndroidVersion"
            | "Encoder"
            | "HDRPlusMakerNote"
            | "HDRPMakerNote"
            | "ShotLogData"
            | "BurstID"
            | "HostComputer"
            | "ImageUIDList"
    )
}

/// Tags only the strictest choice removes: dates and exposure settings.
fn informative(key: &str, value: &str) -> bool {
    let (g, n) = (group(key), name(key));
    if g.starts_with("ICC") || g == "File" || g == "Composite" {
        return false;
    }
    // Video header times are zeroed, not removed.
    if matches!(
        n,
        "CreateDate"
            | "ModifyDate"
            | "TrackCreateDate"
            | "TrackModifyDate"
            | "MediaCreateDate"
            | "MediaModifyDate"
    ) && matches!(
        g,
        "QuickTime" | "Track1" | "Track2" | "Track3" | "Track4" | "Track5"
    ) {
        return !value.starts_with("0000:00:00 00:00:00");
    }
    if matches!(g, "Keys" | "UserData" | "ItemList") {
        return n != "AndroidCaptureFPS"
            && n != "MajorBrand"
            && n != "MinorVersion"
            && n != "CompatibleBrands";
    }
    if g == "PNG" && n == "ModifyDate" {
        return true;
    }
    if g == "ExifIFD" {
        return !matches!(
            n,
            "ExifVersion"
                | "FlashpixVersion"
                | "ColorSpace"
                | "ExifImageWidth"
                | "ExifImageHeight"
                | "ComponentsConfiguration"
                | "Gamma"
        );
    }
    if g == "IFD0" {
        return n == "ModifyDate";
    }
    if g.starts_with("XMP-") {
        return matches!(
            g,
            "XMP-exif" | "XMP-exifEX" | "XMP-xmp" | "XMP-photoshop" | "XMP-tiff"
        ) && !matches!(
            n,
            "Orientation"
                | "XResolution"
                | "YResolution"
                | "ResolutionUnit"
                | "ExifVersion"
                | "ColorSpace"
                | "ExifImageWidth"
                | "ExifImageHeight"
        );
    }
    false
}

/// Tags every choice keeps, with the same value.
fn structural(key: &str) -> bool {
    let (g, n) = (group(key), name(key));
    // IFD1 is the thumbnail; "Google" is decoded from maker notes.
    if matches!(g, "IFD1" | "Google") {
        return false;
    }
    if matches!(
        g,
        "XMP-GContainer"
            | "XMP-hdrgm"
            | "XMP-GDepth"
            | "XMP-GImage"
            | "XMP-Device"
            | "XMP-HDRGainMap"
            | "XMP-apdi"
            | "MPF0"
    ) {
        return true;
    }
    if g.starts_with("MPImage") {
        return !n.starts_with("MPImageStart") && n != "MPImageLength";
    }
    if g == "XMP-GCamera" {
        return n.starts_with("MotionPhoto")
            || n.starts_with("MicroVideo")
            || n.starts_with("Portrait")
            || n.starts_with("Relit")
            || n == "SpecialTypeID";
    }
    if g == "ICC_Profile" || g.starts_with("ICC-") {
        return true;
    }
    matches!(
        n,
        "Orientation"
            | "ImageWidth"
            | "ImageHeight"
            | "BitDepth"
            | "ColorType"
            | "FrameCount"
            | "AnimationIterations"
            | "AndroidCaptureFPS"
    ) && !matches!(g, "Keys" | "UserData")
}

/// Tags the middle choice keeps with the same value.
fn dates_kept(key: &str) -> bool {
    let (g, n) = (group(key), name(key));
    (g == "ExifIFD"
        && matches!(
            n,
            "DateTimeOriginal" | "OffsetTimeOriginal" | "ExposureTime" | "FNumber" | "ISO"
        ))
        || (g == "QuickTime" && n == "CreateDate")
}

/// Every check for one file and one choice. Returns the stripped bytes.
pub fn check(
    label: &str,
    input: &[u8],
    mime: &str,
    choice: Choice,
    problems: &mut Vec<String>,
) -> Option<Vec<u8>> {
    let mut fail = |m: String| problems.push(format!("{label} {choice:?}: {m}"));
    let out = match strip(input, mime, choice) {
        Ok(s) => s.to_vec(),
        Err(e) => {
            fail(format!("unstrippable: {e}"));
            return None;
        }
    };
    // Deterministic and idempotent.
    let again = strip(&out, mime, choice).map(|s| s.to_vec());
    match again {
        Ok(a) if a == out => {}
        Ok(_) => fail("stripping twice differs from stripping once".into()),
        Err(e) => fail(format!("output is unstrippable: {e}")),
    }
    if strip(input, mime, choice).unwrap().to_vec() != out {
        fail("not deterministic".into());
    }

    let before = tags(input);
    let after = tags(&out);
    for (k, v) in &after {
        if k.starts_with("ExifTool:Warning") || k == "ExifTool:Error" {
            fail(format!("ExifTool: {v}"));
        }
        if identifying(k) {
            fail(format!("{k} = {v} left"));
        }
        if choice == Choice::KeepOnlyDisplay && informative(k, v) {
            fail(format!("{k} = {v} left in the strictest choice"));
        }
    }
    if let Some(e) = after.get("ExifTool:Warning") {
        fail(format!("ExifTool warning: {e}"));
    }
    for (k, v) in &before {
        let keep =
            structural(k) || (choice == Choice::RemoveLocationAndIdentifiers && dates_kept(k));
        if keep && after.get(k) != Some(v) {
            fail(format!("{k} was {v:?}, now {:?}", after.get(k)));
        }
    }

    pixels(label, input, &out, mime, &mut fail);
    if mime.starts_with("video/") || mime.starts_with("audio/") {
        for m in ilst_problems(&out) {
            fail(m);
        }
    }
    containers(input, &out, &before, &mut fail);
    Some(out)
}

/// Android's MP4 extractor rejects the whole file, dates and duration
/// included, when an `ilst` holds two items of the same type (two `free`
/// atoms, say). Checked on a Pixel 7 Pro and a Pixel 10 Pro XL.
pub fn ilst_problems(b: &[u8]) -> Vec<String> {
    fn walk(d: &[u8], start: usize, end: usize, in_ilst: bool, out: &mut Vec<String>) {
        let mut seen = Vec::new();
        let mut i = start;
        while i + 8 <= end {
            let size = u32::from_be_bytes(d[i..i + 4].try_into().unwrap()) as usize;
            let kind: [u8; 4] = d[i + 4..i + 8].try_into().unwrap();
            if size < 8 || i + size > end {
                return;
            }
            if in_ilst {
                if seen.contains(&kind) {
                    out.push(format!(
                        "ilst has two {:?} items",
                        String::from_utf8_lossy(&kind)
                    ));
                }
                seen.push(kind);
            }
            match &kind {
                b"moov" | b"trak" | b"mdia" | b"udta" => walk(d, i + 8, i + size, false, out),
                b"ilst" => walk(d, i + 8, i + size, true, out),
                b"meta" => {
                    let full = d.get(i + 12..i + 16) != Some(b"hdlr");
                    walk(d, i + if full { 12 } else { 8 }, i + size, false, out)
                }
                _ => {}
            }
            i += size;
        }
    }
    let mut out = Vec::new();
    walk(b, 0, b.len(), false, &mut out);
    out
}

fn pixels(label: &str, input: &[u8], out: &[u8], mime: &str, fail: &mut impl FnMut(String)) {
    match mime {
        "image/jpeg" | "image/png" | "image/webp" | "image/gif" => {
            let decode = |b: &[u8]| image::load_from_memory(b).map(|i| i.to_rgba8().into_raw());
            match (decode(input), decode(out)) {
                (Ok(a), Ok(b)) if a == b => {}
                (Ok(_), Ok(_)) => fail("decoded pixels differ".into()),
                (Ok(_), Err(e)) => fail(format!("output does not decode: {e}")),
                (Err(_), _) => {} // the decoder cannot read the original either
            }
            if mime == "image/gif" && frames(input) != frames(out) {
                fail("GIF frames differ".into());
            }
        }
        "image/heic" | "image/heif" | "image/avif" if have_heif_dec() => {
            match (heif_decode(label, input), heif_decode(label, out)) {
                (Some(a), Some(b)) if a == b => {}
                (Some(_), Some(_)) => fail("decoded pixels differ".into()),
                (Some(_), None) => fail("output does not decode".into()),
                (None, _) => {}
            }
        }
        m if (m.starts_with("video/") || m.starts_with("audio/")) && have("ffmpeg") => {
            match (streamhash(input), streamhash(out)) {
                (Some(a), Some(b)) if a == b => {}
                (Some(_), Some(_)) => fail("media streams differ".into()),
                (Some(_), None) => fail("ffmpeg cannot read the output".into()),
                (None, _) => {}
            }
        }
        _ => {}
    }
}

fn frames(b: &[u8]) -> Vec<Vec<u8>> {
    use image::AnimationDecoder;
    let Ok(dec) = image::codecs::gif::GifDecoder::new(std::io::Cursor::new(b)) else {
        return Vec::new();
    };
    dec.into_frames()
        .filter_map(|f| f.ok())
        .map(|f| f.into_buffer().into_raw())
        .collect()
}

fn have_heif_dec() -> bool {
    Command::new("heif-dec")
        .arg("--version")
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .is_ok()
}

fn heif_decode(label: &str, b: &[u8]) -> Option<Vec<u8>> {
    let src = scratch(label, b);
    let dst = src.with_extension("png");
    let ok = Command::new("heif-dec")
        .arg(&src)
        .arg(&dst)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .ok()?
        .success();
    let png = ok.then(|| std::fs::read(&dst).ok()).flatten();
    let _ = std::fs::remove_file(&src);
    // heif-dec writes auxiliary images (depth, gain maps) next to it.
    if let Some(dir) = dst.parent() {
        let stem = dst.file_stem().unwrap().to_string_lossy().into_owned();
        for e in std::fs::read_dir(dir).into_iter().flatten().flatten() {
            if e.file_name().to_string_lossy().starts_with(&stem) {
                let _ = std::fs::remove_file(e.path());
            }
        }
    }
    image::load_from_memory(&png?).ok().map(|i| {
        i.to_rgba16()
            .into_raw()
            .iter()
            .flat_map(|v| v.to_le_bytes())
            .collect()
    })
}

/// Hashes of every stream's packets, as ffmpeg demuxes them.
pub fn streamhash(b: &[u8]) -> Option<String> {
    let src = scratch("x.mp4", b);
    let out = Command::new("ffmpeg")
        .args(["-v", "error", "-i"])
        .arg(&src)
        .args([
            "-map",
            "0",
            "-c",
            "copy",
            "-f",
            "streamhash",
            "-hash",
            "md5",
            "-",
        ])
        .output()
        .ok()?;
    let _ = std::fs::remove_file(&src);
    (out.status.success() && !out.stdout.is_empty())
        .then(|| String::from_utf8_lossy(&out.stdout).into_owned())
}

/// Ultra HDR and motion photos: the embedded images and video still where
/// the metadata says.
fn containers(
    input: &[u8],
    out: &[u8],
    before: &BTreeMap<String, String>,
    fail: &mut impl FnMut(String),
) {
    for tag in [
        "MPImage2",
        "MPImage3",
        "GainMapImage",
        "DepthImage",
        "ConfidenceMap",
    ] {
        let a = binary_tag(tag, input);
        if a.is_empty() {
            continue;
        }
        let b = binary_tag(tag, out);
        if a != b {
            fail(format!(
                "{tag} differs ({} bytes before, {} after)",
                a.len(),
                b.len()
            ));
        }
    }
    if before.keys().any(|k| k.ends_with(":MPImageStart")) {
        // Detected as Ultra HDR: MPF and the hdrgm version agree.
        let t = tags(out);
        if before.contains_key("XMP-hdrgm:Version") && !t.contains_key("XMP-hdrgm:Version") {
            fail("hdrgm XMP lost".into());
        }
    }
    let video = binary_tag("MotionPhotoVideo", input);
    if !video.is_empty() {
        let v = binary_tag("MotionPhotoVideo", out);
        if v.len() != video.len() {
            fail(format!(
                "motion-photo video is {} bytes, was {}",
                v.len(),
                video.len()
            ));
        } else if have("ffmpeg") && streamhash(&v) != streamhash(&video) {
            fail("motion-photo video does not play the same".into());
        }
    }
}
