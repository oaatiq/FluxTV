#!/usr/bin/env bash
#
# Power off / on acceptance test for FluxTV, run from a machine on the same network as the TV
# (README -> Acceptance testing). It drives the two standby cases over adb and prints the evidence:
#
#   1. the picture comes back after a wake, instead of sound on a black screen
#   2. FluxTV comes back on its own when another app was left on screen
#
# The remote's power button is a standby, which is what KEYCODE_SLEEP reproduces. A TV that drops
# off the network while suspended cannot be woken over adb at all: the script notices, asks for the
# power button on the remote, and carries on once the box answers again.
#
# Usage:
#   tools/power-cycle-test.sh 192.168.2.135                 # adb over the network
#   tools/power-cycle-test.sh <usb-serial> --install app.apk --grant
#
#   --install <apk>   install (replace) this build first
#   --grant           allow FluxTV to open itself (Android 10+ needs this for case 2)
#   --standby <secs>  how long to stay in standby, default 30

set -u

PKG=com.fluxtv.app
ACTIVITY="$PKG/.MainActivity"
STANDBY=30
APK=
GRANT=0

TARGET=${1:-}
if [ -z "$TARGET" ]; then
    awk 'NR > 2 { if (!/^#/) exit; sub(/^# ?/, ""); print }' "$0"
    exit 2
fi
shift

while [ $# -gt 0 ]; do
    case $1 in
        --install) APK=${2:-}; shift 2 ;;
        --grant)   GRANT=1; shift ;;
        --standby) STANDBY=${2:-30}; shift 2 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

command -v adb >/dev/null 2>&1 || { echo "adb is not on PATH." >&2; exit 2; }

# An address (anything with a dot) is adb over the network; everything else is a USB serial.
case $TARGET in
    *.*) case $TARGET in *:*) ;; *) TARGET="$TARGET:5555" ;; esac ;;
esac
SERIAL=$TARGET

# `timeout` is not on a stock macOS; fall back to gtimeout, then to a watchdog.
tmo() {
    local secs=$1; shift
    if command -v timeout >/dev/null 2>&1; then timeout "$secs" "$@"; return $?; fi
    if command -v gtimeout >/dev/null 2>&1; then gtimeout "$secs" "$@"; return $?; fi
    "$@" &
    local pid=$! rc=0
    ( sleep "$secs"; kill -TERM "$pid" ) >/dev/null 2>&1 &
    local watchdog=$!
    wait "$pid" 2>/dev/null || rc=$?
    kill "$watchdog" >/dev/null 2>&1
    return $rc
}

adbx() { adb -s "$SERIAL" "$@"; }
step() { printf '\n=== %s ===\n' "$*"; }
alive() { [ "$(tmo 10 adb -s "$SERIAL" shell echo ok 2>/dev/null | tr -d '\r\n')" = ok ]; }
on_screen() {
    adbx shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | tr -d '\r' |
        sed -E 's/^.*mCurrentFocus=//; s/^Window\{[^ ]+ [^ ]+ //; s/\}$//'
}
awake() { adbx shell dumpsys power 2>/dev/null | grep -q "mWakefulness=Awake"; }

connect() {
    case $SERIAL in *:*) tmo 10 adb connect "$SERIAL" >/dev/null 2>&1 ;; esac
}

# Reconnects until the box answers, for boxes whose network goes down in standby.
wait_alive() {
    local budget=${1:-180} start=$SECONDS
    while [ $((SECONDS - start)) -lt "$budget" ]; do
        connect
        alive && return 0
        sleep 2
    done
    return 1
}

standby() {
    adbx shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1
    sleep 2
    awake && echo "note: the box still reports itself awake; it may ignore KEYCODE_SLEEP." || true
}

wake() {
    if alive; then
        adbx shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
        sleep 2
        awake || adbx shell input keyevent KEYCODE_POWER >/dev/null 2>&1
    else
        echo ">>> The box is off the network in standby. Press the power button on the remote now."
        wait_alive 180 || { echo "Still unreachable after 3 minutes — giving up on this case."; return 1; }
    fi
    sleep 12
}

history_tail() {
    # Readable with run-as on a debug build only; a release build keeps its files to itself.
    # One JSON event per line; builds before that kept a single JSON array in diagnostics.json.
    local raw
    raw=$(adbx exec-out run-as "$PKG" cat files/diagnostics.jsonl 2>/dev/null)
    [ -z "$raw" ] && raw=$(adbx exec-out run-as "$PKG" cat files/diagnostics.json 2>/dev/null)
    if [ -z "$raw" ]; then
        echo "  (not readable — expected on a release build; use the logcat lines above,"
        echo "   or Settings -> Diagnostics -> History on the TV)"
        return
    fi
    if command -v python3 >/dev/null 2>&1; then
        printf '%s' "$raw" | python3 -c '
import json, sys
text = sys.stdin.read()
try:
    events = json.loads(text) if text.lstrip().startswith("[") else \
        [json.loads(l) for l in text.splitlines() if l.strip().endswith("}")]
except Exception:
    print("  (unreadable JSON)"); sys.exit()
for e in events[-14:]:
    print("  [" + str(e.get("category")) + "] " + str(e.get("message")))
'
    else
        printf '%s\n' "$raw" | tail -c 1200
    fi
}

