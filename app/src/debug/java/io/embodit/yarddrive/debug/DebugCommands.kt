package io.embodit.yarddrive.debug

import io.embodit.yarddrive.domain.Autopilot
import io.embodit.yarddrive.domain.Car
import io.embodit.yarddrive.domain.Gear
import io.embodit.yarddrive.domain.HeadPose
import io.embodit.yarddrive.domain.LookPoints
import io.embodit.yarddrive.domain.Mode
import io.embodit.yarddrive.domain.Outcome
import io.embodit.yarddrive.domain.Pose
import io.embodit.yarddrive.domain.Quat4
import io.embodit.yarddrive.domain.Rules
import io.embodit.yarddrive.domain.Run
import io.embodit.yarddrive.domain.RunConfig
import io.embodit.yarddrive.domain.RunEvent
import io.embodit.yarddrive.domain.RunPhase
import io.embodit.yarddrive.domain.SegId
import io.embodit.yarddrive.domain.Truth
import io.embodit.yarddrive.domain.Vec3
import io.embodit.yarddrive.domain.Yard
import io.embodit.yarddrive.platform.YdLog
import io.embodit.yarddrive.ui.Confirm
import io.embodit.yarddrive.ui.Screen
import io.embodit.yarddrive.ui.YdController
import io.embodit.yarddrive.ui.YdEvent
import io.embodit.yarddrive.ui.debug.Probe
import io.embodit.yarddrive.ui.scene.ActiveScene

/**
 * QA hooks (design qaHooks). One command per broadcast: a verb, then key=value arguments. Each answers
 * `CMD <raw> -> ok state=<design id> mods=[…]`; every state change logs `STATE <id> …`. Commands send the same events
 * as the panels wherever one exists (factory rule: no shortcut state writes); `pose=` / `steer=` place the car like
 * the library's teleport and the hand on the wheel do.
 *
 *   goto S00..S27 [lesson=n] [seg=1..4] [lp=1..12] [seat=low|mid|high] [pose=x,z,psiDeg] [steer=deg] [gear=R|D]
 *        [rules=lesson|exam] [t=s] [step-by-step=0|1] [line=bay-right|bay-left|back|road] [page=0|1|2] [confirm=exit]
 *   result outcome=pass|out|notin|timeout|order [page=0|1|2]     replay t=<s>
 *   script ideal|early:0.15|early:0.35|late:0.2                   (drives the current run like a user)
 *   go 0|1  go-tap (the Go key)  step  gear R|D  steer <deg>   lock L|R|0 (P2 buttons)
 *   free seed=<n> diff=1..3   seed-scores   clear-scores
 *   mirror <out>,<pitch>   lang zh|en  seat low|mid|high  speed <m/s>  gap 1.5|1.8|2.0  hint 0|1  topview 0|1  poles 0|1  strict 0|1
 *   gaze <yaw>,<pitch>|off   eye off|on   head <yaw>[,<pitch>]|off   lost 0|1
 *   pause | resume | home | back | quit (settings -> Quit app -> Quit) | exitask | dump | state
 *   seated (the S01 button)  reseat (the P1 Re-seat key): the same placing flow as the real buttons
 *   controls [P1] (logs each control's centre: panel pixels for adb shell input tap)  tour start|next|skip-rear (the P2 tour keys)
 *   headoff <x>,<y>,<z>|off (head moved in the Stage, D20)   vignette <0..1>|off   vol master=<p> car=<p> cue=<p>
 *   eye-ask <n>  turnon (the home "Turn on" key)
 */
object DebugCommands {
    private val c = YdController

    fun run(raw: String) {
        val result = runCatching { exec(raw.trim()) }.getOrElse { "error: $it" }
        val st = c.state.value
        YdLog.i("CMD $raw -> $result state=${st.designState()} mods=[${st.modifiers()}]")
    }

    private fun ev(e: YdEvent) = c.onEvent(e)

    /** goto leaves the current page by Home, as a person would; half-way through a run Home asks first (D44), so say yes. */
    private fun home() {
        ev(YdEvent.Home)
        if (c.state.value.confirm == Confirm.HOME) ev(YdEvent.ConfirmHome)
    }

    private fun on(v: String?) = v == null || v == "1" || v == "on" || v == "true"

