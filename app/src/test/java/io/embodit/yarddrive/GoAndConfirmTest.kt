package io.embodit.yarddrive

import io.embodit.yarddrive.domain.Copy
import io.embodit.yarddrive.domain.Gear
import io.embodit.yarddrive.domain.RunPhase
import io.embodit.yarddrive.ui.Confirm
import io.embodit.yarddrive.ui.RunView
import io.embodit.yarddrive.ui.Screen
import io.embodit.yarddrive.ui.YdController
import io.embodit.yarddrive.ui.YdEvent
import io.embodit.yarddrive.ui.YdUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** QA round 7, through the real controller events: q7-3 (the Go key's reason), q7-4 (step key = Go's check), q7-7 (D44 confirms). */
@OptIn(ExperimentalCoroutinesApi::class)
class GoAndConfirmTest {
    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() {
        YdController.onEvent(YdEvent.Home)
        if (YdController.state.value.confirm == Confirm.HOME) YdController.onEvent(YdEvent.ConfirmHome)
        Dispatchers.resetMain()
    }

    private fun lessonRun(n: Int) {
        YdController.onEvent(YdEvent.Home)
        if (YdController.state.value.confirm == Confirm.HOME) YdController.onEvent(YdEvent.ConfirmHome)
        YdController.debugLesson(n)
        YdController.onEvent(YdEvent.OpenLesson(n, demo = false))
        var waited = 0
        while (YdController.state.value.screen != Screen.RUN && waited++ < 400) Thread.sleep(50)
        assertEquals(Screen.RUN, YdController.state.value.screen)
    }

    private fun pose() = YdController.run.pose.let { Triple(it.rear.x, it.rear.z, it.psiDeg) }

    /** q7-3: every state where Go can't go has a short reason under the key, including the line (S10) and the replay (S13). */
    @Test
    fun goIsGreyWithAReasonEverywhereItCannotGo() {
        val base = YdUiState()
        assertEquals(Copy.goWhyReplay, base.copy(screen = Screen.REPLAY).goWhy())
        assertEquals(Copy.goWhyDemo, base.copy(screen = Screen.DEMO).goWhy())
        assertEquals(Copy.goWhyNotHere, base.copy(screen = Screen.POINT).goWhy())
        assertEquals(Copy.goWhyLine, base.copy(screen = Screen.RUN, run = RunView(phase = RunPhase.LINE)).goWhy())
        assertEquals(Copy.goWhyDone, base.copy(screen = Screen.RUN, run = RunView(phase = RunPhase.FINISHED)).goWhy())
        assertEquals(Copy.goWhyLost, base.copy(screen = Screen.RUN, lost = true).goWhy())
        assertNull(base.copy(screen = Screen.RUN, run = RunView(phase = RunPhase.READY)).goWhy())
        // every short reason is a real reason: never one without the other
        for (s in listOf(Screen.REPLAY, Screen.DEMO, Screen.POINT, Screen.TOUR, Screen.HOME)) assertNotNull(base.copy(screen = s).goReason())
    }

    /** q7-4: in step mode the step key is the same check as Go and the A button: D gear at Ⅰ does not move the car, says "shift to R". */
    @Test
    fun stepInTheWrongGearDoesNotMove() {
        lessonRun(3)
        val r = YdController.run
        if (!r.stepMode) YdController.onEvent(YdEvent.StepMode)
        assertTrue(YdController.run.stepMode)
        YdController.onEvent(YdEvent.GearSet(Gear.D))
        assertEquals(Gear.D, YdController.run.sim.gear)
        val before = pose()
        YdController.onEvent(YdEvent.StepOnce)
        YdController.debugAdvance(3.0)
        YdController.onEvent(YdEvent.StepOnce)
        YdController.debugAdvance(3.0)
        val after = pose()
        assertEquals(before.first, after.first, 1e-6)
        assertEquals(before.second, after.second, 1e-6)
        assertTrue(YdController.run.phase != RunPhase.FINISHED)
        val note = YdController.state.value.goNote
        assertNotNull(note)
        assertEquals(String.format(Copy.shiftFirst.zh, "R"), note!!.zh)
        assertEquals(String.format(Copy.shiftFirst.en, "R"), YdController.state.value.goWhy()!!.en)
        // in R the same key steps 0.3 m
        YdController.onEvent(YdEvent.GearSet(Gear.R))
        YdController.onEvent(YdEvent.StepOnce)
        YdController.debugAdvance(4.0)
        val moved = pose()
        val d = kotlin.math.hypot(moved.first - before.first, moved.second - before.second)
        assertTrue("stepped $d m", d > 0.2 && d < 0.45)
    }

