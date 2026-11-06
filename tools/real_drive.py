#!/usr/bin/env python3
# coding: utf-8
"""
A whole drive by real presses (adb shell input tap), no debug command from the first page to the end: the proof that the keys
alone drive the car (user on the headset, 2026-11-05). Debug commands are used only BEFORE the pm clear, to read where the
keys are. After that: taps, and reading the log.
  python3 tools/real_drive.py OUT.log en|zh [6|1]
Lesson 6 (default) drives the four segments by the keys; lesson 1 is the yard tour, walked to its result card by its own
keys (Start, Next step).
"""
import re, subprocess, sys, time

A = "emulator-5556"
OUT, LANG = sys.argv[1], sys.argv[2]
LESSON = int(sys.argv[3]) if len(sys.argv) > 3 else 6
out = open(OUT, "w", encoding="utf-8")


def say(*a):
    t = " ".join(str(x) for x in a)
    print(t, flush=True)
    out.write(t + "\n")
    out.flush()


def run(*a):
    return subprocess.run(a, capture_output=True, text=True).stdout


def cmd(c, wait=3.0):
    run("tools/dev.sh", "cmd", c)
    time.sleep(wait)


def logs():
    return run("adb", "-s", A, "logcat", "-d", "-v", "time", "-s", "YardDrive:V").splitlines()


TS = re.compile(r"^\d\d-\d\d (\d\d):(\d\d):(\d\d\.\d+)")


def ts(line):
    """The device's own time of a log line, in seconds (logcat -v time): the lead below is measured on this clock."""
    m = TS.match(line)
    return int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3)) if m else None


def clear():
    run("adb", "-s", A, "logcat", "-c")


def controls(panel):
    clear()
    cmd(f"controls {panel}", 3)
    got = {}
    for line in logs():
        m = re.search(r"CONTROL (\S+) x=(\d+) y=(\d+) w=(\d+) h=(\d+)", line)
        if m:
            got[m.group(1)] = (int(m.group(2)), int(m.group(3)))
    return got


def tap(xy, what):
    run("adb", "-s", A, "shell", "input", "tap", str(xy[0]), str(xy[1]))
    say("TAP", what, xy)


def fresh():
    # an open permission dialog first: force-stopping the app under it makes the next launch fail silently (pico-cli-feedback)
    run("adb", "-s", A, "shell", "am", "force-stop", "com.android.permissioncontroller")
    run("tools/dev.sh", "stop")
    run("adb", "-s", A, "shell", "pm", "clear", "io.embodit.yarddrive")
    # right after pm clear a launch can fail without a word: try until the process is there
    for _ in range(4):
        time.sleep(4)
        run("tools/dev.sh", "launch")
        time.sleep(8)
        if run("adb", "-s", A, "shell", "pidof", "io.embodit.yarddrive").strip():
            break
    time.sleep(7)
    run("adb", "-s", A, "shell", "am", "force-stop", "com.android.permissioncontroller")
    time.sleep(20)


# ---- rehearsal: only to read positions
fresh()
cmd("view 0")
cmd(f"lang {LANG}")
keys = {}
cmd("goto S01", 6); keys.update(controls("P1"))
cmd("goto S02", 6); keys.update(controls("P1"))
cmd("goto S03", 6); keys.update(controls("P1"))
cmd("goto S17 page=1", 6); keys.update(controls("P1"))
cmd("goto S26", 6); keys.update({k: v for k, v in controls("P1").items() if k.startswith("tour")})
cmd("goto S26 step=1", 6); keys.update({k: v for k, v in controls("P2").items() if k in ("tourNext", "skipRear")})
cmd("goto S05 lesson=6 t=2", 6); p2 = controls("P2")
cmd("goto S07 seg=1", 6); p2.update(controls("P2"))
keys.update(p2)
want = ("seated", "card_lessons", "lesson_6", "lesson_1", "tourStart", "tourNext", "skipDemo", "goStop", "gearKey", "fullL", "centre", "fullR")
say("positions", {k: keys.get(k) for k in want})
lesson_key = next((k for k in keys if re.fullmatch(r"(lesson|les|row)_?%d" % LESSON, k)), None)

# ---- from here on: taps and the log only
fresh()
say("after the second clear: pid", run("adb", "-s", A, "shell", "pidof", "io.embodit.yarddrive").strip(), [l.strip()[:100] for l in run("adb", "-s", A, "shell", "dumpsys", "activity", "activities").splitlines() if "topResumedActivity" in l][:1])
clear()


def state():
    for line in reversed(logs()):
        m = re.search(r"STATE (S\d+)", line)
        if m:
            return m.group(1)
    return None


