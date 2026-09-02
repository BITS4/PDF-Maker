package com.example.pdfmaker

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Data ───────────────────────────────────────────────────────────────────────

data class MergeItem(
    val id: String =
        java.util.UUID
            .randomUUID()
            .toString(),
    val uri: Uri,
    val name: String,
    val sizeKb: Long,
    val pageCount: Int,
    val thumb: Bitmap?, // first-page thumbnail
)

internal enum class MergeState { EMPTY, READY, MERGING, DONE, ERROR }

// ── Merge item card with up/down arrows + delete ───────────────────────────────

@Composable
// The merge list owns ordering and colors; explicit inputs keep this reusable card
// stateless and make every reorder/delete event observable by that owner.
@Suppress("LongParameterList")
internal fun MergeItemCard(
    item: MergeItem,
    index: Int,
    total: Int,
    isDragging: Boolean,
    cardBg: Color,
    textPri: Color,
    textSec: Color,
    accent: Color,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val elevation by animateDpAsState(if (isDragging) 12.dp else 0.dp, label = "elev")
    val scale by animateFloatAsState(if (isDragging) 1.03f else 1f, label = "scale")

    Row(
        Modifier
            .fillMaxWidth()
            .scale(scale)
            .shadow(elevation, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(if (isDragging) Color(0xFF1E2035) else cardBg)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Order number + thumbnail
        Box(contentAlignment = Alignment.TopStart) {
            Box(
                Modifier
                    .size(width = 52.dp, height = 68.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E1E2E)),
                contentAlignment = Alignment.Center,
            ) {
                if (item.thumb != null) {
                    Image(
                        item.thumb.asImageBitmap(),
                        null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Icon(
                        Icons.Default.PictureAsPdf,
                        null,
                        tint = Color(0xFFE53935),
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            // Index badge
            Box(
                Modifier
                    .offset(x = (-4).dp, y = (-4).dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${index + 1}",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // File info
        Column(Modifier.weight(1f)) {
            Text(
                "${item.name}.pdf",
                color = textPri,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "${item.pageCount} pages · ${mergeFormatSize(item.sizeKb)}",
                color = textSec,
                fontSize = 11.sp,
            )
        }

        // Up / Down / Delete
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            IconButton(
                onClick = onMoveUp,
                modifier = Modifier.size(32.dp),
                enabled = index > 0,
            ) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    null,
                    tint = if (index > 0) accent else textSec.copy(alpha = 0.3f),
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(
                onClick = onMoveDown,
                modifier = Modifier.size(32.dp),
                enabled = index < total - 1,
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    null,
                    tint = if (index < total - 1) accent else textSec.copy(alpha = 0.3f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.DeleteOutline,
                null,
                tint = Color(0xFFEF5350),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ── Small helpers ─────────────────────────────────────────────────────────────

@Composable
internal fun InfoChip(
    text: String,
    bg: Color,
    textColor: Color,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = textColor, fontSize = 12.sp)
    }
}

@Composable
internal fun StatColumn(
    label: String,
    value: String,
    labelColor: Color,
    valueColor: Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = labelColor, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, color = valueColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun MergeSpinner(
    progress: Int,
    color: Color,
) {
    val inf = rememberInfiniteTransition(label = "spin")
    val angle by inf.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "angle",
    )
    Canvas(Modifier.size(110.dp)) {
        drawArc(
            Color(0xFF2A2A40),
            0f,
            360f,
            false,
            style = Stroke(10.dp.toPx(), cap = StrokeCap.Round),
        )
        drawArc(
            color,
            angle - 90f,
            (progress * 3.6f).coerceAtLeast(10f),
            false,
            style = Stroke(10.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
internal fun MergeRenameDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E2E),
        title = {
            Text(
                text = "Output file name",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                placeholder = { Text("merged_document", color = Color(0xFF9999BB)) },
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = Color(0xFF444455),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = AccentBlue,
                    ),
                suffix = { Text(".pdf", color = Color(0xFF9999BB)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val normalizedName = draft.trim()
                    if (normalizedName.isNotEmpty()) onConfirm(normalizedName)
                },
            ) {
                Text("OK", color = AccentBlue, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF9999BB))
            }
        },
    )
}

@Composable
internal fun MergePreflightDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(initialName) { mutableStateOf(initialName) }
    val orange = Color(0xFFFF7043)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E2E),
        title = {
            Text(
                text = "Name your merged PDF",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column {
                Text(
                    text = "You can rename the output file before merging.",
                    color = Color(0xFF9999BB),
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    placeholder = { Text("merged_document", color = Color(0xFF9999BB)) },
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = Color(0xFF444455),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = AccentBlue,
                        ),
                    suffix = { Text(".pdf", color = Color(0xFF9999BB)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val normalizedName = draft.trim()
                    if (normalizedName.isNotEmpty()) onConfirm(normalizedName)
                },
                colors = ButtonDefaults.buttonColors(containerColor = orange),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.MergeType, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Merge", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF9999BB))
            }
        },
    )
}

@Composable
internal fun MergeEmptyPanel(onSelectFiles: () -> Unit) {
    val orange = Color(0xFFFF7043)
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E1E30))
                    .border(2.dp, orange.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MergeType,
                contentDescription = null,
                tint = orange,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.height(22.dp))
        Text(
            text = "Merge PDFs",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Select 2 or more PDF files to combine into one.\nLong-press to reorder pages.",
            color = Color(0xFF9999BB),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(36.dp))
        Button(
            onClick = onSelectFiles,
            modifier = Modifier.fillMaxWidth(0.75f).height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = orange),
        ) {
            Icon(Icons.Default.FileOpen, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Select PDFs", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}
