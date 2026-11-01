package io.embodit.yarddrive.ui

import io.embodit.yarddrive.domain.Gear
import io.embodit.yarddrive.domain.Hit
import io.embodit.yarddrive.domain.JudgeLine
import io.embodit.yarddrive.domain.Look
import io.embodit.yarddrive.domain.Mode
import io.embodit.yarddrive.domain.Outcome
import io.embodit.yarddrive.domain.P2
import io.embodit.yarddrive.domain.Pose
import io.embodit.yarddrive.domain.Predictor
import io.embodit.yarddrive.domain.RunPhase
import io.embodit.yarddrive.domain.SegId
import io.embodit.yarddrive.domain.Settings
import io.embodit.yarddrive.domain.Stats
import io.embodit.yarddrive.domain.Tr

/** What P1 shows (design informationArchitecture); DEMO / RUN / REPLAY / POINT / TOUR put P2 up instead. */
enum class Screen { LOADING, SIT, HOME, LESSONS, LIBRARY, POINT, FREE_PICK, DEMO, RUN, RESULT, REPLAY, SCORECARD, SETTINGS, ABOUT, UNAVAILABLE, TOUR_INTRO, TOUR }

enum class Confirm { NONE, EXIT, CLEAR }

/** The live run as P2 / HUB / MRD draw it (10 Hz). */
data class RunView(
    val mode: Mode = Mode.LESSON,
    val lesson: Int = 0,
    val segs: List<SegId> = emptyList(),
    val segIndex: Int = 0,
    val segDone: List<Boolean> = emptyList(),
    val phase: RunPhase = RunPhase.READY,
    val gear: Gear = Gear.D,
    val wrongGear: Boolean = false,
    val speed: Double = 0.0,
    val stepMode: Boolean = false,
    val stepByStep: Boolean = false,
    val lenient: Boolean = false,
    val exam: Boolean = false,
    val timed: Boolean = false,
    val elapsed: Double = 0.0,
    val stoppedFor: Double = 0.0,
    val stops: Int = 0,
    val wheel: Double = 0.0,
    val pose: Pose = Pose(0.0, 0.0, 0.0),
    val predicted: Pose? = null,
    val idealPath: List<P2> = emptyList(),
    val trailRecent: List<P2> = emptyList(),
    val cueBig: Tr? = null,
    val cueSub: Tr? = null,
    val cueKind: String = "",
    val task: Tr? = null,
    val countdown: Double? = null,
    val autoStopped: Boolean = false,
    val gapL: Double? = null,
    val gapR: Double? = null,
    val touchPart: Tr? = null,
    val touchLine: Tr? = null,
    val reason: Tr? = null,
    val lineOutcome: Outcome = Outcome.OUT,
    val free: FreeInfo? = null,
    val goOn: Boolean = false,
    val lane: Boolean = false,
    val laneSawLock: Boolean = false,
    val laneSawCentre: Boolean = false,
    val advice: Predictor.Advice? = null,
)

data class FreeInfo(val run: Int, val of: Int, val difficulty: Int, val seed: Int)

/** The result card (S12). */
data class ResultView(
    val title: Tr,
    val outcome: Outcome,
    val passed: Boolean,
    val score: Int,
    val stops: Int,
    val timeS: Double,
    val segs: List<SegId>,
    val segOk: List<Boolean>,
    val segTime: List<Double>,
    val hits: List<Hit>,
    val gapR: Double?,
    val gapL: Double?,
    val nose: Double?,
    val advice: Tr,
    val reason: Tr?,
    val trail: List<P2>,
    val touchAt: P2?,
    val looks: Map<Look, Int>,
    val lesson: Int,
    val lessonTrial: Int,
    val lessonTrials: Int,
    val lessonPassedNow: Boolean,
    val lessonDone: Boolean,
    val unscored: Boolean,
    val mode: Mode,
    val groupLine: Tr?,
    val groupDone: Boolean,
    val tour: Pair<Int, Int>? = null,
    val lane: Triple<Int, Boolean, Boolean>? = null,
    val mockCall: Boolean = false,
)

data class TourView(val step: Int = 0, val lit: List<Boolean> = listOf(false, false, false, false), val line: Int = 0, val lp: Int = 1, val seen: Int = 0)

