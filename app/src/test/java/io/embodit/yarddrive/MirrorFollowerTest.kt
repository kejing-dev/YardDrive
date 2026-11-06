package io.embodit.yarddrive

import io.embodit.yarddrive.domain.CarModel
import io.embodit.yarddrive.domain.FacePanel
import io.embodit.yarddrive.domain.LookPoints
import io.embodit.yarddrive.domain.MirrorFollower
import io.embodit.yarddrive.domain.Mirrors
import io.embodit.yarddrive.domain.Seat
import io.embodit.yarddrive.domain.V3
import io.embodit.yarddrive.domain.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * critic5-B1, profile D35: a mirror slider turns the face and its frame in the same step, from one normal. The slot's real
 * rotation is FacePanel.facing + PanelFacing.rotation, what Slot.turnTo does; the test seat is turned, so the Stage is not the cabin.
 */
class MirrorFollowerTest {
    private val seat = Seat(Vec3(0.3f, 1.5f, -0.2f), 0.7f)
    private val maxPitch = 1.4f // YardScene: rig.world(MIR_*).maxPitch

    private class Seen(val slotZ: Vec3, val slotX: Vec3, val plateZ: V3, val barZ: V3?)

    private fun followed(seen: MutableMap<Mirrors.Id, Seen>) = MirrorFollower { spec, n ->
        val pos = seat.toStage(spec.pos.f())
        val f = FacePanel.facing(pos, seat.toStage(spec.pos.f() + n.f()), null, maxPitch)
        val bar = if (spec.id == Mirrors.Id.I) null else CarModel.mirrorStrut(spec, n).z
        seen[spec.id] = Seen(f.rotation.rotate(Vec3(0f, 0f, 1f)), f.rotation.rotate(Vec3(1f, 0f, 0f)), CarModel.framePlate(spec, n).z, bar)
    }

    private fun assertSame(a: Vec3, b: Vec3, what: String) {
        assertTrue("$what $a vs $b", (a - b).length < 2e-3f)
    }

    /** One step per slider value, each read at once: all three mirrors, face and frame on the same normal. */
    @Test
    fun aSliderMoveTurnsFaceAndFrameInTheSameStep() {
        val seen = HashMap<Mirrors.Id, Seen>()
        val follower = followed(seen)
        for (out in listOf(5, 6, 0, 15, 7)) for (pitch in listOf(-16, -15, 0, -20, -9)) {
            seen.clear()
            follower.step(out, pitch)
            assertEquals("out $out pitch $pitch", 3, seen.size)
            for (spec in Mirrors.all) {
                val n = Mirrors.normal(spec, Mirrors.aim(spec, out.toDouble(), pitch.toDouble()))
                val want = seat.toStageDir(n.f())
                val s = seen.getValue(spec.id)
                assertSame(s.slotZ, want, "${spec.id} face at $out, $pitch")
                assertEquals("${spec.id} face has no roll", 0f, s.slotX.y, 2e-3f)
                assertSame(seat.toStageDir(s.plateZ.f()), want, "${spec.id} plate at $out, $pitch")
                s.barZ?.let { assertSame(seat.toStageDir(it.f()), want, "${spec.id} bar at $out, $pitch") }
            }
        }
    }

    @Test
    fun onlyWhenTheAimChangesOrAfterAReset() {
        var calls = 0
        val follower = MirrorFollower { _, _ -> calls++ }
        follower.step(5, -16)
        assertEquals(3, calls)
        follower.step(5, -16)
        assertEquals(3, calls)
        follower.step(6, -16)
        assertEquals(6, calls)
        follower.reset()
        follower.step(6, -16)
        assertEquals(9, calls)
    }

    /** R2: the look-points are warmed up off the main thread; whoever asks meanwhile waits for the same result, never an empty list. */
    @Test
    fun lookPointsAskedFromManyThreadsAreOneFullList() {
        val got = arrayOfNulls<Any>(8)
        val threads = (0 until 8).map { i -> Thread { got[i] = LookPoints.all } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        for (g in got) assertSame(LookPoints.all, g)
        assertEquals(12, LookPoints.all.size)
    }
}
