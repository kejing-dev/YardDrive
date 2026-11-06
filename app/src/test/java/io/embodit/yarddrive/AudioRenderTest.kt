package io.embodit.yarddrive

import io.embodit.yarddrive.domain.Synth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * profile B20: at the default volumes (100 %) every one-shot peaks between -3 and -1 dBFS; the loops stay modest (D15). The
 * WAVs and a peak / RMS table land in app/build/audio (copied to apps/yard-drive/qa/audio for QA).
 */
class AudioRenderTest {
    private val loops = setOf("idle", "tyres")

    @Test
    fun renderAndCheckPeaks() {
        val dir = File("build/audio").apply { mkdirs() }
        val sb = StringBuilder("| sound | group | peak dBFS at 100 % | RMS dBFS at 100 % | peak at 66 % | peak at 33 % |\n|---|---|---|---|---|---|\n")
        val car = setOf("wheel_tick", "lock_clunk", "gear_clack", "idle", "tyres")
        for ((name, x) in Synth.all) {
            val p = Synth.dbfs(Synth.peak(x))
            if (name in loops) assertTrue("$name peak $p", p <= -10.0 && p >= -20.0)
            else assertTrue("$name peak $p", p <= -0.99 && p >= -3.01)
            File(dir, "$name.wav").writeBytes(Synth.wav(x, 1f))
            sb.append(String.format(Locale.US, "| %s | %s | %.1f | %.1f | %.1f | %.1f |\n", name, if (name in car) "car" else "cue", p, Synth.dbfs(Synth.rms(x)), Synth.dbfs(Synth.peak(x, 0.66f)), Synth.dbfs(Synth.peak(x, 0.33f))))
        }
        File(dir, "levels.md").writeText(sb.toString())
    }

    @Test
    fun slidersSnapToThreeStops() {
        for (s in Synth.STOPS) for (d in -6..6) assertEquals(s, Synth.snap((s + d).toFloat().coerceIn(0f, 100f)))
        assertEquals(50, Synth.snap(50f))
        assertEquals(0, Synth.snap(0f))
    }
}
