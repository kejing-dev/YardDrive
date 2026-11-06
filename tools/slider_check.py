#!/usr/bin/env python3
# coding: utf-8
"""
k4-1 and q4-1, real presses (adb shell input tap, panel pixels from `controls P1`), final APK on emulator-5556.
Every settings slider: change the page's other settings, press the slider at both ends, read `SETTINGS` from the log: the
others must still be as they were. Then the mirror sliders to the four corners, pictures taken where they stand. Ends with pm clear.
  python3 tools/slider_check.py OUT_DIR [--no-corners]
"""
import re, subprocess, sys, time

A = "emulator-5556"
OUT = sys.argv[1]
PKG = "YardDrive:V"


def run(*a):
    return subprocess.run(a, capture_output=True, text=True).stdout


def cmd(c, wait=3.0):
    run("tools/dev.sh", "cmd", c)
    time.sleep(wait)


def logs():
    return run("adb", "-s", A, "logcat", "-d", "-v", "brief", "-s", PKG).splitlines()


def settings():
    cmd("dump", 2)
    for line in reversed(logs()):
        m = re.search(r"SETTINGS Settings\((.*)\)", line)
        if m:
            return dict(kv.split("=", 1) for kv in m.group(1).split(", "))
    raise SystemExit("no SETTINGS")


def controls():
    run("adb", "-s", A, "logcat", "-c")
    cmd("controls P1", 3)
    out = {}
    for line in logs():
        m = re.search(r"CONTROL (\S+) x=(\d+) y=(\d+) w=(\d+) h=(\d+)", line)
        if m:
            out[m.group(1)] = tuple(int(x) for x in m.groups()[1:])
    return out


def tap(x, y, wait=5.0):
    run("adb", "-s", A, "shell", "input", "tap", str(x), str(y))
    time.sleep(wait)


def press(ctl, name, field, want=None):
    """Press until `field` changes (the first tap after a panel change can get lost)."""
    x, y, w, h = ctl[name]
    before = settings()[field]
    for _ in range(3):
        tap(x, y)
        now = settings()[field]
        if now != before:
            return now
    raise SystemExit(f"{name}: {field} did not change")


def end(ctl, name, field, left):
    x, y, w, h = ctl[name]
    px = x - w // 2 + 6 if left else x + w // 2 - 6
    last = settings()[field]
    for _ in range(3):
        tap(px, y)
        now = settings()[field]
        if now != last:
            return now
        last = now
    return last


def shot(name):
    run("tools/dev.sh", "shot", name, OUT)
    time.sleep(1)


PAGE0 = [("set_speed_slider", "speed"), ("set_mirror_out", "mirrorOut"), ("set_mirror_pitch", "mirrorPitch")]
PAGE1 = [("set_volume_slider", "master"), ("set_car_slider", "carVol"), ("set_cue_slider", "cueVol")]
ok = True
rows = []
for page, sliders, switches in ((0, PAGE0, [("sw_hints", "hints"), ("sw_top", "topView"), ("sw_poles", "poles"), ("sw_comfort", "comfort"), ("sw_strict", "strict")]),
                                (1, PAGE1, [("sw_gaze", "gazeDot"), ("set_mute", "muted")])):
    cmd(f"goto S17 page={page}", 5)
    cmd(f"goto S17 page={page}", 5)
    ctl = controls()
    shot(f"slider_p{page}_before")
    for name, field in sliders:
        for sw, f in switches:
            was = settings()[f]
            now = press(ctl, sw, f)
            ctl = controls()  # the mute key changes width, the slider next to it moves
            left = end(ctl, name, field, True)
            s = settings()
            good = s[f] == now
            right = end(ctl, name, field, False)
            s2 = settings()
            good = good and s2[f] == now
            ok = ok and good
            rows.append(f"{name} | {sw} {was} -> {now} | ends {left} / {right} | after: {s2[f]} | {'ok' if good else 'PUT BACK'}")
            print(rows[-1], flush=True)
    shot(f"slider_p{page}_after")
print("ALL OK" if ok else "SOME PUT BACK", flush=True)
if "--no-corners" in sys.argv:
    run("adb", "-s", A, "shell", "pm", "clear", "io.embodit.yarddrive")
    print("settings restored (pm clear); the app is stopped")
    print("DONE", flush=True)
    sys.exit(0)
# The mirrors stay where they are while the sliders move (critic5-B1, profile D35): the view is set first, then the settings
# page, then only the sliders. No `view`, no `goto` that changes state, no re-seat before a picture: those would place the
# panels again and hide a face that did not follow. The camera is turned to the mirror, so P1 is out of sight in the picture.
cmd("view 0", 3)
cmd("goto S17 page=0", 5)
cmd("goto S17 page=0", 5)
ctl = controls()
for mirror, yaw in (("R", "67"), ("L", "-48"), ("I", "35,14")):
    cmd("goto S17 page=0", 3)
    end(ctl, "set_mirror_out", "mirrorOut", False)
    # back to the default through the same event, then look
    cmd("mirror 5,-16", 3)
    cmd("goto S02", 3)
    cmd(f"view {yaw}", 6)
    cmd("goto S17 page=0", 8)
    shot(f"inplace_{mirror}_default")
    for oleft in (True, False):
        for pleft in (True, False):
            o = end(ctl, "set_mirror_out", "mirrorOut", oleft)
            p = end(ctl, "set_mirror_pitch", "mirrorPitch", pleft)
            s = settings()
            how = "press"
            want = ("0" if oleft else "15", "-20" if pleft else "0")
            if (s["mirrorOut"], s["mirrorPitch"]) != want:
                how = "mirror command (the press did not land with P1 out of view)"
                cmd(f"mirror {want[0]},{want[1]}", 3)
                s = settings()
            tag = f"o{s['mirrorOut']}_p{s['mirrorPitch']}"
            time.sleep(10)
            shot(f"inplace_{mirror}_{tag}")
            off = [l.split("MIRROR ")[1] for l in logs() if "DUMP MIRROR" in l][-3:]
            print("in place", mirror, tag, how, "|", "; ".join(off), flush=True)
    cmd("mirror 5,-16", 3)
    cmd("view 0", 3)
# put everything back: the next tool or person starts from the defaults
run("adb", "-s", A, "shell", "pm", "clear", "io.embodit.yarddrive")
print("settings restored (pm clear); the app is stopped")
print("DONE", flush=True)
