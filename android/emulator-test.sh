#!/usr/bin/env bash
# End-to-end test of the media flow on two emulators, over the debug-only TCP
# transport (real phones remain the test for AOA, the USB role, the cable
# install and attestation). Boots its own two AVDs, installs the debug APK,
# seeds synthetic media from strip/tests/fixtures and runs:
#   keep    - "Keep all metadata": everything copied, the duplicate once
#   again   - the same again: nothing copied
#   strip   - "Remove location and identifiers" on a fresh target: GPS gone, dates kept
#   display - "Remove all but what display needs" on a fresh target: dates gone
#   resume  - the socket is cut mid-copy; the link resumes and the copy verifies
#   space   - preflight refuses when the target is (made to look) full
#   battery - preflight refuses at 5 % and goes on after the battery recovers
#   records - messages, calls, contacts and blocked numbers (synthetic, +1 555 010x and "GT …")
#             copied with the media; graphite takes the SMS role on both phones and gives it back
#   records_again - the same again: nothing copied, nothing duplicated; then the test records go
#   removal - the source moves verified files to its trash
#   attest  - with attestation enforced (the other scenarios skip it: emulators can't pass), the
#             old phone refuses the emulator and both phones say why
#   speed   - not in the default run: SPEED_FILES small photos (default 400) and two 40 MB
#             files, copied with "Keep all"; prints the time and where it went on each side
# Usage: android/emulator-test.sh [scenario...]   (default: all but speed, in that order)
# Env: KEEP=1 leaves the emulators running; HEADLESS=0 shows their windows;
#      GRAPHITE_APK=path installs that APK instead of building one (to compare builds).
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(dirname "$HERE")
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:?set ANDROID_HOME}}
ADB="$SDK/platform-tools/adb"; [ -x "$ADB" ] || ADB=adb
EMULATOR="$SDK/emulator/emulator"
AVDMANAGER=$(command -v avdmanager || echo "$SDK/cmdline-tools/latest/bin/avdmanager")
# avdmanager and the emulator both use this; some installs default elsewhere (~/.config/.android).
export ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-$HOME/.android/avd}
mkdir -p "$ANDROID_AVD_HOME"
PKG=io.github.storskegg.graphite
PIN=1234
PORT=7710
case "$(uname -m)" in arm64|aarch64) ABI=arm64-v8a ;; *) ABI=x86_64 ;; esac
TARGET_IMAGE=${TARGET_IMAGE:-"system-images;android-37.0;google_apis;$ABI"}
SOURCE_IMAGE=${SOURCE_IMAGE:-"system-images;android-30;google_apis;$ABI"}
T=emulator-5580   # target: the "new phone", latest API
S=emulator-5582   # source: the "old phone", API 30
FIX="$REPO/strip/tests/fixtures"
LOG=$(mktemp -d)/graphite-emulator-test
mkdir -p "$LOG"
if [ $# -gt 0 ]; then SCENARIOS=("$@"); else SCENARIOS=(keep again strip display resume space battery records records_again removal attest); fi
SMS_APP=com.google.android.apps.messaging   # both images' default SMS app

say() { printf '\n== %s\n' "$*"; }
fail() {
    printf 'FAIL: %s\n' "$*" >&2; dump "$T" target; dump "$S" source
    "$ADB" -s "$S" logcat -d -v time >>"$LOG/source.logcat"; "$ADB" -s "$T" logcat -d -v time >>"$LOG/target.logcat"
    echo "logs: $LOG" >&2; exit 1
}
sh_() { "$ADB" -s "$1" shell "${@:2}"; }

# --- emulators -------------------------------------------------------------

avd() {  # avd NAME IMAGE
    if ! "$EMULATOR" -list-avds | grep -qx "$1"; then
        echo no | "$AVDMANAGER" create avd -n "$1" -k "$2" -d pixel_6 >/dev/null
    fi
    # The default 2 GB of RAM hangs and crashes recent images while booting; some installs
    # default to a data partition too small to install the APK.
    local cfg="$ANDROID_AVD_HOME/$1.avd/config.ini"
    sed -i.bak -e 's/^hw.ramSize=.*/hw.ramSize=4096/' -e '/^disk.dataPartition.size=/d' "$cfg"
    echo "disk.dataPartition.size=6G" >>"$cfg"
}

boot() {  # boot SERIAL AVD [EMULATOR FLAG...]
    local port=${1#emulator-} pid="" end=$(( $(date +%s) + ${BOOT_TIMEOUT:-600} ))
    if ! "$ADB" devices | grep -q "^$1"; then
        local window=(-no-window); [ "${HEADLESS:-1}" = 0 ] && window=()
        "$EMULATOR" -avd "$2" -port "$port" ${window[@]+"${window[@]}"} -no-audio -no-snapshot -no-boot-anim \
            -gpu swiftshader_indirect "${@:3}" >"$LOG/$2.emulator.log" 2>&1 &
        pid=$!
    fi
    until [ "$(sh_ "$1" getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
        if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
            tail -20 "$LOG/$2.emulator.log" >&2; echo "FAIL: emulator $2 exited while booting" >&2; exit 1
        fi
        if [ "$(date +%s)" -gt "$end" ]; then
            tail -20 "$LOG/$2.emulator.log" >&2; echo "FAIL: emulator $2 did not boot in time" >&2; exit 1
        fi
        sleep 2
    done
    # On slow hosts boot_completed comes before every system service is up.
    until [ "$(sh_ "$1" "service check package; service check appops; service check media.extractor" 2>/dev/null | grep -c ': found')" = 3 ]; do
        [ "$(date +%s)" -gt "$end" ] && { echo "FAIL: emulator $2: system services never came up" >&2; exit 1; }
        sleep 2
    done
    # An AVD booted with the test PIN set stays locked, and a locked user can't start graphite.
    sh_ "$1" "locksettings verify --old $PIN" >/dev/null 2>&1 || true
    sh_ "$1" "input keyevent 224; input keyevent 82"; sleep 1; auth "$1"
    sh_ "$1" "svc power stayon true; settings put system screen_off_timeout 1800000; input keyevent 82" >/dev/null
    # The receipt is read from the log, which the default 256 kB buffer can lose in noise.
    sh_ "$1" "logcat -G 16M" >/dev/null 2>&1 || true
    # uiautomator can't dump a screen that never goes idle (a spinning progress bar).
    sh_ "$1" "settings put global animator_duration_scale 0; settings put global transition_animation_scale 0; settings put global window_animation_scale 0"
    if [ "$(sh_ "$1" getprop ro.build.version.sdk | tr -d '\r')" = 30 ]; then
        # The API 30 image's updated MediaProvider module is denied its pass-through mount by
        # the image's older SELinux policy, so nothing pushed would be scanned. Emulator only.
        "$ADB" -s "$1" root >/dev/null; "$ADB" -s "$1" wait-for-device
        sh_ "$1" "setenforce 0; am force-stop com.google.android.providers.media.module"
    fi
}

prepare() {  # prepare SERIAL ROLE TCP
    # A freshly booted emulator's package service can still refuse for a while.
    local try
    for try in 1 2 3 4 5; do "$ADB" -s "$1" install -r -g "$APK" && break; [ $try = 5 ] && { echo "FAIL: could not install on $1" >&2; exit 1; }; sleep 10; done
    for try in $(seq 30); do sh_ "$1" "pm path $PKG" 2>/dev/null | grep -q '^package:' && break; sleep 2; done
    sh_ "$1" "locksettings set-pin $PIN" >/dev/null 2>&1 || true   # BiometricPrompt then asks for the PIN
    sh_ "$1" "appops set $PKG MANAGE_EXTERNAL_STORAGE allow"
    sh_ "$1" "pm grant $PKG android.permission.POST_NOTIFICATIONS" >/dev/null 2>&1 || true   # API 33+
    # Right after an install the activity may not resolve yet ("does not exist").
    for try in $(seq 15); do
        sh_ "$1" "am force-stop $PKG; am start -n $PKG/.MainActivity --es test_role $2 --es test_tcp $3 --es test_scope graphite-test --es test_attestation skip" 2>&1 | grep -q '^Error' || return 0
        sleep 2
    done
    echo "FAIL: graphite would not start on $1" >&2; exit 1
}

# --- the screen --------------------------------------------------------------

ui() { sh_ "$1" "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && cat /sdcard/ui.xml; rm -f /sdcard/ui.xml" 2>/dev/null | tr '>' '\n'; }
texts() { ui "$1" | grep -oE "text=\"[^\"]+\"" | sed 's/^text="//; s/"$//'; }
dump() { printf -- '--- %s screen:\n' "$2" >&2; texts "$1" >&2 || true; }
focus() { sh_ "$1" "dumpsys window | grep mCurrentFocus" | tr -d '\r' || true; }

# tap SERIAL TEXT [TIMEOUT]: taps the element whose text is TEXT (else starts with it), or fails.
tap() { trytap "$@" || fail "no \"$2\" on $1"; }

# trytap SERIAL TEXT [TIMEOUT]: the same, returning 1 instead of failing.
trytap() {
    local end=$(( $(date +%s) + ${3:-120} )) b
    while [ "$(date +%s)" -lt "$end" ]; do
        auth_both
        # An exact match first ("Accept", not the title "Accept the old phone?"), else a prefix.
        b=$(ui "$1" | tee "$LOG/ui" | grep -F "text=\"$2\"" | grep -o 'bounds="[^"]*"' | head -1 || true)
        [ -n "$b" ] || b=$(grep -F "text=\"$2" "$LOG/ui" | grep -o 'bounds="[^"]*"' | head -1 || true)
        if [ -n "$b" ]; then
            read -r x1 y1 x2 y2 <<<"$(echo "$b" | tr -c '0-9' ' ')"
            sh_ "$1" input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 )); sleep 1; return 0
        fi
        # Below the fold (the treemap and a long receipt push the buttons down): scroll, from under the map.
        read -r w h <<<"$(sh_ "$1" wm size | tail -1 | tr -c '0-9' ' ')"
        [ -n "${h:-}" ] && sh_ "$1" input swipe $(( w / 2 )) $(( h * 85 / 100 )) $(( w / 2 )) $(( h * 40 / 100 )) 300
        sleep 1
    done
    return 1
}

# waitfor SERIAL TEXT [TIMEOUT]
waitfor() {
    local end=$(( $(date +%s) + ${3:-300} ))
    while [ "$(date +%s)" -lt "$end" ]; do auth_both; texts "$1" | grep -qF "$2" && return 0; sleep 2; done
    fail "\"$2\" never showed on $1"
}

# waitlog SERIAL PATTERN [TIMEOUT]: waits for graphite to log PATTERN.
waitlog() {
    local end=$(( $(date +%s) + ${3:-300} ))
    while [ "$(date +%s)" -lt "$end" ]; do "$ADB" -s "$1" logcat -d | grep -qF "$2" && return 0; sleep 2; done
    fail "graphite never logged \"$2\" on $1"
}

# Either phone may be asking while the script waits on the other.
auth_both() { auth "$T"; auth "$S"; }

# The user-presence prompt: with only a PIN set, BiometricPrompt asks for it.
auth() {
    case "$(focus "$1")" in
        *BiometricPrompt*|*ConfirmDeviceCredential*|*Bouncer*|*Keyguard*|*NotificationShade*)
            # The PIN field has no focus on API 30 until tapped.
            local b; b=$(ui "$1" | grep -E 'class="android.widget.EditText"' | grep -oE 'bounds="[^"]+"' | head -1 | grep -oE '[0-9]+' | tr '\n' ' ' || true)
            if [ -n "$b" ]; then set -- "$1" $b; sh_ "$1" "input tap $(( ($2 + $4) / 2 )) $(( ($3 + $5) / 2 ))"; fi
            sh_ "$1" "input text $PIN; input keyevent 66"; sleep 2 ;;
    esac
}

