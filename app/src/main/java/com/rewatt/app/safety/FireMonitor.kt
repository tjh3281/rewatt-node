package com.rewatt.app.safety

import android.util.Log
import com.rewatt.app.data.TelemetryRepository
import com.rewatt.app.telemetry.FireThreshold
import com.rewatt.app.telemetry.GasReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fire detection from the optional SGP41, process-wide so it keeps working with the screen off or
 * on another tab. Always active whenever gas data arrives.
 *
 * Rule: a ~1 s sample is a hit when VOC Index ≥ 130 OR NOx Index ≥ 5; 2 consecutive hits sound the
 * alarm. Any normal sample, a sensor error (-1) or a warm-up sample (0) resets the count. Samples closer than ~1 s are
 * skipped because the JSON line repeats the last gas value at the radar's packet rate.
 *
 * After Dismiss the alarm re-arms once the air has been normal for 10 s, or after 5 min if it is
 * still high — otherwise an ongoing fire would re-trigger 2 s after every Dismiss.
 */
class FireMonitor(
    private val telemetryRepository: TelemetryRepository,
    val alarm: FireAlarm,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val lock = Any()

    private var lastCountedAt = 0L
    private var hits = 0
    private var rearmPending = false
    private var dismissedAt = 0L
    private var normalSince: Long? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            telemetryRepository.gasSamples.collect { onSample(it) }
        }
    }

    fun dismiss() {
        synchronized(lock) {
            if (alarm.state.value.active && !alarm.state.value.test) {
                rearmPending = true
                dismissedAt = System.currentTimeMillis()
                normalSince = null
            }
            hits = 0
        }
        alarm.dismiss()
    }

    private fun onSample(gas: GasReading) = synchronized(lock) {
        val now = gas.capturedAtMillis
        if (now - lastCountedAt < MIN_SAMPLE_GAP_MS) return@synchronized
        lastCountedAt = now

        Log.d(TAG, "gas voc=${gas.vocIndex} nox=${gas.noxIndex}")
        if (gas.isError || gas.isWarmingUp) {
            hits = 0
            return@synchronized
        }

        if (rearmPending) {
            if (gas.isFireHit) normalSince = null else if (normalSince == null) normalSince = now
            val airCleared = normalSince?.let { now - it >= REARM_NORMAL_MS } == true
            if (airCleared || now - dismissedAt >= REARM_TIMEOUT_MS) {
                rearmPending = false
                Log.i(TAG, "Fire alarm re-armed")
            }
        }

        if (!gas.isFireHit) {
            hits = 0
            return@synchronized
        }
        hits++
        if (hits >= FireThreshold.CONSECUTIVE_HITS && !rearmPending && !alarm.state.value.active) {
            Log.w(TAG, "FIRE: VOC=${gas.vocIndex} NOx=${gas.noxIndex}")
            hits = 0
            alarm.trigger(gas.vocIndex, gas.noxIndex)
        }
    }

    companion object {
        private const val TAG = "FireMonitor"
        private const val MIN_SAMPLE_GAP_MS = 900L
        private const val REARM_NORMAL_MS = 10_000L
        private const val REARM_TIMEOUT_MS = 5 * 60_000L
    }
}
