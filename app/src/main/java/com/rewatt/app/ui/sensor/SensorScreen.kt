package com.rewatt.app.ui.sensor

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.provider.Settings
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.TextStyle
import com.rewatt.app.BuildConfig
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rewatt.app.ReWattApplication
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rewatt.app.R
import com.rewatt.app.data.MqttStatus
import com.rewatt.app.energy.LightsController
import com.rewatt.app.energy.LightsState
import com.rewatt.app.energy.EnergyBanner
import com.rewatt.app.energy.EnergyMonitor
import com.rewatt.app.energy.EnergyPhase
import com.rewatt.app.energy.EnergyUiState
import com.rewatt.app.perception.CaptureStartSource
import com.rewatt.app.perception.LiveHeadcount
import com.rewatt.app.perception.ObjectDetection
import com.rewatt.app.perception.PerceptionUiState
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import com.rewatt.app.safety.FireAlarmState
import com.rewatt.app.security.EventPhase
import com.rewatt.app.security.SecurityUiState
import com.rewatt.app.ui.security.LiveFaceOverlay
import com.rewatt.app.telemetry.FireThreshold
import com.rewatt.app.telemetry.GasLevel
import com.rewatt.app.telemetry.GasReading
import com.rewatt.app.telemetry.noxLevel
import com.rewatt.app.telemetry.vocLevel
import com.rewatt.app.usb.UsbConnectionStatus

private enum class StitchTab(val label: String) {
    ENERGY("ENERGY"),
    FIRE("FIRE"),
    SECURITY("SECURITY"),
    SETTINGS("SETTINGS"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorScreen(
    uiState: SensorNodeUiState,
    onScanUsb: () -> Unit,
    onStartPerception: (CaptureStartSource) -> Unit,
    onStopPerception: () -> Unit,
    onDebugCapture: () -> Unit,
    onSendPhotoMqtt: () -> Unit,
    onUpdateMqttConfig: (String, Int, String, String, String) -> Unit,
    onOpenSetupGuide: () -> Unit,
    modifier: Modifier = Modifier,
    securityState: SecurityUiState = SecurityUiState(),
    /** SECURITY tab pieces placed around the camera box it shares with ENERGY. */
    securityParts: SecurityTabParts = SecurityTabParts(),
    fireAlarmState: FireAlarmState = FireAlarmState(),
    settingsSecurityContent: @Composable () -> Unit = {},
    /** Settings: the Room 1 lights' smart plug (login / IP backup). */
    settingsSmartPlugContent: @Composable () -> Unit = {},
    energyState: EnergyUiState = EnergyUiState(),
    onLivePreviewSurface: (Preview.SurfaceProvider, Boolean) -> Unit = { _, _ -> },
    /** Live headcount: called with each frame the camera box is showing (see [LivePreview]). */
    onLiveFrame: (suspend (Bitmap) -> Unit)? = null,
    onDismissPhoto: () -> Unit = {},
    /** Room lights + aircond (Room card icons, Schedule / Auto-off, occupancy-card countdown). */
    lightsState: LightsState = LightsState(),
    lightsController: LightsController? = null,
    /** Real camera zoom ratio shown on the live previews; pinch calls [onPreviewZoom] with a factor. */
    previewZoom: Float = 1f,
    onPreviewZoom: (Float) -> Unit = {},
) {
    var selectedTab by rememberSaveable { mutableStateOf(StitchTab.ENERGY) }
    var landscapeScheduleOpen by remember { mutableStateOf(false) }

    // Every tab follows the phone into landscape.
    val activity = LocalContext.current.findActivity()
    LaunchedEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
    }

    // Landscape also hides the phone's status bar (swipe down from the top edge to peek at it).
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val view = LocalView.current
    LaunchedEffect(landscape) {
        val window = activity?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, view).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (landscape) hide(WindowInsetsCompat.Type.statusBars()) else show(WindowInsetsCompat.Type.statusBars())
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color(0xFFF9FAFB),
        topBar = {
            // Landscape: no header — the content (camera, fire status, settings) gets the height.
            if (!landscape) {
                StitchHeader(
                    moduleOnline = uiState.sensorState.connectionStatus == UsbConnectionStatus.Connected,
                )
            }
        },
        bottomBar = {
            StitchBottomBar(
                selectedTab = selectedTab,
                onTabSelected = {
                    // Freeze every visible preview first, so the next tab can show that frame
                    // until its own camera stream is rendering (no black flash).
                    if (it != selectedTab) PreviewFrameCache.captureAll()
                    selectedTab = it
                },
                // Landscape ENERGY has no Room card: its Schedule / Auto-off sit beside the menu.
                leading = if (landscape && selectedTab == StitchTab.ENERGY && lightsController != null) {
                    {
                        LightsMenuPill(
                            lights = lightsState,
                            onOpenSchedule = { landscapeScheduleOpen = true },
                            onToggleAutoOff = { lightsController.setAutoOff(!lightsState.autoOff) },
                        )
                    }
                } else {
                    null
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // ENERGY and SECURITY are ONE layout sharing ONE live camera box: switching between
            // them only swaps the panels around it, so the preview never stops or reloads.
            when (selectedTab) {
                StitchTab.ENERGY, StitchTab.SECURITY -> CameraTabs(
                    tab = selectedTab,
                    uiState = uiState,
                    securityState = securityState,
                    energyState = energyState,
                    lightsState = lightsState,
                    lightsController = lightsController,
                    security = securityParts,
                    onLivePreviewSurface = onLivePreviewSurface,
                    onLiveFrame = onLiveFrame,
                    onStartCamera = { onStartPerception(CaptureStartSource.Manual) },
                    onDismissPhoto = onDismissPhoto,
                    previewZoom = previewZoom,
                    onPreviewZoom = onPreviewZoom,
                )
                StitchTab.FIRE -> ReadableWidth {
                    StitchFireTab(
                        uiState = uiState,
                        fireAlarmState = fireAlarmState,
                    )
                }
                StitchTab.SETTINGS -> ReadableWidth {
                    StitchSettingsTab(
                        uiState = uiState,
                        onUpdateMqttConfig = onUpdateMqttConfig,
                        onOpenSetupGuide = onOpenSetupGuide,
                        securitySettingsContent = settingsSecurityContent,
                        smartPlugContent = settingsSmartPlugContent,
                        diagnosticsContent = {
                            DiagnosticsSection(
                                uiState = uiState,
                                onScanUsb = onScanUsb,
                                onStartPerception = onStartPerception,
                                onStopPerception = onStopPerception,
                                onDebugCapture = onDebugCapture,
                                onSendPhotoMqtt = onSendPhotoMqtt,
                            )
                        },
                    )
                }
            }

            // In-app banners from the ENERGY flow (shown on every tab).
            EnergyBannerHost(
                banner = energyState.banner,
                roomName = uiState.roomId.ifBlank { "Room1" },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
    if (landscapeScheduleOpen && lightsController != null) {
        LightsScheduleSheet(lights = lightsState, controller = lightsController, onDismiss = { landscapeScheduleOpen = false })
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

// ── TOP HEADER BAR ────────────────────────────────────────────────────────────

@Composable
private fun StitchHeader(moduleOnline: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF9FAFB))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // App Logo + Title (Capitalized ReWatt)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_app_logo),
                contentDescription = "ReWatt Logo",
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "ReWatt",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF09090B),
            )
        }

        // Online Pill Status Badge — the sensor module (USB) connection
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (pillBg, pillText, label) = if (moduleOnline) {
                Triple(Color(0xFFDCFCE7), Color(0xFF15803D), "ONLINE")
            } else {
                Triple(Color(0xFFF3F4F6), Color(0xFF6B7280), "OFFLINE")
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(pillBg)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(pillText),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = pillText,
                    )
                }
            }
        }
    }
}

