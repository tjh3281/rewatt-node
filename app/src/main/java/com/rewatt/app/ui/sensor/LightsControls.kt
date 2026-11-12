package com.rewatt.app.ui.sensor

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rewatt.app.energy.LightsController
import com.rewatt.app.energy.LightsState
import java.util.Calendar
import java.util.Date

private val BulbYellow = Color(0xFFF9E231)
private val GlassGrey = Color(0xFFC9CBCD)
private val BaseGrey = Color(0xFF919497)
private val BaseStripe = Color(0xFF76797C)
private val AircondBlue = Color(0xFF38BDF8)
private val IconOffGrey = Color(0xFFB8BBBE)
private val FanTeal = Color(0xFF14B8A6)

/**
 * Flat light bulb: all grey when off, yellow glass with three small "glow" dots on its left when on.
 * [on] null (no reading yet) draws the off bulb faded.
 */
@Composable
internal fun BulbIcon(on: Boolean?, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val glass by animateColorAsState(if (on == true) BulbYellow else GlassGrey, label = "bulbGlass")
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f // drawn on a 24×24 grid
        val alpha = if (on == null) 0.45f else 1f
        if (on == true) {
            // Soft halo so a lit bulb stands out even when small.
            drawCircle(
                Brush.radialGradient(
                    listOf(BulbYellow.copy(alpha = 0.55f), Color.Transparent),
                    center = Offset(14f * u, 9.4f * u),
                    radius = 12f * u,
                ),
                radius = 12f * u,
                center = Offset(14f * u, 9.4f * u),
            )
            listOf(Offset(4.2f, 4.6f), Offset(2.4f, 10.2f), Offset(4.2f, 15.8f)).forEach {
                drawCircle(BulbYellow, radius = 1.4f * u, center = Offset(it.x * u, it.y * u))
            }
        }
        // Glass: round top narrowing into the neck.
        drawCircle(glass, radius = 6.6f * u, center = Offset(14f * u, 9.4f * u), alpha = alpha)
        drawRect(glass, topLeft = Offset(11.1f * u, 12.5f * u), size = Size(5.8f * u, 5.8f * u), alpha = alpha)
        // Screw base with two thread stripes.
        drawRoundRect(
            BaseGrey,
            topLeft = Offset(10.8f * u, 18f * u),
            size = Size(6.4f * u, 4.8f * u),
            cornerRadius = CornerRadius(1.6f * u),
            alpha = alpha,
        )
        listOf(19.6f, 21.4f).forEach { y ->
            drawLine(
                BaseStripe,
                start = Offset(10.4f * u, (y + 0.4f) * u),
                end = Offset(17.6f * u, (y - 0.4f) * u),
                strokeWidth = 1.1f * u,
                cap = StrokeCap.Round,
                alpha = alpha,
            )
        }
    }
}

/** Snowflake in the bulb's style: grey when off, blue with three small dots on its left when on. */
@Composable
internal fun AircondIcon(on: Boolean, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val tint by animateColorAsState(if (on) AircondBlue else IconOffGrey, label = "aircondTint")
    Box(modifier.size(size)) {
        if (on) {
            Canvas(Modifier.fillMaxSize()) {
                val u = this.size.minDimension / 24f
                drawCircle(
                    Brush.radialGradient(
                        listOf(AircondBlue.copy(alpha = 0.35f), Color.Transparent),
                        center = Offset(14f * u, 12f * u),
                        radius = 12f * u,
                    ),
                    radius = 12f * u,
                    center = Offset(14f * u, 12f * u),
                )
                listOf(Offset(4.2f, 4.6f), Offset(2.4f, 12f), Offset(4.2f, 19.4f)).forEach {
                    drawCircle(AircondBlue, radius = 1.4f * u, center = Offset(it.x * u, it.y * u))
                }
            }
        }
        Icon(
            Icons.Filled.AcUnit,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(size * 0.72f)
                .offset(x = -(size * 0.06f)),
        )
    }
}

/** Three-blade fan around a hub, drawn on a 24×24 grid in [tint]; it turns while [spinning]. */
@Composable
internal fun FanGlyph(tint: Color, spinning: Boolean, modifier: Modifier = Modifier) {
    val angle by rememberInfiniteTransition(label = "fanSpin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1200, easing = LinearEasing)),
        label = "fanAngle",
    )
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val center = Offset(12f * u, 12f * u)
        val blade = Path().apply {
            moveTo(12f * u, 12f * u)
            cubicTo(8.6f * u, 9.4f * u, 8.8f * u, 2.6f * u, 12.8f * u, 2.2f * u)
            cubicTo(16.6f * u, 2f * u, 16.4f * u, 8.2f * u, 12f * u, 12f * u)
            close()
        }
        rotate(if (spinning) angle else 0f, pivot = center) {
            repeat(3) { i ->
                rotate(i * 120f, pivot = center) { drawPath(blade, tint) }
            }
        }
        drawCircle(tint, radius = 2.6f * u, center = center)
        drawCircle(Color.White, radius = 1f * u, center = center)
    }
}

