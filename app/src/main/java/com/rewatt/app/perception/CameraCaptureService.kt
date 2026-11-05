package com.rewatt.app.perception

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build

import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.rewatt.app.R
import com.rewatt.app.ReWattApplication
import com.rewatt.app.core.AppGraph
import com.rewatt.app.data.PerceptionSyncSummary
import com.rewatt.app.data.TelemetryRepository
import com.rewatt.app.energy.EnergyMonitor
import com.rewatt.app.security.IntrusionMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the camera so it keeps working while the screen is off. It runs two
 * independent features that share one [CameraCaptureController]:
 *
 * - **Occupancy perception**: loads YOLO (shared through [AppGraph.yoloDetector] with the live
 *   headcount on the camera preview, [LiveHeadcount]) and runs the ENERGY flow, which takes a photo
 *   when the radar sees someone enter / leave. Results go to [PerceptionStateHolder] (dashboard) and
 *   [TelemetryRepository] (sync).
 * - **CCTV security** ([IntrusionMonitor]): while Armed, radar motion opens events that record video
 *   and check faces against FAMILY.
 *
 * The service stops only when both are off.
 */
class CameraCaptureService : LifecycleService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var appGraph: AppGraph
    private lateinit var stateHolder: PerceptionStateHolder
    private lateinit var telemetryRepository: TelemetryRepository
    private lateinit var alarmManager: AlarmManager

    private var cameraController: CameraCaptureController? = null

    @Volatile
    private var yoloDetector: YoloDetector? = null
    private var yoloLoading = false
    private var started = false
    private var armed = false
    private var monitor: IntrusionMonitor? = null
    private var energyMonitor: EnergyMonitor? = null

    @Volatile
    private var testCheckRunning = false

    override fun onCreate() {
        super.onCreate()
        appGraph = (application as ReWattApplication).appGraph
        stateHolder = appGraph.perceptionStateHolder
        telemetryRepository = appGraph.telemetryRepository
        alarmManager = getSystemService(AlarmManager::class.java)

        startAsForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent == null) {
            // START_STICKY restart after the process was killed: restore only what was on.
            val settings = appGraph.securitySettings
            if (settings.perceptionActive) startPerception()
            if (settings.armed) arm()
            stopIfIdle()
            return START_STICKY
        }
        when (intent.action) {
            // A wake-up left over from the old 10-minute photo schedule: cancel it, take no photo.
            ACTION_CAPTURE -> runCatching { alarmManager.cancel(capturePendingIntent()) }
            ACTION_CAPTURE_NOW -> {
                ensureController()
                cameraController?.captureNow()
            }
            ACTION_STOP_PERCEPTION -> stopPerception()
            ACTION_ARM -> arm()
            ACTION_DISARM -> disarm()
            ACTION_DISMISS_ALARM -> dismissAlarm()
            ACTION_RELEASE_CAMERA -> serviceScope.launch { cameraController?.pauseForExternalCamera() }
            ACTION_RESUME_CAMERA -> cameraController?.resumeAfterExternalCamera()
            ACTION_TEST_CHECK -> runTestCheck()
            else -> startPerception()
        }
        return START_STICKY
    }

    // ── Occupancy perception ─────────────────────────────────────────────────────────────────

    private fun startPerception() {
        if (started) return
        started = true
        appGraph.securitySettings.perceptionActive = true
        stateHolder.update {
            it.copy(
                isRunning = true,
                statusMessage = "Perception is running in the background.",
                yoloMessage = "Loading YOLO in the background.",
            )
        }
        ensureController()
        loadDetectors()
        // No scheduled photos: the headcount runs live on the camera preview. Photos are only taken
        // by "Capture Now" (ACTION_CAPTURE_NOW) or the ENERGY flow when the radar sees someone enter
        // / leave. Clear any alarm still pending from the old 10-minute schedule.
        runCatching { alarmManager.cancel(capturePendingIntent()) }
        energyMonitor = EnergyMonitor(
            scope = serviceScope,
            perception = stateHolder,
            security = appGraph.securityStateHolder,
            holder = appGraph.energyStateHolder,
            requestCapture = { cameraController?.capture() },
        ).also { it.start() }
    }

    private fun stopPerception() {
        started = false
        energyMonitor?.stop()
        energyMonitor = null
        appGraph.securitySettings.perceptionActive = false
        runCatching { alarmManager.cancel(capturePendingIntent()) }
        stateHolder.update { it.copy(isRunning = false, statusMessage = "Perception is stopped.") }
        stopIfIdle()
    }

    private fun ensureController() {
        if (cameraController == null) setupControllers()
    }

    private fun setupControllers() {
        cameraController = CameraCaptureController(
            context = this,
            yoloDetectorProvider = { yoloDetector },
            // The photo's own EXIF EV is no longer used: illuminance comes from the live view (onLiveExposure).
            onFramePreview = { bitmap, _, _ ->
                // Fast path: show the frame immediately, clear stale boxes, defer detection/alerts.
                stateHolder.update {
                    it.copy(
                        latestFrame = bitmap,
                        latestFrameAtMillis = System.currentTimeMillis(),
                        yoloDetections = emptyList(),
                        yoloMessage = "Frame captured; running person detection…",
                        statusMessage = "Perception is running.",
                    )
                }
            },
            onFrameAnalyzed = { bitmap, detections, yoloMessage, _, _ ->
                val yoloRan = bitmap != null && yoloDetector != null
                val newState = stateHolder.updateAndGet {
                    it.copy(
                        analyzedAtMillis = System.currentTimeMillis(),
                        analysisOk = yoloRan,
                        latestFrame = bitmap ?: it.latestFrame,
                        latestFrameAtMillis = if (bitmap != null) System.currentTimeMillis() else it.latestFrameAtMillis,
                        yoloDetections = detections,
                        yoloMessage = yoloMessage,
                        statusMessage = "Perception is running.",
                    )
                }
                publishPerceptionSummary(newState)
                // NOTE: the full-screen energy-waste alert is no longer driven by raw sensor detection
                // (empty + lights on). It now escalates from an un-Done "Turn off AC & lights"
                // recommendation on the Admin side (see DashboardViewModel.notifyNowActions).
            },
            onError = { message -> stateHolder.update { it.copy(yoloMessage = message) } },
            liveView = appGraph.livePreviewHub,
            // Live illuminance (every 10 s while the live view runs) → the occupancy card's lights icon.
            onLiveExposure = { reading ->
                stateHolder.update {
                    it.copy(
                        exposureEv = reading.ev,
                        exposureIso = reading.iso,
                        exposureTimeSeconds = reading.exposureTimeSeconds,
                        exposureAperture = reading.aperture,
                        lightsOn = reading.lightsOn,
                        exposureMessage = "EV %.1f; lights %s.".format(reading.ev, if (reading.lightsOn) "on" else "off"),
                    )
                }
            },
        ).also {
            it.attach(this)
            appGraph.torchRouter.controller = it // fire strobe goes through CameraX while we hold the camera
        }
    }

    private fun loadDetectors() {
        if (yoloDetector != null || yoloLoading) return
        yoloLoading = true
        serviceScope.launch {
            runCatching { YoloDetector.create(applicationContext) }
                .onSuccess {
                    yoloDetector = it
                    appGraph.yoloDetector = it
                    stateHolder.update { s -> s.copy(yoloMessage = "YOLO loaded; counting people live on the camera preview.") }
                }
                .onFailure { error ->
                    stateHolder.update { s -> s.copy(yoloMessage = "YOLO model failed to load: ${error.message ?: error::class.java.simpleName}") }
                }
            yoloLoading = false
        }
    }

    /** The old 10-minute photo alarm's intent — kept only so a pending one can be cancelled. */
    private fun capturePendingIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            CAPTURE_REQUEST_CODE,
            Intent(this, CameraCaptureService::class.java).setAction(ACTION_CAPTURE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun publishPerceptionSummary(state: PerceptionUiState) {
        telemetryRepository.updatePerceptionSummary(
            PerceptionSyncSummary(
                personCount = state.personCount,
                lightsOn = state.lightsOn,
                exposureEv = state.exposureEv,
                exposureIso = state.exposureIso,
                exposureTimeSeconds = state.exposureTimeSeconds,
                exposureAperture = state.exposureAperture,
                capturedAtMillis = state.latestFrameAtMillis,
            ),
        )
    }

    // ── CCTV security ────────────────────────────────────────────────────────────────────────

    private fun arm() {
        if (armed) return
        armed = true
        appGraph.securitySettings.armed = true
        startAsForegroundService() // re-promote so the microphone type is included for clip audio
        ensureController()
        loadDetectors()
        appGraph.deterrentVoice.prepare()
        monitor = IntrusionMonitor(
            context = this,
            scope = serviceScope,
            controllerProvider = { cameraController },
            yoloProvider = { yoloDetector },
            perceptionActive = { started },
            telemetryRepository = telemetryRepository,
            recognizer = appGraph.faceRecognizer,
            eventStore = appGraph.securityEventStore,
            settings = appGraph.securitySettings,
            stateHolder = appGraph.securityStateHolder,
            alarm = appGraph.intrusionAlarm,
            notifier = appGraph.intrusionNotifier,
            mqtt = appGraph.mqttSyncRepository,
        ).also { it.start() }
        serviceScope.launch { appGraph.securityEventStore.applyRetention(activeId = null) }
    }

    private fun disarm() {
        armed = false
        appGraph.securitySettings.armed = false
        val current = monitor
        monitor = null
        serviceScope.launch {
            current?.stop()
            appGraph.securityStateHolder.update { it.copy(armed = false) }
            launch(Dispatchers.Main) { stopIfIdle() }
        }
    }

    /** Manual "Test CCTV check": one photo through the full pipeline, result only (no alarm). */
    private fun runTestCheck() {
        ensureController()
        loadDetectors()
        val controller = cameraController ?: return
        testCheckRunning = true
        serviceScope.launch {
            try {
                appGraph.securityTestCheck.run(controller) { yoloDetector }
                // Release the camera again unless a real motion event grabbed it meanwhile.
                if (appGraph.securityStateHolder.state.value.activeEventId == null) {
                    controller.endSecuritySession(keepImageBound = started)
                }
            } finally {
                testCheckRunning = false
                launch(Dispatchers.Main) { stopIfIdle() }
            }
        }
    }

    private fun dismissAlarm() {
        appGraph.intrusionAlarm.dismiss()
        serviceScope.launch {
            monitor?.onAlarmDismissed()
            launch(Dispatchers.Main) { stopIfIdle() }
        }
    }

    private fun stopIfIdle() {
        if (!started && !armed && !testCheckRunning && !appGraph.securityStateHolder.state.value.alarmActive) stopSelf()
    }

    // ── Foreground notification ──────────────────────────────────────────────────────────────

    private fun startAsForegroundService() {
        createNotificationChannel()
        val withMic = foregroundServiceType(includeMicrophone = true)
        val cameraOnly = foregroundServiceType(includeMicrophone = false)
        val result = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), withMic)
        }.recoverCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), cameraOnly)
        }
        result.onFailure { error ->
            // Android refuses camera access when started from the background (e.g. after a reboot).
            Log.w(TAG, "Could not start camera foreground service", error)
            appGraph.intrusionNotifier.showResumeHint()
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_rewatt)
            .setContentTitle(getString(R.string.perception_notification_title))
            .setContentText(getString(R.string.perception_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.perception_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        started = false
        armed = false
        runCatching { alarmManager.cancel(capturePendingIntent()) }
        monitor?.abort()
        monitor = null
        energyMonitor?.stop()
        energyMonitor = null
        appGraph.intrusionAlarm.dismiss()
        if (appGraph.torchRouter.controller === cameraController) appGraph.torchRouter.controller = null
        cameraController?.close()
        cameraController = null
        appGraph.yoloDetector = null
        yoloDetector?.close()
        yoloDetector = null
        serviceScope.cancel()
        stateHolder.update {
            it.copy(isRunning = false, statusMessage = "Perception is stopped.")
        }
        appGraph.securityStateHolder.update {
            it.copy(phase = com.rewatt.app.security.EventPhase.IDLE, activeEventId = null, recording = false, torchOn = false)
        }
        super.onDestroy()
    }

    private fun foregroundServiceType(includeMicrophone: Boolean): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (
            includeMicrophone &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return type
    }

    companion object {
        private const val TAG = "CameraCaptureService"
        private const val NOTIFICATION_CHANNEL_ID = "rewatt_perception"
        private const val NOTIFICATION_ID = 73
        private const val CAPTURE_REQUEST_CODE = 71

        const val ACTION_CAPTURE = "com.rewatt.app.action.CAPTURE"
        const val ACTION_CAPTURE_NOW = "com.rewatt.app.action.CAPTURE_NOW"
        const val ACTION_STOP_PERCEPTION = "com.rewatt.app.action.STOP_PERCEPTION"
        const val ACTION_ARM = "com.rewatt.app.action.CCTV_ARM"
        const val ACTION_DISARM = "com.rewatt.app.action.CCTV_DISARM"
        const val ACTION_DISMISS_ALARM = "com.rewatt.app.action.CCTV_DISMISS_ALARM"
        const val ACTION_RELEASE_CAMERA = "com.rewatt.app.action.RELEASE_CAMERA"
        const val ACTION_RESUME_CAMERA = "com.rewatt.app.action.RESUME_CAMERA"
        const val ACTION_TEST_CHECK = "com.rewatt.app.action.CCTV_TEST_CHECK"

        fun testCheckIntent(context: Context): Intent = actionIntent(context, ACTION_TEST_CHECK)

        fun startIntent(context: Context): Intent =
            Intent(context, CameraCaptureService::class.java)

        fun captureNowIntent(context: Context): Intent = actionIntent(context, ACTION_CAPTURE_NOW)

        fun stopPerceptionIntent(context: Context): Intent = actionIntent(context, ACTION_STOP_PERCEPTION)

        fun armIntent(context: Context): Intent = actionIntent(context, ACTION_ARM)

        fun disarmIntent(context: Context): Intent = actionIntent(context, ACTION_DISARM)

        fun dismissAlarmIntent(context: Context): Intent = actionIntent(context, ACTION_DISMISS_ALARM)

        fun releaseCameraIntent(context: Context): Intent = actionIntent(context, ACTION_RELEASE_CAMERA)

        fun resumeCameraIntent(context: Context): Intent = actionIntent(context, ACTION_RESUME_CAMERA)

        private fun actionIntent(context: Context, action: String): Intent =
            Intent(context, CameraCaptureService::class.java).setAction(action)
    }
}
