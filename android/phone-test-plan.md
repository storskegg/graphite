# Phone test plan, 6 November 2026

Pixel 7 Pro (stock, old phone) and Pixel 10 Pro XL (GrapheneOS, new phone), USB 3
cable. Debug build from `main`. In order; each step says what to look at. Test data
only, except step 6, which copies the real library to the new phone as on 3 November
and removes nothing.

## Before you connect anything

1. **Disconnect wireless debugging from the old phone** (Settings › System ›
   Developer options › Wireless debugging: off) before any cable install.
2. Keep both phones unlocked and on the charger between steps. Screen timeout stays
   at its usual value: graphite keeps the screen on itself.

## 1. Install and attestation (iteration 2)

1. Install the build on both phones by cable: `adb install -r app-debug.apk`.
2. Open graphite on each. The status line should say:
   - Pixel 10: "New phone: GrapheneOS on a Pixel 10 Pro XL, bootloader locked,
     verified by hardware attestation."
   - Pixel 7 Pro: "Old phone: not GrapheneOS (Android 17), verified by hardware
     attestation."
3. Pair. Right after "Accept", the old phone shows "Checking the new phone" and
   the log says `attestation of the new phone: GrapheneOS on Pixel 10 Pro XL`.
   A refusal here stops the session on both phones with the reason: note it
   word for word.
4. Optional: the device test on the Pixel 10 alone,
   `ANDROID_SERIAL=<pixel 10> ./gradlew :app:connectedDebugAndroidTest`
   (it logs the chain under `graphite-attest`).

## 2. Media leftovers from iteration 1 (test scope `graphite-test`)

1. **Treemap and verification progress on real screens**: during the copy and the
   verification both phones show the map above the progress; tap a square, check
   the name and state. Squares go gray, yellow, blue.
2. **Done on the Pixel 10's last screen** goes back to the start.
3. **USB-control wording**: plug in so the old phone takes USB control; read the
   screen on each phone against the labels in Settings.
4. **Trash fallback for `.mov` and `.3gp`**: removal from the old phone with
   `gt-clip.mov` and `gt-clip.3gp` in `DCIM/graphite-test`; both go to the trash.
5. **`DATE_TAKEN` steering**: the receipt notes "modification time set to the date
   taken" where it applied.

## 3. Messages, calls, contacts, blocked numbers (iteration 2, test records only)

Seeded on the old phone by graphite itself, in the fictional +1 555 010x range and
named "GT …": 5 SMS (one saved twice), 1 MMS with a picture, 3 calls, 2 contacts,
2 blocked numbers. The test scope keeps graphite from reading anything else.

1. Seed: give graphite the SMS role on the old phone for a moment, seed, give it back:
   `adb shell cmd role add-role-holder android.app.role.SMS io.github.storskegg.graphite`,
   then `adb shell am start -n io.github.storskegg.graphite/.MainActivity --es test_seed_records 1`,
   then the same `cmd role` with `com.google.android.apps.messaging`.
2. Transfer with every category ticked. Follow graphite's prompts. These are the
   real user steps:
   - old phone: "graphite needs to be the SMS app for a moment", Android's dialog,
     then **give it back through Messages** ("Set default chat app", "Set as default");
   - new phone: the same, once at comparison; it gives the role back after the
     records are written.
3. On the Pixel 10 check:
   - Messages: the GT threads, the picture MMS, sent on the right, the 2020 dates;
   - Phone, Recents: the 3 calls;
   - Contacts: GT Jane Doe (with photo, starred) and GT Bob;
   - Blocked numbers: +1 555-0108 and -0109;
   - Messages, Settings, RCS chats: still on, after losing the role for a minute.
4. Run it again: "0 copied", nothing doubled.
5. Note whether any SMS arrived during a role window (graphite counts them).

## 4. On-phone speed (iteration 1)

With the test scope cleared (`--es test_scope ''`), copy the real library, as on
3 November: "Remove location and identifiers", records unticked. Note
`copy phases:` and `send phases:` from both logs and the time. The 5,612 squares
of the treemap: is it readable? Remove nothing.

## 5. Clean up: give the phones back as found

1. Delete the test records on both phones (graphite deletes only test-range records):
   SMS role to graphite, `--es test_clear_records 1`, role back to Messages.
2. Delete the `graphite-test` folders and the test files on both phones:
   `.trash-storage` test items, `big*.bin` on the Pixel 10, the `Android/media` sticker.
3. Check the SMS role is Messages on both phones, and the Pixel 7 Pro's USB power role
   and USB data role are as before.
4. Clear graphite's test settings (`pm clear io.github.storskegg.graphite`), or
   uninstall graphite.