// ── BOTTOM NAVIGATION BAR ─────────────────────────────────────────────────────

/**
 * Floating pill menu (iOS 26/27 style): a rounded, slightly see-through bar lifted off the bottom
 * edge with a soft shadow. The selected tab sits in its own inner pill, which slides to the tapped
 * tab. Takes the same height as the old full-width bar, so the content area doesn't shrink.
 */
@Composable
private fun StitchBottomBar(
    selectedTab: StitchTab,
    onTabSelected: (StitchTab) -> Unit,
    /** Shown just left of the menu, in the same floating-pill style (ENERGY in landscape). */
    leading: (@Composable () -> Unit)? = null,
) {
    val pill = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            leading?.invoke()
            BoxWithConstraints(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .height(BOTTOM_BAR_HEIGHT)
                    .floatingPill(),
            ) {
                val tabs = StitchTab.values()
                val slot = maxWidth / tabs.size
                val indicatorX by animateDpAsState(
                    targetValue = slot * selectedTab.ordinal,
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
                    label = "tabIndicator",
                )
                // The sliding inner pill behind the selected tab.
                Box(
                    Modifier
                        .offset(x = indicatorX)
                        .width(slot)
                        .fillMaxHeight()
                        .clip(pill)
                        .background(Color(0xFFEDEFF2)),
                )
                Row(Modifier.fillMaxSize()) {
                    tabs.forEach { tab ->
                        val selected = tab == selectedTab
                        val tint by animateColorAsState(
                            if (selected) Color(0xFF09090B) else Color(0xFF9CA3AF),
                            label = "tabTint",
                        )
                        val icon = when (tab) {
                            StitchTab.ENERGY -> Icons.Outlined.Bolt
                            StitchTab.FIRE -> Icons.Outlined.LocalFireDepartment
                            StitchTab.SECURITY -> Icons.Outlined.Shield
                            StitchTab.SETTINGS -> Icons.Outlined.Settings
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(pill)
                                .selectable(
                                    selected = selected,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Tab,
                                    onClick = { onTabSelected(tab) },
                                ),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = tab.label,
                                fontSize = 9.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                letterSpacing = 0.3.sp,
                                color = tint,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Height of the floating menu (and the pill beside it in landscape). */
internal val BOTTOM_BAR_HEIGHT = 58.dp

/** The floating menu's look: white, slightly see-through, soft shadow, hairline edge. */
internal fun Modifier.floatingPill(): Modifier {
    val pill = RoundedCornerShape(50)
    return this
        .shadow(elevation = 14.dp, shape = pill, ambientColor = Color(0x22000000), spotColor = Color(0x33000000))
        .clip(pill)
        .background(Color.White.copy(alpha = 0.94f))
        .border(0.5.dp, Color(0x14000000), pill)
        .padding(4.dp)
}

// ── TAB 1: MAIN ───────────────────────────────────────────────────────────────

/**
 * Tiny CCTV shield after the room name. Disarmed: outlined grey. Armed: solid red-gradient shield
 * plus "ARMED", and the live state ("· Motion" / "· Recording") while an event is running; the text
 * is cut short ("…") rather than pushing the card wider.
 */
@Composable
private fun SecurityShieldBadge(security: SecurityUiState, modifier: Modifier = Modifier) {
    val armedRed = Color(0xFFB91C1C)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (security.armed) {
            Icon(
                imageVector = Icons.Filled.Shield,
                contentDescription = "Armed",
                tint = Color.Black, // replaced by the gradient below
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithCache {
                        val gradient = Brush.verticalGradient(listOf(Color(0xFFF87171), armedRed))
                        onDrawWithContent {
                            drawContent()
                            drawRect(gradient, blendMode = BlendMode.SrcAtop)
                        }
                    },
            )
            Spacer(Modifier.width(4.dp))
            val live = when {
                security.recording -> " · Recording"
                security.phase != EventPhase.IDLE -> " · Motion"
                else -> ""
            }
            Text(
                text = "ARMED$live",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = armedRed,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Shield,
                contentDescription = "Disarmed",
                tint = Color(0xFF9CA3AF),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * VOC Index and NOx Index gauges (Sensirion Gas Index values — unitless, 0..500). They show "--"
 * when there is no recent reading (no SGP41 on the module) or while the sensor warms up. Cards turn
 * red above the fire threshold or while the fire alarm is active.
 */
@Composable
private fun GasGaugeRow(
    gas: GasReading?,
    nowMillis: Long,
    fireAlarmActive: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val fresh = gas?.takeIf { nowMillis - it.capturedAtMillis <= FireThreshold.STALE_AFTER_MS && !it.isWarmingUp }
    val voc = fresh?.vocIndex
    val nox = fresh?.noxIndex
    // Landscape ([compact]): the cards fill the row's height, content centred.
    val cardModifier = if (compact) Modifier.fillMaxHeight() else Modifier
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
    ) {
        GasGaugeCard(
            label = "VOC INDEX",
            value = voc,
            level = voc?.let(::vocLevel),
            colors = listOf(Color(0xFF8B5CF6), Color(0xFFEC4899)),
            fireAlarmActive = fireAlarmActive,
            compact = compact,
            modifier = Modifier.weight(1f).then(cardModifier),
        )
        GasGaugeCard(
            label = "NOx INDEX",
            value = nox,
            level = nox?.let(::noxLevel),
            colors = listOf(Color(0xFFF59E0B), Color(0xFFEA580C)),
            fireAlarmActive = fireAlarmActive,
            compact = compact,
            modifier = Modifier.weight(1f).then(cardModifier),
        )
    }
}

/** [compact] (landscape): the card fills its height with the label and ring centred in it. */
@Composable
private fun GasGaugeCard(
    label: String,
    value: Int?,
    level: GasLevel?,
    colors: List<Color>,
    fireAlarmActive: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val shownLevel = if (fireAlarmActive) GasLevel.DANGER else level
    val high = shownLevel == GasLevel.DANGER
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = if (high) Color(0xFFFEE2E2) else Color(0xFFECEEEF)),
    ) {
        Column(
            modifier = Modifier
                .then(if (compact) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                .padding(if (compact) 14.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (high) Color(0xFFB91C1C) else Color(0xFF374151),
                letterSpacing = 0.5.sp,
            )
            // Landscape: label at the top like the status card, ring centred in the space below.
            if (compact) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(16.dp))
            // The traffic-light pill sits in the ring's open bottom gap, so the card keeps exactly the
            // same height as the Temperature/Humidity cards.
            Box(contentAlignment = Alignment.BottomCenter) {
                StitchRingGauge(
                    value = (value ?: 0).toFloat(),
                    maxValue = GAS_INDEX_MAX,
                    centerText = value?.toString() ?: "--",
                    subText = if (value != null) "Index" else null,
                    gradientColors = if (high) listOf(Color(0xFFF87171), Color(0xFFB91C1C)) else colors,
                    isDataAvailable = value != null,
                )
                BreathingTrafficPill(level = shownLevel, modifier = Modifier.padding(bottom = 8.dp))
            }
            if (compact) Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * Small traffic-light pill: green = safe, amber = caution, red = danger. It softly "breathes"
 * (opacity only), a little faster as the level rises. Grey and still when there is no reading.
 */
@Composable
private fun BreathingTrafficPill(level: GasLevel?, modifier: Modifier = Modifier) {
    val color = when (level) {
        GasLevel.SAFE -> Color(0xFF22C55E)
        GasLevel.CAUTION -> Color(0xFFF59E0B)
        GasLevel.DANGER -> Color(0xFFEF4444)
        null -> Color(0xFFD1D5DB)
    }
    val alpha = if (level == null) {
        1f
    } else {
        val periodMs = when (level) {
            GasLevel.DANGER -> 700
            GasLevel.CAUTION -> 1_200
            GasLevel.SAFE -> 2_400
        }
        val breath by rememberInfiniteTransition(label = "gasBreath").animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(periodMs, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "gasBreathAlpha",
        )
        breath
    }
    Box(
        modifier = modifier
            .size(width = 22.dp, height = 4.dp)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = alpha)),
    )
}

private const val GAS_INDEX_MAX = 500f

/**
 * Keeps a scrolling tab (FIRE, SETTINGS) at a comfortable reading width: full width in portrait,
 * centred and at most [READABLE_MAX_WIDTH] wide in landscape.
 */
@Composable
private fun ReadableWidth(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .widthIn(max = READABLE_MAX_WIDTH)
                .fillMaxSize(),
        ) { content() }
    }
}

private val READABLE_MAX_WIDTH = 640.dp

// ── TAB 2: FIRE ───────────────────────────────────────────────────────────────

/** What the VOC / NOx readings say right now; [level] null = no fresh reading (module off, warming up). */
private data class GasStatus(val level: GasLevel?, val voc: Int?, val nox: Int?, val warmingUp: Boolean)

private fun gasStatus(gas: GasReading?, nowMillis: Long, fireAlarmActive: Boolean): GasStatus {
    val recent = gas?.takeIf { nowMillis - it.capturedAtMillis <= FireThreshold.STALE_AFTER_MS }
    val fresh = recent?.takeIf { !it.isWarmingUp }
    val voc = fresh?.vocIndex
    val nox = fresh?.noxIndex
    val worst = listOfNotNull(voc?.let(::vocLevel), nox?.let(::noxLevel)).maxByOrNull { it.ordinal }
    return GasStatus(
        level = if (fireAlarmActive) GasLevel.DANGER else worst,
        voc = voc,
        nox = nox,
        warmingUp = recent?.isWarmingUp == true,
    )
}

/**
 * Debug builds only: show the FIRE tab with sample readings, to see each state's design without a
 * real gas leak. Start with `adb shell am start -S -n com.rewatt.app/.MainActivity --es preview_gas
 * caution` (or `danger` / `normal`). Display only — the real alarm never sees these values.
 */
@Composable
private fun rememberGasPreview(nowMillis: Long): GasReading? {
    val activity = LocalContext.current.findActivity()
    val kind = remember { activity?.intent?.getStringExtra("preview_gas")?.takeIf { BuildConfig.DEBUG } } ?: return null
    return when (kind) {
        "danger" -> GasReading(vocIndex = 146, noxIndex = 6, capturedAtMillis = nowMillis)
        "caution" -> GasReading(vocIndex = 121, noxIndex = 1, capturedAtMillis = nowMillis)
        "normal" -> GasReading(vocIndex = 98, noxIndex = 1, capturedAtMillis = nowMillis)
        else -> null
    }
}

/**
 * FIRE tab. Portrait: title, the gas status card, then the VOC / NOx gauges. Landscape: one row of
 * three equal, full-height cards — status, VOC, NOx — so everything shows at once. The status comes
 * from the readings themselves (the alarm itself is the full-screen page).
 */
@Composable
private fun StitchFireTab(
    uiState: SensorNodeUiState,
    fireAlarmState: FireAlarmState,
) {
    val alarm = fireAlarmState.active
    val preview = rememberGasPreview(uiState.nowMillis)
    val gas = preview ?: uiState.sensorState.latestGas
    val status = gasStatus(gas, uiState.nowMillis, alarm)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GasStatusCard(
                    status = status,
                    preview = preview != null,
                    tall = true,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                GasGaugeRow(
                    gas = gas,
                    nowMillis = uiState.nowMillis,
                    fireAlarmActive = alarm,
                    compact = true,
                    modifier = Modifier
                        .weight(2f)
                        .fillMaxHeight(),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Fire Safety", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF09090B))
                GasStatusCard(status = status, preview = preview != null, tall = false, modifier = Modifier.fillMaxWidth())
                // VOC / NOx gauges with traffic-light pills ("--" when the module has no SGP41)
                GasGaugeRow(gas = gas, nowMillis = uiState.nowMillis, fireAlarmActive = alarm)
            }
        }
    }
}

private data class GasStatusLook(
    val title: String,
    val color: Color,
    val iconBackground: Color,
    val cardColor: Color,
    val fire: FireIntensity,
)

private fun GasStatus.look(): GasStatusLook = when (level) {
    null -> GasStatusLook(if (warmingUp) "WARMING UP" else "NO DATA", Color(0xFF6B7280), Color(0xFFE5E7EB), Color(0xFFECEEEF), FireIntensity.NONE)
    GasLevel.SAFE -> GasStatusLook("NORMAL", Color(0xFF15803D), Color(0xFFDCFCE7), Color(0xFFECEEEF), FireIntensity.NONE)
    GasLevel.CAUTION -> GasStatusLook("CAUTION", Color(0xFFB45309), Color(0xFFFDE68A), Color(0xFFFFF7ED), FireIntensity.CAUTION)
    GasLevel.DANGER -> GasStatusLook("FIRE RISK", Color(0xFFB91C1C), Color(0xFFB91C1C), Color(0xFFFEE2E2), FireIntensity.DANGER)
}

/**
 * The FIRE tab's status card, from the VOC / NOx readings (the numbers are on the gauges beside it):
 * NO DATA (grey), NORMAL (green), CAUTION (amber, low flickering flames along the bottom) or FIRE RISK
 * (red, tall flames, pulsing red edge and icon). [tall]: the landscape card, icon and word stacked in
 * the middle.
 */
@Composable
private fun GasStatusCard(status: GasStatus, preview: Boolean, tall: Boolean, modifier: Modifier = Modifier) {
    val look = status.look()
    val danger = look.fire == FireIntensity.DANGER
    val abnormal = look.fire != FireIntensity.NONE
    val cardColor by animateColorAsState(look.cardColor, label = "gasCard")
    val pulse by rememberInfiniteTransition(label = "gasPulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "gasPulseValue",
    )
    val shape = RoundedCornerShape(24.dp)
    Card(
        modifier = modifier.then(
            if (danger) Modifier.border(2.dp, Color(0xFFDC2626).copy(alpha = pulse), shape) else Modifier,
        ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = cardColor),
    ) {
        Box(
            Modifier
                .then(if (tall) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                .fireEffect(look.fire),
        ) {
            val icon: @Composable () -> Unit = {
                Box(
                    modifier = Modifier
                        .size(if (tall) 48.dp else 44.dp)
                        .scale(if (danger) 0.92f + 0.12f * pulse else 1f)
                        .clip(CircleShape)
                        .background(look.iconBackground),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.LocalFireDepartment,
                        contentDescription = null,
                        tint = if (danger) Color.White else look.color,
                        modifier = Modifier.size(if (tall) 26.dp else 24.dp),
                    )
                }
            }
            val title: @Composable () -> Unit = {
                Text(look.title, fontSize = if (tall) 20.sp else 22.sp, fontWeight = FontWeight.Black, color = look.color, maxLines = 1)
            }
            val rule: @Composable () -> Unit = {
                Text(
                    "Alarm at VOC ≥ ${FireThreshold.VOC_INDEX} or NOx ≥ ${FireThreshold.NOX_INDEX}",
                    fontSize = 10.sp,
                    color = Color(0xFF9CA3AF),
                    textAlign = if (tall) TextAlign.Center else TextAlign.Start,
                )
            }

            if (tall) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("STATUS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF374151), letterSpacing = 0.5.sp)
                    Spacer(Modifier.weight(1f))
                    icon()
                    Spacer(Modifier.height(8.dp))
                    title()
                    Spacer(Modifier.weight(1f))
                    // The flames fill the bottom when abnormal; the rule shows when all is calm.
                    if (!abnormal) rule()
                }
            } else {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = if (abnormal) 52.dp else 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        icon()
                        Spacer(Modifier.width(14.dp))
                        title()
                    }
                    if (!abnormal) {
                        Spacer(Modifier.height(8.dp))
                        rule()
                    }
                }
            }
            if (preview) {
                Text(
                    "PREVIEW",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xCC09090B))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

// ── TAB 1: ENERGY ─────────────────────────────────────────────────────────────


/**
 * Two-finger pinch on a live preview → [onZoom] with the zoom factor of each step. One-finger taps
 * and long-presses pass through untouched (only multi-touch moves are consumed).
 */
internal fun Modifier.pinchToZoom(onZoom: (Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                val zoom = event.calculateZoom()
                if (zoom != 1f) {
                    onZoom(zoom)
                    event.changes.forEach { it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

/** "2.0×" chip shown on a live preview while the camera is zoomed. */
@Composable
internal fun ZoomChip(zoomRatio: Float, modifier: Modifier = Modifier) {
    if (abs(zoomRatio - 1f) < 0.05f) return
    Box(
        modifier = modifier
            .padding(10.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0x99000000))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text("%.1f×".format(zoomRatio), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

/** What the SECURITY tab contributes around the camera box it shares with ENERGY (from MainActivity). */
data class SecurityTabParts(
    val armed: Boolean = false,
    /** Title + arm card; `compact` = the slim landscape column. */
    val side: @Composable (compact: Boolean) -> Unit = {},
    /** Event history: below the camera (portrait), or under the arm card, simplified (`compact`, landscape). */
    val below: @Composable (compact: Boolean) -> Unit = {},
)

/**
 * ENERGY and SECURITY as ONE layout around ONE camera box. Only the panels change with [tab]; the
 * live camera box is the same composable at the same place, so switching between the two tabs never
 * stops, reloads or moves it.
 *  - Portrait: tab panel on top → camera → tab content below (SECURITY: event history; the
 *    illuminance card lives in Settings → Diagnostics).
 *  - Landscape: tab panel in a left column (20%) → camera filling the rest (80%), the same size in
 *    both tabs (SECURITY: arm card with the event log under it).
 */
@Composable
private fun CameraTabs(
    tab: StitchTab,
    uiState: SensorNodeUiState,
    securityState: SecurityUiState,
    energyState: EnergyUiState,
    lightsState: LightsState,
    lightsController: LightsController?,
    security: SecurityTabParts,
    onLivePreviewSurface: (Preview.SurfaceProvider, Boolean) -> Unit,
    onLiveFrame: (suspend (Bitmap) -> Unit)?,
    onStartCamera: () -> Unit,
    onDismissPhoto: () -> Unit,
    previewZoom: Float,
    onPreviewZoom: (Float) -> Unit,
) {
    val energyTab = tab == StitchTab.ENERGY
    val usbConnected = uiState.sensorState.connectionStatus == UsbConnectionStatus.Connected
    val telemetry = uiState.sensorState.latestTelemetry
    val temp = telemetry?.temperatureC
    val hum = telemetry?.humidityPercent
    val tempAvailable = temp != null && usbConnected
    val humAvailable = hum != null && usbConnected
    val liveCount = uiState.perceptionState.liveCount(uiState.nowMillis)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val camera: @Composable (Modifier) -> Unit = { modifier ->
            SharedCameraBox(
                energyTab = energyTab,
                perception = uiState.perceptionState,
                energy = energyState,
                onLivePreviewSurface = onLivePreviewSurface,
                onStartCamera = onStartCamera,
                onDismissPhoto = onDismissPhoto,
                onLiveFrame = onLiveFrame,
                nowMillis = uiState.nowMillis,
                roomChip = uiState.roomId.ifBlank { "Room1" }.takeIf { landscape && energyTab },
                moduleOnline = usbConnected,
                armed = security.armed && !energyTab,
                securityState = securityState,
                zoomRatio = previewZoom,
                onZoom = onPreviewZoom,
                modifier = modifier,
            )
        }

        if (landscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Both tabs: the same 20% / 80% split, so the camera is the same size in each.
                Column(
                    modifier = Modifier
                        .weight(0.2f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (energyTab) {
                        WideGaugeBox(
                            label = "TEMP",
                            value = temp ?: 0f,
                            maxValue = 50f,
                            centerText = if (temp != null) "${temp.toInt()}°" else "--",
                            subText = null,
                            colors = listOf(Color(0xFF2563EB), Color(0xFF00E676)),
                            available = tempAvailable,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                        WideGaugeBox(
                            label = "HUMIDITY",
                            value = hum ?: 0f,
                            maxValue = 100f,
                            centerText = if (hum != null) "${hum.toInt()}" else "--",
                            subText = if (hum != null) "%" else null,
                            colors = listOf(Color(0xFF06B6D4), Color(0xFF14B8A6)),
                            available = humAvailable,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                        OccupancyCard(
                            energy = energyState,
                            nowMillis = uiState.nowMillis,
                            lights = lightsState,
                            onToggleLights = { lightsController?.switchLights(lightsState.lightsOn != true) },
                            onToggleAircond = { lightsController?.toggleAircond() },
                            onToggleFan = { lightsController?.toggleFan() },
                            liveCount = liveCount,
                            wide = true,
                            modifier = Modifier
                                .weight(2f)
                                .fillMaxWidth(),
                        )
                    } else {
                        // Arm card, then the event log right under it.
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            security.side(true)
                            security.below(true)
                        }
                    }
                }
                // Same call site for both tabs → the same camera box stays alive across switches.
                camera(
                    Modifier
                        .weight(0.8f)
                        .fillMaxHeight(),
                )
            }
        } else {
            // The camera fills what the current tab's top panel leaves (no gap under SECURITY's
            // shorter panel).
            val density = LocalDensity.current
            var panelHeight by remember { mutableStateOf(0.dp) }
            val cameraHeight = if (panelHeight == 0.dp) {
                300.dp
            } else {
                (maxHeight - panelHeight - 8.dp - 12.dp - 12.dp).coerceIn(220.dp, 560.dp)
            }
            val scroll = rememberScrollState()
            LaunchedEffect(tab) { scroll.scrollTo(0) }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.onSizeChanged { panelHeight = with(density) { it.height.toDp() } },
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (energyTab) {
                        RoomCard(
                            uiState = uiState,
                            securityState = securityState,
                            lights = lightsState,
                            lightsController = lightsController,
                        )
                        SensorBoxesRow(
                            temp = temp,
                            tempAvailable = tempAvailable,
                            hum = hum,
                            humAvailable = humAvailable,
                            energyState = energyState,
                            nowMillis = uiState.nowMillis,
                            lights = lightsState,
                            liveCount = liveCount,
                        )
                    } else {
                        security.side(false)
                    }
                }
                // Same call site for both tabs → the same camera box stays alive across switches.
                camera(
                    Modifier
                        .fillMaxWidth()
                        .height(cameraHeight),
                )
                if (!energyTab) security.below(false)
            }
        }
    }
}

/** Left inset of the Room card's content (the ceiling light lines up with the room name). */
private val ROOM_CARD_START = 18.dp

/**
 * Room card (turns light red while CCTV is Armed): a surface ceiling light flush with the card's top
 * edge above the room name (tap to switch; lit, it brightens the card), then the room name, node code,
 * CCTV shield, aircond and fan pills, and the Schedule and Auto-off buttons on the right.
 */
@Composable
private fun RoomCard(
    uiState: SensorNodeUiState,
    securityState: SecurityUiState,
    lights: LightsState,
    lightsController: LightsController?,
) {
    val armed = securityState.armed
    var scheduleOpen by remember { mutableStateOf(false) }
    // The room's ceiling light sits flush with the card's top edge above the room name; when it is on
    // the whole card lights up.
    // No smart plug linked: the light (and its glow) isn't shown at all; the card keeps its size.
    val glow = lightGlow(lights.lightsOn.takeIf { lights.plugLinked })
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = if (armed) Color(0xFFFEE2E2) else Color(0xFFECEEEF)),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .ceilingLightBeam(
                    glow = glow,
                    lightStartX = ROOM_CARD_START,
                    lightWidth = CEILING_LIGHT_WIDTH,
                    lightBottomY = CEILING_LIGHT_DRAWN_HEIGHT,
                ),
        ) {
            // Tap the light to switch it (through the smart plug).
            if (lights.plugLinked) {
                CeilingLight(
                    on = lights.lightsOn,
                    glow = glow,
                    modifier = Modifier
                        .padding(start = ROOM_CARD_START)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Switch,
                            onClickLabel = if (lights.lightsOn == true) "Turn lights off" else "Turn lights on",
                        ) { lightsController?.switchLights(lights.lightsOn != true) },
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = ROOM_CARD_START, end = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    // Room name starts just under the ceiling light.
                    Spacer(Modifier.height(CEILING_LIGHT_DRAWN_HEIGHT + 6.dp))
                    // Room name with the CCTV shield right after it.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            uiState.roomId.ifBlank { "Room1" },
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF09090B),
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(8.dp))
                        SecurityShieldBadge(securityState, Modifier.weight(1f, fill = false))
                    }
                    Text(
                        "RN-${uiState.nodeId.takeLast(6).ifBlank { "082" }}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF6B7280),
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AircondPill(on = lights.aircondOn, onToggle = { lightsController?.toggleAircond() })
                        FanPill(on = lights.fanOn, onToggle = { lightsController?.toggleFan() })
                    }
                }
                Spacer(Modifier.width(8.dp))
                RoomLightsButtons(
                    lights = lights,
                    onOpenSchedule = { scheduleOpen = true },
                    onToggleAutoOff = { lightsController?.setAutoOff(!lights.autoOff) },
                )
            }
        }
    }
    if (scheduleOpen && lightsController != null) {
        LightsScheduleSheet(lights = lights, controller = lightsController, onDismiss = { scheduleOpen = false })
    }
}

