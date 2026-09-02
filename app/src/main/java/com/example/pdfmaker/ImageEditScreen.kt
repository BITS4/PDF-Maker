package com.example.pdfmaker

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageEditScreen(
    editStates  : List<ImageEditState>,
    initialIndex: Int = 0,
    onCrop  : (index: Int) -> Unit,
    onDone  : () -> Unit,
    onBack  : () -> Unit,
    onDelete: (index: Int) -> Unit
) {
    val context    = LocalContext.current
    val scope      = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (editStates.size - 1).coerceAtLeast(0))
    ) { editStates.size }

    var showAdjust by remember { mutableStateOf(false) }
    var adjustTab by remember { mutableStateOf(0) }
    val renderMutex = remember { Mutex() }
    val renderVersions = remember { mutableMapOf<ImageEditState, Int>() }

    val currentState = editStates.getOrNull(pagerState.currentPage)
    val isIdCard = ImageToPdfState.isIdCardScan
    val allImagesReady = editStates.isNotEmpty() && editStates.all {
        it.originalBitmap != null && it.loadError == null && !it.isRendering
    }

    fun requestRender(editState: ImageEditState, debounceMillis: Long = 0L) {
        val version = (renderVersions[editState] ?: 0) + 1
        renderVersions[editState] = version
        editState.isRendering = true
        scope.launch {
            var rendered: ImageRenderResult? = null
            var renderSource: Bitmap? = null
            try {
                if (debounceMillis > 0L) delay(debounceMillis)
                renderMutex.withLock {
                    if (renderVersions[editState] != version) return@withLock
                    val request = editState.renderRequest() ?: return@withLock
                    renderSource = request.source
                    rendered = withContext(Dispatchers.Default) {
                        ImageProcessing.render(request)
                    }
                    currentCoroutineContext().ensureActive()
                    if (
                        renderVersions[editState] == version &&
                        editState.originalBitmap === request.source
                    ) {
                        BitmapOwnership.retire(editState.installRender(requireNotNull(rendered)))
                        rendered = null
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (renderVersions[editState] == version) {
                    editState.loadError = "This image could not be processed."
                }
            } finally {
                val abandoned = rendered
                val source = renderSource
                if (abandoned != null && source != null) {
                    BitmapOwnership.retire(abandoned.generatedBitmaps(source))
                }
                if (renderVersions[editState] == version) editState.isRendering = false
            }
        }
    }

    // ── Load bitmaps ──────────────────────────────────────────────────────────
    // Always apply EXIF rotation for both Docs and ID card.
    // Camera sensors are physically landscape — raw pixels are always sideways.
    // The EXIF orientation tag is the ONLY thing that encodes how the phone
    // was held. Ignoring it always shows photos rotated regardless of mode.
    LaunchedEffect(editStates.map(ImageEditState::uri), initialIndex) {
        val first = editStates.getOrNull(initialIndex)
        val orderedStates = listOfNotNull(first) + editStates.filterNot { it === first }
        orderedStates.forEach { editState ->
            if (editState.originalBitmap != null) return@forEach
            val generation = renderVersions[editState] ?: 0
            renderMutex.withLock {
                if (editState.originalBitmap != null) return@withLock
                var decoded: Bitmap? = null
                var rendered: ImageRenderResult? = null
                var renderSource: Bitmap? = null
                editState.isRendering = true
                try {
                    decoded = withContext(Dispatchers.IO) {
                        BoundedImageDecoder.decode(context, editState.uri).getOrThrow()
                    }
                    currentCoroutineContext().ensureActive()
                    BitmapOwnership.retire(editState.installSource(requireNotNull(decoded)))
                    decoded = null

                    val request = requireNotNull(editState.renderRequest())
                    renderSource = request.source
                    rendered = withContext(Dispatchers.Default) {
                        ImageProcessing.render(request)
                    }
                    currentCoroutineContext().ensureActive()
                    if (renderVersions[editState] == generation) {
                        BitmapOwnership.retire(editState.installRender(requireNotNull(rendered)))
                        rendered = null
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    editState.loadError = "This image could not be loaded safely."
                } finally {
                    decoded?.let { BitmapOwnership.retire(listOf(it)) }
                    val abandoned = rendered
                    val source = renderSource
                    if (abandoned != null && source != null) {
                        BitmapOwnership.retire(abandoned.generatedBitmaps(source))
                    }
                    if (renderVersions[editState] == generation) editState.isRendering = false
                }
            }
        }
    }

    // ── Filter thumbnails ─────────────────────────────────────────────────────
    val filterThumbs = remember { mutableStateMapOf<ImageFilter, Bitmap>() }
    val currentBitmap = currentState?.originalBitmap
    LaunchedEffect(pagerState.currentPage, currentBitmap) {
        BitmapOwnership.retire(filterThumbs.values.toList())
        filterThumbs.clear()
        val base = currentBitmap?.takeUnless { it.isRecycled } ?: return@LaunchedEffect
        ImageFilter.entries.forEach { filter ->
            var thumbnail: Bitmap? = null
            try {
                thumbnail = withContext(Dispatchers.Default) {
                    ImageProcessing.filterThumbnail(base, filter, 80)
                }
                currentCoroutineContext().ensureActive()
                filterThumbs.put(filter, requireNotNull(thumbnail))?.let {
                    BitmapOwnership.retire(listOf(it))
                }
                thumbnail = null
            } finally {
                thumbnail?.let { BitmapOwnership.retire(listOf(it)) }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { BitmapOwnership.retire(filterThumbs.values.toList()) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {

            // ── Top bar ───────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White,
                    modifier = Modifier.size(26.dp).clickable { onBack() })
                Spacer(Modifier.width(10.dp))
                val title = if (isIdCard) {
                    when (pagerState.currentPage) { 0 -> "Front side"; 1 -> "Back side"; else -> "Page ${pagerState.currentPage + 1}" }
                } else {
                    currentState?.uri?.lastPathSegment?.substringAfterLast('/') ?: ""
                }
                Text(title, color = Color.White, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f), maxLines = 1)
                IconButton(onClick = { onCrop(pagerState.currentPage) }) {
                    Icon(Icons.Default.Crop, "Crop", tint = Color.White)
                }
                IconButton(onClick = { onDelete(pagerState.currentPage) }) {
                    Icon(Icons.Default.Delete, "Delete", tint = Color.White)
                }
            }

            // ── Image pager ───────────────────────────────────────────────────
            HorizontalPager(
                state    = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) { page ->
                val es  = editStates.getOrNull(page)
                val bmp = es?.finalBitmap ?: es?.originalBitmap
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (es?.loadError != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.BrokenImage, null, tint = BadgeRed)
                            Spacer(Modifier.height(8.dp))
                            Text(es.loadError.orEmpty(), color = Color.White, fontSize = 13.sp)
                        }
                    } else if (bmp != null) {
                        Image(bmp.asImageBitmap(), null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize())
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = AccentBlue)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (isIdCard) "Processing card…" else "Loading…",
                                color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // ── Page counter ──────────────────────────────────────────────────
            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                contentAlignment = Alignment.Center) {
                Box(Modifier.background(Color(0xAA111122), RoundedCornerShape(16.dp))
                    .padding(horizontal = 18.dp, vertical = 4.dp)) {
                    val label = if (isIdCard && editStates.size == 2) {
                        when (pagerState.currentPage) { 0 -> "Front  1/2"; else -> "Back  2/2" }
                    } else "${pagerState.currentPage + 1} / ${editStates.size}"
                    Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // ── Filter strip ──────────────────────────────────────────────────
            LazyRow(
                modifier = Modifier.fillMaxWidth().height(110.dp)
                    .background(Color(0xFF0D0D14)).padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(ImageFilter.entries) { filter ->
                    val thumb = filterThumbs[filter]
                    val isSel = currentState?.filter == filter
                    Column(
                        modifier = Modifier.width(72.dp).clickable {
                            currentState?.let { es ->
                                es.filter = filter
                                requestRender(es)
                            }
                        },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(6.dp))
                                .border(if (isSel) 2.dp else 0.dp,
                                    if (isSel) AccentBlue else Color.Transparent,
                                    RoundedCornerShape(6.dp))
                                .background(Color(0xFF1C1C2E))
                        ) {
                            if (thumb != null)
                                Image(thumb.asImageBitmap(), null, contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize())
                            else
                                Box(Modifier.fillMaxSize().background(Color(0xFF2A2A3E)))
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(filter.label, color = if (isSel) AccentBlue else TextSecond,
                            fontSize = 10.sp, textAlign = TextAlign.Center)
                    }
                }
            }

            // ── Bottom toolbar ────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF0D0D14))
                    .navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EditControlBtn(Icons.AutoMirrored.Filled.RotateLeft, "Left") {
                    currentState?.let { es ->
                        es.totalRotation -= 90f
                        requestRender(es)
                    }
                }
                Spacer(Modifier.width(20.dp))
                EditControlBtn(Icons.AutoMirrored.Filled.RotateRight, "Right") {
                    currentState?.let { es ->
                        es.totalRotation += 90f
                        requestRender(es)
                    }
                }
                Spacer(Modifier.width(20.dp))
                EditControlBtn(Icons.Default.Tune, "Adjust",
                    tint = if (showAdjust) AccentBlue else Color.White
                ) { showAdjust = !showAdjust }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onDone,
                    enabled = allImagesReady,
                    colors  = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    shape   = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp)
                ) {
                    Text("DONE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }

        if (showAdjust && currentState != null) {
            AdjustPanel(
                editState   = currentState,
                activeTab   = adjustTab,
                onTabChange = { adjustTab = it },
                onApply     = { showAdjust = false },
                onCancel    = {
                    currentState.brightness = 0f; currentState.contrast = 0f; currentState.details = 0f
                    requestRender(currentState)
                    showAdjust = false
                },
                onAdjustmentChange = { requestRender(currentState, debounceMillis = 120L) }
            )
        }
    }
}

// ── Adjust panel ──────────────────────────────────────────────────────────────

@Composable
fun BoxScope.AdjustPanel(
    editState  : ImageEditState,
    activeTab  : Int,
    onTabChange: (Int) -> Unit,
    onApply    : () -> Unit,
    onCancel   : () -> Unit,
    onAdjustmentChange: () -> Unit,
) {
    val tabs = listOf(
        Triple("Contrast",   Icons.Default.Contrast,    0),
        Triple("Brightness", Icons.Default.WbSunny,     1),
        Triple("Details",    Icons.Default.AutoAwesome, 2)
    )
    var sliderVal by remember(activeTab) {
        mutableFloatStateOf(when (activeTab) { 0 -> editState.contrast; 1 -> editState.brightness; else -> editState.details })
    }

    Column(
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Color(0xFF111122), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .navigationBarsPadding().padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            tabs.forEach { (label, icon, idx) ->
                Column(Modifier.clickable { onTabChange(idx) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(50.dp).clip(CircleShape)
                        .background(if (idx == activeTab) AccentBlue else Color(0xFF252535)),
                        contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(label, color = if (idx == activeTab) AccentBlue else TextSecond, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = sliderVal,
                onValueChange = { v ->
                    sliderVal = v
                    when (activeTab) { 0 -> editState.contrast = v; 1 -> editState.brightness = v; 2 -> editState.details = v }
                    onAdjustmentChange()
                },
                valueRange = if (activeTab == 2) 0f..100f else -100f..100f,
                modifier   = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = AccentBlue, activeTrackColor = AccentBlue)
            )
            Spacer(Modifier.width(10.dp))
            Text(sliderVal.toInt().toString(), color = Color.White, fontSize = 16.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) { Icon(Icons.Default.Close, null, tint = Color.White) }
            Text("Adjust", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onApply)  { Icon(Icons.Default.Check, null, tint = AccentBlue) }
        }
    }
}

@Composable
fun EditControlBtn(
    icon   : androidx.compose.ui.graphics.vector.ImageVector,
    label  : String,
    tint   : Color = Color.White,
    onClick: () -> Unit
) {
    Column(Modifier.clickable { onClick() }, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = tint, fontSize = 11.sp)
    }
}
