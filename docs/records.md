# Step 2: messages, calls, contacts, blocked numbers, attestation

Step 1 ([media.md](media.md)) copies files. Step 2 copies the records Android keeps in
its providers: SMS and MMS, the call log, contacts and blocked numbers. It also adds
the attestation check that [design.md](design.md#roles) requires before the source
sends anything.

## What is in scope

| Data | Read on the source | Written on the target | Needs |
|---|---|---|---|
| SMS | `content://sms` | `content://sms` | `READ_SMS` on the source; the **SMS role** on the target |
| MMS | `content://mms`, its `addr` and `part` rows | the same | as SMS |
| Call log | `CallLog.Calls` | `CallLog.Calls` | `READ_CALL_LOG` / `WRITE_CALL_LOG` (hard-restricted: an adb-style install) |
| Contacts | raw contacts and their data rows, every account | one new raw contact each, in the phone's own (local) account | `READ_CONTACTS` / `WRITE_CONTACTS` |
| Blocked numbers | `content://com.android.blockednumber/blocked` | the same | the SMS role on **both** phones (see below) |

Not in scope: RCS (no app can read it), spam marks (kept privately by Google's apps,
[spike.md](spike.md#7-blocked-numbers-and-spam-marks)), voicemail, calendars (a later
step), contact groups and photos stored only in the cloud.

Contacts from accounts (Google, Exchange) are copied as contacts stored on the new
phone: they stop syncing with that account. Someone who signs in to the same account
on the new phone gets the account's copy too, and Android joins the two into one
contact when name and number match. The checklist says so next to the count.

## Where records fit

**Records go in the same transfer as the files: one manifest, one plan and one receipt,
signed as before.** The manifest gains a second list, `records`. Plan and receipt
entries refer to manifest entries by index, and records are numbered after the files:
entry `i` is `files[i]` when `i < files.len()`, else `records[i - files.len()]`. So the
plan's statuses, the receipt's statuses, the source's refusal of anything not *to
copy* and the rule that every entry appears once all work unchanged. No new document
type, context string or key is needed.

A record entry in the manifest:

| Field | Meaning |
|---|---|
| `kind` | SMS, MMS, call, contact, blocked number |
| `label` | What the screens show, `/`-separated so the treemap can group it: `Messages/+15550100/2020-01-02 09:04`, `Calls/+15550100/…`, `Contacts/Jane Doe`, `Blocked numbers/+15550100` |
| `date_ms` | When the message was sent or the call made; none for contacts and blocked numbers. Records are written in this order |
| `identity` | BLAKE3 of the record's identity fields (below): two records with the same identity are the same item |
| `sent` | Size and BLAKE3 of the record's **payload**, the bytes that are sent |

The payload is the record's rows in a canonical encoding: for each row, its table and
its columns sorted by name, each a null, an integer, a string or bytes. MMS part
data goes in as bytes, so an MMS image is inside its payload. A payload is requested
and streamed like a file (`REQUEST`, `FILE_BEGIN`, `FILE_DATA`, `FILE_END`); the
target checks its hash against `sent`, and that the rows give the manifest's
identity, before writing anything. The source keeps each payload from the manifest
in memory and sends that, except an MMS, which it reads again (its pictures can be
large) and sends only if it still hashes the same; otherwise it is *changed on the
old phone*.

A contact is read as the phone shows it, joined across accounts: the data rows of
all its raw contacts, without duplicates, of the standard kinds (name, numbers,
emails, addresses, organisation, nickname, note, website, events, relations, IM,
SIP, photo). It is written as one new raw contact in the phone's own account. Its
photo is the thumbnail stored with the contact; a full-size photo kept as a
separate file is not carried. Group memberships are not carried either: groups
belong to accounts.

Only listed columns are carried. Provider IDs (`_id`, `thread_id`, `person`,
`raw_contact_id`), SIM subscription IDs and anything a provider computes
(`last_modified`, `geocoded_location`, contact aggregation) are left out: the new
phone has its own. A column the source has and the target's provider lacks (API levels
differ) is dropped when writing; the note says so.

## What counts as a duplicate

Files are identified by their bytes. Records cannot be: a provider rewrites some
columns on insert (`thread_id`, `last_modified`), and a second copy of a contact
synced from an account has different IDs. So **each kind has an identity, a BLAKE3
over normalized fields, with the context `graphite/1 record identity` and the kind
first.** A record is *present* on the target if any record of the same kind there
has the same identity.

Normalizing a number: drop everything but digits and a leading `+` ("+1 (555)
010-0" and "+15550100" are the same); a sender with no digits ("AMAZON") is kept
as written, lowercased. No country code is guessed: "5550100" and "+15550100" are
different numbers, so a message stored both ways would be copied. Both phones hold
what the same networks delivered, so in practice the forms agree.

| Kind | Identity fields |
|---|---|
| SMS | box (`type`: inbox, sent, draft, outbox, failed, queued), number, `date` in ms, body |
| MMS | box (`msg_box`), `date` in s, the addresses as sorted (type, number) pairs, subject, then for each part in `seq` order its content type and its text or the BLAKE3 of its data |
| Call | number, `date` in ms, `duration`, `type` (incoming, outgoing, missed, rejected, blocked…) |
| Contact | display name (whitespace collapsed, case-folded), the sorted set of its numbers, the sorted set of its email addresses (lowercased) |
| Blocked number | the number |

| Case | What happens |
|---|---|
| The same message, call, contact or blocked number already on the target | *Present*: not copied, verified with the rest |
| Two records with one identity on the source (a contact in two accounts, a message saved twice) | Copied once; the other follows it, as same-bytes files do |
| A contact edited on one phone (another number added) | Different identity, so copied: the target gets a second contact and Android may join them. Never merged or overwritten |
| A message that differs only in a column not in its identity (`read`) | The same message: not copied again |

Exact identity gives no false positives within a kind; two different messages are
never treated as one unless they have the same box, number, time to the millisecond
and body.

The target's identities are computed when it compares. For MMS, only target MMS with
the same `date` as a manifest MMS are read part by part; the rest are ruled out
without reading their parts, as files are by size.

## The transfer

The phases are those of [media.md](media.md#the-transfer-phase-by-phase). What changes:

1. **Inventory.** The source counts what it can read: messages, calls, contacts, and
   blocked numbers only after the source grants access (below).
2. **Choices.** The target's first screen ticks each category it can write, with
   its count and what it needs ("graphite becomes the SMS app for a minute while it
   writes messages"). Unticking a category leaves it out of the manifest.
3. **Manifest.** The source reads the ticked kinds and adds a record entry for each.
4. **Comparison and plan.** As for files. Reading the target's messages and
   blocked numbers already needs the SMS role, so the target takes it here, before
   the plan, when the manifest has any. The plan's totals count record payloads
   once per identity.
5. **Copy.** Records first, so the role goes back as soon as possible: contacts,
   calls, blocked numbers, then SMS and MMS in date order; several requests are in
   flight at once, since records are small. Then the files, as in step 1.
6. **Verification.** Records are verified right after their copy, inside the role
   window: each written record is read back from its provider by the URI the insert
   returned, its identity recomputed from what the provider stored, and compared
   with the manifest; a present record is found again by identity and checked the
   same way. A mismatch is *failed*. Then the target gives the role back, and the
   files are copied and verified as in step 1.
7. **Receipt.** Records get the same statuses as files; *present in trash* does not
   occur. The receipt's note lists columns the target could not store.
8. **Removal** from the source is offered for files only. graphite never deletes
   messages, calls or contacts.

A second run finds everything present and copies nothing (the idempotence rule of
[media.md](media.md#idempotence)). An interrupted run leaves whole records only: each
record is one insert (one `applyBatch` for a contact, and for an MMS its row, then
its addresses and parts). An MMS left without parts by a crash has a different
identity, so the next run copies it again, and the target deletes the incomplete one
it journalled.

## The SMS role

Only the default SMS app can write SMS and MMS, and on both phones it is the only
non-system way to read blocked numbers ([spike.md](spike.md#7-blocked-numbers-and-spam-marks)).
graphite qualifies for the role (it has the four components Android requires: an
SMS receiver, an MMS-notification receiver, a respond-via-message service and a
compose activity) and holds it only for the inserts.

**Taking it.** Android's "Set graphite as your default SMS app?" dialog, one tap
(`RoleManager.createRequestRoleIntent`). graphite first records which app held it
(`Telephony.Sms.getDefaultSmsPackage`). The new phone holds it from its comparison
(reading what it already has needs it too) through the plan, the user's start and
the records' copy and verification: usually a few minutes, most of it the user
reading the plan. The old phone holds it only while it reads blocked numbers.

**Giving it back.** An app cannot give the role to another app. The legacy
`ACTION_CHANGE_DEFAULT` intent is removed by Android for apps targeting API 29 and
up ("PermissionPolicyService: Action Removed", seen on the API 30 emulator on
5 November 2026). graphite therefore opens the previous app, which asks to be the
default again: Google Messages shows "Set default chat app" and then Android's own
dialog, two taps. If the previous app does not ask, graphite opens Settings, Default
apps, SMS app. graphite checks the role really went back and keeps asking until it
has. A record in graphite's preferences survives a crash, so the next start asks
first. Over an adb shell (`cmd role add-role-holder`) both directions need no tap;
that needs the other phone to run the shell, so it waits for the cable setup to
offer it.

**While graphite holds it.** An SMS that arrives is saved to the inbox, as any SMS
app would, and the previous app shows it once it has the role back. An MMS is not
downloaded (graphite has no MMS settings for the carrier); graphite counts any that
arrive and the last screen asks the user to have them sent again. RCS chats in
Google Messages may need to be switched on again afterwards (not tested; see the
phone test list).

**Blocked numbers on the source.** design.md planned to read them with the adb
shell from the target over the cable. That route sends them unencrypted over USB,
outside the paired channel, which the threat model rules out. graphite instead
takes the SMS role on the old phone for the read (a few seconds) and gives it back
the same way. The target writes them during its own role window.

## Attestation

[design.md](design.md#roles) has the source refuse a target that cannot prove it runs
GrapheneOS with a locked bootloader. What is built:

- **Target.** After pairing, the source sends `ATTEST`. The challenge is not sent: both
  phones compute it as BLAKE3 over `graphite/1 attestation` and the transcript hash, so
  it is bound to this session. The target creates a Keystore EC key with that
  challenge (StrongBox when there is one, else the TEE) and sends the certificate chain.
- **Source.** Checks, in order, and stops at the first failure, telling both phones
  why:
  1. the chain is well formed and each certificate is signed by the next;
  2. its root is one of Google's hardware attestation roots, shipped in the app (the
     RSA root of 2022 and the EC "Key Attestation CA1" root that signs since February
     2026; the older RSA roots too, since chains from them stay valid);
  3. no certificate's serial is on the revocation list; the app ships a snapshot of
     `https://android.googleapis.com/attestation/status` taken at build time (no
     network is used);
  4. the key description's challenge is this session's;
  5. the attestation and Keymaster security levels are TEE or StrongBox, not software;
  6. the root of trust says the bootloader is locked, the boot state is *self-signed*
     (an OS with its own key) and the verified-boot key is one of GrapheneOS's
     published fingerprints, which the app ships ([GrapheneOS's attestation
     compatibility guide](https://grapheneos.org/articles/attestation-compatibility-guide));
  7. the OS patch level is no older than the minimum the app ships: older is a warning
     on both screens, not a refusal.
- **Role.** Each phone also attests itself at start: a hardware chain whose boot key
  is a GrapheneOS key makes it a target. A phone with GrapheneOS's system apps and no
  matching key is refused ("this GrapheneOS device is not in graphite's list"),
  instead of being guessed. Without a hardware chain (an emulator) the role falls back
  to the unattested check and the screen says so.

On an emulator the chain is software-backed and its root is not Google's, so the check
correctly fails at step 2. The emulator test runs the refusal once (`attest`), and the
other scenarios use a debug-only switch that records the failure and goes on.
Release builds have no switch. The real check needs the GrapheneOS phone; see the
phone test list in [android/README.md](../android/README.md).

## Test data

Debug builds with a test scope (`test_scope`, as for media) read only records that are
clearly test data: numbers starting `+1555010` (the reserved fictional range) and
contacts whose name starts with `GT `. The emulator test seeds such records on the
source emulator; on real phones the same scope keeps a test run away from the user's
own messages.
