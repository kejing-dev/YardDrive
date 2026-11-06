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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import com.pico.spatial.ui.design.PicoTheme
import io.embodit.yarddrive.R
import io.embodit.yarddrive.domain.Car
import io.embodit.yarddrive.domain.Copy
import io.embodit.yarddrive.domain.Gear
import io.embodit.yarddrive.domain.LookPoints
import io.embodit.yarddrive.domain.Mirrors
import io.embodit.yarddrive.domain.Mode
import io.embodit.yarddrive.domain.MrdReadout
import io.embodit.yarddrive.domain.Outcome
import io.embodit.yarddrive.domain.P2
import io.embodit.yarddrive.domain.Pose
import io.embodit.yarddrive.domain.RunPhase
import io.embodit.yarddrive.domain.SegId
import io.embodit.yarddrive.domain.StopView
import io.embodit.yarddrive.domain.Tr
import io.embodit.yarddrive.domain.Yard
import io.embodit.yarddrive.ui.RunView
import io.embodit.yarddrive.ui.Screen
import io.embodit.yarddrive.ui.YdEvent
import io.embodit.yarddrive.ui.YdUiState
import io.embodit.yarddrive.ui.scene.PanelSizes
import io.embodit.yarddrive.ui.theme.YdColors
import io.embodit.yarddrive.ui.theme.YdType
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

private fun mmss(s: Double): String = String.format(Locale.US, "%02d:%02d", (s / 60).toInt(), (s % 60).toInt())

/**
 * P2 head-up strip (design panels P2): 1160 × 240 dp, four columns 220 / 400 / 180 / 280: state, coach, top view, keys.
 */
@Composable
internal fun P2Panel(state: YdUiState?, onEvent: (YdEvent) -> Unit) {
    Glass(PanelSizes.P2_W, PanelSizes.P2_H, shape = RoundedCornerShape(28.dp), padding = 16.dp) {
        if (state == null) return@Glass
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.width(220.dp).fillMaxHeight()) { StateCol(state, onEvent) }
            Column(modifier = Modifier.width(400.dp).fillMaxHeight()) { CoachCol(state) }
            Column(modifier = Modifier.width(180.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) { TopCol(state) }
            Column(modifier = Modifier.width(280.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) { KeysCol(state, onEvent) }
        }
    }
}

// ------------------------------------------------------------------------------------------------ column 1: state

@Composable
private fun ColumnScope.StateCol(state: YdUiState, onEvent: (YdEvent) -> Unit) {
    val r = state.run
    val gearWord = if (r.gear == Gear.R) Copy.gearR else Copy.gearD
    val showRing = state.screen == Screen.RUN && r.stop.kind in setOf(StopView.Kind.TIMER, StopView.Kind.TIMER_AMBER, StopView.Kind.PENALTY)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tag(if (r.gear == Gear.R) "R" else "D", "gear", YdType.gear.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified), if (r.wrongGear && state.screen == Screen.RUN) YdColors.Warn else null)
        Column(modifier = Modifier.weight(1f)) {
            Line(state.t(gearWord), "gearWord", YdType.label)
            val speed = when {
                state.screen == Screen.POINT || state.screen == Screen.TOUR -> state.t(Copy.parked)
                r.timed && !state.screen.let { it == Screen.DEMO || it == Screen.REPLAY } -> mmss(r.elapsed) + " / 03:30"
                r.stepMode && state.screen == Screen.RUN -> state.t(Copy.stepSpeed)
                else -> String.format(Locale.US, "%.1f m/s", r.speed)
            }
            Line(speed, "speed", YdType.label)
        }
        if (showRing) StopRing(r)
    }
    val sub = when {
        state.eyeOff && state.screen == Screen.RUN -> state.t(Copy.byHead)
        r.stops > 0 && r.exam -> state.t(Copy.stop2s) + " ×${r.stops}"
        else -> null
    }
    if (sub != null) Line(sub, "stateSub", YdType.small, lines = 2)
    Spacer(Modifier.weight(1f))
    // stop timer ring 0 -> 2.0 s (design S08) sits next to the gear: amber from 1 s; red with "-5" only once the judge charged it
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // English "Pause" was 2-3 dp over its 78 dp key (the v0.1.7 streamed sweep caught it; the v0.1.6 one read a rolled-over buffer): 15 sp there
        Key(state.t(Copy.pause), "p2_pause", enabled = state.screen in setOf(Screen.RUN, Screen.DEMO, Screen.TOUR), modifier = Modifier.width(if (state.zh) 80.dp else 78.dp), style = if (state.zh) null else YdType.small) { onEvent(YdEvent.Pause) }
        MuteKey(state, onEvent, "p2_mute")
    }
}