/** Temperature · Humidity · Occupancy — three equal boxes in one row. */
@Composable
private fun SensorBoxesRow(
    temp: Float?,
    tempAvailable: Boolean,
    hum: Float?,
    humAvailable: Boolean,
    energyState: EnergyUiState,
    nowMillis: Long,
    lights: LightsState,
    liveCount: Int?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CompactGaugeCard(
            label = "TEMPERATURE",
            value = temp ?: 0f,
            maxValue = 50f,
            centerText = if (temp != null) "${temp.toInt()}°" else "--",
            subText = null,
            colors = listOf(Color(0xFF2563EB), Color(0xFF00E676)),
            available = tempAvailable,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
        CompactGaugeCard(
            label = "HUMIDITY",
            value = hum ?: 0f,
            maxValue = 100f,
            centerText = if (hum != null) "${hum.toInt()}" else "--",
            subText = if (hum != null) "%" else null,
            colors = listOf(Color(0xFF06B6D4), Color(0xFF14B8A6)),
            available = humAvailable,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
        OccupancyCard(
            energy = energyState,
            nowMillis = nowMillis,
            lights = lights,
            onToggleLights = null,
            onToggleAircond = null,
            onToggleFan = null,
            liveCount = liveCount,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
    }
}

@Composable
private fun CompactGaugeCard(
    label: String,
    value: Float,
    maxValue: Float,
    centerText: String,
    subText: String?,
    colors: List<Color>,
    available: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFECEEEF)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF374151),
                letterSpacing = 0.3.sp,
                maxLines = 1,
                softWrap = false,
            )
            Spacer(Modifier.weight(1f))
            StitchRingGauge(
                value = value,
                maxValue = maxValue,
                centerText = centerText,
                subText = subText,
                gradientColors = colors,
                isDataAvailable = available,
                gaugeSize = 76.dp,
                subTextInline = true,
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

/** Landscape variant of a gauge box: label on the left, ring on the right (short and wide). */
@Composable
private fun WideGaugeBox(
    label: String,
    value: Float,
    maxValue: Float,
    centerText: String,
    subText: String?,
    colors: List<Color>,
    available: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFECEEEF)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Shrinks to fit beside the ring on narrow phones instead of running under it.
            BasicText(
                text = label,
                style = TextStyle(fontWeight = FontWeight.Bold, color = Color(0xFF374151), letterSpacing = 0.3.sp),
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = 10.sp, stepSize = 0.5.sp),
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
            )
            StitchRingGauge(
                value = value,
                maxValue = maxValue,
                centerText = centerText,
                subText = subText,
                gradientColors = colors,
                isDataAvailable = available,
                gaugeSize = 56.dp,
                centerFontSize = 17.sp,
                subTextInline = true,
            )
        }
    }
}

