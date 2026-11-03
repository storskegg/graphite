# graphite for Android

One APK for both phones. On a GrapheneOS phone it is the **target** (the new
phone); on any other Android 11+ phone it is the **source** (the old phone).
The two phones talk over a USB-C cable only: Android Open Accessory, with the
target as USB host. This iteration copies **media**: photos, videos, audio,
downloads and documents ([docs/media.md](../docs/media.md)).

## Build

Needs:

- JDK 17 or newer (built with 27)
- The Android SDK with platform 37 and NDK 30.0.16248370 (`ANDROID_HOME` or `local.properties`)
- Rust with the `aarch64-linux-android` target: `rustup target add aarch64-linux-android`
- [cargo-ndk](https://github.com/bbqsrc/cargo-ndk): `cargo install cargo-ndk --version 4.1.2`

```bash
./gradlew :app:assembleDebug
```

```bash
./gradlew :app:testDebugUnitTest
```

The build compiles `core-ffi/` (which wraps `core/` and `strip/`) for arm64 with
cargo-ndk, then generates the Kotlin bindings from the built library with
uniffi. Cargo is taken from `$CARGO`, else `~/.cargo/bin/cargo`. Only
`arm64-v8a` is built: every phone graphite targets is 64-bit ARM.

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Install it on both
phones; or install it on the new phone only and let it install itself on the
old phone over the cable (needs USB debugging on the old phone, see below).

## How a transfer goes

1. Both phones: graphite asks you to confirm it's you (fingerprint or screen
   lock). It asks again after the phone locks or graphite leaves the screen,
   and always before removing anything.
2. Connect the phones. The new phone must control USB; graphite tells you how
   to swap "USB controlled by" if the old phone took it.
3. If graphite isn't answering on the old phone, the new phone offers to
   install it over the cable (old phone has USB debugging on) or explains how
   to sideload it.
4. Both phones show six digits; compare them and confirm on both. Accept the
   old phone on the new one.
5. On the new phone, choose what to do with metadata (nothing is preselected),
   and whether to include `Android/media/`.
6. The old phone lists and hashes its files; the new phone works out what it
   already has, checks space, battery and names, and shows the plan. Untick
   folders if you like, continue, and start the copy.
7. The new phone verifies everything and signs a receipt; the old phone checks
   it. Then, only if you ask, the old phone moves verified files to its trash.

**The role check is not attested yet.** A phone counts as GrapheneOS when
GrapheneOS's own system apps are present. The screen and this README say so;
hardware attestation goes in at the marked seam in
[`role/Roles.kt`](app/src/main/kotlin/io/github/storskegg/graphite/role/Roles.kt).

### Test builds

A debuggable build can limit the old phone's scope to folders with one name,
so a test never reads or copies your own files:

```bash
adb shell am start -n io.github.storskegg.graphite/.MainActivity --es test_scope graphite-test
```

An empty value clears it. Both phones show "TEST SCOPE" while it is set, and
the cable setup passes it on to the old phone. Release builds ignore it.

## Layout

| Path | What |
|---|---|
| `link/` | Frames (magic, CRCs, resync) and the reliable, resumable link over a byte pipe |
| `usb/` | USB role, AOA switch, host and accessory pipes, the connection keeper, setup over the cable (adb client) |
| `auth/` | The user-presence gate: an auth-bound Keystore key signs a nonce |
| `role/` | Target or source (unattested check, with the attestation seam) |
| `session/` | Encrypted channel on top of the link, message types |
| `media/` | Source library, target store and hash cache, comparison, preflight, journal, receipts, both flows |
| `Flows.kt` | Pairing and the session from first screen to last |

## Dependencies

| Dependency | Version | Why |
|---|---|---|
| Android Gradle Plugin | 9.4.1 | Builds the APK; its built-in Kotlin support compiles the app |
| Gradle | 9.8.1 | Wrapper |
| Android NDK | 30.0.16248370 | Links the Rust library for Android |
| cargo-ndk | 4.1.2 | Drives cargo with the NDK toolchain (build tool, not shipped) |
| uniffi | 0.32.2 (pinned) | Generates the Kotlin bindings for `core-ffi/`, from proc macros |
| JNA | 5.19.1 | uniffi's Kotlin bindings call the Rust library through JNA |
| memmap2 | 0.9.12 | `core-ffi/`: memory-maps files for the stripper and hashing, so videos aren't read into memory. Depends only on `libc` |
| JUnit | 4.13.2 | Unit tests (JVM only) |

