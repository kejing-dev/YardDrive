package io.embodit.yarddrive.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentSlider
import com.pico.spatial.ui.design.Slider
import io.embodit.yarddrive.R
import io.embodit.yarddrive.domain.Car
import io.embodit.yarddrive.domain.Copy
import io.embodit.yarddrive.domain.Hit
import io.embodit.yarddrive.domain.Look
import io.embodit.yarddrive.domain.LookPoints
import io.embodit.yarddrive.domain.Mode
import io.embodit.yarddrive.domain.Outcome
import io.embodit.yarddrive.domain.SegId
import io.embodit.yarddrive.domain.Yard
import io.embodit.yarddrive.ui.Confirm
import io.embodit.yarddrive.ui.ResultView
import io.embodit.yarddrive.ui.Screen
import io.embodit.yarddrive.ui.YdEvent
import io.embodit.yarddrive.ui.YdUiState
import io.embodit.yarddrive.ui.debug.probe
import io.embodit.yarddrive.ui.scene.PanelSizes
import io.embodit.yarddrive.ui.theme.YdColors
import io.embodit.yarddrive.ui.theme.YdType
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private fun mmss(s: Double): String = String.format(Locale.US, "%02d:%02d", (s / 60).toInt(), (s % 60).toInt())

private fun m2(v: Double?): String = if (v == null) "—" else String.format(Locale.US, "%.2f m", v)

/** P1 centre screen (design panels P1): one content at a time, P1 800 × 600 dp at scale 0.8. */
@Composable
internal fun P1Panel(state: YdUiState?, onEvent: (YdEvent) -> Unit) {
    Glass(PanelSizes.P1_W, PanelSizes.P1_H) {
        if (state == null) return@Glass
        if (state.paused) {
            Paused(state, onEvent)
            return@Glass
        }
        when (state.screen) {
            Screen.LOADING -> Loading(state)
            Screen.UNAVAILABLE -> Unavailable(state, onEvent)
            Screen.SIT -> Sit(state, onEvent)
            Screen.LESSONS -> Lessons(state, onEvent)
            Screen.LIBRARY -> Library(state, onEvent)
            Screen.FREE_PICK -> FreePick(state, onEvent)
            Screen.RESULT -> Result(state, onEvent)
            Screen.SCORECARD -> Scorecard(state, onEvent)
            Screen.SETTINGS -> SettingsPanel(state, onEvent)
            Screen.ABOUT -> About(state, onEvent)
            Screen.TOUR_INTRO -> TourIntro(state, onEvent)
            else -> Home(state, onEvent)
        }
    }
}

@Composable
private fun BackKey(state: YdUiState, onEvent: (YdEvent) -> Unit) = Key(state.t(Copy.back), "back", icon = R.drawable.ic_chevron_left) { onEvent(YdEvent.Back) }

@Composable
private fun MuteKey(state: YdUiState, onEvent: (YdEvent) -> Unit, name: String = "mute") =
    Key(if (state.settings.muted) state.t(Copy.unmute) else state.t(Copy.mute), name, icon = if (state.settings.muted) R.drawable.ic_volume_up else R.drawable.ic_volume_off) { onEvent(YdEvent.Mute) }

@Composable
private fun ColumnScope.Loading(state: YdUiState) {
    TopBar(state.t(Copy.appTitle), state.t(Copy.tagline))
    Spacer(Modifier.weight(1f))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.CenterHorizontally)) {
        CircularProgressIndicator(modifier = Modifier.size(36.dp), color = PicoTheme.colorScheme.labelPrimary, progressSize = CircularProgressDefaults.circleProgressSize(36.dp))
        Spacer(Modifier.width(16.dp))
        Line(state.t(Copy.loading), "loading", YdType.title)
    }
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.Unavailable(state: YdUiState, onEvent: (YdEvent) -> Unit) {
    TopBar(state.t(Copy.appTitle), null)
    Spacer(Modifier.height(12.dp))
    Line(state.t(Copy.unavailable), "unavailable", YdType.body, lines = 3)
    Spacer(Modifier.weight(1f))
    Key(state.t(Copy.quit), "quit", icon = R.drawable.ic_logout, kind = KeyKind.PRIMARY, height = 56.dp, modifier = Modifier.fillMaxWidth()) { onEvent(YdEvent.Exit) }
}

@Composable
private fun SeatSeg(state: YdUiState, onEvent: (YdEvent) -> Unit) =
    Seg(Copy.seats.mapIndexed { i, s -> state.t(s) to (state.settings.seat == i) }, "seat", 64.dp) { onEvent(YdEvent.Seat(it)) }