/**
 * Occupancy box: YOLO headcount + status. While the Auto-off countdown runs, the whole card turns into
 * "LIGHTS OFF IN n s". [wide] (landscape, where there is no Room card): the lights + aircond icons sit
 * in a column on the right. (Landscape gives this box a slim 20% column, so count and status stay
 * stacked there too.)
 */
@Composable
private fun OccupancyCard(
    energy: EnergyUiState,
    nowMillis: Long,
    lights: LightsState,
    onToggleLights: (() -> Unit)?,
    onToggleAircond: (() -> Unit)?,
    onToggleFan: (() -> Unit)?,
    liveCount: Int?,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
) {
    // The live headcount on the preview when it is running; otherwise the last radar-triggered photo.
    val count: Int? = liveCount ?: energy.personCount
    val occupied = (count ?: 0) > 0
    val lightsOffIn = lights.countdownSecondsLeft(nowMillis)

    val (status, statusColor) = when {
        liveCount != null -> if (occupied) "OCCUPIED" to Color(0xFFD97706) else "EMPTY" to Color(0xFF6B7280)
        energy.pausedForCctv -> "CCTV" to Color(0xFFB91C1C)
        energy.phase == EnergyPhase.ENTERING || energy.phase == EnergyPhase.LEAVING -> "CHECKING" to Color(0xFFD97706)
        occupied -> "OCCUPIED" to Color(0xFFD97706)
        else -> "EMPTY" to Color(0xFF6B7280)
    }

    val title: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFED7AA)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Person, contentDescription = null, tint = Color(0xFFEA580C), modifier = Modifier.size(11.dp))
            }
            Spacer(Modifier.width(4.dp))
            // Shrinks to fit narrow phones / large system fonts instead of being cut off.
            BasicText(
                text = "OCCUPANCY",
                style = TextStyle(fontWeight = FontWeight.Bold, color = Color(0xFF374151)),
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = 10.sp, stepSize = 0.5.sp),
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
    val countBlock: @Composable () -> Unit = {
        Box(
            modifier = Modifier.height(COUNT_BLOCK_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = count?.toString() ?: "--",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF09090B),
            )
        }
    }
    val statusPill: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White)
                .padding(horizontal = if (wide) 10.dp else 6.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = status,
                fontSize = if (wide) 10.sp else 8.sp,
                fontWeight = FontWeight.Bold,
                color = statusColor,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
    val cardColor by animateColorAsState(
        if (lightsOffIn != null) Color(0xFFFEF3C7) else Color(0xFFFBF4EB),
        label = "occupancyCard",
    )

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(if (wide) 20.dp else 24.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
    ) {
        if (lightsOffIn != null) {
            LightsOffCountdown(secondsLeft = lightsOffIn, wide = wide)
        } else if (wide) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    title()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        countBlock()
                        Spacer(Modifier.height(4.dp))
                        statusPill()
                    }
                }
                if (onToggleLights != null && onToggleAircond != null && onToggleFan != null) {
                    Spacer(Modifier.width(8.dp))
                    LightsAndAircondColumn(
                        lights = lights,
                        onToggleLights = onToggleLights,
                        onToggleAircond = onToggleAircond,
                        onToggleFan = onToggleFan,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
            ) {
                title()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 6.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    countBlock()
                    Spacer(Modifier.height(6.dp))
                    statusPill()
                }
            }
        }
    }
}

