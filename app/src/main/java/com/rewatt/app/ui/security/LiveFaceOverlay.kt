package com.rewatt.app.ui.security

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rewatt.app.R
import com.rewatt.app.security.LiveFaceUi
import com.rewatt.app.security.TrackStatus

private val FamilyGreen = Color(0xFF22C55E)
private val CheckingAmber = Color(0xFFF59E0B)
private val UnknownRed = Color(0xFFEF4444)

/**
 * Live face boxes over the SECURITY camera view. Boxes are normalised to the live analysis frame,
 * which has the same 4:3 field of view as the Preview, so they are mapped with the same FILL_CENTER
 * crop the PreviewView uses: green = registered person (name), amber = still checking, red = unknown.
 */
@Composable
fun LiveFaceOverlay(
    faces: List<LiveFaceUi>,
    frameWidth: Int,
    frameHeight: Int,
    modifier: Modifier = Modifier,
) {
    if (faces.isEmpty() || frameWidth <= 0 || frameHeight <= 0) return
    val measurer = rememberTextMeasurer()
    val checking = stringResource(R.string.live_face_checking)
    val unknown = stringResource(R.string.live_face_unknown)
    val family = stringResource(R.string.live_face_family)
    val labelStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)

    Canvas(modifier = modifier) {
        val scale = maxOf(size.width / frameWidth, size.height / frameHeight)
        val drawW = frameWidth * scale
        val drawH = frameHeight * scale
        val offsetX = (size.width - drawW) / 2f
        val offsetY = (size.height - drawH) / 2f
        val stroke = 2.5.dp.toPx()
        val pad = 4.dp.toPx()

        faces.forEach { face ->
            val (color, label) = when (face.status) {
                TrackStatus.FAMILY -> FamilyGreen to (face.name ?: family)
                TrackStatus.PENDING -> CheckingAmber to checking
                TrackStatus.UNKNOWN -> UnknownRed to unknown
            }
            val topLeft = Offset(offsetX + face.left * drawW, offsetY + face.top * drawH)
            val boxSize = Size((face.right - face.left) * drawW, (face.bottom - face.top) * drawH)
            drawRoundRect(color = color, topLeft = topLeft, size = boxSize, cornerRadius = CornerRadius(6.dp.toPx()), style = Stroke(width = stroke))

            val text = measurer.measure(label, labelStyle)
            val tagW = text.size.width + pad * 2
            val tagH = text.size.height + pad
            val tagTop = (topLeft.y - tagH - stroke).let { if (it < 0f) topLeft.y + boxSize.height + stroke else it }
            drawRoundRect(color = color, topLeft = Offset(topLeft.x, tagTop), size = Size(tagW, tagH), cornerRadius = CornerRadius(4.dp.toPx()))
            drawText(text, topLeft = Offset(topLeft.x + pad, tagTop + pad / 2f))
        }
    }
}
