# graphite-core-ffi

A thin [uniffi](https://mozilla.github.io/uniffi-rs/) 0.32 wrapper that gives the
Android app [`core/`](../core) and [`strip/`](../strip) in Kotlin. Every type mirrors one in the core
and every method forwards to it; the core's API is unchanged. Objects hold
their core value behind a mutex, because uniffi hands out shared references.

The Kotlin package is `io.github.storskegg.graphite.ffi`, and the library is
`libgraphite_ffi.so` ([uniffi.toml](uniffi.toml)).

## Building

The app's Gradle build does this for you (the `rustCore` task in
[android/app/build.gradle.kts](../android/app/build.gradle.kts)): it builds
for `arm64-v8a`, API 30, with [cargo-ndk](https://github.com/bbqsrc/cargo-ndk),
then generates the bindings from the built library. To do it by hand:

```bash
cargo ndk -t arm64-v8a -P 30 -o out build --release --locked
```

```bash
cargo run --features cli --bin uniffi-bindgen -- generate --library out/arm64-v8a/libgraphite_ffi.so --language kotlin --out-dir kotlin
```

On the host:

```bash
cargo clippy --all-targets -- -D warnings
```

## Dependencies

`graphite-core`, `graphite-strip`, `uniffi` (pinned to 0.32.2) and
[memmap2](https://crates.io/crates/memmap2) 0.9, which memory-maps files so a
video is never read into memory whole. memmap2's only dependency is `libc`.

## API

### Core

The same as the core's (see [core/README.md](../core/README.md)):
`TargetPairing`, `SourcePairing`, `Session`, `FileHasher`, the manifest, plan
and receipt records, and the free functions `plan_totals`,
`plan_check_request`, `manifest_hash`, `plan_hash`, `blake3_hash`,
`chunk_size` and `chunk_overhead`. Errors come out as `CoreError`, with one
variant for each core error.

### Stripper

Works on files rather than byte arrays (see [strip/README.md](../strip/README.md)
for what each choice removes):

- `open_file(path)` opens a file to send unchanged; `strip_file(path, mime,
  choice)` opens it with the chosen metadata removed. Files that aren't photos or
  videos are sent unchanged in every choice. Both return a `StrippedFile`.
- `StrippedFile`: `original()` and `sent()` give the size and BLAKE3 hash of the
  file on disk and of the bytes that will be sent, both computed on open.
  `stripped()` says whether they differ, and `read(offset, max)` streams the
  sent bytes (it returns an empty array at the end).
- `strip_applies_to(mime)` says whether the stripper handles a MIME type;
  `stripper_version()` is the version that goes in the manifest.
- Errors come out as `StripError`: `Raw`, `Malformed`, `Unsupported` or `Io`.
