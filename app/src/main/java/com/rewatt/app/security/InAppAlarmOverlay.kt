package com.rewatt.app.security

import android.app.Activity
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rewatt.app.BuildConfig
import com.rewatt.app.R
import com.rewatt.app.ReWattApplication
import com.rewatt.app.core.AppGraph
import com.rewatt.app.perception.CameraCaptureService
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Which alarm the full-screen page is for: it sets the colours, the emblem and the fire flames. */
enum class AlarmKind { FIRE, INTRUDER }

private class AlarmTheme(
    val base: Color,
    val accent: Color,
    val accentDeep: Color,
    val icon: ImageVector,
    val reasonIcon: ImageVector,
)

private val FireTheme = AlarmTheme(
    base = Color(0xFF4A0A0A),
    accent = Color(0xFFF97316),
    accentDeep = Color(0xFFDC2626),
    icon = Icons.Rounded.LocalFireDepartment,
    reasonIcon = Icons.Outlined.Air,
)
private val IntruderTheme = AlarmTheme(
    base = Color(0xFF450A0F),
    accent = Color(0xFFEF4444),
    accentDeep = Color(0xFFB91C1C),
    icon = Icons.Rounded.GppMaybe,
    reasonIcon = Icons.Outlined.Face,
)
private val TabularDigits = TextStyle(fontFeatureSettings = "tnum") // the clock doesn't jiggle as digits change

/**
 * Fire or intruder alarm while the app is open: the full-screen [AlarmFullScreen] page on top of
 * everything, in the app's current orientation. Same page as the lock-screen [IntrusionAlertActivity].
 */
@Composable
fun InAppAlarmOverlay(graph: AppGraph) {
    val context = LocalContext.current
    val security by graph.securityStateHolder.state.collectAsStateWithLifecycle()
    val fire by graph.fireMonitor.alarm.state.collectAsStateWithLifecycle()
    val roomId = graph.mqttSyncRepository.roomId.ifBlank { "Room1" }

    when {
        fire.active -> AlarmFullScreen(
            kind = AlarmKind.FIRE,
            heading = stringResource(R.string.fire_alert_heading),
            message = stringResource(R.string.fire_alert_text, roomId),
            photoPath = null,
            startedAtMillis = fire.atMillis,
            detail = fireAlarmDetail(fire.test, fire.vocIndex, fire.noxIndex),
            onDismiss = { graph.fireMonitor.dismiss() },
        )
        security.alarmActive -> {
            val eventId = security.alarmEventId
            val photoName = security.alarmPhotoName
            AlarmFullScreen(
                kind = AlarmKind.INTRUDER,
                heading = stringResource(R.string.cctv_alert_heading),
                message = stringResource(R.string.cctv_alert_text, roomId),
                photoPath = if (eventId != null && photoName != null) {
                    graph.securityEventStore.photoFile(eventId, photoName).absolutePath
                } else {
                    null
                },
                startedAtMillis = security.alarmStartedAtMillis,
                detail = intruderAlarmDetail(security.alarmScore),
                onDismiss = {
                    if (graph.securitySettings.armed) {
                        // The service also releases the camera / records the dismissal on the event.
                        context.startService(CameraCaptureService.dismissAlarmIntent(context))
                    } else {
                        graph.intrusionAlarm.dismiss() // e.g. Test alarm while disarmed
                    }
                },
            )
        }
        BuildConfig.DEBUG -> AlarmPreview(graph, roomId)
    }
    // The small "You are being recorded" notice is shown in the camera box, under its status chip.
}



/**
 * Debug builds only: `adb shell am start -S -n com.rewatt.app/.MainActivity --es preview_alarm fire|intruder`
 * shows the alarm page silently (no siren, no strobe), to check its look. DISMISS closes it.
 */
@Composable
private fun AlarmPreview(graph: AppGraph, roomId: String) {
    val activity = LocalContext.current as? Activity
    var kind by remember {
        mutableStateOf(
            when (activity?.intent?.getStringExtra("preview_alarm")) {
                "fire" -> AlarmKind.FIRE
                "intruder" -> AlarmKind.INTRUDER
                else -> null
            },
        )
    }
    val started = remember { System.currentTimeMillis() - 42_000L }
    when (kind) {
        AlarmKind.FIRE -> AlarmFullScreen(
            kind = AlarmKind.FIRE,
            heading = stringResource(R.string.fire_alert_heading),
            message = stringResource(R.string.fire_alert_text, roomId),
            photoPath = null,
            startedAtMillis = started,
            detail = fireAlarmDetail(test = false, vocIndex = 214, noxIndex = 7),
            onDismiss = { kind = null },
        )
        AlarmKind.INTRUDER -> {
            // The newest stranger photo on this phone, if there is one.
            val photo = remember {
                graph.securityEventStore.events.value
                    .sortedByDescending { it.startedAtMillis }
                    .firstNotNullOfOrNull { e -> e.strangerPhoto?.let { graph.securityEventStore.photoFile(e.id, it) }?.takeIf { it.exists() } }
                    ?.absolutePath
            }
            AlarmFullScreen(
                kind = AlarmKind.INTRUDER,
                heading = stringResource(R.string.cctv_alert_heading),
                message = stringResource(R.string.cctv_alert_text, roomId),
                photoPath = photo,
                startedAtMillis = started,
                detail = intruderAlarmDetail(0.31f),
                onDismiss = { kind = null },
            )
        }
        null -> Unit
    }
}