/** Height of the people count block (fits the 34 sp number). */
private val COUNT_BLOCK_HEIGHT = 46.dp

/** Illuminance card (Settings → Diagnostics): live EV100 from the live view's exposure, every 10 s. */
@Composable
private fun IlluminanceCard(perception: PerceptionUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFECEEEF)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.WbSunny, contentDescription = null, tint = Color(0xFF374151), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "ILLUMINANCE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF374151),
                        letterSpacing = 0.5.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    val evVal = perception.exposureEv?.let { "%.1f".format(it) }
                    Text(
                        text = evVal ?: "--",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF09090B),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "EV100",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF6B7280),
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                val lightsOn = perception.lightsOn == true
                Text(
                    text = if (perception.exposureEv == null) "Open live view" else if (lightsOn) "Lights ON" else "Lights OFF",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF09090B),
                )
                Spacer(Modifier.height(4.dp))
                Switch(
                    checked = lightsOn,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF34D399),
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFE5E7EB),
                    ),
                )
            }
        }
    }
}

/**
 * The ONE live camera box shared by ENERGY and SECURITY. Its live view is the same composable on
 * both tabs, so switching tabs never stops or reloads it — only the overlays change. The live
 * headcount draws a box around each person on the preview and shows the count in the LIVE chip.
 *  - ENERGY: the checked photo with YOLO boxes (until tapped), capture/YOLO chip, Room1 chip in
 *    landscape.
 *  - SECURITY: ARMED chip.
 * A tap starts the camera if it is off; pinch zooms the real camera. While the reWatt module is not
 * plugged into this phone, a reminder card sits at the bottom (both tabs).
 */
