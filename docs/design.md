# Design

## Goal

Copy everything an app can reach on a stock Android phone (the **source**) to a
GrapheneOS phone (the **target**), with nothing readable or alterable in transit or
at rest anywhere in between, and without duplicating anything already on the target.
Where something cannot be copied, say so and guide the user through its own export.

First devices: Pixel 7 Pro (stock Android 17) to Pixel 10 Pro XL (GrapheneOS).

## Roles

One APK. On launch the user picks **This is my old phone** (source) or
**This is my new phone** (target).

## Transports

Wired only. No Wi-Fi, for setup or for transfer.

1. **Phone to phone (default).** USB-C to USB-C. The phone acting as USB host puts
   the other into Android Open Accessory (AOA) mode and the encrypted stream runs
   over its bulk endpoints. AOA needs no developer settings. **The target is the
   USB host and the source is the accessory**: accessory to host is the fast
   direction (110-140 MB/s over USB 3, 33-36 MB/s over USB 2; the other way is
   2.5-3.5 times slower). The app recommends a USB 3 cable and shows the link
   speed it got. Measured in the [spike](spike.md#1-aoa-throughput).
2. **Through a laptop (fallback).** Both phones plug into the laptop;
   `adb forward` / `adb reverse` splice the two apps together. The laptop relays,
   and may briefly cache, ciphertext only.

Both carry the same framed, encrypted stream; only the byte pipe differs. The
framing starts each session with a handshake and discards anything before it:
bytes from an earlier session can still be waiting in the AOA pipe. A read of
zero bytes on the host is the accessory's end-of-write packet, not an error.

### Host role

Which phone comes up as USB host depends on the cable and plug order. Each app
reads its real role from `UsbManager` (an attached device means host, an
attached accessory means accessory), never from the "USB controlled by" label in
Settings, which can name the wrong phone. If the source is host, the app asks the
user to switch "USB controlled by" and waits until the roles actually change
(1-3 s). Only the data role moves: the phone that supplies power keeps doing so.
Once the transfer starts, the app asks the user not to touch USB settings: a
role change ends the session, and the transfer resumes from where it stopped.

### Staying awake and unlocked

Both apps keep the screen on (`FLAG_KEEP_SCREEN_ON`) for pairing and the whole
transfer, so neither phone times out or locks, and hold a wake lock in a
foreground service in case the user leaves the app. This is required, not a
convenience: a locked GrapheneOS phone turns its USB-C port charging-only and
refuses new USB devices, and the host phone is powering the other, so it is not
charging and "stay awake while charging" does not apply. If a phone locks
anyway, the app says why the transfer paused and resumes after unlock.

### adb-level privileges

Some steps need what the adb shell can do (`pm install`, `pm grant`, `appops`,
`cmd role`, `settings`). Over the phone-to-phone cable the host phone acts as an
adb client to the other phone, when that phone has USB debugging on: in normal
mode or in AOA's accessory + adb mode, next to the data stream. The app carries a
small adb client (RSA auth, shell and exec streams), and the user taps "Allow USB
debugging" once on the other phone. Swapping the host role (above) lets each
phone configure the other, so the laptop is not needed for setup. Proven in the
[spike](spike.md#2-adb-client-over-the-cable): shell, `cmd package install` and
`pm grant` both ways, GrapheneOS included.

Installing graphite on the other phone through that shell (`cmd package install`)
exempts it from the SMS and call-log hard restrictions, and on stock it is not
subject to developer verification (shell installs opt out; verification was not
enforced on stock at the time of the spike).

At the end the app revokes debugging authorizations and turns USB debugging off.

On GrapheneOS the USB-C port setting must allow data; the app checks and says so.

## Pairing

Pairing authenticates the cable link. A cable alone proves nothing.

1. **NFC tap (default).** The target emulates a card (host card emulation, private
   AID, preferred service while the app is in front); the source reads it. Each side
   sends a hash of its ephemeral X-Wing and ML-DSA public keys and confirms what it
   received. No secret crosses NFC, so a passive listener learns nothing useful.
2. **QR code.** The target shows: protocol version, session ID, hash of its
   ephemeral public keys, a 256-bit one-time secret. The source scans it. The secret
   becomes the HPKE pre-shared key, proving to the target that the peer scanned it.
3. **6-digit comparison.** Commit-then-reveal: the source sends a hash of its key
   share, the target sends its share, the source reveals. Both screens show 6 digits
   derived from the transcript hash; the user confirms they match on both. One chance
   in 10^6 for a man-in-the-middle per attempt, nothing to attack offline.

Afterwards the target asks the user to accept the named source before anything flows.

## Capability inventory

Right after pairing, inside the encrypted channel, each phone sends what it can do:

- the link: its USB role, the link speed, whether the other phone's adb is reachable;
- the app's grants: permissions, roles (SMS), all-files access, install source;
- the data: which categories it can read (source) or write (target), with counts
  and sizes where they are cheap to get.

The target combines both into one checklist. Everything that can move is ticked;
the user unticks what they do not want. What cannot move is listed with the
reason and the fix, for example "Call log: allow call-log access on the old
phone" or "SMS: graphite needs to be the SMS app for a moment". Sensitive
categories still need their own permission prompts, so nothing is copied without
the user having seen it.

## Cryptography

| Layer | Choice |
|---|---|
| Key exchange | HPKE (RFC 9180) with X-Wing (X25519 + ML-KEM-768); PSK mode for QR pairing |
| Session keys | One key per direction, derived from the HPKE export; key confirmation both ways |
| Bulk data | XChaCha20-Poly1305, 1 MiB chunks, nonce from chunk counter, last-chunk flag authenticated |
| Signatures | ML-DSA-65 (FIPS 204), per-session keys bound by pairing; source signs the manifest, target signs a receipt |
| Content identity | BLAKE3 of each file's bytes (see [media.md](media.md)) |
| Passphrases | None in a live transfer. Argon2id (RFC 9106) only for an optional encrypted export file |

The 6-digit code is never a key. A 20-bit secret run through a KDF is still 20 bits
and falls to an offline search; it is only used as an online comparison.

Implementation: a Rust core (libcrux for ML-KEM and ML-DSA; RustCrypto for X25519,
XChaCha20-Poly1305, BLAKE3, Argon2), wrapped for Kotlin with uniffi. HPKE with X-Wing
is checked against the draft's test vectors.

## Threat model

Protected against:

- Eavesdropping or tampering on the cable or in the laptop relay.
- A man-in-the-middle at pairing (bounded by the pairing method above).
- Theft or leak of anything cached on the laptop: it never holds a key.
- A future quantum computer recording traffic now (hybrid post-quantum key exchange).

Not protected against:

- A compromised laptop in the laptop route: with adb it has a shell on both phones
  and can read plaintext directly.
- A compromised source or target phone.
- An active NFC relay with equipment within centimetres of both phones.

## What can and cannot move

| Data | Possible | Note |
|---|---|---|
| Photos, videos, screenshots, downloads, documents, audio | Yes | Step 1, [media.md](media.md) |
| Contacts, local calendars | Yes | Later step; account-backed ones resync instead |
| SMS/MMS | Yes | Target app briefly holds the SMS role. RCS cannot be read |
| Call log | Yes | Hard-restricted permission; needs an adb-style install |
| App APKs | Partly | Store reinstall list preferred; Play Integrity apps may refuse to run |
| App private data | No | Needs root. Guided per-app exports instead |
| Saved Wi-Fi passwords | No | Share each network by QR code |
| Settings, wallpaper | A little | Via `settings` and wallpaper access |
| eSIM | No | Carrier-mediated |
