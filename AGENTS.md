# Yard Drive (倒车入库)

Sit in a 1:1 exam car and practise the bay park of the Chinese driving test (科目二倒车入库): pinch / grip the wheel to
full lock and back, turn your head to the mirrors and the rear window; the reference points come from your own eye
height; reverse in right → pull out left → reverse in left → pull out right, judged to GA 1026-2017, every point told in
centimetres early / late. PICO OS 6, Kotlin, PICO Spatial SDK 6.1.9, package `io.embodit.yarddrive`. Skeleton C,
container **STAGE_FULL** (manifest `pico.spatial.stage.style=3`), window model `stage_with_attachment_panels`:
P1 centre screen 800×600 dp, P2 HUD strip 1160×240 (both scale 0.8), HUB Ø160 and MRD-L / MRD-R 200×64 (scale 0.6), three
mirrors MIR-L / MIR-R 225×138 and MIR-I 325×88 (scale 1, opaque canvases). No WindowContainer, no network.

Design package (binding): `../../spatial-app-factory/apps/yard-drive/design-package.json` (r3, gate B passed with the four
defaults: strict switch off, lessons 3-5 lenient +0.2 m, coach's hand in demos on, hand throttle tap Go / Stop).
Sample `../../spatial-app-factory/apps/yard-drive/sample.html`, PRD https://claude.ai/artifact/QHsiMqdtRLAcQ6NtCC89oh.
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
    Free.kt Records.kt Settings.kt Copy.kt Synth.kt CarModel.kt Seat.kt (the one placement function) Vec3 Geometry
  platform/              YdLog (tag YardDrive), Store (prefs), Sound (AudioTrack), HeadTracker, Inputs (controller,
                         hands, eyes), LaunchActivity, SpatialApplication
  ui/
    YdUiState.kt         Screen, RunView, ResultView, designState() -> S00..S27, modifiers()
    YdController.kt      ALL behaviour: events -> state, the per-frame run (tick), lessons / free / replay / tour
    YdScreen.kt          wires controller, scene, panels; lifecycle -> pause (S18)
    scene/YardScene.kt   ECS: cabin (fixed to the seat), yardRoot (inverse car pose every frame), wheel, knob, mirror
                         frames, ideal path, line flash, gaze dot, vignette, coach's hand; panel placement
    scene/PanelRig.kt    pre-built panel pool (Spatial Ruler findings); Palette.kt (colour atlas + Toon mesh builder)
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
tools/dev.sh test                          # 51 JVM tests: U1-U17, 15 sloppy runs, endings / time-outs, placement, D25, audio
tools/dev.sh log 'STATE|PLACE|JUDGE|CUE' 60
tools/dev.sh crash
tools/dev.sh shot NAME [DIR]               # pico-cli capture screenshot (keep >= 10 s between shots)
tools/fit_sweep.sh "en zh" 5               # every state, widest values, prints OVERFLOW lines (expect none)
python3 tools/contrast.py SHOT_DIR --md OUT.md
python3 tools/subset_cjk_font.py           # after adding Chinese copy anywhere in app/src/main/java
python3 tools/make_icon.py ../../spatial-app-factory/apps/yard-drive/icon.png
```

If another Full Space app is in front on the emulator, `am start` logs `current full space don't allow start
fullscreen` and nothing opens: force-stop that app first.

## Debug broadcast (debug builds only)

`adb -s emulator-5556 shell "am broadcast -a io.embodit.yarddrive.DEBUG -p io.embodit.yarddrive --es cmd 'VERB k=v …'"`
or `tools/dev.sh cmd 'VERB k=v …'`. Each answers `CMD <raw> -> ok state=<design id> mods=[…]`; every state change logs
`STATE <id> mods=[…]`. Commands send the panels' own events; `pose=` / `steer=` place the car the way the library teleport
and the hand on the wheel do. The emulator camera cannot turn: `view DEG` turns the cabin at placement (+ = look right)
and points the debug head the same way, so mirrors / side windows come in front of the camera.

| command | effect |
|---|---|
| `goto S00`…`goto S27` + `lesson=n seg=1..4 lp=1..12 seat=low|mid|high pose=x,z,psi steer=deg gear=R|D rules=lesson|exam t=s clock=s step-by-step=1 line=bay-right|bay-left page=0..2 confirm=exit about=1 top=1 lang=zh|en` | any design state by name (see the list below) |
| `result outcome=pass|out|notin|timeout|order [page=0|1|2]` | a real run (ideal script, or 0.4 m early for out) judged and shown as the result card |
| `replay t=6.5` | result → replay, frozen at t |
| `script ideal|early:0.15|early:0.35|late:0.2 [follow=1]` | the ideal-script driver takes the current run (or opens the mock test) |
| `go 0|1`, `step`, `gear R|D`, `lock L|R|0`, `steer DEG`, `grab angle=DEG|off` | the inputs (A key, B key, Y key, P2 lock buttons, a hand on the wheel) |
| `free seed=N diff=1..3`, `seed-scores`, `clear-scores` | free practice / fake scorecard / clear |
| `lang zh|en`, `seat …`, `speed X`, `gap X`, `hint 0|1`, `topview 0|1`, `poles 0|1`, `hands 0|1`, `strict 0|1`, `mute` | settings |
| `gaze yaw,pitch|off`, `eye off|on`, `head yaw[,pitch]|off`, `view DEG[,pitch]`, `lost 0|1` | tracking stand-ins (S22, S19) |
| `pause`, `resume`, `home`, `back`, `settings page=0|1`, `exitask`, `quit` | navigation; `quit` = settings → Quit app → Quit (D26 path) |
| `dump`, `state` | scene / instance list / panel pool / run to the log (`DUMP …`) |

## Screenshot list (QA, design states[].qaCapture)

| state | commands |
|---|---|
| S00 | `goto S00` (P1 loading) |
| S01 | `goto S01`; tilted cold starts by the QA harness |
| S02 | `goto S02`; `seed-scores` then `goto S02` |
| S03 | `goto S03` |
| S04 | `goto S04 lp=2` + `view -45` (left mirror lit, line under the shell); `lp=3` + `view 60`; `lp=6`; `goto S04 lp=2 seat=high` |
| S05 | `goto S05 lesson=3 t=6`; `goto S05 lesson=6 t=20 top=1` (top view only) |
| S06 | `goto S06 seg=1`; `goto S06 seg=2` (in the bay, P2 up at +1°) |
| S07 | `goto S07 seg=1 pose=2.0,-0.5,45 steer=540` (+ `view 60` for the right mirror); `hint 0` / `topview 0` variants; `goto S07 seg=2 gear=D pose=0.04,0.95,90` |
| S08 | `goto S08 t=1.5`; `goto S08 t=2.5 rules=exam` (red −5) |
| S09 | `goto S09 lesson=6` |
| S10 | `goto S10 line=bay-right`; `goto S10 line=bay-left lesson=6` |
| S11 | `goto S11 lesson=6` |
| S12 | `goto S12 outcome=pass|out|notin|timeout|order`; `result pass page=1`; `result pass page=2` |
| S13 | `goto S13 t=6.5` |
| S14 | `goto S14 clock=83` (mock test, no cues, timer) |
| S15 | `goto S15 seed=42 diff=3` |
| S16 | `goto S16 seed-scores=1` |
| S17 | `goto S17`; `goto S17 page=1`; `goto S17 confirm=exit`; `goto S17 about=1` |
| S18 | `goto S18` |
| S19 | `goto S07 seg=1` then `lost 1` |
| S20 | `goto S20` |
| S21 | `lang en` then S02 / S07 / S12 / S16 |
| S22 | `eye off` (the emulator has no eye permission: on by default) |
| S23 | `goto S23 lesson=2` (three steps: 0.9 m back) |
| S24 | `hands 1` then `goto S07 seg=1` (hint line + Go / Stop) |
| S25 | `goto S25 lesson=3` (stopped by itself at LP2) |
| S26 | `goto S26`; `goto S26 step=1`; `goto S26 step=3 lp=4` |
| S27 | `goto S27` |