# --- media ---------------------------------------------------------------------

scan() { sh_ "$1" "content call --uri content://media --method scan_file --arg '$2'" >/dev/null; }

seed() {  # the source's test set; mtimes fixed so dates can be checked
    local i=0 f
    for f in "jpeg-full.jpg DCIM/graphite-test/gt-gps.jpg" "motion.jpg DCIM/graphite-test/gt-motion.jpg" \
             "ultrahdr.jpg DCIM/graphite-test/gt-ultrahdr.jpg" "heic.heic DCIM/graphite-test/gt-photo.heic" \
             "jpeg-full.jpg Pictures/graphite-test/gt-gps-copy.jpg" "png.png Pictures/graphite-test/gt-shot.png" \
             "video.mp4 DCIM/graphite-test/gt-video.mp4" "audio.m4a Music/graphite-test/gt-tone.m4a"; do
        set -- $f; i=$((i + 1))
        "$ADB" -s "$S" push "$FIX/$1" "/sdcard/$2" >/dev/null
        sh_ "$S" "touch -m -t 20210304050$i.0$i /sdcard/$2"; scan "$S" "/sdcard/$2"
    done
    sh_ "$S" "mkdir -p /sdcard/Documents/graphite-test /sdcard/Download/graphite-test; echo note > /sdcard/Documents/graphite-test/gt-note.txt"
    scan "$S" /sdcard/Documents/graphite-test/gt-note.txt
}