report() {
    echo "- on screen now: $(on_screen)"
    echo "- FluxTV lifecycle log:"
    adbx logcat -d -s FluxTV:I 2>/dev/null | tail -20 | sed 's/^/  /'
    local blocked
    # The platform names the package it refused; the buffer was cleared before this case, so a
    # match here belongs to this wake.
    blocked=$(adbx logcat -d 2>/dev/null | grep -i "background activity" | grep -F "$PKG" | tail -3)
    if [ -n "$blocked" ]; then
        echo "- Android refused a self-start (grant --grant and retry):"
        printf '%s\n' "$blocked" | sed 's/^/  /'
    fi
    echo "- diagnostics history:"
    history_tail
}

step "Device"
if ! wait_alive 30; then
    echo "Cannot reach $SERIAL — nothing to test. Usual causes:"
    echo "  - the address is wrong, or the box is off or in standby (standby drops adb on most boxes)"
    echo "  - adb over the network is not enabled: Developer options -> ADB over network, and on"
    echo "    many boxes a one-off 'adb tcpip 5555' while plugged in over USB"
    echo "  - another adb client holds the box: try 'adb disconnect' and run again"
    exit 1
fi
for prop in ro.product.model ro.product.board ro.build.version.release ro.build.version.sdk; do
    echo "- $prop = $(adbx shell getprop $prop 2>/dev/null | tr -d '\r')"
done

if [ -n "$APK" ]; then
    step "Install"
    out=$(adbx install -r "$APK" 2>&1)
    printf '%s\n' "$out"
    case $out in
        *INSTALL_FAILED_UPDATE_INCOMPATIBLE*|*signatures\ do\ not\ match*|*INSTALL_FAILED_VERSION_DOWNGRADE*)
            echo
            echo "The box holds a build signed with a different key. Debug and release both use the"
            echo "debug keystore here, so they replace each other fine on one machine — but that"
            echo "keystore is per-machine, so an APK built elsewhere (another laptop, a CI runner)"
            echo "cannot update this install. Either:"
            echo "  - build on the machine that produced the installed APK, or"
            echo "  - adb -s $SERIAL uninstall $PKG   <- this also wipes the playlist and favorites"
            echo "Note the diagnostics history below is only readable over adb on a debug build;"
            echo "on a release build use the logcat lines, which both builds write."
            exit 1 ;;
        *Success*) ;;
        *) echo "Install did not report success — stopping."; exit 1 ;;
    esac
fi

if [ "$GRANT" = 1 ]; then
    step "Allow FluxTV to open itself"
    adbx shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
fi
step "Self-start permission"
op=$(adbx shell appops get "$PKG" SYSTEM_ALERT_WINDOW 2>/dev/null | tr -d '\r' | head -1)
[ -n "$op" ] || op="SYSTEM_ALERT_WINDOW: not set, so the platform default applies"
echo "- $op"
echo "  Case 2 needs this to read 'allow' on Android 10+ (SDK 29 and up). Pass --grant, or use"
echo "  Settings -> Preferences -> Allow FluxTV to open itself on the TV."

step "1/2  Picture comes back after a wake"
adbx shell am start -n "$ACTIVITY" >/dev/null 2>&1
echo "- FluxTV started; giving it 15s to be playing"
sleep 15
adbx logcat -c >/dev/null 2>&1
echo "- into standby for ${STANDBY}s"
standby
sleep "$STANDBY"
wake
report
echo
echo ">>> LOOK AT THE TV: a channel banner and a moving picture = pass."
echo "    Sound with a black screen = the failure this case is testing for."

step "2/2  FluxTV comes back on its own"
adbx shell input keyevent KEYCODE_HOME >/dev/null 2>&1
sleep 3
echo "- left FluxTV, now on screen: $(on_screen)"
adbx logcat -c >/dev/null 2>&1
echo "- into standby for ${STANDBY}s"
standby
sleep "$STANDBY"
wake
report
echo
echo ">>> PASS when FluxTV is on screen by itself, with 'bringing FluxTV to the front'"
echo "    followed by 'FluxTV is on screen' in the log above."
echo "    'did not come up'      = Android refused the start: run again with --grant."
echo "    no [wake] line at all  = the TV never told the app it woke, or the process had been"
echo "                             killed in standby; nothing the app can do about that."
