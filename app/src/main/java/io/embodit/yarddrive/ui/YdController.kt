package io.embodit.yarddrive.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import io.embodit.yarddrive.BuildConfig
import io.embodit.yarddrive.domain.Autopilot
import io.embodit.yarddrive.domain.Car
import io.embodit.yarddrive.domain.Copy
import io.embodit.yarddrive.domain.Free
import io.embodit.yarddrive.domain.Gear
import io.embodit.yarddrive.domain.Hit
import io.embodit.yarddrive.domain.Look
import io.embodit.yarddrive.domain.LookPoints
import io.embodit.yarddrive.domain.Mode
import io.embodit.yarddrive.domain.MrdReadout
import io.embodit.yarddrive.domain.Outcome
import io.embodit.yarddrive.domain.P2
import io.embodit.yarddrive.domain.Pose
import io.embodit.yarddrive.domain.Reason
import io.embodit.yarddrive.domain.Predictor
import io.embodit.yarddrive.domain.Rules
import io.embodit.yarddrive.domain.Run
import io.embodit.yarddrive.domain.RunConfig
import io.embodit.yarddrive.domain.RunEvent
import io.embodit.yarddrive.domain.RunPhase
import io.embodit.yarddrive.domain.RunRecord
import io.embodit.yarddrive.domain.SegId
import io.embodit.yarddrive.domain.Settings
import io.embodit.yarddrive.domain.Stats
import io.embodit.yarddrive.domain.Tr
import io.embodit.yarddrive.domain.Truth
import io.embodit.yarddrive.domain.Yard
import io.embodit.yarddrive.platform.Sound
import io.embodit.yarddrive.platform.Store
import io.embodit.yarddrive.platform.YdLog
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the panels, the controllers and the debug receiver can ask for (debug commands send these same events). */
sealed class YdEvent {
    data object SceneReady : YdEvent()
    data class Seated(val reseat: Boolean = false) : YdEvent()
    data class Seat(val i: Int) : YdEvent()
    data object SeatCycle : YdEvent()
    data object OpenLessons : YdEvent()
    data object OpenLibrary : YdEvent()
    data object OpenMock : YdEvent()
    data object OpenFree : YdEvent()
    data class FreeLevel(val d: Int, val seed: Int? = null) : YdEvent()
    data object OpenScorecard : YdEvent()
    data object OpenSettings : YdEvent()
    data object Back : YdEvent()
    data object Home : YdEvent()
    data class OpenLesson(val n: Int, val demo: Boolean? = null) : YdEvent()
    data object SkipDemo : YdEvent()
    data object TopOnly : YdEvent()
    data class PickLp(val n: Int) : YdEvent()
    data object PrevLp : YdEvent()
    data object NextLp : YdEvent()
    data class Steer(val target: Double) : YdEvent()
    data object GearToggle : YdEvent()
    data class GearSet(val g: Gear) : YdEvent()
    data class Go(val on: Boolean) : YdEvent()
    data object GoToggle : YdEvent()
    data object StepOnce : YdEvent()
    data object StepMode : YdEvent()
    data object Easy : YdEvent()
    data object Pause : YdEvent()
    data object Resume : YdEvent()
    data object Restart : YdEvent()
    data object BackToPoint : YdEvent()
    data object CarryOn : YdEvent()
    data class ResultPage(val i: Int) : YdEvent()
    data object GoAgain : YdEvent()
    data object NextLesson : YdEvent()
    data object RedoLesson : YdEvent()
    data object LessonList : YdEvent()
    data object AnotherGroup : YdEvent()
    data object WatchReplay : YdEvent()
    data class ReplaySpeed(val s: Double) : YdEvent()
    data object EndReplay : YdEvent()
    /**
     * A change, applied here to the latest settings. Never a whole Settings copied in a panel: the design Slider keeps the
     * onValueChange of its first composition, so a copy made there puts every other setting back (k4-1, pico-cli-feedback).
     */
    data class SetSettings(val change: (Settings) -> Settings) : YdEvent()
    data class SettingsPage(val i: Int) : YdEvent()
    data object About : YdEvent()
    data object AskExit : YdEvent()
    data object AskClear : YdEvent()
    data object CancelConfirm : YdEvent()
    data object ConfirmRestart : YdEvent()
    data object ConfirmHome : YdEvent()
    data object ConfirmClear : YdEvent()
    data object Exit : YdEvent()
    data object Reseat : YdEvent()
    data object Mute : YdEvent()
    data object TurnOnEye : YdEvent()
    data object TourStart : YdEvent()
    data object TourNext : YdEvent()
    data object TourSkipRear : YdEvent()
    data object SkipLesson : YdEvent()
    data class Background(val on: Boolean) : YdEvent()
    data class Blocked(val reason: String) : YdEvent()
}

/** Per-frame inputs read by the scene (tracking stays in the ECS; only these values cross over). */
data class FrameInput(
    val dt: Double,
    val aEdge: Boolean = false,
    val xEdge: Boolean = false,
    val yEdge: Boolean = false,
    val stickX: Float = 0f,
    val look: Look = Look.AHEAD,
    val lookByEyes: Boolean = false,
    val lost: Boolean = false,
)

/**
 * ALL behaviour: events -> state, and the per-frame run (design states S00-S27). Main thread only. The scene calls
 * [tick] from the ECS frame system; panels and the debug receiver call [onEvent].
 */
object YdController {
    private val _state = MutableStateFlow(YdUiState())
    val state: StateFlow<YdUiState> = _state
    private val s: YdUiState get() = _state.value
    private fun set(f: (YdUiState) -> YdUiState) {
        val before = _state.value
        val after = f(before)
        _state.value = after
        if (before.designState() != after.designState() || before.modifiers() != after.modifiers()) YdLog.i("STATE ${after.designState()} mods=[${after.modifiers()}] screen=${after.screen} phase=${after.run.phase}")
    }

    val sound = Sound()
    private var store: Store? = null
    var activity: Activity? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The car you sit in: always a run (parked at the right start outside a practice). */
    var run: Run = Run(RunConfig(mode = Mode.LESSON, segments = listOf(SegId.I)))
        private set
    private var autopilot: Autopilot? = null
    private var lastRun: Run? = null
    private var lastCfg: RunConfig? = null
    private var lesson = 0
    private var lessonTrial = 0
    private var lessonPasses = 0
    private var free: FreeInfo? = null
    private var freeScores = ArrayList<Pair<Boolean, Int>>()
    private var started = false
    private var finishedAtMs = 0L
    private var predictJob: Job? = null
    private var predictClock = 0.0
    private var telemetryClock = 0.0
    private var cue: Pair<String, SegId>? = null
    private var cueAdvice: Predictor.Advice? = null
    private var lastWheelTick = 0.0
    private var clock = 0.0
    private var flashUntil = 0.0
    private var gearNoteUntil = 0.0
    private var goNoteUntil = 0.0
    private var glowUntil = 0.0
    private var glowSet: Set<Char> = emptySet()
    private var lostFor = 0.0
    private var replay: List<io.embodit.yarddrive.domain.Sample> = emptyList()
    private var idealPath: List<P2> = emptyList()
    private val lockCache = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private var lockJob: kotlinx.coroutines.Job? = null
    private var eyeSeenFor = 0.0
    private var eyeMissFor = 0.0
    private var tourStep = 0
    private var tourLit = BooleanArray(4)
    private var tourLine = 0
    private var tourLp = 1
    private var tourSeen = HashSet<Int>()
    private var runSerial = 0

    /** The placing flow starts (profile D29: "I'm seated" and "Re-seat" are this one thing). */
    var onPlace: (() -> Unit)? = null

    /** The scene has a live head pose; without one P1 must not go away for a flow that cannot run (R4). */
    var headReady: (() -> Boolean)? = null
    private var placingSeated: YdEvent.Seated? = null
    var onQuit: (() -> Unit)? = null

