# graphite

Move your data from a stock Android phone to a GrapheneOS phone over a cable,
end-to-end encrypted with post-quantum cryptography, without creating duplicates.

Status: design; the [feasibility spike](docs/spike.md) passed on the first two
phones. Nothing to install yet.

- [Design](docs/design.md): roles, transports, pairing, cryptography, threat model
- [Step 1: media](docs/media.md): what is copied and how duplicates are avoided
- [Feasibility spike](docs/spike.md): what has to be proven on real devices first

## In short

- The same app runs on both phones; you pick "old phone" or "new phone".
- Phones are joined by a USB-C cable (Android Open Accessory), the new phone as
  USB host: over 110 MB/s with a USB 3 cable. A laptop running adb is the
  fallback. No Wi-Fi.
- Pairing is an NFC tap, with a QR code and then a 6-digit comparison as fallbacks.
- Keys come from hybrid X25519 + ML-KEM-768 (X-Wing) in HPKE; bulk data is
  chunked XChaCha20-Poly1305; manifests and receipts are signed with ML-DSA-65.
- Copying the same phone twice copies nothing the second time.
- Every transfer asks whether to keep photo and video metadata or remove location
  and identifiers (or everything not needed for display), losslessly, on the old phone.

## License

GPL-3.0-only. See [LICENSE](LICENSE).
