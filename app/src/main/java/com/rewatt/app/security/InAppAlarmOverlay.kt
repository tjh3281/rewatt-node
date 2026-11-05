package com.rewatt.app.security

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rewatt.app.R
import com.rewatt.app.core.AppGraph
import com.rewatt.app.perception.CameraCaptureService
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val AlarmRed = Color(0xFFB91C1C)
private val AlarmRedDark = Color(0xFF7F1D1D)
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
 * Full-screen red alarm page (fire or intruder): pulsing heading, a live clock that follows the phone's
 * current time (12 / 24 h as set on the phone), when the alarm started and how long it has rung, the
 * stranger photo (if any), and one big DISMISS button. It takes every touch, keeps the screen on and
 * ignores Back, so the alarm is only stopped with DISMISS. Landscape: the info on the left, the photo
 * and DISMISS on the right. [startedAtMillis] null: when this page opened.
 */
@Composable
internal fun AlarmFullScreen(
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

    val transition = rememberInfiniteTransition(label = "alarm")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    // Ticks on each new second of the wall clock, so the clock never lags or skips.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L - value % 1_000L)
        }
    }
    val started = remember(startedAtMillis) { startedAtMillis ?: System.currentTimeMillis() }
    val locale = context.resources.configuration.locales[0]
    val is24Hour = android.text.format.DateFormat.is24HourFormat(context)
    val clockFormat = remember(locale, is24Hour) { bestFormat(locale, if (is24Hour) "Hms" else "hmsa") }
    val dateFormat = remember(locale) { bestFormat(locale, "EEEEdMMMMyyyy") }
    val startedFormat = remember(locale, is24Hour, started) {
        // The date as well when the alarm started on an earlier day.
        bestFormat(locale, (if (sameDay(started, System.currentTimeMillis())) "" else "dMMM") + if (is24Hour) "Hms" else "hmsa")
    }
    val startedText = stringResource(R.string.alarm_started, startedFormat.format(Date(started)), elapsed(now - started))

    val photo by produceState<ImageBitmap?>(null, photoPath) {
        value = photoPath?.let { path ->
            withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
        }
    }

    val info: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = Color(0xFFFDE047),
                modifier = Modifier
                    .size(36.dp)
                    .scale(0.9f + 0.2f * pulse),
            )
            Spacer(Modifier.width(10.dp))
            Text(heading, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        }
        Text(message, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            clockFormat.format(Date(now)),
            color = Color.White,
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
            style = TabularDigits,
            maxLines = 1,
        )
        Text(dateFormat.format(Date(now)), color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
        Text(
            startedText,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            style = TabularDigits,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.18f))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
        Text(detail, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, textAlign = TextAlign.Center)
    }
    val photoBox: @Composable (Modifier) -> Unit = { modifier ->
        photo?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black),
            )
        }
    }
    val dismiss: @Composable () -> Unit = {
        Button(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = AlarmRed),
        ) {
            Text(stringResource(R.string.cctv_dismiss).uppercase(), fontSize = 24.sp, fontWeight = FontWeight.Black)
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(lerp(AlarmRed, AlarmRedDark, pulse * 0.6f))
            // Takes every touch so nothing behind the alarm can be tapped.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
            .systemBarsPadding(),
    ) {
        if (maxWidth > maxHeight) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) { info() }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    if (photo != null) photoBox(Modifier.fillMaxWidth().weight(1f))
                    dismiss()
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                info()
                if (photo != null) {
                    photoBox(Modifier.fillMaxWidth().weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                dismiss()
            }
        }
    }
}

private fun bestFormat(locale: java.util.Locale, skeleton: String): SimpleDateFormat =
    SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale)

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
