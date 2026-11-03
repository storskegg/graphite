# Feasibility spike

Each of these decides part of the design. They were proven on the Pixel 7 Pro
(stock Android 17, CP3A.261005.002.A1) and the Pixel 10 Pro XL (GrapheneOS,
Android 17, build 2026102101) on 2026-11-03, with the throwaway app in
[`spike/`](../spike).

| # | Question | If it fails | Result |
|---|---|---|---|
| 1 | Does AOA work between the two Pixels in both host directions, and what is the sustained throughput? (Expect 30-40 MB/s; measure with a USB 3 cable too.) | Laptop route becomes the default | **Pass.** Both directions. USB 3: 110-140 MB/s accessory to host, 36-56 MB/s host to accessory. USB 2: 33-36 / 20-26 MB/s |
| 2 | Can a phone act as an adb client over USB to the other phone in AOA accessory + adb mode? | Laptop does the one-time privileged setup | **Pass.** Both directions, in normal mode and in accessory + adb mode. Shell, `cmd package install` and `pm grant` all work |
| 3 | Does "USB controlled by" swap the host role reliably on both phones, GrapheneOS included? | Laptop does the setup for the second phone | **Pass, with gotchas.** 7 of 7 swaps took effect in 1-3 s. Only the data role moves, and the Settings label can name the wrong phone |
| 4 | Does a `pm install` from the shell lift the SMS and call-log restrictions on Android 17, and skip sideloading verification on stock? | Install both phones from the laptop with `adb install` | **Pass.** Shell installs get `RESTRICTION_INSTALLER_EXEMPT` on both. Verification is not enforced on stock yet, and shell installs opt out of it |
| 5 | Can the target's MediaStore entries be given the source's `DATE_TAKEN` and modification time? | Accept that sort order may follow EXIF only | **Partly.** Modification time: yes. `DATE_TAKEN`: never written by the app; the scanner derives it from the bytes (EXIF for photos, the MP4 `mvhd` creation time for videos), so identical bytes give the same value. `DATE_ADDED`: cannot be set, not even from the shell |
| 6 | Can graphite write SMS, MMS and call-log entries on GrapheneOS, and take and give back the SMS role? | No messages or call log | **Pass.** The role moves by dialog or silently by `cmd role` from the shell. SMS and MMS keep their dates and show in order, images included, in Google Messages. The shell alone can write the call log but not SMS |

The laptop was only a monitor: it watched both phones' logs over wireless
debugging while they were cabled to each other. That was test instrumentation;
graphite itself stays wired.

## 1. AOA throughput

512 MiB per test, timed on both ends. "Sync" is one 256 KiB `bulkTransfer` at a
time on the host; "queued" keeps eight 256 KiB `UsbRequest`s in flight.

| Link | Host | Host to accessory, sync | Host to accessory, queued | Accessory to host |
|---|---|---|---|---|
| USB 2 (480 Mb/s, bcdUSB 2.10, 512-byte packets) | Pixel 10 | 22.0-25.9 MB/s | 24.3 | 33.0 |
| USB 2 | Pixel 7 Pro | 19.5, 19.6 | 22.3, 22.3 | 36.2, 36.1 |
| USB 3.2 (bcdUSB 3.20, 1024-byte packets) | Pixel 7 Pro | 36.1, 38.0, 38.8 | 48.3, 52.3, 51.1 | 123.2, 133.9, 140.1 |
| USB 3.2 | Pixel 10 | 56.3 | not run | about 110 (500 MiB in 4.7 s, cut short by a role toggle) |

- **Accessory to host is the fast direction**, 2.5-3.5 times host to accessory on
  USB 3. The accessory side reads through `f_accessory` in 16 KiB requests, which
  caps what the host can push. So the **target should be the USB host** and the
  source the accessory: media then flows at over 110 MB/s, under 15 minutes for
  100 GB.
- **USB 3 matters**: 3.5-4 times faster in the media direction. The first cable
  ran at USB 2 and the second at USB 3.2, with the same phones.
- AOA protocol version 2 on both phones. Switching to accessory mode re-enumerates
  in 1.1-1.9 s as `18d1:2d01` (accessory + adb), because USB debugging was on.
- Which phone comes up as host by default depended on the cable (the Pixel 10 with
  the first one, the Pixel 7 Pro with the second).
- Each phone is asked once: the host for USB access to the device, the accessory
  to open the app. Neither prompt came back later in the same app install.

Gotchas for the real transport:

- The accessory sends a **zero-length packet after every `write()`** (511 per
  512 MiB in 1 MiB writes). A host read of 0 bytes is not an error, and a
  `UsbRequest` can complete short. The spike's queued download miscounted because
  of this and was dropped; the real reader must count bytes per completion.
- **Bytes outlive a session.** After a killed run, a new session found 48 KiB of
  the old stream waiting in the pipe. The protocol needs a session handshake that
  discards anything before it, and framing that can resync.
- Interrupting the link (a role toggle, a cable pull) fails both sides with `EIO`.
  Transfers must resume, which [media.md](media.md) already plans for.

## 2. adb client over the cable

