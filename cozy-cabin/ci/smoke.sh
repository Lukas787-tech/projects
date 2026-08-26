#!/usr/bin/env bash
# Boots the game on an emulator, walks it round the yard and in and out of the
# cabin, captures a screenshot of each screen, then fails if anything crashed.
set -uo pipefail

SHOTS=cozy-cabin/screenshots
mkdir -p "$SHOTS"
# start from a clean set: renumbering the run has more than once left orphans
# from the old numbering sitting in the folder, committed and stale
rm -f "$SHOTS"/*.png

blank=0
shot() {
  adb exec-out screencap -p > "$SHOTS/$1"
  local bytes
  bytes=$(stat -c%s "$SHOTS/$1")
  echo "captured $1 (${bytes} bytes)"
  # a real frame of this game compresses to 30-300 KB; a blank one to about 5 KB
  if [ "$bytes" -lt 15000 ]; then
    echo "::error::$1 is blank ($bytes bytes) - the game drew nothing"
    blank=$((blank + 1))
  fi
}

# The floating stick drops wherever you press, so a swipe = press then lean.
# Everything below stays inside the left 45% of the screen, which is the half
# given over to walking, and clear of the edge-swipe gesture zone.
walk() { adb shell input swipe "$1" "$2" "$3" "$4" "${5:-2500}"; }
walk_right() { walk 300 560 560 560 "${1:-2000}"; }
walk_left()  { walk 380 560 140 560 "${1:-2000}"; }
walk_near()  { walk 300 340 300 620 "${1:-1600}"; }   # toward the camera (+z)
walk_far()   { walk 300 620 300 340 "${1:-1600}"; }   # into the scene (-z)
walk_se()    { walk 300 470 560 620 "${1:-2000}"; }
walk_nw()    { walk 520 660 380 400 "${1:-2000}"; }

# HUD hit points on the 1280x720 skin this runs on (design height 480,
# so the scale is 1.5 and the design width is 853).
ACTION_X=1161; ACTION_Y=583
BAG_X=1161;    BAG_Y=424
MENU_X=1197;   MENU_Y=82

tap() { adb shell input tap "$1" "$2"; }
act() { tap $ACTION_X $ACTION_Y; }

fail=0

echo "--- display info ---"
adb shell wm size

echo "--- install ---"
adb install -r -t out/FrostfallHollow.apk || { echo "install failed"; exit 1; }

adb logcat -c
adb shell am start -W -n com.cozyhollow.riverside/.MainActivity
sleep 16
shot 01-title.png

tap 640 381        # Begin
sleep 3
shot 02-letter.png

tap 640 595        # Let's begin
sleep 4
shot 03-the-yard.png

# a wander east across the yard, past the fire ring
walk_right 2200
sleep 1
shot 04-yard-east.png

# back west and up to the front door, then in
walk_left 2200
sleep 1
walk_far 1400
sleep 1
shot 05-at-the-door.png
act
sleep 4
shot 06-inside.png

# the stove is on the far wall to the west: go up and left, then cook
walk_far 1300
sleep 1
walk_left 1200
sleep 1
shot 07-by-the-stove.png
act
sleep 2
shot 08-cooking.png
tap 640 553        # Close
sleep 2

# the hearth is the other end of the same wall
walk_right 1400
sleep 1
act
sleep 2
shot 09-hearth.png

# backpack and the pause menu, from indoors
tap $BAG_X $BAG_Y
sleep 2
shot 10-backpack.png
tap 640 553
sleep 2

tap $MENU_X $MENU_Y
sleep 2
shot 11-paused.png
tap 640 372        # Journal
sleep 2
shot 12-journal.png
tap 640 597        # Close
sleep 2

tap $MENU_X $MENU_Y
sleep 1
tap 640 559        # Settings
sleep 2
shot 13-settings.png
tap 1002 597       # Back
sleep 1
tap 640 186        # Resume
sleep 2

# back out into the weather
walk_near 1800
sleep 1
act
sleep 4
shot 14-back-outside.png

# a longer walk south-east toward the glasshouse
walk_se 2600
sleep 1
walk_se 2600
sleep 1
shot 15-toward-the-glasshouse.png

echo "--- logcat: crashes ---"
if adb logcat -d -s AndroidRuntime:E | grep -q "FATAL EXCEPTION"; then
  echo "::error::the app crashed"
  adb logcat -d -s AndroidRuntime:E | tail -60
  fail=1
fi

echo "--- logcat: our own error paths ---"
adb logcat -d -s Riverside:E | tail -40 | tee "$SHOTS/_logcat.txt"
if grep -q "GL setup failed" "$SHOTS/_logcat.txt"; then
  echo "::error::the renderer failed to start - see the log above"
  fail=1
fi
if grep -q "frame failed" "$SHOTS/_logcat.txt"; then
  echo "::error::a frame threw - see the log above"
  fail=1
fi

if [ "$blank" -gt 0 ]; then
  echo "::error::$blank screenshot(s) came back blank"
  fail=1
fi

exit $fail
