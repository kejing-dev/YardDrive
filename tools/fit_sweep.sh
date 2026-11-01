#!/usr/bin/env bash
# D13 pre-QA gate (build.md): every design state in both languages with the widest dynamic values (exam clock 03:29,
# 9 mid-stops, free chip run 5/5 level 3 seed 9999, three-digit score, two-column point table, both settings pages and
# the confirm rows), then prints the OVERFLOW lines (expect none). The emulator renders at about 1 fps: W s per step.
#   tools/fit_sweep.sh [LANGS] [WAIT_S]
cd "$(dirname "$0")/.."
LANGS="${1:-en zh}"; W="${2:-6}"
c() { CMD_WAIT=0.3 tools/dev.sh cmd "$1" >/dev/null; sleep "${2:-$W}"; echo "  $1 -> $(tools/dev.sh log 'STATE' 1 | sed 's/.*STATE //' | cut -c1-50)"; }
adb -s emulator-5556 logcat -c
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
  c "hands 1"; c "goto S07 seg=1"; c "goto S23 lesson=2" 10; c "hands 0"
  c "goto S25 lesson=3" 10; c "goto S26"; c "goto S26 step=1"; c "goto S26 step=2"; c "goto S26 step=3 lp=4"; c "goto S27"
  c "goto S06 lesson=2"; c "goto S02"
done
echo "== OVERFLOW lines"; tools/dev.sh log 'OVERFLOW' 300 | sed 's/.*OVERFLOW/OVERFLOW/' | sort | uniq -c
