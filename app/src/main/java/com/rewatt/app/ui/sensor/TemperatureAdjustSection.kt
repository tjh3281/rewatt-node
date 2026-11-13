package com.rewatt.app.ui.sensor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rewatt.app.telemetry.TemperatureAdjust
import kotlin.math.roundToInt

/**
 * Settings: shift the temperature the Energy tab shows by a fixed amount (−10…+10 °C, 1° steps).
 * [moduleTempC] is the module's own reading right now (null = module not attached).
 */
@Composable
fun TemperatureAdjustSection(moduleTempC: Float?) {
    val offset by TemperatureAdjust.offsetC.collectAsStateWithLifecycle()
    val offsetText = offset.roundToInt().let { if (it > 0) "+$it°C" else "$it°C" }

    Column {
        Text("Temperature Adjustment", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF09090B))
        Text("Correct the temperature shown in the Energy tab.", fontSize = 13.sp, color = Color(0xFF6B7280))
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFECEEEF)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("OFFSET", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF374151))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StepButton(
                    minus = true,
                    enabled = offset > TemperatureAdjust.MIN_OFFSET_C,
                    onClick = { TemperatureAdjust.set(offset - TemperatureAdjust.STEP_C) },
                )
                Text(
                    offsetText,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF09090B),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                StepButton(
                    minus = false,
                    enabled = offset < TemperatureAdjust.MAX_OFFSET_C,
                    onClick = { TemperatureAdjust.set(offset + TemperatureAdjust.STEP_C) },
                )
            }

            val preview = if (moduleTempC == null) {
                "Plug in the ReWatt module to see its reading here."
            } else {
                val shown = TemperatureAdjust.apply(moduleTempC, offset) ?: moduleTempC
                "Module reads ${moduleTempC.toInt()}° → Energy tab shows ${shown.toInt()}°"
            }
            Text(preview, fontSize = 13.sp, color = Color(0xFF4B5563))

            TextButton(
                onClick = { TemperatureAdjust.set(0f) },
                enabled = offset != 0f,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Reset to 0", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StepButton(minus: Boolean, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(width = 64.dp, height = 50.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF09090B)),
    ) {
        Icon(
            if (minus) Icons.Outlined.Remove else Icons.Outlined.Add,
            contentDescription = if (minus) "Lower by 1°C" else "Raise by 1°C",
            modifier = Modifier.size(22.dp),
        )
    }
}
