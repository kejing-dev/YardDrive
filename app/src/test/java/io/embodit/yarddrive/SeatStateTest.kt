package io.embodit.yarddrive

import io.embodit.yarddrive.domain.Copy
import io.embodit.yarddrive.ui.Screen
import io.embodit.yarddrive.ui.YdController
import io.embodit.yarddrive.ui.YdEvent
import io.embodit.yarddrive.ui.YdUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R4: no head pose: P1 must not go away for a flow that cannot run, and S20 is always seen. Through the real events. */
class SeatStateTest {
    @After
    fun clean() {
        YdController.onPlace = null
        YdController.headReady = null
        YdController.placingCancelled()
        YdController.debugSet { YdUiState() }
    }

    @Test
    fun s20IsVisibleEvenDuringTheFlow() {
        assertTrue(YdUiState(screen = Screen.UNAVAILABLE, reseating = true).p1)
        assertTrue(!YdUiState(screen = Screen.HOME, reseating = true).p1)
        assertTrue(YdUiState(screen = Screen.HOME).p1)
    }

    @Test
    fun noHeadPoseKeepsP1AndSaysWhy() {
        var started = 0
        YdController.debugSet { it.copy(screen = Screen.HOME) }
        YdController.onPlace = { started++ }
        YdController.headReady = { false }
        YdController.onEvent(YdEvent.Reseat)
        assertEquals(0, started)
        assertTrue(!YdController.state.value.reseating)
        assertTrue(YdController.state.value.p1)
        assertEquals(Copy.seatNoHead, YdController.state.value.seatNote)
        YdController.onEvent(YdEvent.Seated())
        assertEquals(0, started)
        assertEquals(Screen.HOME, YdController.state.value.screen)
    }

    @Test
    fun blockedEndsTheFlow() {
        YdController.debugSet { it.copy(screen = Screen.HOME) }
        YdController.onPlace = {}
        YdController.headReady = { true }
        YdController.onEvent(YdEvent.Reseat)
        assertTrue(YdController.state.value.reseating)
        assertTrue(!YdController.state.value.p1)
        YdController.onEvent(YdEvent.Blocked("test"))
        assertEquals(Screen.UNAVAILABLE, YdController.state.value.screen)
        assertTrue(!YdController.state.value.reseating)
        assertTrue(YdController.state.value.p1)
    }

    @Test
    fun lostTrackingSaysPressAgain() {
        YdController.debugSet { it.copy(screen = Screen.HOME) }
        YdController.onPlace = {}
        YdController.headReady = { true }
        YdController.onEvent(YdEvent.Reseat)
        YdController.placingCancelled(lost = true)
        assertTrue(YdController.state.value.p1)
        assertEquals(Copy.seatLost, YdController.state.value.seatNote)
    }
}
