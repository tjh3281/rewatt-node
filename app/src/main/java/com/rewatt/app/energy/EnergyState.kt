package com.rewatt.app.energy

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class EnergyPhase {
    /** Nothing known yet (just started, or the last check could not run). */
    IDLE,

    /** Check triggered: photo + YOLO headcount in progress. */
    ENTERING,

    /** YOLO counted people in the room. */
    OCCUPIED,

    /** Walk-out check: photo + YOLO checking whether anyone is still there. */
    LEAVING,

    /** YOLO counted 0 people; the room must stay empty for [EnergyMonitor.EMPTY_CONFIRM_MS]. */
    EMPTY_COUNTDOWN,

    /** Confirmed empty; the "turn OFF lights and aircond" banner was shown. */
    EMPTY,
}

data class EnergyBanner(val id: Long, val kind: Kind, val atMillis: Long) {
    enum class Kind { MOTION, NO_MOTION, ROOM_EMPTY }
}

/** Kind of occupancy check (see [EnergyStateHolder.requestCheck]; nothing triggers one yet). */
enum class EnergyCheck {
    /** Person walks in: "Movement detected" banner → photo → YOLO count. */
    ENTER,

    /** Person walks out: "No movement detected" banner → photo → YOLO count; 0 starts the empty countdown. */
    EXIT,
}

data class EnergyUiState(
    val phase: EnergyPhase = EnergyPhase.IDLE,
    /** A photo is being taken / analysed by YOLO. */
    val capturing: Boolean = false,
    /** People counted by YOLO in the last successful photo (null = no photo yet). */
    val personCount: Int? = null,
    /** While [EnergyPhase.EMPTY_COUNTDOWN]: when the room counts as confirmed empty. */
    val countdownEndsAt: Long? = null,
    /** The camera box shows the last check's photo (with YOLO boxes) until it is tapped, then live video. */
    val photoPinned: Boolean = false,
    val banner: EnergyBanner? = null,
    /** CCTV is Armed: it owns motion events, so the energy flow is paused. */
    val pausedForCctv: Boolean = false,
)

/** Process-wide holder: [EnergyMonitor] (in the camera service) writes, the ENERGY tab reads. */
class EnergyStateHolder {
    private val mutableState = MutableStateFlow(EnergyUiState())
    val state: StateFlow<EnergyUiState> = mutableState.asStateFlow()

    /** Occupancy check requests. Conflated: kept until the monitor (camera service) is running. */
    val checkRequests = Channel<EnergyCheck>(Channel.CONFLATED)

    fun update(transform: (EnergyUiState) -> EnergyUiState) = mutableState.update(transform)

    fun requestCheck(check: EnergyCheck) {
        checkRequests.trySend(check)
    }
}