/** Fan in the bulb's style: grey when off, teal and turning with three small dots on its left when on. */
@Composable
internal fun FanIcon(on: Boolean, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val tint by animateColorAsState(if (on) FanTeal else IconOffGrey, label = "fanTint")
    Box(modifier.size(size)) {
        if (on) {
            Canvas(Modifier.fillMaxSize()) {
                val u = this.size.minDimension / 24f
                drawCircle(
                    Brush.radialGradient(
                        listOf(FanTeal.copy(alpha = 0.35f), Color.Transparent),
                        center = Offset(14f * u, 12f * u),
                        radius = 12f * u,
                    ),
                    radius = 12f * u,
                    center = Offset(14f * u, 12f * u),
                )
                listOf(Offset(4.2f, 4.6f), Offset(2.4f, 12f), Offset(4.2f, 19.4f)).forEach {
                    drawCircle(FanTeal, radius = 1.4f * u, center = Offset(it.x * u, it.y * u))
                }
            }
        }
        FanGlyph(
            tint = tint,
            spinning = on,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(size * 0.72f)
                .offset(x = -(size * 0.06f)),
        )
    }
}

/**
 * The lights, aircond and fan icons, stacked (landscape occupancy card). Tapping an icon switches it
 * on ⇄ off. The lights icon shows only while the smart plug is linked.
 */
@Composable
internal fun LightsAndAircondColumn(
    lights: LightsState,
    onToggleLights: () -> Unit,
    onToggleAircond: () -> Unit,
    onToggleFan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (lights.plugLinked) {
            BulbIcon(
                on = lights.lightsOn,
                size = 30.dp,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Switch,
                    onClick = onToggleLights,
                ),
            )
        }
        AircondIcon(
            on = lights.aircondOn,
            size = 28.dp,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
                onClick = onToggleAircond,
            ),
        )
        FanIcon(
            on = lights.fanOn,
            size = 28.dp,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
                onClick = onToggleFan,
            ),
        )
    }
}

/** Size of [CeilingLight]: the light itself is the top [CEILING_LIGHT_DRAWN_HEIGHT]; the rest is tap area. */
internal val CEILING_LIGHT_WIDTH = 76.dp
internal val CEILING_LIGHT_HEIGHT = 26.dp
internal val CEILING_LIGHT_DRAWN_HEIGHT = 16.dp

/** 0 (off) → 1 (on), eased, so the bulb and the card light up and dim smoothly. */
@Composable
internal fun lightGlow(on: Boolean?): Float =
    animateFloatAsState(if (on == true) 1f else 0f, animationSpec = tween(durationMillis = 700), label = "lightGlow").value

/**
 * A modern surface-mounted ceiling light, flush with the top edge of the box: a thin mounting plate, a
 * slim housing and a frosted diffuser. Off: light grey diffuser. On ([glow] 1): the diffuser glows warm
 * white and light spills down from it. Unknown (no reading yet): the off light, faded. Only the top
 * [CEILING_LIGHT_DRAWN_HEIGHT] is drawn; the space below makes it easier to tap.
 */
