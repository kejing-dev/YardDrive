package io.embodit.yarddrive

import io.embodit.yarddrive.domain.CarModel
import io.embodit.yarddrive.domain.Mirrors
import io.embodit.yarddrive.domain.PanelCone
import io.embodit.yarddrive.domain.Seat
import io.embodit.yarddrive.domain.V3
import io.embodit.yarddrive.domain.Vec3
import io.embodit.yarddrive.ui.scene.PanelSizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** B1, design D20: the wheel against the bottom edge of P1 and of P2 at its R height (D height is far above it). */
class PanelConeTest {
    private val panels = listOf(PanelCone.p1(), PanelCone.p2(Seat.P2_PITCH_R))

    /** Profile D35: the default and the four corners of the settings' mirror sliders (out 0...15, pitch -20...0). */
    private val AIMS = listOf(5.0 to -16.0, 0.0 to -20.0, 0.0 to 0.0, 15.0 to -20.0, 15.0 to 0.0)

    /** The design's number, now true of what is built: the top of the wheel as seen from the eye. */
    @Test
    fun wheelTopIsTheDesignAngle() {
        assertEquals(-29.8, PanelCone.wheelTopDeg(), 0.6)
    }

    @Test
    fun clearAtTheSeatedEye() {
        for (p in panels) assertTrue("clear ${PanelCone.clearDeg(Vec3.ZERO, p)}", PanelCone.clearDeg(Vec3.ZERO, p) >= 3.0)
    }

    /** The critic's case: you slump, the head is 5 cm lower than when you were seated. */
    @Test
    fun clearWithTheHead5cmLower() {
        val eye = Vec3(0f, -0.05f, 0f)
        for (p in panels) {
            val c = PanelCone.clearDeg(eye, p)
            assertTrue("clear $c", c >= 3.0)
            val f = PanelCone.Fade()
            repeat(30) { f.step(c, 0.05f) }
            assertEquals(1f, f.opacity, 1e-3f)
        }
    }

    /** Every head position in a generous box: either 3° clear, or the wheel really is at 25 %. */
    @Test
    fun clearOrFadedEverywhere() {
        for (dx in -20..20 step 10) for (dy in -40..10 step 5) for (dz in -20..20 step 10) {
            val eye = Vec3(dx / 100f, dy / 100f, dz / 100f)
            for (p in panels) {
                val c = PanelCone.clearDeg(eye, p)
                val f = PanelCone.Fade()
                repeat(30) { f.step(c, 0.05f) }
                assertTrue("eye $eye clear $c opacity ${f.opacity}", c >= PanelCone.NEAR_DEG || f.opacity <= PanelCone.FADED + 1e-3f)
            }
        }
    }

    /** Profile D34: the HUB stays at the design's angle, not below -40°, and the wheel does not cover it. */
    @Test
    fun hubAngleAndNotCovered() {
        val hub = Seat.hubLocal()
        val deg = Math.toDegrees(kotlin.math.atan2(hub.y.toDouble(), -hub.z.toDouble()))
        assertTrue("hub $deg", deg >= -40.0 && deg <= -35.0)
        val rPanel = 0.0384f
        for (eye in listOf(Vec3.ZERO, Vec3(0f, -0.05f, 0f))) {
            val toHub = hub - eye
            val dist = toHub.length
            for (p in PanelCone.rim) {
                val d = p - eye
                val along = d.dot(toHub.normalized())
                if (along >= dist) continue // behind the panel
                val atHub = d * (dist / along) - toHub
                assertTrue("eye $eye rim $p", atHub.length > rPanel)
            }
        }
    }

    private fun v(p: Vec3) = V3(p.x.toDouble(), p.y.toDouble(), p.z.toDouble())

    private fun inBox(p: Vec3, b: CarModel.Box): Boolean {
        val a = b.min
        val c = b.max
        return p.x > a.x && p.x < c.x && p.y > a.y && p.y < c.y && p.z > a.z && p.z < c.z
    }

