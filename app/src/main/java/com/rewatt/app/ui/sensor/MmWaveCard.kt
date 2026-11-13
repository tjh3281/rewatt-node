package com.rewatt.app.ui.sensor

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

private val RadarTeal = Color(0xFF0EA5E9)
private val PresentGreen = Color(0xFF16A34A)
private val AbsentGrey = Color(0xFF9CA3AF)

/**
 * The mmWave (presence) card in the landscape ENERGY column: a small radar scope with a sweeping beam,
 * then the presence: "Detected" or "Clear".
 *  - [moduleAttached] false (no ReWatt module): "--".
 *  - Attached: follows the room's occupancy ([occupied]), the same count as the occupancy card.
 */
@Composable
internal fun MmWaveCard(moduleAttached: Boolean, occupied: Boolean, modifier: Modifier = Modifier) {
    val present: Boolean? = if (moduleAttached) occupied else null
    val transition = rememberInfiniteTransition(label = "mmWave")
    val sweep by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2_200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "mmSweep")
    val ping by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "mmPing")
    val valueColor by animateColorAsState(
        when (present) {
            true -> PresentGreen
            false -> Color(0xFF6B7280)
            null -> Color(0xFF09090B)
        },
        label = "mmColor",
    )

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFECEEEF)),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Sensors, contentDescription = null, tint = RadarTeal, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                BasicText(
                    "mmWAVE",
                    style = TextStyle(fontWeight = FontWeight.Bold, color = Color(0xFF374151), letterSpacing = 0.3.sp),
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = 10.sp, stepSize = 0.5.sp),
                    modifier = Modifier.weight(1f),
                )
                PresenceDot(present == true, ping)
            }
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth().weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadarScope(
                    present = present == true,
                    sweep = sweep,
                    ping = ping,
                    modifier = Modifier.fillMaxHeight().aspectRatio(1.35f, matchHeightConstraintsFirst = true),
                )
                BasicText(
                    when (present) {
                        true -> "Detected"
                        false -> "Clear"
                        null -> "--"
                    },
                    style = TextStyle(fontWeight = FontWeight.Bold, color = valueColor),
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 16.sp, stepSize = 0.5.sp),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Green pulsing dot while presence is detected, grey otherwise. */
@Composable
private fun PresenceDot(present: Boolean, ping: Float) {
    Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
        if (present) {
            Box(
                Modifier
                    .size(12.dp)
                    .graphicsLayer {
                        val s = 0.5f + ping
                        scaleX = s
                        scaleY = s
                        alpha = (1f - ping) * 0.5f
                    }
                    .clip(CircleShape)
                    .background(PresentGreen),
            )
        }
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (present) PresentGreen else AbsentGrey))
    }
}

/**
 * A radar fan opening upwards from the bottom centre: range rings, a beam sweeping left ⇄ right, and a
 * target dot (with a ping ring) while presence is detected.
 */
@Composable
private fun RadarScope(present: Boolean, sweep: Float, ping: Float, modifier: Modifier) {
    val targetAlpha by animateFloatAsState(if (present) 1f else 0f, tween(400), label = "mmTarget")
    Canvas(modifier) {
        val origin = Offset(size.width / 2f, size.height)
        val radius = minOf(size.width / 2f / sin(Math.toRadians(60.0)).toFloat(), size.height) * 0.98f
        val start = -150f // 60° either side of straight up (−90°)
        val span = 120f
        fun point(deg: Float, r: Float): Offset {
            val a = Math.toRadians((deg - 90f).toDouble())
            return Offset(origin.x + r * cos(a).toFloat(), origin.y + r * sin(a).toFloat())
        }
        // Fan and range rings.
        drawArc(RadarTeal.copy(alpha = 0.08f), start, span, useCenter = true, topLeft = origin - Offset(radius, radius), size = Size(radius * 2, radius * 2))
        listOf(1f, 0.66f, 0.33f).forEach { f ->
            drawArc(
                RadarTeal.copy(alpha = 0.35f),
                start,
                span,
                useCenter = false,
                topLeft = origin - Offset(radius * f, radius * f),
                size = Size(radius * 2 * f, radius * 2 * f),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        drawLine(RadarTeal.copy(alpha = 0.25f), origin, point(-60f, radius), strokeWidth = 1.dp.toPx())
        drawLine(RadarTeal.copy(alpha = 0.25f), origin, point(60f, radius), strokeWidth = 1.dp.toPx())
        // Sweeping beam: a soft wedge of light with a bright line in the middle.
        val beam = -60f + 120f * sweep
        drawArc(
            RadarTeal.copy(alpha = 0.22f),
            beam - 90f - 7f,
            14f,
            useCenter = true,
            topLeft = origin - Offset(radius, radius),
            size = Size(radius * 2, radius * 2),
        )
        drawLine(RadarTeal.copy(alpha = 0.8f), origin, point(beam, radius), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        // The target, fading in / out with presence.
        if (targetAlpha > 0.01f) {
            val at = point(8f, radius * 0.55f)
            drawCircle(PresentGreen.copy(alpha = (1f - ping) * 0.45f * targetAlpha), radius = 3.dp.toPx() + 7.dp.toPx() * ping, center = at)
            drawCircle(PresentGreen.copy(alpha = targetAlpha), radius = 3.2.dp.toPx(), center = at)
        }
        drawCircle(RadarTeal, radius = 2.dp.toPx(), center = origin)
    }
}