@Composable
internal fun fireAlarmDetail(test: Boolean, vocIndex: Int?, noxIndex: Int?): String =
    if (test) {
        stringResource(R.string.fire_alert_reason_test)
    } else {
        stringResource(R.string.fire_alert_reason, vocIndex?.toString() ?: "--", noxIndex?.toString() ?: "--")
    }

@Composable
internal fun intruderAlarmDetail(score: Float?): String =
    if (score != null) {
        stringResource(R.string.cctv_alert_reason_score, "%.2f".format(score))
    } else {
        stringResource(R.string.cctv_alert_reason)
    }

/**
 * Full-screen alarm page (fire or intruder) on a dark backdrop that breathes in the alarm colour, with
 * a slow siren beam sweeping round and a pulsing red edge.
 *  - An emblem with radar rings, the heading, a live clock (the digits roll as they change; always the
 *    12-hour clock with AM / PM), when it started and how long it has rung, and the reason.
 *  - Intruder: the stranger photo in a viewfinder frame with a scan line.
 *  - One big DISMISS button (a light sheen runs across it; it presses in when touched).
 * Everything fades and rises in, one part after another, when the page opens. It takes every touch,
 * keeps the screen on and ignores Back, so the alarm is only stopped with DISMISS. Landscape: the
 * information on the left; the photo (or a large emblem) and DISMISS on the right.
 * [startedAtMillis] null: when this page opened.
 */
