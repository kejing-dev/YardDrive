#!/usr/bin/env bash
# Key-state screenshots for ui-audit.md (build station) and the QA list in AGENTS.md: one debug command, wait, one
# pico-cli screenshot (>= 10 s apart). tools/qa_shots.sh OUT_DIR [LANG]
cd "$(dirname "$0")/.."
OUT="${1:?out dir}"; L="${2:-zh}"; mkdir -p "$OUT"
s() { CMD_WAIT=0.3 tools/dev.sh cmd "$2" >/dev/null; sleep "${3:-9}"; tools/dev.sh shot "$1" "$OUT" >/dev/null; echo "$1  <- $2"; }
c() { CMD_WAIT=0.3 tools/dev.sh cmd "$1" >/dev/null; sleep 1; }
c "lang $L"; c "view 0"; c "seat mid"
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
s S08_settle "goto S08 t=0.5 pose=0.04,3.8,90"
s S08_noseout "goto S08 t=1.5 rules=exam pose=0.04,2.2,90"
s S12_pass "goto S12 outcome=pass"
s S12_out "goto S12 outcome=out"
s S08_lesson "goto S08 t=2.5 lesson=6"
s S12_lesson_stops "goto S12 outcome=pass stops=2"
s S12_notin "goto S12 outcome=notin"
s S12_timeout "goto S12 outcome=timeout"
s S12_order "goto S12 outcome=order"
s S12_points "result pass page=1"
s S12_path "result pass page=2"
s S13 "goto S13 t=6.5"
s S14 "goto S14 clock=83"
s S15_ready "goto S15 seed=4 diff=3" 13
s S06_go "goto S06 seg=1"; c "go-tap" 2; tools/dev.sh shot S06_go_refused "$OUT" >/dev/null; c "goto S02"
s S16 "goto S16 seed-scores=1"
c "clear-scores"  # fake scores must not reach the other shots
s S17_practice "goto S17"
s S17_system "goto S17 page=1"
s S17_exit "goto S17 confirm=exit"
s S18 "goto S18 lesson=6"
c "resume"
s S20 "goto S20"
s S23 "goto S23 lesson=2" 14
s S25 "goto S25 lesson=3" 14
s S26_step1 "goto S26 step=1"
c "flash-hold 1"; c "view -45"; s S26_line0_left "goto S26 step=2 line=0" 13; c "view 0"; s S26_line1 "goto S26 step=2 line=1" 13; c "view 60"; s S26_line2_right "goto S26 step=2 line=2" 13; c "view 0"; s S26_line3 "goto S26 step=2 line=3" 13; c "view -60"; s S26_line4_left "goto S26 step=2 line=4" 13; c "view 0"; c "flash-hold 0"
c "goto S02"; c "view 0,-35"; s HUB_view "state" 10; c "view 0"
c "view 70"; s S01_panel_away "goto S01" 13; c "view 0"; c "goto S02"
c "flash-hold 1"; c "head 12"; c "reseat" 3; sleep 12; tools/dev.sh shot reseat_done "$OUT" >/dev/null; echo reseat_done; c "head off"; c "flash-hold 0"; c reseat 9
c "goto S04 lp=2"; c "view -32"; s MRD_L_zh "state" 10; c "view 60"; s MRD_R_zh "goto S04 lp=3" 13; c "view 0"; c "lang en"; c "goto S04 lp=2"; c "view -32"; s MRD_L_en "state" 10; c "view 60"; s MRD_R_en "goto S04 lp=3" 13; c "view 0"; c "goto S07 seg=3 lesson=5"; c "view -32"; s MRD_L_run_en "state" 10; c "view 0"; c "lang $L"
# v0.1.3: the three mirrors from the front (QA q3-1) and MRD-R with readouts (zh, en): look at them at full size
c "lang $L"; c "goto S04 lp=3"; c "view 67"; s MIR_R_front "state" 13; c "goto S04 lp=2"; c "view -48"; s MIR_L_front "state" 13; c "view 35,14"; s MIR_I_front "state" 13
c "view 60"; s S04_right "goto S04 lp=3" 13; s S07_arc_MRD_R_zh "goto S07 seg=1 lp=3" 13; s S07_lesson5_right "goto S07 seg=1 lesson=5" 13; c "view -45"; s S07_lesson5_left "state" 13
c "lang en"; c "view 60"; s S07_arc_MRD_R_en "goto S07 seg=1 lp=3" 13; c "lang $L"; c "view 0"; c "goto S02"
# D38: the same point read three ways, MRD, HUB and the run state
for G in zh en; do c "lang $G"; c "view 60"; s LP3_MRD_R_$G "goto S04 lp=3" 13; c "view 0,-35"; s LP3_HUB_$G "state" 13; c "view -32"; s LP10_MRD_L_$G "goto S04 lp=10" 13; c "view 0,-35"; s LP10_HUB_$G "state" 13; c "view 0"; done; c "lang $L"
c "view 60"; s LP4_MRD_R "goto S04 lp=4" 13; c "view -32"; s LP7_MRD_L "goto S04 lp=7" 13; s LP11_MRD_L "goto S04 lp=11" 13; c "view 45"; s S4_strut_side "goto S04 lp=3" 13; c "view 0"; c "goto S02"
c "topview 0"; s P2_gap_seg1 "goto S07 seg=1 lp=3" 13; s P2_gap_seg3 "goto S07 seg=3 pose=-1.6,-0.6,135 steer=-540" 13; c "topview 1"
c "goto S02"; c "headoff 0,-0.30,0.05"; s D20_fade "state" 10; c "headoff off"
c "goto S07 seg=1 pose=3.9,-2.65,0"; s R4_vignette "vignette 1" 8; c "vignette off"
s S27 "goto S27"
c "lang en"; s S02_en "goto S02"; s S07_en "goto S07 seg=1 pose=3.9,-2.65,0"; s S12_en "goto S12 outcome=pass"; s S17_en "goto S17"
c "lang $L"; c "goto S02"