    fun start(context: Context) {
        if (started) return
        started = true
        val st = Store(context.applicationContext)
        store = st
        val settings = st.settings.let { if (!st.hasLang()) it.copy(zh = java.util.Locale.getDefault().language == "zh") else it }
        sound.muted = settings.muted
        sound.setVolumes(settings.master, settings.carVol, settings.cueVol)
        set { it.copy(settings = settings, passedLessons = st.lessonsPassed, demosSeen = st.demosSeen, recordCount = st.records.size, version = BuildConfig.VERSION_NAME) }
        parkAtStart()
        YdLog.i("CONTROLLER start settings=$settings")
    }

    private fun settings() = s.settings

    // =========================================================================================== events

    fun onEvent(e: YdEvent) {
        YdLog.i("EVENT $e")
        when (e) {
            YdEvent.SceneReady -> if (s.screen == Screen.LOADING) { preheat(); set { it.copy(screen = Screen.SIT) } }
            is YdEvent.Seated -> startPlacing(e)
            is YdEvent.Seat -> saveSettings(settings().copy(seat = e.i.coerceIn(0, 2)))
            YdEvent.SeatCycle -> saveSettings(settings().copy(seat = (settings().seat + 1) % 3))
            YdEvent.OpenLessons -> set { it.copy(screen = Screen.LESSONS) }
            YdEvent.OpenLibrary -> { parkAtStart(); set { it.copy(screen = Screen.LIBRARY) } }
            YdEvent.OpenMock -> afterLock { startRun(mockCfg()) }
            YdEvent.OpenFree -> set { it.copy(screen = Screen.FREE_PICK) }
            is YdEvent.FreeLevel -> { free = FreeInfo(1, Free.GROUP, e.d, e.seed ?: (1000 + (Math.random() * 9000).toInt())); freeScores.clear(); startFree() }
            YdEvent.OpenScorecard -> openScorecard()
            YdEvent.OpenSettings -> set { it.copy(screen = Screen.SETTINGS, from = if (it.screen == Screen.SETTINGS || it.screen == Screen.ABOUT) it.from else it.screen, confirm = Confirm.NONE) }
            YdEvent.Back -> back()
            // D44: leaving a run (or the tour) half-way loses it, so it asks first, on the pause page (q7-7)
            YdEvent.Home -> if (hasProgress()) ask(Confirm.HOME) else goHome()
            YdEvent.ConfirmHome -> goHome()
            is YdEvent.OpenLesson -> openLesson(e.n, e.demo)
            YdEvent.SkipDemo -> if (s.screen == Screen.DEMO) startLessonRun()
            YdEvent.TopOnly -> set { it.copy(demoTopOnly = !it.demoTopOnly) }
            is YdEvent.PickLp -> pickLp(e.n)
            YdEvent.PrevLp -> pickLp(if (s.screen == Screen.TOUR) (tourLp - 1).coerceAtLeast(1) else (s.lp + 10) % 12 + 1)
            YdEvent.NextLp -> pickLp(if (s.screen == Screen.TOUR) (tourLp + 1).coerceAtMost(5) else s.lp % 12 + 1)
            is YdEvent.Steer -> if (driving()) run.steerButton(e.target)
            YdEvent.GearToggle -> if (driving()) shift(if (run.sim.gear == Gear.R) Gear.D else Gear.R)
            is YdEvent.GearSet -> if (driving()) shift(e.g)
            is YdEvent.Go -> if (driving()) run.setGo(e.on)
            YdEvent.GoToggle -> {
                // P2 key and A button: one tap goes, the next stops. When it can't, it says why (the user tapped and nothing happened)
                val why = s.goReason()
                when {
                    why != null -> refuseGo(why)
                    !s.paused -> {
                        run.setGo(!run.sim.go)
                        YdLog.i("GO ${run.sim.go}")
                        publish()
                    }
                }
            }
            // the step key and the A button in step mode: the same check as Go, a wrong gear does not move the car (q7-4)
            YdEvent.StepOnce -> {
                val why = s.goReason()
                if (why != null) refuseGo(why) else if (driving()) run.step()
            }
            YdEvent.StepMode -> toggleStepMode()
            YdEvent.Easy -> toggleEasy()
            YdEvent.Pause -> if (s.screen in setOf(Screen.RUN, Screen.DEMO, Screen.TOUR)) { run.setGo(false); set { it.copy(paused = true) } }
            YdEvent.Resume -> { confirmUnpauses = false; set { it.copy(paused = false, confirm = Confirm.NONE) } }
            YdEvent.Restart -> if (hasProgress()) ask(Confirm.RESTART) else { set { it.copy(paused = false, confirm = Confirm.NONE) }; restart() }
            YdEvent.ConfirmRestart -> { confirmUnpauses = false; set { it.copy(paused = false, confirm = Confirm.NONE) }; restart() }
            YdEvent.BackToPoint -> { set { it.copy(paused = false) }; if (s.screen == Screen.RUN && run.rules == Rules.LESSON) { run.backToPoint(); cue = null; publish() } }
            YdEvent.CarryOn -> { run.carryOn(); publish() }
            is YdEvent.ResultPage -> set { it.copy(resultPage = e.i.coerceIn(0, 2)) }
            YdEvent.GoAgain -> goAgain()
            YdEvent.NextLesson -> openLesson((lesson + 1).coerceAtMost(8), null)
            YdEvent.RedoLesson -> { lessonTrial = 0; lessonPasses = 0; openLesson(lesson, false) }
            YdEvent.LessonList -> set { it.copy(screen = Screen.LESSONS) }
            YdEvent.AnotherGroup -> { val f = free; if (f != null) { free = FreeInfo(1, Free.GROUP, f.difficulty, f.seed + 7); freeScores.clear(); startFree() } }
            YdEvent.WatchReplay -> startReplay()
            is YdEvent.ReplaySpeed -> set { it.copy(replaySpeed = e.s) }
            YdEvent.EndReplay -> endReplay()
            is YdEvent.SetSettings -> saveSettings(e.change(settings()))
            is YdEvent.SettingsPage -> set { it.copy(settingsPage = e.i.coerceIn(0, 1), confirm = Confirm.NONE) }
            YdEvent.About -> set { it.copy(screen = Screen.ABOUT) }
            YdEvent.AskExit -> set { it.copy(confirm = Confirm.EXIT) }
            YdEvent.AskClear -> set { it.copy(confirm = Confirm.CLEAR) }
            // a confirm raised while driving (P2 Restart run at a line) goes back to where you were, not to the pause page
            YdEvent.CancelConfirm -> { val unpause = confirmUnpauses; confirmUnpauses = false; set { it.copy(confirm = Confirm.NONE, paused = if (unpause) false else it.paused) } }
            YdEvent.ConfirmClear -> { store?.clearScores(); set { it.copy(confirm = Confirm.NONE, recordCount = 0, passedLessons = emptySet(), score = scoreView(emptyList())) } }
            YdEvent.Exit -> quit()
            YdEvent.Reseat -> startPlacing(null)
            YdEvent.Mute -> saveSettings(settings().copy(muted = !settings().muted))
            YdEvent.TurnOnEye -> requestEye()
            YdEvent.TourStart -> { parkAtStart(); tourStep = 1; tourLit = BooleanArray(4); tourSeen.clear(); set { it.copy(screen = Screen.TOUR, tourFlash = -1) }; publishTour() }
            YdEvent.TourNext -> tourNext()
            YdEvent.TourSkipRear -> { tourLit[3] = true; publishTour() }
            YdEvent.SkipLesson -> { set { it.copy(screen = Screen.LESSONS) } }
            is YdEvent.Background -> if (e.on && s.screen in setOf(Screen.RUN, Screen.DEMO, Screen.TOUR)) { run.setGo(false); set { it.copy(paused = true) } }
            // S20 is the page to show, also when it comes during a pause (k2-5)
            is YdEvent.Blocked -> { placingSeated = null; set { it.copy(screen = Screen.UNAVAILABLE, paused = false, reseating = false) } }
        }
    }

