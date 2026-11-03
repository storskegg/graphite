# graphite

Move your data from an Android 11+ phone to a GrapheneOS phone over a cable,
end-to-end encrypted with post-quantum cryptography, without creating duplicates.

Status: building iteration 1 (media). The [feasibility spike](docs/spike.md)
passed on the first two phones, and the first tracer build pairs over the cable
and sends one encrypted file. Nothing to install yet.

- [Design](docs/design.md): roles, transports, pairing, cryptography, threat model
- [Step 1: media](docs/media.md): what is copied and how duplicates are avoided
- [Feasibility spike](docs/spike.md): what has to be proven on real devices first

## In short

- The same app runs on both phones and works out which is which: the new phone proves it runs GrapheneOS with hardware key attestation; the old phone can be any Android 11 or newer.
- Phones are joined by a USB-C cable (Android Open Accessory), the new phone as
  USB host: over 110 MB/s with a USB 3 cable. A laptop running adb is the
  fallback. No Wi-Fi.
- Pairing happens over the cable; you confirm the same 6 digits on both screens.
- Keys come from hybrid X25519 + ML-KEM-768 (X-Wing) in HPKE; bulk data is
  chunked XChaCha20-Poly1305; manifests and receipts are signed with ML-DSA-65.
- Copying the same phone twice copies nothing the second time.
- Every transfer asks whether to keep photo and video metadata or remove location
  and identifiers (or everything not needed for display), losslessly, on the old phone.

## Repository

| Path | What it is |
|---|---|
| [`docs/`](docs) | The design |
| [`core/`](core) | Rust crypto core |
| [`core-ffi/`](core-ffi) | uniffi wrapper for Kotlin |
| [`strip/`](strip) | Rust metadata stripper |
| [`android/`](android) | The app |
| [`spike/`](spike) | Throwaway feasibility app |

[Contributing](CONTRIBUTING.md) · [Security policy](SECURITY.md) ·
[Code of conduct](CODE_OF_CONDUCT.md) · [Changelog](CHANGELOG.md)

## License

GPL-3.0-only. See [LICENSE](LICENSE).
