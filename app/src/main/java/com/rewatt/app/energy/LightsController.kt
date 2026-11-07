package com.rewatt.app.energy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A pending lights switch from the Schedule screen. */
data class LightsSchedule(val turnOn: Boolean, val atMillis: Long)

/** The lights icon changed (on ⇄ off); [seq] grows with every change so each one is told apart. */
data class LightsChange(val seq: Int, val on: Boolean, val reason: Reason) {
    enum class Reason {
        /** The camera's illuminance reading changed. */
        CAMERA,

        /** The room was empty: the Auto-off countdown finished. */
        AUTO_OFF,

        /** A schedule from the Schedule screen ran. */
        SCHEDULE,

        /** The user tapped the lights icon. */
        MANUAL,
    }
}

data class LightsState(
    /** The room's lights: the camera's latest reading, or what the app last switched them to. */
    val lightsOn: Boolean? = null,
    /** When the app last switched the lights (Auto-off, Schedule, a tap). */
    val appSwitchedAt: Long? = null,
    /**
     * Auto-off already switched the lights off but the camera still sees them on (nobody turned them
     * off for real): don't count down again until someone is in the room or the lights go off.
     */
    val autoOffSpent: Boolean = false,
    val aircondOn: Boolean = true,
    val fanOn: Boolean = false,
    /** Auto-off: lights off when the room has been empty for [LightsController.EMPTY_MS] + the countdown. */
    val autoOff: Boolean = false,
    /** While the room is empty with the lights on (Auto-off): when it became empty. */
    val emptySince: Long? = null,
    /** The lights-off countdown is running and ends at this time. */
    val countdownEndsAt: Long? = null,
    val schedule: LightsSchedule? = null,
    /** When the camera's last illuminance reading was taken, to notice a new one. */
    val lastCameraReadAt: Long? = null,
    /** The last on ⇄ off change of [lightsOn] (not the first reading), for the notification. */
    val lastChange: LightsChange? = null,
) {
    fun countdownSecondsLeft(nowMillis: Long): Int? =
        countdownEndsAt?.let { ((it - nowMillis + 999) / 1000).toInt().coerceAtLeast(0) }
}

/**
 * The room's lights, aircond and fan in the ENERGY tab.
 *  - Lights always follow the camera: every new illuminance reading (about every 10 s while the live
 *    view runs) sets them, whether or not anyone is in the room.
 *  - The app can switch them too: Auto-off (live headcount 0 for [EMPTY_MS], then a [COUNTDOWN_MS]
 *    countdown), Schedule (at a set time) and a tap on the light. That shows at once; the next camera
 *    reading taken [SETTLE_MS] later confirms it, or shows the light is still on (no relay yet).
 *  - After Auto-off, if the camera still sees the light on, Auto-off waits until someone is in the
 *    room or the light goes off before counting down again (no repeat every few seconds).
 * Auto-off and Schedule are independent (both can be on). Every on ⇄ off change is recorded in
 * [LightsState.lastChange] for the notification. Driven by [tick] once a second.
 */
class LightsController {
    private val mutableState = MutableStateFlow(LightsState())
    val state: StateFlow<LightsState> = mutableState.asStateFlow()

    /**
     * [liveCount]: the live headcount, null while the live view isn't running (then the room doesn't
     * count as empty). [cameraLightsOn] / [cameraReadAt]: the camera's latest illuminance reading and
     * when it was taken, null before the first.
     */
    fun tick(nowMillis: Long, liveCount: Int?, cameraLightsOn: Boolean?, cameraReadAt: Long?) {
        mutableState.update { step(it, nowMillis, liveCount, cameraLightsOn, cameraReadAt) }
    }

    fun setAutoOff(on: Boolean) =
        mutableState.update { it.copy(autoOff = on, emptySince = null, countdownEndsAt = null, autoOffSpent = false) }

    /** The user tapped the lights icon. */
    fun switchLights(on: Boolean, nowMillis: Long = System.currentTimeMillis()) =
        mutableState.update { switched(it, on, LightsChange.Reason.MANUAL, nowMillis) }

    /** Replaces any pending schedule. */
    fun schedule(turnOn: Boolean, atMillis: Long) = mutableState.update { it.copy(schedule = LightsSchedule(turnOn, atMillis)) }

    fun cancelSchedule() = mutableState.update { it.copy(schedule = null) }

    fun toggleAircond() = mutableState.update { it.copy(aircondOn = !it.aircondOn) }

    fun toggleFan() = mutableState.update { it.copy(fanOn = !it.fanOn) }

    companion object {
        /** The room must stay empty this long before the lights-off countdown starts… */
        const val EMPTY_MS = 10_000L

        /** …which then runs this long, shown on the occupancy card. */
        const val COUNTDOWN_MS = 5_000L

        /** A camera reading taken this soon after the app switched the lights is too early to trust. */
        const val SETTLE_MS = 3_000L

        internal fun step(state: LightsState, now: Long, liveCount: Int?, cameraLightsOn: Boolean?, cameraReadAt: Long?): LightsState {
            var s = state

            s.schedule?.let {
                if (now >= it.atMillis) s = switched(s, it.turnOn, LightsChange.Reason.SCHEDULE, now).copy(schedule = null)
            }

            // Camera: every new reading sets the lights (unless it was taken right after an app switch).
            if (cameraLightsOn != null && cameraReadAt != null && cameraReadAt != s.lastCameraReadAt) {
                s = s.copy(lastCameraReadAt = cameraReadAt)
                if (cameraReadAt >= (s.appSwitchedAt ?: Long.MIN_VALUE) + SETTLE_MS) {
                    s = withLights(s, cameraLightsOn, LightsChange.Reason.CAMERA)
                    // The camera saw the lights really off: Auto-off may count down again next time.
                    if (!cameraLightsOn) s = s.copy(autoOffSpent = false)
                }
            }
            // Someone in the room: Auto-off may count down again once they leave.
            if (liveCount != null && liveCount > 0) s = s.copy(autoOffSpent = false)

            // Auto-off: empty for EMPTY_MS → COUNTDOWN_MS countdown → lights off.
            val empty = liveCount == 0
            if (!s.autoOff || s.lightsOn != true || !empty || s.autoOffSpent) {
                return s.copy(emptySince = null, countdownEndsAt = null)
            }
            val since = s.emptySince ?: now
            s = s.copy(emptySince = since)
            if (s.countdownEndsAt == null && now - since >= EMPTY_MS) s = s.copy(countdownEndsAt = now + COUNTDOWN_MS)
            val endsAt = s.countdownEndsAt
            if (endsAt != null && now >= endsAt) s = switched(s, on = false, LightsChange.Reason.AUTO_OFF, now).copy(autoOffSpent = true)
            return s
        }

        /** Switched by the app (schedule, Auto-off, a tap): shows at once; later camera readings still count. */
        private fun switched(state: LightsState, on: Boolean, reason: LightsChange.Reason, now: Long): LightsState =
            withLights(state, on, reason).copy(appSwitchedAt = now, emptySince = null, countdownEndsAt = null)

        /** Set the lights; a real on ⇄ off change (not the first reading) is recorded in [LightsState.lastChange]. */
        private fun withLights(state: LightsState, on: Boolean, reason: LightsChange.Reason): LightsState {
            if (state.lightsOn == on) return state
            val change = if (state.lightsOn == null) {
                state.lastChange
            } else {
                LightsChange((state.lastChange?.seq ?: 0) + 1, on, reason)
            }
            return state.copy(lightsOn = on, lastChange = change)
        }
    }
}
