package com.rewatt.app.ui.sensor

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** How strong the fire effect on the gas status card is. */
enum class FireIntensity { NONE, CAUTION, DANGER }

/**
 * Flames along the bottom of a card, flickering: low amber flames for [FireIntensity.CAUTION], tall
 * red-orange flames for [FireIntensity.DANGER]. Drawn behind the card's content; nothing at
 * [FireIntensity.NONE]. The flames grow and shrink smoothly when the intensity changes.
 */
@Composable
internal fun Modifier.fireEffect(intensity: FireIntensity): Modifier {
    val strength by animateFloatAsState(
        targetValue = when (intensity) {
            FireIntensity.NONE -> 0f
            FireIntensity.CAUTION -> 0.45f
            FireIntensity.DANGER -> 1f
        },
        animationSpec = tween(600),
        label = "fireStrength",
    )
    val danger = intensity == FireIntensity.DANGER
    // One slow clock drives every flame; each flame has its own phase and speed.
    val time by rememberInfiniteTransition(label = "fire").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2400, easing = LinearEasing), RepeatMode.Restart),
        label = "fireTime",
    )
    return drawBehind {
        if (strength <= 0.01f) return@drawBehind
        val maxHeight = minOf(size.height * 0.42f, 72.dp.toPx()) * strength
        drawFlames(time, strength, danger, maxHeight)
        drawSparks(time, strength, danger, maxHeight)
    }
}

/** Little flames and sparks breaking off the fire and flying up, wobbling and fading. */
private fun DrawScope.drawSparks(time: Float, strength: Float, danger: Boolean, flameHeight: Float) {
    val count = if (danger) 16 else 6
    val rise = size.height * 0.62f
    for (i in 0 until count) {
        val speed = 1 + i % 2 // whole numbers so the loop is seamless
        val p = (time * speed + i * 0.137f) % 1f
        val x = size.width * ((i * 0.293f + 0.05f) % 1f) + sin(2 * PI * (p * 1.5f + i)).toFloat() * 8.dp.toPx()
        val y = size.height - flameHeight * 0.5f - p * rise
        val alpha = (1f - p) * strength
        if (i % 3 == 0) {
            // A tiny flying flame.
            val h = 9.dp.toPx() * (1f - p * 0.5f)
            val w = 5.dp.toPx() * (1f - p * 0.5f)
            val flame = Path().apply {
                moveTo(x - w / 2, y)
                cubicTo(x - w / 2, y - h * 0.5f, x, y - h * 0.7f, x, y - h)
                cubicTo(x, y - h * 0.7f, x + w / 2, y - h * 0.5f, x + w / 2, y)
                close()
            }
            drawPath(
                flame,
                Brush.verticalGradient(listOf(Color(0xFFFDE047), if (danger) Color(0xFFF97316) else Color(0xFFF59E0B)), startY = y - h, endY = y),
                alpha = alpha,
            )
        } else {
            drawCircle(
                color = if (i % 3 == 1) Color(0xFFFFF7AE) else if (danger) Color(0xFFFB923C) else Color(0xFFFBBF24),
                radius = 1.8.dp.toPx() * (1f - p * 0.5f),
                center = Offset(x, y),
                alpha = alpha,
            )
        }
    }
}

private fun DrawScope.drawFlames(time: Float, strength: Float, danger: Boolean, maxHeight: Float) {
    val flameWidth = 30.dp.toPx()
    val count = (size.width / (flameWidth * 0.62f)).toInt().coerceAtLeast(3)
    val step = size.width / count
    val outer = if (danger) listOf(Color(0xFFDC2626), Color(0xFFF97316), Color(0x00FDE047)) else listOf(Color(0xFFF59E0B), Color(0xFFFBBF24), Color(0x00FEF3C7))
    val inner = if (danger) listOf(Color(0xFFFDE047), Color(0x00FFFBEB)) else listOf(Color(0xFFFDE68A), Color(0x00FFFBEB))

    // Warm glow rising from the bottom edge.
    drawRect(
        Brush.verticalGradient(
            listOf(Color.Transparent, (if (danger) Color(0xFFF97316) else Color(0xFFFBBF24)).copy(alpha = 0.28f * strength)),
            startY = size.height - maxHeight * 1.6f,
            endY = size.height,
        ),
        topLeft = Offset(0f, size.height - maxHeight * 1.6f),
    )

    for (i in 0..count) {
        val phase = (i * 0.37f) % 1f
        val speed = 1f + (i % 3) * 0.35f
        val wave = sin(2 * PI * (time * speed + phase)).toFloat()
        val wave2 = sin(2 * PI * (time * speed * 1.7f + phase * 2)).toFloat()
        val x = i * step + step * 0.5f * ((i % 2) * 0.6f)
        val h = maxHeight * (0.62f + 0.24f * wave + 0.14f * wave2) * (if (i % 2 == 0) 1f else 0.8f)
        val sway = flameWidth * 0.18f * wave2
        drawFlame(x, h, flameWidth, sway, outer, alpha = 0.85f)
        drawFlame(x, h * 0.55f, flameWidth * 0.5f, sway * 0.6f, inner, alpha = 0.9f)
    }
}

/** One flame: a teardrop from the bottom edge up to a swaying tip, hot at the base, fading at the top. */
private fun DrawScope.drawFlame(x: Float, height: Float, width: Float, sway: Float, colors: List<Color>, alpha: Float) {
    if (height <= 1f) return
    val bottom = size.height
    val half = width / 2f
    val path = Path().apply {
        moveTo(x - half, bottom)
        cubicTo(x - half, bottom - height * 0.45f, x - half * 0.3f + sway, bottom - height * 0.7f, x + sway, bottom - height)
        cubicTo(x + half * 0.3f + sway, bottom - height * 0.7f, x + half, bottom - height * 0.45f, x + half, bottom)
        close()
    }
    drawPath(path, Brush.verticalGradient(colors.reversed(), startY = bottom - height, endY = bottom), alpha = alpha)
}
