# Step 1: media

Copy photos, videos, screenshots, downloads, documents and audio from the source to
the target. Never create a duplicate. Running the same transfer twice copies nothing
the second time.

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
all three choices so the gallery order survives. Videos' `DATE_TAKEN` is not
tested yet.

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

## How the target decides

The target never sends its inventory to the source. The source sends a manifest; the
target alone decides what to request.

1. **Source builds the manifest.** A header with the metadata choice and the
   stripper version. For each file: relative path, MIME type, `DATE_TAKEN`,
   modification time, and size and BLAKE3 hash both of the original and of the
   bytes to be sent. The manifest is signed with the session's ML-DSA key and sent
   inside the encrypted channel.
2. **Target narrows by size.** It lists its own MediaStore (including trash) and
   groups files by size. Only target files whose size matches either size of a
   manifest entry are hashed. Most files are ruled out without reading them.
3. **Target hashes the candidates**, using its hash cache (below), and compares
   each against both hashes.
4. **Target requests** every manifest entry it holds under neither hash, once per
   sent hash.
5. **Target writes the file, then checks the hash, then publishes it.** MediaStore
   entry inserted with `IS_PENDING = 1` into the same relative folder; bytes
   streamed in; `fsync`; BLAKE3 recomputed and compared to the manifest's sent hash; the
   source's modification time set on the file (`File.setLastModified`, which works
   on the app's own pending file); only then `IS_PENDING = 0`. A mismatch deletes
   the pending entry and retries once.

   The publish scan sets `DATE_MODIFIED` from that modification time. The app
   cannot write `DATE_TAKEN`: MediaStore overwrites it at publish and ignores later
   updates. The scanner derives it from the file's EXIF (and from the modification
   time when EXIF has no time-zone offset), so identical bytes plus the restored
   modification time give the same `DATE_TAKEN` as on the source. The manifest's
   `DATE_TAKEN` is compared after publishing; an item whose source value came from
   the app that saved it, not from its bytes, ends up with none, galleries sort it
   by modification time, and the receipt notes it. See
   [spike.md](spike.md#5-mediastore-dates).
6. **Target signs a receipt** listing what was copied, skipped as already present,
   and failed, and sends it back. Both phones show the same summary.

## Hash cache

The target keeps a small SQLite table: MediaStore ID, size, modification time,
BLAKE3. An entry is reused only while ID, size and modification time all still match;
otherwise the file is hashed again. The cache only saves time: a missing or stale
cache never changes the outcome, only how long step 3 takes.

## Interruptions

If the cable comes out or an app is killed, nothing half-written is ever visible:
unfinished items are still pending. On the next run the target deletes its own
leftover pending entries and the manifest comparison starts over, so finished files
are skipped and the rest are copied.

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
