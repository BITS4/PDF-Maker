package com.tajapps.pdfmaker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class UlState { LIST, ENTER_PASSWORD, UNLOCKING, DONE, ERROR }

@Composable
fun UnlockPdfScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var ulState    by remember { mutableStateOf(UlState.LIST) }
    var pickedFile by remember { mutableStateOf<PdfFile?>(null) }
    var password   by remember { mutableStateOf("") }
    var showPass   by remember { mutableStateOf(false) }
    var wrongPass  by remember { mutableStateOf(false) }
    var outName    by remember { mutableStateOf("") }
    var errMsg     by remember { mutableStateOf("") }

    // All files that ARE locked
    val allFiles   = FileCache.files
    val lockedFiles = remember(allFiles) { allFiles.filter { isLockedFile(it.filePath) } }

    Box(Modifier.fillMaxSize().background(currentBg)) {
        Column(Modifier.fillMaxSize()) {

            // ── Top bar ───────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().background(currentCard).statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (ulState == UlState.ENTER_PASSWORD) {
                        ulState = UlState.LIST; password = ""; wrongPass = false
                    } else onBack()
                }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = currentText) }
                Text(
                    if (ulState == UlState.ENTER_PASSWORD) "Enter Password" else "Unlock File",
                    color = currentText, fontSize = 18.sp, fontWeight = FontWeight.Bold
                )
            }

            when (ulState) {

                // ── Locked file list ──────────────────────────────────────────
                UlState.LIST -> {
                    if (lockedFiles.isEmpty()) {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.LockOpen, null, tint = currentTextSecond,
                                modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("No locked files", color = currentText, fontSize = 18.sp,
                                fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("You have no locked files.\nUse Lock File to protect your files.",
                                color = currentTextSecond, fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 32.dp))
                        }
                    } else {
                        Text(
                            "Choose a file to unlock",
                            color = currentTextSecond, fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                        LazyColumn {
                            items(lockedFiles) { file ->
                                LockedFileRow(file) {
                                    pickedFile = file
                                    password   = ""
                                    wrongPass  = false
                                    ulState    = UlState.ENTER_PASSWORD
                                }
                            }
                        }
                    }
                }

                // ── Enter password ────────────────────────────────────────────
                UlState.ENTER_PASSWORD -> {
                    val file = pickedFile ?: return@Column
                    Column(Modifier.fillMaxSize().padding(24.dp)) {

                        // Selected file card
                        Surface(color = currentCard, shape = RoundedCornerShape(12.dp)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Lock, null,
                                    tint = Color(0xFF4F8EF7), modifier = Modifier.size(32.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(file.name, color = currentText,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(file.size, color = currentTextSecond, fontSize = 12.sp)
                                }
                            }
                        }

                        Spacer(Modifier.height(24.dp))
                        Text("Enter password", color = currentText,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("Enter the password used to lock this file.",
                            color = currentTextSecond, fontSize = 13.sp)
                        Spacer(Modifier.height(16.dp))

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it; wrongPass = false },
                            label = { Text("Password") }, singleLine = true,
                            isError = wrongPass,
                            visualTransformation = if (showPass) VisualTransformation.None
                                                   else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                Icon(
                                    if (showPass) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    null, tint = currentTextSecond,
                                    modifier = Modifier.clickable { showPass = !showPass }
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor   = AccentBlue,
                                unfocusedBorderColor = currentTextSecond,
                                focusedTextColor     = currentText,
                                unfocusedTextColor   = currentText,
                                focusedLabelColor    = AccentBlue,
                                unfocusedLabelColor  = currentTextSecond,
                                errorBorderColor     = BadgeRed,
                                errorLabelColor      = BadgeRed
                            )
                        )
                        if (wrongPass) {
                            Spacer(Modifier.height(4.dp))
                            Text("Wrong password. Try again.", color = BadgeRed, fontSize = 12.sp)
                        }

                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = {
                                val f = pickedFile ?: return@Button
                                scope.launch {
                                    ulState = UlState.UNLOCKING
                                    val error = withContext(Dispatchers.IO) {
                                        unlockFileInPlace(f.filePath, password)
                                    }
                                    when (error) {
                                        null -> {
                                            // Success
                                            PdfThumbnailCache.invalidate(f.filePath)
                                            FileCache.invalidate()
                                            FileCache.load(context, forceRefresh = true)
                                            outName = f.name
                                            ulState = UlState.DONE
                                        }
                                        "Wrong password" -> {
                                            wrongPass = true
                                            ulState   = UlState.ENTER_PASSWORD
                                        }
                                        else -> {
                                            errMsg  = error
                                            ulState = UlState.ERROR
                                        }
                                    }
                                }
                            },
                            enabled  = password.isNotEmpty(),
                            colors   = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                            shape    = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.LockOpen, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Unlock File", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }

                // ── Unlocking ─────────────────────────────────────────────────
                UlState.UNLOCKING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AccentBlue, modifier = Modifier.size(52.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Unlocking…", color = currentText, fontSize = 15.sp)
                    }
                }

                // ── Done ──────────────────────────────────────────────────────
                UlState.DONE -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(Modifier.size(90.dp).clip(CircleShape).background(Color(0xFF0F2420)),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.LockOpen, null, tint = Color(0xFF26C6A0),
                            modifier = Modifier.size(50.dp))
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("File Unlocked!", color = currentText, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(outName, color = currentTextSecond, fontSize = 13.sp,
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Surface(color = Color(0xFF0F2420), shape = RoundedCornerShape(10.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, null, tint = Color(0xFF26C6A0),
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Password protection removed",
                                color = currentTextSecond, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = { ulState = UlState.LIST; password = "" },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        shape  = RoundedCornerShape(12.dp)
                    ) { Text("Unlock Another File", fontWeight = FontWeight.Bold) }
                }

                // ── Error ─────────────────────────────────────────────────────
                UlState.ERROR -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.ErrorOutline, null, tint = BadgeRed,
                        modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Failed to unlock file", color = currentText, fontSize = 16.sp,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(errMsg, color = currentTextSecond, fontSize = 13.sp,
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { ulState = UlState.LIST },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                    ) { Text("Try Again") }
                }
            }
        }
    }
}

// ── Locked file row composable ─────────────────────────────────────────────────

@Composable
private fun LockedFileRow(file: PdfFile, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = currentCard
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1A2340)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Lock, null, tint = AccentBlue,
                        modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.name, color = currentText, fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(file.size, color = currentTextSecond, fontSize = 12.sp)
                }
                Icon(Icons.Default.ChevronRight, null, tint = currentTextSecond,
                    modifier = Modifier.size(20.dp))
            }
            HorizontalDivider(color = currentBg, thickness = 1.dp)
        }
    }
}
