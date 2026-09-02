package com.example.pdfmaker

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background by
        animateColorAsState(
            if (selected) AccentBlue else currentToolIcon,
            label = "tabBg",
        )
    val textColor by
        animateColorAsState(
            if (selected) Color.White else currentTextSecond,
            label = "tabTxt",
        )
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(background)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
fun FilterTypeChip(
    filter: FileTypeFilter,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val (icon, tint, selectedBackground) = remember(filter) { filterStyle(filter) }
    val background by
        animateColorAsState(
            if (selected) selectedBackground else currentToolIcon,
            label = "filterBg",
        )
    val iconColor by
        animateColorAsState(
            if (selected) tint else currentTextSecond,
            label = "filterIc",
        )
    val textColor by
        animateColorAsState(
            if (selected) tint else currentTextSecond,
            label = "filterTxt",
        )

    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(background)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, null, tint = iconColor, modifier = Modifier.size(15.dp))
        Text(
            text = filter.label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
fun MoreMenuItem(
    icon: ImageVector,
    label: String,
    tint: Color = currentText,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = tint, fontSize = 15.sp)
    }
}

private fun filterStyle(filter: FileTypeFilter): Triple<ImageVector, Color, Color> =
    when (filter) {
        FileTypeFilter.ALL -> {
            Triple(Icons.Default.Apps, Color(0xFF4F8EF7), Color(0xFF1A2340))
        }

        FileTypeFilter.PDF -> {
            Triple(Icons.Default.PictureAsPdf, Color(0xFFEF5350), Color(0xFF2A1010))
        }

        FileTypeFilter.DOCS -> {
            Triple(Icons.Default.Description, Color(0xFF4F8EF7), Color(0xFF1A2340))
        }

        FileTypeFilter.SHEETS -> {
            Triple(Icons.Default.TableChart, Color(0xFF26C6A0), Color(0xFF0F2420))
        }

        FileTypeFilter.SLIDES -> {
            Triple(Icons.Default.Slideshow, Color(0xFFFFA726), Color(0xFF2A1E0A))
        }

        FileTypeFilter.TEXT -> {
            Triple(
                Icons.AutoMirrored.Filled.TextSnippet,
                Color(0xFF9C6DFF),
                Color(0xFF1E1530),
            )
        }

        FileTypeFilter.IMAGES -> {
            Triple(Icons.Default.Image, Color(0xFF26C6A0), Color(0xFF0F2420))
        }
    }