@Composable
internal fun AlarmFullScreen(
    kind: AlarmKind,
    heading: String,
    message: String,
    photoPath: String?,
    startedAtMillis: Long?,
    detail: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler {} // only DISMISS stops the alarm
    val theme = if (kind == AlarmKind.FIRE) FireTheme else IntruderTheme
    // Status and navigation bars in the alarm's dark colour (not the app's white) while it shows.
    val activity = context as? Activity
    DisposableEffect(activity, theme) {
        val window = activity?.window
        if (window == null) {
            onDispose { }
        } else {
            val bars = WindowCompat.getInsetsController(window, window.decorView)
            @Suppress("DEPRECATION")
            val oldStatus = window.statusBarColor
            @Suppress("DEPRECATION")
            val oldNav = window.navigationBarColor
            val oldLightStatus = bars.isAppearanceLightStatusBars
            val oldLightNav = bars.isAppearanceLightNavigationBars
            @Suppress("DEPRECATION")
            window.statusBarColor = theme.base.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = theme.base.toArgb()
            bars.isAppearanceLightStatusBars = false
            bars.isAppearanceLightNavigationBars = false
            onDispose {
                @Suppress("DEPRECATION")
                window.statusBarColor = oldStatus
                @Suppress("DEPRECATION")
                window.navigationBarColor = oldNav
                bars.isAppearanceLightStatusBars = oldLightStatus
                bars.isAppearanceLightNavigationBars = oldLightNav
            }
        }
    }

    val transition = rememberInfiniteTransition(label = "alarm")
    // Breathing glow / edge / emblem.
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    // Radar rings, siren beam, sheen, scan line: each its own steady loop.
    val rings by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2_100, easing = LinearEasing)), label = "rings")
    val beam by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(4_000, easing = LinearEasing)), label = "beam")
    val sheen by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2_400, easing = LinearEasing)), label = "sheen")
    val scan by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1_800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scan")

    // Opening: everything fades and rises in, one part after another.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(900, easing = LinearEasing)) }
    fun Modifier.enter(order: Int): Modifier = graphicsLayer {
        val t = ((appear.value - order * 0.09f) / 0.55f).coerceIn(0f, 1f)
        val eased = 1f - (1f - t) * (1f - t) * (1f - t)
        alpha = eased
        translationY = (1f - eased) * 28.dp.toPx()
    }

    // Ticks on each new second of the wall clock, so the clock never lags or skips.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L - value % 1_000L)
        }
    }
    val started = remember(startedAtMillis) { startedAtMillis ?: System.currentTimeMillis() }
    // Demo video playing (debug demo): the page shows the demo's date and time (see [demoClock]); the
    // real camera always shows the real time.
    val hub = (context.applicationContext as ReWattApplication).appGraph.livePreviewHub
    val demoVideo by hub.demoVideo.collectAsStateWithLifecycle()
    val shown: (Long) -> Long = { t -> if (demoVideo != null) demoClock(t) else t }
    val locale = context.resources.configuration.locales[0]
    // Always the 12-hour clock with AM / PM on the alarm page (whatever the phone's 24-hour setting).
    val is24Hour = false
    val clockFormat = remember(locale, is24Hour) { bestFormat(locale, if (is24Hour) "Hms" else "hmsa") }
    val dateFormat = remember(locale) { bestFormat(locale, "EEEEdMMMMyyyy") }
    val startedFormat = remember(locale, is24Hour, started) {
        // The date as well when the alarm started on an earlier day.
        bestFormat(locale, (if (sameDay(started, System.currentTimeMillis())) "" else "dMMM") + if (is24Hour) "Hms" else "hmsa")
    }

    val photo by produceState<ImageBitmap?>(null, photoPath) {
        value = photoPath?.let { path ->
            withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
        }
    }

    // A short screen (landscape phone): the title and buttons a little smaller so everything fits.
    val configuration = LocalConfiguration.current
    val shortScreen = minOf(configuration.screenWidthDp, configuration.screenHeightDp) < 420

    val liveChip: @Composable (Modifier) -> Unit = { modifier ->
        Row(
            modifier
                .clip(RoundedCornerShape(50))
                .background(theme.accent.copy(alpha = 0.16f))
                .border(1.dp, theme.accent.copy(alpha = 0.45f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer { alpha = 0.35f + 0.65f * pulse }
                    .clip(CircleShape)
                    .background(theme.accent),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.alarm_active),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
        }
    }
    val title: @Composable (Modifier, Boolean) -> Unit = { modifier, centered ->
        Column(modifier, horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
            Text(
                heading,
                color = Color.White,
                fontSize = if (shortScreen) 30.sp else 34.sp,
                lineHeight = if (shortScreen) 34.sp else 38.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                maxLines = 2,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                message,
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 16.sp,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            )
        }
    }
    val clock: @Composable (Modifier, Boolean, Dp) -> Unit = { modifier, centered, size ->
        Column(modifier, horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
            RollingText(
                clockFormat.format(Date(shown(now))),
                TextStyle(
                    color = Color.White,
                    fontSize = size.value.sp,
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                ),
            )
            Text(dateFormat.format(Date(shown(now))), color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
        }
    }
    val stats: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(stringResource(R.string.alarm_started_label), startedFormat.format(Date(shown(started))), Modifier.weight(1f))
            StatTile(stringResource(R.string.alarm_ringing_label), elapsed(now - started), Modifier.weight(1f), accent = theme.accent)
        }
    }
    val reason: @Composable (Modifier) -> Unit = { modifier ->
        Row(
            modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(theme.reasonIcon, contentDescription = null, tint = theme.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(detail, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    // Right under the captured photo: the intruder's facial embedding is kept with the evidence.
    val evidence: @Composable (Modifier) -> Unit = { modifier ->
        EvidenceStoredBar(stringResource(R.string.alarm_embedding_stored), theme.accent, pulse, modifier)
    }
    val dismiss: @Composable (Modifier) -> Unit = { modifier ->
        DismissButton(stringResource(R.string.cctv_dismiss).uppercase(), theme.accentDeep, sheen, onDismiss, modifier, if (shortScreen) 60.dp else 70.dp)
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .alarmBackdrop(theme, pulse, beam)
            // Takes every touch so nothing behind the alarm can be tapped.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
            .systemBarsPadding(),
    ) {
        if (maxWidth > maxHeight) {
            // Short screens (e.g. a 360 dp-high phone in landscape): tighter spacing and a smaller clock.
            val short = maxHeight < 420.dp
            val gap = if (short) 8.dp else 12.dp
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left: who / what / when.
                Column(
                    Modifier.weight(1.1f),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    liveChip(Modifier.enter(0))
                    title(Modifier.enter(1), false)
                    clock(Modifier.enter(2), false, if (short) 44.dp else 52.dp)
                    stats(Modifier.fillMaxWidth().enter(3))
                }
                // Right: the photo (or the emblem), why, and DISMISS.
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically),
                ) {
                    val current = photo
                    if (current != null) {
                        CapturedPhoto(current, theme.accent, scan, Modifier.fillMaxWidth().weight(1f).enter(2))
                        evidence(Modifier.fillMaxWidth().enter(3))
                    } else {
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().enter(1), contentAlignment = Alignment.Center) {
                            // The emblem with its rings is 2.3 × the badge: fit it in the space left.
                            AlarmEmblem(theme, pulse, rings, minOf(96.dp, maxHeight / 2.4f))
                        }
                    }
                    reason(Modifier.fillMaxWidth().enter(4))
                    dismiss(Modifier.fillMaxWidth().enter(5))
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 22.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                liveChip(Modifier.enter(0))
                val current = photo
                if (current == null) {
                    // No photo: the content sits in the middle, not stuck to the top.
                    Spacer(Modifier.weight(0.6f))
                    Box(Modifier.enter(1).padding(vertical = 4.dp)) { AlarmEmblem(theme, pulse, rings, 76.dp) }
                }
                title(Modifier.enter(2), true)
                clock(Modifier.enter(3), true, 50.dp)
                stats(Modifier.fillMaxWidth().enter(4))
                reason(Modifier.enter(5))
                if (current != null) {
                    CapturedPhoto(current, theme.accent, scan, Modifier.fillMaxWidth().weight(1f).enter(5))
                    evidence(Modifier.fillMaxWidth().enter(5))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                dismiss(Modifier.fillMaxWidth().enter(6))
            }
        }
    }
}

/** Dark backdrop: a glow that breathes in the alarm colour, a siren beam sweeping round, a pulsing edge. */
private fun Modifier.alarmBackdrop(theme: AlarmTheme, pulse: Float, beam: Float): Modifier = drawBehind {
    drawRect(theme.base)
    val center = Offset(size.width * 0.5f, size.height * 0.42f)
    val reach = size.maxDimension
    drawRect(
        Brush.radialGradient(
            listOf(theme.accentDeep.copy(alpha = 0.55f + 0.35f * pulse), theme.accentDeep.copy(alpha = 0.25f), Color.Transparent),
            center = center,
            radius = reach * 0.75f,
        ),
    )
    // Siren beam: two soft wedges of light turning slowly, like a rotating beacon.
    rotate(beam, pivot = center) {
        drawCircle(
            Brush.sweepGradient(
                0f to Color.Transparent,
                0.06f to theme.accent.copy(alpha = 0.16f),
                0.14f to Color.Transparent,
                0.5f to Color.Transparent,
                0.56f to theme.accent.copy(alpha = 0.10f),
                0.64f to Color.Transparent,
                1f to Color.Transparent,
                center = center,
            ),
            radius = reach,
            center = center,
        )
    }
    // Vignette, then the pulsing edge.
    drawRect(
        Brush.radialGradient(
            listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)),
            center = center,
            radius = reach * 0.8f,
        ),
    )
    val edge = 6.dp.toPx()
    drawRect(
        Color(0xFFEF4444).copy(alpha = 0.45f + 0.55f * pulse),
        topLeft = Offset(edge / 2, edge / 2),
        size = Size(size.width - edge, size.height - edge),
        style = Stroke(width = edge),
    )
}