big() {  # big MB: a file large enough to cut the socket in the middle of
    sh_ "$S" "head -c $(( $1 * 1000000 )) /dev/urandom > /sdcard/Download/graphite-test/gt-big-$1.bin"
    scan "$S" "/sdcard/Download/graphite-test/gt-big-$1.bin"
}

rows() {  # rows SERIAL: graphite-test rows as "path|datetaken|date_modified"
    sh_ "$1" "content query --uri content://media/external/file --projection _data:datetaken:date_modified \
        --where \"_data LIKE '%/graphite-test/gt-%'\"" | sed -E 's#.*/storage/emulated/0/##; s/, datetaken=/|/; s/, date_modified=/|/' | { grep -v '^No result' || true; }
}

fresh_target() {  # forget everything the target has: its copies, journal and hash cache
    sh_ "$T" "content delete --uri content://media/external/file --where \"_data LIKE '%/graphite-test/%'\"" >/dev/null 2>&1 || true
    sh_ "$T" "pm clear $PKG" >/dev/null
    prepare "$T" target "listen:$PORT"
    # The source reconnects as soon as a session ends; restart it so it pairs with the fresh target.
    sh_ "$S" "am force-stop $PKG; am start -n $PKG/.MainActivity" >/dev/null
}

# --- records -------------------------------------------------------------------

