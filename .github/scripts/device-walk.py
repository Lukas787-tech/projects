#!/usr/bin/env python3
"""
Taps every button on every screen of Mochi on a real Android system image and
writes down each one that takes the app down or throws the person out of it.

The smoke run walks a fixed path; this one walks everything it can see. On
each screen it reads what is tappable from uiautomator, taps each thing once,
and after every tap checks three things: did the app crash (the crash log
buffer), did it die without a crash, and is it still in front. Scrolling
down until nothing new appears reaches the bottom of every list. A crash is
written with its stack trace, the app is started again, and the walk goes on,
so one run finds every broken button, not just the first.

The hardware back button is tried on every room too: back should lead to the
canvas, never out of the app.

Usage: device-walk.py OUT_DIR   (run from jarvis-android, with the debug APK built)
"""
import base64
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.lukas.jarvis.debug"
ACT = "com.lukas.jarvis.MainActivity"
OUT = sys.argv[1] if len(sys.argv) > 1 else "../docs/device-walk"
os.makedirs(OUT, exist_ok=True)
REPORT = open(os.path.join(OUT, "walk.txt"), "w", buffering=1)
CRASHES = open(os.path.join(OUT, "crashes.txt"), "w", buffering=1)
problems = []


def say(line):
    print(line, flush=True)
    REPORT.write(line + "\n")


def adb(*args, timeout=60):
    try:
        return subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout).stdout
    except subprocess.TimeoutExpired:
        return ""


def shell(command, timeout=60):
    return adb("shell", command, timeout=timeout)


def alive():
    return shell(f"pidof {PKG}").strip() != ""


def focused():
    """The window in front, as dumpsys names it."""
    for line in shell("dumpsys window | grep mCurrentFocus").splitlines():
        if "mCurrentFocus" in line:
            return line.strip()
    return ""


def crash_text():
    return adb("logcat", "-b", "crash", "-d")


def clear_crashes():
    adb("logcat", "-b", "crash", "-c")


def keyboard_up():
    return "mInputShown=true" in shell("dumpsys input_method | grep mInputShown")


def start():
    shell(f"am start -W -n {PKG}/{ACT}", timeout=90)
    time.sleep(2)


dump_errors = set()