@Composable
private fun ColumnScope.Sit(state: YdUiState, onEvent: (YdEvent) -> Unit) {
    TopBar(state.t(Copy.appTitle), state.t(Copy.tagline))
    Spacer(Modifier.height(12.dp))
    Line(state.t(Copy.sitHint), "sitHint", YdType.body, lines = 2)
    Spacer(Modifier.height(16.dp))
    FormRow(state.t(Copy.seatHeight), state.t(Copy.seatHeightSub), "seatRow", Modifier.fillMaxWidth()) { SeatSeg(state, onEvent) }
    Spacer(Modifier.height(12.dp))
    Line(state.t(Copy.eyeHint), "eyeHint", YdType.small, lines = 2)
    Spacer(Modifier.weight(1f))
    Key(state.t(Copy.sit), "seated", icon = R.drawable.ic_check_circle, kind = KeyKind.PRIMARY, height = 56.dp, modifier = Modifier.fillMaxWidth()) { onEvent(YdEvent.Seated(reseat = state.seatedOnce)) }
}

@Composable
private fun ColumnScope.Home(state: YdUiState, onEvent: (YdEvent) -> Unit) {
    TopBar(state.t(Copy.appTitle), state.t(Copy.tagline)) {
        MuteKey(state, onEvent)
        Key(state.t(Copy.settings), "settings", icon = R.drawable.ic_settings) { onEvent(YdEvent.OpenSettings) }
    }
    if (state.eyeOff) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Line(state.t(Copy.eyeOff), "eyeOff", YdType.small, modifier = Modifier.weight(1f), lines = 2)
            Key(state.t(Copy.turnOn), "eyeOn") { onEvent(YdEvent.TurnOnEye) }
        }
    } else Spacer(Modifier.height(8.dp))
    Spacer(Modifier.height(8.dp))
    val next = (1..8).firstOrNull { it !in state.passedLessons }
    val lessonSub = when {
        state.passedLessons.isEmpty() -> state.t(Copy.firstLesson)
        next == null -> state.t(Copy.allLessons)
        else -> state.f(Copy.nextLesson, Copy.lessonList8[next - 1].name.of(state.zh), next)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
            Card(state.t(Copy.lessons), lessonSub, R.drawable.ic_school, "card_lessons", Modifier.weight(1f)) { onEvent(YdEvent.OpenLessons) }
            Card(state.t(Copy.library), state.t(Copy.librarySub), R.drawable.ic_explore, "card_library", Modifier.weight(1f)) { onEvent(YdEvent.OpenLibrary) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
            Card(state.t(Copy.mock), state.t(Copy.mockSub), R.drawable.ic_timer, "card_mock", Modifier.weight(1f)) { onEvent(YdEvent.OpenMock) }
            Card(state.t(Copy.free), state.t(Copy.freeSub), R.drawable.ic_shuffle, "card_free", Modifier.weight(1f)) { onEvent(YdEvent.OpenFree) }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Line(state.t(Copy.disclaimer), "disclaimer", YdType.small, modifier = Modifier.weight(1f), lines = 2)
        Key(state.t(Copy.scorecard) + if (state.recordCount > 0) " · ${state.recordCount}" else "", "scorecard", icon = R.drawable.ic_chevron_right) { onEvent(YdEvent.OpenScorecard) }
    }
}

@Composable
private fun Card(title: String, sub: String, icon: Int, name: String, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxHeight().probe(name),
        size = ButtonDefaults.Regular.copy(height = 150.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary),
    ) {
        Row(modifier = Modifier.fillMaxSize().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // a blue icon straight on the grey card is about 2:1; it sits in a disc with dark ink (D13)
            Box(modifier = Modifier.size(56.dp).clip(CircleShape).background(YdColors.InteractionFill), contentAlignment = Alignment.Center) {
                Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(32.dp), tint = YdColors.Ink)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Line(title, name, YdType.headline)
                Line(sub, "${name}_sub", YdType.small, lines = 2)
            }
        }
    }
}

