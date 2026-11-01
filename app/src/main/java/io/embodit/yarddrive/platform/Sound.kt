package io.embodit.yarddrive.platform

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.embodit.yarddrive.domain.Synth

/**
 * Playback of the synthesized sounds (design audio): one-shots on static AudioTracks, two loops (idle, tyres) whose
 * volume follows the car. Mute and the three master levels come from the settings; muting stops everything at once (D15).
 */
class Sound {
    var muted = false
        set(v) {
            field = v
            applyLoops()
        }
    var level = 1
        set(v) {
            field = v.coerceIn(0, 2)
            applyLoops()
        }
    private val gain: Float get() = Synth.LEVELS[level]
    private var idle: AudioTrack? = null
    private var tyres: AudioTrack? = null
    private var idleOn = false
    private var tyresOn = false

    private fun track(data: FloatArray, loop: Boolean): AudioTrack? =
        runCatching {
            val pcm = ShortArray(data.size) { (data[it] * 32767).toInt().coerceIn(-32768, 32767).toShort() }
            val t =
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(Synth.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
            t.write(pcm, 0, pcm.size)
            if (loop) t.setLoopPoints(0, pcm.size, -1)
            t
        }.onFailure { YdLog.w("SOUND track failed: $it") }.getOrNull()

    private fun shot(data: FloatArray) {
        if (muted) return
        val t = track(data, false) ?: return
        runCatching {
            t.setVolume(gain)
            t.setNotificationMarkerPosition(data.size)
            t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack) { runCatching { track.release() } }
                override fun onPeriodicNotification(track: AudioTrack) {}
            })
            t.play()
        }
    }

    fun wheelTick() = shot(Synth.wheelTick)
    fun cue() = shot(Synth.cueTick)
    fun lock() = shot(Synth.lockClunk)
    fun gear() = shot(Synth.gearClack)
    fun refused() = shot(Synth.refused)
    fun line() = shot(Synth.lineBuzz)
    fun fail() = shot(Synth.failTone)
    fun pass() = shot(Synth.passDing)
    fun stopBeep() = shot(Synth.stopBeep)

    /** The engine idles while you sit in the car; tyres only while moving. */
    fun setLoops(idleOn: Boolean, tyresOn: Boolean) {
        if (this.idleOn == idleOn && this.tyresOn == tyresOn) return
        this.idleOn = idleOn
        this.tyresOn = tyresOn
        applyLoops()
    }

    private fun applyLoops() {
        fun drive(t: AudioTrack?, on: Boolean, make: () -> AudioTrack?, k: Float): AudioTrack? {
            if (!on || muted) {
                if (t != null) runCatching { t.pause() }
                return t
            }
            val tr = t ?: make() ?: return null
            runCatching {
                tr.setVolume(gain * k)
                if (tr.playState != AudioTrack.PLAYSTATE_PLAYING) tr.play()
            }
            return tr
        }
        idle = drive(idle, idleOn, { track(Synth.idle, true) }, 0.6f)
        tyres = drive(tyres, tyresOn, { track(Synth.tyres, true) }, 0.5f)
    }

    fun release() {
        listOf(idle, tyres).forEach { t -> t?.let { runCatching { it.stop(); it.release() } } }
        idle = null
        tyres = null
        idleOn = false
        tyresOn = false
    }
}
