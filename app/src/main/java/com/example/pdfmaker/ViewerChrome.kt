package com.example.pdfmaker

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
internal fun ViewerTopBar(
    visible: Boolean,
    fileName: String,
    kind: ViewerFileKind,
    loadedCount: Int,
    isLoading: Boolean,
    onBack: () -> Unit,
    onShare: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xCC121218))
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewerIconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            ViewerFileKindBadge(kind)
            Spacer(Modifier.width(8.dp))
            Text(
                text = fileName,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isLoading && loadedCount > 0) {
                Text("$loadedCount pages…", color = TextSecond, fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
            }
            ViewerIconButton(onClick = onShare) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = "Share",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
internal fun ViewerBottomBar(
    visible: Boolean,
    pages: List<Bitmap>,
    currentPage: Int,
    totalPages: Int,
    isLoading: Boolean,
    listState: LazyListState,
) {
    val scope = rememberCoroutineScope()
    val thumbnailListState = rememberLazyListState()

    LaunchedEffect(currentPage, pages.size) {
        if (pages.size > 1) {
            thumbnailListState.animateScrollToItem((currentPage - 1).coerceIn(0, pages.lastIndex))
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xEE0D0D14))
                    .navigationBarsPadding(),
            ) {
                if (pages.size > 1) {
                    LazyRow(
                        state = thumbnailListState,
                        modifier = Modifier.fillMaxWidth().height(72.dp).padding(vertical = 6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(pages) { index, bitmap ->
                            ViewerThumbnail(
                                bitmap = bitmap,
                                page = index + 1,
                                active = index + 1 == currentPage,
                                onClick = { scope.launch { listState.animateScrollToItem(index) } },
                            )
                        }
                    }
                    HorizontalDivider(color = Color(0xFF2A2A3A), thickness = 0.5.dp)
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(
                        onClick = {
                            scope.launch { listState.animateScrollToItem(currentPage - 2) }
                        },
                        enabled = currentPage > 1,
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            contentDescription = "Previous page",
                            tint = if (currentPage > 1) Color.White else TextSecond,
                        )
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(BgToolIcon)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        val label = if (isLoading) "$currentPage / $totalPages+" else "$currentPage / $totalPages"
                        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    IconButton(
                        onClick = { scope.launch { listState.animateScrollToItem(currentPage) } },
                        enabled = currentPage < pages.size,
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Next page",
                            tint = if (currentPage < pages.size) Color.White else TextSecond,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerThumbnail(
    bitmap: Bitmap,
    page: Int,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .width(46.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) AccentBlue.copy(alpha = 0.25f) else Color(0xFF1E1E2E))
            .then(if (active) Modifier.border(2.dp, AccentBlue, RoundedCornerShape(6.dp)) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = "Page $page thumbnail",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(2.dp),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xAA000000))
                .padding(vertical = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$page",
                color = if (active) AccentBlue else Color.White,
                fontSize = 8.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun ViewerIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(BgToolIcon)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun ViewerFileKindBadge(kind: ViewerFileKind) {
    val (label, color) =
        when (kind) {
            ViewerFileKind.PDF -> "PDF" to Color(0xFFE53935)
            ViewerFileKind.DOCX -> "DOCX" to Color(0xFF1565C0)
            ViewerFileKind.XLSX -> "XLSX" to Color(0xFF2E7D32)
            ViewerFileKind.PPTX -> "PPTX" to Color(0xFFE65100)
            ViewerFileKind.CSV -> "CSV" to Color(0xFF6A1B9A)
            ViewerFileKind.TXT -> "TXT" to Color(0xFF37474F)
            ViewerFileKind.IMAGE -> "IMG" to Color(0xFF00838F)
            ViewerFileKind.UNSUPPORTED -> "?" to Color(0xFF555566)
        }
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
