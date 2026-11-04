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
#   removal - the source moves verified files to its trash
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
if [ $# -gt 0 ]; then SCENARIOS=("$@"); else SCENARIOS=(keep again strip display resume space battery removal); fi

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
    # The default 2 GB hangs and crashes recent images while booting.
    sed -i.bak 's/^hw.ramSize=.*/hw.ramSize=4096/' "$ANDROID_AVD_HOME/$1.avd/config.ini"
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
    sh_ "$1" "svc power stayon true; settings put system screen_off_timeout 1800000; input keyevent 82" >/dev/null
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
    for try in 1 2 3 4 5; do "$ADB" -s "$1" install -r -g "$APK" >/dev/null && break; [ $try = 5 ] && exit 1; sleep 10; done
    sh_ "$1" "locksettings set-pin $PIN" >/dev/null 2>&1 || true   # BiometricPrompt then asks for the PIN
    sh_ "$1" "appops set $PKG MANAGE_EXTERNAL_STORAGE allow"
    sh_ "$1" "pm grant $PKG android.permission.POST_NOTIFICATIONS" >/dev/null 2>&1 || true   # API 33+
    sh_ "$1" "am force-stop $PKG; am start -n $PKG/.MainActivity --es test_role $2 --es test_tcp $3 --es test_scope graphite-test" >/dev/null
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

# Either phone may be asking while the script waits on the other.
auth_both() { auth "$T"; auth "$S"; }

# The user-presence prompt: with only a PIN set, BiometricPrompt asks for it.
auth() {
    case "$(focus "$1")" in
        *BiometricPrompt*|*ConfirmDeviceCredential*|*Bouncer*|*Keyguard*)
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

# --- one transfer --------------------------------------------------------------

# transfer CHOICE [HOOK]: pairs, chooses, copies; HOOK runs once the copy starts.
# Leaves the source on its "Copy verified" screen and prints the receipt line.
transfer() {
    # Keep each transfer's logs (in $LOG) before clearing them for this one.
    "$ADB" -s "$S" logcat -d -v time >>"$LOG/source.logcat"; "$ADB" -s "$T" logcat -d -v time >>"$LOG/target.logcat"
    sh_ "$S" "logcat -c"; sh_ "$T" "logcat -c"
    sh_ "$T" "am start -n $PKG/.MainActivity" >/dev/null; sh_ "$S" "am start -n $PKG/.MainActivity" >/dev/null
    waitfor "$T" "Compare the codes"; waitfor "$S" "Compare the codes"
    local ct cs
    ct=$(texts "$T" | grep -E '^[0-9]{3} [0-9]{3}$'); cs=$(texts "$S" | grep -E '^[0-9]{3} [0-9]{3}$')
    [ -n "$ct" ] && [ "$ct" = "$cs" ] || fail "codes differ: '$ct' vs '$cs'"
    tap "$T" "They match"; tap "$S" "They match"; tap "$T" "Accept"
    tap "$T" "$1"; tap "$T" "Continue"
    if [ "${2:-}" = space ] || [ "${2:-}" = battery ]; then return 0; fi
    tap "$T" "Continue" 600; tap "$T" "Start copy"
    if [ -n "${2:-}" ]; then "$2"; fi
    waitfor "$T" "Copy finished" 1200
    waitfor "$S" "Copy verified" 120
    "$ADB" -s "$S" logcat -d | grep -o 'receipt verified: .*' | tail -1 | sed 's/^receipt verified: //'
}

done_both() { tap "$T" "Done"; tap "$S" "Done"; trytap "$S" "Keep them" 10 || true; }

expect() {  # expect RECEIPT_LINE PATTERN
    echo "  receipt: $1"; echo "$1" | grep -qE "$2" || fail "receipt does not match /$2/"
}

cut_socket() {  # the emulators' "cable pull": reset the source's connection mid-copy, then let it back
    # (removing the adb forward would leave the open socket alone). The source is rooted (API 30, see boot).
    waitfor "$T" "gt-big" 120; sleep 3
    sh_ "$S" "iptables -I OUTPUT -p tcp --dport $PORT -j REJECT --reject-with tcp-reset"; sleep 5
    sh_ "$S" "iptables -D OUTPUT -p tcp --dport $PORT -j REJECT --reject-with tcp-reset"
}

# --- scenarios -----------------------------------------------------------------

keep() {
    fresh_target; local r; r=$(transfer "Keep all metadata"); done_both
    expect "$r" "^9 copied and verified, 0 already"   # the duplicate counts as copied; its bytes went once
    [ "$(rows "$T" | grep -c 'gt-gps')" = 1 ] || fail "the duplicate was copied twice"
    local src; src=$(rows "$S" | grep "DCIM/graphite-test/gt-gps.jpg|")
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

    for sc in "${SCENARIOS[@]}"; do say "$sc"; "$sc"; echo "  PASS"; done

    say "all passed: ${SCENARIOS[*]}"
    if [ "${KEEP:-0}" != 1 ]; then "$ADB" -s "$T" emu kill >/dev/null 2>&1 || true; "$ADB" -s "$S" emu kill >/dev/null 2>&1 || true; fi
}

main