The spike has a minimal adb client (about 200 lines of Kotlin): CNXN, RSA AUTH
with Android's public-key format, OPEN/WRTE/OKAY/CLSE. Over the USB host port it
reached the other phone's adbd:

- Pixel 10 to Pixel 7 Pro, normal mode (`18d1:4ee7`): `uid=2000(shell)`, connected
  in 4.5 s including the "Allow USB debugging" tap, and 27 ms once trusted.
- Pixel 7 Pro to Pixel 10 (GrapheneOS), normal mode and **AOA accessory + adb**
  mode (`18d1:2d01`, adb on interface 1 next to the accessory interface): shell
  works in both.
- `exec:cmd package install -r -S <size>` streamed the 2.6 MB APK and returned
  `Success` in 1.35-1.8 s; `pm grant` and `appops set` then worked over the same
  connection.
- adbd did not ask for TLS (STLS) over USB.

Needs: USB debugging on in the other phone's developer options, and one
"Allow USB debugging" tap there. The client must match replies to stream IDs:
a late CLSE from a previous stream otherwise looks like a refusal.

## 3. Swapping the host role

Seven swaps, started from both phones over two cables, all took effect within
1-3 s, and the new host saw the other phone about 3 s later. None happened on
its own.

- **Only the data role moves.** The phone that was supplying power keeps doing so
  (for example: Pixel 10 source + device, Pixel 7 Pro sink + host).
- **The Settings label can be wrong.** Twice, both phones' "USB controlled by"
  named one phone while `dumpsys usb` and `UsbManager` said the other was host.
  The app must read the real role (an attached `UsbDevice` means host, a
  `UsbAccessory` means accessory) and guide the user from that.
- Swapping during a transfer kills it (seen twice). The app should tell the user
  not to touch USB settings once the transfer starts.

## 4. Restricted permissions and sideload verification

- `adb install` and the phone-to-phone `cmd package install` both give
  `installerPackageName=null`, `packageSource=1`, `initiatingPackageName=com.android.shell`,
  and mark READ_SMS, READ_CALL_LOG and WRITE_CALL_LOG `RESTRICTION_INSTALLER_EXEMPT`
  on both phones.
- On stock, the app's own `requestPermissions` showed normal dialogs and all three
  were granted. `pm grant` from the shell works too.
- Stock verification: `cmd package get-developer-verification-policy` returns `0`
  (none), and `cmd package install` documents `--force-verification` as the way to
  *enable* verification for a shell install. The debug-signed APK from an
  unregistered developer installed without any prompt. The verifier service was
  bound during the shell install, so whether a future enforced policy could still
  block shell installs is not proven.
- Not tested: a manual sideload (Files app) as a comparison, and requesting the
  SMS role through `RoleManager`.

## 5. MediaStore dates

Tested on the target (GrapheneOS) by inserting test JPEGs through MediaStore with
`IS_PENDING`, with and without all-files access (same results):

- `DATE_TAKEN` written at insert is visible while pending, then **overwritten by
  the scan at publish**. Updates afterwards are silently ignored (0 rows).
- The scanner sets `DATE_TAKEN` from EXIF `DateTimeOriginal` with
  `OffsetTimeOriginal`, or from a `DateTimeOriginal` without an offset when the
  file's modification time is within a day of it. Otherwise it is null.
- **Modification time can be restored**: `File.setLastModified` on the app's own
  file works through the FUSE path without all-files access. Done while pending,
  the publish scan picks it up in one go, and `DATE_MODIFIED` matches. Done after
  publishing, it needs a rescan.
- Both survive a full volume rescan.

Since graphite copies exact bytes and restores the modification time before
publishing, the target derives the same `DATE_TAKEN` the source's scanner did.
The exception is a source item whose `DATE_TAKEN` came from the app that saved it
and not from its bytes: on the target it is null, and galleries fall back to the
modification time.

### Videos

Three test MP4s, inserted the same way on both phones (and checked again after a
full volume rescan on the Pixel 10):

| File | Restored mtime | `DATE_TAKEN` |
|---|---|---|
| `mvhd` creation time 2018-07-08T09:10:11Z | no | 2018-07-08T09:10:11Z |
| same | yes (2020-01-02) | 2018-07-08T09:10:11Z |
| `mvhd` creation time 0 | no / yes | null |
| `mvhd` creation time 0, modification time 2016-05-01 | yes | null |

So for videos the scanner takes `DATE_TAKEN` from the `mvhd` **creation time
only**, as UTC. It ignores the `mvhd` modification time, and the file's
modification time never feeds it (unlike a photo whose EXIF lacks an offset).
Restoring the modification time still sets `DATE_MODIFIED`, as for photos.

### `DATE_ADDED` from the shell

Tested on both phones with the shell uid (`com.android.shell`), the identity
graphite's adb client gets over the cable, on items the spike app inserted:

- `content update --bind date_added:l:<seconds>` on `content://media/external/file`,
  `.../external_primary/images/media`, `.../images/media/<id>` and
  `.../video/media`: the command reports nothing, the value does not change, and
  MediaProvider logs `Ignoring mutation of date_added from com.android.shell`.
