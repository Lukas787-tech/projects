#!/usr/bin/env bash
# Boots the game on an emulator, drives it around the valley in both axes and
# captures a screenshot of each screen, then fails if anything crashed.
set -uo pipefail

SHOTS=cozy-cabin/screenshots
mkdir -p "$SHOTS"

blank=0
shot() {
  adb exec-out screencap -p > "$SHOTS/$1"
  local bytes
  bytes=$(stat -c%s "$SHOTS/$1")
  echo "captured $1 (${bytes} bytes)"
  # a real frame of this game compresses to 30-200 KB; a blank one to about 5 KB
  if [ "$bytes" -lt 15000 ]; then
    echo "::error::$1 is blank ($bytes bytes) - the game drew nothing"
    blank=$((blank + 1))
  fi
}

# The floating stick drops wherever you press, so a swipe = press then lean.
walk() { adb shell input swipe "$1" "$2" "$3" "$4" "${5:-2500}"; }
walk_right() { walk 300 560 760 560 "${1:-2500}"; }
walk_left()  { walk 700 560 240 560 "${1:-2500}"; }
walk_near()  { walk 300 470 300 700 "${1:-1600}"; }   # toward the camera
walk_far()   { walk 300 620 300 380 "${1:-1600}"; }   # into the woods

fail=0

echo "--- display info ---"
adb shell wm size

echo "--- install ---"
adb install -r -t out/RiversideHollow.apk || { echo "install failed"; exit 1; }

adb logcat -c
adb shell am start -W -n com.cozyhollow.riverside/.MainActivity
sleep 14
shot 01-title.png

adb shell input tap 640 366     # Begin
sleep 3
shot 02-letter.png

adb shell input tap 640 581     # Let's begin
sleep 4
shot 03-morning.png

# --- walk in depth, which is the whole point of the layout ---
walk_near 1500
sleep 1
shot 04-depth.png

# --- over to the field, then work a plot ---
# the camera starts facing north up the valley, so right is east: the field
# sits about 11 m that way and a couple of metres toward the camera
walk_right 3200
sleep 1
walk_near 800
sleep 1
adb shell input tap 1162 583   # till
sleep 2
adb shell input tap 1162 583   # plant
sleep 2
adb shell input tap 1162 583   # water
sleep 2
shot 05-field.png

# --- backpack ---
adb shell input tap 1161 425
sleep 2
shot 06-backpack.png
adb shell input keyevent 4
sleep 2

# --- pause menu ---
adb shell input tap 1197 83
sleep 2
shot 07-pause.png
adb shell input keyevent 4
sleep 2

# --- on to the market and the river ---
for _ in 1 2 3 4 5; do walk_right 2600; done
sleep 1
shot 08-river.png

adb shell input tap 1162 583   # fish / shop, whatever is in reach
sleep 3
shot 09-action.png

sleep 6
shot 10-later.png

echo "--- process check ---"
if ! adb shell pidof com.cozyhollow.riverside > /dev/null; then
  echo "::error::the game process is no longer running"
  fail=1
fi

if [ "$blank" -gt 0 ]; then
  echo "::error::$blank screenshot(s) came back blank"
  fail=1
fi

echo "--- crash check ---"
adb logcat -d > logcat.txt
# an exception inside the render loop is swallowed by the GL thread's try/catch,
# so it never becomes a FATAL EXCEPTION - it just silently stops drawing
if grep -q "Riverside: frame failed\|Riverside: GL setup failed\|Riverside: GL resize failed" logcat.txt; then
  echo "::error::the render loop threw"
  grep -A 12 "Riverside: frame failed\|Riverside: GL setup failed" logcat.txt | head -40
  fail=1
fi
if grep -q "FATAL EXCEPTION" logcat.txt; then
  echo "::error::FATAL EXCEPTION in logcat"
  grep -A 40 "FATAL EXCEPTION" logcat.txt | head -80
  fail=1
fi
if grep -qi "ANR in com.cozyhollow" logcat.txt; then
  echo "::error::ANR detected"
  fail=1
fi

echo "--- our own log lines ---"
grep -iE "Riverside" logcat.txt | head -30 || true

# Keep a trimmed copy in the screenshots folder, which the workflow commits.
# Pulling the branch is a far more reliable way to read a stack trace than
# digging it out of the tail of a GitHub Actions log.
{
  echo "run: $(date -u +%FT%TZ)"
  echo
  grep -E "Riverside|FATAL EXCEPTION|AndroidRuntime|ANR in" logcat.txt | head -150
} > "$SHOTS/_logcat.txt" 2>/dev/null || true

exit $fail