/** The alarm's emblem: a glowing badge with the icon, radar rings spreading out from it. */
@Composable
private fun AlarmEmblem(theme: AlarmTheme, pulse: Float, rings: Float, badge: Dp) {
    Box(Modifier.size(badge * 2.3f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(badge * 2.3f)) {
            val r = badge.toPx() / 2f
            drawCircle(
                Brush.radialGradient(listOf(theme.accent.copy(alpha = 0.35f + 0.2f * pulse), Color.Transparent), radius = r * 2.1f),
                radius = r * 2.1f,
            )
            repeat(3) { i ->
                val p = (rings + i / 3f) % 1f
                drawCircle(
                    theme.accent.copy(alpha = (1f - p) * 0.6f),
                    radius = r * (1f + 1.25f * p),
                    style = Stroke(width = (1f - p) * 3.dp.toPx() + 1f),
                )
            }
        }
        Box(
            Modifier
                .size(badge)
                .graphicsLayer {
                    val s = 0.95f + 0.07f * pulse
                    scaleX = s
                    scaleY = s
                }
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(theme.accent, theme.accentDeep)))
                .border(2.dp, Color.White.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(theme.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(badge * 0.52f))
        }
    }
}

/** A small glass tile: a label over a value. */
@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, accent: Color? = null) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, color = accent ?: Color.White.copy(alpha = 0.55f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, style = TabularDigits, maxLines = 1)
    }
}

