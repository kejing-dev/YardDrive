#!/usr/bin/env bash
# D13 pre-QA gate (build.md): every design state in both languages with the widest dynamic values (exam clock 03:29,
# 9 mid-stops, free chip run 5/5 level 3 seed 9999, three-digit score, two-column point table, both settings pages and
# the confirm rows), then prints the OVERFLOW lines (expect none). The emulator renders at about 1 fps: W s per step.
#   tools/fit_sweep.sh [LANGS] [WAIT_S] [LOGFILE]
# v0.1.7: the log is streamed to LOGFILE from the first step (logcat -v time, tag YardDrive) and the OVERFLOW count at the end
# comes from that file: the ring buffer read at the end had rolled over in a 40-minute sweep (QA round 7), so a clean result
# from it proved nothing. Keep LOGFILE outside the factory repo while it is written (its sync replaces tracked files).
cd "$(dirname "$0")/.."
LANGS="${1:-en zh}"; W="${2:-6}"; LOGF="${3:-$PWD/.scratch/fit_sweep_logcat.txt}"
mkdir -p "$(dirname "$LOGF")"
c() { CMD_WAIT=0.3 tools/dev.sh cmd "$1" >/dev/null; sleep "${2:-$W}"; echo "  $1 -> $(tools/dev.sh log 'STATE' 1 | sed 's/.*STATE //' | cut -c1-50)"; }
# from the defaults, one process: pm clear, launch, dismiss the eye permission dialog unanswered; checked again at the end
tools/dev.sh stop >/dev/null 2>&1; adb -s emulator-5556 shell pm clear io.embodit.yarddrive >/dev/null
# right after pm clear a launch can fail without a word (real_drive.py saw it too): try until the process is there, or stop
for i in 1 2 3 4 5; do sleep 4; tools/dev.sh launch >/dev/null 2>&1; sleep 10; [ -n "$(adb -s emulator-5556 shell pidof io.embodit.yarddrive | tr -d '\r')" ] && break; done
[ -z "$(adb -s emulator-5556 shell pidof io.embodit.yarddrive | tr -d '\r')" ] && { echo "== APP DID NOT START, sweep not run"; exit 3; }
sleep 5
adb -s emulator-5556 shell am force-stop com.android.permissioncontroller; sleep 25
PID0=$(adb -s emulator-5556 shell pidof io.embodit.yarddrive | tr -d '\r')
adb -s emulator-5556 logcat -c
adb -s emulator-5556 logcat -v time -s YardDrive:V > "$LOGF" 2>/dev/null &
LOGPID=$!
trap 'kill $LOGPID 2>/dev/null' EXIT
sleep 6
FRAME0=$(tools/dev.sh log 'FRAME n=' 1 | sed -E 's/.*FRAME n=([0-9]+).*/\1/')
for L in $LANGS; do
  echo "== $L"
  c "lang $L"; c "eye off"; c "goto S00"; c "goto S01"; c "goto S02"; c "seed-scores"; c "goto S02"; c "goto S03"
  c "goto S04"; for n in 1 2 3 5 6 8 9 12; do c "goto S04 lp=$n"; done; c "goto S04 lp=2 seat=high"; c "seat mid"
  c "goto S05 lesson=3 t=6"; c "goto S05 lesson=6 t=20 top=1"
  c "goto S06 seg=1"; c "goto S06 seg=2"; c "goto S06 seg=3 rules=exam"
  c "goto S07 seg=1 pose=3.9,-2.65,0"; c "goto S07 seg=1 pose=2.0,-0.5,45 steer=540"; c "goto S07 seg=2 gear=D pose=0.04,0.95,90"
  c "goto S07 seg=3 pose=-1.6,-0.6,135 steer=-540"; c "goto S08 t=1.5"; c "goto S08 t=2.5 rules=exam clock=209"
  c "goto S09 lesson=6" 10; c "goto S10 line=bay-right" 10; c "goto S10 line=bay-left lesson=6" 10; c "goto S11 lesson=6" 14
  for o in pass out notin timeout order; do c "goto S12 outcome=$o"; done; c "result pass page=1"; c "result pass page=2"
  c "goto S13 t=6.5"; c "goto S14 clock=209"; c "goto S15 seed=9999 diff=3" 10; c "goto S16 seed-scores=1"
  c "goto S17"; c "goto S17 page=1"; c "goto S17 confirm=exit"; c "goto S17 about=1"; c "goto S18 lesson=6"; c "resume"
  c "goto S07 seg=1"; c "lost 1"; c "lost 0"; c "goto S20"; c "goto S02"
  c "goto S07 seg=1"; c "goto S23 lesson=2" 10
  c "goto S25 lesson=3" 10; c "goto S26"; c "goto S26 step=1"; c "goto S26 step=2"; c "goto S26 step=3 lp=4"; c "goto S27"
  c "goto S06 lesson=2"; c "goto S02"
  # v0.1.1: re-seat hint on the three pages, settle lines, tour lines, eye note, gear note, seat hint
  c "goto S02"; c "reseat" 2; c "goto S02"; c "reseat" 7; c "goto S17 page=1"; c "reseat" 7; c "goto S18 lesson=6"; c "reseat" 7; c "resume"
  c "goto S08 t=0.5 pose=0.04,3.8,90"; c "goto S08 t=1.5 rules=exam pose=0.04,2.2,90"
  for n in 0 1 2 3 4; do c "goto S26"; c "goto S26 step=2 line=$n"; done
  c "goto S15 seed=4 diff=3" 10; c "goto S15 seed=6 diff=3" 10
  c "script ideal"; c "go 1" 3; c "gear D" 3; c "goto S02"
  c "goto S16 seed-scores=1"; c "goto S02"
  # second round: panel-away line both sides, hand line in free practice, lesson stops on the card, solid S00 / S20
  c "view 70"; c "goto S01" 8; c "view -70" 8; c "view 0"; c "goto S02"
  c "goto S15 seed=4 diff=3" 10; c "goto S02"
  c "goto S12 outcome=pass stops=3"; c "goto S08 t=1.5 lesson=6"; c "goto S02"; c "goto S00"; c "goto S20"; c "goto S02"
  # v0.1.3: MRD-R (it only composes in view): every chant it can show, the widest seat, run readouts incl. the < 0.10 m tag and both turn signs
  c "view -32"; for n in 1 2 3 4 5 6 7 8 9 10 11 12; do c "goto S04 lp=$n"; done; c "goto S07 seg=3 pose=-1.6,-0.6,135 steer=-540"; c "goto S07 seg=1 lesson=5"
  c "view 60"; for n in 1 2 3 4 5 6 7 8 9 10 11 12; do c "goto S04 lp=$n"; done; c "goto S04 lp=6 seat=low"; c "goto S04 lp=2 seat=high"; c "seat mid"
  c "goto S07 seg=1 lp=3"; c "goto S07 seg=1 pose=2.0,-0.5,45 steer=540"; c "goto S07 seg=1 pose=1.2,-1.0,60 steer=540"; c "goto S07 seg=3 pose=-1.6,-0.6,135 steer=-540"; c "goto S07 seg=1 lesson=5"; c "goto S14 clock=209"; c "view 0"; c "goto S02"
  # v0.1.6: why Go can't, every reason, on the panel that says it: run stopped in the wrong gear, library, tour, demo
  c "goto S06 seg=1"; c "go-tap" 3; c "goto S04 lp=3"; c "go-tap" 3; c "goto S26 step=1"; c "go-tap" 3; c "goto S05 lesson=3 t=6"; c "go-tap" 3; c "goto S10 line=bay-right" 8; c "go-tap" 3; c "goto S02"
  # v0.1.7: Go grey with its reason under it in S10 / S13 too (q7-3), the step key in D (q7-4), the D44 confirms on the pause page (q7-7)
  c "goto S10 line=bay-right" 10; c "goto S10 line=bay-left lesson=6" 10; c "goto S13 t=6.5"; c "goto S23 lesson=2" 10; c "gear D" 3; c "step" 4; c "gear R" 3
  c "goto S07 seg=1"; c "go 1" 4; c "pause"; c "restart"; c "confirm no"; c "home"; c "confirm no"; c "resume"; c "goto S10 line=bay-right" 10; c "restart"; c "confirm no"; c "goto S02"
  # v0.1.5: muted (the mute key says Sound on): home, both settings pages, P2 running and paused, back to unmuted
  c "goto S02"; c "mute 1"; c "goto S02"; c "goto S17"; c "goto S17 page=1"; c "goto S07 seg=1"; c "goto S08 t=1.5"; c "goto S18 lesson=6"; c "resume"; c "goto S02"; c "mute 0"; c "goto S17 page=1"; c "goto S02"
  # v0.1.4: P2 third column with the top view off, both sides
  c "topview 0"; c "goto S07 seg=1 lp=3"; c "goto S07 seg=3 pose=-1.6,-0.6,135 steer=-540"; c "goto S07 seg=4 gear=D"; c "topview 1"; c "goto S02"
  # v0.1.4: the settings pages with every slider at its ends
  c "mirror 0,-20"; c "vol master=0 car=0 cue=0"; c "speed 0.5"; c "goto S17"; c "goto S17 page=1"; c "mirror 15,0"; c "vol master=100 car=100 cue=100"; c "speed 1.2"; c "goto S17"; c "goto S17 page=1"; c "mirror 5,-16"; c "vol master=100 car=60 cue=100"; c "speed 0.8"; c "goto S02"
  # the flow's own lines: no head pose, tracking lost during the wait, on the three pages and S01
  c "flash-hold 1"; c "lost 1" 3; c "reseat" 5; c "goto S17 page=1" 3; c "reseat" 5; c "goto S18 lesson=6" 3; c "reseat" 5; c "goto S01" 3; c "seated" 5; c "lost 0" 3; c "goto S02" 3; c "reseat" 0; c "lost 1" 5; c "lost 0" 2; c "flash-hold 0"; c "goto S02"
done
sleep 2; kill $LOGPID 2>/dev/null
echo "== log $LOGF: $(wc -l < "$LOGF" | tr -d ' ') lines, $(grep -c 'CMD ' "$LOGF") commands answered, from $(head -1 "$LOGF" | cut -c1-18) to $(tail -1 "$LOGF" | cut -c1-18)"
echo "== OVERFLOW lines"; grep 'OVERFLOW' "$LOGF" | sed 's/.*OVERFLOW/OVERFLOW/' | sort | uniq -c
PID1=$(adb -s emulator-5556 shell pidof io.embodit.yarddrive | tr -d '\r')
FRAME1=$(grep 'FRAME n=' "$LOGF" | tail -1 | sed -E 's/.*FRAME n=([0-9]+).*/\1/')
[ "$PID0" = "$PID1" ] && echo "== same process $PID1" || echo "== PROCESS CHANGED $PID0 -> $PID1"
echo "== FRAME n $FRAME0 -> $FRAME1"