@Composable
private fun SharedCameraBox(
    energyTab: Boolean,
    perception: PerceptionUiState,
    energy: EnergyUiState,
    onLivePreviewSurface: (Preview.SurfaceProvider, Boolean) -> Unit,
    onStartCamera: () -> Unit,
    onDismissPhoto: () -> Unit,
    onLiveFrame: (suspend (Bitmap) -> Unit)?,
    nowMillis: Long,
    roomChip: String?,
    moduleOnline: Boolean,
    armed: Boolean,
    securityState: SecurityUiState,
    zoomRatio: Float,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    // While armed the camera service is up, so SECURITY shows the live view even without occupancy capture.
    val cameraOn = perception.isRunning || (!energyTab && securityState.armed)
    val liveCheck = !energyTab && securityState.liveActive
    val frame = perception.latestFrame
    val showPhoto = energyTab && frame != null && energy.photoPinned && !energy.capturing
    val liveShown = cameraOn && !showPhoto
    val liveCount = perception.liveCount(nowMillis).takeIf { liveShown }
    // TEMP (testing): tapping the LIVE chip flips between the back and front camera.
    val previewHub = (LocalContext.current.applicationContext as ReWattApplication).appGraph.livePreviewHub
    val frontCamera by previewHub.frontCamera.collectAsStateWithLifecycle()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF111827))
            .then(if (liveShown) Modifier.pinchToZoom(onZoom) else Modifier),
    ) {
        // Base feed — identical on both tabs.
        if (cameraOn) {
            LivePreview(
                onLivePreviewSurface = onLivePreviewSurface,
                onLiveFrame = onLiveFrame,
                modifier = Modifier.fillMaxSize(),
            )
            if (liveCount != null) LivePeopleBoxes(people = perception.livePeople, modifier = Modifier.fillMaxSize())
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.CameraAlt, contentDescription = null, tint = Color(0xFF9CA3AF), modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(6.dp))
                Text("Camera off", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Tap to start", fontSize = 11.sp, color = Color(0xFF9CA3AF))
            }
        }

        if (showPhoto && frame != null) {
            DetectionPhoto(
                frame = frame,
                people = perception.peopleDetections,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismissPhoto,
                    ),
            )
        } else if (!cameraOn) {
            // A tap starts a stopped camera.
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onStartCamera,
                    ),
            )
        }

        if (liveCheck) {
            LiveFaceOverlay(
                faces = securityState.liveFaces,
                frameWidth = securityState.liveFrameWidth,
                frameHeight = securityState.liveFrameHeight,
                modifier = Modifier.fillMaxSize(),
                mirrored = frontCamera,
            )
        }

        // Status chip (top-left)
        val chip: Pair<String, Color>? = when {
            liveCheck -> stringResource(
                R.string.live_chip,
                securityState.liveFps ?: 0f,
                securityState.liveFaces.size,
            ) to Color(0xFFEF4444)
            energyTab && energy.capturing -> "Capturing photo…" to Color(0xFFF59E0B)
            showPhoto -> {
                val n = perception.personCount
                "YOLO · $n ${if (n == 1) "person" else "people"}" to Color(0xFF22C55E)
            }
            liveCount != null -> "LIVE · $liveCount ${if (liveCount == 1) "person" else "people"}" to Color(0xFFEF4444)
            cameraOn -> "LIVE" to Color(0xFFEF4444)
            else -> null
        }
        chip?.let { (text, dot) ->
            if (liveShown) {
                CameraChip(
                    if (frontCamera) "$text · FRONT" else text,
                    dot,
                    Modifier.align(Alignment.TopStart),
                    onClick = previewHub::toggleCamera,
                )
            } else {
                CameraChip(text, dot, Modifier.align(Alignment.TopStart))
            }
        }

        if (liveShown) {
            // Lifted above the module reminder while that is showing.
            ZoomChip(
                zoomRatio,
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = if (moduleOnline) 0.dp else 64.dp),
            )
        }

        when {
            armed -> CameraChip(stringResource(R.string.sec_armed), Color(0xFFB91C1C), Modifier.align(Alignment.TopEnd))
            roomChip != null -> CameraChip(
                roomChip,
                if (moduleOnline) Color(0xFF22C55E) else Color(0xFFF59E0B),
                Modifier.align(Alignment.TopEnd),
            )
        }

        AnimatedVisibility(
            visible = !moduleOnline,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ModuleMissingReminder()
        }
    }
}

