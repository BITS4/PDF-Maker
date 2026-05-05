package com.example.pdfmaker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var securityEnabled by remember { mutableStateOf(SettingsManager.getSecurityEnabled(context)) }
    var showPinDialog   by remember { mutableStateOf(false) }
    var pinInput        by remember { mutableStateOf("") }
    var showPin         by remember { mutableStateOf(false) }
    var pinError        by remember { mutableStateOf("") }

    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { showPinDialog = false; pinInput = ""; pinError = "" },
            containerColor   = currentCard,
            title  = { Text("Set PIN", color = currentText, fontWeight = FontWeight.Bold) },
            text   = {
                Column {
                    OutlinedTextField(
                        value            = pinInput,
                        onValueChange    = { if (it.length <= 6 && it.all(Char::isDigit)) { pinInput = it; pinError = "" } },
                        label            = { Text("Enter 4-6 digit PIN") },
                        singleLine       = true,
                        isError          = pinError.isNotEmpty(),
                        supportingText   = if (pinError.isNotEmpty()) ({ Text(pinError, color = BadgeRed) }) else null,
                        visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions  = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        trailingIcon     = {
                            Icon(
                                if (showPin) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                null,
                                tint     = currentTextSecond,
                                modifier = Modifier.clickable { showPin = !showPin }
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors   = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = AccentBlue,
                            unfocusedBorderColor = currentTextSecond,
                            focusedTextColor     = currentText,
                            unfocusedTextColor   = currentText,
                            focusedLabelColor    = AccentBlue,
                            unfocusedLabelColor  = currentTextSecond
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (pinInput.length < 4) { pinError = "PIN must be at least 4 digits"; return@TextButton }
                    SettingsManager.savePin(context, pinInput)
                    showPinDialog = false; pinInput = ""; pinError = ""
                }) { Text("Save", color = AccentBlue, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false; pinInput = ""; pinError = "" }) {
                    Text("Cancel", color = currentTextSecond)
                }
            }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(currentBg)
    ) {
        // Top bar
        Row(
            Modifier
                .fillMaxWidth()
                .background(currentCard)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = currentText)
            }
            Text(
                "Settings", color = currentText,
                fontSize = 18.sp, fontWeight = FontWeight.Bold
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp)
        ) {
            // Security section
            SettingsSectionHeader("Security")

            SettingsToggleRow(
                icon    = Icons.Default.Lock,
                label   = "App PIN lock",
                sub     = "Require a PIN to open the app",
                checked = securityEnabled,
                onToggle = { checked ->
                    securityEnabled = checked
                    SettingsManager.setSecurityEnabled(context, checked)
                }
            )

            if (securityEnabled) {
                SettingsActionRow(
                    icon  = Icons.Default.Pin,
                    label = "Change PIN",
                    sub   = if (SettingsManager.getPin(context).isNotEmpty()) "PIN is set" else "No PIN set"
                ) { showPinDialog = true }
            }

            // About section
            SettingsSectionHeader("About")

            SettingsInfoRow(
                icon  = Icons.Default.Info,
                label = "Version",
                value = "1.0"
            )
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        title,
        color    = AccentBlue,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun SettingsToggleRow(
    icon    : ImageVector,
    label   : String,
    sub     : String,
    checked : Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(currentCard)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = AccentBlue, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = currentText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = currentTextSecond, fontSize = 12.sp)
        }
        Switch(
            checked  = checked,
            onCheckedChange = onToggle,
            colors   = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentBlue)
        )
    }
    HorizontalDivider(color = currentBg, thickness = 1.dp)
}

@Composable
private fun SettingsActionRow(
    icon  : ImageVector,
    label : String,
    sub   : String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(currentCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = AccentBlue, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = currentText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = currentTextSecond, fontSize = 12.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = currentTextSecond, modifier = Modifier.size(18.dp))
    }
    HorizontalDivider(color = currentBg, thickness = 1.dp)
}

@Composable
private fun SettingsInfoRow(
    icon  : ImageVector,
    label : String,
    value : String
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(currentCard)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = AccentBlue, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = currentText, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(value, color = currentTextSecond, fontSize = 14.sp)
    }
    HorizontalDivider(color = currentBg, thickness = 1.dp)
}
