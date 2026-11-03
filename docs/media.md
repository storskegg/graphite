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

## What counts as a duplicate

**Identity is the content: the BLAKE3 hash of the file's exact bytes.** Name, folder
and dates do not decide it.

| Case | What happens |
|---|---|
| Same bytes already anywhere on the target | Skip. Not copied to a second folder |
| Same bytes at two paths on the source | Copy once. Prefer `DCIM/`, then `Pictures/`, then the shortest path |
| Same name and folder, different bytes | Copy, and let MediaStore give it a new name (`IMG_1234 (1).jpg`). Never overwrite |
| Same picture re-encoded, resized or EXIF-stripped | Different bytes, so it is copied. Visual near-duplicate detection is a possible later option that only flags items for the user; it never skips on its own |
| Same bytes in the target's trash | Counts as present. Something the user deleted on the new phone is not brought back |

Exact-bytes identity has no false positives: two different files are never treated
as one.

## How the target decides

The target never sends its inventory to the source. The source sends a manifest; the
target alone decides what to request.

1. **Source builds the manifest.** For each file: relative path, size, MIME type,
   `DATE_TAKEN`, modification time, BLAKE3 hash. The manifest is signed with the
   session's ML-DSA key and sent inside the encrypted channel.
2. **Target narrows by size.** It lists its own MediaStore (including trash) and
   groups files by size. Only target files whose size matches a manifest entry are
   hashed. Most files are ruled out without reading them.
3. **Target hashes the candidates**, using its hash cache (below), and compares.
4. **Target requests** every manifest entry whose hash it does not hold, once per hash.
5. **Target writes the file, then checks the hash, then publishes it.** MediaStore
   entry inserted with `IS_PENDING = 1` into the same relative folder; bytes
   streamed in; `fsync`; BLAKE3 recomputed and compared to the manifest; only then
   `IS_PENDING = 0`, `DATE_TAKEN` and modification time restored. A mismatch deletes
   the pending entry and retries once.
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

## Later steps (not now)

Contacts and calendars get their own duplicate rules: there, identity cannot be exact
bytes, so it will be built from normalized fields (names, phone numbers in E.164,
email addresses). Designed when we get there.