/** Bottom card on the camera box: the reWatt module isn't plugged into this phone. */
@Composable
private fun ModuleMissingReminder() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xCC111827))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Color(0x33F59E0B)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Usb, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.preview_module_missing_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                stringResource(R.string.preview_module_missing_body),
                fontSize = 11.sp,
                color = Color(0xFFD1D5DB),
                lineHeight = 14.sp,
            )
        }
    }
}

/** A box around each person the live headcount found; boxes are normalised to the preview itself. */
@Composable
private fun LivePeopleBoxes(people: List<ObjectDetection>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = 3.dp.toPx()
        people.forEach { person ->
            val b = person.box.clamped()
            drawRect(
                color = Color(0xFF22C55E),
                topLeft = Offset(b.left * size.width, b.top * size.height),
                size = Size((b.right - b.left) * size.width, (b.bottom - b.top) * size.height),
                style = Stroke(width = stroke),
            )
        }
    }
}

/** Small dark pill with a coloured dot on a camera box ("● LIVE", "● Room1", "● ARMED"). */
@Composable
private fun CameraChip(text: String, dot: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier = modifier
            .padding(10.dp)
            .clip(RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(Color(0x99000000))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dot),
        )
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, softWrap = false)
    }
}

/**
 * Last frame of each live preview box. Right before a tab switch every visible preview freezes what
 * it shows ([captureAll]); the incoming tab shows that still frame until its own camera stream is
 * rendering again, so the switch has no black flash.
 */
internal object PreviewFrameCache {
    const val LIVE_KEY = "live"

    private val frames = mutableMapOf<String, Bitmap>()
    private val snapshotters = mutableMapOf<String, () -> Bitmap?>()

    fun get(key: String): Bitmap? = frames[key]

    fun register(key: String, snapshot: () -> Bitmap?) {
        snapshotters[key] = snapshot
    }

    fun unregister(key: String, snapshot: () -> Bitmap?) {
        if (snapshotters[key] === snapshot) snapshotters.remove(key)
    }

    /** Main thread, right before the tab changes. */
    fun captureAll() {
        snapshotters.forEach { (key, snapshot) -> runCatching { snapshot() }.getOrNull()?.let { frames[key] = it } }
    }
}

/** The frozen frame drawn over a preview box until it renders again. */
@Composable
private fun FrozenFrame(frame: Bitmap?, visible: Boolean) {
    if (!visible || frame == null) return
    val image = remember(frame) { frame.asImageBitmap() }
    Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
}

/**
 * CameraX PreviewView, publishing its surface only while the app is in the foreground. Uses a
 * TextureView (COMPATIBLE) so it is clipped exactly to the card's rounded shape. Shows the last
 * frozen live frame until the camera stream is running again (smooth tab switches).
 * [onLiveFrame]: while streaming, the displayed frame is handed over for the live headcount, again as
 * soon as the previous count (and its pacing pause) has finished — up to 10 times a second.
 */
@Composable
internal fun LivePreview(
    onLivePreviewSurface: (Preview.SurfaceProvider, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onLiveFrame: (suspend (Bitmap) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val frozen = remember { PreviewFrameCache.get(PreviewFrameCache.LIVE_KEY) }
    var streaming by remember { mutableStateOf(false) }
    DisposableEffect(previewView, lifecycleOwner) {
        val observer = Observer<PreviewView.StreamState> { streaming = it == PreviewView.StreamState.STREAMING }
        previewView.previewStreamState.observe(lifecycleOwner, observer)
        val snapshot: () -> Bitmap? = { previewView.bitmap }
        PreviewFrameCache.register(PreviewFrameCache.LIVE_KEY, snapshot)
        onDispose {
            previewView.previewStreamState.removeObserver(observer)
            PreviewFrameCache.unregister(PreviewFrameCache.LIVE_KEY, snapshot)
        }
    }
    LifecycleStartEffect(previewView) {
        val provider = previewView.surfaceProvider
        onLivePreviewSurface(provider, true)
        onStopOrDispose { onLivePreviewSurface(provider, false) }
    }
    val currentOnLiveFrame by rememberUpdatedState(onLiveFrame)
    LaunchedEffect(previewView, onLiveFrame != null) {
        if (onLiveFrame == null) return@LaunchedEffect
        while (true) {
            // PreviewView.bitmap = exactly what the box shows (cropped, zoomed); null until streaming.
            val frame = if (streaming) previewView.bitmap else null
            val analyze = currentOnLiveFrame
            if (frame != null && analyze != null) {
                analyze(frame) // counts, then waits until the next frame is due (LiveHeadcount paces itself)
            } else {
                frame?.recycle()
                delay(LiveHeadcount.IDLE_POLL_MS)
            }
        }
    }
    Box(modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        FrozenFrame(frame = frozen, visible = !streaming)
    }
}

/** The analysed photo, cropped to fill like the live view, with a box around each detected person. */
@Composable
private fun DetectionPhoto(
    frame: android.graphics.Bitmap,
    people: List<ObjectDetection>,
    modifier: Modifier = Modifier,
) {
    val image = remember(frame) { frame.asImageBitmap() }
    Canvas(modifier = modifier) {
        val scale = maxOf(size.width / image.width, size.height / image.height)
        val drawW = image.width * scale
        val drawH = image.height * scale
        val offsetX = (size.width - drawW) / 2f
        val offsetY = (size.height - drawH) / 2f
        drawImage(
            image = image,
            dstOffset = IntOffset(offsetX.roundToInt(), offsetY.roundToInt()),
            dstSize = IntSize(drawW.roundToInt(), drawH.roundToInt()),
        )
        val stroke = 3.dp.toPx()
        people.forEach { person ->
            val b = person.box
            drawRect(
                color = Color(0xFF22C55E),
                topLeft = Offset(offsetX + b.left * drawW, offsetY + b.top * drawH),
                size = Size((b.right - b.left) * drawW, (b.bottom - b.top) * drawH),
                style = Stroke(width = stroke),
            )
        }
    }
}

/** In-app banner that slides down from the top (not a system notification). */
@Composable
private fun EnergyBannerHost(
    banner: EnergyBanner?,
    roomName: String,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<EnergyBanner?>(null) }
    LaunchedEffect(banner?.id) {
        // Only fresh banners (not one replayed after the screen was recreated).
        if (banner == null || System.currentTimeMillis() - banner.atMillis > BANNER_VISIBLE_MS) return@LaunchedEffect
        shown = banner
        visible = true
        delay(BANNER_VISIBLE_MS)
        visible = false
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        val b = shown ?: return@AnimatedVisibility
        val (icon, title, subtitle) = when (b.kind) {
            EnergyBanner.Kind.MOTION -> Triple(
                Icons.Outlined.Radar,
                "Movement detected in $roomName",
                "Taking a photo to count people",
            )
            EnergyBanner.Kind.NO_MOTION -> Triple(
                Icons.Outlined.Radar,
                "No movement detected in $roomName",
                "Checking if the room is empty",
            )
            EnergyBanner.Kind.ROOM_EMPTY -> Triple(
                Icons.Outlined.PowerSettingsNew,
                "Turn OFF lights and aircond in $roomName",
                "No one detected for ${EnergyMonitor.EMPTY_CONFIRM_MS / 1000} s",
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF111827))
                .clickable { visible = false }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1F2937)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(subtitle, fontSize = 12.sp, color = Color(0xFF9CA3AF))
            }
        }
    }
}

private const val BANNER_VISIBLE_MS = 4_000L