    private fun exec(raw: String): String {
        val parts = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
        val verb = parts.firstOrNull()?.lowercase() ?: return "empty"
        val kv = HashMap<String, String>()
        val bare = ArrayList<String>()
        for (p in parts.drop(1)) if ('=' in p) kv[p.substringBefore('=').lowercase()] = p.substringAfter('=') else bare += p
        val st = c.state.value
        when (verb) {
            "goto" -> return goto(bare.firstOrNull()?.uppercase() ?: kv["s"]?.uppercase() ?: return "need state", kv)
            "result" -> {
                c.debugResult(outcomeOf(kv["outcome"] ?: bare.firstOrNull() ?: "pass"))
                kv["page"]?.let { ev(YdEvent.ResultPage(it.toInt())) }
            }
            "replay" -> {
                if (st.screen != Screen.RESULT) c.debugResult(Outcome.PASS)
                ev(YdEvent.WatchReplay)
                val t = (kv["t"] ?: bare.firstOrNull())?.toDouble() ?: 0.0
                c.debugSet { it.copy(replayT = t, replaySpeed = 0.0) }
            }
            "script" -> {
                val a = bare.firstOrNull() ?: kv["script"] ?: "ideal"
                val off = when {
                    a.startsWith("early:") -> a.substringAfter(':').toDouble()
                    a.startsWith("late:") -> -a.substringAfter(':').toDouble()
                    else -> 0.0
                }
                if (st.screen != Screen.RUN) ev(YdEvent.OpenMock)
                c.debugScript(Autopilot(lockEarly = off, followAdvice = kv["follow"] == "1"))
            }
            "go" -> ev(YdEvent.Go(on(bare.firstOrNull())))
            "step" -> ev(YdEvent.StepOnce)
            "go-tap" -> ev(YdEvent.GoToggle)
            "gear" -> ev(YdEvent.GearSet(if ((bare.firstOrNull() ?: "R").uppercase() == "D") Gear.D else Gear.R))
            "lock" -> ev(YdEvent.Steer(when ((bare.firstOrNull() ?: "0").uppercase()) { "L" -> -Car.WHEEL_MAX; "R" -> Car.WHEEL_MAX; else -> 0.0 }))
            "steer" -> c.debugRun().handWheel((bare.firstOrNull() ?: "0").toDouble())
            "free" -> { c.debugFree(kv["seed"]?.toInt() ?: 42, kv["diff"]?.toInt() ?: 2) }
            "seed-scores" -> c.debugSeedScores()
            "clear-scores" -> ev(YdEvent.ConfirmClear)
            "lang" -> ev(YdEvent.SetSettings { s -> s.copy(zh = (bare.firstOrNull() ?: "zh") == "zh") })
            // the mirror sliders' values, for a picture; the real sliders are in tools/slider_check.sh
            "mirror" -> bare.firstOrNull()?.split(',')?.let { v -> ev(YdEvent.SetSettings { s -> s.copy(mirrorOut = v[0].toInt(), mirrorPitch = v[1].toInt()) }) }
            "seat" -> ev(YdEvent.Seat(seatOf(bare.firstOrNull() ?: "mid")))
            "speed" -> ev(YdEvent.SetSettings { s -> s.copy(speed = (bare.firstOrNull() ?: "0.8").toDouble()) })
            "gap" -> ev(YdEvent.SetSettings { s -> s.copy(gap = (bare.firstOrNull() ?: "1.8").toDouble()) })
            "hint" -> ev(YdEvent.SetSettings { s -> s.copy(hints = on(bare.firstOrNull())) })
            "topview" -> ev(YdEvent.SetSettings { s -> s.copy(topView = on(bare.firstOrNull())) })
            "poles" -> ev(YdEvent.SetSettings { s -> s.copy(poles = on(bare.firstOrNull())) })
            "strict" -> ev(YdEvent.SetSettings { s -> s.copy(strict = on(bare.firstOrNull())) })
            "mute" -> when (bare.firstOrNull()) { "1" -> ev(YdEvent.SetSettings { s -> s.copy(muted = true) }); "0" -> ev(YdEvent.SetSettings { s -> s.copy(muted = false) }); else -> ev(YdEvent.Mute) }
            "gaze" -> {
                val sc = ActiveScene.current
                val a = bare.firstOrNull()
                if (a == null || a == "off") sc?.inputs?.debugGaze = null
                else {
                    val (yaw, pitch) = a.split(',').map { it.toFloat() }.let { it[0] to it.getOrElse(1) { 0f } }
                    val seat = sc?.seat ?: return "not seated"
                    val q = Quat4.axisAngle(Vec3.UP, seat.yaw + Math.toRadians(-yaw.toDouble()).toFloat()) * Quat4.axisAngle(Vec3.RIGHT, Math.toRadians(pitch.toDouble()).toFloat())
                    sc.inputs.debugGaze = HeadPose(seat.eye, q)
                }
            }
            "eye" -> {
                val off = (bare.firstOrNull() ?: "off") == "off"
                ActiveScene.current?.inputs?.eyeOff = off
                c.debugSet { it.copy(eyeOff = off) }
            }
            "head" -> {
                val a = bare.firstOrNull()
                ActiveScene.current?.debugHead = if (a == null || a == "off") null else a.split(',').map { it.toFloat() }.let { -it[0] to it.getOrElse(1) { 0f } }
            }
            "view" -> {
                val a = (bare.firstOrNull() ?: "0").split(',').map { it.toFloat() }
                val sc = ActiveScene.current ?: return "no scene"
                sc.debugHead = if (a[0] == 0f && a.size < 2) null else -a[0] to a.getOrElse(1) { 0f }
                sc.debugView(a[0], a.getOrElse(1) { 0f })
            }
            "lost" -> forceLost(on(bare.firstOrNull()))
            "seated" -> ev(YdEvent.Seated(reseat = st.seatedOnce))
            "reseat" -> ev(YdEvent.Reseat)
            "controls" -> Probe.controlsOf(bare.firstOrNull() ?: "P1").forEach { (n, b) -> YdLog.i(String.format(java.util.Locale.US, "CONTROL %s x=%.0f y=%.0f w=%.0f h=%.0f", n, b.cx, b.cy, b.w, b.h)) }
            "tour" -> when (bare.firstOrNull() ?: "next") {
                "start" -> ev(YdEvent.TourStart)
                "skip-rear" -> ev(YdEvent.TourSkipRear)
                else -> ev(YdEvent.TourNext)
            }
            "headoff" -> {
                val a = bare.firstOrNull()
                ActiveScene.current?.debugHeadOffset = if (a == null || a == "off") null else a.split(',').map { it.toFloat() }.let { Vec3(it[0], it[1], it[2]) }
            }
            "vignette" -> ActiveScene.current?.debugVignette = bare.firstOrNull()?.takeIf { it != "off" }?.toFloat()
            "vol" -> ev(YdEvent.SetSettings { s -> s.copy(master = kv["master"]?.toInt() ?: st.settings.master, carVol = kv["car"]?.toInt() ?: st.settings.carVol, cueVol = kv["cue"]?.toInt() ?: st.settings.cueVol) })
            "askclear" -> { ev(YdEvent.OpenScorecard); ev(YdEvent.AskClear) }
            "flash-hold" -> c.debugFlashHold = on(bare.firstOrNull())
            "eye-ask" -> c.debugEyeDenied((bare.firstOrNull() ?: "2").toInt())
            "turnon" -> ev(YdEvent.TurnOnEye)
            "pause" -> ev(YdEvent.Pause)
            "resume" -> ev(YdEvent.Resume)
            "home" -> ev(YdEvent.Home)
            "restart" -> ev(YdEvent.Restart)
            // the pause page's confirm row (D44): yes / no, the same events as its two keys
            "confirm" -> when (bare.firstOrNull() ?: "yes") {
                "no", "0" -> ev(YdEvent.CancelConfirm)
                else -> when (st.confirm) {
                    Confirm.RESTART -> ev(YdEvent.ConfirmRestart)
                    Confirm.HOME -> ev(YdEvent.ConfirmHome)
                    Confirm.EXIT -> ev(YdEvent.Exit)
                    Confirm.CLEAR -> ev(YdEvent.ConfirmClear)
                    Confirm.NONE -> return "no confirm open"
                }
            }
            "back" -> ev(YdEvent.Back)
            "exitask" -> { ev(YdEvent.OpenSettings); ev(YdEvent.AskExit) }
            "quit" -> { ev(YdEvent.OpenSettings); ev(YdEvent.AskExit); ev(YdEvent.Exit) }
            "settings" -> { ev(YdEvent.OpenSettings); kv["page"]?.let { ev(YdEvent.SettingsPage(it.toInt())) } }
            "dump" -> { ActiveScene.current?.dump()?.forEach { YdLog.i("DUMP $it") }; dumpRun() }
            "state" -> dumpRun()
            "event" -> return "use goto"
            else -> return "unknown verb $verb"
        }
        return "ok"
    }

