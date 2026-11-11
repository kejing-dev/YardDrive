# Yard Drive (倒车入库)

Sit in a 1:1 exam car and practise the bay park of the Chinese driving test (科目二倒车入库): tap the P2 keys (or the controller) to steer
to full lock and back, turn your head to the mirrors and the rear window; the reference points come from your own eye
height; reverse in right → pull out left → reverse in left → pull out right, judged to GA 1026-2017, every point told in
centimetres early / late. PICO OS 6, Kotlin, PICO Spatial SDK 6.1.9, package `io.embodit.yarddrive`. Skeleton C,
container **STAGE_FULL** (manifest `pico.spatial.stage.style=3`), window model `stage_with_attachment_panels`:
P1 centre screen 800×600 dp, P2 HUD strip 1160×240 (both scale 0.8), HUB Ø160 and MRD-L 200×64 (scale 0.6), MRD-R 280×72 (scale 1), three
mirrors MIR-L / MIR-R 225×138 and MIR-I 325×88 (scale 1, opaque canvases). No WindowContainer, no network.

Design package (binding): `../../spatial-app-factory/apps/yard-drive/design-package.json` (r3, gate B passed with the four
defaults: strict switch off, lessons 3-5 lenient +0.2 m, coach's hand in demos on, hand throttle tap Go / Stop; design r5 later dropped the hand, the wheel grab and pinch: steering is the P2 keys and the controller).
Sample `../../spatial-app-factory/apps/yard-drive/sample.html`, PRD `../../spatial-app-factory/apps/yard-drive/html/prd.html`.
Scaffold: `pico-cli project create --sdk-type spatial --template stage`; panel pool, head tracker, theme, fonts, debug
receiver and tools ported from traffic-signals (same skeleton C, same machine). The design's primary reference
(shoulder-check) is not on GitHub (kejing-dev has no ShoulderCheck repo, needsCheck N1): the cabin and mirrors were
rebuilt from `apps/shoulder-check/design-package.json` + `apps/yard-drive/carmodel/build_yard.py`.

## Structure

```
app/src/main/java/io/embodit/yarddrive/
  domain/                pure Kotlin, JVM-tested (app/src/test)
    Car.kt               car numbers (4.50 × 1.70, wheelbase 2.60, ratio 14.2, 38°), Pose (rear axle + heading), yard<->cabin
    Yard.kt              GA 1026-2017 yard paint and judged lines (lenient +0.2 m), starts, lesson-2 lane, geometry
    Drive.kt             DriveSim: single-track model, button / manual / stick steering ramps, speed ramps, step mode
    Segments.kt          the four segments + design truth values (3.52 / 0.85 / 88° / 0.35 …), segment end checks
    Run.kt               the judge: D-OUT / D-NOTIN / D-ORDER / D-STOP / D-TIME, lesson rules (S10), hits, gaps, events
    Predictor.kt         the look-ahead: four branches (hold / lock now / centre now / ease off Δt), advice, bestLock
    Autopilot.kt         the ideal script as a driver (demo S05, debug script=…, U4)
    LookPoints.kt        LP1-LP12 truth poses (captured from the ideal script), chant offsets per seat, gaze classes
    Mirror.kt            equivalent curved mirrors: reflected eye, aim, fixed FOV, polylines of the yard and your car
    Free.kt Records.kt Settings.kt Copy.kt Synth.kt CarModel.kt Seat.kt (the one placement function, D29) PanelCone.kt (D20 wheel fade) Vec3 Geometry
  platform/              YdLog (tag YardDrive), Store (prefs), Sound (AudioTrack), HeadTracker, Inputs (controller,
                         hands, eyes), LaunchActivity, SpatialApplication
  ui/
    YdUiState.kt         Screen, RunView, ResultView, designState() -> S00..S27, modifiers()
    YdController.kt      ALL behaviour: events -> state, the per-frame run (tick), lessons / free / replay / tour
    YdScreen.kt          wires controller, scene, panels; lifecycle -> pause (S18)
    scene/YardScene.kt   ECS: cabin (fixed to the seat), yardRoot (inverse car pose every frame), wheel, knob, mirror
                         frames, ideal path, line flash, gaze dot, vignette; panel placement
    scene/PanelRig.kt    pre-built panel pool (Spatial Ruler findings); Palette.kt (colour atlas + Toon mesh builder);
                         Smooth.kt (v0.1.7: smooth-shaded ramp, lathe / torus / tube / loft; RoundParts = the wheel torus,
                         gear knob, steering column, the three coach cars)
    panels/              YdStage (SpatialView + 8 attachments), P1.kt, P2.kt, Small.kt (HUB, MRD, mirrors), Common.kt
app/src/debug/           DebugCommandReceiver + DebugCommands (QA hooks)
tools/                   dev.sh, fit_sweep.sh (D13 overflow sweep), contrast.py (+ contrast_samples.py), make_icon.py,
                         icon_sheet.py, subset_cjk_font.py, spectrum.py
```

Frames: cabin = origin at your eye E0, -Z car front, +X right, +Y up, road at y = -eyeHeight (1.12 / 1.20 / 1.28). Yard =
origin bay-front-line centre, +X east, +Z south (into the bay). Pose heading ψ: 0 east, 90° north. yardRoot rotation =
π/2 − ψ about +Y (no reflection).

## Build, test, run (macOS, factory emulator emulator-5556 / AVD PICO_6_1_kj-mbp_2)

```bash
tools/dev.sh build && tools/dev.sh install && tools/dev.sh relaunch && tools/dev.sh wait 'STARTUP t=1'
tools/dev.sh test                          # 69 JVM tests: U1-U17, 15 sloppy runs, endings / time-outs, placement + D29, panel cone (D20), stop view (D31), free seat (D30), D25, audio
tools/dev.sh log 'STATE|PLACE|JUDGE|CUE' 60
tools/dev.sh crash
tools/dev.sh shot NAME [DIR]               # pico-cli capture screenshot (keep >= 10 s between shots)
tools/fit_sweep.sh "en zh" 5 LOGFILE       # every state, widest values; logcat streamed to LOGFILE, OVERFLOW counted from it (expect none)
python3 tools/contrast.py SHOT_DIR --md OUT.md
python3 tools/subset_cjk_font.py           # after adding Chinese copy anywhere in app/src/main/java
python3 tools/make_icon.py ../../spatial-app-factory/apps/yard-drive/icon.png
```

On a fresh install the first `seated` opens the system eye-tracking dialog once the car is placed (about 2 s later), which holds the app in ON_STOP. Force-stopping the app leaves it on top and
the next launch fails silently: `adb -s emulator-5556 shell am force-stop com.android.permissioncontroller` closes it without answering.

If another Full Space app is in front on the emulator, `am start` logs `current full space don't allow start
fullscreen` and nothing opens: force-stop that app first.

## Debug broadcast (debug builds only)

`adb -s emulator-5556 shell "am broadcast -a io.embodit.yarddrive.DEBUG -p io.embodit.yarddrive --es cmd 'VERB k=v …'"`
or `tools/dev.sh cmd 'VERB k=v …'`. Each answers `CMD <raw> -> ok state=<design id> mods=[…]`; every state change logs
`STATE <id> mods=[…]`. Commands send the panels' own events; `pose=` / `steer=` place the car the way the library teleport
and the P2 steering keys do. The emulator camera cannot turn: `view DEG` turns the cabin at placement (+ = look right)
and points the debug head the same way, so mirrors / side windows come in front of the camera.

| command | effect |
|---|---|
| `goto S00`…`goto S27` + `lesson=n seg=1..4 lp=1..12 seat=low|mid|high pose=x,z,psi steer=deg gear=R|D rules=lesson|exam t=s clock=s step-by-step=1 line=bay-right|bay-left page=0..2 confirm=exit about=1 top=1 lang=zh|en` | any design state by name (see the list below) |
| `result outcome=pass|out|notin|timeout|order [page=0|1|2]` | a real run (ideal script, or 0.4 m early for out) judged and shown as the result card |
| `replay t=6.5` | result → replay, frozen at t |
| `script ideal|early:0.15|early:0.35|late:0.2 [follow=1]` | the ideal-script driver takes the current run (or opens the mock test) |
| `go 0|1`, `step`, `gear R|D`, `lock L|R|0`, `steer DEG`, `go-tap` (the Go key, one tap goes, the next stops; says why when it can't) | the inputs (A key, Y key, P2 lock buttons) |
| `free seed=N diff=1..3`, `seed-scores`, `clear-scores` | free practice / fake scorecard / clear |
| `lang zh|en`, `seat …`, `speed X`, `gap X`, `hint 0|1`, `topview 0|1`, `poles 0|1`, `strict 0|1`, `mute` | settings |
| `gaze yaw,pitch|off`, `eye off|on`, `head yaw[,pitch]|off`, `view DEG[,pitch]`, `lost 0|1` | tracking stand-ins (S22, S19) |
| `pause`, `resume`, `home`, `back`, `restart`, `confirm yes|no`, `settings page=0|1`, `exitask`, `quit` | navigation; `quit` = settings → Quit app → Quit (D26 path). Half-way through a run (moved off, or > 1 s on the clock) or the tour past step 1, `home` / `restart` / P2 Restart run open the D44 confirm on the pause page (log `CONFIRM ask HOME|RESTART`) and pause the run; `confirm yes` / `confirm no` are its two keys (no from P2 at a line goes back to the line). `goto` itself leaves a run by Home + yes |
| `dump`, `state` | scene / instance list / panel pool / run to the log (`DUMP …`). `DUMP HEAD (x, y, z) yaw= pitch=` is what the tracker says before the debug head and `view`; `FRAME` and `STARTUP` lines carry the head `yaw= pitch=` too (after the debug head). A picture that sits lower or higher than an older one with the same command: compare `pitch` first. Not 0 at `view 0` means the emulator camera moved (seen after sleep / wake), nothing in the app pitches the cabin (Seat only uses yaw); restart the emulator before you compare pixels |
| `seated`, `reseat` | the S01 button; the P1 Re-seat key (home, pause, settings system page). The same flow as the real buttons (SeatFlow): P1 goes away, the line "look straight ahead" stays with the head, after at least 1.2 s and a still head (5 s at most) the car goes to the head's yaw, P1 comes back, "Placed ✓". S01 only leaves for S02 when that is done. Log `SEAT start`, `SEAT look=…`, `PLACE placed eye=… yaw=…`. `head N` + `reseat` always ends at the head's yaw |
| hint line on screen | Two ways, and which one works depends on the frame rate. **About 1 fps only:** `pico-cli capture record`, `head -16`, `reseat`, then `head -10`, `-4`, `2`, `8`, `14`, `20`, `26` one after another (each lands about one per frame, so +6° a frame): the flow runs its full 5 s and the line, which shows about 3 s after P1 goes at this frame rate, can be seen. Once the emulator is back at 6–25 fps the same commands end the flow after about 2 s and the line is missed: the broadcasts run one at a time and each takes about 1 s to land, so the head looks still in between. **Any frame rate (QA #3):** one `adb shell` that sends 30 broadcasts in parallel, 0.2 s apart, each ±3°: `adb -s emulator-5556 shell 'for i in $(seq 1 30); do am broadcast -a io.embodit.yarddrive.DEBUG -p io.embodit.yarddrive --es cmd "head $((i % 2 * 6 - 3))" >/dev/null & sleep 0.2; done; wait'` with `reseat` sent as it starts. QA measured 5.03 s with the line on from the moment P1 goes. Alternating values sent one at a time can repeat across two frames and end the wait early |
| `lost 1` + `reseat` / `reseat` + `lost 1` | no head pose: P1 stays, the sub line says why / tracking lost during the wait: `SEAT cancelled … lost=true`, P1 back, press again |
| `controls [P1]` | logs `CONTROL name x= y= w= h=` : panel pixels for a real press with `adb -s emulator-5556 shell input tap X Y` (800 dp = 1600 px; the first tap after a panel change can get lost, read the state back) |
| `tour start|next|skip-rear` | the tour's P2 keys: lesson 1 can be walked to its result card (`tour next` ×…) |
| `flash-hold 0|1` | the line flash stays on for a screenshot (it blinks 1.5 s; the emulator shows about 1 frame per second) |
| `headoff x,y,z|off` | the head moved in the Stage (`headoff 0,-0.30,0.05`: the wheel falls into P1's line of sight, log `FADE wheel faded=true`; the camera itself stays) |
| `vignette 0..1|off` | hold the comfort vignette at this strength |
| `vol master=p car=p cue=p` | the three volume sliders |
| `eye-ask n`, `turnon`, `askclear` | pretend the eye dialog was answered n times; the home Turn on key (n >= 2: opens system settings, shows the note); scorecard clear confirm |
| `adb shell setprop debug.yarddrive.nohead 1` + relaunch | no head pose and not in Full Space from the start (S20 from a cold start, R11); set back to 0 |

## Screenshot list (QA, design states[].qaCapture)

| state | commands |
|---|---|
| HUB | `view 0,-35` (the view command can pitch the car too: the emulator camera cannot look down) |
| S01 away | `view 70` then `goto S01` (one line where you look, the car stays put) |
| S00 | cold start: `tools/dev.sh relaunch`, `tools/dev.sh wait 'STARTUP p1'`, 7 s, shot (P1 loading + sky, log `STARTUP p1 placed ms=…`); `goto S00` |
| S01 | `goto S01`; tilted cold starts by the QA harness |
| S02 | `goto S02`; `seed-scores` then `goto S02` |
| S03 | `goto S03` |
| S04 | `goto S04 lp=2` + `view -45` (left mirror lit, line under the shell); `lp=3` + `view 60`; `lp=6`; `goto S04 lp=2 seat=high` |
| Muted state | `mute 1` then `goto S02`, `goto S17 page=1`, `goto S07 seg=1`; `tools/fit_sweep.sh` runs it in both languages. The mute keys have a fixed width, so nothing beside them moves; settings has it in the top bar, the sliders are all 150 dp. `tools/slider_check.py OUT --no-corners` skips the mirror corners |
| A whole drive, real presses | `python3 tools/real_drive.py OUT.log en|zh [6|1]` (1 = the yard tour walked to its card by Start / Next step): reads the panel positions once, `pm clear`, then only `adb shell input tap` and logcat: no debug command from the first page to the bay. `DRIVE` and `CUE` lines say where the car is; `PERF` says frames, world and mirror rate, dropped frames, the vignette. The taps lead by the car's rate on the device clock times the measured tap latency (`LATENCY` lines), not a fixed second |
| Settings sliders | `python3 tools/slider_check.py OUT`: real presses. Every slider against the page's other settings, read back from `SETTINGS` in `dump`; then the mirror sliders to the four corners, each mirror photographed where it stands: **no `view`, no state-changing `goto`, no re-seat between the slider and the picture**, they place the panels again. `DUMP MIRROR` says how far each face is from its frame's normal. Ends with `pm clear`. `mirror out,pitch` is the same event as the sliders. A panel writes a change (`SetSettings { s -> s.copy(...) }`), never a copy of what it read: the design Slider keeps its first onValueChange |
| Mirrors from the front | `goto S04 lp=3` + `view 67` (MIR-R), `goto S04 lp=2` + `view -48` (MIR-L), `view 35,14` (MIR-I): crop and enlarge, the four rounded corners must be there. The R frame's inner lower tip is 0.75 cm behind the sill and accepted (design r4); the mirror face is not |
| MRD-L / MRD-R | Read `MrdReadout`, one source with the HUB (profile D38). The library: a chant point shows the chant, the others show corner + turns like a run at that truth pose. `view 60` + `goto S04 lp=3`, `view -32` + `goto S04 lp=10`, `view 0,-35` for the HUB in the same state, `lang en` too. They only compose in view, so `tools/fit_sweep.sh` turns to them |
| The bar behind a side mirror | `goto S04 lp=3` + `view 45`: off the front, it joins the frame to the shell. From the eye it stays behind the face (test strutsHideBehindTheMirror) |
| S05 | `goto S05 lesson=3 t=6`; `goto S05 lesson=6 t=20 top=1` (top view only) |
| S06 | `goto S06 seg=1`; `goto S06 seg=2` (in the bay, P2 up at +1°) |
| S07 | `goto S07 seg=1 lp=3` (LP3 truth pose, full lock; + `view 60` for the right mirror); `hint 0` / `topview 0` variants; `goto S07 seg=2 gear=D pose=0.04,0.95,90` |
| S08 | `goto S08 t=1.5` (amber); `goto S08 t=2.5 rules=exam` (red −5, 1 stop charged); `goto S08 t=0.5 pose=0.04,3.8,90` (at the end: Settled ✓, no ring); `goto S08 t=1.5 rules=exam pose=0.04,2.2,90` (nose out: how many cm, no ring) |
| S09 | `goto S09 lesson=6` |
| S10 | `goto S10 line=bay-right`; `goto S10 line=bay-left lesson=6` |
| S11 | `goto S11 lesson=6` |
| S12 | `goto S12 outcome=pass stops=2` (lesson rules: stops noted, no deduction); `goto S12 outcome=pass|out|notin|timeout|order|stop` (each is a real run through the judge; the failed segment shows ✗); `result pass page=1`; `result pass page=2` |
| S13 | `goto S13 t=6.5` |
| S14 | `goto S14 clock=83` (mock test, no cues, timer) |
| S15 | `goto S15 seed=42 diff=3` |
| S16 | `goto S16 seed-scores=1` |
| S17 | `goto S17`; `goto S17 page=1` (master / car / cue sliders, Re-seat); `goto S17 confirm=exit`; `goto S17 about=1` |
| S18 | `goto S18` (Re-seat next to Home) |
| S19 | `goto S07 seg=1` then `lost 1` |
| S20 | `goto S20` |
| S21 | `lang en` then S02 / S07 / S12 / S16 |
| S22 | `eye off` (the emulator has no eye permission: on by default) |
| S23 | `goto S23 lesson=2` (three steps: 0.9 m back) |
| S25 | `goto S25 lesson=3` (stopped by itself at LP2) |
| S26 | `goto S26`; `goto S26 step=1`; `flash-hold 1` then `goto S26 step=2 line=0..4` (order yellow, poles, bay front, control, road; 0 and 1 in the bay, 2-4 at the start; `view 60` / `view -45` to look out of the side); `goto S26 step=3 lp=4`; `tour start` + `tour next` to the card |
| S27 | `goto S27` |
