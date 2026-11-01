package io.embodit.yarddrive.ui

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
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
import io.embodit.yarddrive.domain.Outcome
import io.embodit.yarddrive.domain.P2
import io.embodit.yarddrive.domain.Pose
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
    data class SetSettings(val s: Settings) : YdEvent()
    data class SettingsPage(val i: Int) : YdEvent()
    data object About : YdEvent()
    data object AskExit : YdEvent()
    data object AskClear : YdEvent()
    data object CancelConfirm : YdEvent()
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
    val a: Boolean = false,
    val bEdge: Boolean = false,
    val xEdge: Boolean = false,
    val yEdge: Boolean = false,
    val stickX: Float = 0f,
    /** Hand on the wheel: azimuth in the wheel plane (degrees, counter-clockwise as you see it), or null. */
    val grabAzimuth: Double? = null,
    val look: Look = Look.AHEAD,
    val lookByEyes: Boolean = false,
    val lost: Boolean = false,
    val pinchGo: Boolean = false,
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
    private var grabbing = false
    private var grabLast = 0.0
    private var grabStartT = 0.0
    private var grabStartWheel = 0.0
    private var clock = 0.0
    private var flashUntil = 0.0
    private var glowUntil = 0.0
    private var glowSet: Set<Char> = emptySet()
    private var lostFor = 0.0
    private var replay: List<io.embodit.yarddrive.domain.Sample> = emptyList()
    private var idealPath: List<P2> = emptyList()
    private val lockCache = HashMap<String, Double>()
    private var eyeSeenFor = 0.0
    private var eyeMissFor = 0.0
    private var tourStep = 0
    private var tourLit = BooleanArray(4)
    private var tourLine = 0
    private var tourLp = 1
    private var tourSeen = HashSet<Int>()
    private var runSerial = 0

    /** Notified when the car must be placed again (seat / re-seat) and when the app should quit. */
    var onReseat: (() -> Unit)? = null
    var onQuit: (() -> Unit)? = null

    fun start(context: Context) {
        if (started) return
        started = true
        val st = Store(context.applicationContext)
        store = st
        val settings = st.settings.let { if (!st.hasLang()) it.copy(zh = java.util.Locale.getDefault().language == "zh") else it }
        sound.muted = settings.muted
        sound.level = settings.volume
        set { it.copy(settings = settings, passedLessons = st.lessonsPassed, demosSeen = st.demosSeen, recordCount = st.records.size, version = BuildConfig.VERSION_NAME) }
        parkAtStart()
        YdLog.i("CONTROLLER start settings=$settings")
    }

    private fun settings() = s.settings

    // =========================================================================================== events

    fun onEvent(e: YdEvent) {
        YdLog.i("EVENT $e")
        when (e) {
            YdEvent.SceneReady -> if (s.screen == Screen.LOADING) set { it.copy(screen = Screen.SIT) }
            is YdEvent.Seated -> {
                onReseat?.invoke()
                askEyeOnce()
                set { it.copy(screen = if (e.reseat && it.from != Screen.SIT) it.from else Screen.HOME, seatedOnce = true) }
            }
            is YdEvent.Seat -> saveSettings(settings().copy(seat = e.i.coerceIn(0, 2)))
            YdEvent.SeatCycle -> saveSettings(settings().copy(seat = (settings().seat + 1) % 3))
            YdEvent.OpenLessons -> set { it.copy(screen = Screen.LESSONS) }
            YdEvent.OpenLibrary -> { parkAtStart(); set { it.copy(screen = Screen.LIBRARY) } }
            YdEvent.OpenMock -> startRun(mockCfg())
            YdEvent.OpenFree -> set { it.copy(screen = Screen.FREE_PICK) }
            is YdEvent.FreeLevel -> { free = FreeInfo(1, Free.GROUP, e.d, e.seed ?: (1000 + (Math.random() * 9000).toInt())); freeScores.clear(); startFree() }
            YdEvent.OpenScorecard -> openScorecard()
            YdEvent.OpenSettings -> set { it.copy(screen = Screen.SETTINGS, from = if (it.screen == Screen.SETTINGS || it.screen == Screen.ABOUT) it.from else it.screen, confirm = Confirm.NONE) }
            YdEvent.Back -> back()
            YdEvent.Home -> goHome()
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
            YdEvent.GoToggle -> if (driving()) run.setGo(!run.sim.go)
            YdEvent.StepOnce -> if (driving()) run.step()
            YdEvent.StepMode -> toggleStepMode()
            YdEvent.Easy -> toggleEasy()
            YdEvent.Pause -> if (s.screen in setOf(Screen.RUN, Screen.DEMO, Screen.TOUR)) { run.setGo(false); set { it.copy(paused = true) } }
            YdEvent.Resume -> set { it.copy(paused = false) }
            YdEvent.Restart -> { set { it.copy(paused = false) }; restart() }
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
            is YdEvent.SetSettings -> saveSettings(e.s)
            is YdEvent.SettingsPage -> set { it.copy(settingsPage = e.i.coerceIn(0, 1), confirm = Confirm.NONE) }
            YdEvent.About -> set { it.copy(screen = Screen.ABOUT) }
            YdEvent.AskExit -> set { it.copy(confirm = Confirm.EXIT) }
            YdEvent.AskClear -> set { it.copy(confirm = Confirm.CLEAR) }
            YdEvent.CancelConfirm -> set { it.copy(confirm = Confirm.NONE) }
            YdEvent.ConfirmClear -> { store?.clearScores(); set { it.copy(confirm = Confirm.NONE, recordCount = 0, passedLessons = emptySet(), score = scoreView(emptyList())) } }
            YdEvent.Exit -> quit()
            YdEvent.Reseat -> set { it.copy(screen = Screen.SIT, from = if (it.from == Screen.SETTINGS) Screen.HOME else it.from) }
            YdEvent.Mute -> saveSettings(settings().copy(muted = !settings().muted))
            YdEvent.TurnOnEye -> requestEye()
            YdEvent.TourStart -> { tourStep = 1; tourLit = BooleanArray(4); tourSeen.clear(); set { it.copy(screen = Screen.TOUR) }; publishTour() }
            YdEvent.TourNext -> tourNext()
            YdEvent.TourSkipRear -> { tourLit[3] = true; publishTour() }
            YdEvent.SkipLesson -> { set { it.copy(screen = Screen.LESSONS) } }
            is YdEvent.Background -> if (e.on && s.screen in setOf(Screen.RUN, Screen.DEMO, Screen.TOUR)) { run.setGo(false); set { it.copy(paused = true) } }
            is YdEvent.Blocked -> set { it.copy(screen = Screen.UNAVAILABLE) }
        }
    }

    private fun driving() = s.screen == Screen.RUN && !s.paused

    private fun shift(g: Gear) {
        val ok = run.shift(g)
        if (!ok) sound.refused()
        publish()
    }

    private fun saveSettings(n: Settings) {
        val o = settings()
        store?.settings = n
        sound.muted = n.muted
        sound.level = n.volume
        set { it.copy(settings = n) }
        if (n.speed != o.speed) run.sim.vSet = n.speed
        if (n.hands != o.hands || n.zh != o.zh) publish()
    }

    private fun back() {
        when (s.screen) {
            Screen.SETTINGS -> set { it.copy(screen = it.from.takeIf { f -> f != Screen.SETTINGS && f != Screen.ABOUT && f != Screen.SIT } ?: Screen.HOME, confirm = Confirm.NONE) }
            Screen.ABOUT -> set { it.copy(screen = Screen.SETTINGS) }
            Screen.FREE_PICK, Screen.LESSONS, Screen.LIBRARY, Screen.SCORECARD -> goHome()
            Screen.POINT -> set { it.copy(screen = Screen.LIBRARY) }
            else -> goHome()
        }
    }

    fun goHome() {
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
        runCatching {
            if (a.checkSelfPermission(EYE_PERMISSION) != PackageManager.PERMISSION_GRANTED) a.requestPermissions(arrayOf(EYE_PERMISSION), 41)
        }.onFailure { YdLog.w("EYE permission request failed: $it") }
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
        return lockCache.getOrPut(key) { Predictor.bestLock(st, vSet = speed) }
    }

    private fun lessonCfg(n: Int): RunConfig {
        val st = settings()
        val gap = st.gap
        val base = RunConfig(mode = Mode.LESSON, lesson = n, gap = gap, speed = st.speed, start = Yard.rightStart(gap), lockX = lockX(gap, st.speed))
        return when (n) {
            2 -> base.copy(rules = Rules.LESSON, segments = listOf(SegId.LANE1, SegId.LANE2), stepMode = true)
            3 -> base.copy(rules = Rules.LESSON, lenient = true, stepByStep = true, stepMode = true, segments = listOf(SegId.I))
            4 -> base.copy(rules = Rules.LESSON, lenient = true, stepByStep = true, segments = listOf(SegId.II), start = Yard.inBay, startGear = Gear.R)
            5 -> base.copy(rules = Rules.LESSON, lenient = true, stepByStep = true, segments = listOf(SegId.III), start = Yard.leftStart(gap))
            6 -> base.copy(rules = Rules.LESSON)
            7 -> base.copy(rules = Rules.EXAM, strict = st.strict)
            else -> base.copy(rules = Rules.EXAM, strict = st.strict, timed = true)
        }
    }

    /** Hints / top view / poles per lesson (design content.lessons); the settings switch them off everywhere. */
    fun hintsOn(): Boolean = settings().hints && run.cfg.mode == Mode.LESSON && run.cfg.lesson in 1..6

    fun topViewOn(): Boolean = settings().topView && run.cfg.mode == Mode.LESSON && run.cfg.lesson in 1..6 || s.screen == Screen.REPLAY || s.screen == Screen.DEMO || s.screen == Screen.POINT

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

    private fun startDemo(n: Int) {
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
        startRun(lessonCfg(lesson))
    }

    private fun startFree() {
        val f = free ?: return
        val st = Free.start(f.seed + f.run - 1, f.difficulty, settings().gap)
        if (st.seat != null && st.seat != settings().seat) saveSettings(settings().copy(seat = st.seat))
        val easy = run.cfg.mode == Mode.FREE && run.cfg.rules == Rules.LESSON
        val speed = settings().speed
        scope.launch {
            val x0 = withContext(Dispatchers.Default) { lockX(settings().gap, speed, st.pose) }
            startRun(RunConfig(mode = Mode.FREE, rules = if (easy) Rules.LESSON else Rules.EXAM, lenient = easy, strict = settings().strict, timed = !easy, gap = settings().gap, start = st.pose, speed = speed, lockX = x0, seed = st.seed, difficulty = f.difficulty))
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
        val r = Run(RunConfig(mode = Mode.LESSON, segments = listOf(lp.seg), start = lp.pose, startGear = lp.gear, gap = settings().gap))
        r.place(lp.pose, lp.wheel)
        r.frozen = true
        run = r
        idealPath = emptyList()
        computeIdeal(RunConfig(mode = Mode.LESSON, gap = settings().gap))
        fade()
        publish()
    }

    /** 0.5 s fade out / in for jumps (design T15); the scene reads it. */
    var fadeEpoch = 0
        private set

    private fun fade() {
        fadeEpoch++
    }

    private fun tourNext() {
        when (tourStep) {
            1 -> { tourStep = 2; tourLine = 0 }
            2 -> if (tourLine < Copy.tourLines.size - 1) tourLine++ else { tourStep = 3; tourLp = 1; tourSeen.clear(); pickLp(1) }
            3 -> if (tourLp < 5) pickLp(tourLp + 1) else finishTour()
        }
        publishTour()
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
                    Tr("口诀是别人的，眼睛是你的。下一课去摸方向盘。", "The chant is theirs, the eyes are yours. Next lesson: the wheel."), null, emptyList(), null, emptyMap(), 1, 1, 1, passed, passed, false, Mode.LESSON, null, false, tour = tourSeen.size to lit,
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
        if (clock > flashUntil && st.flashLine != null) set { it.copy(flashLine = null) }
        if (clock > glowUntil && st.glow != glowLp()) set { it.copy(glow = glowLp()) }
        sound.setLoops(idleOn = st.seatedOnce, tyresOn = run.sim.moving)
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

    private var lastA = false

    private fun applyInputs(input: FrameInput) {
        val hands = settings().hands
        if (!hands) {
            if (input.a != lastA) run.setGo(input.a)
            lastA = input.a
        } else if (settings().pinchHold) run.setGo(input.pinchGo)
        if (input.bEdge) run.step()
        if (input.xEdge) run.steerButton(0.0)
        if (input.yEdge) shift(if (run.sim.gear == Gear.R) Gear.D else Gear.R)
        // joystick: 1.5 turns / s at full deflection, dead zone 0.15 (design steeringModel.joystick)
        run.sim.stickRate = if (abs(input.stickX) > Car.STICK_DEAD) input.stickX * Car.STICK_RATE else 0.0
        // grabbing the wheel: the wheel follows the change of the hand's azimuth around the hub (design A2)
        val az = input.grabAzimuth
        if (az != null) {
            if (!grabbing) {
                grabbing = true
                grabLast = az
                grabStartT = clock
                grabStartWheel = run.sim.wheel
                run.sim.grabbed = true
            } else {
                var d = az - grabLast
                while (d > 180) d -= 360
                while (d < -180) d += 360
                grabLast = az
                // counter-clockwise azimuth up = wheel to the left
                run.handWheel(run.sim.wheel - d)
            }
        } else if (grabbing) {
            grabbing = false
            run.sim.grabbed = false
            val dur = clock - grabStartT
            val turned = abs(run.sim.wheel - grabStartWheel)
            if (dur > 0.2 && turned > 90) run.userRate = (turned / dur).coerceIn(150.0, Car.BUTTON_RATE)
        }
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
        )
        set { it.copy(screen = Screen.RESULT, result = res, resultPage = 0, recordCount = store?.records?.size ?: it.recordCount) }
    }

    /** One sentence of advice (copy.advice and the same voice for the other common errors). */
    private fun adviceFor(r: Run): Tr {
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
        if (!hints) { big = null; sub = null; count = null; kind = "" }
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
                    gapL = r.cornerGap[SegId.III] ?: pr?.hold?.gapCorner?.takeIf { seg == SegId.III && pr.seg == seg && it < 8 },
                    gapR = r.cornerGap[SegId.I]?.let { g -> if (seg == SegId.I && pr != null && pr.seg == seg) minOf(g, pr.hold.gapCorner) else g } ?: pr?.hold?.gapCorner?.takeIf { seg == SegId.I && pr.seg == seg && it < 8 },
                    touchPart = touch?.part?.let { t -> Tr(t.zh, t.en) }, touchLine = touch?.line?.let { t -> Tr(t.zh, t.en) }, reason = r.reason?.let { t -> Tr(t.zh, t.en) }, lineOutcome = r.lineOutcome,
                    free = free, goOn = r.sim.go, lane = r.cfg.lane, laneSawLock = r.sawLock, laneSawCentre = r.sawCentre, advice = pr?.advice,
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

    fun debugResult(outcome: Outcome) {
        val cfg = mockCfg().copy(mode = if (outcome == Outcome.PASS) Mode.LESSON else Mode.MOCK, lesson = 6)
        val r = Run(cfg)
        val ap = Autopilot(lockEarly = if (outcome == Outcome.OUT) 0.4 else 0.0)
        var t = 0.0
        while (t < 120 && r.phase != RunPhase.FINISHED) {
            ap.drive(r, 0.02)
            r.tick(0.02)
            t += 0.02
        }
        run = r
        lesson = 6
        lessonTrial = 1
        showResult()
        if (outcome != Outcome.PASS && outcome != Outcome.OUT) set { it.copy(result = it.result?.copy(outcome = outcome, passed = false, mockCall = false)) }
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

    fun debugTour(step: Int, lp: Int) {
        tourStep = step
        if (step == 3) { tourLp = lp; tourSeen += lp; place(LookPoints.byN(lp)) }
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

    private const val EYE_PERMISSION = "com.picovr.permission.EYE_TRACKING"
}