No AndroidX: the UI is plain Android views and the platform BiometricPrompt.
SQLite (the hash cache) and the Keystore are the platform's.

## Tests

JVM unit tests (`app/src/test/`): frames and the link (handshake discarding
leftovers, windowing, lost and resent records, resume after EIO, a restarted
peer), comparison, preflight maths, the journal, receipt mapping and the wire
messages. `core-ffi` has Rust tests for the wrapper and the stripped-file
reader (`cargo test` in `core-ffi/`).

## Verified on device

Pixel 7 Pro (stock Android 17) as source, Pixel 10 Pro XL (GrapheneOS,
Android 17) as target, USB 3.2 cable, 3 November 2026. The test set was 12
synthetic files in `graphite-test` folders: JPEGs with GPS (one also as a
duplicate in `Pictures/`), a motion photo, Ultra HDR, HEIC, PNG, an MP4, a MOV,
an M4A, a 48 MB download, a PDF and a text file.

Verified:

- User authentication on both phones, pairing with the six digits, AOA with
  the target as host, link speed 74-83 MB/s.
- Tracer: a 64 MiB encrypted file arrived with a matching BLAKE3 at 61.6 MB/s.
- "Remove location and identifiers": all 12 entries copied and verified
  (the duplicate copied once), receipt signed on the target and verified on
  the source. GPS, make, model and serial numbers were gone from the copies;
  EXIF dates stayed. Photos kept `DATE_TAKEN`; every file kept its
  modification time.
- **A second run copies nothing**: three more runs each gave "0 copied, 12
  already on the new phone (verified)", including the copy of `clip.mov` that
  MediaStore had renamed (identity is the bytes).
- An unticked folder is reported as not copied, unticked.
- **Setup over the cable**: with graphite uninstalled on the Pixel 7 Pro and USB
  debugging on, the Pixel 10 installed it (about 1.6 s), allowed all-files
  access and opened it, after a replug into normal mode.
- **Removal**: from a fresh receipt, after re-authentication, 11 of 12 test
  files went to the Pixel 7 Pro's trash (`.trash-storage`): photos, videos and
  audio through Android's trash request, the PDF, text and download through
  `IS_TRASHED`. Nothing outside the chosen folders was touched.

Found and fixed on device: digests compared by reference (everything looked
changed on the source); `clip.mov` became `clip.mov.mp4` (MIME type now from
the extension); unticked folders could not be ticked again; the adb client
re-read a header after reusing its buffer; Android's trash request refuses
non-media items; an accepted trash request silently skipped `clip.mov` (a `.mov`
MediaStore types `video/mp4`) while graphite counted it as trashed. Removal now
checks each item's `IS_TRASHED` afterwards and trashes approved items Android
skipped directly; **that fallback is not yet verified on device**.

Replugging the cable for the setup handed USB control back to the old phone
twice. Apps can't change the USB role (it needs the system permission
`MANAGE_USB`), so graphite now detects it and names the exact taps ("USB
controlled by" → "This device" on the new phone); that wording is not yet
tested by a user.

Known issue: **videos lose `DATE_TAKEN`** with either stripping choice. The
stripper keeps the `mvhd` times, but Android's scanner gives a stripped MP4 no
date (checked on both phones with the same file before and after stripping).
Tracked with `strip/`.

Open question: **does wireless debugging on the old phone break the cable
install?** The install that worked had two changes at once: the fix for the
adb client's buffer bug, and wireless debugging turned off on the Pixel 7 Pro.
Next time the cable install is tested, run it once with wireless adb connected
to the old phone, using the fixed build. If it fails, graphite should detect
that and tell the user to turn wireless debugging off; if it passes, the buffer
bug was the whole cause. Until then, disconnect wireless adb from the old phone
(`adb disconnect <serial>`) before a cable-install test.

Not verified on device:

- Motion photos, camera HEIC and DNG from real cameras (only `strip/`'s
  synthetic fixtures); the RAW "copy as they are or skip" question.
- "Keep all metadata" and "Remove all but what display needs" end to end.
- `Android/media/` opt-in, running out of space mid-copy, the battery
  refusal, renamed (unsafe) names, resume after pulling the cable mid-copy.
- A large real library (speed and hashing time at scale).
