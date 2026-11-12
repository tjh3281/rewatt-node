package com.rewatt.app.ui.security

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FaceRetouchingNatural
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rewatt.app.R
import com.rewatt.app.security.EventResult
import com.rewatt.app.security.FaceSample
import com.rewatt.app.security.MatchCalibration
import com.rewatt.app.security.Person
import com.rewatt.app.security.SecurityEvent
import com.rewatt.app.security.SecurityUiState
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val CardGrey = Color(0xFFECEEEF)
private val Ink = Color(0xFF09090B)
private val Muted = Color(0xFF6B7280)
private val Danger = Color(0xFFB91C1C)
private val Ok = Color(0xFF15803D)

/**
 * SECURITY tab, side panel: title (portrait) + the CCTV arm card. The tab shares its camera box with
 * ENERGY (laid out by SensorScreen), so only this panel and [SecurityHistorySection] change when
 * switching tabs. [compact] = the slim landscape column. FAMILY faces, permissions, strictness and
 * the test tools live in Settings → Security & permissions.
 */
@Composable
fun SecuritySidePanel(
    viewModel: SecurityViewModel,
    onRequestArm: () -> Unit,
    compact: Boolean,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val faces by viewModel.familyFaces.collectAsStateWithLifecycle()
    val canArm = faces.isNotEmpty()
    val onArmChange: (Boolean) -> Unit = { checked -> if (checked) onRequestArm() else viewModel.disarm() }
    if (!compact) {
        Text(stringResource(R.string.sec_title), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
    }
    ArmCard(state = state, canArm = canArm, onArmChange = onArmChange, compact = compact)
}

/**
 * SECURITY tab, CCTV event history + its detail dialog: below the camera in portrait; under the arm
 * card in landscape, simplified ([compact]: no storage line, two-line rows).
 */
@Composable
fun SecurityHistorySection(viewModel: SecurityViewModel, compact: Boolean = false) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    var openEvent by remember { mutableStateOf<SecurityEvent?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
        Text(stringResource(R.string.sec_history), fontSize = if (compact) 13.sp else 16.sp, fontWeight = FontWeight.Bold, color = Ink)
        if (!compact) {
            Text(
                stringResource(R.string.sec_storage, Formatter.formatShortFileSize(context, events.sumOf { it.sizeBytes })),
                fontSize = 12.sp,
                color = Muted,
            )
        }
        val finished = events.filter { it.result == EventResult.STRANGER || it.result == EventResult.NO_FACE || it.id == state.activeEventId }
        if (finished.isEmpty()) {
            Text(stringResource(R.string.sec_history_empty), fontSize = if (compact) 11.sp else 13.sp, color = Muted)
        }
        finished.forEach { event ->
            if (compact) CompactEventRow(event = event, onClick = { openEvent = event }) else EventRow(event = event, onClick = { openEvent = event })
        }
        Spacer(Modifier.height(8.dp))
    }

    openEvent?.let { event ->
        EventDetailDialog(
            event = event,
            clips = viewModel.clipFiles(event.id),
            photos = viewModel.photoFiles(event.id),
            onDelete = {
                viewModel.deleteEvent(event.id)
                openEvent = null
            },
            onClose = { openEvent = null },
        )
    }
}

/** Arm / Disarm card. [compact] (landscape column): smaller, no hint text. */
@Composable
private fun ArmCard(
    state: SecurityUiState,
    canArm: Boolean,
    onArmChange: (Boolean) -> Unit,
    compact: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (compact) 20.dp else 24.dp),
        colors = CardDefaults.cardColors(containerColor = if (state.armed) Color(0xFFFEE2E2) else CardGrey),
    ) {
        val title = stringResource(if (state.armed) R.string.sec_armed else R.string.sec_disarmed)
        val titleColor = if (state.armed) Danger else Ink
        val switch: @Composable () -> Unit = {
            Switch(
                checked = state.armed,
                enabled = canArm || state.armed,
                onCheckedChange = onArmChange,
                colors = SwitchDefaults.colors(checkedTrackColor = Danger),
            )
        }
        if (compact) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Shield, contentDescription = null, tint = titleColor, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.weight(1f))
                    switch()
                }
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Black, color = titleColor, maxLines = 1)
            }
        } else {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Shield, contentDescription = null, tint = titleColor, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = title,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black,
                        color = titleColor,
                        modifier = Modifier.weight(1f),
                    )
                    switch()
                }
                val armHint = when {
                    state.armed -> null
                    !canArm -> stringResource(R.string.sec_arm_hint_no_faces)
                    else -> stringResource(R.string.sec_arm_desc_disarmed)
                }
                armHint?.let { Text(text = it, fontSize = 13.sp, color = Muted) }
            }
        }
    }
}