@Composable
private fun StopRing(r: RunView) {
    val t = r.stop.t.coerceAtMost(2.0)
    val red = r.stop.kind == StopView.Kind.PENALTY
    val color = if (red) YdColors.FailText else if (r.stop.kind == StopView.Kind.TIMER_AMBER) YdColors.Warn else YdColors.Cue
    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // the charged stop is a filled disc with ink on it: the state colour as the fill (D13, k2-3)
            if (red) drawCircle(color) else drawArc(Color.White.copy(alpha = 0.25f), 0f, 360f, false, style = Stroke(5f))
            if (!red) drawArc(color, -90f, (360 * t / 2.0).toFloat(), false, style = Stroke(5f))
        }
        if (red) Line("−5", "ring", YdType.ring, color = YdColors.Ink) else Line(String.format(Locale.US, "%.1f", t), "ring", YdType.small)
    }
}

// ------------------------------------------------------------------------------------------------ column 2: coach

@Composable
private fun ColumnScope.CoachCol(state: YdUiState) {
    val r = state.run
    // Go was tapped and can't: say why, in the place you are reading
    state.goNote?.let {
        Tag(state.t(it), "goNote", YdType.title, YdColors.Warn, lines = 3)
        return
    }
    val chips = ArrayList<Pair<String, Color?>>()
    if (state.screen == Screen.RUN) {
        r.free?.let { chips += state.f(Copy.freeChip, it.run, it.of, it.difficulty, it.seed) to null }
        if (r.stepByStep) chips += state.t(Copy.stepChip) to YdColors.InteractionFill
        if (r.lenient) chips += state.t(Copy.easyChip) to null
    }
    if (chips.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.height(30.dp)) {
            chips.forEachIndexed { i, (t, c) -> if (c != null) Badge(t, "chip$i", c) else Chip(t, "chip$i") }
        }
    }
    val segName = r.segs.getOrNull(r.segIndex)?.let { s -> state.t(Tr(s.zh, s.en)) } ?: ""
    when (state.screen) {
        Screen.POINT -> PointCoach(state)
        Screen.TOUR -> TourCoach(state)
        Screen.DEMO -> {
            Line("$segName · ${state.t(Copy.demo)}", "segName", YdType.title)
            Spacer(Modifier.height(6.dp))
            Line(state.demoSub?.of(state.zh) ?: "", "demoSub", YdType.body, lines = 3)
        }
        Screen.REPLAY -> {
            Line("$segName · ${state.t(Copy.replay)}", "segName", YdType.title)
            Spacer(Modifier.height(6.dp))
            state.replayNote?.let { Tag(it.of(state.zh), "replayNote", YdType.title, YdColors.Right, lines = 2) }
            Spacer(Modifier.weight(1f))
            Box(modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(PicoTheme.colorScheme.fillTertiary)) {
                Box(modifier = Modifier.fillMaxWidth((state.replayT / state.replayLen).toFloat().coerceIn(0f, 1f)).height(10.dp).background(YdColors.Cue))
            }
        }
        else -> RunCoach(state, segName)
    }
}

