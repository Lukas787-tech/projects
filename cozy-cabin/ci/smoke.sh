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
walk_near()  { walk 300 340 300 620 "${1:-1600}"; }   # toward the camera
walk_far()   { walk 300 620 300 340 "${1:-1600}"; }   # into the woods
# Crossing the valley on a diagonal, because collision is resolved one axis at
# a time: a walk straight into a bench stops dead, while a diagonal slides off
# it and carries on.
walk_se()    { walk 300 470 760 620 "${1:-2200}"; }
# Mostly north with a little west in it, up the valley to the market. The
# stick reads the swipe's shape, so a wide shallow one walks west with barely
# any north in it - which is how an earlier version of this ended up at the
# pond.
walk_nw()    { walk 520 660 380 400 "${1:-2200}"; }

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

# Where a swipe puts you varies by a metre or two between runs, so press the
# action button through a few positions rather than betting a whole path on
# one guessed spot. Extra presses cost nothing: the button does nothing when
# nothing is in reach.
act() { adb shell input tap 1162 583; sleep 2; }

# --- walk in depth, which is the whole point of the layout ---
# This doubles as the first leg to the market. The spawn is a fixed point and
# the camera starts facing north, so a route measured from here lands where it
# says it will - which routes measured from wherever the last leg finished did
# not: they reached the pond one run and the woods behind the stall the next.
# far enough east to clear the log pile stacked against the cabin's east wall,
# which a walk straight up the valley runs into and stops at
walk_right 2600
sleep 1
walk_right 2600
sleep 1
walk_far 2000
sleep 1
shot 04-depth.png

# --- up the valley to Pip's market, then its four tabs ---
# with an eastward nudge between the long legs: anything he does run into stops
# him dead, and a step sideways is enough to get round it
for _ in 1 2 3 4 5; do walk_far 2200; sleep 1; walk_right 700; sleep 1; done
for _ in 1 2 3 4; do walk_far 1200; sleep 1; act; done
sleep 2
shot 05-shop-seeds.png
adb shell input tap 504 168    # Tools tab
sleep 2
shot 06-shop-tools.png
adb shell input tap 760 168    # Sell tab
sleep 2
shot 07-shop-sell.png
adb shell input tap 1016 168   # Home tab
sleep 2
shot 08-shop-home.png
adb shell input keyevent 4
sleep 2

# --- back down the valley to the field, and work a plot ---
for _ in 1 2 3 4 5 6; do walk_se 2200; sleep 1; done
walk_near 1200
sleep 1
shot 09-field.png
act; act; act
walk_left 900
sleep 1
act; act; act
walk_far 700
sleep 1
act; act; act
shot 10-worked.png

# If the action button turned out to be Sleep rather than Till, the day
# summary is now up and every tap below would land on a modal. Dismissing it
# costs nothing when it is not there: in play that tap just rests the stick.
adb shell input tap 640 564   # where "Good morning" sits
sleep 3

# --- backpack ---
adb shell input tap 1161 425
sleep 2
shot 11-backpack.png
adb shell input keyevent 4
sleep 2

# --- pause menu, and the two screens hanging off it ---
adb shell input tap 1197 83
sleep 2
shot 12-pause.png

adb shell input tap 640 400   # Journal
sleep 2
shot 13-journal.png
adb shell input keyevent 4    # back goes straight to play, not to the menu
sleep 2

adb shell input tap 1197 83
sleep 2
adb shell input tap 640 500   # Settings
sleep 2
shot 14-settings.png
adb shell input keyevent 4
sleep 2

# --- on to the river ---
for _ in 1 2 3 4 5; do walk_right 2600; done
sleep 1
shot 15-river.png

adb shell input tap 1162 583   # fish / shop, whatever is in reach
sleep 3
shot 16-action.png

sleep 6
shot 17-later.png

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
  grep -E "Riverside|FATAL EXCEPTION|AndroidRuntime|ANR in" logcat.txt | head -400
} > "$SHOTS/_logcat.txt" 2>/dev/null || true

exit $fail
