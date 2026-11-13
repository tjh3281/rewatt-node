package com.rewatt.app.core

import android.content.Context
import com.rewatt.app.data.AuthRepository
import com.rewatt.app.data.MqttSyncRepository
import com.rewatt.app.data.ReWattDatabase
import com.rewatt.app.data.TelemetryRepository
import com.rewatt.app.data.WeatherRepository
import com.rewatt.app.energy.EnergyStateHolder
import com.rewatt.app.energy.DemoClimate
import com.rewatt.app.energy.LightsController
import com.rewatt.app.energy.tapo.TapoLink
import com.rewatt.app.perception.LiveHeadcount
import com.rewatt.app.perception.LivePreviewHub
import com.rewatt.app.perception.PerceptionStateHolder
import com.rewatt.app.perception.YoloDetector
import com.rewatt.app.safety.FireAlarm
import com.rewatt.app.safety.FireMonitor
import com.rewatt.app.safety.TorchRouter
import com.rewatt.app.security.AlarmOutputs
import com.rewatt.app.security.DeterrentVoice
import com.rewatt.app.security.FaceRecognizer
import com.rewatt.app.security.FamilyFaceStore
import com.rewatt.app.security.IntrusionAlarm
import com.rewatt.app.security.IntrusionNotifier
import com.rewatt.app.security.SecurityEventStore
import com.rewatt.app.security.SecuritySettings
import com.rewatt.app.security.SecurityStateHolder
import com.rewatt.app.security.SecurityTestCheck

class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val authRepository: AuthRepository by lazy {
        AuthRepository(appContext)
    }

    val database: ReWattDatabase by lazy {
        ReWattDatabase.create(appContext)
    }

    val mqttSyncRepository: MqttSyncRepository by lazy {
        MqttSyncRepository(appContext)
    }

    val telemetryRepository: TelemetryRepository by lazy {
        TelemetryRepository(
            telemetryDao = database.telemetryDao(),
        )
    }

    val weatherRepository: WeatherRepository by lazy {
        WeatherRepository(appContext)
    }

    val perceptionStateHolder: PerceptionStateHolder by lazy {
        PerceptionStateHolder()
    }

    /** The one YOLO model: loaded and closed by CameraCaptureService, shared with the live headcount. */
    @Volatile
    var yoloDetector: YoloDetector? = null

    val liveHeadcount: LiveHeadcount by lazy {
        LiveHeadcount(detectorProvider = { yoloDetector }, stateHolder = perceptionStateHolder)
    }

    // ── Energy (motion → photo → YOLO occupancy, live camera view) ──
    val livePreviewHub: LivePreviewHub by lazy {
        LivePreviewHub()
    }

    val energyStateHolder: EnergyStateHolder by lazy {
        EnergyStateHolder()
    }

    /** Room lights (live illuminance, Auto-off, Schedule) and aircond; ticked by SensorViewModel. */
    val lightsController: LightsController by lazy {
        LightsController()
    }

    /** Debug demo: tap-driven room temperature / humidity in the ENERGY tab (see [DemoClimate]). */
    val demoClimate: DemoClimate by lazy {
        DemoClimate(appContext, lightsController) { mqttSyncRepository.roomId.ifBlank { "Room1" } }
    }

    /** The Tapo smart plug that switches the Room 1 lights for real (local Wi-Fi). */
    val tapoLink: TapoLink by lazy {
        TapoLink(appContext, lightsController)
    }

    // ── CCTV (security camera) ──
    val securitySettings: SecuritySettings by lazy {
        SecuritySettings(appContext)
    }

    val securityStateHolder: SecurityStateHolder by lazy {
        SecurityStateHolder(initialArmed = securitySettings.armed)
    }

    val familyFaceStore: FamilyFaceStore by lazy {
        FamilyFaceStore(appContext)
    }

    val securityEventStore: SecurityEventStore by lazy {
        SecurityEventStore(appContext)
    }

    val faceRecognizer: FaceRecognizer by lazy {
        FaceRecognizer(appContext, familyFaceStore)
    }

    val intrusionNotifier: IntrusionNotifier by lazy {
        IntrusionNotifier(appContext)
    }

    val deterrentVoice: DeterrentVoice by lazy {
        DeterrentVoice(appContext)
    }

    val securityTestCheck: SecurityTestCheck by lazy {
        SecurityTestCheck(appContext, faceRecognizer, securitySettings, securityStateHolder)
    }

    /** Siren / volume / vibration / strobe shared by the intruder and fire alarms. */
    val alarmOutputs: AlarmOutputs by lazy {
        AlarmOutputs(appContext)
    }

    val intrusionAlarm: IntrusionAlarm by lazy {
        IntrusionAlarm(securityStateHolder, intrusionNotifier, deterrentVoice, alarmOutputs)
    }

    // ── Fire (optional SGP41 gas sensor) ──
    val torchRouter: TorchRouter by lazy {
        TorchRouter(appContext)
    }

    val fireMonitor: FireMonitor by lazy {
        FireMonitor(
            telemetryRepository = telemetryRepository,
            alarm = FireAlarm(alarmOutputs, intrusionNotifier, torchRouter, mqttSyncRepository),
        )
    }
}
