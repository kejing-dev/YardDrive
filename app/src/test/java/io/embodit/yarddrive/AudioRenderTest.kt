package io.embodit.yarddrive

import io.embodit.yarddrive.domain.Synth
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * profile B20: every synthesized sound rendered offline at the high master level, peak <= -1 dBFS; the WAVs and a
 * peak / RMS table land in app/build/audio (tools copy them to apps/yard-drive/qa/audio for QA).
 */
class AudioRenderTest {
    @Test
    fun renderAndCheckPeaks() {
        val dir = File("build/audio").apply { mkdirs() }
        val sb = StringBuilder("| sound | level | peak dBFS | RMS dBFS |\n|---|---|---|---|\n")
        for ((name, x) in Synth.all) {
            for ((li, g) in Synth.LEVELS.withIndex()) {
                val loopK = if (name == "idle") 0.6f else if (name == "tyres") 0.5f else 1f
                val gain = g * loopK
                val peak = Synth.peak(x, gain)
                assertTrue("$name level $li peak ${Synth.dbfs(peak)}", Synth.dbfs(peak) <= -1.0)
                if (li == 2) File(dir, "$name.wav").writeBytes(Synth.wav(x, gain))
                sb.append(String.format(Locale.US, "| %s | %s | %.1f | %.1f |\n", name, listOf("low", "mid", "high")[li], Synth.dbfs(peak), Synth.dbfs(Synth.rms(x, gain))))
            }
        }
        File(dir, "levels.md").writeText(sb.toString())
    }
}
