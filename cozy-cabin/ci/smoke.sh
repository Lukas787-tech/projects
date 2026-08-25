#!/usr/bin/env bash
# Boots the game on an emulator, drives the UI through its main screens and
# captures a screenshot of each, then fails if anything crashed.
set -uo pipefail

SHOTS=cozy-cabin/screenshots
mkdir -p "$SHOTS"

shot() {
  adb exec-out screencap -p > "$SHOTS/$1"
  local bytes
  bytes=$(stat -c%s "$SHOTS/$1")
  echo "captured $1 (${bytes} bytes)"
  if [ "$bytes" -lt 5000 ]; then echo "::warning::$1 looks empty"; fi
}

fail=0

echo "--- display info ---"
adb shell wm size
adb shell wm density

echo "--- install ---"
adb install -r -t out/RiversideHollow.apk || { echo "install failed"; exit 1; }

adb logcat -c
adb shell am start -W -n com.cozyhollow.riverside/.MainActivity
sleep 14
shot 01-title.png

# Title: "Begin" / "Continue" is the first button, centred at 40% height
adb shell input tap 640 321
sleep 3
shot 02-letter.png

# Intro letter: "Let's begin"
adb shell input tap 640 565
sleep 4
shot 03-morning.png

# Hold the right walk pad to stroll toward the field
adb shell input swipe 250 628 250 628 4000
sleep 1
shot 04-walking.png

adb shell input swipe 250 628 250 628 4000
sleep 1
shot 05-field.png

# Backpack
adb shell input tap 1208 537
sleep 2
shot 06-backpack.png
adb shell input keyevent 4
sleep 2

# Pause menu
adb shell input tap 1225 55
sleep 2
shot 07-pause.png
adb shell input keyevent 4
sleep 2

# Walk right toward the market and the river
for _ in 1 2 3 4 5; do adb shell input swipe 250 628 250 628 4000; done
sleep 1
shot 08-river.png

# Action button (fish / shop / whatever is in reach)
adb shell input tap 1208 630
sleep 3
shot 09-action.png

sleep 6
shot 10-later.png

echo "--- process check ---"
if ! adb shell pidof com.cozyhollow.riverside > /dev/null; then
  echo "::error::the game process is no longer running"
  fail=1
fi

echo "--- crash check ---"
adb logcat -d > logcat.txt
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
grep -i "cozyhollow\|riverside" logcat.txt | grep -vi "^--------" | head -40 || true

exit $fail
