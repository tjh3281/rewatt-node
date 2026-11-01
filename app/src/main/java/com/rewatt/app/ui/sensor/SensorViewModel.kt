package com.rewatt.app.ui.sensor

import android.content.Context
import android.os.BatteryManager
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rewatt.app.data.MqttState
import com.rewatt.app.data.MqttSyncRepository
import com.rewatt.app.data.SensorDashboardState
import com.rewatt.app.data.TelemetryRepository
import com.rewatt.app.perception.PerceptionStateHolder
import com.rewatt.app.perception.PerceptionUiState
import com.rewatt.app.telemetry.FireThreshold
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

data class SensorNodeUiState(
    val roomId: String = "Room1",
    val nodeId: String = "",
    val mqttState: MqttState = MqttState(),
    val sensorState: SensorDashboardState = SensorDashboardState(),
    val perceptionState: PerceptionUiState = PerceptionUiState(),
    val nowMillis: Long = System.currentTimeMillis(),
    val batteryPercent: Int = 100,
)

class SensorViewModel(
    private val appContext: Context,
    private val telemetryRepository: TelemetryRepository,
    val mqttRepository: MqttSyncRepository,
    val perceptionStateHolder: PerceptionStateHolder,
) : ViewModel() {

    private val nowMillisFlow = MutableStateFlow(System.currentTimeMillis())

    val uiState: StateFlow<SensorNodeUiState> = combine(
        telemetryRepository.telemetryState,
        mqttRepository.mqttState,
        perceptionStateHolder.state,
        nowMillisFlow,
    ) { sensorState: SensorDashboardState, mqttState: MqttState, perceptionState: PerceptionUiState, now: Long ->
        SensorNodeUiState(
            roomId = mqttRepository.roomId,
            nodeId = mqttRepository.nodeId,
            mqttState = mqttState,
            sensorState = sensorState,
            perceptionState = perceptionState,
            nowMillis = now,
            batteryPercent = getRealBatteryLevel(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SensorNodeUiState(),
    )

    init {
        // Ticker loop: update time every second and emit MQTT 1 Hz payload
        viewModelScope.launch {
            while (true) {
                val current = System.currentTimeMillis()
                nowMillisFlow.value = current
                streamMqttTelemetry(current)
                delay(1000L)
            }
        }
    }

    fun updateMqttConfig(host: String, port: Int, user: String, pass: String, roomId: String) {
        mqttRepository.configure(host, port, user, pass, roomId)
    }

    /** Publish latest camera frame photo (compressed JPEG Base64) to MQTT on demand. */
    fun publishCurrentPhoto() {
        val bitmap = perceptionStateHolder.state.value.latestFrame ?: return
        viewModelScope.launch {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, outputStream)
            val jpegBytes = outputStream.toByteArray()
            val base64Jpeg = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

            val currentRoomId = mqttRepository.roomId
            val json = JSONObject().apply {
                put("nodeId", mqttRepository.nodeId)
                put("roomId", currentRoomId)
                put("timestamp", System.currentTimeMillis())
                put("imageFormat", "jpeg")
                put("imageBase64", base64Jpeg)
            }

            mqttRepository.publishTelemetryJson(json.toString(), currentRoomId)
        }
    }

    private fun getRealBatteryLevel(): Int {
        return runCatching {
            val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val capacity = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (capacity in 0..100) capacity else 100
        }.getOrDefault(100)
    }

    private fun streamMqttTelemetry(now: Long) {
        val state = uiState.value
        val latest = state.sensorState.latestTelemetry
        val perception = state.perceptionState
        val batteryPct = state.batteryPercent

        val radarObj = JSONObject().apply {
            put("state", latest?.radarState ?: 0)
            put("movingDistanceCm", latest?.movingDistanceCm ?: 0)
            put("movingEnergy", latest?.movingEnergy ?: 0)
            put("stationaryDistanceCm", latest?.stationaryDistanceCm ?: 0)
            put("stationaryEnergy", latest?.stationaryEnergy ?: 0)
        }

        val json = JSONObject().apply {
            put("nodeId", state.nodeId)
            put("roomId", state.roomId)
            put("timestamp", now)
            put("presence", latest?.presence ?: false)
            // Live headcount on the preview when it is running; otherwise the last photo's count.
            put("personCount", perception.liveCount(now) ?: perception.personCount)
            put("lightsOn", perception.lightsOn)
            put("temperatureC", latest?.temperatureC ?: 0.0)
            put("humidityPercent", latest?.humidityPercent ?: 0.0)
            put("exposureEv", perception.exposureEv)
            put("radar", radarObj)
            put("batteryPercent", batteryPct)
            // Optional SGP41 — only included when a recent reading exists, so existing consumers see no change.
            state.sensorState.latestGas
                ?.takeIf { now - it.capturedAtMillis <= FireThreshold.STALE_AFTER_MS && !it.isWarmingUp }
                ?.let { gas ->
                    gas.vocIndex?.let { put("vocIndex", it) }
                    gas.noxIndex?.let { put("noxIndex", it) }
                }
        }

        mqttRepository.publishTelemetryJson(json.toString(), state.roomId)
    }
}

class SensorViewModelFactory(
    private val appContext: Context,
    private val telemetryRepository: TelemetryRepository,
    private val mqttRepository: MqttSyncRepository,
    private val perceptionStateHolder: PerceptionStateHolder,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SensorViewModel(appContext, telemetryRepository, mqttRepository, perceptionStateHolder) as T
    }
}