    private fun driving() = s.screen == Screen.RUN && !s.paused

    private fun refuseGo(why: Tr) {
        YdLog.i("GO refused ${why.en}")
        goNoteUntil = clock + 3.0
        sound.cue()
        set { it.copy(goNote = why) }
    }

    /** Set when a confirm paused a run that was not paused: Cancel lets it go on. */
    private var confirmUnpauses = false

    /** Would Restart / Home lose something: a run that has moved off or been on the clock, a tour past its first step (D44). */
    internal fun hasProgress(): Boolean =
        when (s.screen) {
            Screen.RUN -> run.phase != RunPhase.FINISHED && (run.started || run.elapsed > 1.0)
            Screen.TOUR -> tourStep > 1 || tourSeen.isNotEmpty()
            else -> false
        }

    /** The confirm row sits on the pause page (P1): the run stops first, so nothing moves while you decide. */
    private fun ask(c: Confirm) {
        if (!s.paused) confirmUnpauses = true
        run.setGo(false)
        YdLog.i("CONFIRM ask $c")
        set { it.copy(paused = true, confirm = c) }
    }

    private fun shift(g: Gear) {
        val ok = run.shift(g)
        if (!ok) {
            // R9b: the beep comes from RunEvent.GearRefused; say why too (muted, it would be silent)
            gearNoteUntil = clock + 1.5
            set { it.copy(gearNote = true) }
        }
        publish()
    }

    private fun saveSettings(n: Settings) {
        val o = settings()
        if (n != o) YdLog.i("SETTINGS $n")
        store?.settings = n
        sound.muted = n.muted
        sound.setVolumes(n.master, n.carVol, n.cueVol)
        set { it.copy(settings = n) }
        if (n.speed != o.speed) run.sim.vSet = n.speed
        if (n.zh != o.zh) publish()
    }

    private fun back() {
        when (s.screen) {
            Screen.SETTINGS -> set { it.copy(screen = it.from.takeIf { f -> f != Screen.SETTINGS && f != Screen.ABOUT && f != Screen.SIT } ?: Screen.HOME, confirm = Confirm.NONE) }
            Screen.ABOUT -> set { it.copy(screen = Screen.SETTINGS) }
            Screen.FREE_PICK, Screen.LESSONS, Screen.LIBRARY, Screen.SCORECARD -> goHome()
            Screen.POINT -> set { it.copy(screen = Screen.LIBRARY) }
            else -> if (hasProgress()) ask(Confirm.HOME) else goHome()
        }
    }

    fun goHome() {
        confirmUnpauses = false
        autopilot = null
        parkAtStart()
        set { it.copy(screen = Screen.HOME, paused = false, confirm = Confirm.NONE, demoTopOnly = false) }
    }

    private fun quit() {
        YdLog.i("EXIT finishAndRemoveTask")
        sound.release()
        onQuit?.invoke()
        val a = activity
        if (a != null) a.finishAndRemoveTask()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun askEyeOnce() {
        val st = store ?: return
        if (st.eyeAsked) return
        st.eyeAsked = true
        requestEye()
    }

    private fun requestEye() {
        val a = activity ?: return
        val st = store
        runCatching {
            if (a.checkSelfPermission(EYE_PERMISSION) == PackageManager.PERMISSION_GRANTED) return
            val denied = st?.eyeDenied ?: 0
            // R9c: after real denials the system stops showing the dialog; requestPermissions would just do nothing
            if (denied >= 2 || denied >= 1 && !a.shouldShowRequestPermissionRationale(EYE_PERMISSION)) {
                set { it.copy(eyeNote = true) }
                YdLog.i("EYE open system settings denied=$denied")
                a.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                a.requestPermissions(arrayOf(EYE_PERMISSION), EYE_REQUEST)
            }
        }.onFailure {
            set { s0 -> s0.copy(eyeNote = true) }
            YdLog.w("EYE permission request failed: $it")
        }
    }

    /** The system dialog was answered (S2: a dialog that went away unanswered does not count). */
    fun onEyeAnswered(granted: Boolean) {
        val st = store ?: return
        if (!granted) st.eyeDenied = st.eyeDenied + 1
        YdLog.i("EYE answered granted=$granted denied=${st.eyeDenied}")
    }

    private object SeatLook {
        const val START_S = 1.2
    }

    /** Debug: pretend the system dialog has been answered "don't allow" [n] times. */
    fun debugEyeDenied(n: Int) {
        store?.eyeDenied = n
    }

    fun eyeGranted(): Boolean = activity?.let { runCatching { it.checkSelfPermission(EYE_PERMISSION) == PackageManager.PERMISSION_GRANTED }.getOrDefault(false) } ?: false

    // =========================================================================================== runs

    private fun parkAtStart() {
        run = Run(RunConfig(mode = Mode.LESSON, segments = listOf(SegId.I), gap = settings().gap, start = Yard.rightStart(settings().gap)))
        run.frozen = true
        autopilot = null
        idealPath = emptyList()
        publish()
    }

    private fun mockCfg() = RunConfig(mode = Mode.MOCK, lesson = 0, rules = Rules.EXAM, strict = settings().strict, timed = true, gap = settings().gap, start = Yard.rightStart(settings().gap), speed = settings().speed, lockX = lockX(settings().gap, settings().speed))

    /** The LP2 truth: the design's 3.52 at the default gap and speed, otherwise from the look-ahead (design: g and ramp change it). */
    private fun lockX(gap: Double, speed: Double, start: Pose? = null): Double {
        if (start == null && abs(gap - Yard.GAP_DEFAULT) < 1e-6 && abs(speed - Car.SPEED_DEFAULT) < 1e-6) return Truth.LOCK_I_X
        val st = start ?: Yard.rightStart(gap)
        val key = "${st.x},${st.z},${st.psi},$speed"
        return lockCache.getOrPut(key) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            Predictor.bestLock(st, vSet = speed).also { YdLog.i("TIMING lockX ms=${android.os.SystemClock.elapsedRealtime() - t0} speed=$speed gap=$gap") }
        }
    }

    private fun lessonCfg(n: Int): RunConfig {
        val st = settings()
        val gap = st.gap
        val base = RunConfig(mode = Mode.LESSON, lesson = n, gap = gap, speed = st.speed, start = Yard.rightStart(gap), lockX = lockX(gap, st.speed))
        return when (n) {
            2 -> base.copy(rules = Rules.LESSON, segments = listOf(SegId.LANE1, SegId.LANE2))
            3 -> base.copy(rules = Rules.LESSON, lenient = true, stepByStep = true, segments = listOf(SegId.I))
            4 -> base.copy(rules = Rules.LESSON, lenient = true, stepByStep = true, segments = listOf(SegId.II), start = Yard.inBay, startGear = Gear.R)
            5 -> base.copy(rules = Rules.LESSON, lenient = true, stepByStep = true, segments = listOf(SegId.III), start = Yard.leftStart(gap))
            6 -> base.copy(rules = Rules.LESSON, lenient = true)
            7 -> base.copy(rules = Rules.EXAM, strict = st.strict)
            else -> base.copy(rules = Rules.EXAM, strict = st.strict, timed = true)
        }
    }

    /** Hints / top view / poles per lesson (design content.lessons); the settings switch them off everywhere. */
    fun hintsOn(): Boolean = settings().hints && (run.cfg.mode == Mode.FREE || run.cfg.mode == Mode.LESSON && run.cfg.lesson in 1..6)

    fun topViewOn(): Boolean = settings().topView && (run.cfg.mode == Mode.FREE || run.cfg.mode == Mode.LESSON && run.cfg.lesson in 1..6) || s.screen == Screen.REPLAY || s.screen == Screen.DEMO || s.screen == Screen.POINT