@Composable
internal fun CeilingLight(on: Boolean?, glow: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.size(CEILING_LIGHT_WIDTH, CEILING_LIGHT_HEIGHT)) {
        val u = size.width / 76f // drawn on a 76-wide grid, 16 high
        val faded = if (on == null) 0.5f else 1f

        // Light spilling down (on), drawn first so the fitting sits on top of it.
        if (glow > 0f) {
            val center = Offset(38f * u, 13f * u)
            drawOval(
                Brush.radialGradient(
                    listOf(Color(0xFFFFF4C2).copy(alpha = 0.9f * glow), Color(0xFFFFE27A).copy(alpha = 0f)),
                    center = center,
                    radius = 34f * u,
                ),
                topLeft = Offset(4f * u, -6f * u),
                size = Size(68f * u, 38f * u),
            )
        }

        // Mounting plate on the ceiling, then the slim housing.
        drawRoundRect(
            Color(0xFFB8BCC2),
            topLeft = Offset(24f * u, 0f),
            size = Size(28f * u, 3f * u),
            cornerRadius = CornerRadius(1f * u),
            alpha = faded,
        )
        drawRoundRect(
            Color(0xFFF3F4F6),
            topLeft = Offset(6f * u, 2.5f * u),
            size = Size(64f * u, 6f * u),
            cornerRadius = CornerRadius(2.5f * u),
            alpha = faded,
        )
        drawLine(Color(0xFFD1D5DB), Offset(7f * u, 8.4f * u), Offset(69f * u, 8.4f * u), strokeWidth = 0.6f * u, alpha = faded)

        // Frosted diffuser: a shallow dome under the housing.
        val diffuserTop = lerp(Color(0xFFE5E7EB), Color(0xFFFFFDF2), glow)
        val diffuserBottom = lerp(Color(0xFFD1D5DB), Color(0xFFFFE68A), glow)
        drawRoundRect(
            Brush.verticalGradient(listOf(diffuserTop, diffuserBottom), startY = 8f * u, endY = 15.5f * u),
            topLeft = Offset(8f * u, 8f * u),
            size = Size(60f * u, 7.5f * u),
            cornerRadius = CornerRadius(7f * u, 5f * u),
            alpha = faded,
        )
        // A soft sheen along the diffuser.
        drawLine(
            Color.White.copy(alpha = 0.7f),
            Offset(16f * u, 10f * u),
            Offset(40f * u, 10f * u),
            strokeWidth = 1f * u,
            cap = StrokeCap.Round,
            alpha = faded,
        )
    }
}

/**
 * The ceiling light lighting the card like a projector ([glow] 0…1): a beam of warm light from the
 * diffuser, widening down over the words to the bottom of the card, a bright spot right under the
 * light, and a soft warm wash over the whole card. [lightStartX] / [lightWidth] / [lightBottomY]: where
 * the light is, from the card's top-left corner.
 */
internal fun Modifier.ceilingLightBeam(glow: Float, lightStartX: Dp, lightWidth: Dp, lightBottomY: Dp): Modifier = drawBehind {
    if (glow <= 0f) return@drawBehind
    val top = lightBottomY.toPx()
    val left = lightStartX.toPx() + lightWidth.toPx() * 0.12f
    val right = lightStartX.toPx() + lightWidth.toPx() * 0.88f
    val centerX = (left + right) / 2f
    val bottom = size.height

    // The whole card a little warmer.
    drawRect(Color(0xFFFFF6D6).copy(alpha = 0.45f * glow))

    // Beam: a wide soft cone, then a narrower bright core inside it.
    fun cone(spread: Float, alphaTop: Float, alphaMid: Float) {
        val path = Path().apply {
            moveTo(left, top)
            lineTo(right, top)
            lineTo(right + spread, bottom)
            lineTo(left - spread, bottom)
            close()
        }
        drawPath(
            path,
            Brush.verticalGradient(
                0f to Color(0xFFFFE27A).copy(alpha = alphaTop * glow),
                0.55f to Color(0xFFFFEDB0).copy(alpha = alphaMid * glow),
                1f to Color(0xFFFFF4CC).copy(alpha = 0f),
                startY = top,
                endY = bottom,
            ),
        )
    }
    cone(spread = (right - left) * 1.1f, alphaTop = 0.45f, alphaMid = 0.22f)
    cone(spread = (right - left) * 0.45f, alphaTop = 0.65f, alphaMid = 0.3f)

    // Hot spot right under the diffuser.
    drawOval(
        Brush.radialGradient(
            listOf(Color(0xFFFFF7D1).copy(alpha = 0.95f * glow), Color(0xFFFFE27A).copy(alpha = 0f)),
            center = Offset(centerX, top),
            radius = (right - left) * 0.7f,
        ),
        topLeft = Offset(centerX - (right - left) * 0.7f, top - (right - left) * 0.25f),
        size = Size((right - left) * 1.4f, (right - left) * 0.6f),
    )
}

/** Aircond switch in the Room card: "AC ON" (blue) / "AC OFF" (grey); tap to switch. */
@Composable
internal fun AircondPill(on: Boolean, onToggle: () -> Unit) {
    DevicePill(
        label = if (on) "AC ON" else "AC OFF",
        on = on,
        onColor = Color(0xFF0284C7),
        onBackground = Color(0xFFE0F2FE),
        onToggle = onToggle,
    ) { tint -> Icon(Icons.Filled.AcUnit, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp)) }
}

