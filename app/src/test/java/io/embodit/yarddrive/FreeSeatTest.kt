package io.embodit.yarddrive

import io.embodit.yarddrive.domain.Autopilot
import io.embodit.yarddrive.domain.Car
import io.embodit.yarddrive.domain.Free
import io.embodit.yarddrive.domain.Mode
import io.embodit.yarddrive.domain.RunPhase
import io.embodit.yarddrive.domain.Settings
import io.embodit.yarddrive.ui.Screen
import io.embodit.yarddrive.ui.YdController
import io.embodit.yarddrive.ui.YdEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Profile D30: free practice level 3's random seat goes into this run only. Through the real controller events. */
@OptIn(ExperimentalCoroutinesApi::class)
class FreeSeatTest {
    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    /**
     * k4-1: what the design Slider does: its onValueChange is the closure of the first composition. A change made there must
     * not put back what happened since. Every slider's field, against every switch and the mute.
     */
    @Test
    fun aSliderChangeKeepsWhatChangedSince() {
        val before = YdController.state.value.settings
        val sliders: List<(Settings) -> Settings> = listOf(
            { s -> s.copy(speed = 1.1) }, { s -> s.copy(mirrorOut = 12) }, { s -> s.copy(mirrorPitch = -3) },
            { s -> s.copy(master = 40) }, { s -> s.copy(carVol = 40) }, { s -> s.copy(cueVol = 40) },
        )
        for (slider in sliders) {
            YdController.onEvent(YdEvent.SetSettings { s -> s.copy(hints = !before.hints, topView = !before.topView, poles = !before.poles, comfort = !before.comfort, strict = !before.strict) })
            YdController.onEvent(YdEvent.Mute)
            val muted = YdController.state.value.settings.muted
            YdController.onEvent(YdEvent.SetSettings(slider))
            val now = YdController.state.value.settings
            assertEquals(!before.hints, now.hints)
            assertEquals(!before.topView, now.topView)
            assertEquals(!before.poles, now.poles)
            assertEquals(!before.comfort, now.comfort)
            assertEquals(!before.strict, now.strict)
            assertEquals(muted, now.muted)
            assertEquals(slider(now), now)
        }
        YdController.onEvent(YdEvent.SetSettings { before })
    }

    @Test
    fun aRunLeavesTheSettingsAsTheyWere() {
        val before = YdController.state.value.settings
        // a seed whose run gets a different seat from the one in the settings
        val seed = (1..200).first { s -> Free.start(s, 3).seat.let { it != null && it != before.seat } }
        val start = Free.start(seed, 3)
        YdController.onEvent(YdEvent.FreeLevel(3, seed))
        var waited = 0
        while (YdController.state.value.screen != Screen.RUN && waited++ < 400) Thread.sleep(50)
        assertEquals(Screen.RUN, YdController.state.value.screen)
        val cfg = YdController.run.cfg
        assertEquals(Mode.FREE, cfg.mode)
        // the run has its own seat, and the road follows it ...
        assertEquals(start.seat, cfg.seat)
        assertEquals(Car.EYE_HEIGHTS[start.seat!!], cfg.eyeH(before), 1e-9)
        assertNotEquals(before.eyeH, cfg.eyeH(before), 1e-9)
        assertTrue(YdController.state.value.run.seatHint != null)
        // ... the settings do not, during or after the run
        assertEquals(before, YdController.state.value.settings)
        YdController.debugAutopilotSet(Autopilot())
        YdController.debugAdvance(150.0)
        assertEquals(RunPhase.FINISHED, YdController.run.phase)
        assertEquals(before, YdController.state.value.settings)
        // and back home you are in your own seat again
        YdController.onEvent(YdEvent.Home)
        assertNull(YdController.run.cfg.seat)
        assertEquals(before, YdController.state.value.settings)
        assertEquals(before.eyeH, YdController.run.cfg.eyeH(YdController.state.value.settings), 1e-9)
    }

    @Test
    fun yourOwnSeatIsNoOverride() {
        val s = YdController.state.value.settings
        val seed = (1..200).first { Free.start(it, 3).seat == s.seat }
        assertNull(Free.config(s, Free.start(seed, 3), false, 3.52).seat)
        assertNull(Free.config(s, Free.start(seed, 1), false, 3.52).seat)
    }
}
