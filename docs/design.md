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
   over its bulk endpoints. AOA needs no developer settings.
2. **Through a laptop (fallback).** Both phones plug into the laptop;
   `adb forward` / `adb reverse` splice the two apps together. The laptop relays,
   and may briefly cache, ciphertext only.

Both carry the same framed, encrypted stream; only the byte pipe differs.

### adb-level privileges

Some steps need what the adb shell can do (`pm install`, `pm grant`, `appops`,
`cmd role`, `settings`). Over the phone-to-phone cable the host phone acts as an
adb client to the other phone, using AOA's accessory + adb mode, when that phone
has USB debugging on. Android's "USB controlled by" toggle swaps the host role so
each phone can configure the other. If that proves unreliable, the laptop does
this one-time setup. See [spike.md](spike.md).

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