    private fun forceLost(on: Boolean) {
        ActiveScene.current?.debugLost(on)
        val r = c.debugRun()
        if (on) r.setGo(false)
    }

    private fun dumpRun() {
        val r = c.debugRun()
        YdLog.i("DUMP run mode=${r.cfg.mode} lesson=${r.cfg.lesson} rules=${r.cfg.rules} seg=${r.seg} phase=${r.phase} pose=${r.pose} wheel=${r.sim.wheel} gear=${r.sim.gear} v=${r.sim.v} elapsed=${r.elapsed} stops=${r.stops} outcome=${r.outcome} hits=${r.hits}")
    }

    private fun outcomeOf(s: String) = when (s.lowercase()) { "out" -> Outcome.OUT; "notin" -> Outcome.NOTIN; "timeout" -> Outcome.TIMEOUT; "order" -> Outcome.ORDER; "stop" -> Outcome.STOP; else -> Outcome.PASS }

    private fun seatOf(s: String) = when (s.lowercase()) { "low" -> 0; "high" -> 2; else -> 1 }

    private fun pose(kv: Map<String, String>): Pose? = kv["pose"]?.split(',')?.map { it.toDouble() }?.let { Pose.deg(it[0], it[1], it[2]) }

    /** The run a design state needs: lesson n, or the mock test for rules=exam. */
    private fun startRunFor(kv: Map<String, String>) {
        val st = c.state.value
        val lesson = kv["lesson"]?.toInt()
        val exam = kv["rules"] == "exam"
        when {
            exam -> ev(YdEvent.OpenMock)
            lesson != null -> { c.debugLesson(lesson); c.onEvent(YdEvent.OpenLesson(lesson, demo = false)) }
            else -> { c.debugLesson(6); c.onEvent(YdEvent.OpenLesson(6, demo = false)) }
        }
        if (kv["step-by-step"] == "1" && c.debugRun().cfg.stepByStep.not()) c.startRun(c.debugRun().cfg.copy(stepByStep = true))
        if (st.screen == Screen.RESULT) Unit
    }

