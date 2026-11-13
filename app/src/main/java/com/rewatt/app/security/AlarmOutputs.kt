package com.rewatt.app.security

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive as coroutineActive
import kotlinx.coroutines.launch

enum class AlarmReason { INTRUDER, FIRE }

/**
 * Alarm SOUND on/off: the siren (intruder and fire) and the spoken "You are being recorded".
 * MUTED FOR NOW (testing next to teammates). Set to true to bring the sound back; the alarm screen,
 * pop-ups, vibration, torch strobe and evidence are unaffected either way.
 */
const val ALARM_SOUND_ON = false

/**
 * Physical alarm outputs shared by the intruder and fire alarms: max ALARM-stream volume (restored
 * afterwards), a looping synthesised siren, vibration and a torch strobe. Reference-counted by
 * [AlarmReason], so everything keeps going until every active alarm has been dismissed.
 * All methods must be called on the main thread.
 */
class AlarmOutputs(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    private class TorchControl(val set: (Boolean) -> Unit, val restore: () -> Unit)

    private val active = LinkedHashMap<AlarmReason, TorchControl>()
    private var siren: AudioTrack? = null
    private var strobeJob: Job? = null
    private var savedAlarmVolume: Int? = null

    val anyActive: Boolean get() = active.isNotEmpty()

    /** Volume up, vibration and torch strobe start immediately; the siren starts with [startSiren]. */
    fun begin(reason: AlarmReason, torch: (Boolean) -> Unit, torchRestore: () -> Unit) {
        val first = active.isEmpty()
        active[reason] = TorchControl(torch, torchRestore)
        raiseVolume()
        if (first) startVibration()
        startStrobe()
    }

    fun startSiren() {
        if (!ALARM_SOUND_ON) return // muted for now (see ALARM_SOUND_ON)
        if (siren != null || active.isEmpty()) return
        runCatching {
            val samples = sirenSamples()
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
                .also { track ->
                    track.write(samples, 0, samples.size)
                    track.setLoopPoints(0, samples.size, -1)
                    track.play()
                    siren = track
                }
        }.onFailure { Log.e(TAG, "Siren failed to start", it) }
    }

    /** Stop this reason; outputs only stop (and volume is restored) once no alarm is left. */
    fun end(reason: AlarmReason) {
        val control = active.remove(reason) ?: return
        control.restore()
        if (active.isNotEmpty()) {
            startStrobe() // keep strobing with the remaining alarm's torch
            return
        }
        strobeJob?.cancel()
        strobeJob = null
        siren?.let { track ->
            runCatching { track.stop() }
            track.release()
        }
        siren = null
        vibrator()?.cancel()
        restoreVolume()
    }

    fun raiseVolume() {
        if (!ALARM_SOUND_ON) return // muted: leave the phone's alarm volume alone
        runCatching {
            if (savedAlarmVolume == null) savedAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }.onFailure { Log.w(TAG, "Could not raise alarm volume", it) }
    }

    /** Restore the user's alarm volume unless an alarm is still running. */
    fun restoreVolumeIfIdle() {
        if (active.isEmpty()) restoreVolume()
    }

    private fun restoreVolume() {
        val saved = savedAlarmVolume ?: return
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0) }
        savedAlarmVolume = null
    }

    private fun startStrobe() {
        strobeJob?.cancel()
        val torch = active.values.lastOrNull()?.set ?: return
        strobeJob = scope.launch {
            var on = true
            while (coroutineActive) {
                torch(on)
                on = !on
                delay(STROBE_INTERVAL_MS)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val pattern = VibrationEffect.createWaveform(longArrayOf(0, 700, 300), 0)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        runCatching { vibrator()?.vibrate(pattern, attributes) }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Vibrator::class.java)
        }

    /** One 1.2 s cycle of a 600 ↔ 1400 Hz sweep, slightly clipped for loudness; looped forever. */
    private fun sirenSamples(): ShortArray {
        val count = (SAMPLE_RATE * SWEEP_SECONDS).toInt()
        val out = ShortArray(count)
        var phase = 0.0
        for (i in 0 until count) {
            val t = i.toDouble() / count
            val tri = if (t < 0.5) t * 2 else (1 - t) * 2
            val freq = 600.0 + 800.0 * tri
            phase += 2 * PI * freq / SAMPLE_RATE
            val v = (sin(phase) * 1.4).coerceIn(-1.0, 1.0)
            out[i] = (v * Short.MAX_VALUE * 0.95).toInt().toShort()
        }
        return out
    }

    companion object {
        private const val TAG = "AlarmOutputs"
        private const val SAMPLE_RATE = 22_050
        private const val SWEEP_SECONDS = 1.2
        private const val STROBE_INTERVAL_MS = 250L
    }
}
