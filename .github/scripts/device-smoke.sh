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

# The stopwatch, through the model when it answers: its notification is the proof.
start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "'start the stopwatch'"
# It waits behind the timer turn and the free models can be slow: up to a minute.
SW=""
for i in $(seq 1 12); do
  sleep 5
  # The channel is called "Stopwatch" too, so look for the posted notification itself.
  if adb shell dumpsys notification --noredact 2>/dev/null | grep -q "android.title=String (Stopwatch)"; then SW=yes; break; fi
done
shot 05b-stopwatch
if [ -n "$SW" ]; then
  echo "STOPWATCH: running in the notification shade" >> "$REPORT"
else
  echo "STOPWATCH: no notification within 60 s" >> "$REPORT"
fi

# A turn through the free models, when they answer.
start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "'What is the capital of Australia? One word.'"
sleep 40
shot 06-live-question

# Every main screen, through the bar at the bottom as a person would.
for tab in "Map" "Notes" "Settings" "Today"; do
  tap_text "$tab" && sleep 5
  shot "09-$(echo "$tab" | tr 'A-Z' 'a-z')"
done
# The map as a person uses it: hold a finger on it, then make the pin home.
tap_text "Map" && sleep 5
SIZE=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -n1)
W=${SIZE%x*}; H=${SIZE#*x}
adb shell input swipe $((W / 2)) $((H * 2 / 5)) $((W / 2)) $((H * 2 / 5)) 900
sleep 6
shot 09b-map-pin
if tap_text "My home"; then
  sleep 4
  shot 09c-map-home
  if adb shell run-as "$PKG" cat shared_prefs/jarvis_places.xml 2>/dev/null | grep -qi "home"; then
    echo "MAP PIN: saved as home" >> "$REPORT"
  else
    echo "MAP PIN: tapped My home, nothing saved" >> "$REPORT"
  fi
else
  echo "MAP PIN: no My home button after holding the map" >> "$REPORT"
fi
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

# A place reminder on a moving GPS: Android's own proximity alert, which no
# simulated test can exercise. Set through a debug-only hook so no model is
# involved; the result is written down rather than failing the run, since
# the emulator's location service can be slow to wake.
start
sleep 3
adb shell settings put secure location_mode 3 >/dev/null 2>&1 || true
# The emulator's GPS only reports while something has it switched on, and
# Android's fence checks rarely while the phone is far away; the map asks
# for live GPS, so it is kept open while the phone "moves".
tap_text "Map" && sleep 4
for i in 1 2 3 4 5; do adb emu geo fix 13.4050 52.5400 >/dev/null; sleep 4; done
adb shell am broadcast -n "$PKG/com.lukas.jarvis.debug.TestHooks" -a com.lukas.jarvis.debug.PLACE \
  --es text "'buy test milk'" --es lat 52.5200 --es lon 13.4050 >> "$REPORT" 2>&1
for i in 1 2 3; do adb emu geo fix 13.4050 52.5400 >/dev/null; sleep 4; done
FIRED=""
for i in $(seq 1 36); do
  adb emu geo fix 13.4050 52.5200 >/dev/null
  sleep 5
  if adb shell dumpsys notification --noredact 2>/dev/null | grep -qi "buy test milk"; then FIRED=yes; break; fi
done
adb shell cmd statusbar expand-notifications >/dev/null 2>&1; sleep 2
shot 13-place-reminder
adb shell cmd statusbar collapse >/dev/null 2>&1
if [ -n "$FIRED" ]; then echo "PLACE ALERT: fired on arrival" >> "$REPORT"; else echo "PLACE ALERT: did not fire within 180 s" >> "$REPORT"; fi
# What Android's location service holds: the fence, and the fixes it saw.
{
  echo "--- dumpsys location (geofences and providers)"
  adb shell dumpsys location 2>/dev/null | grep -iE -A3 "geofence|proximity|last location|gps provider|fused provider|$PKG" | head -n 120
} >> "$OUT/location.txt"

# A quiet routine: WorkManager runs it in the background, the free model
# answers, and the answer arrives as a notification. The app goes to the
# background first, which is when quiet routines normally run.
adb shell input keyevent KEYCODE_HOME
sleep 2
adb shell am broadcast -n "$PKG/com.lukas.jarvis.debug.TestHooks" -a com.lukas.jarvis.debug.ROUTINE \
  --es name "'device check'" --es step "'What is 2 plus 2? Answer with the number only.'" >> "$REPORT" 2>&1
DONE=""
for i in $(seq 1 24); do
  sleep 5
  if adb shell dumpsys notification --noredact 2>/dev/null | grep -q "Device check"; then DONE=yes; break; fi
done
adb shell cmd statusbar expand-notifications >/dev/null 2>&1; sleep 2
shot 14-quiet-routine
adb shell cmd statusbar collapse >/dev/null 2>&1
if [ -n "$DONE" ]; then echo "QUIET ROUTINE: answered in the background" >> "$REPORT"; else echo "QUIET ROUTINE: no notification within 120 s" >> "$REPORT"; fi
adb shell dumpsys notification --noredact 2>/dev/null | grep -A3 -i "device check" | head -n 12 >> "$REPORT"

# A routine started by arriving somewhere: the place fires, the routine runs
# in the background, and its answer is the notification.
# A force-stop clears the app's notifications, so the check below cannot
# mistake the earlier routine's answer for this one; the app re-arms its
# place alerts when it starts again.
adb shell am force-stop "$PKG"
sleep 2
start
sleep 3
# A routine of its own, so a late answer from the quiet one above cannot pass for it.
adb shell am broadcast -n "$PKG/com.lukas.jarvis.debug.TestHooks" -a com.lukas.jarvis.debug.ROUTINE \
  --es name "'arrival check'" --es step "'What is 3 plus 3? Answer with the number only.'" --ez run false >> "$REPORT" 2>&1
tap_text "Map" && sleep 4
for i in 1 2 3 4; do adb emu geo fix 13.3777 52.5163 >/dev/null; sleep 4; done
adb shell am broadcast -n "$PKG/com.lukas.jarvis.debug.TestHooks" -a com.lukas.jarvis.debug.PLACE \
  --es text "''" --es routine "'arrival check'" --es lat 52.5070 --es lon 13.3900 >> "$REPORT" 2>&1
for i in 1 2 3; do adb emu geo fix 13.3777 52.5163 >/dev/null; sleep 4; done
RAN=""
for i in $(seq 1 36); do
  adb emu geo fix 13.3900 52.5070 >/dev/null
  sleep 5
  if adb shell dumpsys notification --noredact 2>/dev/null | grep -q "Arrival check"; then RAN=yes; break; fi
done
if [ -n "$RAN" ]; then echo "PLACE ROUTINE: ran on arrival" >> "$REPORT"; else echo "PLACE ROUTINE: nothing within 180 s" >> "$REPORT"; fi

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
