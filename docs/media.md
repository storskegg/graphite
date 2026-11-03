# Step 1: media

Copy photos, videos, screenshots, downloads, documents and audio from the source to
the target. Never create a duplicate. Running the same transfer twice copies nothing
the second time. Nothing is removed from the source unless both phones have verified
the result and the user then asks for it.

## What is in scope

Shared storage, read with all-files access on the source:

- `DCIM/` (camera, including motion photos and RAW+JPEG pairs, as plain files)
- `Pictures/` (screenshots, edits, app-saved images)
- `Movies/`, `Music/`, `Recordings/`, `Audiobooks/`, `Podcasts/`
- `Ringtones/`, `Alarms/`, `Notifications/`
- `Download/`, `Documents/`
- `Android/media/` (for example messaging apps' media): **opt-in**, off by default

Not in scope: `Android/data/`, `Android/obb/` (not readable), items in the source's
trash, and cloud-only items (for example Google Photos originals not on the device).

## Removing metadata

Photos and videos carry metadata that can identify the user: where a photo was
taken, which phone took it, serial numbers. Every transfer asks the user what to do
with it. Nothing is preselected; the transfer does not start until they choose.

### The question

It appears in the target's checklist (see [design.md](design.md#capability-inventory)),
with counts from the source's own library, for example "143 photos and 12 videos
contain a location". Three choices:

| Choice | Removes | Keeps |
|---|---|---|
| **Keep all metadata** | Nothing | Everything; files are copied byte for byte |
| **Remove location and identifiers** | Location; camera make, model and serial numbers; lens serial; owner and artist names; software; unique image IDs; maker notes; the embedded EXIF thumbnail | Dates and time-zone offsets, orientation, colour profile, exposure settings, motion photos, Ultra HDR, depth maps |
| **Remove all but what display needs** | All of the above, plus dates and exposure settings | Orientation, colour profile, motion photos, Ultra HDR, depth maps |

The embedded EXIF thumbnail is removed in both stripping choices because it can
still show an uncropped or unedited original.

The choice is sent from the target to the source inside the encrypted channel
and recorded in the signed manifest.

### Where and how

- **The source strips, before encryption.** Removed metadata never leaves the old
  phone, not even encrypted.
- **Lossless only.** Metadata is rewritten; image and video data is never
  re-encoded.
- **Deterministic and versioned.** The output is a pure function of the input bytes,
  the choice and the stripper version, and stripping twice gives the same bytes as
  stripping once. The version goes in the manifest. Duplicate detection depends on
  this (below).

### What has to stay

Some metadata is structure, not information about the user. Removing it breaks the
file:

- **Orientation.** Without it photos show sideways, and fixing that means
  re-encoding.
- **Motion photos.** The video follows the JPEG in the same file; XMP fields
  (`GCamera:MotionPhoto*`, `Container:Directory`) tell the gallery where it is.
- **Ultra HDR.** The gain map is a second image found through MPF and the `hdrgm`
  XMP fields.
- **Depth maps** (portrait blur), found through XMP.
- **Colour profile (ICC).** Without it colours shift.

Removing earlier segments can move later ones, so MPF offsets are recalculated.
Motion-photo offsets count from the end of the file and are unaffected; tests
check both.

### Dates

Android's media scanner sets `DATE_TAKEN` from EXIF `DateTimeOriginal` and
`OffsetTimeOriginal` (see [spike.md](spike.md#5-mediastore-dates)), so
**Remove location and identifiers** keeps both, and the gallery shows the same date
taken as on the old phone. **Remove all but what display needs** removes them, so
the target has no date taken; galleries sort those items by modification time,
which is still restored from the source. The user is told this under that choice.
The modification time is file-system data, not part of the file, and is kept in
all three choices so the gallery order survives. A video's `DATE_TAKEN` comes
from its `mvhd` creation time only, which the first two choices keep; **Remove all
but what display needs** zeroes it, so the same applies to videos.

### Formats

| Format | Where metadata is removed |
|---|---|
| JPEG | APP1 EXIF (IFDs rewritten; GPS IFD and IFD1 thumbnail dropped), APP1 XMP, APP13 IPTC, COM comments |
| HEIC, HEIF, AVIF | `Exif` and `mime` (XMP) items |
| PNG | `eXIf`, `tEXt`, `zTXt`, `iTXt` (XMP) chunks |
| WebP | `EXIF` and `XMP ` chunks (VP8X flags updated) |
| GIF | Comment and XMP application extensions |
| MP4, MOV, 3GP, M4A | Location (`©xyz`, `loci`, `com.apple.quicktime.location.*`) and make, model and software atoms. Each removed atom becomes a `free` atom of the same size, so no offsets move. **Remove all but what display needs** also zeroes the `mvhd`, `tkhd` and `mdhd` creation and modification times |

Documents (PDF, Office), other audio and anything else are copied unchanged in
every choice. Author fields in documents may be added later.

### When metadata cannot be removed

RAW (DNG) files are built around EXIF, and some files fail to parse. With either
stripping choice, these are never copied quietly. Before the copy starts, the user
gets one question per group: "12 RAW files can't have their metadata removed: copy
them as they are, or skip them?" Skipped files are listed in the receipt.

### Tests

Stripping is checked against a git-ignored corpus of real files from both phones,
with ExifTool as the reference reader. For each format and choice, the tests check:

- no removed tag is left;
- the decoded pixels are identical;
- motion photos still play and Ultra HDR is still detected;
- stripping twice gives the same bytes as stripping once.

## What counts as a duplicate

**Identity is the content: the BLAKE3 hash of the file's exact bytes.** Name, folder
and dates do not decide it.

When metadata is removed, the source file and the copy have different bytes. So the
manifest carries two hashes per file: **original** (the bytes on the source) and
**sent** (the bytes after stripping; the same as original under **Keep all
metadata**). A target file is the same item if its bytes match either one.

| Case | What happens |
|---|---|
| Same bytes already anywhere on the target | Skip. Not copied to a second folder |
| A copy graphite stripped earlier is on the target (matches **sent**) | Skip. Re-running a stripped transfer copies nothing |
| The original, with its metadata, is already on the target (matches **original**) but the user chose to strip | Skip, and the receipt says "already on the new phone, with its metadata". It is never quietly replaced |
| Same bytes at two paths on the source | Copy once. Prefer `DCIM/`, then `Pictures/`, then the shortest path |
| Two source files that differ only in removed metadata (same **sent** hash) | Copy once, as above |
| Same name and folder, different bytes | Copy, and let MediaStore give it a new name (`IMG_1234 (1).jpg`). Never overwrite |
| Same picture re-encoded, resized, or stripped by another tool | Different bytes, so it is copied. Visual near-duplicate detection is a possible later option that only flags items for the user; it never skips on its own |
| Same bytes in the target's trash | Counts as present. Something the user deleted on the new phone is not brought back |

Exact-bytes identity has no false positives: two different files are never treated
as one.

## The transfer, phase by phase

The manifest goes first, and nothing is copied until both phones have checked it.
The target never sends its inventory to the source: the source sends the manifest,
and the target alone decides what to request.

| Phase | What happens | Signed document |
|---|---|---|
| 1. Manifest | Source lists, hashes and (if chosen) strips in memory; sends the manifest | Manifest (source) |
| 2. Comparison | Target works out what it already has | |
| 3. Preflight | Space, battery, names and open questions checked; both phones show the plan; the user starts the copy | Plan (target) |
| 4. Copy | Requested files are written, checked and published one by one | |
| 5. Verification | Target re-reads everything the receipt will vouch for | Receipt (target) |
| 6. Removal (optional) | Only if the user asks, on the source, from a verified receipt | |

The plan is a third signed document next to the manifest and receipt. The core
needs a `graphite/1 plan` context string for it.

### 1. Manifest

A header with the metadata choice and the stripper version. For each file: relative
path, MIME type, `DATE_TAKEN`, `DATE_ADDED`, modification time, and size and BLAKE3 hash both of
the original and of the bytes to be sent. Signed with the session's ML-DSA key and
sent inside the encrypted channel.

### 2. Comparison

1. **Target narrows by size.** It lists its own MediaStore (including trash) and
   groups files by size. Only target files whose size matches either size of a
   manifest entry are hashed. Most files are ruled out without reading them.
2. **Target hashes the candidates**, using its hash cache (below), and compares
   each against both hashes.
3. **Each manifest entry gets a status:** *to copy* (held under neither hash, one
   per sent hash), *present* (held, not in trash) or *present in trash*. In
   preflight the user can untick entries *to copy*; they become *unticked*.

### 3. Preflight

Before anything is copied, the target checks, and both phones show the result:

- **Storage.** Bytes needed is the sum of the sent sizes of everything *to copy*,
  each sent hash counted once (same bytes at two paths are copied once).
  It must fit within `StorageManager.getAllocatableBytes` on the target (which
  already leaves Android's own reserve) with 1 GiB to spare. If it does not, the
  plan shows sizes by category and folder and the user unticks until it fits. The
  copy does not start until it fits.
- **Battery.** The USB host powers the other phone (see
  [spike.md](spike.md#3-swapping-the-host-role)). Both phones report charge and
  whether they are charging. From the measured link speed the app estimates the
  time. It warns when either phone is under 50 % and not charging for a long
  transfer, and will not start under 15 %.
- **Names.** Every path is checked against what the target's MediaStore accepts
  (components at most 255 bytes, no reserved names). Anything that fails is listed
  with the name it will get instead.
- **Open questions.** Files whose metadata cannot be removed (see above) are asked
  about here, once per group.
- **Time.** An estimate from the link speed measured during pairing.

The **plan** lists every entry with its status (*to copy*, *present*, *present in
trash* or *unticked*) and totals: bytes to copy, counted once per sent hash, and
the count for each status. Unticking happens before the target signs, so the plan
is the agreed contract for what the source sends: the source checks the
signature and that the plan answers this manifest, and during the copy refuses a
request for anything the plan does not have *to copy*. The receipt reports an
entry as not copied because unticked exactly when the plan has it *unticked*. Both
phones show the same summary: "1,204 to copy (38.2 GB), 3,511 already on the new
phone, 2 in the new phone's trash (not copied), about 6 minutes". The user starts
the copy on the target.

### 4. Copy

Files are copied in the source's `DATE_ADDED` order (see [File dates](#file-dates)).
For each file *to copy*:

1. MediaStore entry inserted with `IS_PENDING = 1` in the same relative folder.
2. The source re-reads the file, strips it if chosen, and checks that its hashes
   still match the manifest. A file that changed since the manifest is not sent;
   it is reported as *changed on the old phone* and copied on the next run.
3. Bytes streamed in; `fsync`; BLAKE3 recomputed and compared to the manifest's
   sent hash; a mismatch deletes the pending entry and retries once.
4. The source's modification time set on the file (`File.setLastModified`, which
   works on the app's own pending file); then `IS_PENDING = 0`.

The publish scan sets `DATE_MODIFIED` from that modification time. The app cannot
write `DATE_TAKEN`: MediaStore overwrites it at publish and ignores later updates.
The scanner derives it from a photo's EXIF (and from the modification time when
EXIF has no time-zone offset) and from a video's MP4 `mvhd` creation time, so
identical bytes plus the restored modification time give the same `DATE_TAKEN` as on the source. An item whose source value came
from the app that saved it, not from its bytes, ends up with none, and galleries
sort it by modification time. See [spike.md](spike.md#5-mediastore-dates).

If the target runs out of space anyway (another app filled it), the copy pauses and
asks; nothing half-written is published.

### 5. Verification

After the last file, the target re-reads every item the receipt will call
*verified*: everything copied in this run and everything *present*. It reads the
bytes through MediaStore, hashes them **without the cache**, and checks the entry is
published, not pending and not trashed. This catches a bad write, or a file the user
changed or deleted on the target during the transfer. It also compares `DATE_TAKEN`
with the manifest and notes any difference.

The **receipt** gives every manifest entry one status:

| Status | Meaning |
|---|---|
| Copied, verified | Written in this run and read back with the right hash |
| Present, verified | Was already on the target and read back with a matching hash |
| Present in trash | On the target, but in its trash; not copied, not verified |
| Not copied | With a reason: changed on the old phone, did not fit, metadata could not be removed and the user skipped it, failed twice, unticked |

The target signs it with the manifest's hash; the source checks both. Both phones
show the same summary.

### 6. Removal from the source (optional)

Only offered after a verified receipt, only on the source, and only if the user
asks. Nothing is preselected.

- **What can be removed:** entries the receipt marks *copied, verified* or *present,
  verified*, whose file on the source still hashes to the manifest's original
  hash. The source re-hashes each one just before removing it.
- **Never:** *present in trash* entries (the target's trash empties itself after
  30 days), *not copied* entries, and anything whose source file changed.
- **The user picks** by category and folder (for example photos and videos, but
  not downloads).
- **When metadata was removed** in the copy, the new phone's copies lack what the
  old phone's still have. Before removal the user is told, with counts: "1,204
  photos on the new phone have had their location removed; removing them here loses
  it for good."
- **To the trash, not deleted.** Photos, videos and audio are moved to the
  source's trash with `MediaStore.createTrashRequest`, which shows Android's own
  confirmation once for the batch. That request takes media items only, so
  documents, downloads and other files are trashed by setting `IS_TRASHED` on
  their MediaStore row, which all-files access allows; for those, graphite's own
  "Move to trash" step and the fresh authentication are the confirmation. Either
  way they can be restored for 30 days. Every file is re-hashed just before.
  graphite never deletes permanently; the user empties the trash when they are
  ready.
- **Only from this session's receipt.** A receipt from an earlier session is not
  enough: the target may have changed since. Running the transfer again gives a
  fresh receipt (nothing to copy, everything *present*), which can then be used for
  removal.

## File dates

Every copied file, media or not, keeps its original dates wherever Android lets an
app set them, so it sorts on the target the same way it did on the source. The
manifest carries each file's modification time and MediaStore `DATE_ADDED` and
`DATE_TAKEN`.

| Date | Where it sorts | On the target |
|---|---|---|
| Modification time (`mtime`, MediaStore `DATE_MODIFIED`) | File managers; galleries' fallback | **Restored** from the source before publishing (proven in the [spike](spike.md#5-mediastore-dates)) |
| `DATE_TAKEN` | Galleries | **The same**, because the scanner derives it from the bytes (EXIF, or a video's `mvhd` creation time) and the restored modification time (see the copy phase above). Not under **Remove all but what display needs**, which removes it on purpose |
| `DATE_ADDED` | "Recently added" views, some galleries | **Set by MediaProvider at insert; nothing can change it.** Apps cannot, and neither can the shell: MediaProvider drops `date_added` from a shell `content update` or `content insert` ([spike](spike.md#date_added-from-the-shell)). Files are copied in the source's `DATE_ADDED` order, so the relative order survives, at one-second resolution, though the values become the copy time |
| Access time (`atime`) | Nothing users see | Not restored |
| Change time and creation time (`ctime`, `btime`) | `ls -c`, `stat` | **Cannot be set** by any app or by the shell; the kernel sets them. Only root could, by changing the clock |

The receipt notes every file whose modification time or `DATE_TAKEN` did not come
out as on the source. It does not list `DATE_ADDED`, which never matches; it says
once that "recently added" on the new phone shows the transfer date.

## Idempotence

- A transfer is identified by the hash of its manifest. The target keeps a journal
  keyed by sent hash: *pending*, *published*, *verified*.
- **After an interruption** (cable out, app killed, phone locked), nothing
  half-written is visible: unfinished items are still pending. On the next run the
  target deletes its own leftover pending entries and the source builds a new
  manifest. The comparison skips what was published, and only the rest is copied.
- **After a finished transfer**, running it again copies nothing and verifies
  everything again.
- **Removal is idempotent** too: trashed files are out of scope for the manifest,
  so a later run neither copies them again nor offers to remove them twice.

## Hash cache

The target keeps a small SQLite table: MediaStore ID, size, modification time,
BLAKE3. An entry is reused only while ID, size and modification time all still match;
otherwise the file is hashed again. The cache only saves time in the comparison:
a missing or stale cache never changes the outcome, and verification never uses it.

## Speed notes

BLAKE3 on these phones runs well above cable speed, so hashing the source is bounded
by flash reads. The source hashes while it is building the manifest; a large library
may take minutes for that, so the UI shows progress for it separately from the copy.
With a stripping choice, the source strips in memory to compute the sent hash, then
strips again while sending, so the stripped files never touch its storage. Only
metadata is parsed, so this adds little to the reads.

## Later steps (not now)

Contacts and calendars get their own duplicate rules: there, identity cannot be exact
bytes, so it will be built from normalized fields (names, phone numbers in E.164,
email addresses). Designed when we get there.