def wait_state(names, secs=25):
    t0 = time.time()
    while time.time() - t0 < secs:
        s = state()
        if s in names:
            return s
        time.sleep(0.5)
    return state()


def until_state(names, xy, what, secs=30):
    """Tap again if the first tap after a panel change got lost."""
    t0 = time.time()
    while time.time() - t0 < secs:
        tap(xy, what)
        s = wait_state(names, 6)
        if s in names:
            return s
    say("STUCK waiting for", names, "at", state())
    for line in logs()[-25:]:
        say(line[:150])
    say(run("adb", "-s", A, "shell", "dumpsys", "activity", "activities")[:0])
    raise SystemExit(1)


say("state at the start", state())
until_state({"S02"}, keys["seated"], "seated", 150)
# the first Seated asks for eye tracking: closed unanswered, as a person might, not answered for them
time.sleep(4)
run("adb", "-s", A, "shell", "am", "force-stop", "com.android.permissioncontroller")
time.sleep(4)
if LANG == "zh":
    until_state({"S17"}, keys["settings"], "settings")
    time.sleep(3)
    tap(keys["setPage_1"], "system page")
    time.sleep(5)
    tap(keys["lang_0"], "中文")
    time.sleep(5)
    until_state({"S02"}, keys["back"], "back")
    time.sleep(3)
until_state({"S03"}, keys["card_lessons"], "lessons")
time.sleep(3)
say("lesson key", lesson_key)


def events(name):
    return sum(1 for l in logs() if ("EVENT " + name) in l)


if LESSON == 1:
    # the yard tour: Start on P1, then Next step on P2 until the result card (S12), every press a real tap
    until_state({"S26"}, keys[lesson_key], "lesson 1")
    time.sleep(3)
    n0 = events("TourStart")
    for _ in range(6):
        tap(keys["tourStart"], "Start the tour")
        time.sleep(4)
        if events("TourStart") > n0:
            break
    for i in range(40):
        if state() == "S12":
            break
        n = events("TourNext")
        tap(keys["tourNext"], f"Next step {i + 1}")
        t0 = time.time()
        while time.time() - t0 < 8 and events("TourNext") == n:
            time.sleep(0.5)
        time.sleep(2)
    say("tour ended at", state())
    say("--- the app's own log of this tour")
    for line in logs():
        if re.search(r"EVENT|STATE|JUDGE|RESULT|PERF", line):
            out.write(line[:170] + "\n")
    out.close()
    print("DONE")
    raise SystemExit(0)
until_state({"S05", "S06", "S07"}, keys[lesson_key], "lesson 6")
if state() == "S05":
    time.sleep(3)
    until_state({"S06", "S07"}, keys["skipDemo"], "skip the demo")
time.sleep(3)


DRIVE = re.compile(r"DRIVE x=(\S+) z=(\S+) psi=(\S+) wheel=(\S+) v=(\S+) gear=(\S+) phase=(\S+) seg=(\S+) go=(\S+)")


def parse(line):
    m = DRIVE.search(line)
    if not m:
        return None
    g = m.groups()
    return dict(t=ts(line), x=float(g[0]), z=float(g[1]), psi=float(g[2]), wheel=float(g[3]), v=float(g[4]), gear=g[5], phase=g[6], seg=g[7], go=g[8])


def drives(lines=None):
    return [d for d in (parse(l) for l in (lines if lines is not None else logs())) if d]


def drive():
    ds = drives()
    return ds[-1] if ds else None


def rates(ds):
    """Metres and degrees per second of device time, over the last second of DRIVE lines (one every 200 ms of app time)."""
    if len(ds) < 2 or ds[-1]["t"] is None:
        return 0.0, 0.0
    last = ds[-1]
    first = next((d for d in ds if d["t"] is not None and last["t"] - d["t"] <= 1.2), ds[-2])
    if first is last:
        first = ds[-2]
    dt = (last["t"] - first["t"]) if first["t"] is not None else 0
    if dt <= 0.05:
        return 0.0, 0.0
    return (abs(last["x"] - first["x"]) + abs(last["z"] - first["z"])) / dt, abs(last["psi"] - first["psi"]) / dt


# How long from the log line a decision is read from to the tap's effect on the car, device time: measured after every
# tap (decision line -> the app's EVENT line) plus one frame (the next tick applies it). v0.1.6 wrote 1.0 s here, right at
# 1 fps but 9 degrees early at 40 fps (QA round 7); this follows whatever the emulator does right now.
LAT = [0.8]
FRAME = [0.1]