@Composable
private fun ColumnScope.RunCoach(state: YdUiState, segName: String) {
    val r = state.run
    if (state.lost) {
        Line(segName, "segName", YdType.title)
        Tag(state.t(Copy.lost), "cueBig", YdType.title.copy(fontSize = YdType.title.fontSize * 1.2f), YdColors.FailText, lines = 2)
        Line(state.t(Copy.lostSub), "cueSub", YdType.body, lines = 2)
        return
    }
    if (r.lane) {
        Line(r.segs.joinToString("  ") { state.t(Tr(it.zh, it.en)) }, "segName", YdType.title)
        Spacer(Modifier.height(4.dp))
        Line(state.t(if (r.segIndex == 0) Copy.laneTask else Copy.laneTask2), "task", YdType.body, lines = 3)
        if (r.phase == RunPhase.LINE) Tag(state.f(Copy.lineTouched, r.touchPart?.of(state.zh) ?: "", r.touchLine?.of(state.zh) ?: ""), "cueBig", YdType.title, YdColors.FailText, lines = 2)
        return
    }
    when (r.phase) {
        RunPhase.SEG_DONE -> {
            val seg = r.segs.getOrNull(r.segIndex)
            val next = r.segs.getOrNull(r.segIndex + 1)
            Line(segName, "segName", YdType.title)
            Tag(if (state.zh) "$segName 搞定" else "Segment ${seg?.en?.substringBefore(' ')} done", "cueBig", YdType.cue, YdColors.PassText)
            if (next != null) {
                val n = r.nose
                val noseTxt = if (seg?.bayIn == true && n != null) (if (state.zh) String.format(Locale.US, "前沿离库前线 %.2f m · ", n) else String.format(Locale.US, "Nose %.2f m past the line · ", n)) else ""
                val nextTxt = if (state.zh) "下一段：${next.zh}，切 ${next.gear.name}" else "next: ${next.en}, shift to ${next.gear.name}"
                Line(noseTxt + nextTxt, "cueSub", YdType.body, lines = 2)
            }
        }
        RunPhase.LINE -> {
            Line(segName, "segName", YdType.title)
            val big = when (r.lineOutcome) {
                Outcome.NOTIN -> state.t(Copy.outcomes[Outcome.NOTIN]!!)
                Outcome.ORDER -> state.t(Copy.outcomes[Outcome.ORDER]!!)
                else -> if (r.touchPart != null) state.f(Copy.lineTouched, r.touchPart.of(state.zh), r.touchLine?.of(state.zh) ?: "") else state.t(Copy.cues[(r.segs.getOrNull(r.segIndex)?.cornerLp ?: "LP3") + "late"]?.first ?: Copy.cues["LP3late"]!!.first)
            }
            Tag(big, "cueBig", YdType.title.copy(fontSize = YdType.title.fontSize * 1.2f), YdColors.FailText, lines = 2)
            r.reason?.let { Line(it.of(state.zh), "cueSub", YdType.body, lines = 2) }
        }
        RunPhase.FINISHED -> {
            Line(r.segs.indices.joinToString("  ") { i -> state.t(Tr(r.segs[i].zh, r.segs[i].en)).substringBefore(' ') + if (r.segDone.getOrElse(i) { false }) " ✓" else "" }, "segName", YdType.title)
            Tag(state.t(Copy.finished), "cueBig", YdType.cue, YdColors.PassText)
            Line(state.f(Copy.finishedSub, mmss(r.elapsed)), "cueSub", YdType.body)
        }
        else -> {
            val gearName = r.segs.getOrNull(r.segIndex)?.gear?.name ?: "R"
            val task = when {
                // design S15: free practice opens with this sentence, whatever the gear (q1-4)
                state.screen == Screen.RUN && r.mode == Mode.FREE && r.segIndex == 0 && r.phase == RunPhase.READY -> state.t(Copy.freeStart)
                r.phase == RunPhase.READY && r.wrongGear -> null
                r.phase == RunPhase.READY && r.stepMode -> state.f(Copy.readyStep, gearName)
                r.phase == RunPhase.READY -> state.f(Copy.ready, gearName)
                r.autoStopped -> state.t(Copy.autoStopped)
                r.stop.kind == StopView.Kind.SETTLING || r.stop.kind == StopView.Kind.NOSE_OUT -> null
                r.phase == RunPhase.STOPPED && r.cueBig == null && r.stepMode -> state.t(Copy.stepMode)
                r.phase == RunPhase.STOPPED && r.cueBig == null -> state.t(Copy.stopped)
                else -> r.task?.of(state.zh)
            }
            Line(segName, "segName", YdType.title)
            if (state.gearNote) Tag(state.t(Copy.gearLocked), "gearNote", YdType.title, YdColors.Warn)
            if (task != null) Line(task, "task", YdType.body, lines = 2)
            if (r.phase == RunPhase.READY) r.seatHint?.let { Line(state.f(Copy.seatHintFree, state.t(it)), "seatHint", YdType.small) }
            // the ring says the stop is being timed; under lesson rules say right there that it costs nothing (k2-4)
            if (!r.exam && (r.stop.kind == StopView.Kind.TIMER || r.stop.kind == StopView.Kind.TIMER_AMBER)) Line(state.t(Copy.lessonRingNote), "ringNote", YdType.small)
            if (r.stop.kind == StopView.Kind.SETTLING) {
                // design S08: at the end of the segment the wait is a confirmation, not a countdown to a penalty (R1)
                Tag(state.t(Copy.settling), "cueBig", YdType.cue, YdColors.PassText)
                Line(state.t(Copy.settlingSub), "cueSub", YdType.body, lines = 2)
            } else if (r.stop.kind == StopView.Kind.NOSE_OUT) {
                Tag(state.f(Copy.notIn, r.stop.noseOutCm), "cueBig", YdType.title, YdColors.Warn, lines = 2)
            } else if (r.phase == RunPhase.READY && r.wrongGear) {
                Tag(state.f(Copy.shiftFirst, gearName), "cueBig", YdType.cue, YdColors.Warn)
                Line(state.t(if (r.stepMode) Copy.stepMode else Copy.wrongGearSub), "cueSub", YdType.small, lines = 2)
            } else if (r.cueBig != null) {
                val color = when {
                    r.cueKind.endsWith("late") -> YdColors.FailText
                    r.cueBig.zh.startsWith("右") -> YdColors.Right
                    r.cueBig.zh.startsWith("左") -> YdColors.Left
                    else -> YdColors.Cue
                }
                Tag(r.cueBig.of(state.zh), "cueBig", YdType.cue, color)
                Line(if (r.autoStopped) state.t(Copy.autoStoppedSub) else r.cueSub?.of(state.zh) ?: "", "cueSub", YdType.body, lines = 2)
            }
            val c = r.countdown
            if (c != null && r.cueBig == null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.width(200.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(PicoTheme.colorScheme.fillTertiary)) {
                        Box(modifier = Modifier.fillMaxWidth((1 - c / 0.5).toFloat().coerceIn(0f, 1f)).height(10.dp).background(YdColors.Cue))
                    }
                    Line(state.f(Copy.toGo, c), "countdown", YdType.label)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.PointCoach(state: YdUiState) {
    val id = "LP${state.lp}"
    val lp = Copy.lp[id] ?: return
    Line("$id · " + lp.name.of(state.zh), "segName", YdType.title)
    Line(lp.doIt.of(state.zh), "lpDo", YdType.body, lines = 2)
    Tag(lp.look.of(state.zh), "lpLook", YdType.label, if (state.gazeOnLp) YdColors.Cue else null)
    Tag(thirdLine(state, id), "lpThird", YdType.label, if (state.gazeOnLp) YdColors.Cue else null, lines = 2)
}

/** The library's third line: how far off the chant is on this car at your seat (design lookPointLibrary). */
internal fun thirdLine(state: YdUiState, id: String): String {
    val eyeH = state.settings.eyeH
    val cm = LookPoints.chantLateCm(id, eyeH)
    val lp = Copy.lp[id]
    return when {
        cm == null -> lp?.third?.of(state.zh) ?: ""
        id == "LP5" -> state.f(Copy.chantDeep, cm)
        id == "LP8" -> state.f(Copy.chantPast, LookPoints.chantPast())
        else -> {
            val early = cm < 0
            val n = abs(cm)
            val delta = if (state.zh) String.format(Copy.chantDelta.zh, if (early) "早" else "晚", n) else String.format(Copy.chantDelta.en, n, if (early) "early" else "late")
            state.t(Copy.chantThisCar) + delta + if (n <= 5) state.t(Copy.chantSpotOn) else ""
        }
    }
}

@Composable
private fun ColumnScope.TourCoach(state: YdUiState) {
    val t = state.tour
    when (t.step) {
        1 -> {
            Line(state.t(Copy.tourSteps[0]), "segName", YdType.title)
            Line(state.t(Copy.tourLook), "task", YdType.body, lines = 2)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Copy.tourBlocks.forEachIndexed { i, b -> if (t.lit.getOrElse(i) { false }) Badge(state.t(b), "block$i", YdColors.Cue) else Chip(state.t(b), "block$i") }
            }
        }
        2 -> {
            Line(state.t(Copy.tourSteps[1]) + "  ${t.line + 1} / ${Copy.tourLines.size}", "segName", YdType.title)
            Line(state.t(Copy.tourLines[t.line.coerceIn(0, Copy.tourLines.size - 1)]), "task", YdType.body, lines = 3)
            if (Yard.tourInBay(t.line)) Line(state.t(Copy.tourInBay), "tourInBay", YdType.small)
        }
        else -> {
            val id = "LP${t.lp}"
            Line(state.t(Copy.tourSteps[2]) + "  ${t.lp} / 5 · " + (Copy.lp[id]?.name?.of(state.zh) ?: ""), "segName", YdType.title)
            Line(Copy.lp[id]?.doIt?.of(state.zh) ?: "", "lpDo", YdType.body, lines = 2)
            Line(thirdLine(state, id), "lpThird", YdType.label, lines = 2)
        }
    }
}

// ------------------------------------------------------------------------------------------------ column 3: top view

@Composable
private fun ColumnScope.TopCol(state: YdUiState) {
    val r = state.run
    val top = state.screen in setOf(Screen.DEMO, Screen.REPLAY, Screen.POINT, Screen.TOUR) || (state.settings.topView && r.mode == Mode.LESSON && r.lesson in 1..6)
    if (r.lane && state.screen != Screen.POINT) {
        Line(state.t(Copy.laneTitle), "col3title", YdType.small)
        Box(modifier = Modifier.size(160.dp).clip(RoundedCornerShape(12.dp))) { TopView(r, state, lane = true) }
        return
    }
    if (top) {
        Box(modifier = Modifier.size(160.dp).clip(RoundedCornerShape(12.dp))) { TopView(r, state, lane = false) }
        Spacer(Modifier.height(4.dp))
        Line(state.t(Copy.topView), "col3caption", YdType.small)
    } else if (r.exam) {
        Line(state.t(Copy.topExam), "col3caption", YdType.small)
        Spacer(Modifier.height(16.dp))
        Line("—", "gapBig", YdType.cue)
    } else {
        Line(state.t(Copy.cornerGap), "col3title", YdType.small)
        Spacer(Modifier.height(16.dp))
        val g = MrdReadout.topViewOffGap(r.segs.getOrNull(r.segIndex), r.gapL, r.gapR)
        Tag(g?.let { String.format(Locale.US, "%.2f m", it) } ?: "—", "gapBig", YdType.cue, if (g != null && g < 0.10) YdColors.Warn else null)
        Spacer(Modifier.weight(1f))
        Line(state.t(Copy.topOff), "col3caption", YdType.small)
    }
}

/** 160 × 160 top view, nose up (design: car-centred, the yard turns around it), ideal path and predicted end. */
@Composable
private fun TopView(r: RunView, state: YdUiState, lane: Boolean) {
    Canvas(modifier = Modifier.fillMaxSize().background(YdColors.Asphalt)) {
        val p = r.pose
        val f = p.f
        val rr = p.r
        val mid = P2(p.x + f.x * (Car.FRONT - Car.REAR) / 2, p.z + f.z * (Car.FRONT - Car.REAR) / 2)
        val k = size.width / 13f
        val c = Offset(size.width / 2, size.height / 2)
        fun pt(q: P2): Offset {
            val d = q - mid
            return Offset(c.x + (d.dot(rr) * k).toFloat(), c.y - (d.dot(f) * k).toFloat())
        }
        val lines = if (lane) Mirrors.lanePaint(Yard.GAP_DEFAULT) else Yard.paint.filter { !it.aux || state.settings.poles }
        for (l in lines) drawLine(if (l.yellow) YdColors.BayYellow else YdColors.LineWhite, pt(l.seg.a), pt(l.seg.b), strokeWidth = 2.5f, pathEffect = if (l.dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 4f)) else null)
        // yard tour: the lines this sentence names (R3)
        if (state.screen == Screen.TOUR && state.tour.step == 2) for (seg in Yard.tourLines(state.tour.line)) drawLine(if (Yard.tourYellow(state.tour.line)) YdColors.FlashYellow else YdColors.FlashWhite, pt(seg.a), pt(seg.b), strokeWidth = 6f)
        if (r.idealPath.size > 1 && !lane) {
            val path = Path()
            r.idealPath.forEachIndexed { i, q -> val o = pt(q); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            drawPath(path, YdColors.Cue.copy(alpha = 0.8f), style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f))))
        }
        r.predicted?.let { e -> carPoly(e, ::pt, YdColors.Cue.copy(alpha = 0.7f), fill = false) }
        carPoly(p, ::pt, YdColors.CarBody, fill = true)
        // nose mark
        val nose = P2(p.x + f.x * Car.FRONT, p.z + f.z * Car.FRONT)
        drawCircle(YdColors.CarEdge, 3f, pt(nose))
    }
}