# sms_role SERIAL PKG: gives PKG the SMS role from the shell: the test's stand-in for the user
# choosing an app in Android's dialog or in Messages' "Set default chat app" prompt.
sms_role() { sh_ "$1" "cmd role add-role-holder android.app.role.SMS $2" >/dev/null; }

# holder SERIAL: the SMS app (API 30's cmd role can't list holders: read dumpsys there).
holder() {
    local h; h=$(sh_ "$1" "cmd role get-role-holders android.app.role.SMS" 2>/dev/null | tr -d '\r')
    case "$h" in ""|Unknown*) sh_ "$1" "dumpsys role" | tr -d '\r' | grep -A1 'name=android.app.role.SMS$' | sed -n 's/.*holders=//p' ;; *) echo "$h" ;; esac
}

# seed_records: graphite writes the synthetic records on the source while it holds the role.
seed_records() {
    sms_role "$S" "$PKG"; sh_ "$S" "logcat -c"
    sh_ "$S" "am start -n $PKG/.MainActivity --es test_seed_records 1" >/dev/null
    waitlog "$S" "test seed: wrote" 60; sms_role "$S" "$SMS_APP"
}

# clear_records: both phones delete their test records (and only those).
clear_records() {
    local d; for d in "$T" "$S"; do
        sms_role "$d" "$PKG"; sh_ "$d" "logcat -c"
        sh_ "$d" "am start -n $PKG/.MainActivity --es test_clear_records 1" >/dev/null
        waitlog "$d" "test clear: deleted" 60; sms_role "$d" "$SMS_APP"
    done
}

