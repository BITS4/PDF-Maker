package com.example.pdfmaker

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ImageReviewScreen(
    editStates   : List<ImageEditState>,
    onAddMore    : () -> Unit,
    onBack       : () -> Unit,
    onConvertDone: (filePath: String, fileName: String) -> Unit
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var isConverting  by remember { mutableStateOf(false) }
    var showOptions   by remember { mutableStateOf(false) }
    var fileName      by remember { mutableStateOf("scan_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}") }
    var usePassword   by remember { mutableStateOf(false) }
    var password      by remember { mutableStateOf("") }
    var showPassword  by remember { mutableStateOf(false) }
    var pageSize      by remember { mutableStateOf("A4") }

    if (showOptions) {
        AlertDialog(
            onDismissRequest = { showOptions = false },
            containerColor   = currentCard,
            title  = { Text("PDF Options", color = currentText, fontWeight = FontWeight.Bold) },
            text   = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value         = fileName,
                        onValueChange = { fileName = it },
                        label         = { Text("File name") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = AccentBlue,
                            unfocusedBorderColor = currentTextSecond,
                            focusedTextColor     = currentText,
                            unfocusedTextColor   = currentText,
                            focusedLabelColor    = AccentBlue,
                            unfocusedLabelColor  = currentTextSecond
                        )
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked         = usePassword,
                            onCheckedChange = { usePassword = it },
                            colors          = CheckboxDefaults.colors(checkedColor = AccentBlue)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Password-protect", color = currentText, fontSize = 14.sp)
                    }
                    if (usePassword) {
                        OutlinedTextField(
                            value            = password,
                            onValueChange    = { password = it },
                            label            = { Text("Password") },
                            singleLine       = true,
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions  = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon     = {
                                Icon(
                                    if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    null, tint = currentTextSecond,
                                    modifier = Modifier.clickable { showPassword = !showPassword }
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
                }
            },
            confirmButton = {
                TextButton(onClick = { showOptions = false }) {
                    Text("Done", color = AccentBlue, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    Column(Modifier.fillMaxSize().background(currentBg)) {

        // Top bar
        Row(
            Modifier
                .fillMaxWidth()
                .background(currentCard)
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = currentText)
            }
            Text(
                "${editStates.size} page${if (editStates.size != 1) "s" else ""}",
                color = currentText, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            IconButton(onClick = { showOptions = true }) {
                Icon(Icons.Default.Settings, null, tint = currentText)
            }
        }

        // Thumbnail strip
        LazyRow(
            modifier            = Modifier.fillMaxWidth().height(140.dp).background(currentCard),
            contentPadding      = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(editStates) { es ->
                val bmp = es.finalBitmap ?: es.originalBitmap
                Box(
                    Modifier
                        .width(90.dp).fillMaxHeight()
                        .clip(RoundedCornerShape(8.dp))
                        .background(currentThumbnail),
                    contentAlignment = Alignment.Center
                ) {
                    if (bmp != null) {
                        Image(
                            bmp.asImageBitmap(), null,
                            contentScale = ContentScale.Fit,
                            modifier     = Modifier.fillMaxSize()
                        )
                    } else {
                        CircularProgressIndicator(color = AccentBlue, modifier = Modifier.size(24.dp))
                    }
                }
            }

            // Add more
            item {
                Box(
                    Modifier
                        .width(90.dp).fillMaxHeight()
                        .clip(RoundedCornerShape(8.dp))
                        .background(currentBg)
                        .clickable(onClick = onAddMore),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Add, null, tint = AccentBlue, modifier = Modifier.size(28.dp))
                        Text("Add", color = AccentBlue, fontSize = 11.sp)
                    }
                }
            }
        }

        // Options summary
        Column(
            Modifier
                .fillMaxWidth()
                .background(currentCard)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PictureAsPdf, null, tint = BadgeRed, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("$fileName.pdf", color = currentText, fontSize = 13.sp, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { showOptions = true },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                    Text("Edit", color = AccentBlue, fontSize = 12.sp)
                }
            }
            if (usePassword) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, tint = AccentBlue, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Password protected", color = currentTextSecond, fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Convert button
        Button(
            onClick = {
                isConverting = true
                scope.launch(Dispatchers.IO) {
                    val outFileName = fileName.ifBlank { "scan_${System.currentTimeMillis()}" } + ".pdf"
                    val outFile = File(getPdfMakerDir(context), outFileName)
                    val pdfDoc  = PdfDocument()
                    editStates.forEachIndexed { idx, es ->
                        val bmp = es.finalBitmap ?: es.originalBitmap ?: return@forEachIndexed
                        val w   = bmp.width.coerceAtLeast(1)
                        val h   = bmp.height.coerceAtLeast(1)
                        val info = PdfDocument.PageInfo.Builder(w, h, idx + 1).create()
                        val pg   = pdfDoc.startPage(info)
                        pg.canvas.drawBitmap(bmp, 0f, 0f, Paint())
                        pdfDoc.finishPage(pg)
                    }
                    outFile.outputStream().use { pdfDoc.writeTo(it) }
                    pdfDoc.close()

                    val finalPath: String
                    val finalName: String
                    if (usePassword && password.isNotEmpty()) {
                        val err = lockFileInPlace(outFile.absolutePath, password)
                        if (err != null) {
                            // Lock failed — still return unencrypted file
                        }
                        finalPath = outFile.absolutePath
                        finalName = outFileName
                    } else {
                        finalPath = outFile.absolutePath
                        finalName = outFileName
                    }

                    val dateStr = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date())
                    val bmp0    = editStates.firstOrNull()?.finalBitmap ?: editStates.firstOrNull()?.originalBitmap
                    val sizeKb  = outFile.length() / 1024
                    FileCache.prependFile(
                        PdfFile(
                            name         = outFileName.removeSuffix(".pdf"),
                            filePath     = finalPath,
                            size         = "${sizeKb} KB",
                            date         = dateStr,
                            pageCount    = editStates.size,
                            lastModified = outFile.lastModified()
                        )
                    )

                    withContext(Dispatchers.Main) {
                        isConverting = false
                        onConvertDone(finalPath, finalName)
                    }
                }
            },
            enabled  = !isConverting && editStates.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .height(54.dp)
                .navigationBarsPadding(),
            colors   = ButtonDefaults.buttonColors(containerColor = AccentBlue),
            shape    = RoundedCornerShape(14.dp)
        ) {
            if (isConverting) {
                CircularProgressIndicator(
                    color    = Color.White,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text("Creating PDF…", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            } else {
                Icon(Icons.Default.PictureAsPdf, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Create PDF", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}