/** Stitch Clean Gradient Ring Gauge with empty state support */
@Composable
private fun StitchRingGauge(
    value: Float,
    maxValue: Float,
    centerText: String,
    subText: String? = null,
    gradientColors: List<Color>,
    isDataAvailable: Boolean = true,
    gaugeSize: Dp = 110.dp,
    centerFontSize: TextUnit = 24.sp,
    /** Show [subText] beside the value (e.g. "58%") instead of under it — for the small rings. */
    subTextInline: Boolean = false,
) {
    Box(
        modifier = Modifier.size(gaugeSize),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = (gaugeSize.value * 14f / 110f).dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
            val size2 = Size(diameter, diameter)

            // Background gray track arc
            drawArc(
                color = Color(0xFFE2E8F0),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = size2,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )

            if (isDataAvailable) {
                // Gradient sweep arc
                val fraction = (value / maxValue).coerceIn(0f, 1f)
                val sweep = fraction * 270f
                drawArc(
                    brush = Brush.sweepGradient(gradientColors),
                    startAngle = 135f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = size2,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }
        }

        if (subTextInline) {
            Row {
                Text(
                    text = centerText,
                    fontSize = centerFontSize,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF09090B),
                    modifier = Modifier.alignByBaseline(),
                )
                if (subText != null) {
                    Text(
                        text = subText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF6B7280),
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = centerText,
                    fontSize = centerFontSize,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF09090B),
                )
                if (subText != null) {
                    Text(
                        text = subText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF6B7280),
                    )
                }
            }
        }
    }
}

// ── SETTINGS → DIAGNOSTICS (formerly the DEBUG tab) ───────────────────────────

@Composable
private fun DiagnosticsSection(
    uiState: SensorNodeUiState,
    onScanUsb: () -> Unit,
    onStartPerception: (CaptureStartSource) -> Unit,
    onStopPerception: () -> Unit,
    onDebugCapture: () -> Unit,
    onSendPhotoMqtt: () -> Unit,
) {
    val telemetry = uiState.sensorState.latestTelemetry
    val usbStatus = uiState.sensorState.connectionStatus
    val perception = uiState.perceptionState

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {

        // Live illuminance (the same reading as the occupancy card's lights icon)
        IlluminanceCard(perception = perception)

        // USB Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFECEEEF)),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(32.dp).clip(CircleShape).background(Color.White),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.Usb, contentDescription = null, tint = Color(0xFF09090B), modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("USB Status", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF09090B))
                    }

                    val connected = usbStatus == UsbConnectionStatus.Connected
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (connected) Color(0xFFDCFCE7) else Color(0xFFFEF3C7))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = if (connected) "CONNECTED" else "DISCONNECTED",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (connected) Color(0xFF15803D) else Color(0xFFB45309),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("PORT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6B7280))
                        Text(
                            text = if (usbStatus == UsbConnectionStatus.Connected) "/dev/ttyUSB0 (ESP32-C6)" else "No USB Device",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF09090B),
                        )
                    }

                    OutlinedButton(
                        onClick = onScanUsb,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF09090B)),
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Rescan", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // mmWave Radar Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFECEEEF)),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(32.dp).clip(CircleShape).background(Color.White),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Radar, contentDescription = null, tint = Color(0xFF09090B), modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("mmWave Radar", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF09090B))
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("Distance", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B7280))
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("${telemetry?.movingDistanceCm ?: 0}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF09090B))
                                Spacer(Modifier.width(4.dp))
                                Text("cm", fontSize = 12.sp, color = Color(0xFF6B7280), modifier = Modifier.padding(bottom = 2.dp))
                            }
                        }
                    }

                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("Energy", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B7280))
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("${telemetry?.movingEnergy ?: 0}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFFC2410C))
                                Spacer(Modifier.width(4.dp))
                                Text("E", fontSize = 12.sp, color = Color(0xFFC2410C), modifier = Modifier.padding(bottom = 2.dp))
                            }
                        }
                    }
                }
            }
        }

        // Perception Feed Card
        Column {
            Text("Perception Feed", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF09090B))
            Spacer(Modifier.height(10.dp))
            perception.latestFrame?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "YOLO Perception Feed",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(20.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        // Controls Section
        Column {
            Text("Controls", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF09090B))
            Spacer(Modifier.height(10.dp))

            Button(
                onClick = {
                    if (perception.isRunning) onStopPerception()
                    else onStartPerception(CaptureStartSource.Manual)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF09090B), contentColor = Color.White),
            ) {
                Icon(Icons.Outlined.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (perception.isRunning) "Stop Perception" else "Start Perception", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDebugCapture,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF09090B)),
                ) {
                    Icon(Icons.Outlined.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Test Capture", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onSendPhotoMqtt,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF09090B)),
                ) {
                    Icon(Icons.Outlined.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("MQTT Publish", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ── TAB 3: SETTINGS ("MQTT Configuration") ────────────────────────────────────

@Composable
private fun StitchSettingsTab(
    uiState: SensorNodeUiState,
    onUpdateMqttConfig: (String, Int, String, String, String) -> Unit,
    onOpenSetupGuide: () -> Unit,
    securitySettingsContent: @Composable () -> Unit,
    smartPlugContent: @Composable () -> Unit,
    diagnosticsContent: @Composable () -> Unit,
) {
    var diagnosticsExpanded by rememberSaveable { mutableStateOf(false) }
    var host by remember { mutableStateOf(uiState.mqttState.brokerHost) }
    var portText by remember { mutableStateOf("8883") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var roomId by remember { mutableStateOf(uiState.roomId) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF09090B))
        Column {
            Text("MQTT Configuration", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF09090B))
            Text("Connect your ReWatt unit to your local broker.", fontSize = 13.sp, color = Color(0xFF6B7280))
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFECEEEF)),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("BROKER HOST", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF374151))
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    placeholder = { Text("mqtt.local") },
                    leadingIcon = { Icon(Icons.Outlined.Dns, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                    ),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(modifier = Modifier.weight(0.4f)) {
                        Text("PORT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF374151))
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White,
                            ),
                        )
                    }

                    Column(modifier = Modifier.weight(0.6f)) {
                        Text("ROOM ID", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF374151))
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = roomId,
                            onValueChange = { roomId = it },
                            placeholder = { Text("Living Room") },
                            leadingIcon = { Icon(Icons.Outlined.MeetingRoom, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White,
                            ),
                        )
                    }
                }

                Text("USERNAME", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF374151))
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    placeholder = { Text("rewatt_user") },
                    leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                    ),
                )

                Text("PASSWORD", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF374151))
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                    trailingIcon = { Icon(Icons.Outlined.Visibility, contentDescription = null) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                    ),
                )

                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        val p = portText.toIntOrNull() ?: 8883
                        onUpdateMqttConfig(host, p, user, pass, roomId)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27272A), contentColor = Color.White),
                ) {
                    Text("Save & Connect", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Room 1 lights: Tapo smart plug (backup login / IP)
        smartPlugContent()

        // Clean Single "Quick Setup Guide" Outlined Button
        OutlinedButton(
            onClick = onOpenSetupGuide,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF09090B)),
        ) {
            Icon(Icons.Outlined.HelpOutline, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Quick Setup Guide", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }

        // Security camera settings + permissions (moved here from the SECURITY tab)
        securitySettingsContent()

        // Diagnostics (formerly the DEBUG tab) — collapsed by default to keep Settings tidy
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { diagnosticsExpanded = !diagnosticsExpanded },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFECEEEF)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Tune, contentDescription = null, tint = Color(0xFF09090B), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Diagnostics", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF09090B), modifier = Modifier.weight(1f))
                Icon(
                    if (diagnosticsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = Color(0xFF6B7280),
                )
            }
        }
        if (diagnosticsExpanded) diagnosticsContent()
        Spacer(Modifier.height(8.dp))
    }
}
