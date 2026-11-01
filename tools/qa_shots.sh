#!/usr/bin/env bash
# Key-state screenshots for ui-audit.md (build station) and the QA list in AGENTS.md: one debug command, wait, one
# pico-cli screenshot (>= 10 s apart). tools/qa_shots.sh OUT_DIR [LANG]
cd "$(dirname "$0")/.."
OUT="${1:?out dir}"; L="${2:-zh}"; mkdir -p "$OUT"
s() { CMD_WAIT=0.3 tools/dev.sh cmd "$2" >/dev/null; sleep "${3:-9}"; tools/dev.sh shot "$1" "$OUT" >/dev/null; echo "$1  <- $2"; }
c() { CMD_WAIT=0.3 tools/dev.sh cmd "$1" >/dev/null; sleep 1; }
c "lang $L"; c "view 0"; c "seat mid"; c "hands 0"
s S01 "goto S01"
s S02 "goto S02"
s S03 "goto S03"
c "goto S04"; s S04_list "goto S04"
s S04_lp2 "goto S04 lp=2"; c "view -45"; s S04_lp2_leftmirror "view -45" 6
c "view 60"; s S04_lp3_rightmirror "goto S04 lp=3"; c "view 0"
s S04_lp6 "goto S04 lp=6"
s S05 "goto S05 lesson=3 t=6"
s S06_seg1 "goto S06 seg=1"
s S06_seg2 "goto S06 seg=2"
s S07 "goto S07 seg=1 pose=3.9,-2.65,0"
s S07_arc "goto S07 seg=1 lp=3"; c "view 60"; s S07_arc_rightmirror "view 60" 6; c "view 0"
s S07_seg2_D "goto S07 seg=2 gear=D pose=0.04,0.95,90"
s S08_exam "goto S08 t=2.5 rules=exam clock=120"
s S09 "goto S09 lesson=6" 14
s S10 "goto S10 line=bay-right" 14
s S11 "goto S11 lesson=6" 16
s S12_pass "goto S12 outcome=pass"
s S12_out "goto S12 outcome=out"
s S12_points "result pass page=1"
s S12_path "result pass page=2"
s S13 "goto S13 t=6.5"
s S14 "goto S14 clock=83"
s S16 "goto S16 seed-scores=1"
s S17_practice "goto S17"
s S17_system "goto S17 page=1"
s S17_exit "goto S17 confirm=exit"
s S18 "goto S18 lesson=6"
c "resume"
s S20 "goto S20"
s S23 "goto S23 lesson=2" 14
s S25 "goto S25 lesson=3" 14
s S26_step1 "goto S26 step=1"
s S27 "goto S27"
c "lang en"; s S02_en "goto S02"; s S07_en "goto S07 seg=1 pose=3.9,-2.65,0"; s S12_en "goto S12 outcome=pass"; s S17_en "goto S17"
c "lang $L"; c "goto S02"
