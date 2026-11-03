# Contributing

graphite is early: the design is settled and the first iteration (media) is being
built. Issues and pull requests are welcome. For a large change, open an issue
first so we can agree on the approach.

Security bugs go through [SECURITY.md](SECURITY.md), not public issues.

## Layout

| Path | What it is |
|---|---|
| [`docs/`](docs) | The design. Read [design.md](docs/design.md) first, then [media.md](docs/media.md) |
| [`core/`](core) | Rust crypto core: pairing, session, encrypted stream, signatures, hashing |
| [`core-ffi/`](core-ffi) | uniffi wrapper that gives the Android app the core in Kotlin |
| [`strip/`](strip) | Rust metadata stripper for photos and videos |
| [`android/`](android) | The app (Kotlin, minSdk 30) |
| [`spike/`](spike) | Throwaway feasibility app; its results are in [docs/spike.md](docs/spike.md) |

## Building and testing

Each Rust crate builds on its own. Before you push, run these in `core/`, `strip/` and `core-ffi/`:

```bash
cargo test
```

```bash
cargo clippy --all-targets -- -D warnings
```

`strip/`'s tests need [ExifTool](https://exiftool.org). They also use ffmpeg and
libheif's `heif-dec` when installed. CI runs the tests and clippy for `core/` and
`strip/` ([.github/workflows/rust.yml](.github/workflows/rust.yml)).

The app builds from `android/` with `./gradlew assembleDebug`. The Gradle build
compiles `core-ffi/` with [cargo-ndk](https://github.com/bbqsrc/cargo-ndk) and
generates the Kotlin bindings, so it needs Rust, cargo-ndk and the Android NDK.
[android/app/build.gradle.kts](android/app/build.gradle.kts) has the details.

## Real devices and real data

graphite runs on real phones, often with someone's real photos on them.

- Never delete, move or change media you did not create for the test. Mark test
  files clearly and remove only those.
- Never commit personal media. Private test files go in a git-ignored
  `private-fixtures/` folder; committed fixtures must be synthetic.
- Record the phones and builds with any on-device result.

## Changes

- Keep a change to one topic. If it changes behaviour, update the design doc it
  belongs to in the same change.
- Crypto changes need tests against published vectors where they exist, and a
  note in [docs/design.md](docs/design.md) (Cryptography).
- Commit messages: `Area: what changed`, then a body that says why and how it
  was checked. Areas so far are `Design`, `Core`, `Strip`, `App`, `Spike` and `Docs`.
- Add a line to [CHANGELOG.md](CHANGELOG.md) under Unreleased.

## License

graphite is GPL-3.0-only ([LICENSE](LICENSE)). By contributing you agree your
contribution is licensed the same way.

Everyone taking part follows the [code of conduct](CODE_OF_CONDUCT.md).