private fun DrawScope.carPoly(p: Pose, pt: (P2) -> Offset, color: Color, fill: Boolean) {
    val cs = p.corners()
    val path = Path()
    cs.forEachIndexed { i, q -> val o = pt(q); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
    path.close()
    if (fill) {
        drawPath(path, color)
        drawPath(path, YdColors.CarEdge, style = Stroke(1.5f))
    } else drawPath(path, color, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f))))
}

// ------------------------------------------------------------------------------------------------ column 4: keys

/** The main key, always here: one tap goes, the next stops. When the car can't go it is grey, and a tap says why (user, 2026-11-05). */
@Composable
private fun GoKey(state: YdUiState, onEvent: (YdEvent) -> Unit, h: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val on = state.run.goOn && state.goReason() == null
    Key(if (on) state.t(Copy.stop) else state.t(Copy.go), "goStop", icon = if (on) R.drawable.ic_pause else R.drawable.ic_play_arrow, kind = if (state.goReason() == null) KeyKind.PRIMARY else KeyKind.NORMAL, height = h, modifier = modifier) { onEvent(YdEvent.GoToggle) }
}

/** D43 / q7-3: while Go is grey, the reason stays written under it, not only after a tap or the A button. */
@Composable
private fun GoWhy(state: YdUiState) {
    val why = state.goWhy() ?: return
    Line(state.t(why), "goWhy", YdType.small, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.fillMaxWidth(), align = TextAlign.End)
}

