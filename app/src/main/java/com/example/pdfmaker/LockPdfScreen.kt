package com.example.pdfmaker

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
import java.io.File
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// ── Lock PDF — AES-256 encrypt any file in-place ──────────────────────────────

internal const val MAGIC = "PDFLOCK1"  // 8-byte magic header
private enum class LockState { LIST, ENTER_PASSWORD, LOCKING, DONE, ERROR }

@Composable
fun LockPdfScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var lState      by remember { mutableStateOf(LockState.LIST) }
    var pickedFile  by remember { mutableStateOf<PdfFile?>(null) }
    var password    by remember { mutableStateOf("") }
    var confirmPwd  by remember { mutableStateOf("") }
    var showPass    by remember { mutableStateOf(false) }
    var outName     by remember { mutableStateOf("") }
    var errMsg      by remember { mutableStateOf("") }

    // All files that are NOT yet locked
    val allFiles = FileCache.files
    val unlocked = remember(allFiles) { allFiles.filter { !isLockedFile(it.filePath) } }

    Box(Modifier.fillMaxSize().background(currentBg)) {
        Column(Modifier.fillMaxSize()) {

            // ── Top bar ───────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().background(currentCard).statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (lState == LockState.ENTER_PASSWORD) {
                        lState = LockState.LIST; password = ""; confirmPwd = ""
                    } else onBack()
                }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = currentText) }
                Text(
                    if (lState == LockState.ENTER_PASSWORD) "Set Password" else "Lock File",
                    color = currentText, fontSize = 18.sp, fontWeight = FontWeight.Bold
                )
            }

            when (lState) {

                // ── File list ─────────────────────────────────────────────────
                LockState.LIST -> {
                    if (unlocked.isEmpty()) {
                        // Empty state
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Lock, null, tint = currentTextSecond,
                                modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("No files to lock", color = currentText, fontSize = 18.sp,
                                fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("All your files are already locked,\nor no files found.",
                                color = currentTextSecond, fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 32.dp))
                        }
                    } else {
                        Text(
                            "Choose a file to lock",
                            color = currentTextSecond, fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                        LazyColumn {
                            items(unlocked) { file ->
                                FilePickRow(file) {
                                    pickedFile = file
                                    password   = ""
                                    confirmPwd = ""
                                    lState     = LockState.ENTER_PASSWORD
                                }
                            }
                        }
                    }
                }

                // ── Password entry ────────────────────────────────────────────
                LockState.ENTER_PASSWORD -> {
                    val file = pickedFile ?: return@Column
                    Column(Modifier.fillMaxSize().padding(24.dp)) {

                        // Selected file card
                        Surface(color = currentCard, shape = RoundedCornerShape(12.dp)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Lock, null,
                                    tint = AccentBlue, modifier = Modifier.size(32.dp))
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
                        Text("Set password", color = currentText,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("You will need this password to open the file.",
                            color = currentTextSecond, fontSize = 13.sp)
                        Spacer(Modifier.height(16.dp))

                        OutlinedTextField(
                            value = password, onValueChange = { password = it },
                            label = { Text("Password") }, singleLine = true,
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
                                unfocusedLabelColor  = currentTextSecond
                            )
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = confirmPwd, onValueChange = { confirmPwd = it },
                            label = { Text("Confirm password") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            isError = confirmPwd.isNotEmpty() && confirmPwd != password,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor   = AccentBlue,
                                unfocusedBorderColor = currentTextSecond,
                                focusedTextColor     = currentText,
                                unfocusedTextColor   = currentText,
                                focusedLabelColor    = AccentBlue,
                                unfocusedLabelColor  = currentTextSecond
                            )
                        )
                        if (confirmPwd.isNotEmpty() && confirmPwd != password) {
                            Spacer(Modifier.height(4.dp))
                            Text("Passwords don\'t match", color = BadgeRed, fontSize = 12.sp)
                        }

                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = {
                                val f = pickedFile ?: return@Button
                                scope.launch {
                                    lState = LockState.LOCKING
                                    val error = withContext(Dispatchers.IO) {
                                        lockFileInPlace(f.filePath, password)
                                    }
                                    if (error == null) {
                                        // Success — invalidate thumbnail + refresh file list
                                        PdfThumbnailCache.invalidate(f.filePath)
                                        FileCache.invalidate()
                                        FileCache.load(context, forceRefresh = true)
                                        outName = f.name
                                        lState  = LockState.DONE
                                    } else {
                                        errMsg = error
                                        lState = LockState.ERROR
                                    }
                                }
                            },
                            enabled  = password.length >= 4 && password == confirmPwd,
                            colors   = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                            shape    = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Lock, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Lock File", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }

                // ── Locking ───────────────────────────────────────────────────
                LockState.LOCKING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AccentBlue, modifier = Modifier.size(52.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Locking…", color = currentText, fontSize = 15.sp)
                    }
                }

                // ── Done ──────────────────────────────────────────────────────
                LockState.DONE -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(Modifier.size(90.dp).clip(CircleShape).background(Color(0xFF1A2340)),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Lock, null, tint = AccentBlue,
                            modifier = Modifier.size(50.dp))
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("File Locked!", color = currentText, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(outName, color = currentTextSecond, fontSize = 13.sp,
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Surface(color = Color(0xFF1A2340), shape = RoundedCornerShape(10.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, null, tint = AccentBlue,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("The file is now password-protected",
                                color = currentTextSecond, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = { lState = LockState.LIST; password = ""; confirmPwd = "" },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        shape  = RoundedCornerShape(12.dp)
                    ) { Text("Lock Another File", fontWeight = FontWeight.Bold) }
                }

                // ── Error ─────────────────────────────────────────────────────
                LockState.ERROR -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.ErrorOutline, null, tint = BadgeRed,
                        modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Failed to lock file", color = currentText, fontSize = 16.sp,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(errMsg, color = currentTextSecond, fontSize = 13.sp,
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { lState = LockState.LIST },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                    ) { Text("Try Again") }
                }
            }
        }
    }
}

