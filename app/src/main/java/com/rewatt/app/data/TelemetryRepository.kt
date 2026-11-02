package com.rewatt.app.data

import android.util.Log
import com.rewatt.app.telemetry.GasReading
import com.rewatt.app.telemetry.PacketFreshness
import com.rewatt.app.telemetry.RadarTelemetry
import com.rewatt.app.telemetry.TelemetryFreshness
import com.rewatt.app.telemetry.UsbDataParser
import com.rewatt.app.usb.UsbConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TelemetryRepository(
    private val telemetryDao: TelemetryDao,
) {
    private val mutableState = MutableStateFlow(SensorDashboardState())
    val telemetryState: StateFlow<SensorDashboardState> = mutableState.asStateFlow()
    private val freshness = TelemetryFreshness()

    /** True only when the USB mmWave sensor sent a packet within the freshness window (~30s). */
    fun isTelemetryFresh(nowMillis: Long = System.currentTimeMillis()): Boolean =
        freshness.evaluate(mutableState.value.lastValidPacketAtMillis, nowMillis) == PacketFreshness.Fresh

    // Throttle markers (Volatile: packets are processed on concurrent coroutines, one per serial line;
    // an occasional race just means one extra emit/insert, which is harmless).
    @Volatile
    private var lastPersistedAtMillis = 0L
    @Volatile
    private var lastUiEmitAtMillis = 0L
    private val validPacketCounter = java.util.concurrent.atomic.AtomicInteger(0)

    // Optional SGP41 gas data. Every reading goes to the fire monitor (not throttled); the dashboard
    // gets the latest one alongside the normal UI emission. Absent on modules without an SGP41.
    private val mutableGasSamples = MutableSharedFlow<GasReading>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val gasSamples: SharedFlow<GasReading> = mutableGasSamples.asSharedFlow()

    @Volatile
    private var latestGas: GasReading? = null

    suspend fun acceptRawPacket(rawPacket: String, capturedAtMillis: Long = System.currentTimeMillis()) {
        // Debug firmware also sends the raw SGP41 signals (sraw_voc / sraw_nox): log the line so they
        // can be read over adb while testing the gas sensor. Normal firmware never hits this.
        if (rawPacket.contains("sraw_")) Log.d("GasRaw", rawPacket.trim())
        val gas = UsbDataParser.parseGas(rawPacket, capturedAtMillis)
        if (gas != null) {
            latestGas = gas
            mutableGasSamples.tryEmit(gas)
        }

        val telemetry = UsbDataParser.parseTelemetry(rawPacket, capturedAtMillis)
        if (telemetry == null && gas != null) {
            // Plain-text gas-only line (VOC:x,NOX:y): valid data, not a malformed packet.
            mutableState.update { it.copy(latestGas = gas) }
            return
        }
        if (telemetry == null) {
            mutableState.update { current ->
                current.copy(
                    malformedPacketCount = current.malformedPacketCount + 1,
                    lastMalformedPacket = rawPacket.trim().take(MAX_MALFORMED_PACKET_PREVIEW),
                )
            }
            return
        }

        val count = validPacketCounter.incrementAndGet()

        // Throttle the LIVE UI emission to ~UI_EMIT_INTERVAL_MILLIS. The mmWave module streams many
        // packets a second; emitting dashboard state on every one floods Compose with recompositions
        // and makes the live card stutter/lag on the sensor phone. Emitting the latest packet at ~8 Hz
        // stays real-time but smooth. (Persistence is throttled separately, below.)
        if (capturedAtMillis - lastUiEmitAtMillis >= UI_EMIT_INTERVAL_MILLIS) {
            lastUiEmitAtMillis = capturedAtMillis
            mutableState.update { current ->
                current.copy(
                    latestTelemetry = telemetry,
                    lastValidPacketAtMillis = telemetry.capturedAtMillis,
                    validPacketCount = count,
                    lastMalformedPacket = null,
                    latestGas = latestGas ?: current.latestGas,
                )
            }
        }

        // Persist at most once per PERSIST_INTERVAL_MILLIS. The serial stream can emit many packets a
        // second; the dashboard/forecast only need a sampled history, so throttling keeps the table
        // small and the write path off the live UI's back.
        if (capturedAtMillis - lastPersistedAtMillis >= PERSIST_INTERVAL_MILLIS) {
            lastPersistedAtMillis = capturedAtMillis
            runCatching { telemetryDao.insert(telemetry.toEntity()) }
        }
    }

    fun setConnectionStatus(
        status: UsbConnectionStatus,
        message: String,
    ) {
        mutableState.update { current ->
            current.copy(
                connectionStatus = status,
                connectionMessage = message,
            )
        }
    }

    fun updatePerceptionSummary(perceptionSummary: PerceptionSyncSummary) {
        // Perception summary updated locally
    }

    companion object {
        private const val MAX_MALFORMED_PACKET_PREVIEW = 160

        // Persist at most one telemetry row every 2s regardless of serial packet rate.
        private const val PERSIST_INTERVAL_MILLIS = 2_000L

        // Emit live dashboard state at most ~8 Hz so a high serial packet rate can't flood
        // Compose recompositions and make the live card lag/stutter.
        private const val UI_EMIT_INTERVAL_MILLIS = 120L
    }
}

data class SensorDashboardState(
    val connectionStatus: UsbConnectionStatus = UsbConnectionStatus.Disconnected,
    val connectionMessage: String = "USB service has not scanned yet.",
    val latestTelemetry: RadarTelemetry? = null,
    /** Latest SGP41 reading, or null when the module has no gas sensor. */
    val latestGas: GasReading? = null,
    val lastValidPacketAtMillis: Long? = null,
    val validPacketCount: Int = 0,
    val malformedPacketCount: Int = 0,
    val lastMalformedPacket: String? = null,
    val firebaseSyncState: FirebaseSyncState = FirebaseSyncState(),
    val activityAnalysisState: ActivityAnalysisState = ActivityAnalysisState(),
)