def dump():
    for _ in range(5):
        out = shell("uiautomator dump /sdcard/ui.xml 2>&1", timeout=40)
        if "dumped" in out.lower():
            raw = subprocess.run(["adb", "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True, timeout=60).stdout
            # Text on screen can carry characters XML 1.0 does not allow;
            # they are dropped rather than losing the whole screen.
            xml = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", "", raw.decode("utf-8", "replace"))
            try:
                return ET.fromstring(xml)
            except ET.ParseError as e:
                if "parse" not in dump_errors:
                    dump_errors.add("parse")
                    say(f"  unreadable dump: {e}")
                    with open(os.path.join(OUT, "unreadable-dump.xml"), "w") as f:
                        f.write(xml)
        elif out.strip() not in dump_errors:
            # Said once: uiautomator refusing to read the screen is a problem
            # with the walk, not with the app, and needs telling apart.
            dump_errors.add(out.strip())
            say(f"  uiautomator: {out.strip()[:300]}")
        time.sleep(1)
    return None


shots = 0


def picture(name):
    """A screenshot beside the report, for whatever the text alone can't explain."""
    global shots
    shots += 1
    if shots > 40:
        return
    with open(os.path.join(OUT, f"{shots:02d}-{name}.png"), "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout)


BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def words(node):
    """What a person would call a node: its own words, else its children's."""
    own = (node.get("content-desc") or "").strip() or (node.get("text") or "").strip()
    if own:
        return own
    for child in node.iter("node"):
        if child is node:
            continue
        text = (child.get("content-desc") or "").strip() or (child.get("text") or "").strip()
        if text:
            return text
    return ""


def mine(root):
    return [n for n in root.iter("node") if n.get("package") == PKG]


def tappables(root):
    found = []
    for node in mine(root):
        if node.get("clickable") != "true" and node.get("checkable") != "true" and node.get("long-clickable") != "true":
            continue
        if node.get("enabled") == "false":
            continue
        match = BOUNDS.match(node.get("bounds") or "")
        if not match:
            continue
        x1, y1, x2, y2 = map(int, match.groups())
        if x2 - x1 < 4 or y2 - y1 < 4:
            continue
        found.append({
            "label": words(node) or f"<unnamed {node.get('class')}>",
            "x": (x1 + x2) // 2,
            "y": (y1 + y2) // 2,
            "edit": "EditText" in (node.get("class") or ""),
        })
    return found


def has(root, label, selected=None):
    if root is None:
        return False
    for node in mine(root):
        if words(node) == label or (node.get("content-desc") or "") == label or (node.get("text") or "") == label:
            if selected is None or node.get("selected") == ("true" if selected else "false"):
                return True
    return False


def tap_label(label, root=None):
    root = root if root is not None else dump()
    if root is None:
        return False
    for node in mine(root):
        if words(node) == label or (node.get("content-desc") or "") == label or (node.get("text") or "") == label:
            match = BOUNDS.match(node.get("bounds") or "")
            if match:
                x1, y1, x2, y2 = map(int, match.groups())
                shell(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
                time.sleep(1.5)
                return True
    return False


SIZE = re.findall(r"(\d+)x(\d+)", shell("wm size"))
W, H = (int(SIZE[-1][0]), int(SIZE[-1][1])) if SIZE else (1080, 2400)


def scroll_down():
    shell(f"input swipe {W // 2} {int(H * 0.72)} {W // 2} {int(H * 0.32)} 400")
    time.sleep(1.2)


CANVAS_MARK = "You and settings"


def to_canvas():
    """Back to the canvas from wherever the walk ended up, starting the app if it has to."""
    for _ in range(8):
        if not alive():
            start()
        root = dump()
        if root is None or not mine(root):
            start()
            continue
        if has(root, "Skip"):
            tap_label("Skip", root)
            continue
        if has(root, CANVAS_MARK):
            return True
        if keyboard_up():
            shell("input keyevent KEYCODE_BACK")
            time.sleep(0.8)
            continue
        # A room: its own back arrow leads to the canvas.
        if not tap_label("Back to talking", root):
            shell("input keyevent KEYCODE_BACK")
            time.sleep(1.2)
    root = dump()
    if root is not None:
        seen = sorted({words(n) for n in mine(root) if words(n)})[:30]
        say(f"  on screen instead: {seen}")
    return False


# Each screen: how to reach it from the canvas, and which room in the bar at
# the bottom lights up while it shows (the bar never scrolls away, so it is the
# one mark that holds at every scroll position).
LIBRARY = "Library: memories, lists, money and tasks"
SCREENS = [
    ("canvas", [], CANVAS_MARK),
    ("today", ["Today"], "Today"),
    ("library-memory", [LIBRARY, "Memory"], "Library"),
    ("library-notes", [LIBRARY, "Notes"], "Library"),
    ("library-lists", [LIBRARY, "Lists"], "Library"),
    ("library-money", [LIBRARY, "Money"], "Library"),
    ("library-tasks", [LIBRARY, "Tasks"], "Library"),
    ("you-you", ["You and settings", "You"], "You"),
    ("you-voice", ["You and settings", "Voice"], "You"),
    ("you-look", ["You and settings", "Look"], "You"),
    ("you-brain", ["You and settings", "Brain"], "You"),
    ("you-powers", ["You and settings", "Powers"], "You"),
    ("you-data", ["You and settings", "Your data"], "You"),
    ("powers-room", ["You and settings", "Powers", "See what each one can do"], "Today"),
    ("map", ["Today", "Map"], "Map"),
]

# Taps that only move between screens: each is exercised by the walk's own
# navigation, and tapping it mid-walk would only leave the screen being walked.
MOVES = {
    "Skip", "Back to talking", "Talk", "Today", "Library", "Map", "You",
    "Memory", "Notes", "Lists", "Money", "Tasks",
    "Voice", "Look", "Brain", "Powers", "Your data",
}


def go(screen, scrolls=0):
    name, path, _ = screen
    if not to_canvas():
        say(f"  [{name}] could not get back to the canvas (in front: {focused()[:160]})")
        picture(f"{name}-no-canvas")
        return False
    for label in path:
        found = False
        for _ in range(4):
            if tap_label(label):
                found = True
                break
            scroll_down()
        if not found:
            say(f"  [{name}] no '{label}' on screen")
            picture(f"{name}-no-{re.sub(r'[^a-z]+', '-', label.lower())[:20]}")
            return False
    for _ in range(scrolls):
        scroll_down()
    return True


def showing(screen, root):
    _, _, mark = screen
    if root is None:
        return False
    if mark == CANVAS_MARK:
        return has(root, CANVAS_MARK)
    return not has(root, CANVAS_MARK) and has(root, mark, True)


def check(where, label):
    """After a tap: crashed, died, or thrown out? True when the app is fine."""
    time.sleep(1.8)
    crash = crash_text()
    if PKG in crash and "FATAL EXCEPTION" in crash:
        problems.append(f"CRASH  {where}: '{label}'")
        say(f"  CRASH after '{label}'")
        for line in crash.splitlines():
            if "Exception" in line or "Error" in line or "com.lukas" in line or "Caused by" in line:
                say("    " + line.strip()[:240])
        CRASHES.write(f"===== {where}: tapped '{label}'\n{crash}\n")
        clear_crashes()
        shell(f"am force-stop {PKG}")
        start()
        return False
    if not alive():
        problems.append(f"DIED   {where}: '{label}' (no crash log)")
        say(f"  DIED after '{label}'")
        start()
        return False
    front = focused()
    if PKG not in front:
        other = re.search(r"mCurrentFocus=Window\{\S+ \S+ ([^ }]+)", front)
        other = other.group(1) if other else front.strip()[:120]
        # Leaving for a system page or a file picker is what some buttons are for.
        launcher = "launcher" in other.lower()
        if launcher:
            problems.append(f"LEFT   {where}: '{label}' put the phone back on the home screen")
            say(f"  LEFT THE APP after '{label}'")
        else:
            say(f"  opened {other}")
        start()
        return False
    return True


def walk(screen):
    name = screen[0]
    say(f"== {name}")
    if not go(screen):
        problems.append(f"UNREACHABLE {name}")
        return
    root = dump()
    if not showing(screen, root):
        problems.append(f"UNREACHABLE {name}: its marker is not on screen after navigating")
        say(f"  [{name}] marker not showing")
        return
    tested = set()
    scrolls = 0
    taps = 0
    stuck = 0
    while taps < 90 and scrolls < 14:
        root = dump()
        if not showing(screen, root):
            stuck += 1
            if stuck > 6 or not go(screen, scrolls):
                say(f"  [{name}] lost the screen; moving on")
                break
            continue
        todo = [t for t in tappables(root) if t["label"] not in tested and t["label"] not in MOVES]
        if not todo:
            before = sorted(t["label"] for t in tappables(root))
            scroll_down()
            scrolls += 1
            after_root = dump()
            after = sorted(t["label"] for t in tappables(after_root)) if after_root is not None else before
            if after == before:
                break
            continue
        target = todo[0]
        tested.add(target["label"])
        taps += 1
        say(f"  tap '{target['label']}'")
        shell(f"input tap {target['x']} {target['y']}")
        ok = check(name, target["label"])
        if ok and keyboard_up():
            shell("input keyevent KEYCODE_BACK")
            time.sleep(0.8)
        if not ok:
            go(screen, scrolls)
    say(f"  {taps} taps on {name}")


def back_button(screen):
    name = screen[0]
    if name == "canvas":
        return
    if not go(screen):
        return
    shell("input keyevent KEYCODE_BACK")
    time.sleep(1.5)
    if not check(name, "the phone's back button"):
        return
    root = dump()
    if not has(root, CANVAS_MARK):
        say(f"  back on {name} did not reach the canvas")


# Past the introduction, and with motion reduced: Mochi's idle loop redraws the
# screen many times a second, and uiautomator only reads a screen that has
# gone still. Everything else is the app as installed.
PREFS = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="onboarded" value="true" />
    <boolean name="reduce_motion" value="true" />
    <boolean name="character_idle" value="false" />
    <boolean name="character_delights" value="false" />
</map>
"""


def main():
    apk = "app/build/outputs/apk/debug/app-debug.apk"
    say(adb("install", "-r", "-g", apk, timeout=300).strip())
    shell(f"appops set {PKG} SYSTEM_ALERT_WINDOW allow")
    shell(f"am force-stop {PKG}")
    encoded = base64.b64encode(PREFS.encode()).decode()
    wrote = shell(f"run-as {PKG} sh -c 'mkdir -p shared_prefs && echo {encoded} | base64 -d > shared_prefs/jarvis_settings.xml' 2>&1")
    stored = shell(f"run-as {PKG} cat shared_prefs/jarvis_settings.xml 2>&1")
    say("settings written: " + ("yes" if stored.count('value="true"') == 4 else f"no ({wrote.strip()[:160]} / {stored.strip()[:160]})"))
    adb("logcat", "-c")
    clear_crashes()
    start()
    time.sleep(6)
    # The same, through the debug build's own hook, for when the file above
    # did not take: it changes the running app's settings directly.
    say("calm: " + adb("shell", "am", "broadcast", "-n", f"{PKG}/com.lukas.jarvis.debug.TestHooks",
                       "-a", "com.lukas.jarvis.debug.CALM").strip().splitlines()[-1][:160])
    time.sleep(3)
    picture("first-open")
    say(f"in front: {focused()[:160]}")
    to_canvas()
    picture("canvas")
    for screen in SCREENS:
        walk(screen)
    say("== the back button in every room")
    for screen in SCREENS:
        back_button(screen)
    # The same rooms after a rotation, which recreates the activity.
    shell("settings put system accelerometer_rotation 0")
    shell("settings put system user_rotation 1")
    time.sleep(3)
    say("== landscape")
    for screen in [s for s in SCREENS if s[0] in ("today", "you-you", "library-memory")]:
        if go(screen):
            check(screen[0] + " (landscape)", "opening it")
    shell("settings put system user_rotation 0")

    logcat = adb("logcat", "-d", timeout=120)
    with open(os.path.join(OUT, "logcat-app.txt"), "w") as f:
        f.write("\n".join(line for line in logcat.splitlines() if PKG in line or "AndroidRuntime" in line or "jarvis" in line.lower())[-400000:])
    say("")
    say("===== RESULT")
    if problems:
        for p in problems:
            say(p)
    else:
        say("Every tap kept the app open.")
    REPORT.close()
    CRASHES.close()
    with open(os.path.join(OUT, "crashes.txt")) as f:
        text = f.read()
    if text:
        print("===== CRASH TRACES")
        # The interesting lines only: the exception and the app's own frames.
        for line in text.splitlines():
            if line.startswith("=====") or "Exception" in line or "Error" in line or "com.lukas" in line or "Caused by" in line:
                print(line)
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
