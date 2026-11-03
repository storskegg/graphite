//! The committed fixtures (see tests/fixtures/make.sh): every format, every
//! choice. They carry every kind of tag the stripping choices remove.

mod common;

use std::path::Path;

use common::{CHOICES, check, ilst_problems, mime_for, tags};
use graphite_strip::{Choice, Unstrippable, applies_to, strip};

fn fixture(name: &str) -> Vec<u8> {
    std::fs::read(
        Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("tests/fixtures")
            .join(name),
    )
    .unwrap()
}

fn run(name: &str) -> Vec<Vec<u8>> {
    let input = fixture(name);
    let mime = mime_for(Path::new(name));
    let mut problems = Vec::new();
    let mut outs = Vec::new();
    for choice in CHOICES {
        if let Some(out) = check(name, &input, mime, choice, &mut problems) {
            outs.push(out);
        }
    }
    assert!(problems.is_empty(), "{}", problems.join("\n"));
    outs
}

#[test]
fn jpeg() {
    let outs = run("jpeg-full.jpg");
    let before = tags(&fixture("jpeg-full.jpg"));
    assert!(before.contains_key("GPS:GPSLatitude") && before.contains_key("IFD1:ThumbnailLength"));
    let middle = tags(&outs[0]);
    assert_eq!(
        middle.get("ExifIFD:DateTimeOriginal").map(String::as_str),
        Some("2020:01:02 03:04:05")
    );
    assert_eq!(
        middle.get("ExifIFD:OffsetTimeOriginal").map(String::as_str),
        Some("-06:00")
    );
    assert_eq!(
        middle.get("IFD0:Orientation").map(String::as_str),
        Some("Rotate 90 CW")
    );
    let strict = tags(&outs[1]);
    assert!(!strict.contains_key("ExifIFD:DateTimeOriginal"));
    assert_eq!(
        strict.get("IFD0:Orientation").map(String::as_str),
        Some("Rotate 90 CW")
    );
    assert!(strict.contains_key("ICC_Profile:ProfileDescription"));
}

#[test]
fn ultra_hdr_offsets_are_recalculated() {
    let input = fixture("ultrahdr.jpg");
    let outs = run("ultrahdr.jpg");
    for out in &outs {
        assert!(out.len() < input.len());
        let t = tags(out);
        assert_eq!(t.get("MPF0:NumberOfImages").map(String::as_str), Some("2"));
        assert_eq!(t.get("XMP-hdrgm:Version").map(String::as_str), Some("1.0"));
        // ExifTool resolves the MPF offset (which counts from the MPF
        // header) to a file offset: the original gain map must be there.
        let start: usize = t["MPImage2:MPImageStart"].parse().unwrap();
        let len: usize = t["MPImage2:MPImageLength"].parse().unwrap();
        assert_eq!(start + len, out.len());
        assert_eq!(&out[start..], &input[input.len() - len..]);
        assert_eq!(t["MPImage1:MPImageLength"].parse::<usize>().unwrap(), start);
    }
}

#[test]
fn motion_photo_keeps_its_video() {
    let input = fixture("motion.jpg");
    let outs = run("motion.jpg");
    let video = fixture("video.mp4");
    for out in &outs {
        let t = tags(out);
        assert_eq!(
            t.get("XMP-GCamera:MotionPhoto").map(String::as_str),
            Some("1")
        );
        // Offsets count from the end of the file: the video is the same
        // length and ends the file.
        let tail = &out[out.len() - video.len()..];
        assert_eq!(&tail[4..8], b"ftyp");
        assert!(out.len() < input.len());
    }
    // The embedded video is stripped like a standalone one.
    let middle_video = &outs[0][outs[0].len() - video.len()..];
    let standalone = strip(&video, "video/mp4", Choice::RemoveLocationAndIdentifiers)
        .unwrap()
        .to_vec();
    assert_eq!(middle_video, &standalone[..]);
}