@Composable
private fun ColumnScope.Lessons(state: YdUiState, onEvent: (YdEvent) -> Unit) {
    TopBar(state.t(Copy.lessons), state.t(Copy.lessonsSub)) { BackKey(state, onEvent) }
    val next = (1..8).firstOrNull { it !in state.passedLessons }
    val groups = listOf(0 to listOf(1, 2), 1 to listOf(3, 4, 5), 2 to listOf(6, 7, 8))
    for ((g, ns) in groups) {
        Line(state.t(Copy.groups[g]), "group$g", YdType.small, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.height(26.dp).padding(top = 4.dp))
        for (n in ns) {
            val l = Copy.lessonList8[n - 1]
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(50.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onEvent(YdEvent.OpenLesson(n)) },
                    modifier = Modifier.weight(1f).height(48.dp).probe("lesson_$n"),
                    size = ButtonDefaults.Regular.copy(height = 48.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Line("$n", "lesson_${n}_n", YdType.title, modifier = Modifier.width(22.dp))
                        Line(l.name.of(state.zh), "lesson_${n}_name", YdType.label)
                        Line(l.slogan.of(state.zh), "lesson_${n}_slogan", YdType.small, modifier = Modifier.weight(1f))
                    }
                }
                when {
                    n in state.passedLessons -> Badge(state.t(Copy.passedChip), "lesson_${n}_done", YdColors.PassText)
                    n == next -> Badge(state.t(Copy.nextChip), "lesson_${n}_next", YdColors.InteractionFill)
                }
                if (n in state.demosSeen && n >= 2) Key(state.t(Copy.demoAgain), "lesson_${n}_demo") { onEvent(YdEvent.OpenLesson(n, demo = true)) }
            }
        }
    }
}