/**
 * Settings → "Security & permissions": everything that used to clutter the SECURITY tab — the
 * permission/phone-setup fixes (re-checked whenever the user returns), FAMILY faces, the
 * match-strictness slider and the test tools (CCTV check, intruder alarm, fire alarm).
 */
@Composable
fun SecuritySettingsSection(
    viewModel: SecurityViewModel,
    onRequestMicrophone: () -> Unit,
    onRequestCamera: () -> Unit,
    onTestFireAlarm: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val faces by viewModel.familyFaces.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val threshold by viewModel.threshold.collectAsStateWithLifecycle()
    val phoneSetupConfirmed by viewModel.phoneSetupConfirmed.collectAsStateWithLifecycle()
    var showRegistration by remember { mutableStateOf(false) }
    var registrationFor by remember { mutableStateOf<Person?>(null) }

    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumeTick++
        onPauseOrDispose { }
    }
    val cameraGranted = remember(resumeTick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    val micGranted = remember(resumeTick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val fullScreenAllowed = remember(resumeTick) { viewModel.canUseFullScreenIntent() }
    val batteryUnrestricted = remember(resumeTick) { viewModel.isIgnoringBatteryOptimizations() }
    val allGood = cameraGranted && micGranted && fullScreenAllowed && batteryUnrestricted && phoneSetupConfirmed

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.settings_security_header), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)

        if (allGood) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFDCFCE7)),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Shield, contentDescription = null, tint = Ok, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_all_permissions_ok), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ok)
                }
            }
        }
        if (!fullScreenAllowed) {
            WarningCard(stringResource(R.string.sec_perm_fullscreen)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")),
                    )
                }
            }
        }
        if (!cameraGranted) WarningCard(stringResource(R.string.sec_perm_camera), onRequestCamera)
        if (!micGranted) WarningCard(stringResource(R.string.sec_perm_mic), onRequestMicrophone)
        if (!batteryUnrestricted) {
            WarningCard(stringResource(R.string.sec_perm_battery)) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                    )
                }.onFailure {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }
        // Phone-maker restrictions (lock-screen pop-ups, autostart) can't be read by any app, so the
        // user confirms them once with "Done".
        if (!phoneSetupConfirmed) {
            PhoneSetupCard(
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                    )
                },
                onDone = viewModel::confirmPhoneSetup,
            )
        }

        // ── FAMILY faces ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CardGrey),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.FaceRetouchingNatural, contentDescription = null, tint = Ink)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.sec_family_title), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Ink)
                        Text(stringResource(R.string.sec_family_count, people.size, faces.size), fontSize = 12.sp, color = Muted)
                    }
                    if (faces.isNotEmpty() && !state.armed) {
                        TextButton(onClick = viewModel::clearFaces) { Text(stringResource(R.string.sec_clear_all), color = Danger) }
                    }
                }
                people.forEach { person ->
                    key(person.id) {
                        PersonRow(
                            person = person,
                            samples = faces.filter { it.personId == person.id },
                            editable = !state.armed,
                            onDeleteSample = viewModel::deleteFace,
                            onDeletePerson = { viewModel.deletePerson(person.id) },
                            onAddPhotos = {
                                if (cameraGranted) {
                                    registrationFor = person
                                    showRegistration = true
                                } else {
                                    onRequestCamera()
                                }
                            },
                        )
                    }
                }
                Button(
                    onClick = {
                        if (cameraGranted) {
                            registrationFor = null
                            showRegistration = true
                        } else {
                            onRequestCamera()
                        }
                    },
                    enabled = !state.armed,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White),
                ) {
                    Text(
                        stringResource(if (state.armed) R.string.sec_register_disarm_first else R.string.sec_register),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // Match strictness (moved from the SECURITY tab)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CardGrey),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.sec_strictness), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Ink, modifier = Modifier.weight(1f))
                    Text("%.2f".format(threshold), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Ink)
                }
                Slider(
                    value = threshold,
                    onValueChange = { viewModel.setThreshold((it * 100).toInt() / 100f) },
                    valueRange = SecurityViewModel.THRESHOLD_RANGE,
                )
                Text(stringResource(R.string.sec_strictness_desc), fontSize = 12.sp, color = Muted)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        R.string.sec_calibration_line,
                        "%.2f".format(MatchCalibration.s0For(threshold)),
                        "%.2f".format(MatchCalibration.K),
                        "%.0f%%".format(MatchCalibration.PASS_CONFIDENCE * 100),
                    ),
                    fontSize = 11.sp,
                    color = Muted,
                )
            }
        }

        // ── Tests ──
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.settings_tests_header), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)

        // Test CCTV check (one photo, result only)
        val test = state.testCheck
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CardGrey),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.sec_test_check), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Ink)
                Text(stringResource(R.string.sec_test_check_desc), fontSize = 12.sp, color = Muted)
                Button(
                    onClick = { if (cameraGranted) viewModel.runTestCheck() else onRequestCamera() },
                    enabled = !test.running && test.countdown == 0,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White),
                ) {
                    Text(
                        text = when {
                            test.countdown > 0 -> stringResource(R.string.sec_test_countdown, test.countdown)
                            test.running -> stringResource(R.string.reg_checking)
                            else -> stringResource(R.string.sec_test_check)
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
                test.errorRes?.let { Text(stringResource(it), fontSize = 12.sp, color = Danger) }
                if (test.result != null && !test.running && test.countdown == 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val stranger = test.result == EventResult.STRANGER
                        val family = test.result == EventResult.FAMILY
                        Pill(
                            text = resultLabel(test.result) + scoreSuffix(test.score, test.confidence),
                            bg = when {
                                stranger -> Color(0xFFFEE2E2)
                                family -> Color(0xFFDCFCE7)
                                else -> Color.White
                            },
                            fg = when {
                                stranger -> Danger
                                family -> Ok
                                else -> Muted
                            },
                        )
                        Text(
                            stringResource(R.string.sec_test_counts, test.personCount, test.faceCount) +
                                (test.atMillis?.let { " · " + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(it)) } ?: ""),
                            fontSize = 12.sp,
                            color = Muted,
                        )
                    }
                    test.photoPath?.let { path ->
                        key(test.atMillis) {
                            FileImage(
                                path = path,
                                maxSide = 1200,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.FillWidth,
                            )
                        }
                    }
                }
            }
        }

        // Intruder alarm: siren, red screen, vibration, strobe.
        OutlinedButton(
            onClick = viewModel::testAlarm,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
        ) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.sec_test_alarm), fontWeight = FontWeight.Bold)
        }

        // Fire alarm: full fire-alert path without the SGP41; no MQTT.
        OutlinedButton(
            onClick = onTestFireAlarm,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
        ) {
            Icon(Icons.Outlined.LocalFireDepartment, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.settings_test_fire_alarm), fontWeight = FontWeight.Bold)
        }
    }

    if (showRegistration) {
        FaceRegistrationDialog(viewModel = viewModel, onClose = { showRegistration = false }, existing = registrationFor)
    }
}