#[test]
fn png() {
    let outs = run("png.png");
    assert_eq!(
        tags(&outs[0])
            .get("ExifIFD:DateTimeOriginal")
            .map(String::as_str),
        Some("2020:01:02 03:04:05")
    );
}

#[test]
fn webp() {
    let outs = run("webp.webp");
    let t = tags(&outs[1]);
    assert!(t.contains_key("ICC_Profile:ProfileDescription"));
    // VP8X flags match the chunks that are left.
    assert_eq!(&outs[1][12..16], b"VP8X");
    let flags = outs[1][20];
    assert_eq!(flags & 0x04, 0, "XMP flag set without XMP");
    assert_eq!(flags & 0x08 != 0, outs[1].windows(4).any(|w| w == b"EXIF"));
}

#[test]
fn gif() {
    let input = fixture("gif.gif");
    assert!(input.windows(11).any(|w| w == b"XMP DataXMP"));
    for out in run("gif.gif") {
        assert!(!out.windows(11).any(|w| w == b"XMP DataXMP"));
        assert!(!out.windows(2).any(|w| w == [0x21, 0xFE]));
        assert!(out.windows(11).any(|w| w == b"NETSCAPE2.0"));
    }
}

#[test]
fn heic() {
    let input = fixture("heic.heic");
    for out in run("heic.heic") {
        assert_eq!(out.len(), input.len());
    }
}

#[test]
fn avif() {
    run("avif.avif");
}

#[test]
fn mp4() {
    let input = fixture("video.mp4");
    let outs = run("video.mp4");
    assert_eq!(outs[0].len(), input.len());
    assert_eq!(
        tags(&outs[0])
            .get("QuickTime:CreateDate")
            .map(String::as_str),
        Some("2020:01:02 03:04:05")
    );
    assert_eq!(
        tags(&outs[1])
            .get("QuickTime:CreateDate")
            .map(String::as_str),
        Some("0000:00:00 00:00:00")
    );
    // The slow-motion frame rate is display data.
    assert_eq!(
        tags(&outs[1])
            .get("Keys:AndroidCaptureFPS")
            .map(String::as_str),
        Some("30")
    );
}

/// Android gave stripped videos no date (nor duration): its extractor
/// rejected an `ilst` holding several `free` atoms. The kept items now come
/// first and one `free` atom after the `ilst` takes the space of the rest.
#[test]
fn stripped_video_stays_readable_by_android() {
    for name in ["video.mp4", "video.mov", "video.3gp", "audio.m4a"] {
        let input = fixture(name);
        assert!(ilst_problems(&input).is_empty(), "{name}");
        for choice in CHOICES {
            let out = strip(&input, mime_for(Path::new(name)), choice)
                .unwrap()
                .to_vec();
            assert_eq!(out.len(), input.len(), "{name} {choice:?}");
            let p = ilst_problems(&out);
            assert!(p.is_empty(), "{name} {choice:?}: {p:?}");
        }
    }
    // The check itself: two items of one type are flagged.
    let mut twice = fixture("video.mp4");
    let ilst = twice.windows(4).position(|w| w == b"ilst").unwrap() + 4;
    let first = u32::from_be_bytes(twice[ilst..ilst + 4].try_into().unwrap()) as usize;
    twice[ilst + 4..ilst + 8].copy_from_slice(b"free");
    twice[ilst + first + 4..ilst + first + 8].copy_from_slice(b"free");
    assert!(!ilst_problems(&twice).is_empty());
}

#[test]
fn mov() {
    run("video.mov");
}

#[test]
fn three_gp() {
    run("video.3gp");
}

#[test]
fn m4a() {
    run("audio.m4a");
}

#[test]
fn keep_all_is_byte_for_byte() {
    for name in ["jpeg-full.jpg", "video.mp4", "png.png"] {
        let input = fixture(name);
        let s = strip(&input, mime_for(Path::new(name)), Choice::KeepAll).unwrap();
        assert!(s.is_unchanged());
        assert_eq!(s.to_vec(), input);
    }
}