def measure(name, t_decision, secs=6):
    """Only taps made while the car moves: then the decision line is fresh (DRIVE lines stop while the car stands)."""
    t0 = time.time()
    while time.time() - t0 < secs and t_decision is not None:
        lines = logs()
        for line in lines:
            t = ts(line)
            if t is not None and t > t_decision and ("EVENT " + name) in line:
                ds = [d for d in drives(lines) if d["t"] is not None]
                if len(ds) >= 6:
                    gaps = sorted(b["t"] - a["t"] for a, b in zip(ds[-6:-1], ds[-5:]))
                    FRAME[0] = max(0.02, min(1.2, gaps[len(gaps) // 2]))
                sample = t - t_decision + FRAME[0]
                if sample > 2.5:
                    say(f"LATENCY {name} {sample:.2f} s ignored (stale decision line)")
                    return
                LAT[0] = 0.5 * LAT[0] + 0.5 * sample
                say(f"LATENCY {name} {sample:.2f} s (decision -> event {t - t_decision:.2f} s + frame {FRAME[0]:.2f} s), lead uses {LAT[0]:.2f} s")
                return
        time.sleep(0.2)


def wait(cond, what, secs=400):
    """Polls the car's position; [cond] gets the position and how far (m, deg) it moves before a tap read now would land:
    the car's own rate on the device clock times the measured tap latency [LAT], not a fixed second."""
    t0 = time.time()
    while time.time() - t0 < secs:
        ds = drives()
        d = ds[-1] if ds else None
        vm, vdeg = rates(ds)
        lead_m, lead_deg = vm * LAT[0], vdeg * LAT[0]
        if d and cond(d, lead_m, lead_deg):
            say("AT", what, d, f"lead {lead_m:.2f} m {lead_deg:.1f} deg")
            return d
        if d and d["phase"] in ("LINE", "FINISHED"):
            say("ENDED", d)
            return d
        time.sleep(0.1)
    say("TIMEOUT", what, drive())
    return drive()


BRAKE = 0.25  # 0.5 m/s, 1 s
SEGS = {
    "I": dict(gear="R", lock=lambda d, m, a: d["x"] <= 3.45 + m, steer="fullR", straight=lambda d, m, a: d["psi"] >= 88.7 - a, stop=lambda d, m, a: d["z"] >= 3.80 - BRAKE - m),
    "II": dict(gear="D", lock=lambda d, m, a: d["z"] <= 0.79 + m, steer="fullL", straight=lambda d, m, a: d["psi"] >= 178.7 - a, stop=lambda d, m, a: d["x"] <= -5.60 + BRAKE + m),
    "III": dict(gear="R", lock=lambda d, m, a: d["x"] >= -3.45 - m, steer="fullL", straight=lambda d, m, a: d["psi"] <= 91.3 + a, stop=lambda d, m, a: d["z"] >= 3.80 - BRAKE - m),
    "IV": dict(gear="D", lock=lambda d, m, a: d["z"] <= 0.79 + m, steer="fullR", straight=lambda d, m, a: abs(d["psi"]) <= 1.3 + a, stop=lambda d, m, a: d["x"] >= 5.60 - BRAKE - m),
}
for seg, s in SEGS.items():
    d = drive()
    if d["phase"] in ("LINE", "FINISHED"):
        break
    if d["gear"] != s["gear"]:
        tap(keys["gearKey"], "R / D")
        wait(lambda d, m, a: d["gear"] == s["gear"], "gear " + s["gear"], 30)
    d = drive()
    tap(keys["goStop"], "Go")
    wait(lambda d, m, a: d["go"] == "true", "going", 30)
    d = wait(s["lock"], "lock point")
    tap(keys[s["steer"]], s["steer"])
    measure("Steer", d["t"])
    wait(lambda d, m, a: abs(d["wheel"]) >= 500, "full lock", 60)
    d = wait(s["straight"], "straighten point")
    tap(keys["centre"], "centre")
    measure("Steer", d["t"])
    d = wait(s["stop"], "stop point")
    tap(keys["goStop"], "Stop")
    measure("GoToggle", d["t"])
    d = wait(lambda d, m, a: d["seg"] != seg or d["phase"] in ("SEG_DONE", "FINISHED"), "segment over", 120)
    if d["phase"] == "LINE":
        break
    time.sleep(4)
say(f"--- tap latency at the end {LAT[0]:.2f} s, frame {FRAME[0]:.3f} s")
say("--- the app's own log of this drive")
for line in logs():
    if re.search(r"EVENT|JUDGE|CUE |GO |RUN start|PERF", line):
        out.write(line[:170] + "\n")
out.close()
print("DONE")
