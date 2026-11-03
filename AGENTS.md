# Notes for coding agents

Read [CONTRIBUTING.md](CONTRIBUTING.md) first: layout, build and test commands,
commit style. The design in [docs/](docs) is the spec. If code and design
disagree, raise it; don't quietly follow either one.

- Real phones carry the user's real data. Create clearly marked test files and
  delete only those. Never remove, move or rewrite anything else.
  [CONTRIBUTING.md](CONTRIBUTING.md) has the rules.
- Give the phones back as you found them: return the SMS role, revoke any grants you added,
  uninstall test apps, and restore any settings you changed (screen timeout, USB role).
- Don't weaken the cryptography or the threat model ([docs/design.md](docs/design.md))
  to make something work. Stop and ask.
- Keep the crates' APIs and READMEs in step: [core/](core/README.md),
  [core-ffi/](core-ffi/README.md), [strip/](strip/README.md).
- Record on-device results with the phones and builds, in the doc they belong to.
- Before pushing, run the tests and clippy for every crate you touched, and add a line to
  [CHANGELOG.md](CHANGELOG.md).
