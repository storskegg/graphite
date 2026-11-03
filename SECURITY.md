# Security policy

graphite moves a whole phone's data, so a security bug matters more here than in
most apps. Thank you for reporting one.

## Reporting a vulnerability

Report it privately through GitHub:
[Report a vulnerability](https://github.com/storskegg/graphite/security/advisories/new).
Please don't open a public issue, pull request or discussion for it.

Include what you can:

- what an attacker can do, and what they need (physical access, a cable, a
  compromised laptop, a malicious app on one phone);
- the commit, the phones and their Android or GrapheneOS builds;
- steps or a proof of concept.

You'll get an answer within a week. Once a fix is on `main`, the advisory is
published with credit to you, unless you'd rather stay anonymous.

## Supported versions

graphite has no releases yet. Only `main` is supported.

## Scope

The design and its threat model are in [docs/design.md](docs/design.md) (User
authentication, Pairing, Cryptography, Threat model). In scope:

- anything that lets a third party read or change data on the cable or in the
  laptop relay, or get between the phones at pairing without the 6 digits
  differing;
- getting past user authentication on either phone;
- a source phone that copies, or removes, more than the signed plan and receipt
  allow;
- metadata the stripper should remove but leaves in
  ([docs/media.md](docs/media.md), Removing metadata);
- bugs in `core/`, `core-ffi/` or `strip/` that crash, hang or corrupt on
  hostile input.

Out of scope, as the threat model says: a compromised phone, a compromised
laptop in the laptop route, and a user who confirms the codes without looking
at both screens. `spike/` is a throwaway test app, not shipped.

## Status of the cryptography

The crypto core uses published constructions (HPKE with X-Wing, ML-DSA-65,
XChaCha20-Poly1305, BLAKE3) from reviewed libraries, and is tested against
their official vectors. graphite's own protocol on top of them has not had an
independent audit yet.