    /** q7-7 / D44: half-way, Restart run and Home ask first; Cancel keeps the run as it was; Yes does it. */
    @Test
    fun restartAndHomeAskHalfWay() {
        lessonRun(6)
        // before you move off nothing is lost: no question
        YdController.onEvent(YdEvent.Pause)
        YdController.onEvent(YdEvent.Restart)
        assertEquals(Confirm.NONE, YdController.state.value.confirm)
        assertEquals(Screen.RUN, YdController.state.value.screen)
        // drive a little
        YdController.onEvent(YdEvent.GearSet(Gear.R))
        YdController.onEvent(YdEvent.Go(true))
        YdController.debugAdvance(4.0)
        assertTrue(YdController.run.started)
        val at = pose()
        YdController.onEvent(YdEvent.Pause)
        YdController.onEvent(YdEvent.Restart)
        assertEquals(Confirm.RESTART, YdController.state.value.confirm)
        assertTrue(YdController.state.value.paused)
        assertEquals(at, pose())
        YdController.onEvent(YdEvent.CancelConfirm)
        assertEquals(Confirm.NONE, YdController.state.value.confirm)
        assertTrue("still paused after cancel on the pause page", YdController.state.value.paused)
        assertEquals(at, pose())
        YdController.onEvent(YdEvent.Home)
        assertEquals(Confirm.HOME, YdController.state.value.confirm)
        assertEquals(Screen.RUN, YdController.state.value.screen)
        YdController.onEvent(YdEvent.Restart)
        assertEquals(Confirm.RESTART, YdController.state.value.confirm)
        YdController.onEvent(YdEvent.ConfirmRestart)
        var waited = 0
        while (YdController.run.started && waited++ < 200) Thread.sleep(20)
        assertFalse(YdController.run.started)
        assertFalse(YdController.state.value.paused)
        assertEquals(Confirm.NONE, YdController.state.value.confirm)
        // and Home with yes
        YdController.onEvent(YdEvent.GearSet(Gear.R))
        YdController.onEvent(YdEvent.Go(true))
        YdController.debugAdvance(4.0)
        YdController.onEvent(YdEvent.Pause)
        YdController.onEvent(YdEvent.Home)
        assertEquals(Confirm.HOME, YdController.state.value.confirm)
        YdController.onEvent(YdEvent.ConfirmHome)
        assertEquals(Screen.HOME, YdController.state.value.screen)
        assertEquals(Confirm.NONE, YdController.state.value.confirm)
    }

    /** P2's Restart run at a line (not paused): asks on the pause page; Cancel goes back to the line, not to the pause page. */
    @Test
    fun restartAtALineAsksAndCancelGoesBack() {
        lessonRun(6)
        YdController.onEvent(YdEvent.GearSet(Gear.R))
        YdController.onEvent(YdEvent.Go(true))
        YdController.debugAdvance(4.0)
        assertFalse(YdController.state.value.paused)
        YdController.onEvent(YdEvent.Restart)
        assertEquals(Confirm.RESTART, YdController.state.value.confirm)
        assertTrue(YdController.state.value.paused)
        assertFalse("the car stops while you decide", YdController.run.sim.go)
        YdController.onEvent(YdEvent.CancelConfirm)
        assertFalse(YdController.state.value.paused)
        assertEquals(Confirm.NONE, YdController.state.value.confirm)
    }
}