# count SERIAL URI WHERE: rows of a provider (as the shell sees them).
count() { sh_ "$1" "content query --uri $2 --projection _id --where \"$3\"" | grep -c '^Row' || true; }

# take_role SERIAL: graphite asks to be the SMS app; the user chooses graphite in Android's dialog.
take_role() {
    waitfor "$1" "graphite needs to be the SMS app" 600; tap "$1" "Continue"
    trytap "$1" "graphite" 20 >/dev/null || true; tap "$1" "Set as default" 30
}

# give_back SERIAL: the user switches back to Messages, and graphite checks.
give_back() { waitfor "$1" "Give the SMS role back" 600; sms_role "$1" "$SMS_APP"; tap "$1" "Check again"; }

# --- one transfer --------------------------------------------------------------

# transfer CHOICE [HOOK] [records]: pairs, chooses, copies; HOOK runs once the copy starts. With
# "records" it also copies messages, calls, contacts and blocked numbers; otherwise blocked numbers
# are unticked (the only record choice shown when the source has no test records).
# Leaves the source on its "Copy verified" screen and prints the receipt line.
transfer() {
    pair_both
    tap "$T" "$1"
    if [ "${3:-}" = records ]; then
        tap "$T" "Continue"; take_role "$S"; give_back "$S"; take_role "$T"
    else
        trytap "$T" "Blocked numbers" 5 >/dev/null || true; tap "$T" "Continue"
    fi
    if [ "${2:-}" = space ] || [ "${2:-}" = battery ]; then return 0; fi
    tap "$T" "Continue" 600; tap "$T" "Start copy"
    if [ "${3:-}" = records ]; then give_back "$T"; fi
    if [ -n "${2:-}" ]; then "$2"; fi
    waitfor "$T" "Copy finished" 1200
    waitfor "$S" "Copy verified" 120
    "$ADB" -s "$S" logcat -d | { grep -o 'receipt verified: .*' || true; } | tail -1 | sed 's/^receipt verified: //'
}

# pair_both: both phones start, pair with the six digits, and the new phone accepts the old one.
pair_both() {
    # Keep each transfer's logs (in $LOG) before clearing them for this one.
    "$ADB" -s "$S" logcat -d -v time >>"$LOG/source.logcat"; "$ADB" -s "$T" logcat -d -v time >>"$LOG/target.logcat"
    sh_ "$S" "logcat -c"; sh_ "$T" "logcat -c"
    sh_ "$T" "am start -n $PKG/.MainActivity" >/dev/null; sh_ "$S" "am start -n $PKG/.MainActivity" >/dev/null
    waitfor "$T" "Compare the codes"; waitfor "$S" "Compare the codes"
    local ct="" cs="" try
    # A dump can fail while a screen is changing: read the codes until both are there.
    for try in $(seq 10); do
        ct=$(texts "$T" | grep -E '^[0-9]{3} [0-9]{3}$' || true); cs=$(texts "$S" | grep -E '^[0-9]{3} [0-9]{3}$' || true)
        [ -n "$ct" ] && [ -n "$cs" ] && break; sleep 1
    done
    [ -n "$ct" ] && [ "$ct" = "$cs" ] || fail "codes differ: '$ct' vs '$cs'"
    tap "$T" "They match"; tap "$S" "They match"; tap "$T" "Accept"
}

# Both back to the start; the source asks once more before leaving its files (it always has some here).
done_both() { tap "$T" "Done"; tap "$S" "Done"; tap "$S" "Keep them" 30; }