#[test]
fn raw_is_unstrippable() {
    let tiff = b"II*\0\x08\0\0\0\0\0\0\0\0\0".to_vec();
    for mime in ["image/x-adobe-dng", "image/dng"] {
        assert_eq!(
            strip(&tiff, mime, Choice::KeepOnlyDisplay).unwrap_err(),
            Unstrippable::Raw
        );
        assert!(applies_to(mime));
    }
    // A TIFF under a JPEG name is not quietly passed through either.
    assert!(strip(&tiff, "image/jpeg", Choice::KeepOnlyDisplay).is_err());
}

#[test]
fn broken_files_are_unstrippable() {
    for name in [
        "jpeg-full.jpg",
        "png.png",
        "webp.webp",
        "gif.gif",
        "heic.heic",
        "video.mp4",
        "ultrahdr.jpg",
        "motion.jpg",
    ] {
        let input = fixture(name);
        let mime = mime_for(Path::new(name));
        // Cut inside the metadata, near the start of the file.
        let cut = &input[..input.len().min(200)];
        for choice in CHOICES {
            match strip(cut, mime, choice) {
                Err(Unstrippable::Malformed { .. } | Unstrippable::Unsupported { .. }) => {}
                other => panic!("{name} cut to 200 bytes: {other:?}"),
            }
        }
    }
    let pdf = b"%PDF-1.7\n";
    assert!(!applies_to("application/pdf"));
    assert!(matches!(
        strip(pdf, "application/pdf", Choice::KeepOnlyDisplay),
        Err(Unstrippable::NotMedia { .. })
    ));
}

#[test]
fn the_checks_catch_leftovers() {
    // Run the checks against an unstripped copy: they must complain.
    for (name, expect) in [
        ("jpeg-full.jpg", "GPS:GPSLatitude"),
        ("video.mp4", "UserData:GPSCoordinates"),
        ("png.png", "IFD0:Make"),
    ] {
        let mut problems = Vec::new();
        check(
            name,
            &fixture(name),
            mime_for(Path::new(name)),
            Choice::KeepAll,
            &mut problems,
        );
        assert!(
            problems.iter().any(|p| p.contains(expect)),
            "{name}: {problems:#?}"
        );
    }
}

#[test]
fn damaged_files_never_panic() {
    let names = [
        "jpeg-full.jpg",
        "ultrahdr.jpg",
        "motion.jpg",
        "png.png",
        "webp.webp",
        "gif.gif",
        "heic.heic",
        "avif.avif",
        "video.mp4",
        "video.mov",
        "video.3gp",
        "audio.m4a",
    ];
    let mut seed = 0x9E37_79B9_7F4A_7C15u64;
    let mut next = move || {
        seed ^= seed << 13;
        seed ^= seed >> 7;
        seed ^= seed << 17;
        seed
    };
    for name in names {
        let input = fixture(name);
        let mime = mime_for(Path::new(name));
        let mut cases: Vec<Vec<u8>> = (0..input.len())
            .step_by(7)
            .map(|n| input[..n].to_vec())
            .collect();
        for _ in 0..400 {
            let mut d = input.clone();
            for _ in 0..1 + next() % 4 {
                let at = (next() as usize) % d.len();
                d[at] = next() as u8;
            }
            cases.push(d);
        }
        for d in cases {
            for choice in CHOICES {
                if let Ok(out) = strip(&d, mime, choice) {
                    // Whatever it accepts, it must also accept again.
                    let once = out.to_vec();
                    let twice = strip(&once, mime, choice).map(|s| s.to_vec());
                    assert_eq!(
                        twice.as_ref(),
                        Ok(&once),
                        "{name}: not idempotent on a damaged file"
                    );
                }
            }
        }
    }
}
