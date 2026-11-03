# graphite-core

The Rust crypto core: pairing, session keys, the chunked encrypted stream,
signed manifests and receipts, and file hashing. Synchronous, no I/O, a small
API meant to be wrapped for Kotlin with uniffi later. The spec is
[docs/design.md](../docs/design.md) (Pairing, Cryptography) and
[docs/media.md](../docs/media.md) (the manifest).

```bash
cargo test
```

```bash
cargo clippy --all-targets -- -D warnings
```

For Android, `blake3` compiles C for NEON, so point `cc` at the NDK, for example
`CC_aarch64_linux_android=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android29-clang cargo check --target aarch64-linux-android`.

## API

- `TargetPairing` (new phone) and `SourcePairing` (old phone): `new(method)`,
  then `handle(message) -> reply`, until `finish() -> Session`. The target's
  `hello()` is the first message: the NFC card's answer, the QR code's content,
  or the first message on the cable. For the code method, `code()` gives the
  six digits and `confirm_code()` records that the user saw them match;
  `finish()` refuses until then.
- `Session`: `seal(chunk, last)` / `open(chunk)` / `finish_receiving()` for the
  stream; `sign_manifest` / `verify_manifest` (source signs, target verifies);
  `sign_receipt` / `verify_receipt` (target signs, source verifies).
- `FileHasher`: streaming BLAKE3.

Every error is final for the pairing or stream that raised it.

## Protocol

Six messages for every method; only the channel of the first two differs:

| # | Message | NFC | QR | Code |
|---|---|---|---|---|
| 1 | Target hello: version, suite, method, session ID, `H(share_T)`, QR secret | NFC card | QR code | cable (no hash) |
| 2 | Source commit: session ID, `H(share_S)` | NFC reply | cable | cable |
| 3 | Target share | cable | cable | cable |
| 4 | Source share, `enc_S` | cable | cable | cable |
| 5 | Target `enc_T`, confirmation | cable | cable | cable |
| 6 | Source confirmation | cable | cable | cable |

A key share is an ephemeral X-Wing public key, an ephemeral ML-DSA-65
verification key and a nonce. `H` is a BLAKE3 commitment over role, session ID
and share.

- **Both sides encapsulate.** The source runs HPKE to the target's X-Wing key,
  the target to the source's. Anyone can encapsulate to a public key, so with a
  single encapsulation the target would have no proof of who it is talking to;
  with two, each side's keys depend on a secret only the genuine owner of the
  peer's authenticated share can recover.
- **QR**: the 256-bit secret is the HPKE PSK in both directions (PSK mode); the
  target never sends it over the cable.
- **Code**: the source commits before seeing the target's share, so no one can
  pick a share to steer the code. The six digits come from the transcript hash
  and are only compared by the user; they never feed a key.
- **Transcript**: BLAKE3 over the protocol version, the suite description,
  the hello (without the QR secret), both shares and both encapsulations. HPKE
  `info` carries the transcript hash up to the shares. The session keys come
  from both HPKE exports and the full transcript hash: one stream key per
  direction and one confirmation key per direction.
- **Stream**: XChaCha20-Poly1305, at most 1 MiB per chunk. The nonce is
  `"g" | sender | zeros | counter | last flag` and is never sent; the flag
  byte travels in front of the ciphertext and is authenticated through the
  nonce.
- **Signatures**: ML-DSA-65, hedged, over `transcript hash || document`, with
  FIPS 204 context strings `graphite/1 manifest` and `graphite/1 receipt`. A
  receipt carries the hash of the signed manifest it answers. Paths must be
  relative with no `.`, `..` or empty components; under **Keep all metadata**
  sent and original must be the same.

## What the tests cover

Official test vectors, in `tests/vectors/`:

| Vectors | Source | What they check |
|---|---|---|
| RFC 9180, DHKEM(X25519) suites, base and PSK modes (16 vectors) | cfrg/draft-irtf-cfrg-hpke `test-vectors.json` @ b1f7cb0, filtered | hpke-rs's key schedule, both modes, seal/open, export, through our HPKE wrapper |
| draft-ietf-hpke-pq, both X-Wing suites (2 vectors, base mode) | hpkewg/hpke-pq `test-vectors.json` @ 6433c8f | full HPKE with X-Wing, including graphite's suite (0x647a, 0x0001, 0x0003), through our wrapper |
| X-Wing KEM (3 vectors) | dconnolly/draft-connolly-cfrg-xwing-kem `spec/test-vectors.json` @ 984c2f7 | key generation from seed, encapsulation, decapsulation |
| ML-DSA-65 keyGen (25) and sigVer, external pure interface (15) | usnistgov/ACVP-Server @ 975de31e, filtered | key generation from seed; verification with context strings, including invalid signatures |
| XChaCha20-Poly1305 (1) | draft-irtf-cfrg-xchacha-03, A.3.1 | the AEAD the stream uses |
| BLAKE3 (35) | BLAKE3-team/BLAKE3 `test_vectors/test_vectors.json` @ f55849f | `FileHasher`, fed whole and in pieces of 1 to 65536 bytes |

