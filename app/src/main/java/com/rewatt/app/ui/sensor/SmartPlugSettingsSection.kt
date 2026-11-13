package com.rewatt.app.ui.sensor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rewatt.app.energy.tapo.TapoConfig
import com.rewatt.app.energy.tapo.TapoLink
import com.rewatt.app.energy.tapo.TapoStatus

/**
 * Settings: the Tapo smart plug that switches the Room 1 lights. The app links it by itself with the
 * built-in login; this is the backup to change the login or type the plug's IP. The link status shows
 * only here (the Energy tab just hides the lights icon while it isn't linked).
 */
@Composable
fun SmartPlugSettingsSection(link: TapoLink) {
    val state by link.state.collectAsStateWithLifecycle()
    val saved = remember { link.config }
    var enabled by remember { mutableStateOf(saved.enabled) }
    var email by remember { mutableStateOf(saved.email) }
    var password by remember { mutableStateOf(saved.password) }
    var ip by remember { mutableStateOf(saved.ip) }
    var showPassword by remember { mutableStateOf(false) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = Color.White,
        unfocusedContainerColor = Color.White,
    )

    Column {
        Text("TAPO Smart Home", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF09090B))
        Text("The Tapo plug that switches the Room 1 lights.", fontSize = 13.sp, color = Color(0xFF6B7280))
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFECEEEF)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Control the lights with Tapo", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF09090B))
                    StatusLine(state.status, state.nickname, state.ip, state.rttMs, state.problem, enabled)
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        link.save(TapoConfig(it, email, password, ip))
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF27272A)),
                )
            }

            Text("TAPO EMAIL", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF374151))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors,
            )

            Text("TAPO PASSWORD", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF374151))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                        )
                    }
                },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors,
            )

            Text("PLUG IP (OPTIONAL)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF374151))
            Text(
                "Not needed: the plug is found on this Wi-Fi by itself, even when the hotspot gives it a new address. An IP typed here is just tried first.",
                fontSize = 12.sp,
                color = Color(0xFF6B7280),
            )
            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it.trim() },
                placeholder = { Text("Blank = find it automatically") },
                leadingIcon = { Icon(Icons.Outlined.Router, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors,
            )

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { link.save(TapoConfig(enabled, email, password, ip)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27272A), contentColor = Color.White),
            ) {
                Text("Save & Link", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = link::testToggle,
                enabled = state.linked,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF09090B)),
            ) {
                Icon(Icons.Outlined.Lightbulb, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.plugOn == true) "Test: turn the light off" else "Test: turn the light on",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(status: TapoStatus, nickname: String?, ip: String?, rttMs: Long?, problem: String?, enabled: Boolean) {
    val (color, text) = when {
        !enabled -> Color(0xFF9CA3AF) to "Off"
        status == TapoStatus.LINKED -> Color(0xFF22C55E) to buildString {
            append("Linked")
            nickname?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            ip?.let { append(" · ").append(it) }
            rttMs?.let { append(" · ").append(it).append(" ms") }
        }
        status == TapoStatus.SEARCHING -> Color(0xFFF59E0B) to "Looking for the plug…"
        else -> Color(0xFFEF4444) to ("Not linked" + (problem?.let { " · $it" } ?: ""))
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, color = Color(0xFF4B5563))
    }
}
