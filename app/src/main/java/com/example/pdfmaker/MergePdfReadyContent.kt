package com.example.pdfmaker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ColumnScope.MergePdfReadyContent(
    items: List<MergeItem>,
    outputName: String,
    summary: MergeSummary,
    colors: MergePdfColors,
    callbacks: MergePdfCallbacks,
) {
    MergeOutputNameRow(outputName, colors, callbacks.onRename)
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InfoChip("${summary.fileCount} files", colors.card, colors.secondaryText)
        InfoChip("${summary.pageCount} pages", colors.card, colors.secondaryText)
        InfoChip(mergeFormatSize(summary.sizeKb), colors.card, colors.secondaryText)
    }
    Spacer(Modifier.height(8.dp))
    LazyColumn(
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            MergeItemCard(
                item = item,
                index = index,
                total = items.size,
                isDragging = false,
                cardBg = colors.card,
                textPri = colors.primaryText,
                textSec = colors.secondaryText,
                accent = colors.accent,
                onDelete = { callbacks.onRemove(index) },
                onMoveUp = { callbacks.onMove(index, MergeItemMove.UP) },
                onMoveDown = { callbacks.onMove(index, MergeItemMove.DOWN) },
            )
        }
        item {
            AddMergeInputButton(colors, callbacks.onSelectFiles)
            Spacer(Modifier.height(4.dp))
        }
    }
    MergeActionBar(
        fileCount = items.size,
        colors = colors,
        onMerge = callbacks.onMerge,
    )
}

@Composable
private fun MergeOutputNameRow(
    outputName: String,
    colors: MergePdfColors,
    onRename: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.DriveFileRenameOutline,
            contentDescription = null,
            tint = colors.secondaryText,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$outputName.pdf",
            color = colors.primaryText,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onRename) {
            Text("Rename", color = colors.accent, fontSize = 13.sp)
        }
    }
}

@Composable
private fun AddMergeInputButton(
    colors: MergePdfColors,
    onSelectFiles: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.card, RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFF333344), RoundedCornerShape(12.dp))
                .clickable(onClick = onSelectFiles)
                .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Add more PDFs",
                color = colors.accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun MergeActionBar(
    fileCount: Int,
    colors: MergePdfColors,
    onMerge: () -> Unit,
) {
    val canMerge = MergeScreenPolicy.canMerge(fileCount)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.bar)
                .navigationBarsPadding()
                .padding(16.dp),
    ) {
        Button(
            onClick = onMerge,
            enabled = canMerge,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.action),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MergeType,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (canMerge) "Merge $fileCount PDFs" else "Add at least 2 PDFs",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }
    }
}
