#!/usr/bin/env bash
# Runs Jarvis on a real Android system image and looks for crashes.
#
# Robolectric draws the screens, but it only imitates Android: services,
# alarms, permissions and the real renderer are not in it. This installs the
# debug build on an emulator, walks through the main screens and a couple of
# turns, saves a screenshot of each, and fails if the app crashed or died.
set -u
PKG=com.lukas.jarvis.debug
ACT=com.lukas.jarvis.MainActivity
OUT=../docs/device
mkdir -p "$OUT"
rm -f "$OUT"/*.png
REPORT="$OUT/report.txt"
: > "$REPORT"

shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1" >> "$REPORT"; }

alive() { adb shell pidof "$PKG" >/dev/null 2>&1; }

# Taps the first element whose text or description matches, via uiautomator.
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local xml bounds
  xml=$(adb shell cat /sdcard/ui.xml 2>/dev/null)
  bounds=$(echo "$xml" | grep -o "<node[^>]*\(text\|content-desc\)=\"$1\"[^>]*>" | head -n1 | grep -o 'bounds="[^"]*"' | head -n1)
  if [ -z "$bounds" ]; then echo "no '$1' on screen" >> "$REPORT"; return 1; fi
  local nums
  nums=$(echo "$bounds" | grep -o '[0-9]\+' | tr '\n' ' ')
  set -- $nums
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

# adb shell joins its arguments into one command line for the phone's
# shell, so text with spaces is passed wrapped in single quotes.
start() { adb shell am start -W -n "$PKG/$ACT" "$@" >/dev/null; }

adb install -r -g app/build/outputs/apk/debug/app-debug.apk || { echo "install failed" >> "$REPORT"; exit 1; }
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || true
adb logcat -c

start
sleep 12
shot 01-first-open
tap_text "Skip" && sleep 3
shot 02-after-onboarding

start -a com.lukas.jarvis.TODAY
sleep 10
shot 03-today

start -a com.lukas.jarvis.TYPE
sleep 4
shot 04-type

# A turn that needs no model: answered by the phone itself.
start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "'set a timer for 5 minutes'"
sleep 12
shot 05-shared-timer

# A turn through the free models, when they answer.
start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "'What is the capital of Australia? One word.'"
sleep 40
shot 06-live-question

# Every main screen, through the bar at the bottom as a person would.
for tab in "Map" "Notes" "Settings" "Today"; do
  tap_text "$tab" && sleep 5
  shot "09-$(echo "$tab" | tr 'A-Z' 'a-z')"
done
tap_text "Notes" && sleep 3
for hub in "Trackers" "Tasks" "Lists" "Memory"; do
  tap_text "$hub" && sleep 3
  shot "10-hub-$(echo "$hub" | tr 'A-Z' 'a-z')"
done
tap_text "Settings" && sleep 3
for tab in "You" "Voice" "Look" "Brain" "Powers" "Data"; do
  tap_text "$tab" && sleep 3
  shot "11-settings-$(echo "$tab" | tr 'A-Z' 'a-z')"
done
tap_text "Today" && sleep 3
tap_text "Skills" && sleep 4
shot 12-skills

adb shell input keyevent KEYCODE_HOME
sleep 3
start -a com.lukas.jarvis.TALK
sleep 6
shot 07-talk

# Rotation recreates the activity, which is where restored state goes wrong.
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 5
shot 08-landscape
adb shell settings put system user_rotation 0
sleep 3

adb logcat -d > "$OUT/logcat.txt"
CRASH=$(grep -E "FATAL EXCEPTION|ANR in $PKG|Process: $PKG" "$OUT/logcat.txt" | head -n 20)
if [ -n "$CRASH" ]; then
  echo "CRASH:" >> "$REPORT"
  echo "$CRASH" >> "$REPORT"
  grep -A 30 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -n 80 >> "$REPORT"
fi
if ! alive; then echo "app not running at the end" >> "$REPORT"; fi
# Only the lines about the app are worth keeping in the repository.
grep -E "$PKG|AndroidRuntime|jarvis" "$OUT/logcat.txt" | tail -n 400 > "$OUT/logcat-app.txt"
rm -f "$OUT/logcat.txt"
cat "$REPORT"
if [ -n "$CRASH" ] || ! alive; then exit 1; fi
echo "No crash on the device." >> "$REPORT"
