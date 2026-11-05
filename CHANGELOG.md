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
- `android/`: live progress during the copy and the verification on both phones
  (bytes, MB/s, time left, current file); the old phone shows the new phone's
  verification from progress messages. Per-phase timing in the log after each copy.
- `android/`: the new phone requests the next small file (16 MB or less) while
  it finishes the current one, so the old phone reads and sends it meanwhile.
- `android/`: a treemap of the transfer on both phones, above the progress:
  one square per file sized by its bytes, grouped by folder. The squares are
  coloured by state during the copy and the verification, and a tap shows the
  file. The old phone gets the new phone's changes with the progress messages.
- `android/`: when a copy's `DATE_TAKEN` comes out unlike the old phone's (the
  old value was written by an app), verification sets the copy's modification
  time to the old phone's date taken and rescans, keeping it only if the date
  then matches. The receipt notes each such file.
- `android/`: the no-duplicates rule runs both ways: after an earlier stripping
  run, "Keep all metadata" skips an original whose stripped copy is there.
- `android/emulator-test.sh`: the media flow end to end on two emulators (API 37
  and API 30) over a debug-only TCP transport: all three metadata choices, a
  second run, resume after a reset socket, space and battery refusals, removal.
  A device test records what MediaStore does with `DATE_TAKEN`. CI: Android unit
  tests on every push, the emulator test on request.
- Design for step 2: messages, calls, contacts and blocked numbers as records
  in the same signed manifest, plan and receipt, identified by normalized
  fields; the SMS role window; attestation ([docs/records.md](docs/records.md)).
- `core/`, `core-ffi/`: record entries in the manifest, numbered after the
  files in plans and receipts, and `record_identity`.

### Fixed

- `strip/`: stripped videos and M4A files kept their `mvhd` date, but Android
  could not read them at all, so they got no `DATE_TAKEN` (nor duration).
  Removed `ilst` items each became a `free` atom, and Android's parser rejects
  an `ilst` with two items of the same type. They now share one `free` atom
  after the `ilst`. `STRIPPER_VERSION` is 2.
- `android/`, from the final pass on the phones: removal now gets every approved
  item into the trash (Android 17 refused items whose MediaStore MIME type
  didn't match their name; graphite corrects the type, never the file); a
  resume no longer waits 2 minutes for a USB permission callback; Done on the
  last screen starts a new session on both phones; setup no longer offers to
  reinstall graphite when it is already there; a refusal on the new phone (no
  space, low battery) reaches the old phone before the link closes; the status
  line no longer redraws twice a second.