    fun polesOn(): Boolean = settings().poles && !(run.cfg.mode == Mode.MOCK || run.cfg.mode == Mode.LESSON && run.cfg.lesson >= 7) && !run.cfg.lane

    private fun openLesson(n: Int, demo: Boolean?) {
        if (n != lesson) { lessonTrial = 0; lessonPasses = 0 }
        lesson = n
        when (n) {
            1 -> { parkAtStart(); tourStep = 0; set { it.copy(screen = Screen.TOUR_INTRO) } }
            else -> {
                val seen = n in s.demosSeen
                if (demo == true || (demo == null && !seen)) startDemo(n) else startLessonRun()
            }
        }
    }

    /** The first look at the look-points runs a whole ideal drive: done here, not on the first press or the first cue (R2). A press that gets there first waits on the same lazy. */
    private fun preheat() {
        scope.launch(Dispatchers.Default) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val n = LookPoints.all.size
            YdLog.i("TIMING LookPoints.all ms=${android.os.SystemClock.elapsedRealtime() - t0} points=$n")
        }
    }

    /** With a speed or gap other than the defaults the lock point takes a search: off the main thread, then [then] finds it in the cache (R2). */
    private fun afterLock(then: () -> Unit) {
        val st = settings()
        if (abs(st.gap - Yard.GAP_DEFAULT) < 1e-6 && abs(st.speed - Car.SPEED_DEFAULT) < 1e-6) {
            then()
            return
        }
        val from = s.screen
        lockJob?.cancel()
        lockJob = scope.launch {
            withContext(Dispatchers.Default) { lockX(st.gap, st.speed) }
            if (s.screen == from) then()
        }
    }

    private fun startDemo(n: Int) = afterLock {
        val cfg = lessonCfg(n).copy(mode = Mode.LESSON, rules = Rules.EXAM, strict = false, timed = false, stepByStep = false, stepMode = false, lenient = false, speed = Car.SPEED_DEFAULT, lockX = Truth.LOCK_I_X)
        run = Run(cfg)
        autopilot = Autopilot(waitS = 1.0)
        computeIdeal(cfg)
        store?.let { st -> st.demosSeen = st.demosSeen + n; set { s2 -> s2.copy(demosSeen = st.demosSeen) } }
        cue = null
        set { it.copy(screen = Screen.DEMO, paused = false, demoSub = null, demoTopOnly = false) }
        publish()
    }

    private fun startLessonRun() {
        lessonTrial++
        afterLock { startRun(lessonCfg(lesson)) }
    }

    private fun startFree() {
        val f = free ?: return
        val st = Free.start(f.seed + f.run - 1, f.difficulty, settings().gap)
        // starts easy (user, 2026-11-05: it should be easy to drive); the Easy chip still switches it for the next run
        val easy = run.cfg.mode != Mode.FREE || run.cfg.rules == Rules.LESSON
        val speed = settings().speed
        scope.launch {
            val x0 = withContext(Dispatchers.Default) { lockX(settings().gap, speed, st.pose) }
            startRun(Free.config(settings(), st, easy, x0))
        }
    }

    fun startRun(cfg: RunConfig) {
        debugAutopilot = false
        run = Run(cfg)
        lastCfg = cfg
        autopilot = null
        cue = null
        cueAdvice = null
        finishedAtMs = 0L
        runSerial++
        computeIdeal(cfg)
        set { it.copy(screen = Screen.RUN, paused = false, resultPage = 0, flashLine = null) }
        YdLog.i("RUN start mode=${cfg.mode} lesson=${cfg.lesson} rules=${cfg.rules} lenient=${cfg.lenient} segs=${cfg.segments} start=${cfg.start} lockX=${"%.3f".format(cfg.lockX)}")
        publish()
    }

    private fun restart() {
        val c = lastCfg
        when {
            s.screen == Screen.DEMO -> startDemo(lesson)
            s.screen == Screen.TOUR -> onEvent(YdEvent.TourStart)
            c != null -> startRun(c)
        }
    }

    private fun goAgain() {
        when {
            s.result?.tour != null -> openLesson(1, null)
            run.cfg.mode == Mode.FREE -> { val f = free ?: return; free = f.copy(run = if (f.run >= Free.GROUP) 1 else f.run + 1); if (f.run >= Free.GROUP) freeScores.clear(); startFree() }
            run.cfg.mode == Mode.LESSON -> startLessonRun()
            else -> lastCfg?.let { startRun(it) }
        }
    }

    private fun toggleStepMode() {
        if (s.screen != Screen.RUN || run.cfg.rules == Rules.EXAM || run.phase == RunPhase.FINISHED) return
        run.stepMode = !run.stepMode
        publish()
    }

    private fun toggleEasy() {
        if (s.screen != Screen.RUN || run.cfg.mode != Mode.FREE || run.started) return
        val easy = run.cfg.rules == Rules.EXAM
        startRun(run.cfg.copy(rules = if (easy) Rules.LESSON else Rules.EXAM, lenient = easy, timed = !easy))
    }

    private fun computeIdeal(cfg: RunConfig) {
        scope.launch {
            val path = withContext(Dispatchers.Default) {
                runCatching {
                    val r = Run(cfg.copy(rules = Rules.EXAM, timed = false, strict = false, stepByStep = false, stepMode = false, lenient = false))
                    val ap = Autopilot()
                    var t = 0.0
                    val pts = ArrayList<P2>()
                    var k = 0.0
                    while (t < 120 && r.phase != RunPhase.FINISHED && r.phase != RunPhase.LINE) {
                        ap.drive(r, 0.02)
                        r.tick(0.02)
                        t += 0.02
                        k += 0.02
                        if (k >= 0.2) { k = 0.0; pts += r.pose.rear }
                    }
                    pts
                }.getOrDefault(emptyList())
            }
            idealPath = path
            publish()
        }
    }

    // =========================================================================================== library / tour

    private fun pickLp(n: Int) {
        val lp = LookPoints.byN(n.coerceIn(1, 12))
        if (s.screen == Screen.TOUR) {
            tourLp = n.coerceIn(1, 5)
            tourSeen += tourLp
            place(lp)
            publishTour()
            return
        }
        place(lp)
        set { it.copy(screen = Screen.POINT, lp = lp.n) }
    }

    private fun place(lp: io.embodit.yarddrive.domain.LookPoint) {
        // no fade when the car is already there (the tour goes from its last sentence at the start to LP1)
        val moved = (run.pose.rear - lp.pose.rear).length > 0.05 || abs(run.pose.psiDeg - lp.pose.psiDeg) > 1
        val r = Run(RunConfig(mode = Mode.LESSON, segments = listOf(lp.seg), start = lp.pose, startGear = lp.gear, gap = settings().gap))
        r.place(lp.pose, lp.wheel)
        r.frozen = true
        run = r
        idealPath = emptyList()
        computeIdeal(RunConfig(mode = Mode.LESSON, gap = settings().gap))
        if (moved) fade()
        publish()
    }

    /** 0.5 s fade out / in for jumps (design T15); the scene reads it. */
    var fadeEpoch = 0
        private set

    private fun fade() {
        fadeEpoch++
        YdLog.i("FADE jump epoch=$fadeEpoch")
    }

    private fun tourNext() {
        when (tourStep) {
            1 -> { tourStep = 2; tourLine = 0; showTourLine() }
            2 -> if (tourLine < Copy.tourLines.size - 1) { tourLine++; showTourLine() } else { tourStep = 3; tourLp = 1; tourSeen.clear(); set { it.copy(tourFlash = -1) }; pickLp(1) }
            3 -> if (tourLp < 5) pickLp(tourLp + 1) else finishTour()
        }
        publishTour()
    }

    /**
     * Design S26 2: the line this sentence names flashes 3 times. The bay is behind you at the start, so for the bay's own
     * sentences the car stands in the bay (R3); the mirrors and the top view colour the same lines for the whole sentence.
     */
    private fun showTourLine() {
        val p = Yard.tourPose(tourLine, settings().gap)
        if (run.pose.rear != p.rear) {
            run.place(p, 0.0)
            fade()
        }
        flashUntil = clock + 1.5
        set { it.copy(tourFlash = tourLine) }
        publish()
    }

    private fun finishTour() {
        tourStep = 4
        val passed = tourSeen.size >= 5
        val st = store
        if (passed && st != null) { st.lessonsPassed = st.lessonsPassed + 1; set { it.copy(passedLessons = st.lessonsPassed) } }
        parkAtStart()
        val lit = tourLit.count { it }
        set {
            it.copy(
                screen = Screen.RESULT, resultPage = 0,
                result = ResultView(
                    Copy.lessonList8[0].name, if (passed) Outcome.PASS else Outcome.OUT, passed, 100, 0, 0.0, emptyList(), emptyList(), emptyList(), emptyList(), null, null, null,
                    Tr("口诀是别人的，眼睛是你的。下一课用按键打方向。", "The chant is theirs, the eyes are yours. Next lesson: steer with the keys."), null, emptyList(), null, emptyMap(), 1, 1, 1, passed, passed, false, Mode.LESSON, null, false, tour = tourSeen.size to lit,
                ),
            )
        }
    }

    private fun publishTour() {
        set { it.copy(tour = TourView(tourStep, tourLit.toList(), tourLine, tourLp, tourSeen.size)) }
    }

    // =========================================================================================== replay

    private fun startReplay() {
        val r = lastRun ?: return
        replay = r.trail.toList()
        if (replay.size < 2) return
        val rr = Run(r.cfg)
        rr.frozen = true
        run = rr
        replayHits = r.hits.toList()
        set { it.copy(screen = Screen.REPLAY, replayT = 0.0, replayLen = max(0.1, replay.last().t - replay.first().t), replaySpeed = 1.0, demoTopOnly = false) }
    }

    private var replayHits: List<Hit> = emptyList()

    private fun endReplay() {
        val r = lastRun
        if (r != null) run = r
        set { it.copy(screen = Screen.RESULT) }
    }

    private fun stepReplay(dt: Double) {
        if (replay.size < 2) return
        val t0 = replay.first().t
        val t = (s.replayT + dt * s.replaySpeed).coerceAtMost(s.replayLen)
        val at = t0 + t
        var i = replay.indexOfLast { it.t <= at }.coerceAtLeast(0)
        if (i >= replay.size - 1) i = replay.size - 2
        val a = replay[i]
        val b = replay[i + 1]
        val k = if (b.t - a.t < 1e-6) 0.0 else ((at - a.t) / (b.t - a.t)).coerceIn(0.0, 1.0)
        run.sim.teleport(Pose(a.x + (b.x - a.x) * k, a.z + (b.z - a.z) * k, a.psi + (b.psi - a.psi) * k), a.wheel + (b.wheel - a.wheel) * k)
        run.sim.gear = a.gear
        val near = replayHits.lastOrNull { it.at <= at && it.kind != Hit.Kind.STOP }
        val note = near?.let { h -> val c = Copy.cues[h.lp]?.first; if (c == null) null else Tr("${c.zh} @ %.1f s，%s".format(h.at, earlyLateZh(h.lateCm)), "${c.en} @ %.1f s, %s".format(h.at, earlyLateEn(h.lateCm))) }
        set { it.copy(replayT = t, replayNote = note) }
        if (t >= s.replayLen) endReplay()
    }

    fun earlyLateZh(cm: Int) = if (cm <= 0) Copy.early.zh.format(-cm) else Copy.late.zh.format(cm)

    fun earlyLateEn(cm: Int) = if (cm <= 0) Copy.early.en.format(-cm) else Copy.late.en.format(cm)

    // =========================================================================================== per frame

    fun tick(input: FrameInput) {
        val dt = input.dt
        clock += dt
        val st = s
        eyeTrack(input)
        if (input.aEdge && st.p2) onEvent(if (st.screen == Screen.RUN && run.stepMode && st.goReason() == null) YdEvent.StepOnce else YdEvent.GoToggle)
        if (st.screen == Screen.REPLAY && !st.paused) stepReplay(dt)
        if (st.screen == Screen.TOUR && tourStep == 1 && !st.paused) {
            val i = when (input.look) { Look.MIRROR_L -> 0; Look.MIRROR_R -> 1; Look.MIRROR_I -> 2; Look.REAR -> 3; else -> -1 }
            if (i >= 0 && !tourLit[i]) { tourLit[i] = true; sound.cue(); publishTour() }
        }
        if (st.screen == Screen.POINT) {
            val want = LookPoints.byN(st.lp).look
            val on = input.look == want
            if (on != st.gazeOnLp) set { it.copy(gazeOnLp = on) }
        }
        val active = st.screen == Screen.RUN || st.screen == Screen.DEMO
        if (active) {
            // lost tracking: the car stops, not a mid-stop; 10 s -> pause (design S19)
            lostFor = if (input.lost) lostFor + dt else 0.0
            if (input.lost != st.lost) set { it.copy(lost = input.lost) }
            if (lostFor > 10.0 && !st.paused) set { it.copy(paused = true) }
            if (st.screen == Screen.DEMO) autopilot?.drive(run, dt)
            if (st.screen == Screen.RUN && debugAutopilot && !st.paused) autopilot?.drive(run, dt)
            if (st.screen == Screen.RUN && !st.paused && !input.lost) applyInputs(input)
            run.look = input.look
            val wheel0 = run.sim.wheel
            run.tick(dt, paused = st.paused, lost = input.lost)
            wheelSounds(wheel0, run.sim.wheel)
            drainEvents()
            predict(dt)
            if (run.phase == RunPhase.FINISHED) onFinished()
        } else if (st.lost) set { it.copy(lost = false) }
        if (clock > flashUntil && !debugFlashHold && (st.flashLine != null || st.tourFlash >= 0)) set { it.copy(flashLine = null, tourFlash = -1) }
        if (clock > gearNoteUntil && st.gearNote) set { it.copy(gearNote = false) }
        if (clock > goNoteUntil && st.goNote != null) set { it.copy(goNote = null) }
        if (clock > seatDoneUntil && !debugFlashHold && st.reseatDone) set { it.copy(reseatDone = false) }
        if (clock > seatNoteUntil && !debugFlashHold && st.seatNote != null) set { it.copy(seatNote = null) }
        if (clock > glowUntil && st.glow != glowLp()) set { it.copy(glow = glowLp()) }
        // R6 / D15: the engine only idles in the driving part, not on the home page, settings or the result card
        val driving = !st.paused && (st.screen == Screen.DEMO || st.screen == Screen.REPLAY || st.screen == Screen.RUN && run.phase != RunPhase.FINISHED)
        sound.setLoops(idleOn = driving, tyresOn = driving && run.sim.moving)
        telemetryClock += dt
        if (telemetryClock >= 0.1) {
            telemetryClock = 0.0
            if (active || st.screen == Screen.REPLAY || st.screen == Screen.POINT) publish()
        }
    }

    /** Library / tour: the mirror to look at stays lit (design S04). */
    private fun glowLp(): Set<Char> {
        if (s.screen != Screen.POINT && !(s.screen == Screen.TOUR && tourStep == 3)) return emptySet()
        val lp = LookPoints.byN(if (s.screen == Screen.TOUR) tourLp else s.lp)
        return when (lp.look) { Look.MIRROR_L -> setOf('L'); Look.MIRROR_R -> setOf('R'); Look.MIRROR_I -> setOf('I'); else -> emptySet() }
    }

    private fun applyInputs(input: FrameInput) {
        if (input.xEdge) run.steerButton(0.0)
        if (input.yEdge) shift(if (run.sim.gear == Gear.R) Gear.D else Gear.R)
        // joystick: 1.5 turns / s at full deflection, dead zone 0.15 (design steeringModel.joystick)
        run.sim.stickRate = if (abs(input.stickX) > Car.STICK_DEAD) input.stickX * Car.STICK_RATE else 0.0
    }

    private fun wheelSounds(before: Double, after: Double) {
        if ((before / 90).toInt() != (after / 90).toInt()) sound.wheelTick()
        if (abs(after) >= Car.WHEEL_MAX - 0.5 && abs(before) < Car.WHEEL_MAX - 0.5) sound.lock()
    }

    private fun eyeTrack(input: FrameInput) {
        if (input.lookByEyes) { eyeSeenFor += input.dt; eyeMissFor = 0.0 } else { eyeMissFor += input.dt; eyeSeenFor = 0.0 }
        val off = eyeMissFor > 5.0 || !eyeGranted() && eyeMissFor > 1.0
        val on = eyeSeenFor > 0.5
        if (off && !s.eyeOff) set { it.copy(eyeOff = true) }
        if (on && s.eyeOff) set { it.copy(eyeOff = false) }
    }

    private fun drainEvents() {
        val hints = hintsOn() || s.screen == Screen.DEMO
        for (e in run.events) {
            when (e) {
                is RunEvent.Cue -> {
                    cue = e.lp to e.seg
                    YdLog.i("CUE ${e.lp} seg=${e.seg}")
                    if (hints) {
                        sound.cue()
                        glowSet = mirrorFor(e.lp)
                        glowUntil = clock + 0.5
                        set { it.copy(glow = glowSet) }
                    }
                    if (s.screen == Screen.DEMO) set { it.copy(demoSub = Copy.cues[e.lp]?.let { c -> Tr("「${c.second.zh}，${c.first.zh}」", "\"${c.second.en}. ${c.first.en}.\"") }) }
                }
                is RunEvent.AutoStop -> YdLog.i("CUE autostop ${e.lp}")
                is RunEvent.Touched -> {
                    sound.line()
                    flashUntil = clock + 1.5
                    set { it.copy(flashLine = e.touch.line) }
                    YdLog.i("JUDGE touched ${e.touch} outcome=${e.outcome}")
                }
                is RunEvent.MidStop -> { if (run.rules == Rules.EXAM) sound.stopBeep(); YdLog.i("JUDGE midstop ${e.count}") }
                is RunEvent.SegDone -> { sound.cue(); YdLog.i("JUDGE segdone ${e.seg}") }
                is RunEvent.NextSeg -> { cue = null; YdLog.i("JUDGE next ${e.seg}") }
                RunEvent.GearRefused -> sound.refused()
                RunEvent.GearChanged -> sound.gear()
                is RunEvent.Finished -> { if (e.outcome == Outcome.PASS) sound.pass() else sound.fail(); YdLog.i("JUDGE finished ${e.outcome} score=${run.score} t=${"%.1f".format(run.elapsed)}") }
            }
        }
        run.events.clear()
    }

    private fun mirrorFor(lp: String): Set<Char> =
        when (io.embodit.yarddrive.domain.LookPoints.byId(lp)?.look) {
            Look.MIRROR_L -> setOf('L')
            Look.MIRROR_R -> setOf('R')
            Look.MIRROR_I -> setOf('I')
            else -> emptySet()
        }

    private fun predict(dt: Double) {
        predictClock += dt
        if (predictClock < 0.2) return
        predictClock = 0.0
        val seg = run.seg
        if (run.phase == RunPhase.FINISHED || run.phase == RunPhase.LINE || seg.isLane) return
        if (predictJob?.isActive == true) return
        val copy = run.sim.copy()
        val extra = run.cfg.extra
        val rate = run.userRate
        val serial = runSerial
        val target = run
        predictJob = scope.launch {
            val pr = withContext(Dispatchers.Default) { runCatching { Predictor.predict(copy, seg, extra, rate) }.getOrNull() }
            if (pr != null && serial == runSerial && target === run && run.seg == seg) run.prediction = pr
        }
    }

    private fun onFinished() {
        if (finishedAtMs == 0L) {
            finishedAtMs = android.os.SystemClock.elapsedRealtime()
            publish()
            return
        }
        // S11 -> S12 after 1 s (pause inside that second stays in S11)
        if (holdFinished || s.paused || android.os.SystemClock.elapsedRealtime() - finishedAtMs < 1000) return
        if (s.screen == Screen.DEMO) {
            startLessonRun()
            return
        }
        showResult()
    }

    private fun showResult() {
        val r = run
        lastRun = r
        val cfg = r.cfg
        val passed = r.passed && (!cfg.lane || r.touches == 0 && r.sawLock && r.sawCentre)
        var lessonPassedNow = false
        var lessonDone = false
        val trials = if (cfg.mode == Mode.LESSON) Copy.lessonList8[(cfg.lesson - 1).coerceIn(0, 7)].trials else 1
        if (cfg.mode == Mode.LESSON) {
            if (passed) lessonPasses++
            lessonDone = lessonTrial >= trials || lessonPasses >= trials
            if (lessonPasses >= trials) {
                lessonPassedNow = true
                store?.let { st -> st.lessonsPassed = st.lessonsPassed + cfg.lesson; set { it.copy(passedLessons = st.lessonsPassed) } }
            }
        }
        var groupLine: Tr? = null
        var groupDone = false
        if (cfg.mode == Mode.FREE) {
            freeScores += passed to r.score
            val f = free
            if (f != null && f.run >= Free.GROUP) {
                groupDone = true
                val n = freeScores.count { it.first }
                val avg = freeScores.map { it.second }.average().toInt()
                groupLine = Tr(Copy.groupLine.zh.format(n, avg), Copy.groupLine.en.format(n, avg))
            }
        }
        if (!r.unscored) {
            val rec = RunRecord(System.currentTimeMillis(), cfg.mode, cfg.lesson, cfg.seed, cfg.difficulty, r.outcome, passed, r.score, r.stops, r.elapsed, r.hits.map { it.lp to it.lateCm }, r.cornerGap[SegId.I], r.cornerGap[SegId.III], r.nose[SegId.I], r.looks.copyOf())
            store?.let { st -> st.records = st.records + rec; st.saveTrail(r.trail) }
        }
        val title = when (cfg.mode) {
            Mode.MOCK -> Copy.mock
            Mode.FREE -> free?.let { f -> Tr("${Copy.free.zh} · 第 ${f.run} / ${f.of} 次", "${Copy.free.en} · run ${f.run} / ${f.of}") } ?: Copy.free
            Mode.LESSON -> Copy.lessonList8[(cfg.lesson - 1).coerceIn(0, 7)].let { l -> Tr("课程 ${l.n} · ${l.name.zh}", "Lesson ${l.n} · ${l.name.en}") }
        }
        val looks = Look.entries.associateWith { r.looks[it.ordinal] }
        val res = ResultView(
            title, if (cfg.lane && r.touches > 0) Outcome.OUT else r.outcome, passed, r.score, r.stops, r.elapsed, cfg.segments, r.segOk.toList(), r.segTime.toList(), r.hits.toList(),
            r.cornerGap[SegId.I], r.cornerGap[SegId.III], r.nose[SegId.I] ?: r.nose[SegId.III], adviceFor(r), r.reason?.let { Tr(it.zh, it.en) }, r.trail.map { P2(it.x, it.z) }, r.touch?.at,
            looks, cfg.lesson, lessonTrial, trials, lessonPassedNow, lessonDone, r.unscored, cfg.mode, groupLine, groupDone,
            lane = if (cfg.lane) Triple(r.touches, r.sawLock, r.sawCentre) else null,
            mockCall = cfg.mode == Mode.MOCK && r.outcome == Outcome.OUT,
            failSeg = if (r.outcome != Outcome.PASS) r.segIndex else null,
            exam = cfg.rules == Rules.EXAM,
        )
        set { it.copy(screen = Screen.RESULT, result = res, resultPage = 0, recordCount = store?.records?.size ?: it.recordCount) }
    }

    /** One sentence of advice (copy.advice and the same voice for the other common errors). */
    private fun adviceFor(r: Run): Tr {
        when (r.outcome) {
            Outcome.NOTIN -> return Tr("前沿要过库前线才算进库。再往里倒一点再停。", "The nose has to clear the bay line. Back in a bit more before you stop.")
            Outcome.ORDER -> return Reason.lockReason(r, r.seg, outcome = Outcome.ORDER)?.let { Tr(it.zh + "。", it.en + ".") } ?: Tr("顺序错了，按段名走。", "Wrong order. Follow the segment name.")
            Outcome.TIMEOUT -> return Tr("过了三分半。慢一点没关系，别停太久。", "Past three and a half minutes. Slow is fine, just don't sit too long.")
            Outcome.STOP -> return Tr("严格模式：中途停车就不合格。", "Strict mode: any mid-way stop fails.")
            else -> Unit
        }
        val lock = r.hits.filter { it.kind == Hit.Kind.LOCK }.maxByOrNull { abs(it.lateCm) }
        if (lock != null && abs(lock.lateCm) > 15) {
            val right = lock.seg.lockSign > 0
            return if (lock.lateCm < 0) {
                if (right && lock.seg.bayIn) Copy.advice else if (lock.seg.bayIn) Tr("左打死早了：控制线到右镜壳下面再打，别提前。", "Your left full lock came early: wait till the line is under the right mirror.")
                else Tr("出库打早了：再往前开一点，后角就不会扫到库角。", "You turned out early. Roll a bit further so the rear corner clears the bay.")
            } else {
                if (lock.seg.bayIn) Tr("打死晚了：控制线一到镜壳下面就打，别犹豫。", "Full lock came late: go the moment the line is under the mirror.")
                else Tr("出库打晚了：别等口诀，看到点就打。", "You turned out late. Don't wait for the chant, go at the point.")
            }
        }
        val nose = r.hits.firstOrNull { it.kind == Hit.Kind.STOP && it.seg.bayIn }
        if (nose != null && abs(nose.lateCm) > 15) return if (nose.lateCm > 0) Tr("停深了：前沿过线 35 cm 就停，后面还有库底线。", "You parked deep. Stop at 35 cm past the line; the back line is right there.") else Tr("停浅了：再倒一点，前沿要过线。", "A bit shallow. Back up more; the nose has to clear the line.")
        if (r.stops > 0) return Tr("中途停了 ${r.stops} 次。慢一点没关系，别停。", "You stopped ${r.stops} times. Slow is fine, just keep rolling.")
        return Tr("这把挺稳。再来一次，把每个点位都压在 15 cm 以内。", "That was steady. Go again and keep every point within 15 cm.")
    }

    // =========================================================================================== scorecard

    private fun openScorecard() {
        val recs = store?.records ?: emptyList()
        set { it.copy(screen = Screen.SCORECARD, score = scoreView(recs), confirm = Confirm.NONE) }
    }

    private fun scoreView(recs: List<RunRecord>) = ScoreView(Stats.rows(recs), Stats.mostCommon(recs), Stats.medianGap(recs, true), Stats.medianGap(recs, false), recs.size)

    // =========================================================================================== telemetry

    /** Publishes the run as P2 / HUB / MRD draw it. */
    fun publish() {
        val r = run
        val seg = r.seg
        val p = r.pose
        val pr = r.prediction
        val hints = hintsOn() || s.screen == Screen.DEMO
        var big: Tr? = null
        var sub: Tr? = null
        var kind = ""
        var task: Tr? = null
        var count: Double? = null
        val c = cue
        val lockDone = r.hits.any { it.seg == seg && it.kind == Hit.Kind.LOCK }
        if (!seg.isLane && (r.phase == RunPhase.DRIVING || r.phase == RunPhase.STOPPED || r.phase == RunPhase.READY)) {
            task = when {
                !lockDone -> Copy.taskBeforeLock[seg]
                r.hits.none { it.seg == seg && it.kind == Hit.Kind.STRAIGHTEN } -> Copy.taskLocked[seg]
                else -> Copy.taskStraight[seg]
            }
            if (!lockDone) {
                val d = Truth.toLock(seg, p, r.lockX)
                if (d in 0.0..0.5) count = d
            } else if (r.hits.any { it.seg == seg && it.kind == Hit.Kind.STRAIGHTEN }) {
                val d = Truth.toStop(seg, p)
                if (d in 0.0..0.5) count = d
            }
            if (c != null && c.second == seg) {
                Copy.cues[c.first]?.let { (b, sm) -> big = b; sub = sm; kind = c.first }
            }
            // the corner advice while at full lock (design LP3 / LP10)
            if (seg.bayIn && lockDone && pr != null && pr.atLock && r.hits.none { it.seg == seg && it.kind == Hit.Kind.STRAIGHTEN }) {
                val base = seg.cornerLp
                val key = when (pr.advice) { Predictor.Advice.KEEP -> base; Predictor.Advice.EASE -> base + "near"; Predictor.Advice.LOCK_AGAIN -> base + "again"; Predictor.Advice.NO_WAY -> base + "late" }
                if (c == null || c.first == seg.lockLp || c.first == base || c.first.startsWith(base)) Copy.cues[key]?.let { (b, sm) -> big = b; sub = sm; kind = key }
            }
        }
        // design S14 / lesson 7 / hints off: column 2 is the segment name only, the task sentence is a hint too (R8)
        if (!hints) { big = null; sub = null; count = null; kind = ""; task = null }
        val touch = r.touch
        val free = if (r.cfg.mode == Mode.FREE) free else null
        set {
            it.copy(
                run = RunView(
                    mode = r.cfg.mode, lesson = r.cfg.lesson, segs = r.cfg.segments, segIndex = r.segIndex, segDone = r.segOk.toList(), phase = r.phase,
                    gear = r.sim.gear, wrongGear = r.wrongGear, speed = r.sim.v, stepMode = r.stepMode, stepByStep = r.cfg.stepByStep, lenient = r.cfg.lenient,
                    exam = r.cfg.rules == Rules.EXAM, timed = r.cfg.timed, elapsed = r.elapsed, stoppedFor = r.stoppedFor, stops = r.stops, wheel = r.sim.wheel, pose = p,
                    predicted = pr?.hold?.takeIf { b -> b.reachable && pr.seg == seg }?.end, idealPath = idealPath, trailRecent = emptyList(),
                    cueBig = big, cueSub = sub, cueKind = kind, task = task, countdown = count, autoStopped = r.cfg.stepByStep && r.phase == RunPhase.STOPPED && c != null && c.second == seg,
                    gapL = MrdReadout.cornerGap(SegId.III, r.cornerGap[SegId.III], seg, pr?.seg, pr?.hold?.gapCorner),
                    gapR = MrdReadout.cornerGap(SegId.I, r.cornerGap[SegId.I], seg, pr?.seg, pr?.hold?.gapCorner),
                    touchPart = touch?.part?.let { t -> Tr(t.zh, t.en) }, touchLine = touch?.line?.let { t -> Tr(t.zh, t.en) }, reason = r.reason?.let { t -> Tr(t.zh, t.en) }, lineOutcome = r.lineOutcome,
                    free = free, goOn = r.sim.go, lane = r.cfg.lane, laneSawLock = r.sawLock, laneSawCentre = r.sawCentre, advice = pr?.advice, nose = r.nose[seg],
                    stop = r.stopView, started = r.started, seatHint = r.cfg.seat?.let { o -> if (o > it.settings.seat) Copy.seatHigher else Copy.seatLower },
                ),
            )
        }
    }

    // =========================================================================================== debug seams (src/debug drives these)

    /** Debug: the demo autopilot drives the current run (script=ideal|early:x|late:x). */
    fun debugScript(ap: Autopilot) {
        autopilot = ap
        debugAutopilot = true
    }

    var debugAutopilot = false

    /** Debug: the line flash stays on, for a screenshot (the emulator shows about 1 frame per second). */
    var debugFlashHold = false

    /** Debug: keep S11 on screen (no automatic switch to the result card). */
    var holdFinished = false

    /** Debug: run the current run (with the demo / script autopilot if any) for [seconds] right now, in 20 ms steps. */
    fun debugAdvance(seconds: Double, until: (Run) -> Boolean = { false }) {
        val ap = autopilot
        var t = 0.0
        while (t < seconds && run.phase != RunPhase.FINISHED && !until(run)) {
            ap?.drive(run, 0.02)
            run.tick(0.02)
            if (run.phase == RunPhase.LINE) {
                drainEvents()
                break
            }
            t += 0.02
        }
        drainEvents()
        publish()
    }

    fun debugAutopilotSet(ap: Autopilot?) {
        autopilot = ap
    }

    /** Kept for the scene: the debug script now drives inside [tick]. */
    fun debugDrive(@Suppress("UNUSED_PARAMETER") dt: Double) = Unit

    fun debugSet(f: (YdUiState) -> YdUiState) = set(f)

    private fun startPlacing(seated: YdEvent.Seated?) {
        val cb = onPlace ?: return
        if (s.reseating) return
        if (headReady?.invoke() == false) {
            seatNote(Copy.seatNoHead)
            return
        }
        placingSeated = seated
        set { it.copy(reseating = true, reseatDone = false, seatNote = null, lookLeftS = SeatLook.START_S) }
        cb()
    }

    /** The flow counts down (scene, a few times a second at most: only the hint line reads it). */
    fun setLookLeft(leftS: Double) {
        val v = Math.round(leftS * 10) / 10.0
        if (s.lookLeftS != v) set { it.copy(lookLeftS = v) }
    }

    /** The car has been placed; P1 comes back at its new place: "Placed" for about 1.5 s (profile D29). */
    fun placingDone() {
        val seated = placingSeated
        placingSeated = null
        seatDoneUntil = clock + 1.5
        set { it.copy(reseating = false, reseatDone = true, seatNote = null) }
        sound.cue() // P1 went away and came back: say it worked (S1)
        if (seated != null) {
            set { it.copy(screen = if (seated.reseat && it.from != Screen.SIT) it.from else Screen.HOME, seatedOnce = true) }
            // after the car is placed: the system dialog must not cut into the wait
            askEyeOnce()
        }
    }

    /** Left the page, started driving, went to the background: P1 comes back where it was. */
    fun placingCancelled(lost: Boolean = false) {
        placingSeated = null
        if (s.reseating) set { it.copy(reseating = false) }
        if (lost) seatNote(Copy.seatLost)
    }

    private fun seatNote(note: Tr) {
        seatNoteUntil = clock + 3.0
        set { it.copy(seatNote = note) }
    }

    private var seatNoteUntil = 0.0

    private var seatDoneUntil = 0.0

    /** S01: which side the panel went out of view on: -1 left, 1 right, 0 in view (scene -> hint panel). */
    fun setPanelAway(side: Int) {
        if (s.panelAway != side) set { it.copy(panelAway = side) }
    }

    /** Debug: a real run judged to [outcome] (q1-8: the same judge as a user's run, nothing written over the card). */
    fun debugResult(outcome: Outcome, lessonStops: Int = 0) {
        val cfg = if (lessonStops > 0) lessonCfg(6) else mockCfg().copy(mode = if (outcome == Outcome.PASS) Mode.LESSON else Mode.MOCK, lesson = 6, strict = outcome == Outcome.STOP)
        val r = Run(cfg)
        fun run(seconds: Double, each: () -> Unit = {}) {
            var t = 0.0
            while (t < seconds && r.phase != RunPhase.FINISHED) {
                each()
                r.tick(0.02)
                t += 0.02
            }
        }
        when (outcome) {
            Outcome.ORDER -> { r.setGo(true); run(30.0) } // still in D: forward more than 0.5 m outside the bay
            Outcome.TIMEOUT -> { r.shift(Gear.R); r.setGo(true); run(1.0); r.setGo(false); run(230.0) }
            Outcome.STOP -> { r.shift(Gear.R); r.setGo(true); run(1.0); r.setGo(false); run(5.0) }
            Outcome.NOTIN -> {
                // the ideal script, but stop as soon as the car counts as "at the end" with the nose still out
                val ap = Autopilot()
                var t = 0.0
                while (t < 60 && !(Truth.atEnd(SegId.I, r.pose, cfg.start) && Truth.nose(r.pose) < -0.25)) {
                    ap.drive(r, 0.02)
                    r.tick(0.02)
                    t += 0.02
                }
                r.setGo(false)
                run(8.0)
            }
            else -> {
                val ap = Autopilot(lockEarly = if (outcome == Outcome.OUT) 0.4 else 0.0)
                var made = 0
                var hold = 0.0
                run(120.0) {
                    // lesson rules with mid-way stops: the same stops a person would make while reversing
                    if (made < lessonStops && hold <= 0.0 && r.sim.moving && r.pose.x > 4.0 && r.segIndex == 0) { made++; hold = 2.6 }
                    if (hold > 0) { r.setGo(false); hold -= 0.02 } else ap.drive(r, 0.02)
                }
            }
        }
        run = r
        lesson = 6
        lessonTrial = 1
        showResult()
    }

    fun debugRun(): Run = run

    fun debugReplaceRun(r: Run) {
        run = r
        lastCfg = r.cfg
        set { it.copy(screen = Screen.RUN, paused = false) }
        publish()
    }

    fun debugLesson(n: Int) {
        lesson = n
        lessonTrial = 0
        lessonPasses = 0
    }

    fun debugTour(step: Int, lp: Int, line: Int = 0) {
        tourStep = step
        if (step == 3) { tourLp = lp; tourSeen += lp; place(LookPoints.byN(lp)) }
        if (step == 2) { tourLine = line.coerceIn(0, Copy.tourLines.size - 1); showTourLine() }
        set { it.copy(screen = if (step == 0) Screen.TOUR_INTRO else Screen.TOUR) }
        publishTour()
    }

    fun debugFree(seed: Int, d: Int) {
        free = FreeInfo(3, Free.GROUP, d, seed)
        freeScores.clear()
        startFree()
    }

    fun debugSeedScores() {
        val st = store ?: return
        val rnd = java.util.Random(5)
        val recs = (0 until 23).map { i ->
            val m = if (i < 14) Mode.LESSON else if (i < 20) Mode.MOCK else Mode.FREE
            val p = rnd.nextInt(10) < 7
            RunRecord(System.currentTimeMillis() - i * 60000L, m, 6, i, 2, if (p) Outcome.PASS else Outcome.OUT, p, 100 - 5 * rnd.nextInt(3), rnd.nextInt(3), 150 + rnd.nextInt(60).toDouble(),
                listOf("LP2" to -12 + rnd.nextInt(9), "LP4" to rnd.nextInt(20) - 5, "LP6" to 20 + rnd.nextInt(15)), 0.12 + rnd.nextInt(10) / 100.0, 0.15 + rnd.nextInt(10) / 100.0, 0.30 + rnd.nextInt(10) / 100.0, IntArray(8) { rnd.nextInt(50) })
        }
        st.records = recs
        set { it.copy(recordCount = recs.size) }
    }

    const val EYE_REQUEST = 41
    private const val EYE_PERMISSION = "com.picovr.permission.EYE_TRACKING"
}