/** Fan switch in the Room card, same style as [AircondPill]: "FAN ON" (teal, turning) / "FAN OFF" (grey). */
@Composable
internal fun FanPill(on: Boolean, onToggle: () -> Unit) {
    DevicePill(
        label = if (on) "FAN ON" else "FAN OFF",
        on = on,
        onColor = Color(0xFF0D9488),
        onBackground = Color(0xFFCCFBF1),
        onToggle = onToggle,
    ) { tint -> FanGlyph(tint = tint, spinning = on, modifier = Modifier.size(14.dp)) }
}

@Composable
private fun DevicePill(
    label: String,
    on: Boolean,
    onColor: Color,
    onBackground: Color,
    onToggle: () -> Unit,
    icon: @Composable (tint: Color) -> Unit,
) {
    val background by animateColorAsState(if (on) onBackground else Color.White.copy(alpha = 0.7f), label = "devicePillBg")
    val tint by animateColorAsState(if (on) onColor else Color(0xFF9CA3AF), label = "devicePillTint")
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(role = Role.Switch, onClick = onToggle)
            .padding(start = 7.dp, end = 9.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon(tint)
        Spacer(Modifier.width(3.dp))
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = tint, maxLines = 1, softWrap = false)
    }
}

/** Small square button in the Room card: tinted while [active]. */
@Composable
internal fun RoomActionButton(
    label: String,
    detail: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    activeColor: Color,
    activeBackground: Color,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(if (active) activeBackground else Color.White, label = "roomButtonBg")
    val tint by animateColorAsState(if (active) activeColor else Color(0xFF6B7280), label = "roomButtonTint")
    Column(
        modifier = Modifier
            .width(60.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF09090B), maxLines = 1)
        Text(detail, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1)
    }
}

/** The Schedule and Auto-off buttons of the Room card. */
@Composable
internal fun RoomLightsButtons(lights: LightsState, onOpenSchedule: () -> Unit, onToggleAutoOff: () -> Unit) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RoomActionButton(
            label = "Schedule",
            detail = lights.schedule?.let { "${if (it.turnOn) "ON" else "OFF"} ${formatTime(context, it.atMillis)}" } ?: "None",
            icon = Icons.Outlined.Schedule,
            active = lights.schedule != null,
            activeColor = Color(0xFF4F46E5),
            activeBackground = Color(0xFFE0E7FF),
            onClick = onOpenSchedule,
        )
        RoomActionButton(
            label = "Auto-off",
            detail = if (lights.autoOff) "ON" else "OFF",
            icon = Icons.Outlined.Eco,
            active = lights.autoOff,
            activeColor = Color(0xFF15803D),
            activeBackground = Color(0xFFDCFCE7),
            onClick = onToggleAutoOff,
        )
    }
}

/**
 * Landscape ENERGY: Schedule and Auto-off as a small floating pill just left of the menu, styled like
 * the menu's tabs (icon over a short label). Active: the button fills with its colour (indigo /
 * green) and the label shows the state ("OFF 21:30", "AUTO-OFF ON").
 */
@Composable
internal fun LightsMenuPill(lights: LightsState, onOpenSchedule: () -> Unit, onToggleAutoOff: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .height(BOTTOM_BAR_HEIGHT)
            .floatingPill(),
    ) {
        LightsMenuButton(
            label = lights.schedule?.let { "${if (it.turnOn) "ON" else "OFF"} ${formatTime(context, it.atMillis)}" } ?: "SCHEDULE",
            icon = Icons.Outlined.Schedule,
            active = lights.schedule != null,
            activeColor = Color(0xFF4F46E5),
            activeBackground = Color(0xFFE0E7FF),
            onClick = onOpenSchedule,
        )
        LightsMenuButton(
            label = if (lights.autoOff) "AUTO-OFF ON" else "AUTO-OFF",
            icon = Icons.Outlined.Eco,
            active = lights.autoOff,
            activeColor = Color(0xFF15803D),
            activeBackground = Color(0xFFDCFCE7),
            onClick = onToggleAutoOff,
        )
    }
}

@Composable
private fun LightsMenuButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    activeColor: Color,
    activeBackground: Color,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(if (active) activeBackground else Color.Transparent, label = "menuButtonBg")
    val tint by animateColorAsState(if (active) activeColor else Color(0xFF6B7280), label = "menuButtonTint")
    Column(
        modifier = Modifier
            .width(78.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp, color = tint, maxLines = 1, softWrap = false)
    }
}

