package io.embodit.yarddrive.domain

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * All sounds are synthesized (design audio): idle hum, tyre crunch, wheel tick every 90°, full-lock clunk, cue tick (an
 * octave above the wheel tick), gear clack, refused beep, line buzz, fail tone, pass ding, the 2 s mid-stop beep.
 * Pure functions so the JVM test renders them offline. Profile B20: at the default volumes (100 %) every one-shot peaks
 * between -3 and -1 dBFS; the two loops stay well below (D15, a steady sound is kept modest).
 */
object Synth {
    const val RATE = 22050

    /** The three snap stops of every volume slider, percent (profile B20). */
    val STOPS = intArrayOf(33, 66, 100)

    /** A slider value (0..100) to a percent: within 6 of a stop it snaps there. */
    fun snap(v: Float): Int {
        val p = Math.round(v).coerceIn(0, 100)
        return STOPS.firstOrNull { abs(it - p) <= 6 } ?: p
    }

    /** Scales [x] so its peak is [db] dBFS. */
    private fun norm(x: FloatArray, db: Double): FloatArray {
        val k = (Math.pow(10.0, db / 20) / max(1e-6f, peak(x))).toFloat()
        return FloatArray(x.size) { x[it] * k }
    }

    private fun pcm(seconds: Double, gen: (Double) -> Double): FloatArray {
        val n = (RATE * seconds).toInt()
        return FloatArray(n) { i -> gen(i / RATE.toDouble()).coerceIn(-1.0, 1.0).toFloat() }
    }

    private fun env(t: Double, a: Double, d: Double) = if (t < a) t / a else exp(-(t - a) * d)

    val wheelTick by lazy { norm(pcm(0.05) { t -> sin(2 * PI * 1200 * t) * env(t, 0.002, 90.0) * 0.5 }, -3.0) }
    val cueTick by lazy { norm(pcm(0.08) { t -> sin(2 * PI * 2400 * t) * env(t, 0.002, 60.0) * 0.6 }, -1.5) }
    val lockClunk by lazy { norm(pcm(0.12) { t -> (sin(2 * PI * 140 * t) * 0.7 + sin(2 * PI * 420 * t) * 0.2) * env(t, 0.003, 35.0) }, -1.5) }
    val gearClack by lazy { norm(pcm(0.10) { t -> (sin(2 * PI * 900 * t) * 0.4 + sin(2 * PI * 260 * t) * 0.4) * env(t, 0.002, 45.0) }, -3.0) }
    val refused by lazy { norm(pcm(0.18) { t -> sin(2 * PI * 330 * t) * env(t, 0.005, 12.0) * 0.6 }, -3.0) }
    val lineBuzz by lazy { norm(pcm(0.35) { t -> (if (sin(2 * PI * 220 * t) >= 0) 0.45 else -0.45) * env(t, 0.005, 6.0) }, -1.5) }
    val failTone by lazy { norm(pcm(0.6) { t -> (sin(2 * PI * 196 * t) * 0.6 + sin(2 * PI * 147 * t) * 0.3) * env(t, 0.02, 4.0) }, -1.0) }
    val passDing by lazy { norm(pcm(0.8) { t -> (sin(2 * PI * 1318.5 * t) * 0.55 + sin(2 * PI * 1975.5 * t) * 0.25) * exp(-t * 5.0) }, -1.0) }
    val stopBeep by lazy { norm(pcm(0.25) { t -> sin(2 * PI * 880 * t) * env(t, 0.005, 10.0) * 0.6 }, -1.5) }

    /** 2 s idle loop: a low engine murmur (very quiet by default, design: 怠速低鸣). Seamless: whole periods only. */
    val idle by lazy {
        val f0 = 32.0
        norm(pcm(2.0) { t -> sin(2 * PI * f0 * t) * 0.5 + sin(2 * PI * f0 * 2 * t) * 0.25 + sin(2 * PI * f0 * 3 * t) * 0.1 }, -16.0)
    }

    /** 2 s tyre crunch loop (filtered noise), only while moving. */
    val tyres by lazy {
        val r = Random(7)
        var y = 0.0
        val raw = pcm(2.0) { _ ->
            y = y * 0.92 + (r.nextDouble() * 2 - 1) * 0.08
            y * 1.6
        }
        // fade the loop ends into each other
        val n = raw.size
        val f = 600
        for (i in 0 until f) {
            val k = i / f.toFloat()
            raw[i] = raw[i] * k + raw[n - f + i] * (1 - k)
        }
        norm(raw.copyOf(n - f), -12.0)
    }

    val all: Map<String, FloatArray>
        get() = linkedMapOf(
            "wheel_tick" to wheelTick, "cue_tick" to cueTick, "lock_clunk" to lockClunk, "gear_clack" to gearClack, "refused" to refused,
            "line_buzz" to lineBuzz, "fail" to failTone, "pass" to passDing, "stop_beep" to stopBeep, "idle" to idle, "tyres" to tyres,
        )

    fun peak(x: FloatArray, gain: Float = 1f): Float = x.fold(0f) { m, v -> max(m, abs(v * gain)) }

    fun rms(x: FloatArray, gain: Float = 1f): Float = sqrt(x.fold(0.0) { s, v -> s + (v * gain).toDouble() * (v * gain) } / x.size).toFloat()

    fun dbfs(v: Float): Double = 20 * kotlin.math.log10(max(1e-9f, v).toDouble())

    /** 16-bit PCM WAV bytes of [x] at [gain]. */
    fun wav(x: FloatArray, gain: Float): ByteArray {
        val n = x.size
        val out = java.io.ByteArrayOutputStream(44 + n * 2)
        fun i32(v: Int) { out.write(v and 255); out.write((v shr 8) and 255); out.write((v shr 16) and 255); out.write((v shr 24) and 255) }
        fun i16(v: Int) { out.write(v and 255); out.write((v shr 8) and 255) }
        out.write("RIFF".toByteArray()); i32(36 + n * 2); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(RATE); i32(RATE * 2); i16(2); i16(16)
        out.write("data".toByteArray()); i32(n * 2)
        for (v in x) i16((v * gain * 32767).toInt().coerceIn(-32768, 32767))
        return out.toByteArray()
    }
}