@Composable
private fun ColumnScope.KeysCol(state: YdUiState, onEvent: (YdEvent) -> Unit) {
    val r = state.run
    // four rows at most: 3 keys + the reason line fit the 208 dp column (240 - 2 x 16)
    val h = 52.dp
    when (state.screen) {
        Screen.POINT -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key(state.t(Copy.prev), "lpPrev", icon = R.drawable.ic_chevron_left, height = h, modifier = Modifier.weight(1f)) { onEvent(YdEvent.PrevLp) }
                Key(state.t(Copy.next), "lpNext", icon = R.drawable.ic_chevron_right, height = h, modifier = Modifier.weight(1f)) { onEvent(YdEvent.NextLp) }
            }
            Key(state.t(Copy.seatChange) + " · " + state.t(Copy.seats[state.settings.seat]), "seatCycle", height = h, modifier = Modifier.fillMaxWidth()) { onEvent(YdEvent.SeatCycle) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key(state.t(Copy.backHome), "lpHome", icon = R.drawable.ic_home, height = h, modifier = Modifier.weight(1.4f)) { onEvent(YdEvent.Home) }
                GoKey(state, onEvent, h, Modifier.weight(1f))
            }
            GoWhy(state)
        }
        Screen.TOUR -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.tour.step == 1) Key(state.t(Copy.skipRear), "skipRear", icon = R.drawable.ic_skip_next, height = h, modifier = Modifier.weight(1f)) { onEvent(YdEvent.TourSkipRear) }
                if (state.tour.step == 3) Key(state.t(Copy.seatChange), "seatCycle", height = h, modifier = Modifier.weight(1f)) { onEvent(YdEvent.SeatCycle) }
                Key(state.t(Copy.nextStep), "tourNext", icon = R.drawable.ic_arrow_forward, kind = KeyKind.PRIMARY, height = h, modifier = Modifier.weight(1f)) { onEvent(YdEvent.TourNext) }
            }
            Key(state.t(Copy.pause), "tourPause", icon = R.drawable.ic_pause, height = h, modifier = Modifier.fillMaxWidth()) { onEvent(YdEvent.Pause) }
            GoKey(state, onEvent, h, Modifier.fillMaxWidth())
            GoWhy(state)
        }
        Screen.DEMO -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key(state.t(Copy.topOnly), "topOnly", kind = if (state.demoTopOnly) KeyKind.ON else KeyKind.NORMAL, height = h, modifier = Modifier.weight(1.3f)) { onEvent(YdEvent.TopOnly) }
                Key(state.t(Copy.skip), "skipDemo", icon = R.drawable.ic_skip_next, height = h, modifier = Modifier.weight(1f)) { onEvent(YdEvent.SkipDemo) }
            }
            GoKey(state, onEvent, h, Modifier.fillMaxWidth())
            GoWhy(state)
        }
        Screen.REPLAY -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key("0.5×", "rp05", kind = if (state.replaySpeed < 0.9) KeyKind.ON else KeyKind.NORMAL, height = h, modifier = Modifier.weight(0.8f)) { onEvent(YdEvent.ReplaySpeed(0.5)) }
                Key("1×", "rp1", kind = if (state.replaySpeed >= 0.9) KeyKind.ON else KeyKind.NORMAL, height = h, modifier = Modifier.weight(0.8f)) { onEvent(YdEvent.ReplaySpeed(1.0)) }
            }
            Key(state.t(Copy.topOnly), "rpTop", kind = if (state.demoTopOnly) KeyKind.ON else KeyKind.NORMAL, height = h, modifier = Modifier.fillMaxWidth()) { onEvent(YdEvent.TopOnly) }
            // q7-3: Go is here too, grey, the reason under it
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key(state.t(Copy.endReplay), "endReplay", kind = KeyKind.PRIMARY, height = h, modifier = Modifier.weight(1.3f)) { onEvent(YdEvent.EndReplay) }
                GoKey(state, onEvent, h, Modifier.weight(1f))
            }
            GoWhy(state)
        }
        else -> {
            if (r.phase == RunPhase.LINE) {
                Key(state.t(Copy.backToPoint), "backPoint", kind = KeyKind.PRIMARY, height = h, modifier = Modifier.fillMaxWidth()) { onEvent(YdEvent.BackToPoint) }
                Key(state.t(Copy.carryOn), "carryOn", icon = R.drawable.ic_play_arrow, height = h, modifier = Modifier.fillMaxWidth()) { onEvent(YdEvent.CarryOn) }
                // q7-3: Go stays, grey, the reason under it; Restart run asks first (D44), on the pause page
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Key(state.t(Copy.restart), "restartRun", height = h, modifier = Modifier.weight(1.2f)) { onEvent(YdEvent.Restart) }
                    GoKey(state, onEvent, h, Modifier.weight(1f))
                }
                GoWhy(state)
                return
            }
            val w = r.wheel
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key(state.t(Copy.fullL), "fullL", kind = if (w <= -Car.WHEEL_MAX + 1) KeyKind.LEFT else KeyKind.NORMAL, height = h, modifier = Modifier.weight(1f)) { onEvent(YdEvent.Steer(-Car.WHEEL_MAX)) }
                Key(state.t(Copy.centre), "centre", kind = if (abs(w) < 1 && r.lane && r.laneSawLock) KeyKind.ON else KeyKind.NORMAL, height = h, modifier = Modifier.weight(1f)) { onEvent(YdEvent.Steer(0.0)) }
                Key(state.t(Copy.fullR), "fullR", kind = if (w >= Car.WHEEL_MAX - 1) KeyKind.RIGHT else KeyKind.NORMAL, height = h, modifier = Modifier.weight(1f)) { onEvent(YdEvent.Steer(Car.WHEEL_MAX)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key(state.t(Copy.rd), "gearKey", height = h, modifier = Modifier.weight(1f)) { onEvent(YdEvent.GearToggle) }
                // Easy restarts the run from the start line, so it can only be switched before you move off (R9a)
                if (r.mode == Mode.FREE) Key(state.t(Copy.easyChip), "easy", kind = if (r.lenient) KeyKind.ON else KeyKind.NORMAL, enabled = !r.started, height = h, modifier = Modifier.weight(1f)) { onEvent(YdEvent.Easy) }
                else Key(state.t(Copy.step), "stepKey", kind = if (r.stepMode) KeyKind.ON else KeyKind.NORMAL, enabled = !r.exam, height = h, modifier = Modifier.weight(1f)) { onEvent(YdEvent.StepMode) }
            }
            // q7-4: One step is the same check as Go: grey with the reason in a wrong gear, a tap does not move the car
            if (r.stepMode) Key(state.t(Copy.stepOnce), "stepOnce", kind = if (state.goReason() == null) KeyKind.PRIMARY else KeyKind.NORMAL, height = h, modifier = Modifier.fillMaxWidth()) { onEvent(YdEvent.StepOnce) }
            else GoKey(state, onEvent, h, Modifier.fillMaxWidth())
            GoWhy(state)
        }
    }
}
