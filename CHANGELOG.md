# Changelog

Notable changes to graphite. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). graphite has no
releases yet.

## Unreleased

### Added

- Design: roles, transports, cable-only pairing with a 6-digit comparison, user
  authentication, cryptography and threat model ([docs/design.md](docs/design.md)).
- Design for step 1, media: manifest, preflight, signed plan, copy, verification,
  optional removal, file dates and duplicate detection ([docs/media.md](docs/media.md)).
- Feasibility spike on a Pixel 7 Pro and a Pixel 10 Pro XL: AOA throughput, adb
  over the cable, host-role swap, restricted permissions, MediaStore dates, SMS,
  MMS, call log and blocked numbers ([docs/spike.md](docs/spike.md)).
- `core/`: the crypto core. HPKE with X-Wing for pairing and session keys,
  chunked XChaCha20-Poly1305 stream, ML-DSA-65 signed manifest, plan and receipt,
  BLAKE3 hashing.
- `strip/`: lossless, deterministic metadata stripper for JPEG, HEIF/AVIF, PNG,
  WebP, GIF and MP4/MOV/3GP/M4A.
- `core-ffi/` and `android/`: first tracer build. It pairs over the cable and
  sends one encrypted file, end to end.
- `android/`: the media transfer, phases 1-6 of [docs/media.md](docs/media.md):
  inventory and metadata choice, signed manifest, comparison by hash with a hash
  cache, preflight (space, battery, names) and signed plan, copy as pending
  MediaStore items with modification times restored, verification without the
  cache and a signed receipt, optional removal to the source's trash. A journal
  cleans up after interruptions; a second run copies nothing.
- `android/`: setup over the cable. The new phone installs graphite on the old
  one with its own adb client when the old phone has USB debugging on.
- `core-ffi/`: wraps `strip/` (memory-mapped files, both hashes on open).