@Composable
private fun ColumnScope.Library(state: YdUiState, onEvent: (YdEvent) -> Unit) {
    TopBar(state.t(Copy.library), state.t(Copy.libraryTitleSub)) { BackKey(state, onEvent) }
    val groups = listOf(0 to (1..5).toList(), 1 to (6..8).toList(), 2 to (9..12).toList())
    for ((g, ns) in groups) {
        Line(state.t(Copy.libGroups[g]), "libgroup$g", YdType.small, modifier = Modifier.height(28.dp).padding(top = 6.dp))
        for (row in ns.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(56.dp)) {
                for (n in row) {
                    val lp = Copy.lp["LP$n"]!!
                    Key("LP$n  " + lp.name.of(state.zh), "lp_$n", kind = if (state.lp == n) KeyKind.ON else KeyKind.NORMAL, modifier = Modifier.weight(1f)) { onEvent(YdEvent.PickLp(n)) }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ColumnScope.FreePick(state: YdUiState, onEvent: (YdEvent) -> Unit) {
    TopBar(state.t(Copy.freePick), state.t(Copy.freePickSub)) { BackKey(state, onEvent) }
    Spacer(Modifier.height(16.dp))
    for (d in 1..3) {
        Key(state.t(Copy.diffs[d - 1]), "diff_$d", kind = if (d == 2) KeyKind.PRIMARY else KeyKind.NORMAL, height = 64.dp, modifier = Modifier.fillMaxWidth()) { onEvent(YdEvent.FreeLevel(d)) }
        Spacer(Modifier.height(12.dp))
    }
    Spacer(Modifier.weight(1f))
    Line(state.t(Copy.freeStart), "freeStart", YdType.small, lines = 2)
}

@Composable
private fun ColumnScope.Paused(state: YdUiState, onEvent: (YdEvent) -> Unit) {
    TopBar(state.t(Copy.paused), state.t(Copy.pausedSub))
    Spacer(Modifier.height(16.dp))
    Key(state.t(Copy.resume), "resume", icon = R.drawable.ic_play_arrow, kind = KeyKind.PRIMARY, height = 56.dp, modifier = Modifier.fillMaxWidth()) { onEvent(YdEvent.Resume) }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Key(state.t(Copy.restart), "restart", icon = R.drawable.ic_replay, height = 56.dp, modifier = Modifier.weight(1f)) { onEvent(YdEvent.Restart) }
        if (state.screen == Screen.RUN && !state.run.exam) Key(state.t(Copy.backToPoint), "backPoint", height = 56.dp, modifier = Modifier.weight(1f)) { onEvent(YdEvent.BackToPoint) }
    }
    Spacer(Modifier.height(12.dp))
    Key(state.t(Copy.home), "home", icon = R.drawable.ic_home, height = 56.dp, modifier = Modifier.fillMaxWidth()) { onEvent(YdEvent.Home) }
}

@Composable
private fun ColumnScope.TourIntro(state: YdUiState, onEvent: (YdEvent) -> Unit) {
    TopBar(state.t(Copy.tourTitle), state.t(Copy.tourSub)) { BackKey(state, onEvent) }
    Spacer(Modifier.height(12.dp))
    Line(state.t(Copy.tourBody), "tourBody", YdType.body, lines = 4)
    Spacer(Modifier.height(16.dp))
    FormRow(state.t(Copy.seatHeight), state.t(Copy.seatHeightSub), "tourSeat", Modifier.fillMaxWidth()) { SeatSeg(state, onEvent) }
    Spacer(Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Key(state.t(Copy.start), "tourStart", icon = R.drawable.ic_play_arrow, kind = KeyKind.PRIMARY, height = 56.dp, modifier = Modifier.weight(2f)) { onEvent(YdEvent.TourStart) }
        Key(state.t(Copy.skipLesson), "tourSkip", icon = R.drawable.ic_skip_next, height = 56.dp, modifier = Modifier.weight(1f)) { onEvent(YdEvent.SkipLesson) }
    }
}

// =============================================================================================== result (S12)

@Composable
private fun ColumnScope.Result(state: YdUiState, onEvent: (YdEvent) -> Unit) {
    val r = state.result ?: return
    TopBar(r.title.of(state.zh), if (r.mode == Mode.LESSON && r.lessonTrials > 1) state.f(Copy.trialOf, r.lessonTrial.coerceAtMost(r.lessonTrials), r.lessonTrials) else null) {
        if (r.tour == null && r.lane == null) Seg(Copy.resultTabs.mapIndexed { i, s -> state.t(s) to (state.resultPage == i) }, "resultTab", 76.dp) { onEvent(YdEvent.ResultPage(it)) }
    }
    when (if (r.tour != null || r.lane != null) 0 else state.resultPage) {
        1 -> ResultPoints(state, r)
        2 -> ResultPath(state, r, onEvent)
        else -> ResultMain(state, r, onEvent)
    }
}

@Composable
private fun ColumnScope.ResultMain(state: YdUiState, r: ResultView, onEvent: (YdEvent) -> Unit) {
    val ok = r.passed
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(modifier = Modifier.size(52.dp).clip(CircleShape).background(if (ok) YdColors.PassText else YdColors.FailText), contentAlignment = Alignment.Center) {
            Icon(painter = painterResource(if (ok) R.drawable.ic_check_circle else R.drawable.ic_cancel), contentDescription = null, modifier = Modifier.size(34.dp), tint = YdColors.Ink)
        }
        val title = if (r.mockCall) state.t(Copy.mockFail) else if (ok) state.t(Copy.outcomes[Outcome.PASS]!!) else state.t(Copy.outcomes[if (r.outcome == Outcome.PASS) Outcome.OUT else r.outcome]!!)
        Line(title, "outcome", YdType.headline.copy(fontSize = YdType.headline.fontSize * 1.15f), modifier = Modifier.weight(1f))
        if (r.tour == null && r.lane == null) Line("${r.score}", "score", YdType.score)
    }
    when {
        r.tour != null -> Line(state.f(Copy.tourResult, r.tour.first, r.tour.second), "tourLine", YdType.body)
        r.lane != null -> Line(state.f(Copy.laneResult, r.lane.first, state.t(if (r.lane.second) Copy.yes else Copy.no), state.t(if (r.lane.third) Copy.yes else Copy.no)), "laneLine", YdType.body)
        else -> {
            val line = if (r.stops > 0) state.f(Copy.stopsLine, r.stops, r.stops * 5, mmss(r.timeS)) else state.f(Copy.timeLine, mmss(r.timeS))
            Line(line + if (r.unscored) " · " + state.t(Copy.unscoredLine) else "", "deductions", YdType.label)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                r.segs.forEachIndexed { i, s ->
                    val done = r.segOk.getOrElse(i) { false }
                    val txt = s.zh.substringBefore(' ').let { n -> if (state.zh) n else s.en.substringBefore(' ') } + if (done) " ✓ " + mmss(r.segTime.getOrElse(i) { 0.0 }) else " —"
                    if (done) Badge(txt, "seg_$i", YdColors.PassText) else Chip(txt, "seg_$i")
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    if (r.reason != null && !r.passed) Line(r.reason.of(state.zh), "reason", YdType.body, lines = 2)
    Line(r.advice.of(state.zh), "advice", YdType.body, lines = 2)
    r.groupLine?.let { Line(it.of(state.zh), "group", YdType.label) }
    Spacer(Modifier.weight(1f))
    Line(state.t(Copy.sourceLine), "source", YdType.small.copy(fontSize = YdType.small.fontSize * 0.9f), lines = 2)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        when {
            r.mode == Mode.LESSON && r.lessonPassedNow && r.lesson < 8 -> {
                Key(state.t(Copy.nextLessonBtn), "nextLesson", icon = R.drawable.ic_arrow_forward, kind = KeyKind.PRIMARY, height = 56.dp, modifier = Modifier.weight(1.4f)) { onEvent(YdEvent.NextLesson) }
                Key(state.t(Copy.goAgain), "again", icon = R.drawable.ic_replay, height = 56.dp, modifier = Modifier.weight(1f)) { onEvent(YdEvent.GoAgain) }
            }
            r.mode == Mode.LESSON && r.lessonDone && !r.lessonPassedNow -> {
                Key(state.t(Copy.redoLesson), "redo", icon = R.drawable.ic_restart_alt, kind = KeyKind.PRIMARY, height = 56.dp, modifier = Modifier.weight(1.2f)) { onEvent(YdEvent.RedoLesson) }
                Key(state.t(Copy.lessonList), "lessonList", height = 56.dp, modifier = Modifier.weight(1f)) { onEvent(YdEvent.LessonList) }
            }
            r.mode == Mode.FREE && r.groupDone -> Key(state.t(Copy.anotherGroup), "anotherGroup", icon = R.drawable.ic_shuffle, kind = KeyKind.PRIMARY, height = 56.dp, modifier = Modifier.weight(1.2f)) { onEvent(YdEvent.AnotherGroup) }
            else -> Key(state.t(Copy.goAgain), "again", icon = R.drawable.ic_replay, kind = KeyKind.PRIMARY, height = 56.dp, modifier = Modifier.weight(1.2f)) { onEvent(YdEvent.GoAgain) }
        }
        Key(state.t(Copy.home), "home", icon = R.drawable.ic_home, height = 56.dp, modifier = Modifier.weight(0.8f)) { onEvent(YdEvent.Home) }
    }
}

private fun hitLabel(h: Hit, zh: Boolean): String {
    val name = Copy.lp[h.lp]?.name?.of(zh) ?: h.lp
    val seg = h.seg.zh.substringBefore(' ').let { if (zh) it else h.seg.en.substringBefore(' ') }
    return "$seg ${h.lp} $name"
}

@Composable
private fun ColumnScope.ResultPoints(state: YdUiState, r: ResultView) {
    Spacer(Modifier.height(8.dp))
    if (r.hits.isEmpty()) {
        Line(state.t(Copy.noHits), "noHits", YdType.body)
    } else {
        val cols = r.hits.chunked(((r.hits.size + 1) / 2).coerceAtLeast(1))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            for ((ci, col) in cols.withIndex()) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(modifier = Modifier.fillMaxWidth().height(32.dp)) {
                        Line(state.t(Copy.pointCol), "pointCol$ci", YdType.small, modifier = Modifier.weight(1f))
                        Line(state.t(Copy.earlyLateCol), "elCol$ci", YdType.small)
                    }
                    for ((i, h) in col.withIndex()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            Line(hitLabel(h, state.zh), "hit_${ci}_$i", YdType.label, modifier = Modifier.weight(1f))
                            val txt = when (h.kind) {
                                Hit.Kind.STOP -> if (h.seg.bayIn) (if (h.lateCm >= 0) (if (state.zh) "深 ${h.lateCm} cm" else "${h.lateCm} cm deep") else (if (state.zh) "浅 ${-h.lateCm} cm" else "${-h.lateCm} cm short")) else (if (h.lateCm >= 0) (if (state.zh) "过线多 ${h.lateCm} cm" else "${h.lateCm} cm past") else (if (state.zh) "过线少 ${-h.lateCm} cm" else "${-h.lateCm} cm short"))
                                else -> if (h.lateCm <= 0) state.f(Copy.early, -h.lateCm) else state.f(Copy.late, h.lateCm)
                            }
                            Badge(txt, "hitv_${ci}_$i", when (h.band) { 0 -> YdColors.PassText; 1 -> YdColors.Warn; else -> YdColors.FailText })
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.weight(1f))
    Line(state.f(Copy.gapsLine, m2(r.gapR), m2(r.gapL), m2(r.nose)), "gaps", YdType.label, lines = 2)
    Line(state.t(Copy.bandsLine), "bands", YdType.small)
}

@Composable
private fun ColumnScope.ResultPath(state: YdUiState, r: ResultView, onEvent: (YdEvent) -> Unit) {
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.size(280.dp).clip(RoundedCornerShape(14.dp))) { PathCanvas(r) }
        Column(modifier = Modifier.weight(1f).height(280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Line(state.t(Copy.pathLegend), "legend", YdType.small, lines = 2)
            val total = r.looks.values.sum().coerceAtLeast(1)
            for (lk in listOf(Look.AHEAD, Look.MIRROR_L, Look.MIRROR_R, Look.MIRROR_I, Look.REAR)) {
                val pct = (r.looks[lk] ?: 0) * 100 / total
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(30.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Line(if (state.zh) lk.zh else lk.en, "heat_${lk.name}", YdType.small, modifier = Modifier.width(84.dp))
                    Box(modifier = Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(PicoTheme.colorScheme.fillTertiary)) {
                        Box(modifier = Modifier.fillMaxWidth(pct / 100f).height(14.dp).background(YdColors.Cue))
                    }
                    Line("$pct%", "heatv_${lk.name}", YdType.small, modifier = Modifier.width(44.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Key(state.t(Copy.watchReplay), "replay", icon = R.drawable.ic_play_arrow, kind = KeyKind.PRIMARY, height = 56.dp, enabled = r.trail.size > 2, modifier = Modifier.fillMaxWidth()) { onEvent(YdEvent.WatchReplay) }
        }
    }
}

/** Top-down path (north up): yard lines, your rear-axle path, the ideal one, red dot where a line was touched. */
@Composable
private fun PathCanvas(r: ResultView) {
    Canvas(modifier = Modifier.fillMaxSize().background(YdColors.Asphalt)) {
        val x0 = -11.0
        val z0 = -8.5
        val span = 22.0
        val k = (size.width / span).toFloat()
        fun pt(x: Double, z: Double) = Offset(((x - x0) * k).toFloat(), ((z - z0) * k).toFloat())
        for (p in Yard.paint.filter { !it.aux }) drawLine(if (p.yellow) YdColors.BayYellow else YdColors.LineWhite, pt(p.seg.a.x, p.seg.a.z), pt(p.seg.b.x, p.seg.b.z), strokeWidth = 2.5f)
        if (r.trail.size > 1) {
            val path = Path()
            r.trail.forEachIndexed { i, q -> val o = pt(q.x, q.z); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            drawPath(path, Color.White, style = Stroke(width = 3f))
        }
        r.touchAt?.let { drawCircle(YdColors.LineRed, 7f, pt(it.x, it.z)) }
    }
}

// =============================================================================================== scorecard (S16)

@Composable
private fun ColumnScope.Scorecard(state: YdUiState, onEvent: (YdEvent) -> Unit) {
    val eyeH = state.settings.eyeH
    val seat = Copy.seats[state.settings.seat].of(state.zh)
    TopBar(state.t(Copy.scorecard), state.f(Copy.seatNote, seat, String.format(Locale.US, "%.2f", Car.mirrorGround(eyeH).first), String.format(Locale.US, "%.2f", Car.hoodGround(eyeH))), subLines = 2) { BackKey(state, onEvent) }
    val sv = state.score
    Spacer(Modifier.height(8.dp))
    val w = listOf(1.3f, 0.7f, 1.4f, 0.9f, 0.9f)
    Row(modifier = Modifier.fillMaxWidth().height(36.dp)) {
        listOf(Copy.modeCol, Copy.runsCol, Copy.passCol, Copy.avgCol, Copy.stopsCol).forEachIndexed { i, c -> Line(state.t(c), "col$i", YdType.small, modifier = Modifier.weight(w[i])) }
    }
    val modes = listOf(Mode.LESSON to Copy.lessons, Mode.MOCK to Copy.mock, Mode.FREE to Copy.free)
    for ((m, name) in modes) {
        val row = sv?.rows?.firstOrNull { it.mode == m }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Line(state.t(name), "row_${m.name}", YdType.label, modifier = Modifier.weight(w[0]))
            Line("${row?.runs ?: 0}", "runs_${m.name}", YdType.label, modifier = Modifier.weight(w[1]))
            Line(row?.passRate10?.let { "$it%" } ?: "—", "pass_${m.name}", YdType.label, modifier = Modifier.weight(w[2]))
            Line(row?.avgTimeS?.let { mmss(it) } ?: "—", "avg_${m.name}", YdType.label, modifier = Modifier.weight(w[3]))
            Line(row?.avgStops?.let { String.format(Locale.US, "%.1f", it) } ?: "—", "stops_${m.name}", YdType.label, modifier = Modifier.weight(w[4]))
        }
    }
    Spacer(Modifier.height(10.dp))
    val mc = sv?.mostCommon
    if (sv == null || sv.count == 0) Line(state.t(Copy.nothingYet), "nothing", YdType.body)
    else if (mc != null) {
        val name = Copy.lp[mc.first]?.name?.of(state.zh) ?: mc.first
        val txt = if (state.zh) String.format(Copy.mostCommon.zh, name, if (mc.second < 0) "早" else "晚", abs(mc.second)) else String.format(Copy.mostCommon.en, name, abs(mc.second), if (mc.second < 0) "early" else "late")
        Line(txt, "mostCommon", YdType.body)
    }
    if (sv != null && sv.count > 0) Line(state.f(Copy.medianGaps, m2(sv.gapR), m2(sv.gapL)), "medianGaps", YdType.label)
    Spacer(Modifier.weight(1f))
    if (state.confirm == Confirm.CLEAR) ConfirmRow(state.t(Copy.clearAsk), state.t(Copy.clear), "clearYes", state, onEvent) { onEvent(YdEvent.ConfirmClear) }
    else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Key(state.t(Copy.clearScores), "clearScores", icon = R.drawable.ic_delete) { onEvent(YdEvent.AskClear) }
        Line(state.t(Copy.asksAgain), "asksAgain", YdType.small)
    }
}

@Composable
private fun ConfirmRow(question: String, yes: String, name: String, state: YdUiState, onEvent: (YdEvent) -> Unit, onYes: () -> Unit) {
    // a confirm row in the error colour as the fill with dark ink (D13: never red text on grey)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(18.dp)).background(YdColors.Danger).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Line(question, "${name}_q", YdType.title, color = YdColors.Ink, modifier = Modifier.weight(1f))
        Key(state.t(Copy.cancel), "${name}_cancel") { onEvent(YdEvent.CancelConfirm) }
        Key(yes, name, icon = R.drawable.ic_logout, kind = KeyKind.PRIMARY, onClick = onYes)
    }
}

// =============================================================================================== settings (S17)

@Composable
private fun ColumnScope.SettingsPanel(state: YdUiState, onEvent: (YdEvent) -> Unit) {
    val st = state.settings
    fun put(s: io.embodit.yarddrive.domain.Settings) = onEvent(YdEvent.SetSettings(s))
    TopBar(state.t(Copy.settings), null) {
        Seg(listOf(state.t(Copy.pagePractice) to (state.settingsPage == 0), state.t(Copy.pageSystem) to (state.settingsPage == 1)), "setPage", 84.dp) { onEvent(YdEvent.SettingsPage(it)) }
        BackKey(state, onEvent)
    }
    Spacer(Modifier.height(8.dp))
    val items: List<@Composable (Modifier) -> Unit> =
        if (state.settingsPage == 0) listOf(
            { m -> FormRow(state.t(Copy.seatHeight), null, "set_seat", m) { SeatSeg(state, onEvent) } },
            { m -> FormRow(state.t(Copy.speed), String.format(Locale.US, "%.1f m/s", st.speed), "set_speed", m) { Slider(value = st.speed.toFloat(), onValueChange = { put(st.copy(speed = (Math.round(it * 10) / 10.0))) }, valueRange = Car.SPEED_MIN.toFloat()..Car.SPEED_MAX.toFloat(), modifier = Modifier.width(150.dp).probe("set_speed_slider")) } },
            { m -> FormRow(state.t(Copy.startGap), state.t(Copy.startGapSub), "set_gap", m) { Seg(Yard.GAPS.map { g -> String.format(Locale.US, "%.1f", g) to (abs(st.gap - g) < 1e-6) }, "gap", 52.dp) { put(st.copy(gap = Yard.GAPS[it])) } } },
            { m -> FormRow(state.t(Copy.coachCues), null, "set_hints", m) { Toggle(st.hints, "sw_hints") { put(st.copy(hints = it)) } } },
            { m -> FormRow(state.t(Copy.topViewSet), null, "set_top", m) { Toggle(st.topView, "sw_top") { put(st.copy(topView = it)) } } },
            { m -> FormRow(state.t(Copy.polesSet), state.t(Copy.polesSub), "set_poles", m) { Toggle(st.poles, "sw_poles") { put(st.copy(poles = it)) } } },
            { m -> FormRow(state.t(Copy.comfort), null, "set_comfort", m) { Toggle(st.comfort, "sw_comfort") { put(st.copy(comfort = it)) } } },
            { m ->
                FormRow(state.t(Copy.mirrorAdj), state.f(Copy.mirrorSub, st.mirrorOut, st.mirrorPitch), "set_mirror", m) {
                    Slider(value = st.mirrorOut.toFloat(), onValueChange = { put(st.copy(mirrorOut = it.roundToInt())) }, valueRange = 0f..15f, modifier = Modifier.width(84.dp).probe("set_mirror_out"))
                    Slider(value = st.mirrorPitch.toFloat(), onValueChange = { put(st.copy(mirrorPitch = it.roundToInt())) }, valueRange = -20f..0f, modifier = Modifier.width(84.dp).probe("set_mirror_pitch"))
                }
            },
            { m -> FormRow(state.t(Copy.strictSet), state.t(Copy.strictSub), "set_strict", m) { Toggle(st.strict, "sw_strict") { put(st.copy(strict = it)) } } },
            { m -> FormRow(state.t(Copy.coachHand), state.t(Copy.coachHandSub), "set_hand", m) { Toggle(st.coachHand, "sw_coachHand") { put(st.copy(coachHand = it)) } } },
        ) else listOf(
            { m -> FormRow(state.t(Copy.language), null, "set_lang", m) { Seg(listOf("中文" to st.zh, "English" to !st.zh), "lang", 88.dp) { put(st.copy(zh = it == 0)) } } },
            { m -> FormRow(state.t(Copy.input), null, "set_input", m) { Seg(Copy.inputs.mapIndexed { i, s -> state.t(s) to (st.hands == (i == 1)) }, "input", 116.dp) { put(st.copy(hands = it == 1)) } } },
            { m -> FormRow(state.t(Copy.handThrottle), state.t(Copy.handThrottleSub), "set_throttle", m) { Seg(Copy.throttles.mapIndexed { i, s -> state.t(s) to (st.pinchHold == (i == 1)) }, "throttle", 120.dp, enabled = st.hands) { put(st.copy(pinchHold = it == 1)) } } },
            { m -> FormRow(state.t(Copy.gazeDot), state.t(Copy.gazeDotSub), "set_gaze", m) { Toggle(st.gazeDot, "sw_gaze") { put(st.copy(gazeDot = it)) } } },
            { m ->
                FormRow(state.t(Copy.volume), state.t(Copy.volumeSub), "set_volume", m) {
                    SegmentSlider(initialStep = st.volume, segmentCount = 3, onStepChange = { v -> put(st.copy(volume = v.coerceIn(0, 2))) }, modifier = Modifier.width(110.dp).probe("set_volume_slider"))
                    MuteKey(state, onEvent, "set_mute")
                }
            },
            { m -> FormRow(state.t(Copy.reseat), state.t(Copy.reseatSub), "set_reseat", m) { Key(state.t(Copy.reseat), "reseat", icon = R.drawable.ic_my_location) { onEvent(YdEvent.Reseat) } } },
            { m -> FormRow(state.t(Copy.clearScores), state.t(Copy.clearSub), "set_clear", m) { Key(state.t(Copy.clear), "clear", icon = R.drawable.ic_delete) { onEvent(YdEvent.OpenScorecard); onEvent(YdEvent.AskClear) } } },
            { m -> FormRow(state.t(Copy.about), state.f(Copy.aboutSub, state.version), "set_about", m) { Key(state.t(Copy.open), "about", icon = R.drawable.ic_info) { onEvent(YdEvent.About) } } },
        )
    val half = (items.size + 1) / 2
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { for (i in 0 until half) items[i](Modifier.fillMaxWidth()) }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { for (i in half until items.size) items[i](Modifier.fillMaxWidth()) }
    }
    Spacer(Modifier.weight(1f))
    if (state.confirm == Confirm.EXIT) ConfirmRow(state.t(Copy.exitConfirm), state.t(Copy.quit), "quitYes", state, onEvent) { onEvent(YdEvent.Exit) }
    else Key(state.t(Copy.quitApp), "quitApp", icon = R.drawable.ic_logout, kind = KeyKind.DANGER, height = 56.dp, modifier = Modifier.fillMaxWidth()) { onEvent(YdEvent.AskExit) }
}

@Composable
private fun ColumnScope.About(state: YdUiState, onEvent: (YdEvent) -> Unit) {
    TopBar(state.t(Copy.about), state.t(Copy.appTitle) + " v" + state.version) { BackKey(state, onEvent) }
    Spacer(Modifier.height(8.dp))
    Line(state.f(Copy.aboutBody, state.version), "aboutBody", YdType.small, lines = 18)
    Spacer(Modifier.weight(1f))
    Line(state.t(Copy.disclaimer), "aboutDisclaimer", YdType.small, lines = 2)
}
