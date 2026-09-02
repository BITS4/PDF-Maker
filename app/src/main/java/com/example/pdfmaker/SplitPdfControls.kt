package com.example.pdfmaker

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SpModeChip(
    label: String,
    sel: Boolean,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(if (sel) AccentBlue else currentCard, label = "background")
    val text by animateColorAsState(if (sel) Color.White else currentTextSecond, label = "text")
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = text,
            fontSize = 13.sp,
            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
internal fun SpSpinner(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = currentTextSecond, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SpinnerButton(
                enabled = value > min,
                icon = { tint -> Icon(Icons.Default.Remove, null, tint = tint, modifier = Modifier.size(16.dp)) },
                onClick = { onChange(value - 1) },
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(currentCard)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("$value", color = currentText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            SpinnerButton(
                enabled = value < max,
                icon = { tint -> Icon(Icons.Default.Add, null, tint = tint, modifier = Modifier.size(16.dp)) },
                onClick = { onChange(value + 1) },
            )
        }
    }
}

@Composable
private fun SpinnerButton(
    enabled: Boolean,
    icon: @Composable (Color) -> Unit,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (enabled) currentCard else currentCard.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        icon(if (enabled) currentText else currentTextSecond)
    }
}