/** The stranger photo in a camera-viewfinder frame, with a scan line moving over it. */
@Composable
private fun CapturedPhoto(photo: ImageBitmap, accent: Color, scan: Float, modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black)
            .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(18.dp)),
    ) {
        Image(photo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            // Scan line with a soft trail.
            val y = size.height * scan
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.28f)), startY = y - 60.dp.toPx(), endY = y),
                topLeft = Offset(0f, y - 60.dp.toPx()),
                size = Size(size.width, 60.dp.toPx()),
            )
            drawLine(accent.copy(alpha = 0.9f), Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
            // Viewfinder corners.
            val inset = 14.dp.toPx()
            val len = 26.dp.toPx()
            val w = 3.dp.toPx()
            listOf(
                Offset(inset, inset) to Offset(1f, 1f),
                Offset(size.width - inset, inset) to Offset(-1f, 1f),
                Offset(inset, size.height - inset) to Offset(1f, -1f),
                Offset(size.width - inset, size.height - inset) to Offset(-1f, -1f),
            ).forEach { (corner, dir) ->
                drawLine(Color.White, corner, corner + Offset(len * dir.x, 0f), strokeWidth = w, cap = StrokeCap.Round)
                drawLine(Color.White, corner, corner + Offset(0f, len * dir.y), strokeWidth = w, cap = StrokeCap.Round)
            }
        }
        // Bottom-left, tucked inside the viewfinder corner, so it doesn't cover the captured face.
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 22.dp, bottom = 22.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.alarm_captured), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        }
    }
}

/**
 * "Facial embedding stored for police report": a bold, high-contrast bar under the captured photo, with
 * a fingerprint badge and a softly pulsing border so it is seen at a glance.
 */
@Composable
private fun EvidenceStoredBar(text: String, accent: Color, pulse: Float, modifier: Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .border(2.dp, Color.White.copy(alpha = 0.45f + 0.45f * pulse), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Fingerprint, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF4ADE80), modifier = Modifier.size(22.dp))
    }
}

/** Big white DISMISS pill: a light sheen sweeps across it, and it presses in when touched. */
@Composable
private fun DismissButton(text: String, color: Color, sheen: Float, onClick: () -> Unit, modifier: Modifier, height: Dp) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        if (pressed) 0.95f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "dismissPress",
    )
    Box(
        modifier
            .height(height)
            .graphicsLayer {
                scaleX = press
                scaleY = press
            }
            .clip(RoundedCornerShape(50))
            .background(Color.White)
            .drawWithContent {
                drawContent()
                val x = (sheen * 1.8f - 0.4f) * size.width
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, color.copy(alpha = 0.16f), Color.Transparent),
                        start = Offset(x, 0f),
                        end = Offset(x + size.width * 0.35f, size.height),
                    ),
                )
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Close, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, color = color, fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
        }
    }
}

/** Text whose characters roll up when they change (the alarm clock's ticking seconds). */
@Composable
private fun RollingText(text: String, style: TextStyle) {
    Row {
        text.forEach { char ->
            // Each character rolls inside its own slot (clipped), so nothing spills while it changes.
            AnimatedContent(
                targetState = char,
                modifier = Modifier.clipToBounds(),
                contentAlignment = Alignment.Center,
                transitionSpec = {
                    (slideInVertically(tween(300)) { it / 2 } + fadeIn(tween(300))) togetherWith
                        (slideOutVertically(tween(300)) { -it / 2 } + fadeOut(tween(150))) using
                        SizeTransform(clip = true)
                },
                label = "clockDigit",
            ) { c -> Text(c.toString(), style = style, maxLines = 1) }
        }
    }
}

private fun bestFormat(locale: java.util.Locale, skeleton: String): SimpleDateFormat =
    SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale)

/**
 * Debug demo (demo video playing): the alarm page's clock shows 12 November 2026, in the afternoon /
 * evening: the real time of day, with a morning hour shown as the same hour PM. Seconds keep ticking.
 */
private fun demoClock(realMillis: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = realMillis }
    c.set(Calendar.YEAR, 2026)
    c.set(Calendar.MONTH, Calendar.NOVEMBER)
    c.set(Calendar.DAY_OF_MONTH, 12)
    if (c.get(Calendar.HOUR_OF_DAY) < 12) c.add(Calendar.HOUR_OF_DAY, 12)
    return c.timeInMillis
}

private fun sameDay(a: Long, b: Long): Boolean {
    val x = Calendar.getInstance().apply { timeInMillis = a }
    val y = Calendar.getInstance().apply { timeInMillis = b }
    return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
}

/** How long the alarm has rung: "0:42", "12:05", "1:02:09". */
private fun elapsed(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
