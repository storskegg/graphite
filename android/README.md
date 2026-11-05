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
`-Pgraphite.x86_64=true` adds `x86_64` (with `rustup target add
x86_64-linux-android`), for emulators on x86 machines.

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

While copying and verifying, both phones show a treemap above the progress:
one square per file, sized by its bytes, grouped by folder (folder names where
they fit). Gray is waiting, yellow in progress, blue done or verified, red
failed. Teal is already on the new phone. Dark gray is not copied (unticked, in
the new phone's trash, did not fit). For verification the copied squares turn
gray again and go blue as each file reads back right. Tap a square to see its
file, size and state. The map stays on the last screens. Both phones lay it out
from the same manifest; during verification the old phone's colours come from
the new phone's progress messages (only the squares that changed), and the
signed receipt sets the final colours on both.

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
the cable setup passes it on to the old phone. Release builds ignore it, as
they ignore the other test extras:

| Extra | Effect |
|---|---|
| `test_role target` or `source` | Forces the role (an emulator is never GrapheneOS) |
| `test_tcp listen:PORT` or `HOST:PORT` | Links over a TCP socket instead of USB (`usb/TestTransport.kt`, in the debug source set only; release builds have a stub and no network permission) |
| `test_free_bytes N` | Pretends the new phone has N bytes free (`x` clears it) |

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
| AndroidX Test runner | 1.6.2 | Device tests only (`app/src/androidTest`), not in the APK |
| AndroidX Test JUnit | 1.2.1 | Device tests only: the JUnit 4 runner for them |

The app itself uses no AndroidX: the UI is plain Android views and the platform
BiometricPrompt. SQLite (the hash cache) and the Keystore are the platform's.
`android.useAndroidX=true` is only there for the test libraries.

## Tests

JVM unit tests (`app/src/test/`): frames and the link (handshake discarding
leftovers, windowing, lost and resent records, resume after EIO, a restarted
peer), comparison (including the no-duplicates rule both ways), preflight
maths, the journal, receipt mapping, the wire messages, progress and timing,
the cable setup's version check and the session loop. `core-ffi` has Rust tests
for the wrapper and the stripped-file reader (`cargo test` in `core-ffi/`).

### Emulator test

```bash
android/emulator-test.sh            # keep again strip display resume space battery removal
android/emulator-test.sh speed      # timing only: 400 small photos and 80 MB
```

One command runs the media flow end to end on two emulators: the target on
the latest image (API 37) and the source on API 30, the oldest graphite
supports. They link over the debug-only TCP transport (`test_tcp`, the target
listening behind `adb forward`), with the roles forced by `test_role` and the
scope limited to `graphite-test`. The script builds the debug APK, creates and
boots its own AVDs (`graphite-target`, `graphite-source`), sets a PIN for the
user-presence prompt and types it, seeds synthetic media from
`strip/tests/fixtures`, and drives both screens with `uiautomator`. It checks
receipts and MediaStore rows:

| Scenario | Checks |
|---|---|
| keep | "Keep all metadata": 9 copied and verified, the duplicate once; date taken and modification time as on the source; both last screens show the treemap with 9 done, and the old phone got the new phone's tiles mid-verification (screenshots in the log folder) |
| again | A second run copies nothing |
| strip | "Remove location and identifiers": photo and video dates kept |
| display | "Remove all but what display needs": no date taken left |
| resume | The source's connection is reset mid-copy (iptables on the rooted source); the link resumes and the copy verifies |
| space | Refused when the target looks full (`test_free_bytes`); the source shows why |
| battery | Refused at 5 %, goes on after the battery is back and "Check again" |
| removal | The source moves the verified files to its trash (it taps Android's own dialog, test-only) |

Real phones stay the test for AOA, the USB role, the cable install and
attestation. `KEEP=1` leaves the emulators running; `HEADLESS=0` shows them;
`GRAPHITE_APK=path` tests a given APK. Emulator quirks it works around: the API
37 target boots without Wi-Fi (the netsim forwarder hangs while another
emulator holds it), and the API 30 source runs permissive (its updated
MediaProvider module is denied its pass-through mount by the image's older
SELinux policy, so nothing would be scanned). In CI (`.github/workflows/android.yml`)
it runs on request only (`workflow_dispatch`), on x86_64 images with KVM; the
unit tests run on every push. It has not passed on a hosted runner yet: seven
runs on 4 November 2026 got progressively further (AVD location, the adb
server race, the data partition, slow system services, package visibility).
The last booted both emulators, installed and seeded, then lost the API 37
emulator's system services in the first scenario ("Can't find service:
package"), most likely its system server restarting under the runner's load.
Until it passes there, it is a local test: all 8 scenarios pass from a cold
boot on an M-series Mac (arm64 images).

### Device test

`app/src/androidTest/.../DateTakenTest.kt` records what MediaStore makes of
`DATE_TAKEN` for a copy graphite publishes, and that steering it through the
modification time after publishing works (see "File dates" in
[docs/media.md](../docs/media.md)). A cold-booted AVD left with the harness's
PIN stays locked and the app can't start: run `adb shell locksettings verify
--old 1234` first.

```bash
ANDROID_SERIAL=emulator-5580 ./gradlew :app:connectedDebugAndroidTest
```

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
MediaStore types `video/mp4`) while graphite counted it as trashed.

### Final pass (3 November 2026)

Same phones and cable, test files in `graphite-test` folders only (items 1-7),
then the real library (item 8, copy only).

| Item | Result |
|---|---|
| 1. Install over the cable with wireless adb connected to the old phone | Passed (1.5 s). The earlier failures were the adb client's buffer bug alone; wireless debugging can stay on |
| 2. Wording when the old phone has taken USB control | Shown after a forced role swap and followed through to pairing; no comments on the wording itself |
| 3. Removal of items Android's trash request skips | Passed. `clip.mov` and a `.3gp`, which MediaStore had typed `video/mp4`, were refused by Android 17 ("Moving files to .trash-storage directly") until graphite corrected their MIME type from the name; then 5 of 5 moved to the trash. The files themselves are not changed |
| 4. Videos keep `DATE_TAKEN` after stripping (stripper version 2) | Passed: `video.mp4` and `clip.mov` show 2020-01-02 on the target |
| 5. "Keep all metadata" and "Remove all but what display needs" | Passed. Display: AVIF and 3GP arrived with no date taken, the video's creation time zeroed, GPS and make/model gone |
| 6. Failures | Cable pull simulated by dropping the link mid-copy (a USB data-role swap): resumed, the 29 unacknowledged records resent, verified. Not enough space and low battery: refused with the right screens. A file renamed on the old phone: counted as already copied |
| 7. `Android/media` opt-in and a RAW file | Passed: the opt-in folder copied; the DNG skipped after its question (receipt: not copied) |
| 8. The real library, full scope, copy only | Passed, see below |

Found and fixed during the pass: progress moved only per file (now per chunk,
four times a second, with MB/s and time left); a resume stalled 2 minutes
waiting for the USB permission callback (now polled); "Keep all" after an
earlier stripping run saved originals next to the stripped copies as "(1)"
files (the no-duplicates rule now runs both ways, see
[docs/media.md](../docs/media.md)); setup offered to install graphite when it
was already there, just slow to answer (it now checks the installed version and
waits 30 s); Done on the new phone's last screen did nothing (a finished
session now goes back to the start, on both phones); verification showed no
progress for minutes (both phones now show it, the old phone from the new
phone's progress messages); the old phone's "nothing was removed" screen after
Keep them was one tap too many.

**The real library** (Pixel 7 Pro to Pixel 10 Pro XL, full scope, "Remove
location and identifiers"):

- 5,612 copied and verified, 27 already on the new phone, 23 not copied
  (unticked); 127 duplicates copied once. 56.7 GB.
- Copy 40 minutes (about 24 MB/s on average, 26 MB/s at the end, against
  75-83 MB/s with large test files), then verification about 8 minutes. No
  link drops, no retries; both phones at thermal status 0 afterwards.
- 8 photos' date taken differs from the old phone's: see "File dates" in
  [docs/media.md](../docs/media.md). The old phone's values were written by
  other apps (Android's restore app, a browser), not derived from the files.
  Since 5 November verification steers the new phone's scanner for such files
  (modification time := the old phone's date taken); not yet run on the phones.
- Nothing was removed from either phone.

Still to check on the phones (next phone session):

- The speed work: per-phase timing (`copy phases:` and `send phases:` in the
  log) on the same library, and whether requesting small files ahead helps there.
- Live verification progress and Done on both phones' last screens.
- The `DATE_TAKEN` steering on the 8 photos (`steer` lines in the log, and the
  receipt's notes).
- Running out of space mid-copy (only the up-front refusal was tested).
- Motion photos, camera HEIC and DNG from real cameras.
- Attestation (the role check is unattested; the seam is in `role/`).
