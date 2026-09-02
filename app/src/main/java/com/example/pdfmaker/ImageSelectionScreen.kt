package com.example.pdfmaker

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── Device image model ─────────────────────────────────────────────────────────

private data class DeviceImage(val id: Long, val uri: Uri)

private suspend fun loadDeviceImages(context: Context): List<DeviceImage> =
    withContext(Dispatchers.IO) {
        val list       = mutableListOf<DeviceImage>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder  = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(
                collection, projection, null, null, sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext() && list.size < ImageInputPolicy.MAX_GALLERY_ITEMS) {
                    val id  = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    list += DeviceImage(id, uri)
                }
            }
        } catch (_: SecurityException) {
            // The system picker below remains available when media access is not granted.
        } catch (_: IllegalArgumentException) {
            // Some document providers do not expose a MediaStore-compatible collection.
        }
        list
    }

// ── Screen ─────────────────────────────────────────────────────────────────────

@Composable
fun ImageSelectionScreen(
    preSelected: List<Uri> = emptyList(),
    onImport   : (List<Uri>) -> Unit,
    onBack     : () -> Unit
) {
    val context   = LocalContext.current
    var selected by remember(preSelected) {
        mutableStateOf(ImageInputPolicy.mergeDistinct(emptyList(), preSelected).items)
    }
    var allImages by remember { mutableStateOf<List<DeviceImage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        allImages = loadDeviceImages(context)
        isLoading = false
    }

    // Fallback picker — also allows adding extra images when gallery is shown
    fun addToSelection(candidates: List<Uri>) {
        val result = ImageInputPolicy.mergeDistinct(selected, candidates)
        selected = result.items
        if (result.rejectedCount > 0) {
            Toast.makeText(
                context,
                "You can import up to ${ImageInputPolicy.MAX_SELECTED_IMAGES} images at once.",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) addToSelection(uris) }

    Column(Modifier.fillMaxSize().background(currentBg)) {

        // ── Top bar ──────────────────────────────────────────────────────────
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
                if (selected.isEmpty()) "Select Images"
                else "Selected (${selected.size})",
                color      = currentText,
                fontSize   = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier   = Modifier.weight(1f).padding(start = 4.dp)
            )
            if (selected.isNotEmpty()) {
                TextButton(onClick = { onImport(selected) }) {
                    Text(
                        "Next (${selected.size})",
                        color      = AccentBlue,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // ── Action strip ─────────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .background(currentCard)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { picker.launch("image/*") },
                shape   = RoundedCornerShape(10.dp),
                border  = androidx.compose.foundation.BorderStroke(1.dp, AccentBlue),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.AddPhotoAlternate, null, tint = AccentBlue,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open Gallery", color = AccentBlue, fontSize = 13.sp)
            }
            if (selected.isNotEmpty()) {
                TextButton(onClick = { selected = emptyList() }) {
                    Text("Clear All", color = BadgeRed, fontSize = 13.sp)
                }
            }
        }

        // ── Content ──────────────────────────────────────────────────────────
        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentBlue)
            }

            allImages.isEmpty() -> Box(
                Modifier.fillMaxSize(), contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.PhotoLibrary, null,
                        tint     = currentTextSecond,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("No images found on device", color = currentTextSecond, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap Open Gallery to pick images manually",
                        color    = currentTextSecond,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { picker.launch("image/*") },
                        colors  = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        shape   = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, null,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open Gallery")
                    }
                }
            }

            else -> LazyVerticalGrid(
                columns        = GridCells.Fixed(3),
                contentPadding = PaddingValues(2.dp),
                modifier       = Modifier.fillMaxSize()
            ) {
                items(allImages, key = { it.id }) { img ->
                    val isSelected = img.uri in selected
                    Box(
                        Modifier
                            .padding(1.dp)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                if (isSelected) {
                                    selected = selected.filterNot { it == img.uri }
                                } else {
                                    addToSelection(listOf(img.uri))
                                }
                            }
                    ) {
                        AsyncImage(
                            model              = img.uri,
                            contentDescription = null,
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier.fillMaxSize()
                        )
                        // Selection tint
                        if (isSelected) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(AccentBlue.copy(alpha = 0.35f))
                            )
                        }
                        // Checkmark badge
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) AccentBlue
                                    else Color(0x88000000)
                                )
                                .then(
                                    if (!isSelected) Modifier.border(1.dp, Color.White, CircleShape)
                                    else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check, null,
                                    tint     = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