// ── Shared file row composable used in both Lock and Unlock screens ────────────

@Composable
fun FilePickRow(file: PdfFile, onClick: () -> Unit) {
    val ext = file.filePath.substringAfterLast('.', "").lowercase()
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
                        .background(currentThumbnail),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        fileTypeIcon(ext), null,
                        tint = fileTypeTint(ext),
                        modifier = Modifier.size(28.dp)
                    )
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

// ── Crypto: lock file in-place ─────────────────────────────────────────────────
// Returns null on success, error message string on failure

internal fun lockFileInPlace(filePath: String, password: String): String? {
    return try {
        val file  = File(filePath)
        if (!file.exists()) return "File not found: $filePath"
        if (!file.canWrite()) return "File is not writable (check storage permission)"
        val bytes = file.readBytes()
        if (bytes.isEmpty()) return "File is empty"

        // Check not already locked
        if (bytes.size >= 8 && bytes.take(8).toByteArray().toString(Charsets.UTF_8) == MAGIC) {
            return "File is already locked"
        }

        val salt    = java.security.SecureRandom().generateSeed(16)
        val iv      = java.security.SecureRandom().generateSeed(16)
        val key     = deriveKey(password, salt)
        val cipher  = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(bytes)

        val out = java.io.ByteArrayOutputStream()
        out.write(MAGIC.toByteArray(Charsets.UTF_8))
        out.write(salt)
        out.write(iv)
        out.write(encrypted)

        file.writeBytes(out.toByteArray())
        android.util.Log.d("LockFile", "Locked in place: $filePath")
        null // success
    } catch (e: Exception) {
        android.util.Log.e("LockFile", "Lock failed: ${e.javaClass.simpleName}: ${e.message}")
        "${e.javaClass.simpleName}: ${e.message}"
    }
}

// ── Crypto: unlock file in-place ──────────────────────────────────────────────
// Returns null on success, error message on failure

internal fun unlockFileInPlace(filePath: String, password: String): String? {
    return try {
        val file  = File(filePath)
        if (!file.exists()) return "File not found"
        if (!file.canWrite()) return "File is not writable (check storage permission)"
        val bytes = file.readBytes()

        val decrypted = decryptBytes(bytes, password)
            ?: return "Wrong password"

        file.writeBytes(decrypted)
        android.util.Log.d("LockFile", "Unlocked in place: $filePath")
        null // success
    } catch (e: Exception) {
        android.util.Log.e("LockFile", "Unlock failed: ${e.javaClass.simpleName}: ${e.message}")
        "${e.javaClass.simpleName}: ${e.message}"
    }
}

// ── Crypto helpers ─────────────────────────────────────────────────────────────

internal fun isLockedFile(filePath: String): Boolean = try {
    val f = File(filePath)
    if (!f.exists() || f.length() < 8) false
    else f.inputStream().use { s ->
        val header = ByteArray(8)
        s.read(header)
        header.toString(Charsets.UTF_8) == MAGIC
    }
} catch (_: Exception) { false }

// Keep old name for compatibility with PdfViewerScreen
internal fun isLockedPdf(filePath: String): Boolean = isLockedFile(filePath)

internal fun decryptBytes(encryptedBytes: ByteArray, password: String): ByteArray? {
    return try {
        if (encryptedBytes.size < 40) return null
        val magic = encryptedBytes.take(8).toByteArray().toString(Charsets.UTF_8)
        if (magic != MAGIC) return null
        val salt   = encryptedBytes.slice(8..23).toByteArray()
        val iv     = encryptedBytes.slice(24..39).toByteArray()
        val data   = encryptedBytes.drop(40).toByteArray()
        val key    = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        cipher.doFinal(data)
    } catch (_: Exception) { null }
}

// Keep old name for compatibility with PdfViewerScreen and UnlockPdfScreen
internal fun decryptPdf(encryptedBytes: ByteArray, password: String): ByteArray? =
    decryptBytes(encryptedBytes, password)

// Old encryptPdf kept for any call sites that still use it
internal fun encryptPdfBytes(
    context  : android.content.Context,
    bytes    : ByteArray,
    password : String,
    baseName : String
): Pair<String, String>? {
    return try {
        val salt    = java.security.SecureRandom().generateSeed(16)
        val iv      = java.security.SecureRandom().generateSeed(16)
        val key     = deriveKey(password, salt)
        val cipher  = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(bytes)
        val out = java.io.ByteArrayOutputStream()
        out.write(MAGIC.toByteArray(Charsets.UTF_8))
        out.write(salt)
        out.write(iv)
        out.write(encrypted)
        val outName = "${baseName}_locked.pdf"
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        dir.mkdirs()
        val outFile = File(dir, outName)
        outFile.writeBytes(out.toByteArray())
        Pair(outFile.absolutePath, outName)
    } catch (e: Exception) { null }
}

private fun deriveKey(password: String, salt: ByteArray): ByteArray {
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val spec    = PBEKeySpec(password.toCharArray(), salt, 65536, 256)
    return factory.generateSecret(spec).encoded
}
