# graphite-strip

graphite's metadata stripper. The old phone runs it before encrypting, so
removed metadata never leaves it. The spec is the "Removing metadata" section
of [docs/media.md](../docs/media.md).

```bash
cargo test
```

```bash
cargo clippy --all-targets -- -D warnings
```

The tests need [ExifTool](https://exiftool.org) (`brew install exiftool`).
With ffmpeg and libheif's `heif-dec` installed, they also check that video
streams and HEIF/AVIF pixels are unchanged; without them those two checks
are skipped.

## API

- `strip(bytes, mime, choice) -> Result<Stripped, Unstrippable>`.
  - `Choice::KeepAll` returns the input as it is, without parsing it.
  - `Choice::RemoveLocationAndIdentifiers` is the middle choice.
  - `Choice::KeepOnlyDisplay` is the strictest.
- `Stripped` is ranges of the input interleaved with new bytes (`parts()`,
  `write_to()`, `to_vec()`, `len()`, `is_unchanged()`). A video can be
  streamed from the file or a memory map and hashed on the way out without
  being copied. Only the metadata is read.
- `Unstrippable`: `Raw` (DNG and other RAW types), `NotMedia` (documents and
  other audio, which are copied unchanged in every choice), `Malformed`, or
  `Unsupported` (the file parses, but uses a structure the stripper can't
  rewrite without leaving metadata or losing data). The stripper never
  falls back to returning the input.
- `applies_to(mime)`: whether a type is a photo or video, RAW included.
- `STRIPPER_VERSION` (now 2) goes in the manifest. Bump it whenever any
  output changes.

The file's signature decides its format; `mime` is used only when the
signature isn't recognised (and to recognise RAW).

The output is a pure function of the input, the choice and
`STRIPPER_VERSION`, and `strip(strip(x)) == strip(x)`:

- EXIF is rebuilt in a fixed layout from the tags that stay.
- XMP is copied byte for byte, except for the properties removed. A start
  tag is rebuilt only when one of its attributes goes, from the original
  bytes of the others.

Nothing is re-encoded.

## What is removed

Images use allowlists: a tag or XMP property not listed in
[src/policy.rs](src/policy.rs) is removed, so metadata nobody thought of
doesn't leak. Both stripping choices keep orientation, resolution,
colour-space and pixel-dimension tags, the ICC profile, Ultra HDR (`hdrgm`,
the Google container, MPF), motion-photo fields, depth maps (GDepth, GImage,
Dynamic Depth apart from its pose, vendor and app info) and portrait-editing
fields. The middle choice also keeps dates, time-zone offsets, sub-seconds
and exposure settings (`LensSpecification` among them, but not lens make or
model).

| Format | What the stripper does |
|---|---|
| JPEG | Rebuilds EXIF from IFD0, the Exif IFD and the Interop IFD. It never writes the GPS IFD, IFD1 (the thumbnail), SubIFDs or maker notes. Filters XMP and extended XMP: extended XMP is rewritten with a new MD5 GUID, or dropped along with `HasExtendedXMP` when nothing is left. Drops APP13 (IPTC), COM, JFXX, the JFIF thumbnail and every APPn not on the list (JFIF, ICC, MPF, ISO 21496-1 gain map, Adobe APP14). Recalculates the MPF entry sizes and offsets and zeroes `ImageUIDList`. Strips a motion photo's MP4 in place (same size, see MP4), so the offsets counted from the end of the file stay right. Copies everything else after EOI byte for byte |
| HEIC, HEIF, AVIF | Filters the `Exif` and XMP (`mime`, `application/rdf+xml`) items and writes each back into its own extents, padded to the same length: zeros after the TIFF, spaces after the XMP. No box changes size and `iloc` is unchanged |
| PNG | Rewrites `eXIf` (filtered) and the XMP `iTXt` with new CRCs. Drops `tEXt`, `zTXt`, other `iTXt`, unknown ancillary chunks and data after IEND. Keeps `tIME` in the middle choice only |
| WebP | Filters `EXIF` and `XMP `, drops unknown chunks, and updates the VP8X EXIF/XMP flags and the RIFF size |
| GIF | Drops comments, the XMP application extension, unknown extensions and data after the trailer. Keeps frames, graphic control, plain text, NETSCAPE2.0/ANIMEXTS1.0 looping and ICCRGBG1012 |
| MP4, MOV, 3GP, M4A | Turns each removed atom into a `free` atom of the same size with its contents zeroed. In an `ilst`, the kept items move up and one `free` atom after the `ilst` takes the space of the removed ones: Android rejects the whole file (date, duration and all) when an `ilst` holds two items of the same type, such as two `free` atoms. Middle choice: `©xyz`, `loci`, `©mak`, `©mod`, `©swr`, `©too`, `©enc`, `manu`, `modl`, `auth`, `©aut`, XMP (`XMP_`, XMP `uuid`, `xml `), and the `mdta` items for location, make, model, software, encoder, manufacturer, `com.android.version` and Apple camera/content identifiers. Strictest: every `udta` and `ilst` item except `com.android.capture.fps` and `com.android.video.temporal_layers_count`, and zeroes the `mvhd`, `tkhd` and `mdhd` creation and modification times |

## Crates

| Crate | Version | Why |
|---|---|---|
| `crc32fast` | 1.5.4 | PNG chunk CRCs for the rewritten `eXIf` and `iTXt` chunks. Widely used (it is what `png` and `flate2` use); its only dependency is `cfg-if` |
| `md-5` | 0.11.0 | The extended-XMP GUID is the MD5 of the extended packet (XMP spec, part 3), so a rewritten packet needs a new one. RustCrypto; brings `digest`, `block-buffer`, `crypto-common`, `hybrid-array`, `typenum`, `const-oid`, `cfg-if`. Not used for security |
| `image` (dev) | 0.25.10 | Decodes JPEG, PNG, GIF (every frame) and WebP in the tests, to check the pixels are identical. Only those four codecs are enabled |
| `serde_json` (dev) | 1.0 | Reads ExifTool's JSON output in the tests |

The XMP tokenizer, the TIFF writer and the JPEG, PNG, WebP, GIF and
ISO BMFF parsers are written here. No crate does lossless in-place rewriting
for all of these formats, and each parser is small.

## Tests

- **Unit tests** (`src/tiff.rs`, `src/xmp.rs`): EXIF rebuild in both byte
  orders, XMP filtering and fact extraction.
- **Synthetic fixtures** (`tests/fixtures/`, committed; `make.sh` regenerates
  them): JPEG with GPS, an IFD1 thumbnail, EXIF identifiers,
  XMP, IPTC and a comment; an Ultra HDR JPEG with a segment after MPF, so the
  gain map's offset has to move; a motion photo; PNG; WebP; GIF; HEIC; AVIF;
  MP4; MOV; 3GP; M4A. Each file and each stripping choice is checked:
  - ExifTool finds no removed tag;
  - what must stay stays, with the same value;
  - the decoded pixels or the media streams' packet hashes are identical;
  - MPF images and motion-photo videos are where the metadata says, and the
    video plays the same;
  - stripping twice gives the bytes of stripping once.
- **Robustness**: every fixture cut at every 7th byte, and 400 random byte
  changes per fixture. Nothing panics, and anything accepted strips to a
  fixed point. It also ran clean with five seeds at 4,000 changes each.
- **A check of the checks**: the same assertions run against unstripped files
  must fail.
- **Private corpus** (`private-fixtures/`, git-ignored; skipped when empty,
  as in CI): the same checks on real files.

### What the private corpus covered

Pulled over adb on 2026-11-03.

- **Pixel 7 Pro** (stock Android 17):
  - Four Pixel 7 Pro camera JPEGs, all Ultra HDR (MPF, `hdrgm`, Google
    container, extended XMP holding the HDR+ maker note), one of them a
    Night Sight shot.
  - Two Pixel 5 portrait JPEGs (Dynamic Depth: depth and confidence maps
    after EOI, 1.3 to 1.7 MB of extended XMP, IFD1 thumbnail).
  - A Pixel 3 JPEG with GPS.
  - Two iPhone HEICs received in Messages (iPhone 14 with GPS, iPhone 16 Pro
    Max; Apple gain-map XMP).
  - An AVIF, two WebPs and a GIF from downloads, none with metadata.
  - A PNG screenshot (`eXIf` with dates and Software).
  - Three Pixel camera MP4s (`©xyz`, `mdta` make and model, capture fps, a
    timed `mett` track, one Top Shot `.TS.mp4`).
  - A 3GP from Messages (`loci`, `date`).
  - Two M4A ringtones.
- **Pixel 10 Pro XL** (GrapheneOS):
  - Three GrapheneOS Camera JPEGs (orientation and ICC only).
  - A small JPEG from `Pictures/Screenshots`.
  - A PNG screenshot.
  - A camera MP4.
  - A call-recording M4A.

Neither phone had motion photos, camera HEICs, RAW/DNG files, or WebP and
GIF files with metadata. Voice recordings on the Pixel 7 Pro are WAV, which
is out of scope. Those cases are covered only by the synthetic fixtures (DNG
only by the `Unstrippable::Raw` test).

## Not handled

- **Images MPF points to** (gain maps) are kept byte for byte. If one
  carries metadata the choice removes (EXIF, a comment, XMP beyond `hdrgm`),
  the file is `Unsupported` rather than rewritten. Pixel gain maps are clean.
- **Other data after a JPEG's EOI** is copied unchanged: Dynamic Depth items,
  and vendor trailers such as Samsung's SEFT. Those items are not inspected.
- **Video sample data** isn't inspected: the Pixel `mett` track
  (`application/meta`), GoPro GPMF, Apple timed location metadata, and the
  per-frame data in a motion photo's video.
- **HEIF**: only `Exif` and XMP items are filtered. These are refused as
  `Unsupported`: other `mime` items, encoded XMP, items built from other
  items (`iloc` construction method 2), `infe` versions 0 and 1, and image
  sequences without a top-level `meta`. Item names, `udes` properties and
  HEVC thumbnails (`thmb`) are kept.
- **PNG**: compressed XMP `iTXt` is `Unsupported`. Text chunks are dropped in
  the middle choice too, including dates such as `Creation Time`; EXIF dates
  stay.
- **Portrait editing data** stays in both choices: `GCamera:RelitInputImageData`
  (the pre-relighting image, same crop) and `PortraitNote` (opaque). The
  gallery needs them to edit the portrait.
- **M4A music tags** (title, artist, album, cover) stay in the middle choice,
  which removes only what the doc lists for video. The strictest choice
  frees them.
