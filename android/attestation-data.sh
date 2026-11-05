#!/usr/bin/env bash
# Refreshes what the attestation check ships with (docs/records.md, "Attestation"), in
# app/src/main/assets/attestation/:
#   roots.json        Google's hardware attestation roots
#   status.json       Google's attestation revocation list
#   grapheneos.json   GrapheneOS's verified-boot key fingerprints, with its signature (.sig),
#                     checked here against GrapheneOS's signing key pinned below.
# Run it before a release, and review the diff.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/app/src/main/assets/attestation"
# From https://grapheneos.org/allowed_signers (5 November 2026). A change here needs a reason.
SIGNER='contact@grapheneos.org ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIIUg/m5CoP83b0rfSCzYSVA4cw4ir49io5GPoxbgxdJE'
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
get() { curl -fsS -m 60 -o "$TMP/$2" "$1"; }
get https://android.googleapis.com/attestation/root roots.json
get https://android.googleapis.com/attestation/status status.json
get https://grapheneos.org/attestation.json grapheneos.json
get https://grapheneos.org/attestation.json.sig grapheneos.json.sig
echo "$SIGNER" >"$TMP/allowed_signers"
ssh-keygen -Y verify -f "$TMP/allowed_signers" -I contact@grapheneos.org -n "attestation keys" \
    -s "$TMP/grapheneos.json.sig" <"$TMP/grapheneos.json"
mkdir -p "$OUT"
cp "$TMP"/roots.json "$TMP"/status.json "$TMP"/grapheneos.json "$TMP"/grapheneos.json.sig "$OUT/"
echo "updated $OUT"