Our own tests only (no official vectors exist for these):

- HPKE PSK mode **with X-Wing**: the official vectors cover PSK mode only with
  DHKEM(X25519); X-Wing vectors are base mode. The key schedule code is the
  same for both KEMs.
- The pairing protocol: all three methods end to end; a cable
  man-in-the-middle caught by the NFC hashes (both directions) and by the QR
  hash; a source without the QR secret failing key confirmation; a code
  man-in-the-middle showing different codes; a source unable to change its
  share after committing; tampered encapsulation and confirmation tags;
  reflected confirmation; wrong method, version, suite, session; malformed and
  out-of-order messages; code confirmation required to finish.
- The stream: truncation (missing last chunk, cut chunk, short chunk), the
  last flag cleared or set, reordering, replay, cross-direction replay, replay
  from another session, tampering, data after the last chunk, the 1 MiB limit,
  and that a failed stream stays failed.
- Manifest and receipt signing: round trips, any changed byte, another
  session's signature, each role signing only its own document, domain
  separation between the two, a receipt for another manifest, unsafe paths.

## Crates

Direct dependencies (versions as locked in `Cargo.lock`):

| Crate | Version | Why |
|---|---|---|
| `hpke-rs` | 0.8.0 | RFC 9180 HPKE from Cryspen, the libcrux authors; supports X-Wing (codepoint 0x647a) per draft-ietf-hpke-pq. Features `libcrux` (backend with system-seeded PRNG) and `draft-ietf-hpke-pq` (the X-Wing/ML-KEM KEM definitions the vectors are for). Pulls in `hpke-rs-libcrux` 0.8.0, which brings `libcrux-kem` 0.0.10 and `libcrux-ml-kem` 0.0.11 (formally verified ML-KEM) and libcrux's X25519, HKDF, ChaCha20-Poly1305 and SHA-3 |
| `hpke-rs-crypto` | 0.8.0 | hpke-rs's provider trait; needed for the RNG trait bound and the KEM calls in the vector tests |
| `libcrux-ml-dsa` | 0.0.11 | ML-DSA-65 (FIPS 204), libcrux, only the `mldsa65` feature |
| `chacha20poly1305` | 0.11.0 | RustCrypto XChaCha20-Poly1305 for the stream; `zeroize` wipes the key on drop |
| `blake3` | 1.8.7 | the official BLAKE3 crate: content hashes, transcript, commitments, key derivation, confirmation tags; `zeroize` feature |
| `getrandom` | 0.4.3 | system randomness for session IDs, nonces, the QR secret and ML-DSA seeds; already in the tree through `rand` |
| `subtle` | 2.6.1 | constant-time comparison of commitments, confirmation tags and manifest hashes |
| `zeroize` | 1.9.1 | wiping secrets: derived keys, the QR secret, ML-DSA signing keys, HPKE exports |

Dev-dependencies: `serde_json` 1.0 (reading the vector files) and `hpke-rs`
again with `hazmat`, to compare derived private keys with the vectors.

### Deviation from the design

The design says RustCrypto for X25519. X-Wing's X25519 here comes from libcrux
(HACL*-derived, formally verified), inside `libcrux-kem`, because hpke-rs's
libcrux backend implements X-Wing as one unit. The alternatives were the
RustCrypto `x-wing` crate (which would use RustCrypto's ML-KEM, not libcrux's)
or assembling X-Wing ourselves from `libcrux-ml-kem` and `x25519-dalek`,
which is new crypto code where a maintained, vector-tested combination
exists. The rest of the RustCrypto choices stand.

The libcrux backend also links `libcrux-p256`, `libcrux-p384`, AES-GCM and
HMAC-DRBG code that graphite never calls; hpke-rs-libcrux has no features to
leave them out.
