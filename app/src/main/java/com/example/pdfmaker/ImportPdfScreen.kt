package com.example.pdfmaker

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── Device PDF model ──────────────────────────────────────────────────────────

// ── Scan MediaStore for PDFs ──────────────────────────────────────────────────

private suspend fun scanDevicePdfs(context: Context): List<DevicePdf> =
    withContext(Dispatchers.IO) {
        val list   = mutableListOf<DevicePdf>()
        val volume = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else
            MediaStore.Files.getContentUri("external")

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )
        val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} = ?"
        val selArgs   = arrayOf("application/pdf")
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        context.contentResolver.query(volume, projection, selection, selArgs, sortOrder)
            ?.use { cursor ->
                val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val id       = cursor.getLong(idCol)
                    val rawName  = cursor.getString(nameCol) ?: continue
                    val name     = rawName.removeSuffix(".pdf")
                    val sizeB    = cursor.getLong(sizeCol)
                    val sizeStr  = when {
                        sizeB >= 1_048_576 -> "%.1f MB".format(sizeB / 1_048_576.0)
                        sizeB >= 1024      -> "${sizeB / 1024} kB"
                        else               -> "$sizeB B"
                    }
                    val modMs    = cursor.getLong(dateCol) * 1000L
                    val cal      = java.util.Calendar.getInstance().also { it.timeInMillis = modMs }
                    val dateFmt  = "%02d/%02d %02d:%02d".format(
                        cal.get(java.util.Calendar.MONTH) + 1,
                        cal.get(java.util.Calendar.DAY_OF_MONTH),
                        cal.get(java.util.Calendar.HOUR_OF_DAY),
                        cal.get(java.util.Calendar.MINUTE)
                    )
                    val contentUri = ContentUris.withAppendedId(volume, id)
                    list += DevicePdf(contentUri, name, sizeStr, dateFmt, modMs)
                }
            }
        list
    }

// ── Cloud source items ────────────────────────────────────────────────────────

private data class CloudSource(val label: String, val bg: Color, val iconTint: Color = Color.White)

private val cloudSources = listOf(
    CloudSource("File Manager",  Color(0xFF1565C0)),
    CloudSource("OneDrive",      Color(0xFF0078D4)),
    CloudSource("Drive",         Color.White,      Color(0xFF4285F4)),
    CloudSource("My Files",      Color(0xFFF9A825)),
    CloudSource("Files",         Color(0xFF1A73E8))
)

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun ImportPdfScreen(
    onBack     : () -> Unit,
    onPdfPicked: (Uri) -> Unit
) {
    val context      = LocalContext.current
    var devicePdfs   by remember { mutableStateOf<List<DevicePdf>>(emptyList()) }
    var searchQuery  by remember { mutableStateOf("") }
    var isSearching  by remember { mutableStateOf(false) }

    // Standard Android document picker — routes to whichever storage the user chooses
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { onPdfPicked(it) } }

    LaunchedEffect(Unit) { devicePdfs = scanDevicePdfs(context) }

    val filtered = remember(devicePdfs, searchQuery) {
        if (searchQuery.isBlank()) devicePdfs
        else devicePdfs.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    val cardBg = Color(0xFF1A1A2A)
    val surface = Color(0xFF0D0D16)
    val textPri = Color.White
    val textSec = Color(0xFF9999BB)

    Column(
        Modifier
            .fillMaxSize()
            .background(surface)
            .statusBarsPadding()
    ) {
        // ── Top bar ───────────────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = textPri)
            }
            Text(
                "Import PDF", color = textPri,
                fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
        }

        // ── Cloud source grid ─────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            cloudSources.take(4).forEach { src ->
                CloudSourceItem(src, textSec) { filePicker.launch(arrayOf("application/pdf")) }
            }
        }
        // 5th item (Files) on second row
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp)
        ) {
            CloudSourceItem(cloudSources[4], textSec) { filePicker.launch(arrayOf("application/pdf")) }
        }

        // ── From Device section ───────────────────────────────────────────────
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(cardBg)
        ) {
            Column(Modifier.fillMaxSize()) {
                // Header
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSearching) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(0xFF2A2A3A))
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (searchQuery.isEmpty()) {
                                Text("Search PDFs…", color = Color(0xFF666680), fontSize = 14.sp)
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    color = textPri, fontSize = 14.sp
                                ),
                                cursorBrush = SolidColor(AccentBlue),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        IconButton(onClick = { isSearching = false; searchQuery = "" }) {
                            Icon(Icons.Default.Close, null, tint = textSec)
                        }
                    } else {
                        Text(
                            "From Device", color = textPri,
                            fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { isSearching = true }) {
                            Icon(Icons.Default.Search, null, tint = textSec)
                        }
                    }
                }

                if (devicePdfs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = AccentBlue, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Scanning device…", color = textSec, fontSize = 14.sp)
                        }
                    }
                } else if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No PDFs found", color = textSec, fontSize = 15.sp)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(filtered) { pdf ->
                            DevicePdfRow(pdf, textPri, textSec) { onPdfPicked(pdf.uri) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.CloudSourceItem(
    src     : CloudSource,
    textSec : Color,
    onClick : () -> Unit
) {
    Column(
        modifier            = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(src.bg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector  = Icons.Default.Folder,
                contentDescription = null,
                tint         = src.iconTint,
                modifier     = Modifier.size(30.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(src.label, color = textSec, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DevicePdfRow(
    pdf     : DevicePdf,
    textPri : Color,
    textSec : Color,
    onClick : () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // PDF icon
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF252535)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFE53935)),
                contentAlignment = Alignment.Center
            ) {
                Text("PDF", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(pdf.name, color = textPri, fontSize = 15.sp,
                fontWeight = FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(18.dp)
                        .background(Color(0xFF2A2A3A), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("1", color = textSec, fontSize = 9.sp)
                }
                Spacer(Modifier.width(8.dp))
                Text(pdf.dateFmt, color = textSec, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(pdf.sizeFmt, color = textSec, fontSize = 12.sp)
            }
        }
    }
}
