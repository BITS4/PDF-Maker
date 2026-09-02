package com.example.pdfmaker

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun PageManagerPageGrid(
    pages: List<PageState>,
    onRotateClockwise: (Int) -> Unit,
    onRotateCounterClockwise: (Int) -> Unit,
    onToggleDeleted: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(pages) { index, page ->
            PageManagerPageCard(
                page = page,
                pageNumber = index + 1,
                onRotateClockwise = { onRotateClockwise(index) },
                onRotateCounterClockwise = { onRotateCounterClockwise(index) },
                onToggleDeleted = { onToggleDeleted(index) },
            )
        }
    }
}

@Composable
internal fun PageManagerHints() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageManagerHint(Icons.AutoMirrored.Filled.RotateRight, "Rotate", Color(0xFF4F8EF7))
        PageManagerHint(Icons.Default.Delete, "Delete", BadgeRed)
    }
}

@Composable
private fun PageManagerPageCard(
    page: PageState,
    pageNumber: Int,
    onRotateClockwise: () -> Unit,
    onRotateCounterClockwise: () -> Unit,
    onToggleDeleted: () -> Unit,
) {
    val overlayColor by animateColorAsState(
        targetValue = if (page.deleted) BadgeRed.copy(alpha = 0.55f) else Color.Transparent,
        label = "deleted-page",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.aspectRatio(0.77f).clip(RoundedCornerShape(8.dp)).background(currentCard)) {
            Image(
                bitmap = page.bitmap.asImageBitmap(),
                contentDescription = "Page $pageNumber",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().rotate(page.rotation.toFloat()),
            )
            if (page.deleted) {
                Box(
                    modifier = Modifier.fillMaxSize().background(overlayColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Delete, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
            if (page.rotation != 0) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .clip(CircleShape)
                            .background(AccentBlue.copy(alpha = 0.9f))
                            .padding(4.dp),
                ) {
                    Text("${page.rotation}°", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text("$pageNumber", color = currentTextSecond, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PageManagerMiniAction(
                icon = Icons.AutoMirrored.Filled.RotateLeft,
                description = "Rotate counterclockwise",
                tint = Color(0xFF4F8EF7),
                onClick = onRotateCounterClockwise,
            )
            PageManagerMiniAction(
                icon = Icons.AutoMirrored.Filled.RotateRight,
                description = "Rotate clockwise",
                tint = Color(0xFF4F8EF7),
                onClick = onRotateClockwise,
            )
            PageManagerMiniAction(
                icon = if (page.deleted) Icons.Default.Refresh else Icons.Default.Delete,
                description = if (page.deleted) "Restore page" else "Delete page",
                tint = if (page.deleted) Color(0xFF26C6A0) else BadgeRed,
                onClick = onToggleDeleted,
            )
        }
    }
}

@Composable
private fun PageManagerMiniAction(
    icon: ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(currentCard.copy(alpha = 0.7f))
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun PageManagerHint(
    icon: ImageVector,
    label: String,
    tint: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        Text(label, color = currentTextSecond, fontSize = 11.sp)
    }
}