/** " 0.52 · 96%" — raw cosine similarity and calibrated confidence C(s). */
private fun scoreSuffix(score: Float?, confidence: Float?): String = when {
    score == null -> ""
    confidence == null -> " %.2f".format(score)
    else -> " %.2f · %.0f%%".format(score, confidence * 100)
}

@Composable
private fun resultLabel(result: EventResult): String = stringResource(
    when (result) {
        EventResult.NO_PERSON -> R.string.sec_result_no_person
        EventResult.NO_FACE -> R.string.sec_result_no_face
        EventResult.FAMILY -> R.string.sec_result_family
        EventResult.STRANGER -> R.string.sec_result_stranger
    },
)

@Composable
private fun WarningCard(text: String, onFix: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = Color(0xFFB45309), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, fontSize = 12.sp, color = Color(0xFF78350F), modifier = Modifier.weight(1f))
            TextButton(onClick = onFix) { Text(stringResource(R.string.sec_perm_fix), fontWeight = FontWeight.Bold, color = Color(0xFFB45309)) }
        }
    }
}

@Composable
private fun PhoneSetupCard(onOpenSettings: () -> Unit, onDone: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = Color(0xFF1D4ED8), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sec_phone_setup_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF1E3A8A))
            }
            Text(stringResource(R.string.sec_phone_setup_body), fontSize = 12.sp, color = Color(0xFF1E3A8A))
            Text(stringResource(R.string.sec_phone_setup_items), fontSize = 12.sp, color = Color(0xFF1E3A8A), fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.sec_phone_setup_brands), fontSize = 11.sp, color = Color(0xFF3B82F6))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.sec_phone_setup_open), color = Color(0xFF1D4ED8), fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onDone,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D4ED8), contentColor = Color.White),
                ) {
                    Text(stringResource(R.string.sec_phone_setup_done), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, bg: Color, fg: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = fg)
    }
}

