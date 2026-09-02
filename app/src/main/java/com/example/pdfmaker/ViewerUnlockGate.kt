package com.example.pdfmaker

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

@Composable
internal fun ViewerUnlockGate(
    file: PdfFile,
    onBack: () -> Unit,
    onUnlocked: (File) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isUnlocking by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(currentBg), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(90.dp).clip(CircleShape).background(Color(0xFF1A2340)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Lock, null, tint = AccentBlue, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text("Password Protected", color = currentText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Enter the password to open this file", color = currentTextSecond, fontSize = 13.sp)
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    errorMessage = null
                },
                label = { Text("Password") },
                singleLine = true,
                enabled = !isUnlocking,
                isError = errorMessage != null,
                visualTransformation =
                    if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    Icon(
                        if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showPassword) "Hide password" else "Show password",
                        tint = currentTextSecond,
                        modifier = Modifier.clickable { showPassword = !showPassword },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = currentTextSecond,
                        focusedTextColor = currentText,
                        unfocusedTextColor = currentText,
                        focusedLabelColor = AccentBlue,
                        unfocusedLabelColor = currentTextSecond,
                        errorBorderColor = BadgeRed,
                    ),
            )
            errorMessage?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = BadgeRed, fontSize = 12.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack, enabled = !isUnlocking, shape = RoundedCornerShape(12.dp)) {
                    Text("Back")
                }
                Button(
                    onClick = {
                        isUnlocking = true
                        scope.launch {
                            val temporaryFile =
                                withContext(Dispatchers.IO) {
                                    unlockViewerFile(context, file.filePath, password)
                                }
                            isUnlocking = false
                            if (temporaryFile == null) {
                                errorMessage = "Wrong password or unreadable file"
                            } else {
                                onUnlocked(temporaryFile)
                            }
                        }
                    },
                    enabled = password.isNotEmpty() && !isUnlocking,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (isUnlocking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Icon(Icons.Default.LockOpen, null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (isUnlocking) "Opening…" else "Open", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private suspend fun unlockViewerFile(
    context: Context,
    sourcePath: String,
    password: String,
): File? {
    val operationContext = currentCoroutineContext()
    return SecureDocumentStore.decryptedCopy(
        source = File(sourcePath),
        destinationDirectory = pdfMakerCacheDirectory(context),
        password = password,
        beforeChunk = operationContext::ensureActive,
    )
}

private fun reportViewerCleanupFailure(error: Throwable) {
    Timber.tag("ViewerUnlockGate").w(
        ObservabilityPolicy.sanitizedThrowable(error),
        "event=viewer_temporary_cleanup_failure",
    )
}

internal fun deleteViewerTemporaryFile(
    context: Context,
    path: String,
) {
    runCatching {
        val directory = pdfMakerCacheDirectory(context).canonicalFile
        val temporaryFile = File(path).canonicalFile
        if (temporaryFile.parentFile == directory && temporaryFile.isFile) temporaryFile.delete()
    }.onFailure(::reportViewerCleanupFailure)
}