    private fun segStart(r: Run, i: Int): Pose =
        when (r.cfg.segments.getOrNull(i)) {
            SegId.II, SegId.IV -> Yard.inBay
            SegId.III -> Yard.leftStart(r.cfg.gap)
            else -> r.cfg.start
        }

    private fun goto(id: String, kv: Map<String, String>): String {
        kv["seat"]?.let { ev(YdEvent.Seat(seatOf(it))) }
        kv["lang"]?.let { ev(YdEvent.SetSettings { s -> s.copy(zh = it == "zh") }) }
        c.holdFinished = false
        when (id) {
            "S00" -> c.debugSet { it.copy(screen = Screen.LOADING, paused = false) }
            "S01" -> { home(); c.debugSet { it.copy(screen = Screen.SIT, from = Screen.HOME) } }
            "S02" -> home()
            "S03" -> { home(); ev(YdEvent.OpenLessons) }
            "S04" -> { home(); ev(YdEvent.OpenLibrary); kv["lp"]?.let { ev(YdEvent.PickLp(it.toInt())) } }
            "S05" -> {
                home()
                val n = kv["lesson"]?.toInt() ?: 3
                c.debugLesson(n)
                ev(YdEvent.OpenLesson(n, demo = true))
                kv["t"]?.toDouble()?.let { c.debugAdvance(it) }
                if (kv["top"] == "1") ev(YdEvent.TopOnly)
            }
            "S06" -> {
                startRunFor(kv)
                val r = c.debugRun()
                val i = ((kv["seg"]?.toInt() ?: 1) - 1).coerceIn(0, r.cfg.segments.size - 1)
                if (i > 0) {
                    r.jumpToSegment(i, segStart(r, i))
                    r.shift(r.seg.gear)
                }
                c.publish()
            }
            "S07", "S08", "S19", "S25", "S27" -> {
                if (id == "S27") startRunFor(kv + ("lesson" to "2")) else startRunFor(kv)
                val r = c.debugRun()
                val i = ((kv["seg"]?.toInt() ?: 1) - 1).coerceIn(0, r.cfg.segments.size - 1)
                if (i > 0) r.jumpToSegment(i, segStart(r, i))
                r.shift(kv["gear"]?.let { if (it.uppercase() == "D") Gear.D else Gear.R } ?: r.seg.gear)
                if (id == "S25") {
                    // step-by-step: drive to the LP2 truth, the car stops itself there
                    r.place(Pose(Truth.LOCK_I_X + 0.5, r.cfg.start.z, 0.0))
                    r.setGo(true)
                    c.debugAdvance(5.0) { run -> run.events.any { it is RunEvent.AutoStop } }
                    c.debugAdvance(0.5)
                    r.frozen = true
                } else {
                    val lp = kv["lp"]?.let { LookPoints.byN(it.toInt()) }
                    val p = lp?.pose ?: pose(kv) ?: if (id == "S27") Pose(r.cfg.start.x - 3.0, r.cfg.start.z, 0.0) else null
                    if (p != null) r.place(p, lp?.wheel ?: kv["steer"]?.toDouble() ?: 0.0) else kv["steer"]?.let { r.handWheel(it.toDouble()) }
                    val t = kv["t"]?.toDouble() ?: 0.0
                    val exam = r.cfg.rules == Rules.EXAM
                    when (id) {
                        // as the judge would have it: a stop at the end of the segment is never charged (D31)
                        "S08" -> r.debugHold(RunPhase.STOPPED, t, if (exam && t >= 2.0 && !Truth.atEnd(r.seg, r.pose, r.cfg.start)) 1 else 0, kv["clock"]?.toDouble() ?: 23.0)
                        else -> r.debugHold(RunPhase.DRIVING, 0.0, 0, kv["clock"]?.toDouble() ?: 23.0)
                    }
                    if (id == "S19") forceLost(true)
                }
                c.publish()
            }
            "S09" -> {
                startRunFor(kv)
                c.debugAutopilotSet(Autopilot())
                c.debugAdvance(90.0) { it.phase == RunPhase.SEG_DONE }
                c.debugAutopilotSet(null)
            }
            "S10" -> {
                startRunFor(kv + ("lesson" to (kv["lesson"] ?: "3")))
                val line = kv["line"] ?: "bay-right"
                c.debugAutopilotSet(Autopilot(lockEarly = if (line == "bay-left") -0.30 else 0.40))
                c.debugAdvance(60.0) { it.phase == RunPhase.LINE }
                c.debugAutopilotSet(null)
            }
            "S11" -> {
                startRunFor(kv)
                c.holdFinished = true
                c.debugAutopilotSet(Autopilot())
                c.debugAdvance(150.0)
                c.debugAutopilotSet(null)
            }
            "S12" -> { c.debugResult(outcomeOf(kv["outcome"] ?: "pass"), kv["stops"]?.toInt() ?: 0); kv["page"]?.let { ev(YdEvent.ResultPage(it.toInt())) } }
            "S13" -> exec("replay t=${kv["t"] ?: "6.5"}")
            "S14" -> {
                ev(YdEvent.OpenMock)
                val r = c.debugRun()
                pose(kv)?.let { r.place(it, kv["steer"]?.toDouble() ?: 0.0) }
                r.shift(Gear.R)
                r.debugHold(RunPhase.DRIVING, 0.0, 0, kv["clock"]?.toDouble() ?: 83.0)
                c.publish()
            }
            "S15" -> c.debugFree(kv["seed"]?.toInt() ?: 42, kv["diff"]?.toInt() ?: 3)
            "S16" -> { if (kv["seed-scores"] == "1") c.debugSeedScores(); home(); ev(YdEvent.OpenScorecard) }
            "S17" -> { home(); ev(YdEvent.OpenSettings); ev(YdEvent.SettingsPage(0)); kv["page"]?.let { ev(YdEvent.SettingsPage(it.toInt())) }; if (kv["confirm"] == "exit") ev(YdEvent.AskExit); if (kv["about"] == "1") ev(YdEvent.About) }
            "S18" -> { startRunFor(kv); c.debugRun().shift(Gear.R); ev(YdEvent.Pause) }
            "S20" -> ev(YdEvent.Blocked("debug"))
            "S21" -> ev(YdEvent.SetSettings { s -> s.copy(zh = false) })
            "S22" -> { ActiveScene.current?.inputs?.eyeOff = true; c.debugSet { it.copy(eyeOff = true) } }
            "S23" -> {
                startRunFor(kv + ("lesson" to (kv["lesson"] ?: "2")))
                val r = c.debugRun()
                r.shift(Gear.R)
                if (!r.stepMode) r.stepMode = true
                repeat(kv["n"]?.toInt() ?: 3) {
                    r.step()
                    c.debugAdvance(2.0) { run -> !run.sim.stepping && !run.sim.moving && run.sim.odoBackward > 0 }
                    c.debugAdvance(0.1)
                }
                c.publish()
            }
            "S26" -> { c.debugLesson(1); ev(YdEvent.OpenLesson(1)); val step = kv["step"]?.toInt() ?: 0; if (step > 0) c.debugTour(step, kv["lp"]?.toInt() ?: 1, kv["line"]?.toInt() ?: 0) }
            else -> return "unknown state $id"
        }
        return "ok"
    }

    @Suppress("unused")
    private val keep = listOf(Mode.LESSON, RunConfig(), LookPoints, Confirm.NONE)
}
