package com.rewatt.app.telemetry

/**
 * Optional Sensirion SGP41 reading (Gas Index Algorithm output — unitless indices, not ppm/ppb).
 * VOC Index 1..500 with ~100 = this room's normal air; NOx Index 1..500 with ~1 = normal.
 * A `-1` from the firmware (read failure) is stored as `null`. The algorithm outputs `0` while it warms
 * up (about the first minute after power-on), so a 0 means "no value yet", not clean air.
 */
data class GasReading(
    val vocIndex: Int?,
    val noxIndex: Int?,
    val capturedAtMillis: Long,
) {
    val isError: Boolean get() = vocIndex == null && noxIndex == null

    /** SGP41 still warming up: the Gas Index Algorithm reports 0 until it has a baseline. */
    val isWarmingUp: Boolean get() = vocIndex == 0 || noxIndex == 0

    val isFireHit: Boolean
        get() = (vocIndex ?: 0) >= FireThreshold.VOC_INDEX || (noxIndex ?: 0) >= FireThreshold.NOX_INDEX
}

/** Traffic-light level shown by the breathing line under each gas gauge. */
enum class GasLevel { SAFE, CAUTION, DANGER }

fun vocLevel(vocIndex: Int): GasLevel = when {
    vocIndex >= FireThreshold.VOC_INDEX -> GasLevel.DANGER
    vocIndex >= FireThreshold.VOC_CAUTION -> GasLevel.CAUTION
    else -> GasLevel.SAFE
}

fun noxLevel(noxIndex: Int): GasLevel = when {
    noxIndex >= FireThreshold.NOX_INDEX -> GasLevel.DANGER
    noxIndex >= FireThreshold.NOX_CAUTION -> GasLevel.CAUTION
    else -> GasLevel.SAFE
}

/** Fire rule for the SGP41 Gas Index values. */
object FireThreshold {
    const val VOC_INDEX = 130
    const val NOX_INDEX = 5

    /** Amber "caution" band just below the fire thresholds (display only — no alarm). */
    const val VOC_CAUTION = 115
    const val NOX_CAUTION = 3

    /** Consecutive ~1 s samples above threshold before the alarm sounds (filters one-off spikes). */
    const val CONSECUTIVE_HITS = 2

    /** The dashboard shows "--" when no gas reading arrived within this window (no SGP41 attached). */
    const val STALE_AFTER_MS = 30_000L
}
