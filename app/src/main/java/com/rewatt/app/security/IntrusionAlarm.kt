package com.rewatt.app.security

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The intruder alert: red full-screen notification, torch strobe and vibration immediately, then
 * the deterrent voice "You are being recorded", then a looping siren on the ALARM stream at max
 * volume (the user's alarm volume is restored on [dismiss]). Outputs are shared via [AlarmOutputs].
 */
class IntrusionAlarm(
    private val stateHolder: SecurityStateHolder,
    private val notifier: IntrusionNotifier,
    private val voice: DeterrentVoice,
    private val outputs: AlarmOutputs,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sirenJob: Job? = null

    val alarmActive: Boolean get() = stateHolder.state.value.alarmActive

    fun trigger(
        eventId: String,
        roomId: String,
        photoName: String?,
        score: Float?,
        torch: (Boolean) -> Unit,
        torchRestore: () -> Unit,
    ) {
        scope.launch {
            if (alarmActive) return@launch
            stateHolder.update {
                it.copy(
                    alarmActive = true,
                    alarmEventId = eventId,
                    alarmStartedAtMillis = System.currentTimeMillis(),
                    alarmPhotoName = photoName,
                    alarmScore = score,
                )
            }
            outputs.begin(AlarmReason.INTRUDER, torch, torchRestore)
            notifier.showIntrusion(eventId, roomId, photoName, score)

            // Deterrent first so it is heard clearly, then the siren (fallback if TTS never reports done).
            sirenJob = launch {
                delay(VOICE_TIMEOUT_MS)
                outputs.startSiren()
            }
            voice.speak {
                scope.launch {
                    sirenJob?.cancel()
                    if (alarmActive) outputs.startSiren()
                }
            }
        }
    }

    /**
     * Deterrent only — "You are being recorded" at max alarm volume, no siren, no red screen.
     * Used when a person is seen but their face isn't (back turned, fully covered, too far).
     */
    /**
     * The small "You are being recorded" pop-up only (no voice): shown first, as soon as a person is
     * confirmed (the event starts), before the face check decides family / stranger.
     */
    fun showRecordingNotice() {
        scope.launch {
            if (alarmActive) return@launch
            stateHolder.update { it.copy(recordingNoticeAtMillis = System.currentTimeMillis()) }
        }
    }

    fun announceRecording() {
        scope.launch {
            if (alarmActive) return@launch // the full alarm already speaks the deterrent
            // The small in-app alert shows together with the voice.
            stateHolder.update { it.copy(recordingNoticeAtMillis = System.currentTimeMillis()) }
            outputs.raiseVolume()
            voice.speak {
                scope.launch { outputs.restoreVolumeIfIdle() }
            }
        }
    }

    fun dismiss() {
        scope.launch {
            if (!alarmActive) return@launch
            sirenJob?.cancel()
            sirenJob = null
            outputs.end(AlarmReason.INTRUDER)
            notifier.cancelIntrusion()
            stateHolder.update {
                it.copy(alarmActive = false, alarmEventId = null, alarmStartedAtMillis = null, alarmPhotoName = null, alarmScore = null)
            }
        }
    }

    companion object {
        private const val VOICE_TIMEOUT_MS = 5_000L
    }
}