- The same happens to `date_modified`, `datetaken` and `title`. The update path
  itself works: `is_favorite` changed in the same call.
- `content insert` from the shell with `date_added` bound (a shell-copied file,
  `_data` set) creates the row, but `date_added` is the insert time; nothing is
  logged.
- There is no `cmd media` or `cmd media_provider` service on either phone.
  `content call --method scan_volume` rescans but leaves `DATE_ADDED` alone.

`DATE_ADDED` is set by MediaProvider at insert and only MediaProvider itself (or
root) can change it. A rescan keeps it (checked on the Pixel 10).

## 6. SMS, MMS and call log on the target

Tested on the Pixel 10 (GrapheneOS, live Google Fi SIM) on 2026-11-03, with the
user's real Messages and call log. Every test row used the fake number
+1 555 0100 and a "GRAPHITE SPIKE TEST — safe to delete" body. All of them
(SMS 149-153, MMS 379 with parts 534-536 and addresses 1188-1189, calls 315-318)
were deleted by ID afterwards and checked gone, and the SMS role went back to its
holder.

**SMS role.** The holder was **Google Messages** (`com.google.android.apps.messaging`),
not GrapheneOS's AOSP Messaging, which is also installed. The spike qualified for
the role with the four components (SMS_DELIVER receiver, WAP_PUSH_DELIVER
receiver, RESPOND_VIA_MESSAGE service, SENDTO activity).

- `RoleManager.createRequestRoleIntent`: Android's standard "Set as default SMS
  app" dialog, one tap. The user reported no extra GrapheneOS prompt.
- `cmd role add-role-holder android.app.role.SMS <pkg>` from the shell: rc 0, **no
  prompt**, effective at once. The same command with Google Messages gives the
  role back; both directions took 0.35 s.
- The spike held the role for about four minutes via the dialog and 0.35 s via the
  shell. No SMS or MMS arrived in either window (checked in the provider and the
  spike's receivers), so **delivery to a temporary holder was not tested**. While
  the spike held the role, an incoming SMS would have gone to it (it saves SMS to
  the inbox as a normal SMS app would), and an MMS would not have been downloaded.

**SMS.** The role holder inserted three received and two sent messages dated
2020-01-01 to 2020-01-03. `date` and `date_sent` were stored as given. All five
landed in one new thread for the number, and Google Messages showed them there
after it got the role back, in date order, with the right sides (received left,
sent right) and 2020 date headers.

**MMS.** One inbox MMS (dates in seconds, `m_type` 132) with a SMIL part, a
96 px PNG part written through the part URI, a text part and from/to addresses,
in the same thread. Google Messages showed the image, the subject and the text
in date order among the SMS.

**Call log.** Incoming, outgoing and missed calls dated 2-4 days back, with
durations, all showed in GrapheneOS's Dialer (Recents) with the right arrows and
"N days ago".

- Rewritten by the provider: `last_modified` (set to the insert time) and
  `geocoded_location` (blank, though a value was written).
- The `name` column was stored, but Dialer shows the number: it looks names up in
  Contacts. Subscription columns stay null unless written. Whether the missed call
  raised a notification was not checked.

**From the shell** (the uid graphite's adb client gets):

- The call log: insert and delete both work (`CallLogProvider: insert uid/pid=2000`).
  So the target app does not even need WRITE_CALL_LOG for it.
- SMS: `content insert` and `content delete` are silently ignored; only the SMS
  role holder can write. Reading works (`canReadRestrictedMessages=true`).

Gotchas:

- An app's `delete` of a single call URI (`content://call_log/calls/<id>`) throws
  `UnsupportedOperationException`; delete with a `_id` selection instead.
- Deleting an MMS deletes its parts and addresses; deleting those by URI first
  returned 0. Deleting a thread's last message removes the thread.
- Google Messages keeps its own copy of messages. Inserts made while it was not
  the holder appeared once it had the role back, and the deletions disappeared
  from it within a minute.
- Not checked: whether Google Messages' RCS chat features survived losing the role
  for four minutes. The user should look in Messages, Settings, RCS chats.

## Phones stay awake and unlocked

GrapheneOS turns the port to charging-only and refuses new USB devices while the
screen is locked (logged on the Pixel 10). The host phone powers the other, so it
is not charging and "stay awake while charging" does not help. During the tests
the screen timeout was raised to 30 minutes on both phones; the spike app now
keeps the screen on while it is open. graphite must do the same for pairing and
transfer (see [design.md](design.md)).

## Running the spike app

```bash
cd spike && ./gradlew assembleDebug
```

Install on both phones (`adb install -r build/outputs/apk/debug/graphite-spike-debug.apk`),
then use the buttons, or fire them with
`adb shell am start -n io.github.storskegg.graphite.spike/.MainActivity --es run <name>`
(`status`, `aoa`, `accessory`, `adb`, `adbinstall`, `perms`, `media`, `video`, `mediaquery`, `smsrole`, `requestsms`, `sms`, `mms`, `calls`, `cleanup`).
Results go to logcat tag `GSPIKE`. The app requests SMS, call-log and all-files
access, and can hold the SMS role: give the role back and uninstall it afterwards.