data class ScoreView(val rows: List<Stats.ModeRow>, val mostCommon: Pair<String, Int>?, val gapR: Double?, val gapL: Double?, val count: Int)

data class YdUiState(
    val screen: Screen = Screen.LOADING,
    val settings: Settings = Settings(),
    val paused: Boolean = false,
    val lost: Boolean = false,
    val eyeOff: Boolean = true,
    val settingsPage: Int = 0,
    val confirm: Confirm = Confirm.NONE,
    val resultPage: Int = 0,
    val from: Screen = Screen.HOME,
    val run: RunView = RunView(),
    val result: ResultView? = null,
    val lp: Int = 2,
    val gazeOnLp: Boolean = false,
    val passedLessons: Set<Int> = emptySet(),
    val demosSeen: Set<Int> = emptySet(),
    val recordCount: Int = 0,
    val score: ScoreView? = null,
    val demoTopOnly: Boolean = false,
    val demoSub: Tr? = null,
    val replayT: Double = 0.0,
    val replayLen: Double = 1.0,
    val replaySpeed: Double = 1.0,
    val replayNote: Tr? = null,
    val tour: TourView = TourView(),
    val flashLine: JudgeLine? = null,
    val glow: Set<Char> = emptySet(),
    val version: String = "0.1.0",
    val seatedOnce: Boolean = false,
    val fit: Boolean = false,
) {
    val zh: Boolean get() = settings.zh

    /** P1 visible? (design panels P1.visibleIn) */
    val p1: Boolean
        get() = paused || screen in setOf(Screen.LOADING, Screen.SIT, Screen.HOME, Screen.LESSONS, Screen.LIBRARY, Screen.FREE_PICK, Screen.RESULT, Screen.SCORECARD, Screen.SETTINGS, Screen.ABOUT, Screen.UNAVAILABLE, Screen.TOUR_INTRO)

    val p2: Boolean get() = !paused && screen in setOf(Screen.POINT, Screen.DEMO, Screen.RUN, Screen.REPLAY, Screen.TOUR)

    /** P2 pitch: R gear -15°, D gear +1° (design panels P2.placement). */
    val p2Up: Boolean get() = run.gear == Gear.D && screen != Screen.POINT

    /** The design state id (QA screenshots by name). */
    fun designState(): String =
        when {
            paused -> "S18"
            screen == Screen.LOADING -> "S00"
            screen == Screen.SIT -> "S01"
            screen == Screen.HOME -> "S02"
            screen == Screen.LESSONS -> "S03"
            screen == Screen.LIBRARY || screen == Screen.POINT -> "S04"
            screen == Screen.FREE_PICK -> "S15"
            screen == Screen.DEMO -> "S05"
            screen == Screen.RESULT -> "S12"
            screen == Screen.REPLAY -> "S13"
            screen == Screen.SCORECARD -> "S16"
            screen == Screen.SETTINGS || screen == Screen.ABOUT -> "S17"
            screen == Screen.UNAVAILABLE -> "S20"
            screen == Screen.TOUR_INTRO || screen == Screen.TOUR -> "S26"
            screen == Screen.RUN -> when (run.phase) {
                RunPhase.READY -> "S06"
                RunPhase.DRIVING -> "S07"
                RunPhase.STOPPED -> "S08"
                RunPhase.SEG_DONE -> "S09"
                RunPhase.LINE -> "S10"
                RunPhase.FINISHED -> "S11"
            }
            else -> "S02"
        }

    fun modifiers(): String {
        val m = ArrayList<String>()
        val inRun = screen == Screen.RUN || screen == Screen.DEMO
        if (inRun && run.mode == Mode.MOCK) m += "S14"
        if (inRun && run.mode == Mode.FREE) m += "S15"
        if (lost) m += "S19"
        if (!zh) m += "S21"
        if (eyeOff) m += "S22"
        if (inRun && run.stepMode) m += "S23"
        if (settings.hands) m += "S24"
        if (inRun && run.stepByStep) m += "S25"
        if (inRun && run.lane) m += "S27"
        return m.joinToString(",")
    }
}