/** The occupancy card while the Auto-off countdown runs: "LIGHTS OFF IN 5 s". */
@Composable
internal fun LightsOffCountdown(secondsLeft: Int, wide: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BulbIcon(on = true, size = 16.dp)
            Spacer(Modifier.width(4.dp))
            Text(
                "LIGHTS OFF IN",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF92400E),
                maxLines = 1,
                softWrap = false,
            )
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$secondsLeft", fontSize = if (wide) 40.sp else 44.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB45309))
            Text("s", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB45309), modifier = Modifier.padding(bottom = 8.dp))
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text("ROOM EMPTY", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB45309), maxLines = 1)
        }
    }
}

/**
 * Half-screen lights schedule (card style): switch the lights on or off later (in 15 min … 2 h, or at
 * a picked time). One pending schedule at a time; a new one replaces it. Switching them right now is a
 * tap on the lights icon, so it isn't repeated here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LightsScheduleSheet(lights: LightsState, controller: LightsController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var laterOn by remember { mutableStateOf(lights.lightsOn != true) }
    var pickingTime by remember { mutableStateOf(false) }
    val halfScreen = (LocalConfiguration.current.screenHeightDp / 2).dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFFF9FAFB),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = halfScreen)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Title, current state and the pending schedule.
            Row(verticalAlignment = Alignment.CenterVertically) {
                BulbIcon(on = lights.lightsOn, size = 30.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Lights schedule", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF09090B))
                    Text(
                        lights.schedule?.let { "Turns ${if (it.turnOn) "ON" else "OFF"} at ${formatTime(context, it.atMillis)}" }
                            ?: "No schedule",
                        fontSize = 12.sp,
                        color = Color(0xFF6B7280),
                    )
                }
                if (lights.schedule != null) {
                    TextButton(onClick = controller::cancelSchedule) { Text("Cancel") }
                }
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFECEEEF))
                    .padding(4.dp),
            ) {
                listOf(true to "Turn on", false to "Turn off").forEach { (on, label) ->
                    val selected = laterOn == on
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) Color.White else Color.Transparent)
                            .clickable(role = Role.RadioButton) { laterOn = on }
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    ) {
                        Text(
                            label,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) Color(0xFF09090B) else Color(0xFF6B7280),
                        )
                    }
                }
            }
            val delays = listOf(15 to "In 15 min", 30 to "In 30 min", 60 to "In 1 hour", 120 to "In 2 hours")
            delays.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { (minutes, label) ->
                        ChoiceCard(
                            onClick = {
                                controller.schedule(laterOn, System.currentTimeMillis() + minutes * 60_000L)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Column {
                                Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF09090B))
                                Text(
                                    "${if (laterOn) "On" else "Off"} at ${formatTime(context, System.currentTimeMillis() + minutes * 60_000L)}",
                                    fontSize = 11.sp,
                                    color = Color(0xFF6B7280),
                                )
                            }
                        }
                    }
                }
            }
            ChoiceCard(onClick = { pickingTime = true }) {
                Column {
                    Text("Pick a time", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF09090B))
                    Text("Turn ${if (laterOn) "on" else "off"} at a time you choose", fontSize = 11.sp, color = Color(0xFF6B7280))
                }
            }
        }

        if (pickingTime) {
            val now = Calendar.getInstance()
            val pickerState = rememberTimePickerState(
                initialHour = now.get(Calendar.HOUR_OF_DAY),
                initialMinute = now.get(Calendar.MINUTE),
                is24Hour = android.text.format.DateFormat.is24HourFormat(context),
            )
            AlertDialog(
                onDismissRequest = { pickingTime = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            controller.schedule(laterOn, nextOccurrence(pickerState.hour, pickerState.minute))
                            pickingTime = false
                            onDismiss()
                        },
                    ) { Text("Set") }
                },
                dismissButton = { TextButton(onClick = { pickingTime = false }) { Text("Cancel") } },
                title = { Text("Turn ${if (laterOn) "on" else "off"} at") },
                text = { TimePicker(state = pickerState) },
            )
        }
    }
}

/** White rounded card; tappable when [onClick] is set. */
@Composable
private fun ChoiceCard(onClick: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClick = onClick) else Modifier),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { content() }
    }
}

/** The next time the clock shows [hour]:[minute] (today, or tomorrow if that has passed). */
private fun nextOccurrence(hour: Int, minute: Int): Long {
    val at = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (at.timeInMillis <= System.currentTimeMillis()) at.add(Calendar.DAY_OF_MONTH, 1)
    return at.timeInMillis
}

private fun formatTime(context: android.content.Context, millis: Long): String =
    android.text.format.DateFormat.getTimeFormat(context).format(Date(millis))
