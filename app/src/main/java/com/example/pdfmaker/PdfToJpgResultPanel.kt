package com.example.pdfmaker

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun JpgResultPanel(
    files: List<File>,
    primaryText: Color,
    secondaryText: Color,
    status: PdfToJpgResultStatus,
    onSaveToGallery: () -> Unit,
    onShareAll: () -> Unit,
    onNewConversion: () -> Unit,
) {
    val thumbnailDecodeSlots = remember { Semaphore(2) }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(Color(0xFF1A2A1A)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(38.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "${files.size} JPG ${if (files.size == 1) "image" else "images"} ready",
            color = primaryText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Total size: ${jpgFormatSize(files.sumOf { it.length() / 1024 })}",
            color = secondaryText,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(files) { index, file ->
                JpgResultThumbnail(file, index, thumbnailDecodeSlots)
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onSaveToGallery,
            enabled = !status.savingToGallery && !status.savedToGallery,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (status.savedToGallery) Color(0xFF388E3C) else Color(0xFF4CAF50),
            ),
        ) {
            Icon(
                if (status.savedToGallery) Icons.Default.CheckCircle else Icons.Default.SaveAlt,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    status.savingToGallery -> "Saving…"
                    status.savedToGallery -> "Saved to Gallery!"
                    else -> "Save to Gallery"
                },
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }
        status.galleryMessage?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(message, color = BadgeRed, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onShareAll,
                enabled = !status.sharing,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF7043)),
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (status.sharing) "Preparing…" else "Share All",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
            OutlinedButton(
                onClick = onNewConversion,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, secondaryText.copy(alpha = 0.4f)),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = secondaryText, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("New", color = secondaryText, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            }
        }
        status.shareMessage?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(message, color = BadgeRed, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun JpgResultThumbnail(
    file: File,
    index: Int,
    decodeSlots: Semaphore,
) {
    var bitmap by remember(file.absolutePath) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(file.absolutePath) { mutableStateOf(false) }

    LaunchedEffect(file.absolutePath) {
        var pending: Bitmap? = null
        try {
            val decoded = withContext(Dispatchers.IO) {
                decodeSlots.withPermit {
                    decodeJpgResultThumbnail(file).getOrThrow().also { pending = it }
                }
            }
            ensureActive()
            bitmap = decoded
            pending = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            pending?.let { BitmapOwnership.retire(listOf(it)) }
        }
    }

    DisposableEffect(bitmap) {
        onDispose { bitmap?.let { BitmapOwnership.retire(listOf(it)) } }
    }

    Box(
        Modifier.aspectRatio(0.75f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1E1E2E)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                checkNotNull(bitmap).asImageBitmap(),
                contentDescription = "Converted page ${index + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            failed -> Icon(
                Icons.Default.BrokenImage,
                contentDescription = "Preview unavailable",
                tint = Color(0xFF9999BB),
                modifier = Modifier.size(28.dp),
            )
            else -> CircularProgressIndicator(
                color = AccentBlue,
                strokeWidth = 2.dp,
                modifier = Modifier.size(24.dp),
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color(0xAA000000))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            Text(
                "Page ${index + 1}",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