expect() {  # expect RECEIPT_LINE PATTERN
    echo "  receipt: $1"; echo "$1" | grep -qE "$2" || fail "receipt does not match /$2/"
}

cut_socket() {  # the emulators' "cable pull": reset the source's connection mid-copy, then let it back
    # (removing the adb forward would leave the open socket alone). The source is rooted (API 30, see boot).
    # The copy screen changes four times a second, too often for uiautomator: watch the log.
    waitlog "$T" "Copying: " 120; sleep 3
    sh_ "$S" "iptables -I OUTPUT -p tcp --dport $PORT -j REJECT --reject-with tcp-reset"; sleep 5
    sh_ "$S" "iptables -D OUTPUT -p tcp --dport $PORT -j REJECT --reject-with tcp-reset"
}

tiles() {  # tiles N: both final screens show the treemap with N files done, and it changed on the way
    local t s
    t=$(ui "$T" | grep -oE 'content-desc="Treemap: [^"]*"' || true); s=$(ui "$S" | grep -oE 'content-desc="Treemap: [^"]*"' || true)
    echo "  treemap: new phone \"${t#content-desc=}\", old phone \"${s#content-desc=}\""
    [ "$t" = "content-desc=\"Treemap: $1 done\"" ] || fail "the new phone's treemap is not $1 done"
    [ "$s" = "$t" ] || fail "the two treemaps differ"
    "$ADB" -s "$T" logcat -d | grep -qF "tiles after the copy: $1 done" || fail "the new phone's tiles after the copy are not $1 done"
    # The old phone's map during verification came from the new phone, with tiles still waiting then.
    "$ADB" -s "$S" logcat -d | grep -qE "tiles from the new phone: .*(waiting|in progress)" || fail "the old phone never got the new phone's tiles"
    sh_ "$T" "screencap -p" >"$LOG/treemap-target.png"; sh_ "$S" "screencap -p" >"$LOG/treemap-source.png"
}

# --- scenarios -----------------------------------------------------------------

keep() {
    fresh_target; local r; r=$(transfer "Keep all metadata"); tiles 9; done_both
    expect "$r" "^9 copied and verified, 0 already"   # the duplicate counts as copied; its bytes went once
    [ "$(rows "$T" | grep -c 'gt-gps')" = 1 ] || fail "the duplicate was copied twice"
    local src; src=$(rows "$S" | grep "DCIM/graphite-test/gt-gps.jpg|" || true)
    [ -n "$src" ] || fail "gt-gps.jpg is not on the source"
    rows "$T" | grep -qxF "$src" || fail "date taken or modification time differ: $src"
}

again() {
    local r; r=$(transfer "Keep all metadata"); done_both
    expect "$r" "^0 copied and verified, 9 already on the new phone"
}

strip() {
    fresh_target
    local r; r=$(transfer "Remove location and identifiers"); done_both
    expect "$r" "^9 copied and verified"
    rows "$T" | grep -q "gt-gps.jpg|1577955845123|" || fail "date taken not kept under the middle choice"
    rows "$T" | grep -q "gt-video.mp4|1577934245000|" || fail "video date taken not kept"
}

display() {
    fresh_target
    local r; r=$(transfer "Remove all but what display needs"); done_both
    expect "$r" "^9 copied and verified"
    rows "$T" | grep -q "gt-gps.jpg|NULL|" || fail "date taken left under the strictest choice"
}

resume() {
    big 400
    local r; r=$(transfer "Keep all metadata" cut_socket); done_both
    expect "$r" "^1 copied and verified"
    "$ADB" -s "$T" logcat -d | grep -q "link resumed" || fail "the link never resumed"
}

space() {
    sh_ "$T" "am start -n $PKG/.MainActivity --es test_free_bytes 1000" >/dev/null
    big 50
    transfer "Keep all metadata" space
    waitfor "$T" "Not enough space on this phone" 600
    tap "$T" "Stop"
    sh_ "$T" "am start -n $PKG/.MainActivity --es test_free_bytes x" >/dev/null
    waitfor "$S" "not enough space on the new phone" 60
    tap "$S" "Done" 60
}