@Composable
private fun EventRow(event: SecurityEvent, onClick: () -> Unit) {
    val stranger = event.result == EventResult.STRANGER
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(event.startedAtMillis)),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = Ink,
                )
                val seconds = ((event.endedAtMillis ?: System.currentTimeMillis()) - event.startedAtMillis) / 1000
                Text("${seconds / 60}m ${seconds % 60}s · ${event.clipCount} clip · ${event.photoCount} photo", fontSize = 12.sp, color = Muted)
            }
            Pill(
                text = resultLabel(event.result) + (event.bestScore?.takeIf { stranger }?.let { " %.2f".format(it) } ?: ""),
                bg = if (stranger) Color(0xFFFEE2E2) else Color(0xFFF3F4F6),
                fg = if (stranger) Danger else Muted,
            )
        }
    }
}

/** Landscape log row: when, then the result. */
@Composable
private fun CompactEventRow(event: SecurityEvent, onClick: () -> Unit) {
    val stranger = event.result == EventResult.STRANGER
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(event.startedAtMillis)),
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                resultLabel(event.result),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (stranger) Danger else Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EventDetailDialog(
    event: SecurityEvent,
    clips: List<File>,
    photos: List<File>,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    var clipIndex by remember { mutableStateOf(0) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(event.startedAtMillis)),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Ink,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.sec_close)) }
            }
            Text(resultLabel(event.result), fontWeight = FontWeight.Bold, color = if (event.result == EventResult.STRANGER) Danger else Muted)

            if (clips.isNotEmpty()) {
                if (clips.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        clips.indices.forEach { i ->
                            OutlinedButton(onClick = { clipIndex = i }) {
                                Text(stringResource(R.string.sec_clip_part, i + 1), fontWeight = if (i == clipIndex) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
                val path = clips[clipIndex.coerceIn(clips.indices)].absolutePath
                AndroidView(
                    modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(14.dp)).background(Color.Black),
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                        }
                    },
                    update = { view ->
                        if (view.tag != path) {
                            view.tag = path
                            view.setVideoPath(path)
                            view.start()
                        }
                    },
                )
            }

            if (photos.isNotEmpty()) {
                Text(stringResource(R.string.sec_photos), fontWeight = FontWeight.Bold, color = Ink)
                photos.forEach { photo ->
                    FileImage(
                        path = photo.absolutePath,
                        maxSide = 1200,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.FillWidth,
                    )
                }
            }

            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
            ) {
                Text(stringResource(R.string.sec_delete), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** One registered person: name, photo count, thumbnails (each deletable), add photos / delete person. */
@Composable
private fun PersonRow(
    person: Person,
    samples: List<FaceSample>,
    editable: Boolean,
    onDeleteSample: (String) -> Unit,
    onDeletePerson: () -> Unit,
    onAddPhotos: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(person.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Ink)
                Text(stringResource(R.string.sec_person_photos, samples.size), fontSize = 12.sp, color = Muted)
            }
            if (editable) {
                TextButton(onClick = onAddPhotos) { Text(stringResource(R.string.sec_add_photos), color = Ink) }
                TextButton(onClick = onDeletePerson) { Text(stringResource(R.string.sec_delete), color = Danger) }
            }
        }
        if (samples.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                samples.forEach { face ->
                    Box {
                        FileImage(
                            path = face.thumbnailPath,
                            maxSide = 112,
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)),
                        )
                        if (editable) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xCC000000))
                                    .clickable { onDeleteSample(face.id) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.sec_delete), tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileImage(
    path: String,
    maxSide: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { decodeSampled(path, maxSide) }
    }
    bitmap?.let {
        Image(bitmap = it.asImageBitmap(), contentDescription = null, contentScale = contentScale, modifier = modifier)
    } ?: Box(modifier.background(Color(0xFFE5E7EB)))
}

private fun decodeSampled(path: String, maxSide: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()
