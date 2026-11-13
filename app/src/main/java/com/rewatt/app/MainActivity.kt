package com.rewatt.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Preview
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rewatt.app.perception.CaptureStartSource
import com.rewatt.app.perception.PerceptionViewModel
import com.rewatt.app.perception.PerceptionViewModelFactory
import com.rewatt.app.security.InAppAlarmOverlay
import com.rewatt.app.ui.SetupGuideDialog
import com.rewatt.app.ui.security.SecuritySettingsSection
import com.rewatt.app.ui.security.SecurityHistorySection
import com.rewatt.app.ui.security.SecuritySidePanel
import com.rewatt.app.ui.security.SecurityViewModel
import com.rewatt.app.ui.security.SecurityViewModelFactory
import com.rewatt.app.ui.sensor.SecurityTabParts
import com.rewatt.app.ui.sensor.SensorScreen
import com.rewatt.app.ui.sensor.SensorViewModel
import com.rewatt.app.ui.sensor.SensorViewModelFactory
import com.rewatt.app.ui.sensor.SmartPlugSettingsSection
import com.rewatt.app.ui.theme.ReWattTheme
import com.rewatt.app.usb.UsbSentinelService

class MainActivity : AppCompatActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startUsbService()
        }
    private val perceptionPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA] == true ||
                hasPermission(Manifest.permission.CAMERA)
            perceptionViewModel.setPermissionState(cameraGranted)
            if (cameraGranted) {
                perceptionViewModel.startPerception(pendingCaptureSource ?: CaptureStartSource.Manual)
            }
            pendingCaptureSource = null
        }

    private var pendingCaptureSource: CaptureStartSource? = null

    // CCTV: arming needs the camera; the microphone is optional (clips are silent without it).
    private val securityArmPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasPermission(Manifest.permission.CAMERA)) securityViewModel.arm()
        }
    private val securityCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val securityViewModel: SecurityViewModel by viewModels {
        SecurityViewModelFactory(
            appContext = applicationContext,
            graph = (application as ReWattApplication).appGraph,
        )
    }

    private val sensorViewModel: SensorViewModel by viewModels {
        val graph = (application as ReWattApplication).appGraph
        SensorViewModelFactory(
            appContext = applicationContext,
            telemetryRepository = graph.telemetryRepository,
            mqttRepository = graph.mqttSyncRepository,
            perceptionStateHolder = graph.perceptionStateHolder,
            lightsController = graph.lightsController,
        )
    }

    private val perceptionViewModel: PerceptionViewModel by viewModels {
        PerceptionViewModelFactory(
            appContext = applicationContext,
            stateHolder = (application as ReWattApplication).appGraph.perceptionStateHolder,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Auto-off (the energy saver) is on every time the app is opened; a rotation keeps the user's choice.
        if (savedInstanceState == null) {
            (application as ReWattApplication).appGraph.lightsController.setAutoOff(true)
        }
        requestNotificationPermissionIfNeeded()
        perceptionViewModel.setPermissionState(
            cameraGranted = hasPermission(Manifest.permission.CAMERA),
        )
        // Security starts DISARMED every time the app is opened (the user arms it on the SECURITY tab).
        // A rotation keeps whatever the user chose.
        if (savedInstanceState == null && (application as ReWattApplication).appGraph.securitySettings.armed) {
            securityViewModel.disarm()
        }
        // ENERGY tab: the live camera view and the motion → photo → YOLO occupancy flow need the
        // camera service, so start it whenever the app opens (only if the camera is already allowed).
        if (hasPermission(Manifest.permission.CAMERA)) {
            perceptionViewModel.startPerception(CaptureStartSource.Auto)
        }

        setContent {
            ReWattTheme {
                val uiState by sensorViewModel.uiState.collectAsStateWithLifecycle()
                val securityState by securityViewModel.state.collectAsStateWithLifecycle()
                val appGraph = (application as ReWattApplication).appGraph
                val fireAlarm by appGraph.fireMonitor.alarm.state.collectAsStateWithLifecycle()
                val energyState by appGraph.energyStateHolder.state.collectAsStateWithLifecycle()
                val lightsState by appGraph.lightsController.state.collectAsStateWithLifecycle()
                val previewZoom by appGraph.livePreviewHub.zoomRatio.collectAsStateWithLifecycle()
                val onLivePreviewSurface: (Preview.SurfaceProvider, Boolean) -> Unit = { provider, attached ->
                    if (attached) appGraph.livePreviewHub.attach(provider) else appGraph.livePreviewHub.detach(provider)
                }
                // The Quick Setup Guide opens by itself the first time the app is launched.
                val appPrefs = remember { getSharedPreferences(APP_PREFS, MODE_PRIVATE) }
                var showSetupGuide by remember { mutableStateOf(!appPrefs.getBoolean(KEY_SETUP_GUIDE_SEEN, false)) }

                SensorScreen(
                    uiState = uiState,
                    onScanUsb = ::startUsbService,
                    onStartPerception = ::requestPerceptionPermissionsThenStart,
                    onStopPerception = perceptionViewModel::stopPerception,
                    onDebugCapture = perceptionViewModel::captureNow,
                    onSendPhotoMqtt = sensorViewModel::publishCurrentPhoto,
                    onUpdateMqttConfig = sensorViewModel::updateMqttConfig,
                    onOpenSetupGuide = { showSetupGuide = true },
                    securityState = securityState,
                    fireAlarmState = fireAlarm,
                    energyState = energyState,
                    onLivePreviewSurface = onLivePreviewSurface,
                    onLiveFrame = appGraph.liveHeadcount::analyze,
                    previewZoom = previewZoom,
                    onPreviewZoom = appGraph.livePreviewHub::zoomBy,
                    onDismissPhoto = { appGraph.energyStateHolder.update { it.copy(photoPinned = false) } },
                    lightsState = lightsState,
                    lightsController = appGraph.lightsController,
                    securityParts = SecurityTabParts(
                        armed = securityState.armed,
                        side = { compact ->
                            SecuritySidePanel(
                                viewModel = securityViewModel,
                                onRequestArm = ::requestSecurityPermissionsThenArm,
                                compact = compact,
                            )
                        },
                        below = { compact -> SecurityHistorySection(securityViewModel, compact) },
                    ),
                    settingsSecurityContent = {
                        SecuritySettingsSection(
                            viewModel = securityViewModel,
                            onRequestMicrophone = { securityCameraPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                            onRequestCamera = { securityCameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                            onTestFireAlarm = { appGraph.fireMonitor.alarm.trigger(vocIndex = null, noxIndex = null, test = true) },
                        )
                    },
                    settingsSmartPlugContent = { SmartPlugSettingsSection(appGraph.tapoLink) },
                )

                // Fire / intruder alarm: in-app full-screen page on top of everything (no extra
                // activity → no lag before the siren; follows the current orientation).
                InAppAlarmOverlay(appGraph)

                if (showSetupGuide) {
                    SetupGuideDialog(onDismiss = { name ->
                        showSetupGuide = false
                        appPrefs.edit().putBoolean(KEY_SETUP_GUIDE_SEEN, true).apply()
                        if (name != null) {
                            sensorViewModel.updateMqttConfig(
                                sensorViewModel.mqttRepository.host,
                                sensorViewModel.mqttRepository.port,
                                sensorViewModel.mqttRepository.username,
                                sensorViewModel.mqttRepository.password,
                                name,
                            )
                        }
                    })
                }
            }
        }
    }

    // While the app is on screen, alarms use the in-app page instead of a full-screen notification.
    override fun onStart() {
        super.onStart()
        (application as ReWattApplication).appGraph.intrusionNotifier.appInForeground = true
    }

    override fun onStop() {
        (application as ReWattApplication).appGraph.intrusionNotifier.appInForeground = false
        super.onStop()
    }

    // Plugging in the module (USB_DEVICE_ATTACHED) reuses this screen instead of stacking a second
    // dashboard on top (which used to steal and freeze the live camera view).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startUsbService()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startUsbService()
        }
    }

    private fun startUsbService() {
        ContextCompat.startForegroundService(
            this,
            UsbSentinelService.createStartIntent(this),
        )
    }

    private fun requestPerceptionPermissionsThenStart(source: CaptureStartSource) {
        pendingCaptureSource = source
        val cameraGranted = hasPermission(Manifest.permission.CAMERA)
        perceptionViewModel.setPermissionState(cameraGranted)

        if (cameraGranted) {
            pendingCaptureSource = null
            perceptionViewModel.startPerception(source)
            return
        }

        perceptionPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
    }

    private fun requestSecurityPermissionsThenArm() {
        val needed = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filterNot(::hasPermission)
        if (needed.isEmpty()) {
            securityViewModel.arm()
        } else {
            securityArmPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val APP_PREFS = "rewatt_app_prefs"
        const val KEY_SETUP_GUIDE_SEEN = "setup_guide_seen"
    }
}