battery() {
    sh_ "$T" "dumpsys battery unplug; dumpsys battery set level 5"
    transfer "Keep all metadata" battery
    waitfor "$T" "Battery too low" 600
    sh_ "$T" "dumpsys battery reset"
    tap "$T" "Check again"; tap "$T" "Continue" 60; tap "$T" "Start copy"
    waitfor "$T" "Copy finished" 600; done_both
}

# What the target holds of the test records: "sms mms calls contacts blocked".
held_records() {
    local test="LIKE '+1555010%'"
    echo "$(count "$T" content://sms "address $test") $(count "$T" content://mms "_id > 0") \
$(count "$T" content://call_log/calls "number $test") \
$(count "$T" content://com.android.contacts/raw_contacts "display_name LIKE 'GT %' AND deleted = 0") \
$(count "$T" content://com.android.blockednumber/blocked "original_number $test")"
}

# The files in the last manifest, from the new phone's log.
files_in_manifest() { "$ADB" -s "$T" logcat -d | grep -o 'manifest: [0-9]* entries' | tail -1 | tr -dc '0-9'; }

records() {
    clear_records; seed_records; fresh_target
    # 5 SMS (one saved twice), 1 MMS, 3 calls, 2 contacts, 2 blocked numbers; the SMS outside the test range stays.
    local r media; r=$(transfer "Keep all metadata" "" records); media=$(files_in_manifest)
    tiles $(( media + 13 )); done_both
    expect "$r" "^$(( media + 13 )) copied and verified, 0 already"
    local held; held=$(held_records); echo "  new phone holds (sms mms calls contacts blocked): $held"
    [ "$held" = "4 1 3 2 2" ] || fail "the new phone's test records are not 4 1 3 2 2"
    [ "$(count "$T" content://sms "body = 'not test data'")" = 0 ] || fail "a message outside the test range was copied"
    [ "$(count "$T" content://mms/part "ct = 'image/png' AND _data IS NOT NULL")" = 1 ] || fail "the MMS picture did not arrive"
    local h; for h in "$T" "$S"; do [ "$(holder "$h")" = "$SMS_APP" ] || fail "the SMS role is not back with Messages on $h"; done
}

records_again() {
    local r media; r=$(transfer "Keep all metadata" "" records); media=$(files_in_manifest); done_both
    expect "$r" "^0 copied and verified, $(( media + 13 )) already on the new phone"
    [ "$(held_records)" = "4 1 3 2 2" ] || fail "a second run changed the new phone's records: $(held_records)"
    clear_records
}

attest() {  # the old phone refuses the emulator's software attestation; both phones say why
    sh_ "$S" "am start -n $PKG/.MainActivity --es test_attestation ''" >/dev/null
    pair_both
    waitfor "$S" "not rooted in Google's" 120
    waitfor "$T" "the old phone refused this phone" 120
    "$ADB" -s "$S" logcat -d | grep -qF "attestation of the new phone: the new phone's attestation is not rooted" || fail "the source did not log the refusal"
    tap "$S" "Start again"; tap "$T" "Done"
    sh_ "$S" "am start -n $PKG/.MainActivity --es test_attestation skip" >/dev/null
}

removal() {
    transfer "Keep all metadata" >/dev/null
    tap "$S" "Remove from this phone"
    local box; for box in DCIM/graphite-test Pictures/graphite-test Music/graphite-test Documents/graphite-test Download/graphite-test; do trytap "$S" "$box" 10 || true; done
    tap "$S" "Move to trash"
    # Android's own confirmation for the media items: test-only automation.
    tap "$S" "Allow" 60
    waitfor "$S" "Removal finished" 120
    local left; left=$(rows "$S" | wc -l | tr -d ' ')
    [ "$left" = 0 ] || fail "$left test files still on the source after removal"
    # Trashed files are renamed ".trashed-…" (in place on API 30, under .trash-storage on newer).
    sh_ "$S" "find /storage/emulated/0/ -name '.trashed-*gt-*' 2>/dev/null || true" | grep -q . || fail "nothing in the source's trash"
    tap "$S" "Done"; tap "$T" "Done"
}

