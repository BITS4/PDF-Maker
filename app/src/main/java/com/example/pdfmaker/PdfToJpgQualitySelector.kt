package com.example.pdfmaker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun JpgQualitySelector(
    selected: JpgQuality,
    cardBackground: Color,
    primaryText: Color,
    secondaryText: Color,
    accent: Color,
    onSelected: (JpgQuality) -> Unit,
) {
    Text(
        "Quality",
        color = primaryText,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        JpgQuality.entries.forEach { quality ->
            val isSelected = quality == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) Color(0xFF1B2340) else cardBackground)
                    .border(
                        if (isSelected) 1.5.dp else 0.dp,
                        if (isSelected) accent else Color.Transparent,
                        RoundedCornerShape(12.dp),
                    ).clickable { onSelected(quality) }
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(quality.color.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = null,
                        tint = quality.color,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    quality.label,
                    color = if (isSelected) primaryText else secondaryText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    quality.sub,
                    color = secondaryText,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 12.sp,
                )
            }
        }
    }
}