    private fun corners(r: PanelCone.Rect) = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f).map { (u, v) -> r.c + r.right * (u * r.halfW) + r.up * (v * r.halfH) }

    /** Does the open segment eye (origin) -> [p] pass through the inside of [b]? Exact (slabs), not sampled: the door skin is 1 cm thick. */
    private fun crosses(p: Vec3, b: CarModel.Box): Boolean {
        var t0 = 0.0
        var t1 = 1.0
        for ((d, lo, hi) in listOf(Triple(p.x.toDouble(), b.min.x, b.max.x), Triple(p.y.toDouble(), b.min.y, b.max.y), Triple(p.z.toDouble(), b.min.z, b.max.z))) {
            if (kotlin.math.abs(d) < 1e-9) {
                if (0.0 <= lo || 0.0 >= hi) return false
                continue
            }
            val a = lo / d
            val c = hi / d
            t0 = maxOf(t0, minOf(a, c))
            t1 = minOf(t1, maxOf(a, c))
            if (t0 >= t1) return false
        }
        return true
    }

    /** A mirror face as the scene builds it: at spec.pos, turned to n = normalize(toEye + aim); u = up x n, v = n x u. Default aim: out 5 deg, pitch -16 deg. */
    private class Face(val u: V3, val v: V3, val c: V3, val hw: Double, val hh: Double) {
        fun at(su: Double, sv: Double): Vec3 = (c + u * (su * hw) + v * (sv * hh)).f()
    }

    private fun face(spec: Mirrors.Spec, hw: Double = spec.wM / 2, hh: Double = spec.hM / 2, out: Double = 5.0, pitch: Double = -16.0): Face {
        val n = Mirrors.normal(spec, Mirrors.aim(spec, out, pitch))
        val u = V3(0.0, 1.0, 0.0).cross(n).normalized()
        return Face(u, n.cross(u), spec.pos, hw, hh)
    }

    /** The panel on the face is 225 x 138 dp (325 x 88 inside) at scale 1: 0.4 mm over the design's 18 x 11 cm, and that is what is tested. */
    private fun panelFace(spec: Mirrors.Spec, out: Double, pitch: Double): Face {
        val w = (if (spec.id == Mirrors.Id.I) PanelSizes.MIRI_W else PanelSizes.MIR_W) * 0.0008 / 2
        val h = (if (spec.id == Mirrors.Id.I) PanelSizes.MIRI_H else PanelSizes.MIR_H) * 0.0008 / 2
        return face(spec, w, h, out, pitch)
    }

    /**
     * Profile D35: every panel of the pool, turned to the eye as it is in the car: its real corners (and a grid on its face)
     * are in no box of [CarModel], and the lines from the eye to them cross none. The bug was MRD-L's outer end in the door.
     */
    @Test
    fun d35_noPanelInOrBehindTheCar() {
        val mrd = 200f * 0.0008f * 0.6f / 2 to 64f * 0.0008f * 0.6f / 2
        val mrdR = PanelSizes.MRD_R_W * 0.0008f * PanelSizes.MRD_R_SCALE / 2 to PanelSizes.MRD_R_H * 0.0008f * PanelSizes.MRD_R_SCALE / 2
        val hub = 160f * 0.0008f * 0.6f / 2
        val hint = 620f * 0.0008f * Seat.HINT_SCALE / 2 to 72f * 0.0008f * Seat.HINT_SCALE / 2
        val panels = linkedMapOf(
            "P1" to PanelCone.p1(), "P2 R" to PanelCone.p2(Seat.P2_PITCH_R), "P2 D" to PanelCone.p2(Seat.P2_PITCH_D),
            "HUB" to PanelCone.Rect(Seat.hubLocal(), hub, hub),
            "MRD-L" to PanelCone.Rect(Seat.MRD_L, mrd.first, mrd.second), "MRD-R" to PanelCone.Rect(Seat.MRD_R, mrdR.first, mrdR.second),
        )
        var look = -Seat.HINT_MAX_DEG
        while (look <= Seat.HINT_MAX_DEG) {
            panels["HINT $look"] = PanelCone.Rect(Seat.hintDir(look), hint.first, hint.second)
            look += 5.0
        }
        for ((name, r) in panels) {
            for (corner in corners(r) + r.c) {
                for (b in CarModel.boxes) {
                    assertTrue("$name corner $corner in ${b.name}", !inBox(corner, b))
                    assertTrue("$name: eye to $corner crosses ${b.name}", !crosses(corner, b))
                }
                val p = v(corner)
                for ((out, pitch) in AIMS) for (e in CarModel.extrasAt(out, pitch)) {
                    assertTrue("$name corner $corner in ${e.name} at $out, $pitch", !e.contains(p))
                    assertTrue("$name: eye to $corner crosses ${e.name} at $out, $pitch", !e.crossedBy(p))
                }
            }
        }
        // the three mirrors (design model.mirrors.clearanceCheck, U18): corners and a 24 x 16 grid, so 0 % of the face is blocked
        for ((out, pitch) in AIMS) for (spec in Mirrors.all) {
            val extras = CarModel.extrasAt(out, pitch)
            val f = panelFace(spec, out, pitch)
            val points = ArrayList<Vec3>()
            for (su in listOf(-1.0, 1.0)) for (sv in listOf(-1.0, 1.0)) points += f.at(su, sv)
            for (i in 0 until 24) for (j in 0 until 16) points += f.at(-1 + 2 * (i + 0.5) / 24, -1 + 2 * (j + 0.5) / 16)
            for (p in points) {
                for (b in CarModel.boxes) {
                    assertTrue("MIR-${spec.id} at $out, $pitch: point $p in ${b.name}", !inBox(p, b))
                    assertTrue("MIR-${spec.id} at $out, $pitch: eye to $p crosses ${b.name}", !crosses(p, b))
                }
                for (e in extras) {
                    assertTrue("MIR-${spec.id} at $out, $pitch: point $p in ${e.name}", !e.contains(v(p)))
                    assertTrue("MIR-${spec.id} at $out, $pitch: eye to $p crosses ${e.name}", !e.crossedBy(v(p)))
                }
            }
        }
    }

    /**
     * The frame plates are decoration, but they are mesh: from the eye their front faces are clear of everything else, with
     * one exception the user accepted at gate B (design model.mirrors.clearanceCheck.accepted): the inner lower tip of the
     * right frame is behind the sill, door and skin only, by less than 0.8 cm at x 1.23, and less than 1 % of the face
     * (measured: 0.71 cm, 0.75 %, the lower inner part su < -0.5, sv < -0.9; the design worked out 0.75 cm, 0.8 % with the plate 4 mm back).
     */
    @Test
    fun d35_framePlates() {
        for (spec in Mirrors.all) {
            val plate = CarModel.extras.first { it.name == "frame${spec.id}" }
            var total = 0
            var hit = 0
            // cell centres, so the count is an area; the edge itself is checked by the tip below
            for (i in 0 until 96) for (j in 0 until 64) {
                val su = -1 + 2.0 * (i + 0.5) / 96
                val sv = -1 + 2.0 * (j + 0.5) / 64
                val p = plate.c + plate.x * (su * plate.hx) + plate.y * (sv * plate.hy) + plate.z * plate.hz * 0.999
                total++
                val boxes = CarModel.boxes.filter { crosses(p.f(), it) || inBox(p.f(), it) }.map { it.name }
                val others = CarModel.extras.filter { it.name != plate.name && (it.crossedBy(p) || it.contains(p)) }.map { it.name }
                if (boxes.isEmpty() && others.isEmpty()) continue
                hit++
                val accepted = spec.id == Mirrors.Id.R && su < -0.4 && sv < -0.8 && others.isEmpty() && boxes.all { it == "sideR" || it == "doorR" }
                assertTrue("frame ${spec.id} ($su, $sv) behind $boxes $others", accepted)
                val y = p.y * 1.23 / p.x
                assertTrue("frame R tip at the skin y $y", y > -0.22 - 0.008)
            }
            assertTrue("frame ${spec.id} $hit / $total behind the car", hit.toDouble() / total < 0.01)
            val tip = plate.c - plate.x * plate.hx - plate.y * plate.hy + plate.z * plate.hz
            if (spec.id == Mirrors.Id.R) assertEquals(-0.2271, tip.y * 1.23 / tip.x, 0.001)
        }
    }

    /**
     * The other mirror aims are NOT what the user accepted at gate B: this table is reported (deviation), not an exception. It
     * only stops it growing or spreading: the tip is the same right inner lower tip, behind the sill and door skin, nothing else.
     */
    @Test
    fun framePlatesAcrossTheSliderRange() {
        // out, pitch -> the right frame's tip below the sill at x 1.23 (cm), the share of the frame's face behind it (%)
        val measured = mapOf((0.0 to -20.0) to (0.59 to 0.52), (0.0 to 0.0) to (0.89 to 1.17), (15.0 to -20.0) to (0.69 to 0.70), (15.0 to 0.0) to (1.01 to 1.38), (5.0 to -16.0) to (0.71 to 0.75))
        for ((aim, table) in measured) {
            val extras = CarModel.extrasAt(aim.first, aim.second)
            for (spec in Mirrors.all) {
                val plate = extras.first { it.name == "frame${spec.id}" }
                var total = 0
                var hit = 0
                for (i in 0 until 96) for (j in 0 until 64) {
                    val su = -1 + 2.0 * (i + 0.5) / 96
                    val sv = -1 + 2.0 * (j + 0.5) / 64
                    val p = plate.c + plate.x * (su * plate.hx) + plate.y * (sv * plate.hy) + plate.z * plate.hz * 0.999
                    total++
                    val boxes = CarModel.boxes.filter { crosses(p.f(), it) || inBox(p.f(), it) }.map { it.name }
                    val others = extras.filter { it.name != plate.name && (it.crossedBy(p) || it.contains(p)) }.map { it.name }
                    if (boxes.isEmpty() && others.isEmpty()) continue
                    hit++
                    assertTrue("frame ${spec.id} at $aim ($su, $sv) behind $boxes $others", spec.id == Mirrors.Id.R && su < 0 && sv < -0.5 && others.isEmpty() && boxes.all { it == "sideR" || it == "doorR" })
                }
                if (spec.id != Mirrors.Id.R) {
                    assertEquals(0, hit)
                    continue
                }
                assertEquals("frame R share at $aim", table.second, 100.0 * hit / total, 0.01)
                val tip = plate.c - plate.x * plate.hx - plate.y * plate.hy + plate.z * plate.hz
                assertEquals("frame R tip at $aim", table.first, (-0.22 - tip.y * 1.23 / tip.x) * 100, 0.01)
            }
        }
    }

    /** S4: the bar from the frame to the shell really reaches both, and from the eye it is all behind the mirror's face. */
    @Test
    fun strutsHideBehindTheMirror() {
        for (spec in listOf(Mirrors.L, Mirrors.R)) {
            for (out in listOf(0.0, 5.0, 15.0)) for (pitch in listOf(-20.0, -16.0, 0.0)) {
            val n = Mirrors.normal(spec, Mirrors.aim(spec, out, pitch))
            val bar = CarModel.mirrorStrut(spec, n)
            val u = V3(0.0, 1.0, 0.0).cross(n).normalized()
            val f = Face(u, n.cross(u), spec.pos, spec.wM / 2, spec.hM / 2)
            for (sx in listOf(-1.0, 1.0)) for (sy in listOf(-1.0, 1.0)) for (sz in listOf(-1.0, 1.0)) {
                val p = bar.c + bar.x * (sx * bar.hx) + bar.y * (sy * bar.hy) + bar.z * (sz * bar.hz)
                val t = spec.pos.dot(n) / p.dot(n)
                assertTrue("${bar.name} corner in front of the mirror", t in 0.0..1.0)
                val on = p * t - spec.pos
                assertTrue("${bar.name} corner $p shows at u ${on.dot(f.u)}", kotlin.math.abs(on.dot(f.u)) < f.hw)
                assertTrue("${bar.name} corner $p shows at v ${on.dot(f.v)}", kotlin.math.abs(on.dot(f.v)) < f.hh)
            }
            val house = CarModel.boxes.first { it.name == "mirHouse${spec.id}" }
            val rear = bar.c - bar.z * bar.hz
            assertTrue("${bar.name} rear $rear in the shell", inBox(rear.f(), house))
            val plate = CarModel.framePlate(spec, n)
            assertTrue("${bar.name} starts in the plate", plate.contains(bar.c + bar.z * (bar.hz - 1e-3)))
            }
        }
    }

    /** The test above would have failed on the housing and mirror of r3 (QA #3 q3-1): MIR-R's inner corners were in the housing. */
    @Test
    fun d35_theOldMirrorFails() {
        val old = Mirrors.R.copy(pos = V3(1.44, -0.20, -0.62))
        val n = Mirrors.normal(old, Mirrors.aim(old, 5.0, -16.0))
        val u = V3(0.0, 1.0, 0.0).cross(n).normalized()
        val f = Face(u, n.cross(u), old.pos, old.wM / 2, old.hM / 2)
        val house = CarModel.boxes.first { it.name == "mirHouseR" }
        val oldHouse = house.copy(a = V3(house.a.x, -0.255, -0.80), b = V3(house.b.x, -0.145, -0.66))
        assertTrue(inBox(f.at(-1.0, -1.0), oldHouse))
    }

    /** The corners the design worked out from the same numbers (clearanceCheck). A difference is reported, not tuned away. */
    @Test
    fun mirrorCornersAreTheDesigns() {
        val design = mapOf(
            Mirrors.L to listOf(V3(-0.784, -0.240, -0.589), V3(-0.618, -0.240, -0.656), V3(-0.616, -0.130, -0.651), V3(-0.782, -0.130, -0.584)),
            Mirrors.R to listOf(V3(1.366, -0.240, -0.672), V3(1.520, -0.240, -0.578), V3(1.514, -0.130, -0.568), V3(1.360, -0.130, -0.662)),
            Mirrors.I to listOf(V3(0.207, 0.085, -0.523), V3(0.456, 0.085, -0.446), V3(0.453, 0.155, -0.437), V3(0.204, 0.155, -0.514)),
        )
        for ((spec, want) in design) {
            val f = face(spec)
            // the design lists su = -1 first on every mirror (mirror_check.py), so on the left its "inner" is the side towards -x
            val got = listOf(f.at(-1.0, -1.0), f.at(1.0, -1.0), f.at(1.0, 1.0), f.at(-1.0, 1.0))
            for (i in 0..3) {
                assertEquals("MIR-${spec.id} corner $i x", want[i].x, got[i].x.toDouble(), 0.0015)
                assertEquals("MIR-${spec.id} corner $i y", want[i].y, got[i].y.toDouble(), 0.0015)
                assertEquals("MIR-${spec.id} corner $i z", want[i].z, got[i].z.toDouble(), 0.0015)
            }
        }
    }

    /** U18: the line to MIR-R's inner lower corner clears the sill line (y -0.22) where it meets the door skin (x 1.23) by 3 mm or more. */
    @Test
    fun rightMirrorClearsTheSill() {
        val p = face(Mirrors.R).at(-1.0, -1.0)
        val y = p.y * 1.23f / p.x
        assertTrue("y at the skin $y", y > -0.22f + 0.003f)
    }

    /** Chant datum and MRD-R, fixed by the design (r4): the mirror moved, the chant did not; MRD-R stays above the D34 limit. */
    @Test
    fun chantDatumAndMrdR() {
        assertEquals(-0.255, io.embodit.yarddrive.domain.Car.MIRROR_EDGE_L.y, 1e-9)
        assertEquals(-0.255, io.embodit.yarddrive.domain.Car.MIRROR_EDGE_R.y, 1e-9)
        assertEquals(-0.255, CarModel.boxes.first { it.name == "mirHouseR" }.a.y, 1e-9)
        val m = Seat.MRD_R
        val deg = Math.toDegrees(kotlin.math.atan2(-m.y.toDouble(), kotlin.math.hypot(m.x.toDouble(), m.z.toDouble())))
        assertEquals(11.8, deg, 0.1)
        assertTrue(deg <= 40.0)
    }

    /** MRD-L in the old spot really was in the door: the test above would have caught it. */
    @Test
    fun d35_theOldSpotFails() {
        val old = PanelCone.Rect(Vec3(-0.42f, -0.26f, -0.60f), 200f * 0.0008f * 0.6f / 2, 64f * 0.0008f * 0.6f / 2)
        assertTrue(corners(old).any { c -> CarModel.boxes.any { inBox(c, it) } })
    }

    /** Slumped far enough the wheel does reach P1: it goes to 25 %, and comes back without flicker. */
    @Test
    fun fadesInTheConeAndComesBack() {
        val low = Vec3(0f, -0.30f, 0.05f)
        val c = PanelCone.clearDeg(low, PanelCone.p1())
        assertTrue("clear $c", c < PanelCone.NEAR_DEG)
        val f = PanelCone.Fade()
        repeat(30) { f.step(c, 0.05f) }
        assertEquals(PanelCone.FADED, f.opacity, 1e-3f)
        // between the two limits it holds
        repeat(30) { f.step((PanelCone.NEAR_DEG + PanelCone.CLEAR_DEG) / 2, 0.05f) }
        assertEquals(PanelCone.FADED, f.opacity, 1e-3f)
        repeat(30) { f.step(PanelCone.clearDeg(Vec3.ZERO, PanelCone.p1()), 0.05f) }
        assertEquals(1f, f.opacity, 1e-3f)
    }
}