speed() {
    local d=/sdcard/DCIM/graphite-test/speed n=${SPEED_FILES:-400}
    # Distinct small photos: the test JPEG with random bytes after it (about 300 kB each).
    "$ADB" -s "$S" push "$FIX/jpeg-full.jpg" /data/local/tmp/gt.jpg >/dev/null
    sh_ "$S" "mkdir -p $d; i=0; while [ \$i -lt $n ]; do i=\$((i+1)); { cat /data/local/tmp/gt.jpg; head -c 300000 /dev/urandom; } > $d/gt-s\$i.jpg; done"
    sh_ "$S" "head -c 40000000 /dev/urandom > $d/gt-m1.bin; head -c 40000000 /dev/urandom > $d/gt-m2.bin"
    scan "$S" "$d"
    fresh_target
    local t0=$(date +%s) r
    r=$(transfer "Keep all metadata"); local secs=$(( $(date +%s) - t0 ))
    done_both
    echo "  receipt: $r"
    echo "  $n small files + 80 MB in ${secs} s (pairing to receipt)"
    "$ADB" -s "$T" logcat -d | grep -oE '(copy phases|Copying): .*' | tail -2 | sed 's/^/  target /'
    "$ADB" -s "$S" logcat -d | grep -oE '(send phases|Sending): .*' | tail -2 | sed 's/^/  source /'
    sh_ "$S" "rm -rf $d"; scan "$S" "$d"
    sh_ "$T" "content delete --uri content://media/external/file --where \"_data LIKE '%/graphite-test/speed/%'\"" >/dev/null 2>&1 || true
}

# --- run -----------------------------------------------------------------------

# In a function so bash reads it all before running: editing the script mid-run is safe.
main() {
    if [ -n "${GRAPHITE_APK:-}" ]; then
        APK=$GRAPHITE_APK
    else
        say "building the debug APK"
        extra=(); [ "$ABI" = x86_64 ] && extra=(-Pgraphite.x86_64=true)
        (cd "$HERE" && ./gradlew -q :app:assembleDebug ${extra[@]+"${extra[@]}"})
        APK="$HERE/app/build/outputs/apk/debug/app-debug.apk"
    fi

    say "emulators ($TARGET_IMAGE as target, $SOURCE_IMAGE as source)"
    avd graphite-target "$TARGET_IMAGE"; avd graphite-source "$SOURCE_IMAGE"
    # The target needs no Wi-Fi (its port is reached over adb forward), and the
    # netsim Wi-Fi forwarder can hang the boot while another emulator holds it.
    "$ADB" start-server >/dev/null 2>&1   # once, before the two boots race to start it
    boot "$T" graphite-target -feature -VirtioWifi,-WiFiPacketStream & tb=$!
    boot "$S" graphite-source & sb=$!
    wait "$tb" || exit 1; wait "$sb" || exit 1
    "$ADB" -s "$T" forward "tcp:$PORT" "tcp:$PORT" >/dev/null
    prepare "$T" target "listen:$PORT"
    prepare "$S" source "10.0.2.2:$PORT"
    sh_ "$S" "rm -rf /sdcard/*/graphite-test"; seed
    clear_records   # the media scenarios run without test records; "records" seeds its own

    for sc in "${SCENARIOS[@]}"; do say "$sc"; "$sc"; echo "  PASS"; done

    say "all passed: ${SCENARIOS[*]}"
    if [ "${KEEP:-0}" != 1 ]; then "$ADB" -s "$T" emu kill >/dev/null 2>&1 || true; "$ADB" -s "$S" emu kill >/dev/null 2>&1 || true; fi
}

main
